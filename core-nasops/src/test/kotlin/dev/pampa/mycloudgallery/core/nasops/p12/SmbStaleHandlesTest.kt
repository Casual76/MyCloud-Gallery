package dev.pampa.mycloudgallery.core.nasops.p12

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.nas.breaker.AuthBreakerRegistry
import dev.pampa.mycloudgallery.core.nas.contract.CredentialLoad
import dev.pampa.mycloudgallery.core.nas.contract.InvalidateReason
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.NasFileOpsFactory
import dev.pampa.mycloudgallery.core.nas.session.SmbNasSessionManager
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryBreakerStore
import dev.pampa.mycloudgallery.core.nas.testing.InMemorySmbBackend
import dev.pampa.mycloudgallery.core.nas.testing.ManualMonotonicClock
import dev.pampa.mycloudgallery.core.nas.testing.SimAccess
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.nasops.contract.NasItem
import dev.pampa.mycloudgallery.core.nasops.contract.OpClocks
import dev.pampa.mycloudgallery.core.nasops.contract.OpItemState
import dev.pampa.mycloudgallery.core.nasops.contract.OpRequest
import dev.pampa.mycloudgallery.core.nasops.contract.OpState
import dev.pampa.mycloudgallery.core.nasops.contract.PlanOutcome
import dev.pampa.mycloudgallery.core.nasops.engine.NasOpsEngine
import dev.pampa.mycloudgallery.core.nasops.testing.FakeOpsGate
import dev.pampa.mycloudgallery.core.nasops.testing.InMemoryOpsStore
import dev.pampa.mycloudgallery.core.nasops.testing.TestConfirmations
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.Via
import dev.pampa.mycloudgallery.core.network.testing.FakeNasRouteGate
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test

/**
 * L'esecutore vero sopra la sessione SMB di produzione e il simulatore che tiene gli **handle dei client** come Samba (prova F16 sul NAS
 * vero, 15/10). Due casi che prima lasciavano un'operazione confermata "in attesa del PC" senza un PC:
 *  - **una rete persa a meta' di una lettura**: il NAS tiene l'handle della connessione vecchia finche' non se ne accorge (minuti). La
 *    lettura condivide la cancellazione, quindi lo spostamento tra share arriva in fondo lo stesso: sorgente nel cestino dopo la verifica;
 *  - **le chiusure in volo**: l'elenco di una cartella (inventario prima del cestino) chiude gli handle su un altro thread; Samba non
 *    rinomina una cartella con un handle dello stesso client aperto sotto (`ACCESS_DENIED`, l'elemento falliva "permesso negato"). Le
 *    chiusure arrivano al NAS prima della richiesta successiva.
 */
class SmbStaleHandlesTest {

  private val device = Base32.encode(ByteArray(16) { 0x5a })
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val monotonic = ManualMonotonicClock(10_000_000L)
  private val backend = InMemorySmbBackend().apply {
    addUser(USER, PASSWORD)
    for (share in listOf("test", "test-comune")) addShare(share, mapOf(USER to SimAccess.READ_WRITE))
  }
  private val manager = SmbNasSessionManager(
    account = { NasAccount(NasAddress("192.168.178.58"), USER, "slot", backend.serverGuid) },
    routeGate = FakeNasRouteGate().apply { allow(Via.HOME_LAN, backend.binding("pc")) },
    credentials = { CredentialLoad.Found(NasCredentials(USER, PASSWORD.toCharArray())) },
    breakers = AuthBreakerRegistry(InMemoryBreakerStore(), scope),
    scope = scope,
    transport = backend,
    clock = monotonic,
    io = Dispatchers.IO,
    backoff = { },
  )
  private val reader = manager.mediaReader()
  private val clocks = object : OpClocks {
    override fun monotonicMs(): Long = monotonic.nowMillis()

    override fun nasNowMs(): Long? = manager.nasClock.nowMillisOrNull()
  }
  private val store = InMemoryOpsStore()
  private val engine = NasOpsEngine(
    deviceId = device,
    accountId = USER,
    fileOps = NasFileOpsFactory.create(manager, device),
    reader = reader,
    store = store,
    clocks = clocks,
    gate = FakeOpsGate(),
  )

  @After
  fun tearDown() {
    backend.beforeClose = null
    runBlocking { manager.invalidate(InvalidateReason.USER_REQUEST) }
    scope.cancel()
  }

  private fun bounded(block: suspend () -> Unit) = runBlocking { withTimeout(60_000) { block() } }

  private suspend fun item(share: String, path: String, folderBytes: Long = 0): NasItem {
    val entry = (reader.stat(share, SharePath.parse(path), Purpose.INTERACTIVE) as NasResult.Ok).value!!
    val stamp = if (entry.isDirectory) null else NasStamp(entry.size, entry.modifiedAt, entry.fileId)
    return NasItem(share, SharePath.parse(path), entry.isDirectory, if (entry.isDirectory) folderBytes else entry.size, stamp)
  }

  /** Piano, conferma, avvio; poi le riprese (al massimo [resumes]) finche' l'operazione non si chiude. */
  private suspend fun runToEnd(request: OpRequest, resumes: Int = 5): OpState {
    val plan = (engine.plan(request) as PlanOutcome.Ready).plan
    engine.start(plan, TestConfirmations.forPlan(plan, clocks.monotonicMs()))
    repeat(resumes) {
      val op = store.ops(OpState.entries.toSet()).single()
      if (op.state.terminal) return op.state
      engine.resumeAll(1.minutes)
    }
    return store.ops(OpState.entries.toSet()).single().state
  }

  @Test
  fun unaLetturaRimastaSulNasDopoUnaRetePersaNonFermaLoSpostamentoTraShare() = bounded {
    val bytes = ByteArray(300_000) { (it * 31).toByte() }
    backend.put("test", "Foto/a.jpg", bytes)
    val source = item("test", "Foto/a.jpg")
    // Il telefono legge la foto (la Libreria, una verifica) e perde la rete: il NAS tiene la sessione vecchia con l'handle aperto.
    reader.open("test", SharePath.parse("Foto/a.jpg"), Purpose.BACKGROUND)
    backend.abandonAllConnections()
    assertThat(backend.openHandlesOn("test", "Foto/a.jpg")).isEqualTo(1)

    val end = runToEnd(OpRequest.Move(listOf(source), "test-comune", SharePath.parse("Dest")))

    val items = store.items(store.ops(OpState.entries.toSet()).single().id)
    assertWithMessage("stato finale ${items.map { "${it.state.code}/${it.errorCode?.code}" }}").that(end).isEqualTo(OpState.DONE)
    assertThat(backend.bytes("test-comune", "Dest/a.jpg")).isEqualTo(bytes)
    assertThat(backend.exists("test", "Foto/a.jpg")).isFalse()
    // La sorgente e' nel proprio cestino (dopo la verifica), con l'handle vecchio ancora attaccato finche' il NAS non se ne accorge.
    val trashed = NasLayout.trashItemFile(device, items.single().trashId!!).value
    assertThat(backend.bytes("test", trashed)).isEqualTo(bytes)
    assertThat(backend.openHandlesOn("test", trashed)).isEqualTo(1)
    backend.reapAbandoned()
    assertThat(backend.openHandles).isEqualTo(0)
  }

  @Test
  fun leChiusureInVoloNonFannoFallireIlCestinoDiUnaCartellaConSottocartelle() = bounded {
    backend.put("test", "Gita/Mare/a.jpg", ByteArray(1_000) { 1 })
    backend.put("test", "Gita/Mare/Sera/b.jpg", ByteArray(2_000) { 2 })
    backend.put("test", "Gita/c.jpg", ByteArray(3_000) { 3 })
    val folder = item("test", "Gita", folderBytes = 6_000)
    // Ogni chiusura arriva al NAS 100 ms dopo essere stata chiesta (un altro thread, la rete).
    backend.beforeClose = { Thread.sleep(100) }

    val end = runToEnd(OpRequest.Trash(listOf(folder)), resumes = 0)

    val items = store.items(store.ops(OpState.entries.toSet()).single().id)
    assertWithMessage("elementi ${items.map { "${it.state.code}/${it.errorCode?.code}" }}").that(items.single().state).isEqualTo(OpItemState.DONE)
    assertThat(end).isEqualTo(OpState.DONE)
    assertThat(backend.exists("test", "Gita")).isFalse()
    val trashed = NasLayout.trashItemFile(device, items.single().trashId!!).value
    assertThat(backend.bytes("test", "$trashed/Mare/Sera/b.jpg")).isNotNull()
  }

  private companion object {
    const val USER = "test"
    const val PASSWORD = "pw-di-prova"
  }
}
