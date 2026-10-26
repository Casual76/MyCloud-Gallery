package dev.pampa.mycloudgallery.core.albums.chaos

import dev.pampa.mycloudgallery.core.nas.breaker.AuthBreakerRegistry
import dev.pampa.mycloudgallery.core.nas.contract.Stage
import dev.pampa.mycloudgallery.core.nas.contract.TestTarget
import dev.pampa.mycloudgallery.core.nas.fence.ShareFence
import dev.pampa.mycloudgallery.core.nas.session.MonotonicClock
import dev.pampa.mycloudgallery.core.nas.smb.SmbTransport
import dev.pampa.mycloudgallery.core.nas.smb.SmbjTransport
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryBreakerStore
import dev.pampa.mycloudgallery.core.nas.testing.InMemorySmbBackend
import dev.pampa.mycloudgallery.core.nas.testing.ManualMonotonicClock
import dev.pampa.mycloudgallery.core.nas.testing.SimAccess
import dev.pampa.mycloudgallery.core.nas.tools.SmbConnectionTester
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.SocketBinding
import dev.pampa.mycloudgallery.core.network.contract.Via
import dev.pampa.mycloudgallery.core.network.testing.DirectSocketBinding
import dev.pampa.mycloudgallery.core.network.testing.FakeNasRouteGate
import java.io.File
import java.util.Properties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay

// IL RUNNER CONTRO L'AREA DI PROVA (come `ChaosAreaRunner` + `ChaosNasGate` di core-nasops, F16-PC): apre l'area (il NAS vero con smbj, o il simulatore
// SMB per provare tutto senza rete), fa i giri del banco della condivisione finche' le interruzioni cadute sono quelle chieste, e a fine prova pulisce la
// cartella del giro e dice cosa e' rimasto. Un errore di autenticazione ferma tutto subito, senza nuovi tentativi.

/** Se e come si puo' toccare il NAS vero adesso. Nessuna lettura di rete qui: solo variabili d'ambiente, un file locale e la guardia. */
object ShareAreaGate {

  sealed interface Outcome {
    class Ready(val credentials: ShareAreaCredentials, val guard: ShareAreaGuard) : Outcome

    /** Non si tocca il NAS vero: [reason] e' il motivo (finisce nel rapporto del test saltato). */
    class Skip(val reason: String) : Outcome
  }

  /** L'interruttore: la proprieta' `nasIntegration=true` (come `-PnasIntegration` per core-nas) o la variabile `MCG_NAS_INTEGRATION=true`. */
  val enabled: Boolean
    get() = System.getProperty("nasIntegration") == "true" || System.getenv("MCG_NAS_INTEGRATION") == "true"

  val credentialsFile: File get() = File(File(System.getProperty("user.home"), ".mycloudgallery"), "nas-it.properties")

  fun evaluate(runId: String): Outcome {
    if (!enabled) return Outcome.Skip("integrazione col NAS non richiesta (MCG_NAS_INTEGRATION=true, o -DnasIntegration=true)")
    val file = credentialsFile
    if (!file.isFile) return Outcome.Skip("file delle credenziali assente")
    val credentials = ShareAreaCredentials.parse(Properties().apply { file.inputStream().use { load(it) } })
      ?: return Outcome.Skip("file delle credenziali incompleto (user, password, share, share2)")
    // La guardia PRIMA di qualunque socket: un errore nel file fa fallire la prova, non la salta.
    return Outcome.Ready(credentials, ShareAreaGuard.of(credentials, runId))
  }
}

object ShareAreas {

  /** L'area sul NAS vero: smbj, il socket diretto del PC, l'ora vera. Il GUID del file, o dalla NEGOTIATE (senza autenticare). */
  suspend fun real(credentials: ShareAreaCredentials, guard: ShareAreaGuard, firstRound: Int = 0): ShareArea {
    val smb = SmbjTransport()
    val binding = DirectSocketBinding("pc-cablato")
    val guid = credentials.guid ?: negotiateGuid(credentials, guard, smb, binding)
    val clock = MonotonicClock { System.nanoTime() / NANOS_PER_MILLI }
    return ShareArea(
      credentials, guard, smb, binding, guid,
      time = { ChaosTime(clock, advance = { }, manual = false) },
      backoff = { delay(it) },
      describe = "NAS vero, area di prova (sessione SMB di produzione e primitive vere)",
      firstRound = firstRound,
    )
  }

  private suspend fun negotiateGuid(credentials: ShareAreaCredentials, guard: ShareAreaGuard, smb: SmbTransport, binding: SocketBinding): String {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      val gate = FakeNasRouteGate().apply { allow(Via.HOME_LAN, binding) }
      val breakers = AuthBreakerRegistry(InMemoryBreakerStore(), scope)
      val monotonic = MonotonicClock { System.nanoTime() / NANOS_PER_MILLI }
      // Solo la NEGOTIATE (nessun login): passa dal trasporto recintato come tutto il resto.
      val fenced = AreaTransport(smb, guard, guard.roundRoot(0), AreaAuthLatch())
      return SmbConnectionTester(gate, breakers, fenced, monotonic, io = Dispatchers.IO, shareGuard = ShareFence)
        .run(TestTarget(NasAddress(credentials.host), null, null, null), null, setOf(Stage.ROUTE, Stage.TCP, Stage.NEGOTIATE))
        .negotiation?.serverGuid
        ?: throw AssertionError("il NAS di prova non risponde alla NEGOTIATE")
    } finally {
      scope.cancel()
    }
  }

  /** L'area sul simulatore SMB (nessuna rete): lo stesso codice, lo stesso recinto, la stessa pulizia. [password] diversa da quella del simulatore = login rifiutato. */
  fun simulated(password: String = SIM_PASSWORD, runId: String = SIM_RUN, firstRound: Int = 0): Pair<ShareArea, InMemorySmbBackend> {
    val offset = java.util.concurrent.atomic.AtomicLong(0)
    val backend = InMemorySmbBackend().apply {
      addUser("test", SIM_PASSWORD)
      listOf("test", "test-comune").forEach { addShare(it, mapOf("test" to SimAccess.READ_WRITE)) }
      val base = nasTime()
      nasTime = { base + offset.get() }
    }
    val credentials = ShareAreaCredentials("192.168.178.58", "test", password.toCharArray(), "test", "test-comune", backend.serverGuid)
    val guard = ShareAreaGuard.of(credentials, runId)
    val clock = ManualMonotonicClock(10_000_000L)
    val area = ShareArea(
      credentials, guard, backend, backend.binding("pc"), backend.serverGuid,
      time = { ChaosTime(clock, advance = { ms -> offset.addAndGet(ms); clock.advance(ms) }, manual = true) },
      backoff = { },
      describe = "simulatore SMB dietro la guardia dell'area di prova",
      cleanupRetries = 0,
      cleanupPauseMs = 0,
      firstRound = firstRound,
    )
    return area to backend
  }

  const val SIM_PASSWORD = "pw-del-banco"
  const val SIM_RUN = "20261026-101500-1a2b3c4d"
  private const val NANOS_PER_MILLI = 1_000_000L
}

object ShareAreaRunner {

  class Settings(
    /** Le interruzioni da far cadere: 500 sul NAS vero. Una prova breve ne usa meno. */
    val interruptions: Int,
    val baseSeed: Long,
    /** Il primo giro: con lo stesso seme di base e le interruzioni del giro rifa' un giro rosso sul NAS vero. */
    val firstRound: Int = 0,
    val config: ShareChaosConfig = defaultConfig(),
  )

  /** Il giro sul NAS vero: stessi passi del banco JVM, nessun file aperto dal PC e nessuno spazio da cambiare, meno lavoro armato per passo. */
  fun defaultConfig(): ShareChaosConfig = ShareChaosConfig(Capabilities.REAL, workRounds = 6, settleRounds = 30, hazardPercent = 20)

  /** Fa i giri necessari: si ferma appena le interruzioni cadute sono [Settings.interruptions] (al massimo `4 x + 5` giri), o a un login rifiutato. */
  suspend fun run(area: ShareArea, settings: Settings, progress: ((rounds: Int, red: Int, fired: Int) -> Unit)? = null): ShareChaosResult {
    val stats = ShareChaosStats()
    return ShareChaosBench.run(
      rounds = settings.interruptions * 4 + 5,
      baseSeed = settings.baseSeed,
      factory = area.factory(),
      config = settings.config,
      stats = stats,
      firstRound = settings.firstRound,
      stopAfterInterruptions = settings.interruptions,
      stopReason = { if (area.latch.failed) "login rifiutato dal NAS: nessun nuovo tentativo" else null },
      onRound = { done, red -> progress?.invoke(done, red, stats.totalFired) },
    )
  }
}
