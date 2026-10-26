package dev.pampa.mycloudgallery.core.albums.share

import dev.pampa.mycloudgallery.core.nas.breaker.AuthBreakerRegistry
import dev.pampa.mycloudgallery.core.nas.contract.CredentialLoad
import dev.pampa.mycloudgallery.core.nas.contract.InvalidateReason
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.fence.ShareFence
import dev.pampa.mycloudgallery.core.nas.session.SmbNasSessionManager
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryBreakerStore
import dev.pampa.mycloudgallery.core.nas.testing.InMemorySmbBackend
import dev.pampa.mycloudgallery.core.nas.testing.ManualMonotonicClock
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nas.testing.SimAccess
import dev.pampa.mycloudgallery.core.nasops.contract.GateStatus
import dev.pampa.mycloudgallery.core.nasops.contract.MoveCompletionSink
import dev.pampa.mycloudgallery.core.nasops.contract.NasItem
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperations
import dev.pampa.mycloudgallery.core.nasops.contract.OpClocks
import dev.pampa.mycloudgallery.core.nasops.contract.OpPlan
import dev.pampa.mycloudgallery.core.nasops.contract.OpsGate
import dev.pampa.mycloudgallery.core.nasops.contract.OpsStore
import dev.pampa.mycloudgallery.core.nasops.contract.StartOutcome
import dev.pampa.mycloudgallery.core.nasops.testing.InMemoryOpsStore
import dev.pampa.mycloudgallery.core.nasops.testing.KitEngine
import dev.pampa.mycloudgallery.core.nasops.testing.NasOpsTestKit
import dev.pampa.mycloudgallery.core.nasops.testing.TestConfirmations
import dev.pampa.mycloudgallery.core.nasops.wiring.NasOpsConfig
import dev.pampa.mycloudgallery.core.nasops.wiring.NasOpsWiring
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.Via
import dev.pampa.mycloudgallery.core.network.testing.FakeNasRouteGate
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * Il NAS sotto il banco della condivisione (P4, riga del §4.4: "sul NAS finto **e** sul simulatore SMB con le vere `SmbNasFileOps`"): il motore
 * vero delle operazioni di core-nasops (`NasOpsEngine`, con la porta di completamento) sopra il NAS finto di `NasOpsTestKit` o sopra la sessione
 * SMB di produzione e le primitive vere, sul simulatore di core-nas. Le aree: `test` (privata) e `test-comune` (comune). Nessuna conferma di
 * core-nasops si nomina qui (Phase7ArchitectureTest): la emette `TestConfirmations`, come la emettera' :app con lo stesso piano.
 */
interface ShareNasTarget : AutoCloseable {
  val name: String

  /** Il motore delle operazioni del "processo" corrente (uno nuovo a ogni [restart]). */
  val operations: NasOperations

  /** Il diario di core-nasops: sopravvive a [restart]. */
  val store: OpsStore

  /** Lo stato dei cancelli delle operazioni per share (congelamento, emergenza: E-SH19). */
  val gates: MutableMap<String, GateStatus>

  /** La morte del processo: un motore nuovo sullo stesso diario e sullo stesso NAS. */
  fun restart()

  fun put(share: String, path: String, bytes: ByteArray)

  fun bytes(share: String, path: String): ByteArray?

  /** Tutti i file della share (anche quelli dell'app), come percorsi. */
  fun files(share: String): List<String>

  /** L'elemento come lo selezionerebbe l'utente: file con il timbro di adesso; null se non c'e'. */
  suspend fun item(share: String, path: SharePath): NasItem?

  /** Avvia [plan] con la conferma emessa adesso (come :app dopo il tocco sul foglio della condivisione). */
  suspend fun start(plan: OpPlan): StartOutcome

  fun lock(share: String, path: String)

  fun unlock(share: String, path: String)

  /** Una modifica dal PC sul posto (E-D13, E-SH18). */
  fun modify(share: String, path: String, bytes: ByteArray)

  /** Una cancellazione dal PC (o lo spostamento fatto da un altro dispositivo, visto da qui). */
  fun delete(share: String, path: String)

  fun advance(ms: Long)

  /** E-SH8, F81: lo spazio libero dell'account sul NAS (null = quello di serie, abbondante). */
  fun setFreeBytes(bytes: Long?)

  override fun close() = Unit

  companion object {
    const val PRIVATE: String = "test"
    const val COMMON: String = "test-comune"
    val DEVICE: String = Base32.encode(ByteArray(16) { 0x4D })
  }
}

/** Il NAS finto in memoria con il motore vero ([KitEngine.PRODUCTION]). */
class FakeNasTarget(completions: MoveCompletionSink) : ShareNasTarget {
  override val name: String = "NAS finto"
  private val kit = NasOpsTestKit(deviceId = ShareNasTarget.DEVICE, engine = KitEngine.PRODUCTION, completions = completions)

  override val operations: NasOperations get() = kit.operations
  override val store: OpsStore get() = kit.store
  override val gates: MutableMap<String, GateStatus> get() = kit.gate.byShare

  override fun restart() = kit.restart()

  override fun put(share: String, path: String, bytes: ByteArray) {
    kit.putFile(share, path, bytes)
  }

  override fun bytes(share: String, path: String): ByteArray? = kit.bytes(share, path)

  override fun files(share: String): List<String> = kit.filesIn(share)

  override suspend fun item(share: String, path: SharePath): NasItem? =
    if (kit.bytes(share, path.value) == null) null else kit.itemOf(share, path.value)

  override suspend fun start(plan: OpPlan): StartOutcome = kit.operations.start(plan, kit.confirm(plan))

  override fun lock(share: String, path: String) = kit.lock(share, path)

  override fun unlock(share: String, path: String) = kit.unlock(share, path)

  override fun modify(share: String, path: String, bytes: ByteArray) = kit.modify(share, path, bytes)

  override fun delete(share: String, path: String) = kit.deleteFromPc(share, path)

  override fun advance(ms: Long) = kit.advance(ms)

  override fun setFreeBytes(bytes: Long?) = kit.setCallerFreeBytes(bytes)
}

/**
 * Il simulatore SMB di core-nas (semantica Samba: maiuscole, `FILE_CREATE`, rinomina senza sostituire, blocchi, copia lato server) con la sessione
 * SMB di produzione e le operazioni costruite da `NasOpsWiring` come in :app: le primitive sono le vere `SmbNasFileOps`. Una morte del processo
 * chiude anche le connessioni.
 */
class SmbNasTarget(private val completions: MoveCompletionSink) : ShareNasTarget {
  override val name: String = "simulatore SMB"

  private val monotonic = ManualMonotonicClock(START_MONOTONIC)
  private var offset = 0L
  private val backend = InMemorySmbBackend().apply {
    addUser(USER, PASSWORD)
    listOf(ShareNasTarget.PRIVATE, ShareNasTarget.COMMON).forEach { addShare(it, mapOf(USER to SimAccess.READ_WRITE)) }
    val base = nasTime()
    nasTime = { base + offset }
  }
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val session = SmbNasSessionManager(
    account = { NasAccount(NasAddress(HOST), USER, "banco", backend.serverGuid) },
    routeGate = FakeNasRouteGate().apply { allow(Via.HOME_LAN, backend.binding("pc")) },
    credentials = { CredentialLoad.Found(NasCredentials(USER, PASSWORD.toCharArray())) },
    breakers = AuthBreakerRegistry(InMemoryBreakerStore(), scope),
    scope = scope,
    transport = backend,
    clock = monotonic,
    io = Dispatchers.IO,
    deviceId = { ShareNasTarget.DEVICE },
    backoff = { },
    shareGuard = ShareFence,
  )
  private val clocks = object : OpClocks {
    override fun monotonicMs(): Long = monotonic.nowMillis()

    override fun nasNowMs(): Long? = session.nasClock.nowMillisOrNull()
  }

  override val gates: MutableMap<String, GateStatus> = HashMap()
  override val store: OpsStore = InMemoryOpsStore()

  private fun build(): NasOperations = NasOpsWiring.build(
    session, store,
    NasOpsConfig(
      deviceId = ShareNasTarget.DEVICE,
      accountId = USER,
      shares = { listOf(ShareNasTarget.PRIVATE, ShareNasTarget.COMMON) },
      clocks = clocks,
      gate = OpsGate { share -> gates[share] ?: GateStatus.OPEN },
      completions = completions,
    ),
  ).operations

  override var operations: NasOperations = build()
    private set

  init {
    // La prima ancora dell'ora del NAS (NEGOTIATE) e la prima prova della password, come all'avvio dell'app.
    val opened = runBlocking { session.withShare(ShareNasTarget.PRIVATE, Purpose.INTERACTIVE) { } }
    check(opened is NasResult.Ok) { "il simulatore non apre la sessione: $opened" }
  }

  override fun restart() {
    // Il processo muore: le sue connessioni cadono, il motore si ricostruisce sullo stesso diario.
    backend.dropAllConnections()
    runBlocking { session.invalidate(InvalidateReason.SUSPECT_CONNECTION) }
    operations = build()
  }

  override fun put(share: String, path: String, bytes: ByteArray) = backend.put(share, path, bytes)

  override fun bytes(share: String, path: String): ByteArray? = backend.bytes(share, path)

  override fun files(share: String): List<String> = filesUnder(share, "")

  private fun filesUnder(share: String, dir: String): List<String> = backend.children(share, dir).flatMap { name ->
    val path = if (dir.isEmpty()) name else "$dir/$name"
    if (backend.bytes(share, path) != null) listOf(path) else filesUnder(share, path)
  }

  override suspend fun item(share: String, path: SharePath): NasItem? {
    val entry = (session.mediaReader().stat(share, path, Purpose.INTERACTIVE) as? NasResult.Ok)?.value ?: return null
    if (entry.isDirectory) return null
    return NasItem(share, path, isFolder = false, size = entry.size, stamp = entry.stamp)
  }

  override suspend fun start(plan: OpPlan): StartOutcome = operations.start(plan, TestConfirmations.forPlan(plan, clocks.monotonicMs()))

  override fun lock(share: String, path: String) = backend.lock(share, path)

  override fun unlock(share: String, path: String) = backend.unlock(share, path)

  override fun modify(share: String, path: String, bytes: ByteArray) = backend.modify(share, path, NasContent.of(bytes))

  override fun delete(share: String, path: String) = backend.delete(share, path)

  override fun advance(ms: Long) {
    offset += ms
    monotonic.advance(ms)
  }

  override fun setFreeBytes(bytes: Long?) {
    backend.volumeFreeBytes = bytes ?: DEFAULT_FREE
  }

  override fun close() {
    try {
      runBlocking { session.invalidate(InvalidateReason.USER_REQUEST) }
    } finally {
      scope.cancel()
    }
  }

  private companion object {
    const val USER = "test"
    const val PASSWORD = "pw-del-banco"
    const val HOST = "192.168.178.58"
    const val START_MONOTONIC = 10_000_000L
    const val DEFAULT_FREE = 2_420_000_000_000L
  }
}
