package dev.pampa.mycloudgallery.core.nasops.chaos

import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nasops.contract.ItemFlow
import dev.pampa.mycloudgallery.core.nasops.contract.NasItem
import dev.pampa.mycloudgallery.core.nasops.contract.OpId
import dev.pampa.mycloudgallery.core.nasops.contract.OpItemRecord
import dev.pampa.mycloudgallery.core.nasops.contract.OpItemState
import dev.pampa.mycloudgallery.core.nasops.contract.OpPlan
import dev.pampa.mycloudgallery.core.nasops.contract.OpRequest
import dev.pampa.mycloudgallery.core.nasops.contract.OpState
import dev.pampa.mycloudgallery.core.nasops.contract.OpsGate
import dev.pampa.mycloudgallery.core.nasops.contract.OpsStore
import dev.pampa.mycloudgallery.core.nasops.contract.OwnTrashRef
import dev.pampa.mycloudgallery.core.nasops.contract.PlanOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.PlannedItem
import dev.pampa.mycloudgallery.core.nasops.contract.PlanRejection
import dev.pampa.mycloudgallery.core.nasops.contract.StartOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.TrashReason
import dev.pampa.mycloudgallery.core.nasops.contract.TrashRestoreRef
import dev.pampa.mycloudgallery.core.nasops.testing.FakeOpsGate
import dev.pampa.mycloudgallery.core.nasops.testing.TestConfirmations
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes

// Un GIRO della prova delle 500 interruzioni (docs/fase4-progetto.md §5.5): un mondo nuovo con qualche originale, da 1 a 4 azioni
// dell'utente (sposta, rinomina, elimina, ripristina...), ciascuna con colpi casuali: connessione che cade, sessione che scade, socket
// fermo, MORTE DEL PROCESSO (si buttano tutti gli oggetti e si riparte dal diario). Dopo ogni morte e a fine passo il verificatore
// controlla gli invarianti; alla fine una rilettura completa.
//
// Un giro e' ripetibile: stesso seme, stesse scelte, stessi colpi (il codice sotto prova e' deterministico).

/** Come gira un banco. */
class ChaosConfig(
  val minSteps: Int = 2,
  val maxSteps: Int = 6,
  val minFiles: Int = 6,
  val maxFiles: Int = 12,
  /** La taglia di un file "medio" (qualche giro su venti ne ha uno): piu' blocchi del ripiego dal telefono (256 KiB). Sul NAS vero 1 MB. */
  val mediumFileBytes: Int = 340_000,
  /** La taglia del file "grande" e quante volte su cento un file lo e': sul NAS vero e' `chaos.bigFileMb`. */
  val bigFileBytes: Int = 600_000,
  val bigFilePercent: Int = 1,
  val mix: FaultMix = FaultMix.DEFAULT,
  val maxFaultsPerStep: Int = 6,
  /** Le riprese (`resumeAll`) dopo cui un'operazione ancora aperta e' "non arriva in fondo". */
  val resumeBound: Int = 80,
  /** Percentuale di passi cancellabili in cui l'utente tocca "Ferma" invece di lasciar riprendere. */
  val cancelPercent: Int = 30,
  /** Percentuale di passi finiti dopo cui si prova una morte senza colpo: la ripresa a vuoto non deve cambiare niente. */
  val idleRestartPercent: Int = 20,
  /** Con la cache un file non cambiato non si rilegge nelle verifiche intermedie (il NAS vero con file da 200 MB). L'ultima lettura e' sempre completa. */
  val hashCache: Boolean = false,
  /** Se non null, il massimo di colpi per passo (il runner sul NAS vero: uno solo, cosi' ogni giro vale una interruzione). */
  val stepFaults: Int? = null,
  /**
   * Su un mondo che lo sa fare ([ChaosWorld.Capabilities.libraryReads]), la percentuale di cartelle toccate da una rinomina, uno spostamento o
   * un'eliminazione con la Libreria che tiene aperto un file qui sotto: su Samba la rinomina e' rifiutata finche' l'handle e' aperto, e
   * l'operazione deve arrivare in fondo lo stesso (con gli stessi invarianti, colpi e morti del processo di sempre).
   */
  val libraryReadPercent: Int = 40,
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
  /** Il numero del difetto noto che ha fermato il giro (e che e' stato contato), o null. */
  val stoppedByKnownDefect: String? = null,
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

/** Il giro si ferma: ha mostrato un difetto noto dello stack sotto prova (non un difetto nuovo). */
private class KnownDefectStop(val id: String) : Exception(id, null, false, false)

class ChaosRound(
  private val index: Int,
  private val seed: Long,
  private val target: ChaosTarget,
  private val factory: ChaosStackFactory,
  private val config: ChaosConfig,
  private val stats: ChaosStats,
) {
  private val random = Random(seed)

  /** Un generatore a parte per le letture della Libreria: non cambia le scelte dei giri che non le hanno. */
  private val libraryRandom = Random(seed xor LIBRARY_SALT)
  private val ids = SeededIds(seed xor ID_SALT)
  private val ledger = Ledger()
  private val model = FsModel()
  private val violations = ArrayList<Violation>()
  private val log = ArrayList<String>()
  private val stepLog = ArrayList<String>()
  private val storeViolations = ArrayList<String>()
  private val createdFolders = HashSet<String>()

  private lateinit var world: ChaosWorld
  private lateinit var store: OpsStore
  private lateinit var scanner: NasScanner
  private lateinit var context: VerifyContext
  private val injector = Injector(stats) { world.connectionLost() }.also { it.excluded = factory.unfaultable }
  private val gate: OpsGate = FakeOpsGate()
  private var incarnation = Incarnation(0)
  private lateinit var stack: ChaosStack
  private var deaths = 0
  private var stepNumber = 0

  /** Fa il giro. Non lancia mai per un errore del codice sotto prova: lo mette nelle violazioni. */
  suspend fun run(): RoundReport {
    stats.round()
    var stopped: String? = null
    try {
      world = target.openWorld(index)
      try {
        setUp()
        val steps = config.minSteps + random.nextInt(config.maxSteps - config.minSteps + 1)
        repeat(steps) { step() }
        finalCheck()
      } finally {
        world.close()
      }
    } catch (stop: KnownDefectStop) {
      stopped = stop.id
    } catch (death: ProcessDeath) {
      violations += Violation(Invariant.CONVERGENCE, "una morte del processo e' sfuggita al banco: $death")
    } catch (e: Throwable) {
      violations += Violation(Invariant.DIARY, "eccezione nel codice sotto prova o nel banco: ${e::class.simpleName}: ${e.message} @ ${e.stackTrace.take(6).joinToString(" < ")}")
    }
    violations += storeViolations.map { Violation(Invariant.DIARY, it) }.distinctBy { it.detail }
    return RoundReport(index, seed, stepLog, injector.fired.toList(), violations.distinctBy { it.toString() }.take(40), deaths, log, stopped)
  }

  // =========================================================================================================================
  // Il mondo iniziale
  // =========================================================================================================================

  private suspend fun setUp() {
    store = factory.newStore(world)
    scanner = NasScanner(world.reader, ScanScope.wholeShares(world.shares), setOf(world.deviceId), useCache = config.hashCache)
    context = VerifyContext(
      ledger = ledger,
      diary = StoreDiary(store),
      ownDevice = world.deviceId,
      survives = { o -> o.id !in model.gone && model.survives(o.id) },
      writesTrashRecords = factory.writesTrashRecords,
      strictAppArea = true,
    )
    stack = newStack()
    val files = config.minFiles + random.nextInt(config.maxFiles - config.minFiles + 1)
    for (i in 0 until files) {
      val share = world.shares[random.nextInt(world.shares.size)]
      val depth = when (random.nextInt(10)) {
        in 0 until 3 -> 0
        in 3 until 7 -> 1
        in 7 until 9 -> 2
        else -> 3
      }
      var dir: Place? = null
      repeat(depth) {
        val known = model.knownDirs(share).filter { it.path.count { c -> c == '/' } == (dir?.path?.count { c -> c == '/' }?.plus(1) ?: 0) && (dir == null || it.isWithin(dir!!)) }
        dir = if (known.isNotEmpty() && random.nextInt(100) < 70) {
          known.random(random)
        } else {
          val name = Vocabulary.dirs.filter { !model.occupied(dir?.child(it) ?: Place(share, it)) }.randomOrNull(random)
          if (name == null) dir else (dir?.child(name) ?: Place(share, name))
        }
      }
      val name = Vocabulary.files.filter { !model.occupied(dir?.child(it) ?: Place(share, it)) }.randomOrNull(random) ?: continue
      val place = dir?.child(name) ?: Place(share, name)
      addOriginal(place, OriginalBytes.size(random, config.mediumFileBytes, config.bigFileBytes, config.bigFilePercent))
    }
    log += "mondo iniziale: ${ledger.originals.size} originali in ${world.shares.size} share (${world.describe()})"
    // Il mondo iniziale deve gia' essere a posto: altrimenti il banco ha un difetto, non il codice sotto prova.
    val snapshot = scan()
    violations += Verifier.quiescent(snapshot, context, expectation()).map { Violation(it.invariant, "mondo iniziale: ${it.detail}") }
  }

  private suspend fun addOriginal(wanted: Place, size: Int): Original {
    // Se la cartella esiste gia' con un'altra grafia (rinominata solo nelle maiuscole) il file finisce dentro quella.
    val place = model.canonicalize(wanted)
    val original = Original.of(ledger.originals.size, OriginalBytes.make(random, ledger.originals.size, size))
    ledger.add(original)
    world.putFile(place.share, SharePath.parse(place.path), original.bytes!!)
    model.visible[original.id] = place
    place.parent()?.let { model.addDir(it) }
    return original
  }

  // =========================================================================================================================
  // Lo stack e le morti
  // =========================================================================================================================

  private fun newStack(): ChaosStack {
    val inc = incarnation
    val env = StackEnv(
      world = world,
      fileOps = ChaosFileOps(world.fileOps, injector, inc),
      reader = ChaosReader(world.reader, injector, inc),
      store = ChaosStore(store, injector, inc, storeViolations),
      clocks = world.clocks,
      gate = gate,
      newId = ids::next,
      readOnly = { false },
    )
    return factory.create(env)
  }

  /** Il processo e' morto: si controlla il mondo cosi' com'e' (a un passo dalla morte) e si riparte con oggetti nuovi sullo stesso diario. */
  private suspend fun died(cause: ProcessDeath?) {
    deaths++
    stats.restart()
    log += "morte del processo #$deaths${cause?.let { " (${it.primitive} ${if (it.moment == Moment.BEFORE) "prima" else "dopo"})" } ?: ""}"
    // Il sistema chiude i socket del processo morto: il NAS chiude i suoi handle (come sul telefono, ChaosWorld.processDied).
    world.processDied()
    // Se un difetto noto aveva gia' fatto il suo danno prima di questa morte, il mondo non rispetta piu' gli invarianti per colpa sua.
    stopOnKnownDefect()
    val snapshot = scan()
    stats.midCheck()
    for (v in Verifier.midState(snapshot, context)) {
      violations += Violation(v.invariant, "dopo la morte #$deaths: ${v.detail}")
    }
    incarnation = Incarnation(incarnation.number + 1)
    stack = newStack()
  }

  private suspend fun scan(): Snapshot {
    val snapshot = scanner.scan()
    stats.hashed(snapshot.filesHashed, snapshot.bytesHashed)
    return snapshot
  }

  /** Dopo una chiamata del codice sotto prova: se il processo e' morto (anche se la chiamata e' tornata) si riparte. */
  private suspend fun <T> guarded(block: suspend () -> T): T? {
    val result = try {
      block()
    } catch (d: ProcessDeath) {
      died(d)
      return null
    }
    if (incarnation.dead) {
      died(null)
      return null
    }
    return result
  }

  // =========================================================================================================================
  // I passi
  // =========================================================================================================================

  private suspend fun step() {
    val planner = StepPlanner(random, model, world.shares, world.capabilities.advanceTime, world.capabilities.copyFallback, createdFolders)
    val spec = planner.next() ?: return
    stepNumber++
    stepLog += spec.describe()
    log += "--- passo $stepNumber: ${spec.describe()}"
    stats.step(spec.kind)
    if (world.capabilities.advanceTime) world.advanceTime(1_000L + random.nextInt(60_000))
    when (spec) {
      is StepSpec.PcCreate -> {
        val original = addOriginal(spec.place, spec.size)
        log += "il PC ha creato $original in ${spec.place}"
      }
      is StepSpec.ExpirePurge -> expirePurge(spec)
      else -> userAction(spec)
    }
    if (random.nextInt(100) < config.idleRestartPercent) idleRestart()
  }

  private fun armFaults(spec: StepSpec, estimate: Int) {
    val usable = spec.primitives.filter { it.side != Side.STORE && it !in factory.unfaultable }
    val rules = Injector.randomRules(random.nextLong(), estimate, config.stepFaults ?: config.maxFaultsPerStep, usable, config.mix)
    injector.load(rules)
    log += "colpi previsti: ${rules.joinToString("; ")}"
  }

  // --- Le azioni dell'utente: piano, conferma, avvio, ripresa -----------------------------------------------------------------

  private suspend fun userAction(spec: StepSpec) {
    val library = openLibraryReads(spec)
    try {
      runUserAction(spec)
    } finally {
      library.forEach { runCatching { it.close() } }
    }
  }

  /**
   * La Libreria sta leggendo un file dentro una cartella che l'utente sta per rinominare, spostare nella stessa share o eliminare: l'handle resta
   * aperto per tutto il passo (anche attraverso colpi e morti del processo). Si apre senza colpi, dal mondo.
   */
  private suspend fun openLibraryReads(spec: StepSpec): List<AutoCloseable> {
    if (!world.capabilities.libraryReads || config.libraryReadPercent <= 0) return emptyList()
    val folders = when (spec) {
      is StepSpec.Rename -> listOf(spec.src)
      is StepSpec.Move -> if (spec.crossShare) emptyList() else spec.sources
      is StepSpec.Trash -> spec.sources
      else -> emptyList()
    }.filter { it.isFolder }
    val opened = ArrayList<AutoCloseable>()
    for (folder in folders) {
      if (libraryRandom.nextInt(100) >= config.libraryReadPercent) continue
      val (_, place) = model.filesUnder(folder.place).randomOrNull(libraryRandom) ?: continue
      val handle = world.openLibraryRead(place.share, SharePath.parse(place.path)) ?: continue
      stats.libraryRead()
      log += "la Libreria legge $place, sotto ${folder.place}"
      opened += handle
    }
    return opened
  }

  private suspend fun runUserAction(spec: StepSpec) {
    if (spec is StepSpec.Move) world.setCopyMode(if (spec.fallback) CopyMode.FALLBACK else CopyMode.SERVER)
    val estimate = 14 + 9 * spec.itemCount
    val opsBefore = store.ops(OpState.entries.toSet()).map { it.id }.toSet()
    if (spec is StepSpec.Purge) model.pendingPurge += spec.trashIds
    armFaults(spec, estimate)
    val started = start(spec, opsBefore)
    if (started == null) {
      injector.disarm()
      model.pendingPurge.clear()
      return
    }
    val (plan, opId) = started
    val cancelled = converge(spec, opId)
    injector.disarm()
    finishStep(spec, plan, opId, cancelled)
    model.pendingPurge.clear()
  }

  /** Piano e avvio, con i colpi: ritorna il piano e l'operazione nata, o null se il passo non c'e' stato (saltato o fallito: si e' registrato il perche'). */
  private suspend fun start(spec: StepSpec, opsBefore: Set<OpId>): Pair<OpPlan, OpId>? {
    var attempts = 0
    while (true) {
      if (++attempts > START_ATTEMPTS) {
        violations += Violation(Invariant.CONVERGENCE, "la richiesta non riesce a partire dopo $START_ATTEMPTS tentativi: ${spec.describe()}")
        return null
      }
      val request = materialize(spec) ?: return null
      var plan: OpPlan? = null
      var outcome: Pair<OpPlan, OpId>? = null
      var gaveUp = false
      guarded {
        when (val planned = stack.operations.plan(request)) {
          is PlanOutcome.Rejected -> {
            if (planned.reason != PlanRejection.NAS_UNREACHABLE) {
              violations += Violation(Invariant.CONVERGENCE, "il piano e' stato rifiutato (${planned.reason.code}) per una richiesta valida: ${spec.describe()}")
              gaveUp = true
            }
          }
          is PlanOutcome.Ready -> {
            plan = planned.plan
            val existing = store.opByPlanHash(planned.plan.hash)
            if (existing != null && existing.id in opsBefore) {
              // La stessa richiesta, con lo stesso piano, e' gia' stata avviata in un passo precedente: l'impronta del piano e' l'identita'.
              log += "piano uguale a un'operazione di un passo precedente: passo saltato"
              stats.step("passo saltato (stesso piano di prima)")
              gaveUp = true
            } else {
              when (val started = stack.operations.start(planned.plan, TestConfirmations.forPlan(planned.plan, world.clocks.monotonicMs()))) {
                is StartOutcome.Started -> outcome = planned.plan to started.id
                is StartOutcome.AlreadyStarted -> outcome = planned.plan to started.id
                else -> {
                  violations += Violation(Invariant.CONVERGENCE, "l'avvio e' stato rifiutato ($started) per un piano valido: ${spec.describe()}")
                  gaveUp = true
                }
              }
            }
          }
        }
      }
      if (gaveUp) return null
      if (outcome != null) return outcome
      // Il processo e' morto, o il piano non ha potuto leggere: l'operazione e' nata lo stesso? Lo dice il diario, come lo vedrebbe l'utente.
      val made = plan?.let { p -> store.opByPlanHash(p.hash)?.takeIf { it.id !in opsBefore }?.let { p to it.id } }
      if (made != null) return made
    }
  }

  /**
   * Il motivo della pausa come lo dice il diario: il codice dell'operazione e, per ogni elemento non finito, passo, codice, tentativi e fra
   * quanti secondi (ora del NAS) e' la prossima prova. Una pausa senza motivo leggibile e' gia' un difetto.
   */
  private suspend fun pauseReasons(active: List<dev.pampa.mycloudgallery.core.nasops.contract.OpRecord>): String {
    val now = world.clocks.nasNowMs()
    val parts = ArrayList<String>()
    for (op in active) {
      val open = store.items(op.id).filter { !it.state.terminal }.joinToString(", ") { item ->
        val wait = item.nextAttemptAtNas?.let { at -> if (now != null) " fra ${(at - now) / 1000} s" else " a $at" }.orEmpty()
        "#${item.index} ${item.state.code} ${item.errorCode?.code ?: "-"} x${item.attempts}$wait"
      }
      parts += "${op.kind.code} motivo=${op.errorCode?.code ?: "nessuno"} elementi: $open"
    }
    return parts.joinToString(" | ")
  }

  /** Il diario mostra l'effetto di un difetto noto dello stack: si ferma il giro, e si conta. */
  private suspend fun stopOnKnownDefect() {
    val items = store.ops(OpState.entries.toSet()).flatMap { store.items(it.id) }
    val id = factory.knownDefectHit(items) ?: return
    stats.knownDefect(id)
    log += "difetto noto $id riprodotto: il giro si ferma"
    throw KnownDefectStop(id)
  }

  /** Riprende finche' non c'e' piu' niente di aperto. Ritorna vero se l'utente ha toccato "Ferma". */
  private suspend fun converge(spec: StepSpec, opId: OpId): Boolean {
    var passes = 0
    var cancelled = false
    var midChecks = 0
    while (true) {
      val active = store.ops(setOf(OpState.PLANNED, OpState.RUNNING, OpState.PAUSED))
      if (active.isEmpty()) return cancelled
      if (++passes > config.resumeBound) {
        violations += Violation(
          Invariant.CONVERGENCE,
          "dopo $passes riprese ci sono ancora ${active.size} operazioni aperte (${active.first().state.code}; ${pauseReasons(active)}): ${spec.describe()}",
        )
        return cancelled
      }
      val wantsCancel = spec.cancellable && !cancelled && random.nextInt(100) < config.cancelPercent
      val canCancel = wantsCancel && factory.cancelIsSafe(active.flatMap { store.items(it.id) }.filter { !it.state.terminal })
      if (wantsCancel && !canCancel) {
        stats.step("Ferma non provato (difetto noto dello stack)")
        log += "Ferma non provato: difetto noto dello stack"
      }
      if (canCancel) {
        cancelled = true
        stats.cancel()
        log += "l'utente tocca Ferma"
        guarded { active.forEach { stack.operations.cancel(it.id) } }
      } else {
        guarded { stack.operations.resumeAll(5.minutes) }
      }
      if (world.capabilities.advanceTime) world.advanceTime(1_000)
      stopOnKnownDefect()
      // Anche gli stati a meta' (operazione in pausa, temporaneo in giro) devono rispettare gli invarianti di sempre.
      if (midChecks < 3 && store.ops(setOf(OpState.PLANNED, OpState.RUNNING, OpState.PAUSED)).isNotEmpty()) {
        midChecks++
        stats.midCheck()
        for (v in Verifier.midState(scan(), context)) violations += Violation(v.invariant, "a meta' (riprese $passes): ${v.detail}")
      }
    }
  }

  // --- La pulizia del cestino scaduto (ora finta) -----------------------------------------------------------------------------

  private suspend fun expirePurge(spec: StepSpec.ExpirePurge) {
    world.advanceTime(31L * 24 * 60 * 60 * 1000)
    model.pendingPurge += model.slots.keys
    armFaults(spec, 14 + 9 * model.slots.size)
    var passes = 0
    while (store.trashed().isNotEmpty()) {
      if (++passes > config.resumeBound) {
        violations += Violation(Invariant.CONVERGENCE, "dopo $passes pulizie ci sono ancora voci scadute nel diario locale")
        break
      }
      guarded { stack.trash.purgeExpired(5.minutes) }
      if (store.trashed().isNotEmpty()) {
        stats.midCheck()
        for (v in Verifier.midState(scan(), context)) violations += Violation(v.invariant, "durante la pulizia: ${v.detail}")
      }
    }
    injector.disarm()
    for (slot in model.slots.values.toList()) removeSlot(slot)
    model.pendingPurge.clear()
    verifyQuiescent("dopo la pulizia del cestino", allowFailed = false, allowCancelled = false)
  }

  private suspend fun OpsStore.trashed() = expiredTrash(Long.MAX_VALUE, Int.MAX_VALUE)

  // =========================================================================================================================
  // Le richieste, dal NAS e dal diario
  // =========================================================================================================================

  /** L'elemento come lo vedrebbe l'utente scegliendolo: file o cartella con il timbro letto adesso. */
  private suspend fun itemOf(src: Src): NasItem? {
    val path = SharePath.parse(src.place.path)
    val entry = (world.reader.stat(src.place.share, path, Purpose.INTERACTIVE) as? NasResult.Ok)?.value
    if (entry == null) {
      violations += Violation(Invariant.CATALOG, "l'elemento atteso ${src.place} non c'e' sul NAS")
      return null
    }
    val size = if (src.isFolder) model.filesUnder(src.place).sumOf { ledger.byId(it.first).size } else entry.size
    val stamp = if (src.isFolder) null else NasStamp(entry.size, entry.modifiedAt, entry.fileId)
    return NasItem(src.place.share, path, src.isFolder, size, stamp)
  }

  private suspend fun materialize(spec: StepSpec): OpRequest? {
    when (spec) {
      is StepSpec.CreateFolder -> return OpRequest.CreateFolder(spec.share, spec.parent?.let(SharePath::parse), spec.name)
      is StepSpec.Rename -> return itemOf(spec.src)?.let { OpRequest.Rename(it, spec.newName) }
      is StepSpec.Move -> {
        val items = spec.sources.map { itemOf(it) ?: return null }
        return OpRequest.Move(items, spec.destShare, spec.destDir?.let(SharePath::parse))
      }
      is StepSpec.Trash -> {
        val items = spec.sources.map { itemOf(it) ?: return null }
        return OpRequest.Trash(items)
      }
      is StepSpec.Restore -> {
        val refs = spec.trashIds.map { id ->
          val entry = store.trashEntry(id)
          if (entry == null) {
            violations += Violation(Invariant.DIARY, "il diario locale non ha la voce del cestino che il modello prevede")
            return null
          }
          TrashRestoreRef(entry.shareName, world.deviceId, id, entry.originalPath, entry.isFolder, entry.size)
        }
        return OpRequest.Restore(refs)
      }
      is StepSpec.Purge -> return OpRequest.PurgeNow(spec.trashIds.map(::OwnTrashRef))
      else -> return null
    }
  }

  // =========================================================================================================================
  // Dopo il passo: l'effetto sul modello e le verifiche
  // =========================================================================================================================

  private var cancelAllowed = false

  private suspend fun finishStep(spec: StepSpec, plan: OpPlan, opId: OpId, cancelled: Boolean) {
    if (cancelled) cancelAllowed = true
    stopOnKnownDefect()
    val items = store.items(opId).associateBy { it.index }
    // Annullato o no, ogni elemento deve essere arrivato a uno stato finale; quelli rimasti a meta' li segnala la verifica del diario.
    applyPlan(spec, plan, items)
    verifyQuiescent(spec.describe(), allowFailed = false, allowCancelled = cancelled)
  }

  /** Il destinatario reale scritto nel diario: o quello del piano, o un nome alternativo (solo se il posto del piano era occupato). */
  private fun resolved(item: PlannedItem, record: OpItemRecord): Place {
    val planned = Place(item.dstShare!!, item.dstPath!!.value)
    val written = Place(record.dstShare ?: item.dstShare, record.dstPath!!.value)
    // Le cartelle che esistono gia' hanno la grafia del NAS, non quella del percorso del piano.
    val actual = model.canonicalize(written)
    if (written == planned) return actual
    val sameDir = actual.share.equals(planned.share, ignoreCase = true) &&
      actual.parentPath.orEmpty().equals(planned.parentPath.orEmpty(), ignoreCase = true)
    if (sameDir && AlternativeNames.isDouble(planned.name, written.name) && !AlternativeNames.isAlternativeOfSingle(planned.name, written.name)) {
      stats.step("nome alternativo doppio (osservazione)")
    }
    when {
      !sameDir || !AlternativeNames.isAlternativeOf(planned.name, written.name) ->
        violations += Violation(Invariant.CATALOG, "il diario dice che un elemento e' finito in $written invece di $planned")
      !model.occupied(planned) ->
        violations += Violation(Invariant.CATALOG, "nome alternativo ${written.name} scelto ma il posto $planned era libero")
    }
    return actual
  }

  private fun applyPlan(spec: StepSpec, plan: OpPlan, finalItems: Map<Int, OpItemRecord>) {
    for (item in plan.items) {
      val record = finalItems[item.index] ?: continue
      // Un elemento annullato non ha effetto; uno fallito o rimasto a meta' lo segnala la verifica del diario.
      if (record.state != OpItemState.DONE) continue
      val src = Place(item.srcShare, item.srcPath.value)
      when (item.flow) {
        ItemFlow.CREATE_FOLDER -> model.addDir(src, assert = true)

        ItemFlow.RENAME, ItemFlow.RENAME_CASE_ONLY -> {
          val dst = resolved(item, record)
          if (item.isFolder) {
            model.renameDir(src, dst)
          } else {
            val id = model.originalAt(src)
            if (id == null) harness("rinomina di un file che il modello non conosce: $src") else model.visible[id] = dst
            dst.parent()?.let { model.addDir(it) }
          }
        }

        // Una cartella spostata tra share: lo stack vero ha un elemento per la cartella stessa (la ricrea anche vuota nella destinazione e
        // toglie quella d'origine se e' vuota); quello di riferimento no. Nel modello la destinazione c'e', l'origine e' "forse".
        ItemFlow.MOVE_ACROSS_SHARES -> if (item.isFolder) {
          model.addDir(resolved(item, record), assert = true)
        } else {
          val id = model.originalAt(src)
          val trashId = item.trashId
          if (id == null || trashId == null) {
            harness("spostamento tra share di un file che il modello non conosce: $src")
          } else {
            val dst = resolved(item, record)
            model.visible[id] = dst
            dst.parent()?.let { model.addDir(it) }
            model.slots[trashId] = TrashSlot(trashId, src.share, TrashReason.MOVE, false, src, mapOf(id to ""), emptySet())
          }
        }

        ItemFlow.TRASH -> {
          val trashId = item.trashId ?: continue
          if (item.isFolder) {
            val under = model.filesUnder(src)
            val members = under.associate { (id, place) -> id to place.path.substring(src.path.length + 1) }
            val subdirs = model.dirs.values.filter { it != src && it.isWithin(src) }.map { it.path.substring(src.path.length + 1) }.toSet()
            under.forEach { model.visible.remove(it.first) }
            model.removeDirTree(src)
            model.slots[trashId] = TrashSlot(trashId, src.share, TrashReason.DELETE, true, src, members, subdirs)
          } else {
            val id = model.originalAt(src)
            if (id == null) {
              harness("eliminazione di un file che il modello non conosce: $src")
            } else {
              model.visible.remove(id)
              model.slots[trashId] = TrashSlot(trashId, src.share, TrashReason.DELETE, false, src, mapOf(id to ""), emptySet())
            }
          }
        }

        ItemFlow.RESTORE -> {
          val slot = item.trashId?.let { model.slots[it] }
          if (slot == null) {
            harness("ripristino di una voce che il modello non conosce")
          } else {
            val dst = resolved(item, record)
            slot.members.forEach { (id, rel) -> model.visible[id] = if (rel.isEmpty()) dst else dst.child(rel) }
            slot.subdirs.forEach { model.addDir(dst.child(it)) }
            if (slot.isFolder) model.addDir(dst, assert = true) else dst.parent()?.let { model.addDir(it) }
            model.slots.remove(slot.trashId)
          }
        }

        ItemFlow.PURGE -> item.trashId?.let { model.slots[it] }?.let(::removeSlot)
      }
    }
    // Una cartella spostata tra share lascia la sorgente: l'esecutore di riferimento la lascia, quello vero la toglie (rmdirIfEmpty) quando e' vuota.
    // O-B18: se lo stack la toglie e l'operazione e' finita bene (nessun elemento annullato o fermo), a operazioni quiete non deve piu' esserci.
    val completed = plan.items.all { finalItems[it.index]?.state == OpItemState.DONE }
    (spec as? StepSpec.Move)?.takeIf { it.crossShare }?.sources?.filter { it.isFolder }?.forEach {
      model.unassertTree(it.place)
      if (factory.removesEmptySourceFolders && completed) model.expectGone(it.place)
    }
  }

  private fun removeSlot(slot: TrashSlot) {
    model.slots.remove(slot.trashId)
    for (id in slot.members.keys) if (!model.survives(id)) model.gone += id
  }

  private fun harness(message: String) {
    violations += Violation(Invariant.CATALOG, "(difetto del banco) $message")
  }

  private fun expectation(): Expectation {
    val visible = model.visible.entries.associate { (id, place) -> ledger.byId(id).sha to ExpectedVisible(place, tolerantName = false) }
    val copies = HashMap<Sha256, Int>()
    for (slot in model.slots.values) for (id in slot.members.keys) copies.merge(ledger.byId(id).sha, 1, Int::plus)
    val gone = model.gone.map { ledger.byId(it).sha }.toSet()
    val dirs = model.asserted.mapNotNull { model.dirs[it] }.toSet()
    val slots = model.slots.mapValues { (_, slot) -> slot.members.entries.associate { (id, rel) -> ledger.byId(id).sha to rel } }
    return Expectation(visible, copies, gone, dirs, slots, goneDirs = model.goneDirs.values.toSet())
  }

  /** Gli originali devono essere tutti da qualche parte nel modello: se no, e' il banco che ha perso il filo. */
  private fun modelCoversEveryOriginal() {
    for (o in ledger.originals) {
      if (o.id !in model.visible && o.id !in model.gone && model.slotsOf(o.id).isEmpty()) harness("$o non e' in nessun posto del modello")
    }
  }

  private suspend fun verifyQuiescent(label: String, allowFailed: Boolean, allowCancelled: Boolean) {
    stats.quiescentCheck()
    modelCoversEveryOriginal()
    val snapshot = scan()
    val found = Verifier.quiescent(snapshot, context, expectation()) +
      Verifier.diary(context, allowFailed, allowCancelled || cancelAllowed, model.slots.keys, snapshot.trash.map { it.trashId }.toSet())
    found.forEach { violations += Violation(it.invariant, "$label: ${it.detail}") }
  }

  // =========================================================================================================================
  // La morte senza colpo e la rilettura finale
  // =========================================================================================================================

  private fun fingerprint(s: Snapshot): Set<String> =
    s.visible.map { "v|${it.place}|${it.sha}" }.toSet() + s.trash.map { "t|${it.share}|${it.trashId}|${it.rel}|${it.sha}" } +
      s.temps.map { "x|${it.share}|${it.name}" } + s.dirs.map { "d|$it" }

  /** Il processo viene ucciso senza che niente sia in corso: la ripresa non deve trovare niente da fare ne' cambiare il NAS. */
  private suspend fun idleRestart() {
    val before = fingerprint(scan())
    val opsBefore = store.ops(OpState.entries.toSet()).map { it.id.value to it.state }
    world.processDied()
    incarnation = Incarnation(incarnation.number + 1)
    stack = newStack()
    stats.restart()
    log += "riavvio a vuoto"
    val result = stack.operations.resumeAll(5.minutes)
    val after = fingerprint(scan())
    if (before != after) violations += Violation(Invariant.CATALOG, "una ripresa a vuoto ha cambiato il NAS (resumeAll: $result)")
    val opsAfter = store.ops(OpState.entries.toSet()).map { it.id.value to it.state }
    if (opsBefore != opsAfter) violations += Violation(Invariant.DIARY, "una ripresa a vuoto ha cambiato il diario")
  }

  /** L'ultima parola: rilettura completa (senza cache) di tutto il NAS, hash di ogni file, tutti gli invarianti. */
  private suspend fun finalCheck() {
    injector.disarm()
    modelCoversEveryOriginal()
    val cold = NasScanner(world.reader, ScanScope.wholeShares(world.shares), setOf(world.deviceId), useCache = false)
    val snapshot = cold.scan()
    stats.hashed(snapshot.filesHashed, snapshot.bytesHashed)
    stats.quiescentCheck()
    val found = Verifier.quiescent(snapshot, context, expectation()) +
      Verifier.diary(context, allowFailed = false, allowCancelled = cancelAllowed, expectedTrash = model.slots.keys, trashFilesPresent = snapshot.trash.map { it.trashId }.toSet())
    found.forEach { violations += Violation(it.invariant, "rilettura finale: ${it.detail}") }
    val files = snapshot.visible.size + snapshot.trash.size
    if (snapshot.filesHashed != files) violations += Violation(Invariant.HASH, "riletti ${snapshot.filesHashed} file su $files")
  }

  private companion object {
    const val ID_SALT = 0x5DEECE66DL
    const val LIBRARY_SALT = 0x2545F4914F6CDD1DL
    const val START_ATTEMPTS = 40
  }
}
