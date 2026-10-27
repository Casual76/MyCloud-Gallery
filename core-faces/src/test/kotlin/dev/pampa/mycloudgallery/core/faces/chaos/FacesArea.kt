package dev.pampa.mycloudgallery.core.faces.chaos

import dev.pampa.mycloudgallery.core.nas.breaker.AuthBreakerRegistry
import dev.pampa.mycloudgallery.core.nas.contract.Stage
import dev.pampa.mycloudgallery.core.nas.contract.TestTarget
import dev.pampa.mycloudgallery.core.nas.fence.ShareFence
import dev.pampa.mycloudgallery.core.nas.session.MonotonicClock
import dev.pampa.mycloudgallery.core.nas.smb.DirAccess
import dev.pampa.mycloudgallery.core.nas.smb.Negotiation
import dev.pampa.mycloudgallery.core.nas.smb.NtStatus
import dev.pampa.mycloudgallery.core.nas.smb.SessionFacts
import dev.pampa.mycloudgallery.core.nas.smb.SmbChangeHandle
import dev.pampa.mycloudgallery.core.nas.smb.SmbConnection
import dev.pampa.mycloudgallery.core.nas.smb.SmbDirectory
import dev.pampa.mycloudgallery.core.nas.smb.SmbFileInfo
import dev.pampa.mycloudgallery.core.nas.smb.SmbOpenMode
import dev.pampa.mycloudgallery.core.nas.smb.SmbReadFile
import dev.pampa.mycloudgallery.core.nas.smb.SmbSession
import dev.pampa.mycloudgallery.core.nas.smb.SmbShare
import dev.pampa.mycloudgallery.core.nas.smb.SmbSpace
import dev.pampa.mycloudgallery.core.nas.smb.SmbStatusException
import dev.pampa.mycloudgallery.core.nas.smb.SmbTransport
import dev.pampa.mycloudgallery.core.nas.smb.SmbWriteFile
import dev.pampa.mycloudgallery.core.nas.smb.SmbjTransport
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryBreakerStore
import dev.pampa.mycloudgallery.core.nas.testing.InMemorySmbBackend
import dev.pampa.mycloudgallery.core.nas.testing.SimAccess
import dev.pampa.mycloudgallery.core.nas.tools.SmbConnectionTester
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.SocketBinding
import dev.pampa.mycloudgallery.core.network.contract.Via
import dev.pampa.mycloudgallery.core.network.testing.DirectSocketBinding
import dev.pampa.mycloudgallery.core.network.testing.FakeNasRouteGate
import java.io.File
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

// L'AREA DI PROVA: tutto cio' che serve a far girare il banco dei volti contro un NAS dietro la guardia: il trasporto vero (smbj, o il simulatore SMB per
// provare tutto senza rete), un mondo per giro in `mcg-it/<giro>/r<N>/`, l'oracolo, la pulizia, l'autotest della guardia. La prova vera sul NAS di casa e'
// `FacesNasIntegrationTest` (solo con l'interruttore e il file delle credenziali); `FacesAreaRunnerTest` fa girare lo stesso codice sul simulatore.

internal class FacesArea(
  val credentials: FacesAreaCredentials,
  val guard: FacesAreaGuard,
  private val inner: SmbTransport,
  val binding: SocketBinding,
  val guid: String,
  /** Vero sul NAS vero: tempo vero per il gestore della sessione e pause vere fra un giro del mondo e l'altro. */
  val realTime: Boolean,
  val describe: String,
  private val cleanupRetries: Int = 6,
  private val cleanupPauseMs: Long = 20_000,
  firstRound: Int = 0,
) {
  /** "Il login e' stato rifiutato": il banco si ferma, nessun nuovo tentativo. */
  val latch = FacesAuthLatch()

  /** Le chiamate arrivate al trasporto vero attraverso la guardia, in tutto. */
  val counter = FacesDelegateCounter()

  /** La pulizia di ogni mondo (uno per giro), nell'ordine. */
  val cleanups: MutableList<FacesCleanupReport> = ArrayList()

  private var round = firstRound
  private var openFailures = 0

  fun connection(): FacesConnection = FacesConnection(credentials.user, credentials.password.copyOf(), credentials.host, guid, binding)

  /** I NAS del banco: uno nuovo per giro, radicato in `mcg-it/<giro>/r<N>`, con l'oracolo accanto e la pulizia alla chiusura. */
  fun factory(): ChaosTargetFactory = object : ChaosTargetFactory {
    override val name: String = describe
    override val capabilities: Capabilities = Capabilities.REAL

    override fun create(world: ChaosWorld): ChaosNas {
      if (latch.failed) throw FacesAreaAuthStop("login gia' rifiutato dal NAS: nessun nuovo tentativo")
      val root = guard.roundRoot(round++)
      val rooted = FacesAreaTransport(inner, guard, root, latch, counter)
      val admin = FacesAreaAdmin(
        transport = FacesAreaTransport(inner, guard, root, latch, counter),
        connection = connection(),
        latch = latch,
        cleanupRetries = cleanupRetries,
        cleanupPauseMs = cleanupPauseMs,
        onCleaned = { synchronized(cleanups) { cleanups += it } },
      )
      try {
        admin.open()
        return FacesAreaNas(this@FacesArea, world.inj, world.logs, world, rooted, admin).also { openFailures = 0 }
      } catch (e: FacesAreaAuthStop) {
        runCatching { admin.close() }
        throw e
      } catch (e: Throwable) {
        // Il NAS non risponde (rete, NAS spento): tre aperture di fila che non riescono fermano il banco, che non puo' provare niente.
        openFailures++
        runCatching { admin.close() }
        throw e
      }
    }
  }

  /** Perche' il banco deve fermarsi adesso: un login rifiutato (mai un nuovo tentativo) o un NAS che non si apre per tre giri di fila. */
  fun stopReason(): String? = when {
    latch.failed -> "login rifiutato dal NAS: nessun nuovo tentativo"
    openFailures >= MAX_OPEN_FAILURES -> "il NAS di prova non si apre ($MAX_OPEN_FAILURES giri di fila): niente da provare"
    else -> null
  }

  /** La pulizia finale: tutto cio' che sta sotto `mcg-it/<giro>` (anche cio' che un giro rosso ha lasciato) e poi la cartella del giro. */
  fun finish(): FacesCleanupReport {
    val admin = FacesAreaAdmin(
      transport = FacesAreaTransport(inner, guard, guard.root, latch, counter),
      connection = connection(),
      latch = latch,
      cleanupRetries = cleanupRetries,
      cleanupPauseMs = cleanupPauseMs,
      cleanOnClose = false,
    ).open()
    return try {
      admin.clean()
    } finally {
      admin.close()
    }
  }

  private companion object {
    const val MAX_OPEN_FAILURES = 3
  }
}

/** Se e come si puo' toccare il NAS vero adesso. Nessuna lettura di rete qui: solo variabili d'ambiente, un file locale e la guardia. */
internal object FacesAreaGate {

  sealed interface Outcome {
    class Ready(val credentials: FacesAreaCredentials, val guard: FacesAreaGuard) : Outcome

    /** Non si tocca il NAS vero: [reason] e' il motivo (finisce nel rapporto del test saltato). */
    class Skip(val reason: String) : Outcome
  }

  /** L'interruttore: la proprieta' `nasIntegration=true` o la variabile `MCG_NAS_INTEGRATION=true`. */
  val enabled: Boolean
    get() = System.getProperty("nasIntegration") == "true" || System.getenv("MCG_NAS_INTEGRATION") == "true"

  val credentialsFile: File get() = File(File(System.getProperty("user.home"), ".mycloudgallery"), "nas-it.properties")

  fun evaluate(runId: String): Outcome {
    if (!enabled) return Outcome.Skip("integrazione col NAS non richiesta (MCG_NAS_INTEGRATION=true, o -DnasIntegration=true)")
    val file = credentialsFile
    if (!file.isFile) return Outcome.Skip("file delle credenziali assente")
    val credentials = FacesAreaCredentials.parse(Properties().apply { file.inputStream().use { load(it) } })
      ?: return Outcome.Skip("file delle credenziali incompleto (user, password, share, share2)")
    // La guardia PRIMA di qualunque socket: un errore nel file fa fallire la prova, non la salta.
    return Outcome.Ready(credentials, FacesAreaGuard.of(credentials, runId))
  }
}

internal object FacesAreas {

  /** Smbj, il socket diretto del PC e il GUID del NAS (dal file, o dalla NEGOTIATE senza autenticare): cio' che serve per aprire l'area o per leggerla. */
  class RealLink(val transport: SmbTransport, val binding: SocketBinding, val guid: String)

  suspend fun realLink(credentials: FacesAreaCredentials, guard: FacesAreaGuard): RealLink {
    val smb = SmbjTransport()
    val binding = DirectSocketBinding("pc-cablato")
    return RealLink(smb, binding, credentials.guid ?: negotiateGuid(credentials, guard, smb, binding))
  }

  /** L'area sul NAS vero: smbj, il socket diretto del PC, l'ora vera. Il GUID del file, o dalla NEGOTIATE (senza autenticare). */
  suspend fun real(credentials: FacesAreaCredentials, guard: FacesAreaGuard, firstRound: Int = 0): FacesArea {
    val link = realLink(credentials, guard)
    return FacesArea(
      credentials, guard, link.transport, link.binding, link.guid,
      realTime = true,
      describe = "NAS vero, area di prova (sessione SMB di produzione e primitive vere)",
      firstRound = firstRound,
    )
  }

  private suspend fun negotiateGuid(credentials: FacesAreaCredentials, guard: FacesAreaGuard, smb: SmbTransport, binding: SocketBinding): String {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      val gate = FakeNasRouteGate().apply { allow(Via.HOME_LAN, binding) }
      val breakers = AuthBreakerRegistry(InMemoryBreakerStore(), scope)
      val monotonic = MonotonicClock { System.nanoTime() / NANOS_PER_MILLI }
      // Solo la NEGOTIATE (nessun login): passa dal trasporto recintato come tutto il resto.
      val fenced = FacesAreaTransport(smb, guard, guard.roundRoot(0), FacesAuthLatch())
      return SmbConnectionTester(gate, breakers, fenced, monotonic, io = Dispatchers.IO, shareGuard = ShareFence)
        .run(TestTarget(NasAddress(credentials.host), null, null, null), null, setOf(Stage.ROUTE, Stage.TCP, Stage.NEGOTIATE))
        .negotiation?.serverGuid
        ?: throw AssertionError("il NAS di prova non risponde alla NEGOTIATE")
    } finally {
      scope.cancel()
    }
  }

  /** L'area sul simulatore SMB (nessuna rete): lo stesso codice, lo stesso recinto, la stessa pulizia. [password] diversa da quella del simulatore = login rifiutato. */
  fun simulated(password: String = SIM_PASSWORD, runId: String = SIM_RUN, firstRound: Int = 0): Pair<FacesArea, InMemorySmbBackend> {
    val backend = InMemorySmbBackend().apply {
      addUser("test", SIM_PASSWORD)
      listOf("test", "test-comune").forEach { addShare(it, mapOf("test" to SimAccess.READ_WRITE)) }
    }
    val credentials = FacesAreaCredentials("192.168.178.58", "test", password.toCharArray(), "test", "test-comune", backend.serverGuid)
    val guard = FacesAreaGuard.of(credentials, runId)
    val area = FacesArea(
      credentials, guard, backend, backend.binding("pc"), backend.serverGuid,
      realTime = false,
      describe = "simulatore SMB dietro la guardia dell'area di prova",
      cleanupRetries = 0,
      cleanupPauseMs = 0,
      firstRound = firstRound,
    )
    return area to backend
  }

  const val SIM_PASSWORD = "pw-del-banco"
  const val SIM_RUN = "20261029-101500-1a2b3c4d"
  private const val NANOS_PER_MILLI = 1_000_000L
}

/** L'autotest della guardia (come `ChaosAreaSelfTest`): tutto cio' che deve essere rifiutato lo e' davvero, con ZERO chiamate al vero. */
internal object FacesAreaSelfTest {

  class Result(val cases: Int, val failures: List<String>)

  /** Un trasporto "vero" finto, che conta cio' che gli arriva e ricorda l'ultimo percorso, e un login che si puo' far rifiutare. */
  private class Dummy {
    val reached = AtomicInteger(0)
    val authAttempts = AtomicInteger(0)
    var lastPath: String? = null
    var rejectLogin = false

    val transport = object : SmbTransport {
      override fun connect(binding: SocketBinding, address: NasAddress, connectTimeout: Duration): SmbConnection = connection
    }

    private val connection = object : SmbConnection {
      override val negotiation: Negotiation get() = throw UnsupportedOperationException()
      override val isConnected: Boolean get() = true

      override fun authenticate(user: String, password: CharArray): SmbSession {
        authAttempts.incrementAndGet()
        if (rejectLogin) throw SmbStatusException(NtStatus.LOGON_FAILURE)
        return session
      }

      override fun close() = Unit
    }

    private val session = object : SmbSession {
      override val facts: SessionFacts get() = throw UnsupportedOperationException()

      override fun connectShare(name: String): SmbShare {
        reached.incrementAndGet()
        return share
      }

      override fun close() = Unit
    }

    private fun hit(path: String) {
      reached.incrementAndGet()
      lastPath = path
    }

    private val share = object : SmbShare {
      override val name: String get() = "dummy"

      override fun openRead(path: String): SmbReadFile = hit(path).let { throw UnsupportedOperationException() }

      override fun createExclusive(path: String, shareMode: SmbOpenMode): SmbWriteFile = hit(path).let { throw UnsupportedOperationException() }

      override fun mkdir(path: String) = hit(path)

      override fun openDirectory(path: String, access: DirAccess): SmbDirectory = hit(path).let { throw UnsupportedOperationException() }

      override fun stat(path: String): SmbFileInfo = hit(path).let { throw UnsupportedOperationException() }

      override fun removeEmptyDirectory(path: String) = hit(path)

      override fun deleteFile(path: String) = hit(path)

      override fun space(): SmbSpace = throw UnsupportedOperationException()

      override fun openForChange(path: String, exclusive: Boolean): SmbChangeHandle = hit(path).let { throw UnsupportedOperationException() }

      override fun close() = Unit
    }
  }

  fun run(guard: FacesAreaGuard): Result {
    val failures = ArrayList<String>()
    var cases = 0
    val dummy = Dummy()
    val latch = FacesAuthLatch()
    val root = guard.roundRoot(0)
    val transport = FacesAreaTransport(dummy.transport, guard, root, latch)
    val connection = transport.connect(DirectSocketBinding("autotest"), NasAddress("192.168.178.58"), Duration.ZERO)

    fun mustRefuse(what: String, block: () -> Unit) {
      cases++
      val before = dummy.reached.get() + dummy.authAttempts.get()
      try {
        block()
        failures += "accettato: $what"
      } catch (_: FacesAreaViolation) {
        // atteso
      }
      if (dummy.reached.get() + dummy.authAttempts.get() != before) failures += "il vero ha ricevuto una chiamata per un rifiuto: $what"
    }

    for (account in listOf("admin", "ALESSIO", "Pampa", "root", "Administrator", " admin ", "ａｄｍｉｎ", "test2", "test3", "")) {
      mustRefuse("account '$account'") { guard.checkAccount(account) }
      mustRefuse("login con l'account '$account'") { connection.authenticate(account, "x".toCharArray()) }
    }
    for (share in listOf("alessio", "pampa", "NAS", "Public", "public", "ＮＡＳ", "test ", "ｔｅｓｔ", "test2", "TEST2", "test2x", "", "..", "mcg-it", "test/..", "test-comune2")) {
      mustRefuse("share '$share'") { guard.checkShare(share) }
    }
    // Le share di prova passano, e il percorso arriva radicato in `mcg-it/<giro>/r0`.
    val session = connection.authenticate("test", "x".toCharArray())
    for (share in listOf("test", "TEST", "test-comune", "Test-Comune")) {
      cases++
      runCatching { session.connectShare(share) }.onFailure { failures += "rifiutata una share di prova: $share" }
    }
    for (share in listOf("alessio", "NAS", "Public", "test2", "ＮＡＳ", "test ")) mustRefuse("tree connect verso '$share'") { session.connectShare(share) }
    val shared = session.connectShare("test")
    val expectedPrefix = root.components.joinToString("\\") + "\\"
    for (path in listOf("Foto/a.jpg", ".mycloudgallery/faces/x", "a\\b\\c")) {
      cases++
      shared.mkdir(path)
      if (dummy.lastPath?.startsWith(expectedPrefix) != true) failures += "percorso non radicato: $path"
    }
    cases++
    shared.mkdir("")
    if (dummy.lastPath != root.components.joinToString("\\")) failures += "la radice del mondo non e' la radice"
    // I percorsi che escono non arrivano mai al vero.
    for (raw in listOf("../x", "a/../../x", "/x", "\\x", "\\\\server\\x", "C:\\x", "a/./b", ".", "..", "a//b", "a/", "a\\..\\..\\b", "x:y")) {
      mustRefuse("percorso '$raw' (stat)") { shared.stat(raw) }
      mustRefuse("percorso '$raw' (cancellazione)") { shared.deleteFile(raw) }
      mustRefuse("percorso '$raw' (cartella)") { shared.mkdir(raw) }
    }
    // Un login rifiutato non si ripete: il secondo tentativo non arriva al NAS.
    cases++
    dummy.rejectLogin = true
    runCatching { connection.authenticate("test", "sbagliata".toCharArray()) }
    val attemptsAfterFirst = dummy.authAttempts.get()
    runCatching { connection.authenticate("test", "sbagliata".toCharArray()) }
    runCatching { connection.authenticate("test", "sbagliata".toCharArray()) }
    if (!latch.failed) failures += "il login rifiutato non ha fermato il banco"
    if (dummy.authAttempts.get() != attemptsAfterFirst) failures += "dopo un login rifiutato sono arrivati altri tentativi al NAS"
    return Result(cases, failures)
  }
}

internal object FacesAreaRunner {

  class Settings(
    /** Le interruzioni da far cadere: 400 sul NAS vero. Una prova breve ne usa meno. */
    val interruptions: Int,
    val baseSeed: Long,
    /** Il primo giro: con lo stesso seme di base e le interruzioni del giro rifa' un giro rosso sul NAS vero. */
    val firstRound: Int = 0,
    val config: ChaosConfig = defaultConfig(),
  )

  /**
   * Il giro sul NAS vero: stessi passi del banco JVM, nessuno spazio da cambiare e nessun cestino di rete, un po' meno lavoro per passo. [real]: le attese del
   * gestore della sessione (un handle che il NAS non ha ancora chiuso) sono di orologio vero, quindi l'assestamento aspetta davvero fra un giro e l'altro.
   */
  fun defaultConfig(real: Boolean = true): ChaosConfig =
    ChaosConfig(Capabilities.REAL, minSteps = 3, maxSteps = 5, settleTurns = if (real) 14 else 10, finalTurns = if (real) 30 else 24, settlePauseMs = if (real) 600 else 0, dense = true)

  /** Fa i giri necessari: si ferma appena le interruzioni cadute sono [Settings.interruptions] (al massimo `4 x + 5` giri), o a un login rifiutato. */
  suspend fun run(area: FacesArea, settings: Settings, progress: ((rounds: Int, red: Int, fired: Int) -> Unit)? = null): ChaosResult {
    val stats = ChaosStats()
    return ChaosBench.run(
      rounds = settings.interruptions * 4 + 5,
      baseSeed = settings.baseSeed,
      factory = area.factory(),
      config = settings.config,
      stats = stats,
      firstRound = settings.firstRound,
      stopAfterInterruptions = settings.interruptions,
      stopReason = { area.stopReason() },
      onRound = { done, red -> progress?.invoke(done, red, stats.totalFired) },
    )
  }
}
