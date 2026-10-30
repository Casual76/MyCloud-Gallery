package dev.pampa.mycloudgallery.faces

import android.content.ComponentCallbacks2
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.common.perf.InstantShapes
import dev.pampa.mycloudgallery.core.faces.contract.EraseStep
import dev.pampa.mycloudgallery.core.faces.contract.FacesRunResult
import dev.pampa.mycloudgallery.core.faces.contract.FacesScope
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesSyncReport
import dev.pampa.mycloudgallery.core.faces.contract.FlushReason
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.core.faces.contract.EraseReport
import dev.pampa.mycloudgallery.core.faces.contract.EraseWait
import dev.pampa.mycloudgallery.core.faces.erase.JanitorReport
import dev.pampa.mycloudgallery.core.rules.run.StageScope
import dev.pampa.mycloudgallery.feature.people.list.PEOPLE_INSTANT_SHAPE_CODE
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * IL MONTAGGIO DI UNA COSA SOLA PER PROCESSO (le persone, [FacesGraph]) e il suo stop (Fase 8, P13; la stessa regola degli album, Fase 7 I11): lo stop
 * prende lo stesso lucchetto del montaggio, quindi lo aspetta, e un montaggio che si accorge dello stop butta cio' che ha costruito.
 */
internal class FacesMount<T : Any>(
  private val scope: CoroutineScope,
  private val build: suspend (CoroutineScope) -> T?,
  private val onMounted: (T) -> Unit,
) {
  private val state = MutableStateFlow<T?>(null)
  private val building = Mutex()
  private val stopped = AtomicBoolean(false)

  val flow: StateFlow<T?> = state.asStateFlow()

  val current: T? get() = state.value

  /** Vero se un montaggio e' stato tentato e non e' riuscito (per non tenere fermo il resto dell'app per sempre). */
  @Volatile var failed: Boolean = false
    private set

  suspend fun ensure(): T? {
    state.value?.let { return it }
    if (stopped.get()) return null
    return building.withLock {
      state.value ?: run {
        if (stopped.get()) return@withLock null
        val mountScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        val built = try {
          build(mountScope)
        } catch (e: CancellationException) {
          mountScope.cancel()
          throw e
        } catch (e: Exception) {
          failed = true
          GalleryLog.w(TAG) { "persone non montate: ${e.javaClass.simpleName}" }
          null
        }
        if (built == null || stopped.get()) {
          mountScope.cancel()
          return@withLock null
        }
        state.value = built
        onMounted(built)
        built
      }
    }
  }

  suspend fun stop() {
    stopped.set(true)
    building.withLock { state.value = null }
  }

  private companion object {
    const val TAG = "Faces"
  }
}

/**
 * LE PERSONE NELL'APP (Fase 8, P13; docs/fase8-progetto.md §1.4). Nasce con il processo e monta [FacesLive] quando la famiglia e' pronta (galleria
 * partita e dati personali agganciati: l'interruttore ha bisogno del registro). Prima del montaggio nessuna classe dei motori dei volti si carica e
 * nessun archivio si apre (si apre solo con lo stato `On` con `ACTIVE` o `LOCAL_ONLY`, mai prima dell'avviso).
 *
 * I giri lunghi li fanno i lavori, con le regole delle attivita': [syncRound] nel giro dell'indice **dopo** i dati personali (cosi' la generazione e'
 * fresca), [computeStage] per il calcolo (`AI_COMPUTE`, tappe da 8 minuti), [nightly] per il raggruppamento di notte, [eraseRound] per riprendere "Disattiva
 * e cancella" dal diario. [stop] e' il **primo** passo dell'azzeramento (§1.4): motori rilasciati, calcolo fermo, niente si apre piu'.
 * Mai nomi, id, generazioni o conteggi per persona nei log (D37): solo numeri e codici.
 */
class FacesGraph internal constructor(
  parent: CoroutineScope,
  build: suspend (CoroutineScope) -> FacesLive?,
  private val ready: StateFlow<Boolean>,
  /** Dopo un cambio di stato che richiede un lavoro (calcolo da fare, cancellazione da riprendere): il lavoro di WorkManager. */
  private val workRequest: (FacesWork) -> Unit = {},
  /** Il codice dell'ultimo giro dei dati personali che il gancio dei volti ha fermato (D30), o null: per la Diagnostica. */
  private val holdSource: () -> String? = { null },
) {
  /** Cosa chiede un lavoro di WorkManager. */
  enum class FacesWork { COMPUTE, ERASE }

  private val job = SupervisorJob(parent.coroutineContext[Job])
  private val scope = CoroutineScope(parent.coroutineContext + job)
  private val mount = FacesMount(scope, build) { live -> watch(live) }
  private val stageLock = Mutex()

  /** Le persone montate, una sola volta per processo: null finche' la famiglia non e' pronta, e dopo [stop]. */
  val live: StateFlow<FacesLive?> get() = mount.flow

  /** Il montaggio e' fallito (un bug): il resto dell'app non lo aspetta. */
  val mountFailed: Boolean get() = mount.failed

  /** L'ultimo giro della replica (Diagnostica: solo numeri). */
  val lastSync = MutableStateFlow<FacesSyncReport?>(null)

  /** L'ultimo giro della pulizia del NAS (Diagnostica: solo numeri). */
  val lastJanitor = MutableStateFlow<JanitorReport?>(null)

  /** Gli errori isolati dei giri (non fermano niente, si contano). */
  @Volatile var roundErrors: Int = 0
    private set

  /** I byte letti dal NAS dall'ultimo [syncRound] (il conto del traffico del lavoro dell'indice). */
  @Volatile var lastRoundBytes: Long = 0
    private set

  /** Persone e' attiva su questo dispositivo adesso (accesa, non esclusa, avviso visto): solo allora servono i modelli dei volti. */
  fun activeHere(): Boolean = mount.current?.switch?.currentState()?.activeHere == true

  /** Il codice dell'ultimo giro dei dati personali fermato dal gancio dei volti, o null. */
  fun holdCode(): String? = holdSource()

  /** Parte (idempotente): appena la famiglia e' pronta monta le persone. */
  fun start() {
    scope.launch { ready.collect { if (it) ensureLive() } }
  }

  suspend fun ensureLive(): FacesLive? = mount.ensure()

  internal fun noteError(e: Exception) {
    roundErrors++
    GalleryLog.w(TAG) { "giro delle persone: ${e.javaClass.simpleName}" }
  }

  /** Chiede il lavoro che serve ai dati di adesso (calcolo da fare, cancellazione in corso). */
  private fun watch(live: FacesLive) {
    live.scope.launch {
      guarded { live.afterMount() }
      // R11: la forma ricordata della pagina Persone non sopravvive a dati che non ci sono piu' (spenta qui).
      if (live.switch.currentState() is FacesState.Off) InstantShapes.remove(PEOPLE_INSTANT_SHAPE_CODE)
      // Una cancellazione lasciata a meta' si riprende subito (diario), non alla notte.
      if (live.eraser.status.value?.let { it.step != EraseStep.DONE } == true) workRequest(FacesWork.ERASE)
    }
    live.scope.launch {
      var before: FacesState? = null
      live.switch.state.collect { state ->
        val last = before
        before = state
        if (last != null && state != last) onStateChanged(live, last, state)
      }
    }
  }

  private fun onStateChanged(live: FacesLive, from: FacesState, to: FacesState) {
    // Persone si accende qui (o torna attiva): il primo calcolo parte dal lavoro, senza aspettare il giro delle 6 ore.
    if (to is FacesState.On && to.here != HereState.EXCLUDED && (from !is FacesState.On || from.here == HereState.EXCLUDED)) workRequest(FacesWork.COMPUTE)
    if (to is FacesState.Erasing) workRequest(FacesWork.ERASE)
    // R11: senza i dati dei volti qui (cancellati, esclusi, avviso da rivedere) la forma ricordata della pagina Persone si toglie.
    if (!to.activeHere) InstantShapes.remove(PEOPLE_INSTANT_SHAPE_CODE)
    // Le persone cambiano con lo stato: ricerca e album smart le rivalutano (E-F37).
    live.statusChanged()
  }

  // --- I giri dei lavori -------------------------------------------------------------------------------------------------------------------

  /**
   * Il giro dei volti nel giro dell'indice, **dopo** i dati personali (D30: la generazione e' fresca): replica e pubblicazione, poi la pulizia del NAS
   * (generazioni non correnti, dispositivi revocati). Vero se il giro e' finito. Senza persone attive qui: niente da fare.
   */
  suspend fun syncRound(budget: Duration): Boolean {
    lastRoundBytes = 0
    val live = ensureLive() ?: return true
    // L'area privata dichiarata e la recinzione si rileggono a ogni giro (costano poco): un cambio di profilo non aspetta il riavvio.
    guarded { live.switch.refreshRequirements() }
    val state = live.switch.currentState()
    if (state is FacesState.Erasing) {
      guarded { live.eraser.resume(budget) }
      return true
    }
    val janitor = guarded { live.janitor.run(budget) }
    if (janitor != null) lastJanitor.value = janitor
    // CE-2 (correzioni A): "attesa dei dispositivi", cestino di rete e fine si riprendono qui, in qualunque stato (dopo la parte locale lo stato e' `Off`, non piu'
    // `Erasing`): `eraseRound` lascia questi motivi ai giri dell'indice (P13-7) e senza questo passo nessuno rileggeva le conferme fino al prossimo processo.
    if (live.eraser.status.value != null) guarded { live.eraser.resume(budget) }
    if (state !is FacesState.On || state.here != HereState.ACTIVE) return true
    val report = guarded { live.sync.sync(budget) }
    if (report != null) {
      lastSync.value = report
      lastRoundBytes = report.bytesRead
      // Dati nuovi dagli altri dispositivi (volti, nomi, istantanee del custode): chi non e' custode adotta l'istantanea, il custode assegna i volti arrivati.
      // Senza questo passo un dispositivo che non calcola vedrebbe le persone solo alla notte (F96: «ogni azione si vede sull'altro dispositivo dopo un giro»).
      if (report.headsAccepted > 0 || report.bytesRead > 0) guarded { live.grouping.incremental(budget) }
      live.dataArrived()
    }
    guarded { live.publisher.flush(FlushReason.SYNC_ROUND) }
    return report == null || report.stop != dev.pampa.mycloudgallery.core.faces.contract.FacesSyncStop.BUDGET
  }

  /** Una tappa del calcolo (`AI_COMPUTE`): tutti gli ambiti di questo dispositivo in ordine, con le regole della tappa. Null senza persone attive qui. */
  suspend fun computeStage(scope: StageScope<*>, budget: Duration = Duration.INFINITE): List<Pair<FacesScope, FacesRunResult>>? {
    val live = ensureLive() ?: return null
    val state = live.switch.currentState()
    if (state !is FacesState.On || state.here == HereState.EXCLUDED) return null
    // Una tappa alla volta: le regole sono quelle della tappa che ha il cancello (`StageFacesControl`), e il motore non fa due corse insieme. Una seconda tappa
    // (per esempio «Analizza ora» mentre gira quella notturna) non aspetta: la prima continua e non c'e' altro da fare qui.
    if (!stageLock.tryLock()) return null
    return try {
      live.runControl.bind(scope) { live.compute.runAll(budget) }.also { live.dataArrived() }
    } finally {
      // Fine tappa: i motori si rilasciano (la memoria dei volti non resta nel processo, §2.2 "Rilascio").
      try {
        live.releaseEngines()
      } finally {
        stageLock.unlock()
      }
    }
  }

  /** La notte (in carica): il raggruppamento del custode e la manutenzione. */
  suspend fun nightly(budget: Duration) {
    val live = ensureLive() ?: return
    val state = live.switch.currentState()
    if (state !is FacesState.On || state.here == HereState.EXCLUDED) return
    guarded { live.grouping.nightly(budget) }
    guarded { live.publisher.flush(FlushReason.MAINTENANCE_FINISHED) }
    live.dataArrived()
  }

  /**
   * Riprende "Disattiva e cancella" dal diario. Risponde fra quanto riprovare, o null se non serve (finita, o in attesa di una cosa che il lavoro non
   * accelera: gli altri dispositivi, il cestino di rete; la riprendono i giri dell'indice).
   */
  suspend fun eraseRound(budget: Duration): Duration? {
    val live = ensureLive() ?: return null
    val report: EraseReport = guarded { live.eraser.resume(budget) } ?: return ERASE_SOON
    // R13: i giri di fila fermi su un file tenuto aperto dal NAS allungano l'attesa; ogni altro esito la riparte da capo.
    heldRounds = if (report.waiting == EraseWait.FILE_OPEN) heldRounds + 1 else 0
    return eraseDelay(report, heldRounds)
  }

  /** Quanti giri di fila hanno aspettato un file tenuto aperto sul NAS (R13): per allungare l'attesa. Per processo. */
  @Volatile private var heldRounds: Int = 0

  /** La pulizia dei propri file dei volti sul NAS (con la manutenzione dell'indice). */
  suspend fun maintain(budget: Duration) {
    val live = ensureLive() ?: return
    guarded { live.cleanOwnFiles(budget) }
  }

  /**
   * `onTrimMemory` (§2.2 "Rilascio"): rilevatore ed embedder si rilasciano quando il sistema ha davvero poca memoria (come il motore delle immagini, IX-5 della
   * Fase 6: in primo piano `RUNNING_LOW` e `RUNNING_CRITICAL`, in cache `MODERATE` e `COMPLETE`; non per il solo passaggio in secondo piano, che e' dove il
   * calcolo notturno gira) e si riaprono da soli al prossimo bisogno; le copertine in memoria si buttano appena l'app non si vede piu'.
   */
  fun onTrimMemory(level: Int) {
    val live = mount.current ?: return
    if (releasesEnginesAt(level)) live.scope.launch { runCatching { live.releaseEngines() } }
    if (dropsCoversAt(level)) live.crops.clear()
  }

  // --- Fine -----------------------------------------------------------------------------------------------------------------------------

  /** L'azzeramento (§1.4): per primo. Motori rilasciati, calcolo fermo, niente si apre piu'; poi i file li toglie la cancellazione locale. */
  suspend fun stop() {
    val live = mount.current
    live?.stopping()
    mount.stop()
    job.cancelAndJoin()
    live?.releaseEnginesQuietly()
    // Le chiavi per prime, poi i database (anche quelli di generazioni che questo oggetto non ha mai aperto): E-F35. Il resto lo tolgono i file dell'azzeramento.
    runCatching { live?.stores?.eraseEverything() }
  }

  private suspend fun <T> guarded(block: suspend () -> T): T? = try {
    block()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    noteError(e)
    null
  }

  internal companion object {
    private const val TAG = "Faces"

    /** A questo livello di `onTrimMemory` rilevatore ed embedder si rilasciano (la stessa regola del motore delle immagini, IX-5). */
    fun releasesEnginesAt(level: Int): Boolean =
      level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
        level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE

    /** A questo livello le copertine ritagliate in memoria (8 MB al piu') si buttano: l'app non si vede piu', o la memoria scarseggia. */
    fun dropsCoversAt(level: Int): Boolean = level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN || releasesEnginesAt(level)
    val START_BUDGET: Duration = 10.seconds

    /** La cancellazione riprende presto se avanza, piu' tardi se aspetta il NAS o un cancello (E-F30). */
    val ERASE_SOON: Duration = 1.minutes
    val ERASE_LATER: Duration = 30.minutes

    /**
     * Fra quanto riprende la cancellazione dopo un giro (null = non serve il lavoro: finita, o in attesa di una cosa che riprendono i giri dell'indice, CE-2).
     * R13: con un file tenuto aperto dal NAS l'attesa cresce a ogni giro di fila ([heldRounds]): 2, 4, 8, 16 minuti, poi [ERASE_LATER]; mai un giro al minuto
     * senza motivo.
     */
    fun eraseDelay(report: EraseReport, heldRounds: Int): Duration? = when {
      report.done -> null
      report.waiting == null -> ERASE_SOON
      report.waiting == EraseWait.DEVICES || report.waiting == EraseWait.RECYCLE_BIN -> null
      report.waiting == EraseWait.FILE_OPEN -> heldDelay(heldRounds)
      else -> ERASE_LATER
    }

    /** L'attesa dopo [rounds] giri di fila fermi su un file aperto (1 = il primo): raddoppia da 2 minuti fino a [ERASE_LATER]. */
    fun heldDelay(rounds: Int): Duration = ERASE_SOON.times(1 shl rounds.coerceIn(1, 5)).coerceAtMost(ERASE_LATER)
  }
}
