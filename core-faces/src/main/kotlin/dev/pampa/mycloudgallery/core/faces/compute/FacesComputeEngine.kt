package dev.pampa.mycloudgallery.core.faces.compute

import dev.pampa.mycloudgallery.core.faces.contract.AreaKind
import dev.pampa.mycloudgallery.core.faces.contract.FacesArea
import dev.pampa.mycloudgallery.core.faces.contract.FacesComputeRun
import dev.pampa.mycloudgallery.core.faces.contract.FacesDiag
import dev.pampa.mycloudgallery.core.faces.contract.FacesRunResult
import dev.pampa.mycloudgallery.core.faces.contract.FacesScope
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesStatus
import dev.pampa.mycloudgallery.core.faces.contract.FacesStatusSource
import dev.pampa.mycloudgallery.core.faces.contract.FacesStop
import dev.pampa.mycloudgallery.core.faces.contract.FacesWait
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.core.faces.contract.PrivateAreaState
import dev.pampa.mycloudgallery.core.faces.store.FacesRecovery
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * IL CALCOLO DEI VOLTI (docs/fase8-progetto.md §2.3, D16-D19; E-F13-E-F19, E-A5, E-A6, E-A16, E-A17): i volti dell'account, calcolati **una
 * volta per account** dagli indicizzatori dell'utente sulle **anteprime** (mai sugli originali del NAS), a tappe e con le regole di
 * `AI_COMPUTE`, salvati **subito** per chiave di contenuto e accodati per la pubblicazione.
 *
 * Una tappa ([run]) lavora su un ambito:
 * - [FacesScope.Area]: **solo** l'area privata dichiarata di questo account o l'area comune (D11, D16). Un'altra area privata non si tocca
 *   mai: nessuna porta del NAS, del catalogo o dei motori viene chiamata (`foreignScopes` si conta). Le unita' sono prenotate **sotto la
 *   generazione**, nell'area privata, e dividono il lavoro fra gli indicizzatori dello stesso account;
 * - [FacesScope.PhoneOnly]: i media che sono solo sul telefono di questo indicizzatore, dai byte del telefono; risultati **solo locali**.
 *
 * Il cancello (stato `On(G, ACTIVE o LOCAL_ONLY)`, ruolo Indicizzatore, modelli verificati, generazione uguale a quella dei dati locali) si
 * riguarda prima di ogni lotto e lo stato prima di ogni media: una generazione che cambia ferma la tappa **senza salvare ne' pubblicare altro**
 * (§2.8). Con la recinzione (D34, `LOCAL_ONLY`) niente va sul NAS: nessuna prenotazione, nessuna pubblicazione.
 *
 * [status] e' lo stato per la pagina e la Diagnostica: solo numeri e codici (D37). Una tappa alla volta: un'altra chiamata aspetta.
 */
class FacesComputeEngine(
  private val parts: FacesComputeParts,
  private val config: FacesComputeConfig = FacesComputeConfig.DEFAULT,
  val counters: FacesComputeCounters = FacesComputeCounters(),
  /** La misura del lavoro vero: i ritmi con cui si stima quanto resta (`FacesEstimate`). */
  val rates: FacesRateMeter = FacesRateMeter(),
  /** La pausa fra due lotti col caldo (E-A5); nei test e' il tempo virtuale. */
  private val pause: suspend (Duration) -> Unit = { delay(it) },
) : FacesComputeRun, FacesStatusSource {

  private val state = MutableStateFlow<FacesStatus>(FacesStatus.Off)
  override val status: StateFlow<FacesStatus> get() = state

  private val memory = FacesMemory()
  private val mutex = Mutex()

  override suspend fun run(scope: FacesScope, budget: Duration): FacesRunResult = mutex.withLock { runLocked(scope, budget) }

  /**
   * Tutti gli ambiti di questo dispositivo in ordine (D16: prima l'area privata, poi l'area comune, poi i media del solo telefono), finche' il
   * tempo non finisce o le regole non fermano. Comodo per `FacesComputeWorker` e per i test.
   */
  suspend fun runAll(budget: Duration): List<Pair<FacesScope, FacesRunResult>> {
    val start = parts.clock.monotonicNanos()
    val private = (parts.requirements.privateArea() as? PrivateAreaState.Declared)?.share
    val common = parts.areas.commonShare()
    val scopes = listOfNotNull(
      private?.let { FacesScope.Area(it, AreaKind.PRIVATE) },
      common?.takeUnless { it.equals(private, ignoreCase = true) }?.let { FacesScope.Area(it, AreaKind.COMMON) },
      FacesScope.PhoneOnly,
    )
    val out = ArrayList<Pair<FacesScope, FacesRunResult>>()
    for (scope in scopes) {
      val left = budget - (parts.clock.monotonicNanos() - start).nanoseconds
      if (!left.isPositive()) break
      val result = run(scope, left)
      out += scope to result
      // Il NAS che non risponde non toglie i media del telefono (E-A6); ogni altro arresto ferma tutto.
      if (result.stop != FacesStop.DONE && result.stop != FacesStop.NO_NAS) break
    }
    return out
  }

  private suspend fun runLocked(scope: FacesScope, budget: Duration): FacesRunResult {
    val started = parts.switch.currentState()
    val generation = started.generationOrNull
    if (generation == null) return notRunning(FacesStop.NOT_ACTIVE, started)
    stateStop(parts, generation)?.let { return notRunning(it, started) }

    // Dove si lavora: l'area privata dichiarata (L23) e una sola area comune; un'altra area non si tocca (D11, D14).
    val declared = parts.requirements.privateArea()
    if (declared !is PrivateAreaState.Declared) {
      // E-F10: senza un'area privata diversa dalla comune Persone non parte (e non c'e' dove tenere i dati).
      parts.signals.report(FacesDiag.FACES_NO_PRIVATE_AREA, 1)
      return notRunning(FacesStop.NOT_ACTIVE, started, FacesStatus.Problem(FacesDiag.FACES_NO_PRIVATE_AREA))
    }
    val area = areaOf(scope, declared.share)
    if (area == null) {
      counters.foreignScopes.incrementAndGet()
      return FacesRunResult.stopped(FacesStop.DONE)
    }
    if (!parts.requirements.indexers().thisDevice) return notRunning(FacesStop.NOT_INDEXER, started)
    if (!parts.modelsReady()) {
      counters.modelStops.incrementAndGet()
      parts.signals.report(FacesDiag.FACES_MODEL_MISMATCH, 1)
      return notRunning(FacesStop.MODELS_UNAVAILABLE, started)
    }

    val opened = parts.stores.open(generation)
    if (opened !is ComputeStoreOpen.Ready) return notRunning(FacesStop.STORE_UNAVAILABLE, started)
    when (opened.recovery) {
      FacesRecovery.KEY_LOST -> {
        // E-F19: i dati locali non si leggevano piu' e sono stati rifatti da capo. Il calcolo riparte da zero (o dalla replica del NAS).
        counters.keyLost.incrementAndGet()
        parts.signals.report(FacesDiag.FACES_KEY_LOST, 1)
      }
      FacesRecovery.DATABASE_DAMAGED -> counters.databaseRebuilt.incrementAndGet()
      null -> Unit
    }

    val ctx = RunContext(parts, config, counters, memory, rates, generation, opened.store, budget, pause, state)
    val engines = EngineHolder(ctx)
    val runner = UnitRunner(ctx, engines)
    val invalidBefore = counters.vectorsInvalid.get()
    var stop: FacesStop = FacesStop.DONE
    try {
      stop = ctx.gate() ?: runUnits(ctx, runner, area)
      // Cio' che aspetta in coda (un'altra tappa, un NAS che non rispondeva) ha un'altra occasione a fine tappa; mai dopo uno stop che toglie il diritto.
      // Un errore del database in questa pubblicazione (O1) ferma la tappa con il suo codice solo se non c'e' un altro motivo (tempo, regole...) che gia' la ferma.
      if (area != FacesArea.PHONE_ONLY && !ctx.untouchable(stop) && stateStop(parts, generation) == null) {
        runner.flushPending()?.let { if (stop == FacesStop.DONE) stop = it }
      }
      // Il NAS che non ha risposto non e' "finito": fatto cio' che c'era in locale (E-A6).
      if (stop == FacesStop.DONE && ctx.nasDown) stop = FacesStop.NO_NAS
    } catch (e: ArchiveClosed) {
      // La generazione e' cambiata o la chiave e' distrutta a meta': niente piu' salvato, niente piu' pubblicato (§2.8).
      stop = FacesStop.GENERATION_CHANGED
    } finally {
      withContext(NonCancellable) { engines.release() }
    }
    report(ctx, invalidBefore)
    state.value = statusFor(stop, ctx.tally.waiting)
    return ctx.tally.result(stop)
  }

  private suspend fun runUnits(ctx: RunContext, runner: UnitRunner, area: FacesArea): FacesStop {
    val units = FacesUnits(parts.catalog, config.unitSize, config.recipe)
    val seen = HashSet<UnitId>()
    var stop: FacesStop? = null
    for (round in 0 until config.maxUnitsPerRun) {
      val unit = nextUnit(units, area, ctx, seen) ?: break
      // Le regole si chiedono una volta per lotto, e prima di prendere un'unita': la prima volta l'ha gia' chiesta l'inizio della tappa.
      if (round > 0) stop = ctx.gate()
      if (stop != null) break
      seen += unit.id
      ctx.tally.skipped += unit.excluded
      counters.excluded.addAndGet(unit.excluded)
      val end = runner.run(unit)
      if (end.stop != null) {
        stop = end.stop
        break
      }
    }
    return stop ?: FacesStop.DONE
  }

  /** La prossima unita' con qualcosa da fare; un archivio chiuso a meta' (generazione cambiata) ferma la tappa. */
  private suspend fun nextUnit(units: FacesUnits, area: FacesArea, ctx: RunContext, seen: Set<UnitId>): PlannedUnit? = try {
    units.next(area, ctx.store, seen, memory)
  } catch (e: CancellationException) {
    throw e
  } catch (e: IllegalStateException) {
    throw ArchiveClosed()
  }

  /** L'area di [scope], o null se non e' un'area di questo account (D11, D16): l'area privata dichiarata o l'area comune del registro. */
  private suspend fun areaOf(scope: FacesScope, privateShare: String): FacesArea? = when (scope) {
    FacesScope.PhoneOnly -> FacesArea.PHONE_ONLY
    is FacesScope.Area -> when (scope.kind) {
      AreaKind.PRIVATE -> FacesArea.PRIVATE.takeIf { same(scope.share, privateShare) }
      AreaKind.COMMON -> {
        val common = parts.areas.commonShare()
        FacesArea.COMMON.takeIf { common != null && same(scope.share, common) && !same(common, privateShare) }
      }
    }
  }

  /** I nomi delle share si confrontano senza maiuscole (Samba, I13). */
  private fun same(a: String, b: String): Boolean = a.lowercase(Locale.ROOT) == b.lowercase(Locale.ROOT)

  /** Il resoconto di una tappa che non e' partita: lo stato dice perche'. */
  private fun notRunning(stop: FacesStop, current: FacesState, because: FacesStatus? = null): FacesRunResult {
    state.value = because ?: if (stop == FacesStop.NOT_ACTIVE) inactiveStatus(current) else statusFor(stop, 0)
    return FacesRunResult.stopped(stop)
  }

  /** Perche' Persone non lavora qui, per la pagina (§1.2 `FacesStatus`). */
  private fun inactiveStatus(current: FacesState): FacesStatus = when (current) {
    FacesState.Off -> FacesStatus.Off
    is FacesState.AwaitingNotice -> FacesStatus.Waiting(FacesWait.NOTICE)
    is FacesState.On -> FacesStatus.Waiting(if (current.here == HereState.EXCLUDED) FacesWait.EXCLUDED else FacesWait.NOTICE)
    is FacesState.Erasing -> FacesStatus.Waiting(FacesWait.ERASING)
    FacesState.NeedsUpdate -> FacesStatus.Waiting(FacesWait.NEEDS_UPDATE)
  }

  private fun statusFor(stop: FacesStop, waiting: Int): FacesStatus = when (stop) {
    FacesStop.DONE -> if (waiting > 0) FacesStatus.Waiting(FacesWait.PREVIEWS) else FacesStatus.Idle(parts.clock.nasNowMillis())
    // La tappa e' finita per tempo, non per le regole: riprende fra poco.
    FacesStop.BUDGET -> FacesStatus.Waiting(FacesWait.NEXT_STAGE)
    FacesStop.RULES -> FacesStatus.Waiting(FacesWait.RULES)
    FacesStop.NOT_ACTIVE -> FacesStatus.Off
    FacesStop.NOT_INDEXER -> FacesStatus.Waiting(FacesWait.NOT_INDEXER)
    FacesStop.GENERATION_CHANGED -> FacesStatus.Off
    FacesStop.MODELS_UNAVAILABLE -> FacesStatus.Waiting(FacesWait.MODELS)
    FacesStop.ENGINE_FAILED -> FacesStatus.Problem(FacesDiag.FACES_ENGINE_FAILED)
    FacesStop.NO_NAS -> FacesStatus.Waiting(FacesWait.NAS)
    FacesStop.ERASING -> FacesStatus.Waiting(FacesWait.ERASING)
    FacesStop.STORAGE_LOW, FacesStop.STORE_UNAVAILABLE -> FacesStatus.Waiting(FacesWait.STORAGE)
  }

  /** I segnali una tantum della tappa (D37: solo codici e numeri). */
  private fun report(ctx: RunContext, invalidBefore: Int) {
    val invalid = counters.vectorsInvalid.get() - invalidBefore
    if (invalid > 0) parts.signals.report(FacesDiag.FACES_VECTOR_INVALID, invalid)
    if (ctx.tally.waiting > 0) parts.signals.report(FacesDiag.FACES_WAITING_PREVIEWS, ctx.tally.waiting)
  }
}
