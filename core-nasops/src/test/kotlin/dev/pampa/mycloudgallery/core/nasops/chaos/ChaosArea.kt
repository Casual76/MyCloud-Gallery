package dev.pampa.mycloudgallery.core.nasops.chaos

import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.File
import java.util.Locale
import java.util.Properties
import kotlin.random.Random

// L'AREA DI PROVA: il runner delle interruzioni contro il NAS vero (>= 400 interruzioni, §5.5) e la verifica dell'area che si fa anche da sola,
// ad esempio dopo la prova sul telefono. Tutto passa dalla guardia (ChaosAreaGuard.kt) e dagli stessi giri e invarianti del banco JVM.
//
// Stato (P15): il runner e il verificatore sono completi e provati contro il NAS finto dietro la stessa guardia (ChaosAreaRunnerTest). Per
// toccare il NAS vero manca UN pezzo, che consegna P12: una `ChaosAreaBinding` che apre una sessione di prova e dia le primitive di scrittura
// "radicate" in `mcg-it/<giro>/rN` (vedi docs/prova-500.md, "Cosa serve da P12"). Finche' non c'e', ogni prova contro il NAS vero si salta
// PRIMA di aprire un socket.

/** Un mondo avvolto dalla guardia: lettura e primitive passano dai controlli (e dai contatori) prima di arrivare al vero. */
class GuardedWorld(
  private val inner: ChaosWorld,
  guard: ChaosAreaGuard,
  counter: DelegateCounter,
) : ChaosWorld by inner {
  override val reader: NasMediaReader = GuardedReader(inner.reader, guard, counter)
  override val fileOps = GuardedFileOps(inner.fileOps, guard, counter)
}

/** Un bersaglio dietro la guardia. */
class GuardedTarget(
  private val inner: ChaosTarget,
  private val guard: ChaosAreaGuard,
  val counter: DelegateCounter = DelegateCounter(),
) : ChaosTarget {
  override val name: String = "${inner.name} (dietro la guardia, giro ${guard.runId.take(15)})"

  override suspend fun openWorld(round: Int): ChaosWorld = GuardedWorld(inner.openWorld(round), guard, counter)
}

/**
 * Il runner contro l'area di prova (§5.4, §5.5): giri brevi (2-3 passi) con UN colpo per passo, finche' le interruzioni cadute sono almeno
 * [interruptions]. File piccoli, uno da [ChaosAreaRunner.Settings.mediumBytes] e, ogni tanto, uno grande.
 */
object ChaosAreaRunner {
  class Settings(
    /** Le interruzioni da far cadere (`chaos.rounds`): 400 sul PC. Una prova breve ne usa al massimo 20. */
    val interruptions: Int,
    val baseSeed: Long,
    val mediumBytes: Int = 1 shl 20,
    val bigBytes: Int = 200 shl 20,
    val bigPercent: Int = 3,
    /** Il primo giro (`MCG_CHAOS_FIRST_ROUND`): con lo stesso seme di base rifa' un giro rosso sul NAS vero. */
    val firstRound: Int = 0,
  )

  fun config(settings: Settings): ChaosConfig = ChaosConfig(
    minSteps = 2,
    maxSteps = 3,
    minFiles = 4,
    maxFiles = 7,
    mediumFileBytes = settings.mediumBytes,
    bigFileBytes = settings.bigBytes,
    bigFilePercent = settings.bigPercent,
    mix = FaultMix.SINGLE,
    stepFaults = 1,
    // Sul NAS vero un file non cambiato non si rilegge a ogni verifica; la rilettura finale di ogni giro e' sempre completa.
    hashCache = true,
    idleRestartPercent = 10,
  )

  /** Fa i giri necessari. Si ferma appena le interruzioni cadute sono [Settings.interruptions] (ma al massimo `4 x + 5` giri). */
  suspend fun run(
    target: ChaosTarget,
    factory: ChaosStackFactory,
    settings: Settings,
    progress: ((rounds: Int, red: Int, fired: Int) -> Unit)? = null,
  ): BenchResult {
    val stats = ChaosStats()
    return ChaosBench.run(
      rounds = settings.interruptions * 4 + 5,
      baseSeed = settings.baseSeed,
      target = target,
      factory = factory,
      config = config(settings),
      stats = stats,
      stopAfterInterruptions = settings.interruptions,
      firstRound = settings.firstRound,
      onRound = { done, red -> progress?.invoke(done, red, stats.totalFired) },
    )
  }
}

// =============================================================================================================================
// Il manifesto e la verifica da sola
// =============================================================================================================================

/**
 * Cio' che serve a verificare un'area senza il banco che l'ha riempita: gli originali (impronta e dimensione), dove guardare e, se si sa,
 * dove devono stare alla fine. Un file di testo, una riga per voce (chiavi: `run`, `device`, `root`, `app`, `original`, `expect`).
 *
 * ```
 * # mcg-chaos-manifest 1
 * run=20261015-101500-1a2b3c4d
 * device=<id del dispositivo che opera: i suoi temporanei e il suo cestino>
 * root=test|mcg-it/20261015-101500-1a2b3c4d          i media stanno qui (share|cartella; cartella vuota = radice)
 * app=test||0                                         .mycloudgallery sta qui (share|cartella di base|1 se leggerlo tutto)
 * original=<sha256>|<byte>|<etichetta>
 * expect=<sha256>|visible|<share>|<cartella relativa a root>      (facoltativa: dove deve stare, una volta sola)
 * expect=<sha256>|trash                                           (facoltativa: solo nel cestino)
 * expect=<sha256>|gone                                            (facoltativa: eliminato per sempre)
 * ```
 * Un originale senza `expect` deve comparire una volta sola fra i file visibili, o (se spostato da un altro dispositivo) nel cestino.
 */
class ChaosManifest(
  val runId: String,
  val deviceId: String?,
  val roots: List<ScanScope.MediaRoot>,
  val appAreas: List<ScanScope.AppArea>,
  val originals: List<Original>,
  val expectations: Map<Sha256, Expect>,
) {
  sealed interface Expect {
    data class Visible(val share: String?, val dir: String?) : Expect

    data object Trash : Expect

    data object Gone : Expect
  }

  fun write(file: File) {
    file.parentFile?.mkdirs()
    val text = buildString {
      appendLine("# mcg-chaos-manifest 1")
      appendLine("run=$runId")
      appendLine("device=${deviceId.orEmpty()}")
      roots.forEach { appendLine("root=${it.share}|${it.dir?.value.orEmpty()}") }
      appAreas.forEach { appendLine("app=${it.share}|${it.base?.value.orEmpty()}|${if (it.full) 1 else 0}") }
      originals.forEach { appendLine("original=${it.sha}|${it.size}|${it.label}") }
      expectations.forEach { (sha, e) ->
        when (e) {
          is Expect.Visible -> appendLine("expect=$sha|visible|${e.share.orEmpty()}|${e.dir.orEmpty()}")
          Expect.Trash -> appendLine("expect=$sha|trash")
          Expect.Gone -> appendLine("expect=$sha|gone")
        }
      }
    }
    file.writeText(text, Charsets.UTF_8)
  }

  companion object {
    fun read(file: File): ChaosManifest = parse(file.readLines(Charsets.UTF_8))

    fun parse(lines: List<String>): ChaosManifest {
      var run: String? = null
      var device: String? = null
      val roots = ArrayList<ScanScope.MediaRoot>()
      val apps = ArrayList<ScanScope.AppArea>()
      val originals = ArrayList<Original>()
      val expectations = LinkedHashMap<Sha256, Expect>()
      for ((number, raw) in lines.withIndex()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        val key = line.substringBefore('=')
        val value = line.substringAfter('=', "")
        val parts = value.split('|')
        fun bad(): Nothing = throw IllegalArgumentException("manifesto: riga ${number + 1} non valida")
        when (key) {
          "run" -> run = value
          "device" -> device = value.ifEmpty { null }
          "root" -> {
            if (parts.size != 2) bad()
            roots += ScanScope.MediaRoot(parts[0], parts[1].takeIf { it.isNotEmpty() }?.let(SharePath::parse))
          }
          "app" -> {
            if (parts.size != 3) bad()
            apps += ScanScope.AppArea(parts[0], parts[1].takeIf { it.isNotEmpty() }?.let(SharePath::parse), parts[2] == "1")
          }
          "original" -> {
            if (parts.size < 3) bad()
            originals += Original(originals.size, Sha256.parse(parts[0]), parts[1].toLongOrNull() ?: bad(), null, parts.drop(2).joinToString("|"))
          }
          "expect" -> {
            if (parts.size < 2) bad()
            val sha = Sha256.parse(parts[0])
            expectations[sha] = when (parts[1]) {
              "visible" -> Expect.Visible(parts.getOrNull(2)?.ifEmpty { null }, parts.getOrNull(3)?.ifEmpty { null })
              "trash" -> Expect.Trash
              "gone" -> Expect.Gone
              else -> bad()
            }
          }
          else -> bad()
        }
      }
      return ChaosManifest(
        runId = run ?: throw IllegalArgumentException("manifesto senza 'run'"),
        deviceId = device,
        roots = roots,
        appAreas = apps,
        originals = originals,
        expectations = expectations,
      )
    }
  }
}

/** L'esito di una verifica dell'area. */
class AreaVerification(val violations: List<Violation>, val snapshot: Snapshot, val settled: Boolean) {
  val ok: Boolean get() = violations.isEmpty()

  fun text(): String = buildString {
    appendLine("=== Verifica dell'area di prova (${if (settled) "a operazioni finite" else "a meta': solo gli invarianti che valgono sempre"}) ===")
    appendLine("file visibili: ${snapshot.visible.size}; nel cestino: ${snapshot.trash.size}; temporanei: ${snapshot.temps.size}; file letti con SHA-256: ${snapshot.filesHashed} (${snapshot.bytesHashed / 1024} KiB)")
    if (violations.isEmpty()) {
      appendLine("TUTTI GLI INVARIANTI RISPETTATI")
    } else {
      appendLine("${violations.size} VIOLAZIONI")
      violations.take(50).forEach { appendLine("  $it") }
    }
  }
}

/** Verifica un'area dal manifesto, anche dopo una prova fatta da un'altra parte (il telefono). Legge soltanto: non ha nessuna primitiva di scrittura. */
object ChaosAreaVerifier {

  suspend fun verify(reader: NasMediaReader, manifest: ChaosManifest, settled: Boolean): AreaVerification {
    val ledger = Ledger().also { l -> manifest.originals.forEach(l::add) }
    val ownDevices = setOfNotNull(manifest.deviceId)
    val scope = ScanScope(manifest.roots, manifest.appAreas)
    val snapshot = NasScanner(reader, scope, ownDevices, useCache = false).scan()
    val gone = manifest.expectations.filterValues { it == ChaosManifest.Expect.Gone }.keys
    val context = VerifyContext(
      ledger = ledger,
      diary = null,
      ownDevice = manifest.deviceId,
      survives = { o -> o.sha !in gone },
      writesTrashRecords = false,
      // Sul telefono la cartella dell'app e' quella vera della share (si leggono solo tmp/ e il cestino proprio): non si pretende altro.
      strictAppArea = manifest.appAreas.all { it.full },
    )
    val expectation = expectationOf(manifest, ledger)
    val violations = Verifier.quiescent(snapshot, context, expectation, settled)
    return AreaVerification(violations, snapshot, settled)
  }

  private fun expectationOf(manifest: ChaosManifest, ledger: Ledger): Expectation {
    val visible = LinkedHashMap<Sha256, ExpectedVisible>()
    val gone = HashSet<Sha256>()
    for (o in ledger.originals) {
      when (val e = manifest.expectations[o.sha]) {
        null -> visible[o.sha] = ExpectedVisible(place = null, tolerantName = true)
        is ChaosManifest.Expect.Visible -> visible[o.sha] = ExpectedVisible(
          place = null,
          tolerantName = true,
          dir = e.dir?.let { Place(e.share ?: manifest.roots.first().share, it) },
        )
        ChaosManifest.Expect.Trash -> Unit
        ChaosManifest.Expect.Gone -> gone += o.sha
      }
    }
    return Expectation(visible = visible, trashCopies = null, gone = gone, dirs = emptySet(), slots = null)
  }
}

/** Riempie un mondo con gli originali di una prova fatta da un'altra parte e scrive il suo manifesto. Gli originali sono tutti sotto `sorgente/`. */
object ChaosAreaSeeder {

  suspend fun seed(
    world: ChaosWorld,
    runId: String,
    mediaDir: SharePath?,
    files: Int,
    random: Random,
    mediumBytes: Int,
    bigBytes: Int,
    bigPercent: Int,
  ): ChaosManifest {
    val originals = ArrayList<Original>()
    val share = world.shares.first()
    for (i in 0 until files) {
      val bytes = OriginalBytes.make(random, i, OriginalBytes.size(random, mediumBytes, bigBytes, bigPercent))
      val original = Original.of(i, bytes).let { Original(it.id, it.sha, it.size, null, "foto%04d".format(Locale.ROOT, i)) }
      world.putFile(share, SharePath.of("sorgente", "foto%04d.jpg".format(Locale.ROOT, i)), bytes)
      originals += original
    }
    val roots = world.shares.map { ScanScope.MediaRoot(it, mediaDir) }
    val apps = world.shares.map { ScanScope.AppArea(it, null, full = false) }
    return ChaosManifest(runId, world.deviceId, roots, apps, originals, emptyMap())
  }
}

// =============================================================================================================================
// Il cancello per il NAS vero
// =============================================================================================================================

/**
 * Cio' che P12 (o P16) consegna per toccare il NAS vero: si scrive come classe `dev.pampa.mycloudgallery.core.nasops.chaos.SmbChaosBinding`
 * (un `object` o una classe con costruttore vuoto) in questa cartella, e il resto si aggancia da solo. Vedi docs/prova-500.md.
 */
interface ChaosAreaBinding {
  /**
   * Il bersaglio del runner per [guard]: ogni giro e' un mondo radicato in `mcg-it/<giro>/r<N>` (la cartella dell'app compresa), con
   * le primitive di scrittura vere (`NasFileOps` su smbj con `root`) e la lettura radicata. La prima connessione si fa qui.
   */
  suspend fun openTarget(credentials: ChaosCredentials, guard: ChaosAreaGuard): ChaosTarget

  /** Solo la lettura dell'area per la verifica: il lettore vede la share vera (non radicato). */
  suspend fun openReader(credentials: ChaosCredentials, guard: ChaosAreaGuard): NasMediaReader

  /** Chiude le sessioni. */
  suspend fun close()
}

/** Se e come si puo' toccare il NAS vero adesso. Nessuna lettura di rete qui: solo variabili d'ambiente, un file locale e la guardia. */
object ChaosNasGate {

  sealed interface Outcome {
    class Ready(val credentials: ChaosCredentials, val guard: ChaosAreaGuard, val binding: ChaosAreaBinding) : Outcome

    /** Non si tocca il NAS vero: [reason] e' il motivo (finisce nel rapporto del test saltato). */
    class Skip(val reason: String) : Outcome
  }

  const val BINDING_CLASS = "dev.pampa.mycloudgallery.core.nasops.chaos.SmbChaosBinding"

  /** L'interruttore: la proprieta' `nasIntegration=true` (come `-PnasIntegration` per core-nas) o la variabile `MCG_NAS_INTEGRATION=true`. */
  val enabled: Boolean
    get() = System.getProperty("nasIntegration") == "true" || System.getenv("MCG_NAS_INTEGRATION") == "true"

  val credentialsFile: File get() = File(File(System.getProperty("user.home"), ".mycloudgallery"), "nas-it.properties")

  fun evaluate(runId: String): Outcome {
    if (!enabled) return Outcome.Skip("integrazione col NAS non richiesta (MCG_NAS_INTEGRATION=true, o -DnasIntegration=true)")
    val file = credentialsFile
    if (!file.isFile) return Outcome.Skip("file delle credenziali assente")
    val credentials = ChaosCredentials.parse(Properties().apply { file.inputStream().use { load(it) } })
      ?: return Outcome.Skip("file delle credenziali incompleto (user, password, share)")
    // La guardia PRIMA di qualunque socket: un errore nel file fa fallire la prova, non la salta.
    val guard = ChaosAreaGuard.of(credentials, runId)
    if (interruptions(credentials) == 0) return Outcome.Skip("chaos.rounds=0")
    val binding = findBinding()
      ?: return Outcome.Skip(
        "le primitive di scrittura sul NAS vero non sono ancora agganciate al banco: manca la classe $BINDING_CLASS (P12, docs/prova-500.md)",
      )
    return Outcome.Ready(credentials, guard, binding)
  }

  private fun findBinding(): ChaosAreaBinding? = runCatching {
    val type = Class.forName(BINDING_CLASS)
    (runCatching { type.getField("INSTANCE").get(null) }.getOrNull() ?: type.getDeclaredConstructor().newInstance()) as ChaosAreaBinding
  }.getOrNull()

  /** Le interruzioni da far cadere: `chaos.rounds` del file, poi `MCG_CHAOS_ROUNDS`, poi 400. */
  fun interruptions(credentials: ChaosCredentials): Int =
    credentials.rounds ?: System.getenv("MCG_CHAOS_ROUNDS")?.toIntOrNull() ?: DEFAULT_INTERRUPTIONS

  /** La taglia del file grande: `chaos.bigFileMb` del file, poi 200. */
  fun bigFileBytes(credentials: ChaosCredentials): Int = (credentials.bigFileMb ?: DEFAULT_BIG_MB).coerceIn(1, 1_000) shl 20

  const val DEFAULT_INTERRUPTIONS = 400
  const val DEFAULT_BIG_MB = 200
}
