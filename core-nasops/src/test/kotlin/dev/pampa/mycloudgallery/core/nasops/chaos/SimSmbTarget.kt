package dev.pampa.mycloudgallery.core.nasops.chaos

import dev.pampa.mycloudgallery.core.nas.breaker.AuthBreakerRegistry
import dev.pampa.mycloudgallery.core.nas.contract.CredentialLoad
import dev.pampa.mycloudgallery.core.nas.contract.InvalidateReason
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.fence.ShareFence
import dev.pampa.mycloudgallery.core.nas.ops.NasFileOps
import dev.pampa.mycloudgallery.core.nas.ops.NasFileOpsFactory
import dev.pampa.mycloudgallery.core.nas.session.SmbNasSessionManager
import dev.pampa.mycloudgallery.core.nas.smb.NtStatus
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryBreakerStore
import dev.pampa.mycloudgallery.core.nas.testing.InMemorySmbBackend
import dev.pampa.mycloudgallery.core.nas.testing.ManualMonotonicClock
import dev.pampa.mycloudgallery.core.nas.testing.SimAccess
import dev.pampa.mycloudgallery.core.nasops.contract.OpClocks
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.Via
import dev.pampa.mycloudgallery.core.network.testing.FakeNasRouteGate
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * I1 della revisione 4B: il banco delle interruzioni sulle **primitive vere** (`SmbNasFileOps`, via [NasFileOpsFactory]) e sulla lettura
 * vera (`SmbNasMediaReader`) della sessione SMB di produzione ([SmbNasSessionManager]), sopra il simulatore SMB di core-nas
 * ([InMemorySmbBackend], semantica Samba: maiuscole, `FILE_CREATE`, rinomina senza sostituire, condivisioni e blocchi, `DELETE_PENDING`,
 * copia lato server a blocchi, codici NTSTATUS). Le interruzioni del banco cadono prima e dopo ogni primitiva vera (risposta persa); in
 * piu' la connessione si chiude davvero (la chiamata dopo si riconnette).
 *
 * A differenza del NAS vero ([SmbChaosArea]) il simulatore fa passare il tempo (30 giorni del cestino: l'ora del NAS e l'orologio monotono
 * della sessione avanzano insieme) e rifiuta la copia lato server a comando (ripiego dal telefono, E-D12). Un simulatore nuovo a ogni giro.
 */
class SimSmbTarget : ChaosTarget {
  override val name: String = "simulatore SMB di core-nas con la sessione e le primitive vere (SmbNasFileOps)"

  override suspend fun openWorld(round: Int): ChaosWorld = SimSmbWorld()

  private class SimSmbWorld : ChaosWorld {
    override val shares: List<String> = listOf("test", "test-comune")
    override val deviceId: String = InMemoryTarget.DEVICE
    override val accountId: String = USER

    private val monotonic = ManualMonotonicClock(START_MONOTONIC)
    private var offset = 0L
    private val backend = InMemorySmbBackend().apply {
      addUser(USER, PASSWORD)
      shares.forEach { addShare(it, mapOf(USER to SimAccess.READ_WRITE)) }
      val base = nasTime()
      nasTime = { base + offset }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val manager = SmbNasSessionManager(
      account = { NasAccount(NasAddress(HOST), USER, "chaos", backend.serverGuid) },
      routeGate = FakeNasRouteGate().apply { allow(Via.HOME_LAN, backend.binding("pc")) },
      credentials = { CredentialLoad.Found(NasCredentials(USER, PASSWORD.toCharArray())) },
      breakers = AuthBreakerRegistry(InMemoryBreakerStore(), scope),
      scope = scope,
      transport = backend,
      clock = monotonic,
      io = Dispatchers.IO,
      deviceId = { InMemoryTarget.DEVICE },
      backoff = { },
      shareGuard = ShareFence,
    )

    override val reader: NasMediaReader = manager.mediaReader()
    override val fileOps: NasFileOps = NasFileOpsFactory.create(manager, deviceId)
    override val clocks: OpClocks = object : OpClocks {
      override fun monotonicMs(): Long = monotonic.nowMillis()

      override fun nasNowMs(): Long? = manager.nasClock.nowMillisOrNull()
    }
    override val capabilities: ChaosWorld.Capabilities = ChaosWorld.Capabilities(advanceTime = true, copyFallback = true, libraryReads = true)

    override fun advanceTime(ms: Long) {
      offset += ms
      monotonic.advance(ms)
    }

    override fun setCopyMode(mode: CopyMode) {
      // E-D12: la copia lato server rifiutata come la rifiuta Samba (FSCTL non supportato): le primitive vere ripiegano dal telefono.
      backend.copyChunkStatus = if (mode == CopyMode.SERVER) null else NtStatus.NOT_SUPPORTED
    }

    override suspend fun putFile(share: String, path: SharePath, bytes: ByteArray) {
      backend.put(share, path.value, bytes)
    }

    override suspend fun openLibraryRead(share: String, path: SharePath): AutoCloseable? =
      (reader.open(share, path, Purpose.BACKGROUND) as? NasResult.Ok)?.value

    override suspend fun connectionLost() {
      backend.dropAllConnections()
      manager.invalidate(InvalidateReason.SUSPECT_CONNECTION)
    }

    override fun describe(): String = "simulatore SMB, share ${shares.joinToString()}, ${backend.authenticateCalls.get()} accessi"

    override fun close() {
      try {
        kotlinx.coroutines.runBlocking { manager.invalidate(InvalidateReason.USER_REQUEST) }
      } finally {
        scope.cancel()
      }
    }

    init {
      // La prima ancora dell'ora del NAS (NEGOTIATE) e la prima prova della password, come all'avvio dell'app.
      val opened = kotlinx.coroutines.runBlocking { manager.withShare(shares.first(), Purpose.INTERACTIVE) { } }
      check(opened is NasResult.Ok) { "il simulatore non apre la sessione: $opened" }
    }
  }

  private companion object {
    const val USER = "test"
    const val PASSWORD = "pw-di-prova"
    const val HOST = "192.168.178.58"
    const val START_MONOTONIC = 10_000_000L
  }
}
