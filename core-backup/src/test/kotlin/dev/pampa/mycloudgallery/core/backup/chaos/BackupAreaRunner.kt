package dev.pampa.mycloudgallery.core.backup.chaos

import dev.pampa.mycloudgallery.core.backup.testing.FakeBackupClock
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.Sha256
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
import dev.pampa.mycloudgallery.core.nas.ops.PublishOutcome
import dev.pampa.mycloudgallery.core.nas.ops.TempFinish
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupOps
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupOpsFactory
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupPaths
import dev.pampa.mycloudgallery.core.nas.session.MonotonicClock
import dev.pampa.mycloudgallery.core.nas.session.SmbNasSessionManager
import dev.pampa.mycloudgallery.core.nas.smb.SmbTransport
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryBreakerStore
import dev.pampa.mycloudgallery.core.nas.tools.SmbConnectionTester
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.SocketBinding
import dev.pampa.mycloudgallery.core.network.contract.Via
import dev.pampa.mycloudgallery.core.network.testing.DirectSocketBinding
import dev.pampa.mycloudgallery.core.network.testing.FakeNasRouteGate
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.File
import java.util.Locale
import java.util.Properties
import kotlin.random.Random
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// L'AREA DI PROVA del backup (docs/fase5-progetto.md §5.3, §5.4): il runner contro il NAS vero (>= 400 interruzioni) e la verifica dell'area che
// si fa anche da sola, ad esempio dopo la prova sul telefono (`tools/chaos/s25.ps1 -Mode backup`). Tutto passa dalla guardia (BackupAreaGuard.kt)
// e dagli stessi giri e invarianti del banco JVM: stesso `BackupChaosRound`, con il "telefono" finto che serve file veri o virtuali (1 MB, 200 MB e
// `backup.bigFileMb`) e il NAS vero sotto `mcg-it/<giro>/r<N>/`.

// =============================================================================================================================
// Il runner
// =============================================================================================================================

object BackupAreaRunner {

  /** Se e come il giro include la fase 5B (P13): versioni, scambi, guardiano e "Libera spazio" sul NAS vero. */
  enum class FiveB(val code: String) {
    /** Solo il caricamento (la 5A, com'era). */
    OFF("off"),

    /** Il caricamento e poi la 5B nello stesso giro. */
    WITH("with"),

    /** Solo la 5B: i media li semina il banco, nessun caricamento prima (`area.ps1 -Mode backup -Stage run5b`). */
    ONLY("only"),
    ;

    companion object {
      fun of(raw: String?): FiveB = entries.firstOrNull { it.code == raw?.lowercase() } ?: OFF
    }
  }

  class Settings(
    /** Le interruzioni da far cadere (`backup.rounds`): 400 sul PC. Una prova breve ne usa al massimo 20. */
    val interruptions: Int,
    val baseSeed: Long,
    val mediumBytes: Long = 1L shl 20,
    /** Il video grande che ogni tanto c'e' (200 MB). */
    val bigBytes: Long = 200L shl 20,
    val bigPercent: Int = 3,
    /** Il video enorme di E-B1 (`backup.bigFileMb`, 3 GB) e quante volte su mille un giro lo ha. */
    val hugeBytes: Long? = null,
    val hugePermille: Int = 8,
    /** Il primo giro: con lo stesso seme di base rifa' un giro rosso sul NAS vero. */
    val firstRound: Int = 0,
    val fiveB: FiveB = FiveB.OFF,
  )

  fun config(settings: Settings): BackupChaosConfig = BackupChaosConfig(
    minPhotos = if (settings.fiveB == FiveB.ONLY) 0 else 1,
    maxPhotos = if (settings.fiveB == FiveB.ONLY) 0 else 3,
    maxVideos = if (settings.fiveB == FiveB.ONLY) 0 else 1,
    sizeOf = { random, kind ->
      when {
        kind != MediaKind.VIDEO -> settings.mediumBytes + random.nextInt(settings.mediumBytes.toInt() / 2)
        settings.hugeBytes != null && random.nextInt(1_000) < settings.hugePermille -> settings.hugeBytes
        random.nextInt(100) < settings.bigPercent -> settings.bigBytes
        else -> 3 * settings.mediumBytes
      }
    },
    minSteps = if (settings.fiveB == FiveB.ONLY) 0 else 2,
    maxSteps = if (settings.fiveB == FiveB.ONLY) 0 else 3,
    mix = FaultMix.SINGLE,
    stepFaults = 1,
    // Sul NAS vero un file non cambiato non si rilegge a ogni verifica; la rilettura finale di ogni giro e' sempre completa.
    hashCache = true,
    phoneEventPercent = 10,
    oldFilesPercent = 0,
    fiveB = if (settings.fiveB == FiveB.OFF) null else fiveBConfig(settings),
  )

  /**
   * La fase 5B sul NAS vero: un colpo per passo, file da 1 MB (le versioni e gli scambi sono rinomine e un caricamento per modifica), niente colpi del
   * mondo che il NAS vero non fa (il PC che modifica un file, l'orologio che avanza, un byte che marcisce: [NasCaps]); la cache dei timbri nelle
   * verifiche a meta', la rilettura finale sempre completa.
   */
  fun fiveBConfig(settings: Settings): Backup5BConfig = Backup5BConfig(
    only = settings.fiveB == FiveB.ONLY,
    minMedia = 3,
    maxMedia = 4,
    minSteps = 3,
    maxSteps = 4,
    mix = FaultMix.SINGLE,
    stepFaults = 1,
    events = true,
    hashCache = true,
    sizeOf = { random -> settings.mediumBytes.toInt() + random.nextInt(settings.mediumBytes.toInt() / 2) },
  )

  /** Fa i giri necessari. Si ferma appena le interruzioni cadute sono [Settings.interruptions] (ma al massimo `4 x + 5` giri). */
  suspend fun run(
    target: BackupChaosTarget,
    factory: BackupChaosStackFactory,
    settings: Settings,
    statesDir: File? = null,
    progress: ((rounds: Int, red: Int, fired: Int) -> Unit)? = null,
  ): BenchResult {
    val stats = ChaosStats()
    return BackupChaosBench.run(
      rounds = settings.interruptions * 4 + 5,
      baseSeed = settings.baseSeed,
      target = target,
      factory = factory,
      config = config(settings),
      stats = stats,
      stopAfterInterruptions = settings.interruptions,
      firstRound = settings.firstRound,
      // Niente nomi di file nei rapporti del NAS vero.
      redact = true,
      statesDir = statesDir,
      onRound = { done, red -> progress?.invoke(done, red, stats.totalFired) },
    )
  }
}

// =============================================================================================================================
// Il NAS vero (o il simulatore, dietro lo stesso aggancio) come bersaglio
// =============================================================================================================================

/**
 * Cio' che serve a toccare il NAS: la sessione di prova e i bersagli del runner. Per il NAS vero e' [SmbBackupArea] con smbj; i test la provano
 * con il simulatore SMB, senza rete.
 */
interface BackupAreaBinding {
  /** Il bersaglio del runner per [guard]: ogni giro e' un mondo radicato in `mcg-it/<giro>/r<N>`. La prima connessione si fa qui. */
  suspend fun openTarget(credentials: BackupAreaCredentials, guard: BackupAreaGuard): BackupChaosTarget

  /** Solo la lettura dell'area per la verifica: il lettore vede la share vera (non radicato). */
  suspend fun openReader(credentials: BackupAreaCredentials, guard: BackupAreaGuard): NasMediaReader

  /** Chiude le sessioni. */
  suspend fun close()
}

/**
 * L'aggancio vero e proprio, con il trasporto e il socket iniettabili: smbj e il socket diretto del PC per il NAS vero, il simulatore SMB di
 * core-nas per provarlo senza rete. Cosa garantisce, oltre alla guardia:
 *  - la guardia si consulta PRIMA del primo socket (account e share di prova);
 *  - la sessione ha la recinzione D33 ([ShareFence]): un account di prova non apre altre share nemmeno per sbaglio;
 *  - la password si prova UNA volta: se il login non riesce la prova si ferma, senza nuovi tentativi (niente blocchi dell'account);
 *  - le primitive di scrittura sono quelle di produzione (`NasBackupOpsFactory.createForTestArea`) radicate nella cartella del giro, e la
 *    lettura e' radicata nello stesso posto ([RootedReader]): nessun percorso del banco esce da `mcg-it/<giro>/r<N>/`.
 */
class SmbBackupArea(
  private val transport: () -> SmbTransport,
  private val socket: () -> SocketBinding = { DirectSocketBinding("pc-cablato") },
  private val io: CoroutineDispatcher = Dispatchers.IO,
  private val backoff: suspend (Duration) -> Unit = { delay(it) },
) : BackupAreaBinding {

  private class Session(
    val manager: SmbNasSessionManager,
    val scope: CoroutineScope,
    val shares: List<String>,
    val deviceId: String,
  )

  private val mutex = Mutex()
  private var open: Session? = null

  /** Quante sessioni sono state aperte (una per aggancio): la password si prova una volta sola. */
  @Volatile
  var sessionsOpened: Int = 0
    private set

  override suspend fun openTarget(credentials: BackupAreaCredentials, guard: BackupAreaGuard): BackupChaosTarget {
    val session = session(credentials, guard)
    return object : BackupChaosTarget {
      override val name: String = "NAS vero, area di prova (sessione SMB di produzione)"

      override suspend fun openWorld(round: Int): BackupChaosWorld {
        val clock = FakeBackupClock(nasNow = session.manager.nasClock.nowMillisOrNull() ?: FakeBackupClock.DEFAULT_NAS_NOW)
        return BackupChaosWorld(
          clock,
          AreaNasSide(session.manager, session.shares, session.deviceId, guard.roundRoot(round)),
          session.deviceId,
          // Le misure di produzione: scritture da 1 MiB, un punto di ripresa ogni 64 MiB (D5).
          chunkBytes = 1 shl 20,
          checkpointBytes = 64L shl 20,
        )
      }
    }
  }

  override suspend fun openReader(credentials: BackupAreaCredentials, guard: BackupAreaGuard): NasMediaReader =
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

  private suspend fun session(credentials: BackupAreaCredentials, guard: BackupAreaGuard): Session = mutex.withLock {
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
      Session(manager, scope, shares, deviceId).also { open = it }
    } catch (e: Throwable) {
      scope.cancel()
      throw e
    }
  }

  private companion object {
    const val NANOS_PER_MILLI = 1_000_000L
  }
}

/** Un giro sul NAS vero: tutto radicato in [root] (`mcg-it/<giro>/r<N>`), la cartella dell'app compresa. Il NAS vero non fa passare il tempo ne' lascia togliere file. */
class AreaNasSide(
  private val manager: SmbNasSessionManager,
  override val shares: List<String>,
  private val deviceId: String,
  root: SharePath,
) : NasSide {
  override val reader: NasMediaReader = RootedReader(manager.mediaReader(), root)
  override val ops: NasBackupOps = NasBackupOpsFactory.createForTestArea(manager, deviceId, root)
  override val caps: NasCaps = NasCaps(advanceTime = false, housekeeping = false)

  private var putCounter = 0

  /** Il "PC" scrive un file intero: temporaneo proprio, scrittura, pubblicazione senza sostituire. Nessun colpo. */
  override suspend fun pcPut(share: String, path: String, bytes: ByteArray, modifiedAt: Long) {
    val purpose = Purpose.BACKGROUND
    val target = SharePath.parse(path)
    target.parent()?.let { dir -> ops.mkdirs(share, dir, purpose).orFail("cartella") }
    val temp = NasBackupPaths.tempFor(deviceId, FOREIGN_ITEM_BASE + putCounter++)
    val writer = ops.openTemp(share, temp, purpose).orFail("temporaneo")
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
      val outcome = ops.publishTemp(share, temp, target, purpose).orFail("pubblicazione")
      check(outcome is PublishOutcome.Published) { "file del PC non pubblicato: ${outcome::class.simpleName}" }
      published = true
    } finally {
      if (!published) ops.deleteOwnTemp(share, temp, purpose)
    }
  }

  override suspend fun remove(share: String, path: String) = throw UnsupportedOperationException("sul NAS vero il banco non toglie file")

  override fun advanceTime(ms: Long) = throw UnsupportedOperationException("il NAS vero non fa passare il tempo")

  override suspend fun connectionLost() = manager.invalidate(InvalidateReason.SUSPECT_CONNECTION)

  override fun describe(): String = "NAS vero, area di prova"

  override fun close() = Unit

  private fun <T> NasResult<T>.orFail(what: String): T = when (this) {
    is NasResult.Ok -> value
    is NasResult.Err -> throw IllegalStateException("file del PC: $what non riuscita (${error::class.simpleName})")
  }

  private companion object {
    const val PUT_CHUNK = 1 shl 20

    /** Gli elementi "del PC" hanno numeri che il diario del backup non raggiunge mai: i loro temporanei non si confondono con quelli del motore. */
    const val FOREIGN_ITEM_BASE = 9_000_000L
  }
}

// =============================================================================================================================
// Il cancello per il NAS vero
// =============================================================================================================================

/** Se e come si puo' toccare il NAS vero adesso. Nessuna lettura di rete qui: solo variabili d'ambiente, un file locale e la guardia. */
object BackupAreaGate {

  sealed interface Outcome {
    class Ready(val credentials: BackupAreaCredentials, val guard: BackupAreaGuard) : Outcome

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
    val credentials = BackupAreaCredentials.parse(Properties().apply { file.inputStream().use { load(it) } })
      ?: return Outcome.Skip("file delle credenziali incompleto (user, password, share)")
    // La guardia PRIMA di qualunque socket: un errore nel file fa fallire la prova, non la salta.
    val guard = BackupAreaGuard.of(credentials, runId)
    if (interruptions(credentials) == 0) return Outcome.Skip("backup.rounds=0")
    return Outcome.Ready(credentials, guard)
  }

  /**
   * Le interruzioni da far cadere: `backup.rounds` del file, poi `MCG_BACKUP_ROUNDS`, poi 400; **mai piu' di `MCG_BACKUP_CAP`** se c'e' (lo imposta
   * `area.ps1` a `-Interruptions`: un giro breve di prova non diventa per sbaglio quello da 400 perche' il file ha `backup.rounds=400`).
   */
  fun interruptions(credentials: BackupAreaCredentials): Int {
    val wanted = credentials.rounds ?: System.getenv("MCG_BACKUP_ROUNDS")?.toIntOrNull() ?: DEFAULT_INTERRUPTIONS
    val cap = System.getenv("MCG_BACKUP_CAP")?.toIntOrNull()?.takeIf { it >= 0 }
    return if (cap == null) wanted else minOf(wanted, cap)
  }

  /** Il video enorme di E-B1: `backup.bigFileMb` del file (3.072 MB = 3 GB nel brief); senza la chiave non c'e'. */
  fun hugeBytes(credentials: BackupAreaCredentials): Long? = credentials.bigFileMb?.let { it.coerceIn(1, 4_096).toLong() shl 20 }

  const val DEFAULT_INTERRUPTIONS = 400
}

// =============================================================================================================================
// Il manifesto del telefono e la verifica dell'area da sola
// =============================================================================================================================

/**
 * Cio' che serve a verificare l'area dopo una prova fatta da un'altra parte (il telefono): i file che il telefono aveva (impronta, dimensione,
 * etichetta: `adb shell sha256sum`), dove il backup scrive, dove sta la cartella dell'app, e i file che c'erano gia'. Un file di testo, una riga
 * per voce (`# mcg-backup-manifest 1`).
 *
 * ```
 * # mcg-backup-manifest 1
 * run=20261016-101500-1a2b3c4d
 * device=<id del dispositivo del backup: i suoi temporanei>           (vuoto = qualunque dispositivo)
 * dest=test|mcg-campo                                                  (share|cartella sotto cui sta Backup/; cartella vuota = radice)
 * app=test|                                                            (share|cartella sotto cui sta .mycloudgallery/)
 * phone=<sha256>|<byte>|<etichetta>                                    (un file del telefono)
 * pre=<sha256>|<share>|<percorso>                                      (un file gia' presente in Backup/ prima del primo backup)
 * edit=<sha256 vecchio>|<sha256 nuovo>|<etichetta>                     (5B: un file del telefono modificato dopo il primo backup, come Samsung "Salva")
 * ```
 */
class BackupAreaManifest(
  val runId: String,
  val deviceId: String?,
  val dest: List<Pair<String, SharePath?>>,
  val app: List<Pair<String, SharePath?>>,
  val phone: List<PhoneFile>,
  val pre: List<Pre>,
  val edits: List<Edit> = emptyList(),
) {
  class PhoneFile(val sha: Sha256, val size: Long, val label: String)

  class Pre(val sha: Sha256, val share: String, val path: String)

  /** Una modifica sul telefono dopo il primo backup: l'originale ([old]) deve stare in `Versioni/` (o al solito percorso), la modifica ([new]) al solito percorso. */
  class Edit(val old: Sha256, val new: Sha256, val label: String)

  fun write(file: File) {
    file.parentFile?.mkdirs()
    file.writeText(text(), Charsets.UTF_8)
  }

  fun text(): String = buildString {
    appendLine("# mcg-backup-manifest 1")
    appendLine("run=$runId")
    appendLine("device=${deviceId.orEmpty()}")
    dest.forEach { appendLine("dest=${it.first}|${it.second?.value.orEmpty()}") }
    app.forEach { appendLine("app=${it.first}|${it.second?.value.orEmpty()}") }
    phone.forEach { appendLine("phone=${it.sha}|${it.size}|${it.label}") }
    pre.forEach { appendLine("pre=${it.sha}|${it.share}|${it.path}") }
    edits.forEach { appendLine("edit=${it.old}|${it.new}|${it.label}") }
  }

  companion object {
    fun read(file: File): BackupAreaManifest = parse(file.readLines(Charsets.UTF_8))

    fun parse(lines: List<String>): BackupAreaManifest {
      var run: String? = null
      var device: String? = null
      val dest = ArrayList<Pair<String, SharePath?>>()
      val app = ArrayList<Pair<String, SharePath?>>()
      val phone = ArrayList<PhoneFile>()
      val pre = ArrayList<Pre>()
      val edits = ArrayList<Edit>()
      for ((number, raw) in lines.withIndex()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        fun bad(): Nothing = throw IllegalArgumentException("manifesto: riga ${number + 1} non valida")
        val key = line.substringBefore('=')
        val value = line.substringAfter('=', "")
        val p = value.split('|')
        when (key) {
          "run" -> run = value
          "device" -> device = value.ifEmpty { null }
          "dest" -> {
            if (p.size != 2) bad()
            dest += p[0] to p[1].takeIf { it.isNotEmpty() }?.let(SharePath::parse)
          }
          "app" -> {
            if (p.size != 2) bad()
            app += p[0] to p[1].takeIf { it.isNotEmpty() }?.let(SharePath::parse)
          }
          "phone" -> {
            if (p.size < 3) bad()
            phone += PhoneFile(Sha256.parse(p[0]), p[1].toLongOrNull() ?: bad(), p.drop(2).joinToString("|"))
          }
          "pre" -> {
            if (p.size < 3) bad()
            pre += Pre(Sha256.parse(p[0]), p[1], p.drop(2).joinToString("|"))
          }
          "edit" -> {
            if (p.size < 3) bad()
            edits += Edit(Sha256.parse(p[0]), Sha256.parse(p[1]), p.drop(2).joinToString("|"))
          }
          else -> bad()
        }
      }
      if (dest.isEmpty()) throw IllegalArgumentException("manifesto senza 'dest'")
      return BackupAreaManifest(run ?: throw IllegalArgumentException("manifesto senza 'run'"), device, dest, app.ifEmpty { dest }, phone, pre, edits)
    }
  }
}

/** L'esito di una verifica dell'area. */
class AreaVerification(val violations: List<Violation>, val filesHashed: Int, val bytesHashed: Long, val settled: Boolean) {
  val ok: Boolean get() = violations.isEmpty()

  fun text(): String = buildString {
    appendLine("=== Verifica dell'area di prova del backup (${if (settled) "a lavoro finito" else "a meta': solo gli invarianti che valgono sempre"}) ===")
    appendLine("file riletti con SHA-256: $filesHashed (${bytesHashed / 1024} KiB)")
    if (violations.isEmpty()) {
      appendLine("TUTTI GLI INVARIANTI RISPETTATI")
    } else {
      appendLine("${violations.size} VIOLAZIONI")
      violations.take(50).forEach { appendLine("  $it") }
    }
  }
}

/**
 * Verifica un'area dal manifesto, anche dopo una prova fatta da un'altra parte (il telefono). Legge soltanto: non ha nessuna primitiva di
 * scrittura. Gli invarianti che il NAS puo' dire da solo (il diario locale e' sul telefono): 1 (ogni file in `Backup/` ha i byte di un file
 * del telefono o di uno che c'era), 2 (nessun file del telefono due volte), 3 (quelli che c'erano non sono cambiati), 4 (a lavoro finito ogni
 * file del telefono c'e'), 5 (rilettura completa, senza cache), 6 (a lavoro finito nessun temporaneo `-bk-`), 11 (una lettura che non riesce non
 * passa in silenzio). Nessun nome di file nel rapporto.
 */
object BackupAreaVerifier {

  suspend fun verify(reader: NasMediaReader, manifest: BackupAreaManifest, settled: Boolean): AreaVerification {
    val violations = ArrayList<Violation>()
    fun add(invariant: Invariant, detail: String) {
      violations += Violation(invariant, detail)
    }
    var hashed = 0
    var bytes = 0L

    // I file visibili: tutto Backup/ sotto ogni destinazione.
    val visible = ArrayList<NasFileFact>()
    for ((share, dir) in manifest.dest) {
      val backupRoot = (dir?.child("Backup")) ?: SharePath.of("Backup")
      val scan = BackupNasScanner(reader, listOf(ScanRoot(share, backupRoot))).scan(allowCache = false)
      visible += scan.files
      hashed += scan.filesHashed
      bytes += scan.bytesHashed
      scan.problems.forEach { add(Invariant.NAS_READS, it) }
    }
    // 5B: le versioni stanno in `Versioni/` alla radice della share (Q2 (a)): archiviate, mai tolte. Cartella che puo' non esserci ancora.
    val versions = ArrayList<NasFileFact>()
    if (manifest.edits.isNotEmpty()) {
      for ((share, _) in manifest.dest) {
        val scan = BackupNasScanner(reader, listOf(ScanRoot(share, VERSIONS_ROOT))).scan(allowCache = false)
        versions += scan.files
        hashed += scan.filesHashed
        bytes += scan.bytesHashed
        scan.problems.forEach { add(Invariant.NAS_READS, it) }
      }
    }
    // I temporanei: .mycloudgallery/tmp sotto ogni cartella dell'app (e' una cartella che puo' non esserci ancora).
    val temps = ArrayList<NasFileFact>()
    for ((share, dir) in manifest.app) {
      val tmpRoot = (dir?.child(NasLayout.ROOT.name)?.child("tmp")) ?: NasLayout.ROOT.child("tmp")
      val scan = BackupNasScanner(reader, listOf(ScanRoot(share, tmpRoot))).scan(allowCache = false)
      temps += scan.files
      scan.problems.forEach { add(Invariant.NAS_READS, it) }
    }

    val phoneShas = manifest.phone.map { it.sha }.toSet() + manifest.edits.map { it.new }
    val preShas = manifest.pre.map { it.sha }.toSet()
    val preKeys = manifest.pre.map { NasFileFact.keyOf(it.share, it.path) }.toSet()
    val fresh = visible.filter { it.key !in preKeys }

    // 3. Quelli che c'erano non sono cambiati.
    for (p in manifest.pre) {
      val now = visible.firstOrNull { it.key == NasFileFact.keyOf(p.share, p.path) }
      if (now == null) add(Invariant.REPLACED, "un file che c'era prima del backup non c'e' piu'")
      else if (now.sha != p.sha) add(Invariant.REPLACED, "un file che c'era prima del backup ha altri byte")
    }
    // 1. Nessun file a meta': ogni file nuovo ha i byte di un file del telefono.
    for (f in fresh) if (f.sha !in phoneShas) add(Invariant.HALF_FILE, "un file in Backup/ con byte che non sono di nessun file del telefono")
    // Dalla 5B anche in `Versioni/` (archiviati: gli originali e le modifiche) ci sono solo byte che il telefono ha avuto.
    for (f in versions) if (f.sha !in phoneShas && f.sha !in preShas) add(Invariant.HALF_FILE, "un file in Versioni/ con byte che non sono di nessun file del telefono")
    // 2. Nessun doppione: un file del telefono compare una volta sola (anche contando quelli che c'erano gia').
    val count = HashMap<Sha256, Int>()
    visible.forEach { count.merge(it.sha, 1, Int::plus) }
    versions.forEach { count.merge(it.sha, 1, Int::plus) }
    for (sha in phoneShas) if ((count[sha] ?: 0) > 1) add(Invariant.DUPLICATE, "un file del telefono compare ${count[sha]} volte in Backup/")
    // 4. Nessuna perdita (a lavoro finito).
    if (settled) {
      val missing = manifest.phone.count { (count[it.sha] ?: 0) == 0 && it.sha !in preShas }
      if (missing > 0) add(Invariant.LOSS, "$missing file del telefono non sono sul NAS")
    }
    // 5B, 12 e 13: ogni modifica fatta sul telefono dopo il primo backup. A lavoro finito: l'originale e la modifica ci sono, una sola volta ciascuna,
    // una al solito percorso (`Backup/`) e l'altra in `Versioni/` ("Ripristina originale" le scambia: va bene l'una o l'altra).
    if (settled) {
      for (e in manifest.edits) {
        val oldAtUsual = visible.count { it.sha == e.old }
        val newAtUsual = visible.count { it.sha == e.new }
        val oldInVersions = versions.count { it.sha == e.old }
        val newInVersions = versions.count { it.sha == e.new }
        if (newAtUsual + newInVersions == 0) {
          add(Invariant.USUAL_PATH, "una modifica del telefono non e' arrivata sul NAS")
        } else if (oldAtUsual + newAtUsual != 1) {
          add(Invariant.USUAL_PATH, "al solito percorso non c'e' una sola versione di una modifica del telefono")
        }
        if (oldAtUsual + oldInVersions == 0) {
          add(Invariant.VERSIONS_KEPT, "l'originale di una modifica del telefono non c'e' piu' sul NAS")
        } else if (newAtUsual + newInVersions > 0 && oldInVersions + newInVersions != 1) {
          add(Invariant.VERSIONS_KEPT, "in Versioni/ non c'e' una sola versione di una modifica del telefono")
        }
      }
    }
    // 6. Temporanei del backup: a lavoro finito nessuno.
    val ownTemps = temps.filter { t ->
      val name = t.path.substringAfterLast('/')
      if (manifest.deviceId != null) name.startsWith("${manifest.deviceId}-bk-") else BACKUP_TEMP.matches(name)
    }
    if (settled && ownTemps.isNotEmpty()) add(Invariant.TEMP, "${ownTemps.size} temporanei del backup rimasti a lavoro finito")
    return AreaVerification(violations, hashed, bytes, settled)
  }

  private val BACKUP_TEMP = Regex("^[A-Za-z0-9]{26}-bk-[A-Za-z0-9-]+$")

  /** `Versioni/` alla radice della share (Q2 (a)). */
  private val VERSIONS_ROOT: SharePath = SharePath.of("Versioni")
}
