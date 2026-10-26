package dev.pampa.mycloudgallery.core.albums.chaos

import dev.pampa.mycloudgallery.core.albums.contract.HiddenChoice
import dev.pampa.mycloudgallery.core.albums.contract.MediaPlace
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemState
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanOutcome
import dev.pampa.mycloudgallery.core.albums.contract.ShareRequest
import dev.pampa.mycloudgallery.core.albums.contract.SharedState
import dev.pampa.mycloudgallery.core.albums.share.ShareBench
import dev.pampa.mycloudgallery.core.albums.share.ShareNasTarget
import dev.pampa.mycloudgallery.core.albums.testing.SimulatedDeath
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.nasops.contract.MoveCompletionSink
import dev.pampa.mycloudgallery.core.nasops.contract.OpState
import kotlin.random.Random
import kotlinx.coroutines.CancellationException

// IL GIRO (come `ChaosRound` di core-nasops): un mondo (media in tutti i posti), da 1 a 3 passi dell'utente, ognuno con i suoi colpi e, a volte, un
// guasto dell'ambiente. L'utente tocca (piano, conferma, avvio), il lavoro gira a colpi armati; se il processo muore si guarda il mondo cosi' com'e',
// si ricostruiscono gli oggetti sugli stessi diari e si riprende. Poi senza guasti il lavoro arriva in fondo e si controllano tutti gli invarianti. In
// fondo la rilettura completa con l'hash di ogni file, cosa vede un familiare e una morte senza colpo con la ripresa a vuoto.

/** Il NAS di un banco: il NAS finto in memoria, il simulatore SMB, o (area di prova) il NAS vero dietro la guardia. */
interface ChaosTargetFactory {
  val name: String
  val capabilities: Capabilities

  /** Un NAS nuovo per un giro. [completions] e' la porta di completamento (con i colpi); [injector] i colpi sul trasporto e sul diario del motore. */
  fun create(completions: MoveCompletionSink, injector: ShareChaosInjector): ShareNasTarget
}

/** Le mutazioni (`ShareChaosMutationTest`): un pezzo dello stack rotto di proposito. Di serie nessuna. */
class ShareChaosHooks(
  /** Avvolge il NAS creato (per un errore del motore). */
  val target: ((ShareNasTarget) -> ShareNasTarget)? = null,
  /** Avvolge la porta di completamento, fra il motore e l'osservatore. */
  val sink: ((MoveCompletionSink) -> MoveCompletionSink)? = null,
  /** Avvolge ancora le porte dell'orchestratore, sopra gli osservatori e i colpi. */
  val ports: ((ShareBench, ChaosPortsHandle) -> Unit)? = null,
)

class RoundReport(
  val index: Int,
  val seed: Long,
  val violations: List<Violation>,
  val fired: List<FiredBlow>,
  val deaths: Int,
  val steps: List<String>,
  val chronicle: List<String>,
) {
  val ok: Boolean get() = violations.isEmpty()

  fun describe(): String = buildString {
    appendLine("giro $index, seme $seed: ${violations.size} violazioni, ${fired.size} interruzioni, $deaths morti del processo")
    steps.forEachIndexed { i, s -> appendLine("  passo ${i + 1}: $s") }
    appendLine("  interruzioni: " + fired.joinToString("; ") { it.toString() })
    violations.take(12).forEach { appendLine("  VIOLAZIONE $it") }
    if (violations.size > 12) appendLine("  ... e altre ${violations.size - 12}")
    appendLine("  cronaca: " + chronicle.take(40).joinToString(" | "))
  }
}

class ShareChaosRound(
  private val index: Int,
  private val seed: Long,
  private val factory: ChaosTargetFactory,
  private val config: ShareChaosConfig,
  private val stats: ShareChaosStats,
  private val hooks: ShareChaosHooks = ShareChaosHooks(),
) {
  private val rnd = Random(seed)
  private val injector = ShareChaosInjector(stats)
  private val book = ConfirmationBook()
  private val observer = ShareChaosObserver(book)
  private val chronicle = ArrayList<String>()
  private val stepsDone = ArrayList<String>()
  private val violations = ArrayList<Violation>()
  private val albums = ArrayList<AlbumId>()
  private lateinit var bench: ShareBench
  private lateinit var verifier: ShareChaosVerifier
  private lateinit var hazards: HazardRunner
  private lateinit var pool: ChaosPool
  private var diaryBlow: Blow? = null
  private var deaths = 0
  private var stepNo = 0
  private var savesAtArm = 0
  private var firedBefore = 0

  suspend fun run(): RoundReport {
    var made = false
    try {
      bench = ShareBench { sink ->
        val observed = ChaosCompletionSink(sink, injector, observer)
        val engineSide = hooks.sink?.invoke(observed) ?: observed
        val target = factory.create(engineSide, injector)
        hooks.target?.invoke(target) ?: target
      }
      made = true
      observer.bench = bench
      val handle = ChaosPorts.install(bench, injector, observer, config.avoid)
      verifier = ShareChaosVerifier(bench, observer, stats) { injector.fired.any { it.blow.kind == ShareFaultKind.NO_SPACE } }
      hazards = HazardRunner(bench, verifier, chronicle)
      hooks.ports?.invoke(bench, handle)
      pool = ChaosPool(bench, rnd, config)
      chronicle += "mondo: " + pool.entries.joinToString(", ") { e -> "${e.id.value.takeLast(4)} ${e.kind.name.lowercase()} ${bench.library[e.id]?.path ?: "-"} ${bench.originals[e.id]?.size}B" }
      repeat(config.minSteps + rnd.nextInt(config.maxSteps - config.minSteps + 1)) { runStep() }
      finish()
      stats.itemsDone += bench.items().count { it.state == ShareItemState.DONE }
      stats.itemsFailed += bench.items().count { it.state == ShareItemState.FAILED }
      stats.itemsCancelled += bench.items().count { it.state == ShareItemState.CANCELLED }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      violations += Violation(ShareInvariant.CONTRACT, "giro", "eccezione non gestita (${e.javaClass.simpleName}): ${e.message}")
    } finally {
      if (made) runCatching { bench.close() }
    }
    stats.rounds++
    stats.observed += observer.observed
    observer.violations.let { violations += it }
    if (violations.isNotEmpty() && made) {
      runCatching {
        chronicle += "diario: " + bench.items().joinToString(" ") {
          "#${it.index}/${it.media.value.takeLast(4)}/${it.action.code}/${it.state.code}${it.failure?.let { f -> "(${f.code})" } ?: ""}"
        }
        for (op in bench.target.store.ops(OpState.entries.toSet())) {
          chronicle += "operazione ${op.state.code}: " + bench.target.store.items(op.id).joinToString(" ") {
            "#${it.index}/${it.srcPath.value.substringAfterLast('/')}/${it.state.code}/${it.errorCode?.name ?: "-"}/t${it.attempts}"
          }
        }
      }
    }
    return RoundReport(index, seed, violations.distinctBy { "${it.invariant}|${it.text}" }, injector.fired.toList(), deaths, stepsDone, chronicle)
  }

  // --- Un passo dell'utente -------------------------------------------------------------------------------------------------------

  private fun pickStep(): StepKind? {
    val options = ArrayList<Pair<Int, StepKind>>()
    if (pool.shareable().isNotEmpty()) options += 10 to StepKind.SHARE_NEW
    if (albums.isNotEmpty() && albums.any { pool.shareable(it).isNotEmpty() }) options += 6 to StepKind.ADD
    if (pool.bringable().isNotEmpty()) options += 4 to StepKind.BRING_BACK
    if (albums.isNotEmpty()) options += 3 to StepKind.ADOPT
    if (options.isEmpty()) return null
    var roll = rnd.nextInt(options.sumOf { it.first })
    for ((weight, kind) in options) {
      if (roll < weight) return kind
      roll -= weight
    }
    return options.last().second
  }

  private suspend fun runStep() {
    val kind = pickStep() ?: return
    stepNo++
    observer.phase = "passo $stepNo"
    stats.stepKind(kind.label)
    stats.steps++
    val selected = ArrayList<MediaId>()
    val request = requestFor(kind, selected) ?: run {
      chronicle += "passo $stepNo: ${kind.label}: niente da fare"
      return
    }
    stepsDone += "${kind.label}: ${selected.size} media"
    val hazard = hazardFor(kind, selected)
    val blows = ShareChaosBlows.generate(
      rnd, config, kind,
      movers = selected.count { bench.library[it]?.place == MediaPlace.MY_PRIVATE },
      uploads = selected.count { bench.library[it]?.place == MediaPlace.PHONE_ONLY },
    )
    chronicle += "passo $stepNo: ${kind.label}; colpi: ${blows.all.joinToString { it.toString() }.ifEmpty { "nessuno" }}"
    savesAtArm = bench.diary.saves
    firedBefore = injector.fired.size
    injector.arm(blows.early)
    armDiary()
    hazards.apply(hazard)
    if (hazard.kind != HazardKind.NONE) verifier.disturbed = true
    userStart(request)
    hazards.afterStart()
    injector.armLater(blows.late)
    armDiary()
    work()
    settle()
    observer.phase = "fine passo $stepNo"
    check("fine passo $stepNo", final = true)
    if (rnd.nextInt(100) < config.idleRestartPercent) idleCheck("fine passo $stepNo")
  }

  private suspend fun requestFor(kind: StepKind, selected: MutableList<MediaId>): ShareRequest? = when (kind) {
    StepKind.SHARE_NEW -> {
      val picks = pool.shareable().shuffled(rnd).take(1 + rnd.nextInt(5))
      selected += picks
      val hiddenOnes = picks.filter { bench.library[it]?.hidden == true }
      val choice = if (hiddenOnes.isNotEmpty() && rnd.nextInt(100) < 25) HiddenChoice.SHARE_TOO else HiddenChoice.LEAVE_OUT
      if (choice == HiddenChoice.LEAVE_OUT) verifier.hiddenLeftOut += hiddenOnes else verifier.hiddenShared += hiddenOnes
      ShareRequest.NewAlbum("Album $index-$stepNo", ShareLevel.ADD, picks, choice)
    }
    StepKind.ADD -> {
      val album = albums.filter { pool.shareable(it).isNotEmpty() }.random(rnd)
      val picks = pool.shareable(album).shuffled(rnd).take(1 + rnd.nextInt(3))
      selected += picks
      val hiddenOnes = picks.filter { bench.library[it]?.hidden == true }
      verifier.hiddenLeftOut += hiddenOnes
      ShareRequest.AddToShared(album, picks)
    }
    StepKind.BRING_BACK -> {
      val (album, media) = pool.bringable().random(rnd)
      selected += media
      ShareRequest.BringBack(album, listOf(media))
    }
    StepKind.ADOPT -> {
      val album = albums.random(rnd)
      val folder = bench.me.shared.folder(album)
      if (folder == null) {
        null
      } else {
        val pc = bench.commonMedia(PC_BASE + stepNo * 10 + index % 7, folder = folder.path.value, name = "pc_$stepNo.jpg")
        selected += pc
        ShareRequest.FolderAdoption(album, listOf(pc))
      }
    }
  }

  private fun hazardFor(kind: StepKind, selected: List<MediaId>): Hazard {
    if (rnd.nextInt(100) >= config.hazardPercent) return Hazard(HazardKind.NONE)
    val caps = config.capabilities
    val options = ArrayList<HazardKind>()
    val movers = selected.filter { bench.library[it]?.place == MediaPlace.MY_PRIVATE }
    val movable = movers.firstOrNull { bench.library[it]?.hidden != true }
    if (caps.lockedFiles && movable != null && (kind == StepKind.SHARE_NEW || kind == StepKind.ADD)) {
      options += HazardKind.LOCK
      // SD-1: con esiti diversi nello stesso trasferimento un'interruzione fra due salvataggi sbaglia gli elementi rimasti. Si evita con un file solo.
      if (KnownDefect.OUTCOMES_MISALIGNED !in config.avoid || movers.size == 1) options += HazardKind.LOCK_AND_MODIFY
    }
    options += HazardKind.FREEZE
    options += HazardKind.ALBUM_GATE
    if (caps.freeSpace && kind != StepKind.ADOPT) options += HazardKind.LOW_SPACE
    val pick = options[rnd.nextInt(options.size)]
    return Hazard(pick, movable, if (rnd.nextBoolean()) ShareNasTarget.PRIVATE else ShareNasTarget.COMMON, rounds = 1 + rnd.nextInt(3))
  }

  /** L'utente tocca: piano, foglio, conferma, avvio. Se l'avvio non arriva al diario (un guasto, una morte) riprova con un foglio nuovo. */
  private suspend fun userStart(request: ShareRequest) {
    val before = bench.diary.allOps().size
    var notReady = false
    repeat(MAX_TAPS) {
      if (bench.diary.allOps().size > before || notReady) return@repeat
      guarded("l'utente tocca") {
        val outcome = bench.orchestrator.plan(request)
        val plan = (outcome as? SharePlanOutcome.Ready)?.plan
        if (plan == null) {
          chronicle += "piano non pronto (${outcome::class.simpleName})"
          notReady = true
          return@guarded
        }
        val started = bench.orchestrator.start(plan, book.issue(bench, plan))
        chronicle += "avvio: ${started::class.simpleName}"
      }
    }
    // Gli album nuovi che hanno un'operazione nel diario (anche se l'avvio e' stato interrotto da una morte).
    for (op in bench.diary.allOps()) if (op.nonceHex != null && op.album !in albums) albums += op.album
  }

  // --- Il lavoro, a colpi armati --------------------------------------------------------------------------------------------------

  private suspend fun work() {
    for (i in 1..config.workRounds) {
      val before = injector.fired.size
      guarded("giro $i") { bench.round() }
      hazards.tick()
      if (injector.fired.size != before) check("giro $i del passo $stepNo", final = false)
    }
    check("fine del lavoro del passo $stepNo", final = false)
  }

  /** Senza guasti: il lavoro arriva in fondo (al massimo [ShareChaosConfig.settleRounds] giri); poi i familiari leggono. */
  private suspend fun settle() {
    stats.called(Surface.SHARE_DIARY, bench.diary.saves - savesAtArm)
    savesAtArm = bench.diary.saves
    injector.disarm()
    bench.diary.crashAtSave = null
    diaryBlow = null
    hazards.lift()
    var i = 0
    while (i < config.settleRounds) {
      if (i > 0 && converged()) break
      if (i > 0 && config.settlePauseMs > 0) kotlinx.coroutines.delay(config.settlePauseMs)
      guarded("ripresa $i") { bench.round() }
      i++
    }
    guarded("i familiari leggono") { bench.world.settle(2) }
  }

  private suspend fun converged(): Boolean =
    bench.diary.openOps().isEmpty() && bench.target.store.ops(OpState.entries.filter { !it.terminal }.toSet()).isEmpty()

  // --- La morte e i guasti --------------------------------------------------------------------------------------------------------

  /** Esegue [block]; una morte del processo (anche segnata da sotto, nella sessione SMB) ricostruisce il processo, un guasto passeggero e' solo un fatto. */
  private suspend fun guarded(what: String, block: suspend () -> Unit) {
    try {
      block()
    } catch (e: CancellationException) {
      throw e
    } catch (e: ProcessDeath) {
      onDeath(what, e.blow.toString())
      return
    } catch (e: SimulatedDeath) {
      val blow = diaryBlow
      if (blow != null) injector.diaryDied(blow)
      diaryBlow = null
      bench.diary.crashAtSave = null
      onDeath(what, blow?.toString() ?: "diario della condivisione")
      return
    } catch (e: ChaosFault) {
      chronicle += "$what: ${e.blow.kind.label} (${e.blow.surface.label})"
    } catch (e: Exception) {
      violations += Violation(ShareInvariant.CONTRACT, observer.phase, "eccezione uscita dal codice sotto prova durante \"$what\" (${e.javaClass.simpleName}): ${e.message}")
      bench.world.clocks.advance(ShareBench.STEP_MS)
      bench.target.advance(ShareBench.STEP_MS)
    }
    injector.dead?.let { onDeath(what, it.blow.toString()) }
  }

  /** Il processo e' morto: il mondo cosi' com'e', poi gli oggetti nuovi sugli stessi diari, store, NAS e catalogo. */
  private suspend fun onDeath(what: String, where: String) {
    deaths++
    verifier.processDeaths++
    stats.restarts++
    chronicle += "morte #$deaths durante \"$what\": $where"
    check("dopo la morte #$deaths", final = false)
    restartProcess()
    armDiary()
  }

  private suspend fun restartProcess() {
    bench.restart()
    book.nextProcess()
    injector.revive()
  }

  /** Il prossimo salvataggio del diario della condivisione che deve "morire", se ce n'e' uno in coda. */
  private fun armDiary() {
    if (diaryBlow != null) return
    val next = injector.nextDiaryBlow()
    if (next == null) {
      bench.diary.crashAtSave = null
      return
    }
    diaryBlow = next
    bench.diary.crashAtSave = bench.diary.saves + next.nth
    bench.diary.crashAfterWrite = next.moment == Moment.AFTER
  }

  private suspend fun check(phase: String, final: Boolean) {
    if (injector.fired.isNotEmpty()) verifier.disturbed = true
    violations += verifier.check(phase, final)
  }

  // --- La fine: rilettura, familiari, ripresa a vuoto -------------------------------------------------------------------------------

  private suspend fun finish() {
    observer.phase = "rilettura finale"
    settle()
    check("rilettura finale", final = true)
    guarded("il familiare legge") {
      for (m in bench.library.all().filter { it.place == MediaPlace.COMMON }) bench.other.library.put(m.copy(mine = false))
      bench.other.replica.recompute()
      bench.world.settle(1)
      for (album in bench.diary.allOps().filter { bench.me.shared.state(it.album) == SharedState.ACTIVE }.map { it.album }.distinct()) {
        for (media in bench.seenByOther(album)) {
          val m = bench.library[media] ?: continue
          val bytes = m.path?.let { bench.target.bytes(ShareNasTarget.COMMON, it) }
          val expected = bench.originals[media]
          if (m.place != MediaPlace.COMMON || bytes == null || expected == null || !bytes.contentEquals(expected)) {
            violations += Violation(ShareInvariant.NO_ENTRY_WITHOUT_FILE, "rilettura finale", "il familiare vede un elemento senza il suo file nell'area comune")
          }
        }
      }
    }
    verifier.reread()
    idleCheck("rilettura finale")
  }

  /** Una morte senza colpo e la ripresa a vuoto: a lavoro finito non cambia ne' il NAS ne' i diari ne' il catalogo (invariante 11). */
  private suspend fun idleCheck(phase: String) {
    val before = verifier.fingerprint()
    stats.idleRestarts++
    restartProcess()
    guarded("ripresa a vuoto") { bench.round() }
    val after = verifier.fingerprint()
    if (before != after) violations += Violation(ShareInvariant.IDLE, phase, "una morte senza colpo e una ripresa a vuoto hanno cambiato il NAS, il diario o il catalogo")
    check(phase, final = true)
  }

  private companion object {
    const val MAX_TAPS = 6
    const val PC_BASE = 60_000
  }
}
