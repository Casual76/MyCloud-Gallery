package dev.pampa.mycloudgallery.core.backup.chaos

import dev.pampa.mycloudgallery.core.backup.contract.BackupEngine
import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupRunResult
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.PhoneDenied
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.core.backup.contract.StopBy
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.testing.FakePhoneBytes
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupPaths
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import java.util.Locale
import kotlin.random.Random
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CancellationException

// Un GIRO della prova delle 500 interruzioni del backup (docs/fase5-progetto.md §5.4): un telefono finto con qualche foto e video, un NAS con
// cio' che c'era gia' (vecchi backup identici o no, un file diverso con il nome previsto, file qua e la'), da 1 a 3 passi (un lavoro del
// backup: "foto e video", "riprendi"...), ciascuno con i suoi colpi casuali: connessione che cade, sessione che scade, socket fermo, NAS pieno,
// file aperto dal PC, byte alterati, "Ferma", share congelata, un file del PC con il nome previsto, MORTE DEL PROCESSO (si buttano tutti gli
// oggetti e si riparte dal diario). Tra un passo e l'altro il mondo cambia (il MediaStore si ricostruisce, una foto nuova, una riscrittura di
// Samsung "Salva", una cancellazione, i permessi ritirati, due giorni e la pulizia dei propri file, un file del PC). Dopo ogni morte e a fine passo
// il verificatore controlla gli invarianti a meta'; alla fine, tolti i guasti e ripreso dal diario, a lavoro finito con una rilettura completa.
//
// Un giro e' ripetibile: stesso seme, stesse scelte, stessi colpi (il codice sotto prova e' deterministico).

/** Un errore di autenticazione sul NAS vero: la prova si ferma subito, senza nuovi tentativi (niente blocchi dell'account). */
class AreaAuthStop(message: String) : Error(message, null, false, false)

/** Come gira un banco. */
class BackupChaosConfig(
  val minPhotos: Int = 1,
  val maxPhotos: Int = 5,
  val maxVideos: Int = 2,
  /** La taglia di una foto e di un video (in byte) per questo giro. Il NAS vero usa 1 MB, 200 MB e `backup.bigFileMb`. */
  val sizeOf: (Random, MediaKind) -> Long = { random, kind -> if (kind == MediaKind.VIDEO) 40_000L + random.nextInt(160_000) else 1_000L + random.nextInt(59_000) },
  val minSteps: Int = 1,
  val maxSteps: Int = 3,
  val mix: FaultMix = FaultMix.DEFAULT,
  val maxFaultsPerStep: Int = 5,
  /** Se non null, il massimo di colpi per passo (il runner sul NAS vero: uno solo, cosi' ogni giro vale una interruzione). */
  val stepFaults: Int? = null,
  /** Le riprese dopo cui un elemento ancora aperto e' "non arriva in fondo". */
  val resumeBound: Int = 12,
  /**
   * D-13: percentuale delle morti del processo con la rete gia' giu' (il NAS tiene occupati i temporanei aperti dalla connessione morta finche' non
   * se ne accorge). Con la rete su il WD li lascia subito (misure in docs/decisioni.md).
   */
  val deadConnectionPercent: Int = 30,
  /** Percentuale di media colpiti da un evento del telefono a meta' lettura (riscrittura, sparizione, `_ID` rinumerati). */
  val phoneEventPercent: Int = 17,
  /** Gli eventi tra un passo e l'altro (telefono, tempo, PC). Il NAS vero non fa passare il tempo ne' lascia togliere file. */
  val events: Boolean = true,
  /** Con la cache un file non cambiato non si rilegge nelle verifiche intermedie (il NAS vero con file da 200 MB). L'ultima lettura e' sempre completa. */
  val hashCache: Boolean = false,
  /** Percentuale di giri che portano nel NAS file che c'erano gia' (vecchi backup identici o no, nome occupato). */
  val oldFilesPercent: Int = 1,
  /**
   * 5B (P13): dopo il caricamento il giro semina qualche media con le sue versioni e lavora su versioni, scambi, guardiano e "Libera spazio"
   * ([Backup5BPhase]). Senza (di norma, e per i difetti iniettati della 5A) il giro e' quello di prima, identico.
   */
  val fiveB: Backup5BConfig? = null,
)

/** Il racconto di un giro. */
class RoundReport(
  val round: Int,
  val seed: Long,
  val steps: List<String>,
  val faults: List<FiredFault>,
  val violations: List<Violation>,
  val deaths: Int,
  val log: List<String>,
  /** Lo stato in cui e' comparsa la prima violazione: si salva in un file e si verifica da solo. */
  val badState: BackupState?,
  /** Come [badState], per una violazione della fase 5B (versioni, scambi, guardiano, "Libera spazio"). */
  val badFiveBState: FiveBState? = null,
) {
  val ok: Boolean get() = violations.isEmpty()

  fun describe(): String = buildString {
    appendLine("giro $round, seme $seed: ${violations.size} violazioni, ${faults.size} interruzioni, $deaths morti del processo")
    steps.forEachIndexed { i, s -> appendLine("  passo ${i + 1}: $s") }
    appendLine("  interruzioni: ${faults.joinToString("; ")}")
    violations.take(12).forEach { appendLine("  VIOLAZIONE $it") }
    if (violations.size > 12) appendLine("  ... e altre ${violations.size - 12}")
    appendLine("  cronaca:")
    log.takeLast(60).forEach { appendLine("    $it") }
  }
}

private enum class StepKind(val describe: String) {
  RUN_ALL("foto e video"),
  RESUME("riprendi dal diario"),
  RUN_PHOTOS("solo le foto"),
  RUN_VIDEOS("solo i video"),
}

class BackupChaosRound(
  private val index: Int,
  private val seed: Long,
  private val target: BackupChaosTarget,
  private val factory: BackupChaosStackFactory,
  private val config: BackupChaosConfig,
  private val stats: ChaosStats,
  /** Niente nomi di file nei rapporti (le prove contro il NAS vero). */
  private val redact: Boolean = false,
) {
  private val random = Random(seed)

  private lateinit var world: BackupChaosWorld
  private lateinit var scanner: BackupNasScanner
  private lateinit var injector: Injector
  private lateinit var engine: BackupEngine
  private var incarnation = Incarnation(0)
  private var deaths = 0
  private var stepNumber = 0
  private var nameCounter = 0

  private val budget = FaultBudget()
  private val before = LinkedHashMap<String, NasFileFact>()
  private val diaryLog = ArrayList<DiaryTransition>()
  private val diaryViolations = ArrayList<String>()
  private val engineNotes = ArrayList<Note>()
  private val removedCited = ArrayList<String>()

  /** D-13: dove il NAS ha risposto "occupato" al motore nel giro del motore in corso ([checkRun]). */
  private val busyHits = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

  private val violations = LinkedHashMap<String, Violation>()
  private val stepLog = ArrayList<String>()
  private val log = ArrayList<String>()
  private var badState: BackupState? = null
  private var five: FiveBResult? = null

  /** Fa il giro. Non lancia mai per un errore del codice sotto prova: lo mette nelle violazioni (solo un errore di autenticazione risale). */
  suspend fun run(): RoundReport {
    stats.round()
    try {
      world = target.openWorld(index)
      try {
        setUp()
        val steps = config.minSteps + random.nextInt(config.maxSteps - config.minSteps + 1)
        repeat(steps) { step() }
        finish()
        runFiveB()
      } finally {
        stats.phoneEvents(world.phone.firedEvents())
        world.close()
      }
    } catch (stop: AreaAuthStop) {
      throw stop
    } catch (cancel: CancellationException) {
      throw cancel
    } catch (death: ChaosDeath) {
      record(Violation(Invariant.CONTRACT, "una morte del processo e' sfuggita al banco: $death"), "giro")
    } catch (e: Throwable) {
      record(
        Violation(Invariant.CONTRACT, "eccezione nel banco o nel codice sotto prova: ${e::class.simpleName}: ${e.message?.take(160)} @ ${e.stackTrace.take(6).joinToString(" < ")}"),
        "giro",
      )
    }
    val fired = (if (::injector.isInitialized) injector.fired.toList() else emptyList()) + five?.faults.orEmpty()
    return RoundReport(index, seed, stepLog, fired, violations.values.take(40), deaths + (five?.deaths ?: 0), log, badState, five?.badState)
  }

  /** La fase 5B del giro (versioni, scambi, guardiano, "Libera spazio"): su un seme suo, cosi' i colpi del caricamento restano quelli di prima. */
  private suspend fun runFiveB() {
    val settings = config.fiveB ?: return
    val result = Backup5BPhase(index, seed xor FIVE_B_SALT xor factory.fiveBSalt, world, settings, stats, redact).run()
    five = result
    stepLog += result.steps.map { "5B: $it" }
    log += result.log
    for (v in result.violations) violations.putIfAbsent("${v.invariant}|${v.detail}", v)
  }

  // =========================================================================================================================
  // Il mondo iniziale
  // =========================================================================================================================

  private inner class Actions : WorldActions {
    override suspend fun userStop() {
      log += "l'utente tocca Ferma"
      engine.stop(StopBy.USER)
    }

    override suspend fun freeze() {
      log += "la share si congela"
      world.gate.default = BackupGateState.FROZEN
    }

    override suspend fun pcFile() {
      val fact = world.pcCreateAtPlanned(random)
      log += if (fact != null) "il PC crea un file nel punto previsto di un elemento" else "il PC non ha dove mettere un file"
      if (fact != null) learn(fact)
    }
  }

  private fun learn(fact: NasFileFact) {
    before[fact.key] = fact
  }

  private fun contentOf(size: Long): NasContent =
    if (size > FakeBigLimit) NasContent.virtual(size, random.nextLong()) else NasContent.of(random.nextBytes(size.toInt()))

  private suspend fun setUp() {
    scanner = BackupNasScanner.ofShares(world.nas.reader, world.nas.shares, useCache = config.hashCache, held = world.nas::peekHeld)
    injector = Injector(stats, onConnectionLost = { world.nas.connectionLost() }, actions = Actions())
    val photos = config.minPhotos + random.nextInt(config.maxPhotos - config.minPhotos + 1)
    val videos = random.nextInt(config.maxVideos + 1)
    val photoNames = ArrayList<Pair<String, Int?>>()
    val videoNames = ArrayList<Pair<String, Int?>>()
    for (i in 1..photos + videos) {
      val video = i > photos
      val kind = if (video) MediaKind.VIDEO else MediaKind.PHOTO
      val taken = if (video) videoNames else photoNames
      // A volte lo stesso nome nella stessa cartella: due media di un telefono con lo stesso nome (E-B3 fra i nostri file).
      val (name, day) = if (taken.isNotEmpty() && random.nextInt(100) < 10) {
        taken[random.nextInt(taken.size)]
      } else {
        val day = when (random.nextInt(100)) {
          in 0 until 70 -> 20261012
          in 70 until 85 -> 20250307
          in 85 until 95 -> null
          else -> 19790101
        }
        (if (video) "VID_%04d.mp4".format(Locale.ROOT, ++nameCounter) else "IMG_%04d.jpg".format(Locale.ROOT, ++nameCounter)) to day
      }
      taken += name to day
      val t = world.addPhoneMedia(name, contentOf(config.sizeOf(random, kind)), kind, day)
      oldFilesFor(t)
      schedulePhoneEvent(t)
    }
    world.tracked.filter { random.nextInt(100) < 8 }.forEach { t -> world.currentId(t)?.let { world.phone.delete(it) } }
    // Cio' che c'era nel NAS e non e' del backup: file qua e la', anche nell'area comune.
    repeat(random.nextInt(3)) { world.putNasFile("Foto/vecchia ${it + 1}.jpg", random.nextBytes(300 + random.nextInt(3000)), known = false, inShare = world.commonShare) }
    repeat(random.nextInt(3)) { world.putNasFile("Documenti/nota ${it + 1}.txt", random.nextBytes(100 + random.nextInt(900)), known = false) }
    val scan = scanner.scan(allowCache = false)
    scan.files.forEach { before[it.key] = it }
    log += "mondo iniziale: ${world.tracked.size} media sul telefono, ${scan.files.size} file sul NAS (${world.nas.describe()})"
    stats.media(world.tracked.size)
    engine = newEngine()
  }

  /** Cio' che il NAS aveva gia' del media [t]: un vecchio backup identico (noto o no), un file diverso con il nome previsto (E-B3, E-B5, E-B16). */
  private suspend fun oldFilesFor(t: TrackedMedia) {
    if (config.oldFilesPercent <= 0) return
    val bytes = world.bytesOf(t.sha) ?: return
    when (random.nextInt(100)) {
      in 0 until 9 -> world.putNasFile("Vecchio backup/${t.name}", bytes, known = true)
      in 9 until 15 -> world.plannedFor(t)?.let { world.putNasFile(it.value, bytes, known = false) }
      in 15 until 21 -> world.plannedFor(t)?.let { world.putNasFile(it.value, random.nextBytes(bytes.size), known = false) }
      else -> Unit
    }
  }

  /** Il telefono cambia a meta' lettura (E-B15, E-B4, E-P12), con probabilita' [BackupChaosConfig.phoneEventPercent]. */
  private fun schedulePhoneEvent(t: TrackedMedia) {
    if (random.nextInt(100) >= config.phoneEventPercent) return
    val id = t.ids.first()
    val at = 1 + random.nextLong(t.size.coerceAtLeast(2))
    val big = t.size > FakeBigLimit
    when (if (big) 1 + random.nextInt(2) else random.nextInt(3)) {
      0 -> {
        val content = NasContent.of(random.nextBytes(t.size.toInt()))
        world.shaOf(content)
        world.phone.duringRead(id, at, FakePhoneBytes.MidRead.Rewritten(content, 9_999L + index))
      }
      1 -> world.phone.duringRead(id, at, FakePhoneBytes.MidRead.Vanished)
      else -> {
        val fresh = world.nextFreshId()
        t.ids += fresh
        world.phone.duringRead(id, at, FakePhoneBytes.MidRead.Renumbered("v$index-$fresh", fresh))
      }
    }
  }

  // =========================================================================================================================
  // Il motore e le morti
  // =========================================================================================================================

  private fun newEngine(): BackupEngine {
    val inc = incarnation
    val env = EngineEnv(
      world = world,
      ops = ChaosBackupOps(world.nas.ops, injector, inc, busyHits),
      reader = ChaosReader(world.nas.reader, injector, inc, busyHits),
      store = ChaosDiary(world.store, injector, inc, diaryLog, diaryViolations),
      catalog = ChaosCatalog(world.catalog, injector, inc),
      phone = ChaosPhone(world.phone.port, injector, inc),
      random = Random(seed xor (inc.number * 7_919L + 13)),
    )
    return factory.create(env)
  }

  /** Il processo nuovo: oggetti nuovi sopra lo stesso diario, catalogo, telefono e NAS. */
  private fun restartEngine() {
    incarnation = Incarnation(incarnation.number + 1)
    engine = newEngine()
  }

  /** Il processo e' morto: si controlla il mondo cosi' com'e' (a un passo dalla morte) e si riparte con oggetti nuovi. */
  private suspend fun died(cause: ChaosDeath?) {
    deaths++
    stats.restart()
    // D-13: con la rete su il sistema chiude i socket del processo morto e il NAS chiude i suoi handle; con la rete giu' il NAS li tiene.
    val networkDown = random.nextInt(100) < config.deadConnectionPercent
    log += "morte del processo #$deaths${cause?.let { " (${it.primitive} ${if (it.moment == Moment.BEFORE) "prima" else "dopo"})" }.orEmpty()}" +
      if (networkDown) " con la rete gia' giu'" else ""
    if (networkDown) stats.event("D-13: morte del processo con la rete gia' giu' (il NAS tiene i temporanei aperti)")
    world.nas.processDied(networkDown)
    midCheck("dopo la morte #$deaths")
    restartEngine()
  }

  /** Esegue [block] del codice sotto prova. Falso se il processo e' morto (e si e' gia' ripartiti). Un'eccezione non prevista e' una violazione. */
  private suspend fun guarded(block: suspend () -> Unit): Boolean {
    try {
      block()
    } catch (d: ChaosDeath) {
      died(d)
      return false
    } catch (stop: AreaAuthStop) {
      throw stop
    } catch (cancel: CancellationException) {
      throw cancel
    } catch (e: Throwable) {
      engineNotes += Note(
        Invariant.CONTRACT,
        "il motore ha lanciato ${e::class.simpleName}: ${e.message?.take(160)} @ ${e.stackTrace.take(5).joinToString(" < ")}",
      )
      log += "il motore ha lanciato ${e::class.simpleName}"
      restartEngine()
      return false
    }
    if (incarnation.dead) {
      died(null)
      return false
    }
    return true
  }

  // =========================================================================================================================
  // I passi
  // =========================================================================================================================

  private fun pickStep(): StepKind = when (random.nextInt(100)) {
    in 0 until 55 -> StepKind.RUN_ALL
    in 55 until 80 -> StepKind.RESUME
    in 80 until 90 -> StepKind.RUN_PHOTOS
    else -> StepKind.RUN_VIDEOS
  }

  private suspend fun step() {
    stepNumber++
    if (config.events) event()
    var kind = pickStep()
    // Un passo senza niente da fare non prova niente: riprendere senza elementi aperti diventa un giro intero, e un giro senza media in attesa
    // ne trova di nuovi (il telefono continua a fare foto).
    if (kind == StepKind.RESUME && world.store.itemsToResume(1).isEmpty()) kind = StepKind.RUN_ALL
    ensureWork(kind)
    stepLog += kind.describe
    log += "--- passo $stepNumber: ${kind.describe}"
    stats.step(kind.name)
    val units = arm(kind)
    val firedBefore = injector.fired.size
    val completed = guarded {
      when (kind) {
        StepKind.RUN_ALL -> {
          runKind(BackupKind.PHOTOS)
          runKind(BackupKind.VIDEOS)
        }
        StepKind.RUN_PHOTOS -> runKind(BackupKind.PHOTOS)
        StepKind.RUN_VIDEOS -> runKind(BackupKind.VIDEOS)
        StepKind.RESUME -> {
          busyHits.clear()
          checkRun(engine.resumeAll(1.hours), null)
        }
      }
    }
    // Si tara sui passi arrivati in fondo senza colpi: una morte taglia il passo e falserebbe la media.
    if (completed && injector.fired.size == firedBefore) stats.work(injector.nasCallCount, injector.storeWriteCount, injector.phoneCallCount, units)
    injector.disarm()
    resetAfterStep()
    // Un colpo che non e' una morte (connessione, sessione...): il processo puo' restare quello di prima o ripartire.
    if (random.nextInt(100) < 70) world.rescan()
    midCheck("fine passo $stepNumber")
    if (completed && injector.fired.size > firedBefore && random.nextBoolean()) restartEngine()
  }

  /** I media del telefono che aspettano il backup (nel catalogo, con la riga sul telefono, non ancora al sicuro), del tipo [kind] o di tutti. */
  private fun waiting(kind: BackupKind?): List<TrackedMedia> = world.tracked.filter {
    !it.retired && world.catalog.media(it.mediaId) != null && !world.catalog.isSafe(it.mediaId) && world.currentId(it) != null &&
      (kind == null || BackupKind.of(it.kind) == kind)
  }

  private fun ensureWork(kind: StepKind) {
    val missing = when (kind) {
      StepKind.RUN_ALL -> waiting(null).isEmpty()
      StepKind.RUN_PHOTOS -> waiting(BackupKind.PHOTOS).isEmpty()
      StepKind.RUN_VIDEOS -> waiting(BackupKind.VIDEOS).isEmpty()
      StepKind.RESUME -> false
    }
    if (missing) repeat(1 + random.nextInt(2)) { phoneNew(if (kind == StepKind.RUN_VIDEOS) MediaKind.VIDEO else MediaKind.PHOTO) }
  }

  private suspend fun runKind(kind: BackupKind) {
    busyHits.clear()
    checkRun(engine.run(kind, 1.hours, BackupTrigger.SCHEDULED), kind)
  }

  /**
   * Un giro che finisce `COMPLETE` non lascia in silenzio un elemento aperto: ogni elemento ancora aperto e' contato fra quelli in attesa (invariante
   * 11). D-13 (invariante 16): e non finisce `COMPLETE` se in questo giro il motore ha trovato occupato (dal NAS, per una connessione morta che
   * lo tiene ancora) il temporaneo di un elemento rimasto aperto che ne ha bisogno (in caricamento, caricato, verificato o in pubblicazione). Un
   * elemento che aspetta altro (la riga del telefono) e non ha toccato il NAS non conta: il motore non poteva saperlo. Nemmeno il tentativo di
   * prima di un elemento ripartito su un temporaneo nuovo: non serve piu' (il media va avanti, o e' gia' al sicuro), resta citato dal diario e
   * il motore lo toglie a un giro dopo.
   */
  private suspend fun checkRun(result: BackupRunResult, kind: BackupKind?) {
    if (result.stoppedBy != RunStop.COMPLETE) return
    val openItems = world.store.itemsToResume(Int.MAX_VALUE).filter { kind == null || world.kindOf(it) == kind }
    val open = openItems.size
    if (open > result.waiting) {
      engineNotes += Note(Invariant.NAS_READS, "un giro finito COMPLETE lascia $open elementi aperti ma ne conta ${result.waiting} in attesa")
    }
    val held = world.nas.heldByDeadConnections().intersect(busyHits.toSet())
    if (held.isEmpty()) return
    val busy = openItems.count { item ->
      val temp = item.temp ?: return@count false
      item.state in NEEDS_TEMP && NasFileFact.keyOf(item.share, temp.value) in held
    }
    if (busy > 0) engineNotes += Note(Invariant.BUSY, "un giro finito COMPLETE lascia $busy elementi fermi su un temporaneo occupato")
  }

  /** Arma i colpi del passo; restituisce le unita' di lavoro (media da caricare piu' elementi da riprendere). Dove cadono, lo stima sul lavoro. */
  private suspend fun arm(kind: StepKind): Int {
    val sizes = ArrayList<Long>()
    val open = world.store.itemsToResume(Int.MAX_VALUE)
    open.forEach { sizes += it.size }
    val ids = open.map { it.mediaId }.toSet()
    when (kind) {
      StepKind.RUN_ALL -> waiting(null)
      StepKind.RUN_PHOTOS -> waiting(BackupKind.PHOTOS)
      StepKind.RUN_VIDEOS -> waiting(BackupKind.VIDEOS)
      StepKind.RESUME -> emptyList()
    }.filter { it.mediaId !in ids }.forEach { sizes += it.size }
    var nas = 4
    var store = 3
    var phone = 2
    for (size in sizes) {
      val chunks = (size + world.chunkBytes - 1) / world.chunkBytes
      val points = (size + world.checkpointBytes - 1) / world.checkpointBytes
      nas += (EST_NAS_BASE + chunks / 2 + points).toInt()
      store += (EST_STORE_BASE + points).toInt()
      phone += (EST_PHONE_BASE + chunks / 2).toInt()
    }
    val rules = Injector.randomRules(
      random, Estimate(nas = minOf(nas, 4_000), store = minOf(store, 400), phone = minOf(phone, 4_000)),
      config.stepFaults ?: config.maxFaultsPerStep, config.mix, budget,
    )
    injector.load(rules)
    log += "colpi previsti: ${rules.joinToString("; ")}"
    return sizes.size
  }

  private fun resetAfterStep() {
    world.gate.default = BackupGateState.OPEN
    world.phone.denied = null
  }

  // =========================================================================================================================
  // Il mondo cambia tra un passo e l'altro
  // =========================================================================================================================

  private suspend fun event() {
    val roll = random.nextInt(100)
    when {
      roll < 25 -> Unit
      roll < 35 -> phoneRebuild()
      roll < 45 -> phoneNew()
      roll < 53 -> phoneRewriteNow()
      roll < 58 -> phoneDeleteNow()
      roll < 64 -> {
        world.phone.denied = PhoneDenied.MEDIA_PERMISSION
        happened("permessi dei media ritirati per questo passo")
      }
      roll < 78 -> if (world.nas.caps.housekeeping) timeAndClean()
      roll < 90 -> pcFileNow()
      roll < 95 -> if (world.nas.caps.advanceTime) minutesPass()
      roll < 98 -> {
        world.nas.reapDeadConnections()
        happened("il NAS si accorge delle connessioni morte")
      }
      else -> Unit
    }
  }

  private fun happened(what: String) {
    stats.event(what)
    log += "evento: $what"
  }

  /** Il MediaStore si ricostruisce (E-P12): `_ID` rinumerati e versione nuova; il catalogo li ritrova alla prossima lettura del telefono. */
  private fun phoneRebuild() {
    val renumber = HashMap<Long, Long>()
    for (t in world.tracked) {
      if (t.retired) continue
      val id = world.currentId(t) ?: continue
      val fresh = world.nextFreshId()
      renumber[id] = fresh
      t.ids += fresh
    }
    world.phone.rebuild("v$index-s$stepNumber", renumber)
    happened("MediaStore ricostruito, _ID rinumerati")
  }

  private fun phoneNew(kind: MediaKind = MediaKind.PHOTO) {
    val name = (if (kind == MediaKind.VIDEO) "VID_%04d.mp4" else "IMG_%04d.jpg").format(Locale.ROOT, ++nameCounter)
    world.addPhoneMedia(name, contentOf(config.sizeOf(random, kind)), kind, 20261012)
    happened(if (kind == MediaKind.VIDEO) "un video nuovo sul telefono" else "una foto nuova sul telefono")
  }

  /** Samsung "Salva" (E-B15): byte nuovi sul posto, timbro nuovo. La lettura del telefono registra la versione nuova. */
  private fun phoneRewriteNow() {
    val t = world.tracked.filter { !it.retired && it.size <= FakeBigLimit }.randomOrNull(random) ?: return
    val id = world.currentId(t) ?: return
    val content = NasContent.of(random.nextBytes(t.size.toInt()))
    world.shaOf(content)
    world.phone.rewrite(id, content, 5_000L + stepNumber)
    happened("un file del telefono riscritto sul posto")
  }

  private fun phoneDeleteNow() {
    val t = world.tracked.filter { !it.retired }.randomOrNull(random) ?: return
    val id = world.currentId(t) ?: return
    world.phone.delete(id)
    happened("una foto cancellata dal telefono")
  }

  /**
   * D-13: passano venti minuti e il NAS tiene ancora i temporanei delle connessioni morte (non se ne accorge prima di ore): oltre il tetto il motore
   * vero riparte su un temporaneo nuovo, il vecchio resta citato dal diario e si toglie quando il NAS lo lascia.
   */
  private fun minutesPass() {
    world.advanceTime(BUSY_WAIT_MS)
    happened("passano venti minuti (i file occupati restano occupati)")
  }

  /** Due giorni e la pulizia dei propri file (D33, E-B21): un temporaneo fermo da giorni, citato dal diario, resta. */
  private suspend fun timeAndClean() {
    world.advanceTime(2 * BackupChaosWorld.DAY_MS)
    // D-13: in due giorni il NAS si e' accorto delle connessioni morte da un pezzo.
    world.nas.reapDeadConnections()
    val cited = world.store.citedTemps().mapTo(HashSet()) { it.value.lowercase(Locale.ROOT) }
    val removed = world.housekeeping(factory.cleanerHonorsCitations, cited)
    removedCited += removed.filter { it.lowercase(Locale.ROOT) in cited }
    happened("passano due giorni e la pulizia dei propri file toglie ${removed.size} temporanei")
    midCheck("dopo la pulizia")
  }

  private suspend fun pcFileNow() {
    val fact = world.pcCreateAtPlanned(random) ?: return
    learn(fact)
    happened("il PC crea un file con il nome previsto")
  }

  // =========================================================================================================================
  // Le verifiche
  // =========================================================================================================================

  private fun notes(scan: ScanResult): List<Note> =
    engineNotes + diaryViolations.map { Note(Invariant.DIARY, it) } + scan.problems.map { Note(Invariant.NAS_READS, it) }

  private suspend fun midCheck(label: String) {
    val scan = scanner.scan(allowCache = config.hashCache)
    stats.hashed(scan.filesHashed, scan.bytesHashed)
    stats.midCheck()
    val state = world.capture(before.values, scan, diaryLog, removedCited, notes(scan))
    BackupVerifier.check(state, Phase.MID, showNames = !redact).forEach { record(it, label, state) }
  }

  private fun record(v: Violation, label: String, state: BackupState? = null) {
    val key = "${v.invariant}|${v.detail}"
    if (key in violations) return
    violations[key] = Violation(v.invariant, "$label: ${v.detail}")
    if (badState == null) badState = state
  }

  private suspend fun finish() {
    injector.disarm()
    resetAfterStep()
    // D-13: alla fine il NAS si accorge delle connessioni morte (ore dopo): i loro file tornano liberi e il lavoro arriva in fondo.
    world.nas.reapDeadConnections()
    restartEngine()
    converge()
    // La rilettura finale: ogni file, per intero, senza nessuna cache (invariante 5).
    val scan = scanner.scan(allowCache = false)
    stats.hashed(scan.filesHashed, scan.bytesHashed)
    stats.finalCheck()
    val state = world.capture(before.values, scan, diaryLog, removedCited, notes(scan))
    BackupVerifier.check(state, Phase.FINAL, showNames = !redact).forEach { record(it, "a lavoro finito", state) }
    world.store.all().forEach { stats.item(it.state.code) }
    // D-13: quanti elementi sono ripartiti su un temporaneo nuovo perche' il NAS teneva occupato il vecchio oltre il tetto.
    world.store.all().filter { item -> item.temp?.let { NasBackupPaths.attemptOf(it) }?.let { it > 0 } == true }
      .forEach { _ -> stats.event("D-13: elemento ripartito su un temporaneo nuovo") }
  }

  /** Tolti i guasti, si riprende dal diario finche' non c'e' piu' niente da fare (al massimo [BackupChaosConfig.resumeBound] riprese). */
  private suspend fun converge() {
    repeat(config.resumeBound) {
      world.rescan()
      guarded {
        runKind(BackupKind.PHOTOS)
        runKind(BackupKind.VIDEOS)
        busyHits.clear()
        checkRun(engine.resumeAll(1.hours), null)
      }
      val open = world.store.itemsToResume(Int.MAX_VALUE)
      val candidates = world.planner.next(BackupKind.PHOTOS, 1_000) + world.planner.next(BackupKind.VIDEOS, 1_000)
      if (open.isEmpty() && candidates.isEmpty()) return
    }
    log += "dopo ${config.resumeBound} riprese resta ancora lavoro"
  }

  private companion object {
    /** Il seme della fase 5B e' quello del giro rimescolato: la fase ha le sue scelte e non tocca quelle del caricamento. */
    const val FIVE_B_SALT = 0x5B5B5B5B5B5BL

    /** D-13: il tempo dell'evento "passano venti minuti" (oltre il tetto del motore per ripartire su un temporaneo nuovo). */
    const val BUSY_WAIT_MS = 20L * 60_000L

    /** D-13: gli stati in cui un elemento ha bisogno del suo temporaneo per andare avanti (invariante 16). */
    val NEEDS_TEMP = setOf(UploadState.UPLOADING, UploadState.UPLOADED, UploadState.VERIFIED, UploadState.PUBLISHING)

    /** Oltre questa taglia un contenuto del telefono e' virtuale (generato da un seme): non si tiene in memoria. */
    const val FakeBigLimit = 4L shl 20

    // Le chiamate attese per unita' di lavoro (tarate sul rapporto: "chiamate per passo armato"), oltre a quelle per blocco e per punto di ripresa.
    const val EST_NAS_BASE = 3
    const val EST_STORE_BASE = 4
    const val EST_PHONE_BASE = 1
  }
}
