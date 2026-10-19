package dev.pampa.mycloudgallery.core.backup.chaos

import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionReadiness
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionRequest
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceFilter
import dev.pampa.mycloudgallery.core.backup.contract.GuardScope
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceKind
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRules
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRun
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRunState
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.contract.PhoneWriteGrant
import dev.pampa.mycloudgallery.core.backup.contract.RestoreAvailability
import dev.pampa.mycloudgallery.core.backup.contract.RestoreOutcome
import dev.pampa.mycloudgallery.core.backup.contract.TrashBatch
import dev.pampa.mycloudgallery.core.backup.contract.TrashOutcome
import dev.pampa.mycloudgallery.core.backup.free.BootCounter
import dev.pampa.mycloudgallery.core.backup.free.FreeItemState
import dev.pampa.mycloudgallery.core.backup.free.FreeRunRecord
import dev.pampa.mycloudgallery.core.backup.free.FreeSpaceEvaluator
import dev.pampa.mycloudgallery.core.backup.testing.TestConfirmations
import dev.pampa.mycloudgallery.core.metadata.testing.ChangeSamples
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.nas.ops.backup.VersionPaths
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CancellationException

// La FASE 5B di un giro della prova delle 500 interruzioni (docs/fase5-progetto.md §5.4, P13): dopo il caricamento (la 5A, che il giro ha gia'
// fatto e verificato) il banco semina qualche media con le sue versioni e lavora sulle VERSIONI, gli SCAMBI ("Ripristina originale"), il
// GUARDIANO e "LIBERA SPAZIO" con la verifica sul momento: da 3 a 5 passi, ciascuno con i suoi colpi casuali (la connessione che cade, la
// sessione che scade, il socket fermo, il NAS pieno, un file aperto dal PC, byte alterati, "Ferma", il congelamento, il PC che mette un file
// dove l'app sta per scrivere o che modifica il file proprio prima della rinomina, la MORTE DEL PROCESSO a ogni chiamata, a ogni scrittura dei
// diari e del catalogo e alla riscrittura del telefono). Tra un passo e l'altro il mondo cambia (Samsung "Salva" e "Ripristina originale", il
// PC, il marciume di un bit, il MediaStore, il tempo, il riavvio). Dopo ogni morte e a fine passo il verificatore controlla gli invarianti a
// meta'; alla fine, tolti i guasti e ripreso dai diari, a lavoro finito con una rilettura completa. Ripetibile dal seme.

/** Il risultato della fase 5B di un giro. */
class FiveBResult(
  val violations: List<Violation>,
  val steps: List<String>,
  val faults: List<FiredFault>,
  val deaths: Int,
  val log: List<String>,
  val badState: FiveBState?,
)

/** Le cartelle dei file preparati, una per giro: nel temporaneo di sistema, tolte a fine giro. */
internal object FiveBTemp {
  private val base: File by lazy { Files.createTempDirectory("mcg-chaos5b").toFile().also { it.deleteOnExit() } }

  fun dirFor(index: Int, seed: Long): File = File(base, "g$index-${java.lang.Long.toHexString(seed)}").also { it.mkdirs() }
}

private enum class Step5B(val describe: String) {
  EDIT("modifica sul telefono e versione"),
  EDIT_TWICE("due modifiche sul telefono e le versioni"),
  EDIT_WHILE_READ("modifica mentre la versione si legge"),
  REVERT("Samsung ripristina l'originale (scambio)"),
  RESTORE("Ripristina originale dall'app (scambio)"),
  STAGED("editor esterno su un solo-NAS"),
  CORRECT("correzione di un media"),
  FREE("Libera spazio"),
  RESUME("riprendi i lavori"),
}

/** Cosa succede nel mezzo di un giro di "Libera spazio", fra la verifica e il dialogo (una volta sola). */
private enum class FreeEvent(val describe: String) {
  NONE("niente"),
  TIME_JUMP("passano delle ore"),
  REBOOT("il telefono si riavvia"),
  PC_TOUCH("il PC modifica una copia"),
  PHONE_SAVE("Samsung salva sopra una foto"),
  PHONE_DELETE("l'utente cancella una foto"),
  FREEZE("la share si congela"),
}

class Backup5BPhase(
  private val index: Int,
  private val seed: Long,
  private val world: BackupChaosWorld,
  private val config: Backup5BConfig,
  private val stats: ChaosStats,
  /** Niente nomi di file nei rapporti (le prove contro il NAS vero). */
  private val redact: Boolean = false,
) {
  private val random = Random(seed)
  private val root: File = FiveBTemp.dirFor(index, seed)

  private lateinit var fb: FiveBWorld
  private lateinit var injector: Injector
  private lateinit var scanner: BackupNasScanner
  private lateinit var services: FiveBServices
  private var incarnation = Incarnation(0)
  private var deaths = 0
  private var stepNumber = 0

  private val budget = FaultBudget()
  private val engineNotes = ArrayList<Note>()
  private val batchNotes = ArrayList<Note>()
  private val guardLog = ArrayList<DiaryTransition>()
  private val guardViolations = ArrayList<String>()
  private val violations = LinkedHashMap<String, Violation>()
  private val stepLog = ArrayList<String>()
  private val log = ArrayList<String>()
  private var badState: FiveBState? = null
  private var freeEvent: FreeEvent = FreeEvent.NONE

  suspend fun run(): FiveBResult {
    try {
      try {
        setUp()
        val steps = config.minSteps + random.nextInt(config.maxSteps - config.minSteps + 1)
        repeat(steps) { step() }
        finish()
      } finally {
        root.deleteRecursively()
      }
    } catch (stop: AreaAuthStop) {
      throw stop
    } catch (cancel: CancellationException) {
      throw cancel
    } catch (death: ChaosDeath) {
      record(Violation(Invariant.CONTRACT, "una morte del processo e' sfuggita al banco: $death"), "5B")
    } catch (e: Throwable) {
      record(
        Violation(Invariant.CONTRACT, "eccezione nel banco o nei servizi della 5B: ${e::class.simpleName}: ${e.message?.take(160)} @ ${e.stackTrace.take(6).joinToString(" < ")}"),
        "5B",
      )
    }
    val fired = if (::injector.isInitialized) injector.fired.toList() else emptyList()
    return FiveBResult(violations.values.take(40), stepLog, fired, deaths, log, badState)
  }

  // =========================================================================================================================
  // Il mondo e i servizi
  // =========================================================================================================================

  private inner class Actions : WorldActions {
    override suspend fun userStop() {
      log += "l'utente tocca Ferma"
      services.versions.stop()
    }

    override suspend fun freeze() {
      log += "la share si congela"
      world.gate.default = BackupGateState.FROZEN
    }

    override suspend fun pcFile() = Unit

    override suspend fun pcFileAt(target: NasTarget?) {
      if (target == null) return
      if (fb.pcCreate(target.share, target.other ?: target.path)) log += "il PC crea un file dove l'app stava per scrivere"
    }

    override suspend fun pcEdit(target: NasTarget?) {
      if (target == null) return
      if (fb.pcEdit(target.share, target.path)) log += "il PC modifica il file attuale"
    }
  }

  private suspend fun setUp() {
    fb = FiveBWorld(world, config, Random(seed xor 0x5B5B5B5BL), root)
    injector = Injector(stats, onConnectionLost = { world.nas.connectionLost() }, actions = Actions())
    scanner = BackupNasScanner.ofShares(world.nas.reader, world.nas.shares, useCache = config.hashCache)
    val media = fb.seedAll()
    val scan = scanner.scan(allowCache = false)
    scan.files.forEach { fb.before[it.key] = it }
    fb.knownShas += scan.files.filter { !it.appArea }.map { it.sha }
    log += "mondo 5B: ${media.size} media (${media.joinToString { it.category.code }}), ${scan.files.size} file sul NAS (${world.nas.describe()})"
    stats.media(media.size)
    services = newServices()
    midCheck("avvio della 5B")
  }

  private fun newServices(): FiveBServices {
    val inc = incarnation
    val env = FiveBEnv(
      world = world,
      fb = fb,
      ops = ChaosBackupOps(world.nas.ops, injector, inc),
      reader = ChaosReader(world.nas.reader, injector, inc),
      journal = ChaosVersionJournal(fb.journal, injector, inc),
      versionCatalog = ChaosVersionCatalog(fb.catalog, injector, inc),
      freeCatalog = ChaosFreeCatalog(fb.catalog, injector, inc, probe = fb::noteVerified),
      backupCatalog = ChaosCatalog(fb.catalog, injector, inc),
      freeDiary = ChaosFreeDiary(fb.freeDiary, injector, inc),
      guardStore = ChaosDiary(fb.guardStore, injector, inc, guardLog, guardViolations),
      phone = ChaosPhone(world.phone.port, injector, inc),
      rewriter = ChaosRewriter(fb.rewriter, injector, inc),
      clock = world.clock,
      boot = BootCounter { fb.bootCount },
      random = Random(seed xor (inc.number * 7_919L + 31)),
    )
    return config.stack.create(env)
  }

  private fun restartServices() {
    incarnation = Incarnation(incarnation.number + 1)
    services = newServices()
  }

  /** Il processo e' morto: si controlla il mondo cosi' com'e' (a un passo dalla morte) e si riparte con oggetti nuovi. */
  private suspend fun died(cause: ChaosDeath?) {
    deaths++
    stats.restart()
    log += "morte del processo #$deaths${cause?.let { " (${it.primitive} ${if (it.moment == Moment.BEFORE) "prima" else "dopo"})" }.orEmpty()}"
    world.nas.processDied()
    midCheck("dopo la morte #$deaths")
    restartServices()
  }

  /** Esegue [block] dei servizi sotto prova. Falso se il processo e' morto (e si e' gia' ripartiti). Un'eccezione non prevista e' una violazione. */
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
        "i servizi della 5B hanno lanciato ${e::class.simpleName}: ${e.message?.take(160)} @ ${e.stackTrace.take(5).joinToString(" < ")}",
      )
      log += "un servizio ha lanciato ${e::class.simpleName}"
      restartServices()
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

  private fun pickStep(): Step5B = when (random.nextInt(100)) {
    in 0 until 20 -> Step5B.EDIT
    in 20 until 25 -> Step5B.EDIT_TWICE
    in 25 until 31 -> Step5B.EDIT_WHILE_READ
    in 31 until 39 -> Step5B.REVERT
    in 39 until 53 -> Step5B.RESTORE
    in 53 until 59 -> Step5B.STAGED
    in 59 until 66 -> Step5B.CORRECT
    in 66 until 96 -> Step5B.FREE
    else -> Step5B.RESUME
  }

  private fun busy(rec: MediaRec): Boolean = fb.journal.all().any { it.mediaId == rec.mediaId && !it.state.terminal }

  private suspend fun editable(): List<MediaRec> = fb.registry.values.filter { rec ->
    (rec.category == FiveBCategory.SAFE_PHONE || rec.category == FiveBCategory.EDITED_SAFE) && fb.currentRow(rec) != null && !busy(rec) &&
      fb.catalog.media(rec.mediaId)?.let { m -> m.versions.any { v -> v.nas.any { it.present && it.verifiedAtMillis != null && it.share.equals(world.share, true) } } } == true
  }

  private suspend fun step() {
    stepNumber++
    if (config.events) event()
    var kind = pickStep()
    var target: MediaRec? = null
    var freeFilter: FreeSpaceFilter? = null
    var permanent = false
    // Il mondo del passo si prepara senza colpi (il telefono che cambia, il file preparato): i colpi cadono sui servizi, non sulla preparazione.
    when (kind) {
      Step5B.EDIT, Step5B.EDIT_TWICE, Step5B.EDIT_WHILE_READ -> target = editable().randomOrNull(random)
      Step5B.REVERT -> target = editable().filter { rec ->
        fb.catalog.media(rec.mediaId)?.let { m -> m.versions.size > 1 && m.original?.nas?.any { VersionPaths.isInVersions(it.path) && it.verifiedAtMillis != null } == true } == true &&
          rec.originalBytes != null
      }.randomOrNull(random)
      Step5B.RESTORE -> target = fb.registry.values.filter { services.keeper.plan(it.mediaId).restore == RestoreAvailability.AVAILABLE }.randomOrNull(random)
      Step5B.STAGED -> target = fb.registry.values.filter { it.category == FiveBCategory.SOLO_NAS && !busy(it) && it.usualPath != null }.randomOrNull(random)
      // Anche con un elemento del guardiano aperto per il media (F2, corretto il 19/10: `ChaosKnownDefectsTest`).
      Step5B.CORRECT -> target = fb.registry.values.filter { rec ->
        !busy(rec) && services.corrections.plan(rec.mediaId, CorrectionRequest(ChangeSamples.newDate)).readiness == CorrectionReadiness.READY
      }.randomOrNull(random)
      Step5B.FREE, Step5B.RESUME -> Unit
    }
    if (target == null && kind != Step5B.FREE && kind != Step5B.RESUME) kind = Step5B.FREE
    val chosen: MediaRec? = target
    when (kind) {
      Step5B.EDIT -> fb.samsungSave(chosen!!)
      Step5B.EDIT_TWICE -> {
        fb.samsungSave(chosen!!)
        scanNow()
        fb.samsungSave(chosen)
      }
      Step5B.EDIT_WHILE_READ -> fb.samsungSaveWhileRead(chosen!!)
      Step5B.REVERT -> fb.samsungRevert(chosen!!)
      else -> Unit
    }
    var staged: Triple<VersionId, Sha256, Long>? = null
    if (kind == Step5B.STAGED) staged = fb.stageEdit(chosen!!)
    val stagedEdit = staged
    if (kind == Step5B.FREE) {
      freeFilter = FreeSpaceFilter(
        kind = when (random.nextInt(100)) {
          in 0 until 70 -> FreeSpaceKind.ALL
          in 70 until 85 -> FreeSpaceKind.VIDEOS_ONLY
          else -> FreeSpaceKind.OLDER_THAN_A_YEAR
        },
        excludeRecentDays = if (random.nextInt(100) < 75) 0 else FreeSpaceRules.DEFAULT_EXCLUDE_RECENT_DAYS,
      )
      permanent = random.nextInt(100) < 15
      freeEvent = if (config.events) pickFreeEvent() else FreeEvent.NONE
    }
    // La lettura del telefono e del NAS registra nel catalogo cio' che il passo ha cambiato da fuori (senza colpi).
    if (kind != Step5B.FREE && kind != Step5B.RESUME) scanNow()
    stepLog += kind.describe + (if (kind == Step5B.FREE) " (${freeEvent.describe})" else "")
    log += "--- passo 5B $stepNumber: ${kind.describe}"
    stats.step("5B ${kind.name}")
    val estimate = estimateOf(kind)
    injector.load(Injector.randomRules(random, estimate, config.stepFaults ?: config.maxFaultsPerStep, config.mix, budget, config.scope))
    log += "colpi previsti: ${injector.pending().joinToString("; ")}"
    val firedBefore = injector.fired.size
    val completed = guarded {
      when (kind) {
        Step5B.EDIT, Step5B.EDIT_TWICE, Step5B.EDIT_WHILE_READ, Step5B.REVERT -> runVersions()
        Step5B.RESUME -> {
          runVersions()
          continueActiveFree()
        }
        Step5B.STAGED -> {
          val (v, sha, size) = stagedEdit!!
          services.versions.planStaged(chosen!!.mediaId, v, sha, size)
          runVersions()
        }
        Step5B.RESTORE -> restoreOriginal(chosen!!)
        Step5B.CORRECT -> correct(chosen!!)
        Step5B.FREE -> freeSpace(freeFilter!!, permanent)
      }
    }
    injector.disarm()
    world.gate.default = BackupGateState.OPEN
    if (random.nextInt(100) < 70) scanNow()
    log += "diari: " + fb.guardStore.all().joinToString { "${it.kind.code}#${it.id}:${it.state.code}" } + " | " +
      fb.journal.all().joinToString { "${it.kind.code}#${it.id}:${it.state.code}" } + " | " +
      fb.freeDiary.runs().joinToString { "giro#${it.id}:${it.state.code}" }
    midCheck("fine passo 5B $stepNumber")
    if (completed && injector.fired.size > firedBefore && random.nextBoolean()) restartServices()
  }

  private fun estimateOf(kind: Step5B): Estimate = when (kind) {
    Step5B.EDIT, Step5B.EDIT_TWICE, Step5B.EDIT_WHILE_READ, Step5B.STAGED -> Estimate(nas = 55, store = 24, phone = 14)
    Step5B.REVERT, Step5B.RESTORE -> Estimate(nas = 26, store = 18, phone = 8)
    Step5B.CORRECT -> Estimate(nas = 60, store = 26, phone = 18)
    Step5B.FREE -> Estimate(nas = 40 + 14 * fb.registry.size, store = 14 + 5 * fb.registry.size, phone = 8 + 3 * fb.registry.size)
    Step5B.RESUME -> Estimate(nas = 40, store = 16, phone = 10)
  }

  /** La lettura del telefono e la scansione del NAS: il catalogo ritrova le righe, registra le versioni nuove, e il media tornato a una versione nota si riallinea. */
  private suspend fun scanNow() {
    fb.rescan()
  }

  /** Le versioni della 5A/5B in un giro: i candidati dal catalogo, gli scambi dei ritorni a una versione nota, poi i lavori aperti. */
  private suspend fun runVersions() {
    services.versions.planPhoneVersions(fb.candidates())
    for (rec in fb.registry.values.toList()) services.versions.planRealign(rec.mediaId)
    services.versions.run(1.hours)
  }

  private suspend fun restoreOriginal(rec: MediaRec) {
    val plan = services.keeper.plan(rec.mediaId)
    val confirmation = TestConfirmations.confirm(plan, world.clock.monotonicMs())
    val first = services.keeper.restoreOriginal(rec.mediaId, confirmation, null)
    if (first is RestoreOutcome.NeedsPhoneWrite) {
      when (random.nextInt(3)) {
        0 -> {
          fb.rewriter.granted += first.rows.map { it.mediaStoreId }
          services.keeper.restoreOriginal(rec.mediaId, confirmation, PhoneWriteGrant(first.rows.toSet()))
        }
        1 -> services.keeper.restoreOriginal(rec.mediaId, confirmation, PhoneWriteGrant(emptySet()))
        else -> Unit
      }
    }
    // Il lavoro che non e' finito dentro la chiamata lo riprende il backup.
    runVersions()
  }

  private suspend fun correct(rec: MediaRec) {
    val plan = services.corrections.plan(rec.mediaId, CorrectionRequest(ChangeSamples.newDate))
    val confirmation = TestConfirmations.confirm(plan, world.clock.monotonicMs())
    val grant = if (plan.phoneRows.isEmpty()) null else {
      fb.rewriter.granted += plan.phoneRows.map { it.mediaStoreId }
      PhoneWriteGrant(plan.phoneRows.toSet())
    }
    services.corrections.apply(plan, confirmation, grant)
    runVersions()
  }

  // =========================================================================================================================
  // "Libera spazio"
  // =========================================================================================================================

  private fun pickFreeEvent(): FreeEvent = when (random.nextInt(100)) {
    in 0 until 40 -> FreeEvent.NONE
    in 40 until 56 -> FreeEvent.TIME_JUMP
    in 56 until 66 -> FreeEvent.REBOOT
    in 66 until 78 -> FreeEvent.PC_TOUCH
    in 78 until 88 -> FreeEvent.PHONE_SAVE
    in 88 until 95 -> FreeEvent.PHONE_DELETE
    else -> FreeEvent.FREEZE
  }

  private suspend fun freeSpace(filter: FreeSpaceFilter, permanent: Boolean) {
    // Un giro rimasto a meta' (un processo morto) prima si riprende e si risolve: un giro alla volta.
    if (!continueActiveFree()) return
    val plan = services.free.plan(filter, permanent)
    val confirmation = TestConfirmations.confirm(plan, world.clock.monotonicMs())
    val stage = if (random.nextInt(100) < 20) Duration.ZERO else 1.hours
    val run = services.free.verify(plan, confirmation, stage)
    driveFree(run)
  }

  /** Riprende il giro di "Libera spazio" rimasto aperto nel diario, se c'e'. Vero se non c'e' piu' niente da riprendere. */
  private suspend fun continueActiveFree(): Boolean {
    scanNow()
    services.free.settle()
    val last = fb.freeDiary.runs().lastOrNull { !it.state.terminal } ?: return true
    driveFree(runOf(last))
    return true
  }

  private fun runOf(record: FreeRunRecord): FreeSpaceRun = FreeSpaceRun(
    id = record.id, planHash = record.planHash, state = record.state, permanent = record.permanent,
    guardedDone = 0, guardedTotal = 0, verifiedDone = 0, verifiedTotal = 0, verifiedBytes = 0, readyItems = 0, readyBytes = 0,
    batchesTotal = 0, batchesDone = record.batchesDone, freedItems = 0, freedBytes = 0,
  )

  /** Dal giro com'e' fino all'ultimo lotto: la verifica a tappe, l'evento di mezzo, poi ogni lotto controllato quando esce e dato al dialogo. */
  private suspend fun driveFree(start: FreeSpaceRun) {
    var run = start
    var stages = 0
    while (run.isActive && run.state != FreeSpaceRunState.READY && stages++ < 8) run = services.free.resume(run, 1.hours)
    if (run.state != FreeSpaceRunState.READY) return
    applyFreeEvent()
    var batches = 0
    while (batches++ < 50) {
      val batch = services.free.nextBatch(run) ?: return
      checkBatch(batch)
      val outcome = dialog(batch)
      run = services.free.recordOutcome(batch, outcome)
    }
  }

  private suspend fun applyFreeEvent() {
    val event = freeEvent
    freeEvent = FreeEvent.NONE
    when (event) {
      FreeEvent.NONE -> Unit
      FreeEvent.TIME_JUMP -> {
        // Meta' delle volte oltre le 6 ore di validita', meta' dentro.
        val hours = if (random.nextBoolean()) 7 + random.nextInt(4) else 1 + random.nextInt(4)
        fb.timePasses(hours * 3_600_000L)
        happened("passano $hours ore dopo la verifica")
      }
      FreeEvent.REBOOT -> {
        fb.reboot()
        happened("il telefono si riavvia dopo la verifica")
        incarnation.die(ChaosDeath(0, Primitive.PHONE_DIALOG, Moment.BEFORE))
      }
      FreeEvent.PC_TOUCH -> {
        val rec = fb.registry.values.filter { it.usualPath != null }.randomOrNull(random)
        if (rec != null && fb.pcEdit(world.share, rec.usualPath!!)) happened("il PC modifica una copia dopo la verifica")
      }
      FreeEvent.PHONE_SAVE -> {
        val rec = fb.registry.values.filter { fb.currentRow(it) != null }.randomOrNull(random)
        if (rec != null && fb.samsungSave(rec)) happened("Samsung salva sopra una foto dopo la verifica")
      }
      FreeEvent.PHONE_DELETE -> {
        val rec = fb.registry.values.filter { fb.currentRow(it) != null }.randomOrNull(random)
        if (rec != null && fb.phoneDelete(rec)) happened("l'utente cancella una foto dopo la verifica")
      }
      FreeEvent.FREEZE -> {
        world.gate.default = BackupGateState.FROZEN
        happened("la share si congela dopo la verifica")
      }
    }
  }

  /** Il dialogo di sistema simulato: Android toglie le righe confermate, rifiuta, ne conferma una parte, o l'app viene uccisa durante il dialogo. */
  private fun dialog(batch: TrashBatch): TrashOutcome {
    val rows = batch.items.map { it.phone }
    fb.handedRows += rows.map { it.mediaStoreId }
    fun removeAll(chosen: List<PhoneRef>) {
      chosen.forEach {
        world.phone.delete(it.mediaStoreId)
        fb.removedRows += batch.runId.value to it.mediaStoreId
      }
    }
    return when (random.nextInt(100)) {
      in 0 until 60 -> {
        removeAll(rows)
        stats.count("libera spazio: righe liberate dal dialogo", rows.size.toLong())
        TrashOutcome.Confirmed(rows.toSet())
      }
      in 60 until 70 -> {
        val part = rows.filter { random.nextBoolean() }
        removeAll(part)
        stats.count("libera spazio: righe liberate dal dialogo", part.size.toLong())
        TrashOutcome.Confirmed(part.toSet())
      }
      in 70 until 78 -> {
        stats.count("libera spazio: dialoghi rifiutati")
        TrashOutcome.Refused
      }
      else -> {
        // L'app viene uccisa durante il dialogo: l'utente puo' aver gia' confermato, l'esito non arriva mai.
        if (random.nextInt(100) < 70) removeAll(rows)
        happened("l'app viene uccisa durante il dialogo di sistema")
        val death = ChaosDeath(0, Primitive.PHONE_DIALOG, Moment.AFTER)
        val fault = FiredFault(0, Primitive.PHONE_DIALOG, Moment.AFTER, FaultKind.KILL, applied = true)
        injector.fired += fault
        stats.fired(fault)
        incarnation.die(death)
      }
    }
  }

  /**
   * Invariante 14, **al momento in cui il lotto esce** e contro la verita' del NAS e del telefono (non contro cio' che dice il motore): per
   * ogni riga del lotto il telefono ha i byte della versione; ogni versione da proteggere ha una copia privata che il giro ha riletto, i cui
   * byte sul NAS sono ancora quelli della versione e il cui timbro e' ancora quello letto; la rilettura e' di meno di 6 ore fa (orologio
   * monotono) e dello stesso avvio del telefono; il diario sa che la riga e' nel dialogo; nessuna riga e' gia' in un altro dialogo.
   */
  private suspend fun checkBatch(batch: TrashBatch) {
    val scan = scanner.scan(allowCache = config.hashCache)
    stats.hashed(scan.filesHashed, scan.bytesHashed)
    val byKey = HashMap<String, NasFileFact>().also { map -> scan.files.forEach { map[it.key] = it } }
    val diary = fb.freeDiary.everything()
    val mine = diary.filter { it.first == batch.runId }.map { it.second }
    fun bad(text: String) {
      batchNotes += Note(Invariant.FREE_SPACE, text)
      log += "VIOLAZIONE 14: $text"
    }
    if (batch.items.size > FreeSpaceRules.MAX_BATCH) bad("lotto oltre i 2.000")
    stats.count("libera spazio: lotti controllati")
    stats.count("libera spazio: righe nei lotti controllati", batch.items.size.toLong())
    val now = world.clock.monotonicMs()
    for (t in batch.items) {
      val rec = fb.recOf(t.mediaId)
      if (rec == null) {
        bad("un lotto con un media sconosciuto")
        continue
      }
      val wanted = fb.shaOf(t.mediaId, t.versionId)
      val phoneSha = world.phone.sha256(t.phone.mediaStoreId)
      if (wanted == null || phoneSha != wanted) bad("la riga del telefono non ha i byte della versione (media ${rec.text})")
      val record = mine.firstOrNull { it.phone == t.phone }
      if (record == null || record.state != FreeItemState.IN_DIALOG || record.batchIndex != batch.index) {
        bad("il diario non sa che la riga e' nel dialogo (media ${rec.text})")
      }
      if (diary.any { (run, item) -> run != batch.runId && item.phone == t.phone && (item.state == FreeItemState.IN_DIALOG || item.state == FreeItemState.IN_DOUBT) }) {
        bad("una riga gia' in un altro dialogo (media ${rec.text})")
      }
      val core = fb.catalog.media(listOf(t.mediaId)).firstOrNull()?.media
      val required = if (core == null) emptyList() else FreeSpaceEvaluator.requiredVersions(core, t.versionId)
      for (v in required) {
        val truth = fb.shaOf(t.mediaId, v)
        val check = record?.checks?.firstOrNull { it.versionId == v }
        if (check == null) {
          bad("una versione da proteggere senza una copia riletta dal giro (media ${rec.text})")
          continue
        }
        val file = byKey[NasFileFact.keyOf(check.share, check.path.value)]
        val loc = check.location
        val at = fb.verifiedAt[loc]
        when {
          file == null || truth == null || file.sha != truth -> bad("una copia riletta non ha piu' i byte della versione (media ${rec.text})")
          at == null || at.second != fb.bootCount || now < at.first || now - at.first > FreeSpaceRules.VERIFICATION_VALID_MS ->
            bad("una verifica scaduta o di un altro avvio (media ${rec.text})")
        }
        // Il timbro: la copia dev'essere ancora quella che il giro ha riletto (dimensione e data).
        val stamp = fb.stampOf(check.share, check.path)
        if (stamp == null || stamp.size != check.stamp.size || stamp.modifiedAtMillis != check.stamp.modifiedAtMillis) {
          bad("il timbro di una copia e' cambiato dopo la verifica (media ${rec.text})")
        }
      }
    }
  }

  // =========================================================================================================================
  // Gli eventi, le verifiche, la fine
  // =========================================================================================================================

  private fun happened(what: String) {
    stats.event("5B $what")
    log += "evento: $what"
  }

  /** Il mondo cambia fra un passo e l'altro (senza colpi). */
  private suspend fun event() {
    val roll = random.nextInt(100)
    val media = fb.registry.values.toList()
    when {
      roll < 22 -> Unit
      roll < 34 -> {
        scanNow()
        happened("scansione del telefono e del NAS")
      }
      roll < 44 -> {
        val rec = media.filter { it.usualPath != null }.randomOrNull(random)
        if (rec != null && !busy(rec) && fb.pcEdit(world.share, rec.usualPath!!)) happened("il PC modifica un file")
      }
      roll < 50 -> {
        // Un byte che marcisce conservando il timbro non si vede finche' nessuno rilegge la copia: il banco lo fa solo dove nessun giro di "Libera
        // spazio" ha gia' riletto una copia e non ha ancora consegnato il lotto (la finestra della verifica sul momento, §2.5, dichiarata).
        val rec = media.filter { it.category == FiveBCategory.SAFE_PHONE || it.category == FiveBCategory.EDITED_SAFE }.randomOrNull(random)
        // Con la cache dei timbri nelle verifiche a meta' (il NAS vero) un byte che marcisce con lo stesso timbro non si vedrebbe: non si fa.
        if (rec != null && !config.hashCache && !busy(rec) && fb.freeDiary.runs().none { !it.state.terminal } && fb.tamper(rec)) {
          happened("un byte marcisce sul NAS (stesso timbro)")
        }
      }
      roll < 58 -> {
        // Anche con un lavoro dal telefono aperto (F1, corretto il 19/10: `ChaosKnownDefectsTest`).
        fb.phoneRebuild()
        scanNow()
        happened("MediaStore ricostruito, _ID rinumerati")
      }
      roll < 63 -> {
        val rec = media.filter { fb.currentRow(it) != null && !busy(it) }.randomOrNull(random)
        if (rec != null && fb.phoneDelete(rec)) happened("una foto cancellata dal telefono")
      }
      roll < 73 -> {
        val hours = 1 + random.nextInt(9)
        fb.timePasses(hours * 3_600_000L)
        happened("passano $hours ore")
      }
      roll < 79 -> {
        fb.reboot()
        restartServices()
        happened("il telefono si riavvia")
      }
      roll < 86 -> {
        val rec = media.filter { fb.currentRow(it) != null && !busy(it) }.randomOrNull(random)
        if (rec != null && fb.samsungSave(rec)) happened("Samsung salva sopra una foto (nessuno l'ha ancora letta)")
      }
      else -> Unit
    }
  }

  private fun notes(scan: ScanResult): List<Note> =
    engineNotes + batchNotes + guardViolations.map { Note(Invariant.DIARY, it) } + scan.problems.map { Note(Invariant.NAS_READS, it) }

  private suspend fun cited(): Set<String> = (fb.journal.citedTemps() + fb.guardStore.citedTemps()).mapTo(LinkedHashSet()) { it.value }

  private suspend fun midCheck(label: String) {
    val scan = scanner.scan(allowCache = config.hashCache)
    stats.hashed(scan.filesHashed, scan.bytesHashed)
    stats.midCheck()
    val state = fb.capture(scan, notes(scan), cited())
    FiveBVerifier.check(state, Phase.MID, showNames = !redact).forEach { record(it, label, state) }
  }

  private fun record(v: Violation, label: String, state: FiveBState? = null) {
    val key = "${v.invariant}|${v.detail}"
    if (key in violations) return
    violations[key] = Violation(v.invariant, "$label: ${v.detail}")
    if (badState == null) badState = state
  }

  private suspend fun finish() {
    injector.disarm()
    world.gate.default = BackupGateState.OPEN
    restartServices()
    converge()
    val scan = scanner.scan(allowCache = false)
    stats.hashed(scan.filesHashed, scan.bytesHashed)
    stats.finalCheck()
    val state = fb.capture(scan, notes(scan), cited())
    FiveBVerifier.check(state, Phase.FINAL, showNames = !redact).forEach { record(it, "a lavoro finito", state) }
    for (job in fb.journal.all()) stats.count("lavori ${job.kind.code}: ${job.state.code}")
    for (item in fb.guardStore.all()) stats.count("guardiano ${item.kind.code}: ${item.state.code}")
    for ((_, item) in fb.freeDiary.everything()) stats.count("libera spazio, elementi: ${item.state.code}")
    stats.count("media seminati", fb.registry.size.toLong())
    stats.count("interruzioni cadute nella fase 5B", injector.fired.size.toLong())
    stats.count("morti del processo nella fase 5B", deaths.toLong())
  }

  /** Tolti i guasti, si riprende dai diari finche' non c'e' piu' niente da fare (al massimo [Backup5BConfig.resumeBound] riprese). */
  private suspend fun converge() {
    var quietRounds = 0
    repeat(config.resumeBound) {
      scanNow()
      guarded {
        runVersions()
        // Il guardiano riprende dal suo diario (un temporaneo suo non resta a lavoro finito) e protegge cio' che il telefono non ha piu'.
        services.guardian.ensure(GuardScope.All, 1.hours)
        scanNow()
        services.free.settle()
      }
      if (fb.journal.all().none { !it.state.terminal }) quietRounds++ else quietRounds = 0
      if (quietRounds >= 2) return
    }
    log += "dopo ${config.resumeBound} riprese resta ancora lavoro"
  }
}
