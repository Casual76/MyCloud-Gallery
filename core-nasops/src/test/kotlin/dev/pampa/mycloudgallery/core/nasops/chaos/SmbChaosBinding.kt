package dev.pampa.mycloudgallery.core.nasops.chaos

import dev.pampa.mycloudgallery.core.nas.breaker.AuthBreakerRegistry
import dev.pampa.mycloudgallery.core.nas.contract.CredentialLoad
import dev.pampa.mycloudgallery.core.nas.contract.InvalidateReason
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.Stage
import dev.pampa.mycloudgallery.core.nas.contract.TestTarget
import dev.pampa.mycloudgallery.core.nas.fence.ShareFence
import dev.pampa.mycloudgallery.core.nas.ops.NasFileOps
import dev.pampa.mycloudgallery.core.nas.ops.NasFileOpsFactory
import dev.pampa.mycloudgallery.core.nas.ops.PublishOutcome
import dev.pampa.mycloudgallery.core.nas.ops.TempFinish
import dev.pampa.mycloudgallery.core.nas.session.MonotonicClock
import dev.pampa.mycloudgallery.core.nas.session.SmbNasSessionManager
import dev.pampa.mycloudgallery.core.nas.smb.SmbTransport
import dev.pampa.mycloudgallery.core.nas.smb.SmbjTransport
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryBreakerStore
import dev.pampa.mycloudgallery.core.nas.tools.SmbConnectionTester
import dev.pampa.mycloudgallery.core.nasops.contract.OpClocks
import dev.pampa.mycloudgallery.core.nasops.contract.OpId
import dev.pampa.mycloudgallery.core.nasops.contract.OpTemps
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.SocketBinding
import dev.pampa.mycloudgallery.core.network.contract.Via
import dev.pampa.mycloudgallery.core.network.testing.DirectSocketBinding
import dev.pampa.mycloudgallery.core.network.testing.FakeNasRouteGate
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * L'aggancio del banco delle interruzioni al NAS VERO (docs/prova-500.md §9, punto 3): la sessione SMB di produzione (smbj) con l'account di
 * prova, e mondi radicati in `mcg-it/<giro>/r<N>/` (la cartella dell'app compresa: temporanei e cestino finiscono li', non alla radice della
 * share). Lo trova [ChaosNasGate] per nome; la prova vera parte solo con l'interruttore e il file delle credenziali (`ChaosNasIntegrationTest`).
 */
object SmbChaosBinding : ChaosAreaBinding by SmbChaosArea(transport = { SmbjTransport() })

/**
 * L'aggancio vero e proprio, con il trasporto e il socket iniettabili: smbj e il socket diretto del PC per il NAS vero, il simulatore SMB di
 * core-nas per provare l'aggancio senza rete (`p12/SmbChaosBindingTest`).
 *
 * Cosa garantisce, oltre alla guardia del banco ([ChaosAreaGuard], [GuardedReader], [GuardedFileOps]):
 *  - la guardia si consulta PRIMA del primo socket (account e share di prova);
 *  - la sessione ha la recinzione D33 ([ShareFence]): un account di prova non apre altre share nemmeno per sbaglio;
 *  - la password si prova UNA volta: se il login non riesce la prova si ferma, senza nuovi tentativi (niente blocchi dell'account);
 *  - le primitive di scrittura sono quelle di produzione ([NasFileOpsFactory.createForTestArea]) radicate nella cartella del giro, e la lettura
 *    e' radicata nello stesso posto ([RootedReader]): nessun percorso del banco esce da `mcg-it/<giro>/r<N>/`;
 *  - l'ora e' quella del NAS (ancorata alla NEGOTIATE), le durate sull'orologio monotono.
 *
 * La pulizia di `mcg-it/<giro>/` la fa `NasIntegrationSuiteTest` di core-nas al giro dopo, con entrambi gli account (docs/prova-500.md §7).
 */
class SmbChaosArea(
  private val transport: () -> SmbTransport,
  private val socket: () -> SocketBinding = { DirectSocketBinding("pc-cablato") },
  private val io: CoroutineDispatcher = Dispatchers.IO,
  private val backoff: suspend (Duration) -> Unit = { delay(it) },
) : ChaosAreaBinding {

  private class Session(
    val manager: SmbNasSessionManager,
    val scope: CoroutineScope,
    val shares: List<String>,
    val user: String,
    val deviceId: String,
    val clocks: OpClocks,
  )

  private val mutex = Mutex()
  private var open: Session? = null

  /** Quante sessioni sono state aperte (una per aggancio): la password si prova una volta sola. */
  @Volatile
  var sessionsOpened: Int = 0
    private set

  override suspend fun openTarget(credentials: ChaosCredentials, guard: ChaosAreaGuard): ChaosTarget {
    val session = session(credentials, guard)
    return object : ChaosTarget {
      override val name: String = "NAS vero, area di prova (sessione SMB di produzione)"

      override suspend fun openWorld(round: Int): ChaosWorld = SmbChaosWorld(session, guard.roundRoot(round), round)
    }
  }

  override suspend fun openReader(credentials: ChaosCredentials, guard: ChaosAreaGuard): NasMediaReader =
    session(credentials, guard).manager.mediaReader()

  override suspend fun close() {
    mutex.withLock {
      val session = open ?: return@withLock
      open = null
      try {
        session.manager.invalidate(InvalidateReason.USER_REQUEST)
      } finally {
        session.scope.cancel()
      }
    }
  }

  private suspend fun session(credentials: ChaosCredentials, guard: ChaosAreaGuard): Session = mutex.withLock {
    open?.let { return@withLock it }
    // La guardia prima di qualunque socket: account di prova e le due share di prova (privata e comune, in quest'ordine).
    guard.checkAccount(credentials.user)
    val second = credentials.share2 ?: throw AreaViolation("il banco vuole due share di prova (share2)")
    val shares = listOf(guard.checkShare(credentials.share), guard.checkShare(second))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      val monotonic = MonotonicClock { System.nanoTime() / NANOS_PER_MILLI }
      val gate = FakeNasRouteGate().apply { allow(Via.HOME_LAN, socket()) }
      val breakers = AuthBreakerRegistry(InMemoryBreakerStore(), scope)
      val smb = transport()
      // Il GUID del NAS: dal file, o dalla NEGOTIATE come l'onboarding (senza autenticare).
      val guid = credentials.guid ?: SmbConnectionTester(gate, breakers, smb, monotonic, io = io, shareGuard = ShareFence)
        .run(TestTarget(NasAddress(credentials.host), null, null, null), null, setOf(Stage.ROUTE, Stage.TCP, Stage.NEGOTIATE))
        .negotiation?.serverGuid
        ?: throw AssertionError("il NAS di prova non risponde alla NEGOTIATE")
      val account = NasAccount(NasAddress(credentials.host), credentials.user, "chaos", guid)
      val deviceId = TrustIds.random()
      val manager = SmbNasSessionManager(
        account = { account },
        routeGate = gate,
        credentials = { CredentialLoad.Found(NasCredentials(credentials.user, credentials.password.copyOf())) },
        breakers = breakers,
        scope = scope,
        transport = smb,
        clock = monotonic,
        io = io,
        deviceId = { deviceId },
        backoff = backoff,
        shareGuard = ShareFence,
      )
      // UNA prova della password. Un errore non si ritenta.
      sessionsOpened++
      val check = manager.withShare(shares.first(), Purpose.INTERACTIVE) { }
      if (check is NasResult.Err) {
        manager.invalidate(InvalidateReason.USER_REQUEST)
        throw AssertionError("la sessione di prova non parte: ${check.error::class.simpleName} (nessun altro tentativo)")
      }
      val clocks = object : OpClocks {
        override fun monotonicMs(): Long = monotonic.nowMillis()

        override fun nasNowMs(): Long? = manager.nasClock.nowMillisOrNull()
      }
      Session(manager, scope, shares, credentials.user, deviceId, clocks).also { open = it }
    } catch (e: Throwable) {
      scope.cancel()
      throw e
    }
  }

  /** Un giro: tutto radicato in [root] (`mcg-it/<giro>/r<N>`), la cartella dell'app compresa. */
  private class SmbChaosWorld(private val session: Session, private val root: SharePath, private val round: Int) : ChaosWorld {
    override val shares: List<String> = session.shares
    override val deviceId: String = session.deviceId
    override val accountId: String = session.user
    override val reader: NasMediaReader = RootedReader(session.manager.mediaReader(), root)
    override val fileOps: NasFileOps = NasFileOpsFactory.createForTestArea(session.manager, session.deviceId, root)
    override val clocks: OpClocks = session.clocks

    // Il NAS vero non fa passare 31 giorni e non rifiuta la copia lato server a comando (docs/prova-500.md §10).
    override val capabilities: ChaosWorld.Capabilities = ChaosWorld.Capabilities(advanceTime = false, copyFallback = false)

    override fun advanceTime(ms: Long) = throw UnsupportedOperationException("il NAS vero non fa passare il tempo")

    override fun setCopyMode(mode: CopyMode) {
      check(mode == CopyMode.SERVER) { "il NAS vero non rifiuta la copia lato server a comando" }
    }

    /** Il "PC" scrive un file intero: temporaneo proprio, scrittura, pubblicazione senza sostituire. Nessun colpo. */
    override suspend fun putFile(share: String, path: SharePath, bytes: ByteArray) {
      val purpose = Purpose.BACKGROUND
      path.parent()?.let { dir -> fileOps.mkdirs(share, dir, purpose).orFail("cartella") }
      val temp = OpTemps.tempFor(deviceId, OpId(TrustIds.random()), 0)
      val writer = fileOps.openTemp(share, temp, purpose).orFail("temporaneo")
      var published = false
      try {
        writer.use { out ->
          var offset = 0
          while (offset < bytes.size) {
            val n = minOf(PUT_CHUNK, bytes.size - offset)
            out.write(offset.toLong(), bytes, offset, n).orFail("scrittura")
            offset += n
          }
          check(out.finish(bytes.size.toLong()).orFail("chiusura") is TempFinish.Complete) { "file del PC scritto a meta'" }
        }
        val outcome = fileOps.publishTemp(share, temp, path, purpose).orFail("pubblicazione")
        check(outcome is PublishOutcome.Published) { "file del PC non pubblicato: ${outcome::class.simpleName}" }
        published = true
      } finally {
        if (!published) fileOps.deleteOwnTemp(share, temp, purpose)
      }
    }

    override suspend fun connectionLost() = session.manager.invalidate(InvalidateReason.SUSPECT_CONNECTION)

    override fun describe(): String = "NAS vero, area di prova, giro r$round"

    override fun close() = Unit

    private fun <T> NasResult<T>.orFail(what: String): T = when (this) {
      is NasResult.Ok -> value
      is NasResult.Err -> throw IllegalStateException("file del PC: $what non riuscita (${error::class.simpleName})")
    }
  }

  private companion object {
    const val NANOS_PER_MILLI = 1_000_000L
    const val PUT_CHUNK = 1 shl 20
  }
}
