package dev.pampa.mycloudgallery.backup

import dev.pampa.mycloudgallery.core.backup.contract.BackupClock
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpace
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpacePlan
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRun
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRunId
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRunState
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmation
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.backup.free.FreeSpaceDriver
import dev.pampa.mycloudgallery.feature.backup.free.FreeSpaceRuns
import dev.pampa.mycloudgallery.notify.FreeSpaceNotices
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Correzioni 5B (20/10; `docs/review/funzionale-5b.md` LS-2, LS-8, LS-10, LS-11): **il giro di "Libera spazio" del processo**, la porta
 * `FreeSpaceRuns` della pagina. Prima la verifica sul momento viveva nel `viewModelScope` ("Lascia aperta questa pagina"): usciti dalla pagina, o
 * con lo schermo spento e l'app fermata da Android, ricominciava da capo; non c'era la notifica "Pronto" ne' la ripresa. Adesso:
 * - il giro va avanti nello scope del grafo ([scope]), un solo conduttore alla volta ([FreeSpaceDriver]), anche a pagina chiusa;
 * - il tocco di conferma (e la pagina che ritrova un giro a meta') chiede il **lavoro avviato dall'utente** ([startWork]: `BackupJobService` con
 *   un id suo, UIDT su Android 14+, con la sua notifica); il lavoro **ospita** il giro ([host]) e, se il processo muore, Android lo rilancia e
 *   il giro si ritrova dal diario ([active], `SafeFreeSpace.active`): nello stesso avvio e entro le 6 ore dall'inizio, altrimenti scade;
 * - a "pronto", con l'app non davanti all'utente, la notifica "Pronto: N elementi, X GB. Tocca per liberare" ([notices]); al ritorno l'app apre
 *   "Libera spazio" ([openRequest]). Il cestino si chiede sempre dalla pagina, con un tocco (il dialogo del sistema vuole un'activity viva).
 *
 * **Niente cambia nella sicurezza**: ogni passo e' del motore (`SafeFreeSpace`): conferma, guardiano, verifica sul momento, timbro, lotti.
 * Qui si decide solo **dove** gira e chi lo racconta.
 */
class FiveBFreeSpaceRuns(
  service: FreeSpace,
  private val scope: CoroutineScope,
  private val clock: BackupClock,
  active: suspend () -> FreeSpaceRun?,
  abandon: suspend (FreeSpaceRunId) -> FreeSpaceRun?,
  override val ready: StateFlow<Boolean>,
  override val waitingForBackup: StateFlow<Boolean>,
  private val reachNas: suspend () -> Unit,
  private val startWork: () -> Boolean,
  private val notices: FreeSpaceNotices,
  private val foreground: () -> Boolean,
  stepBudget: Duration = 20.seconds,
) : FreeSpaceRuns {

  private val nasKnown = MutableStateFlow(clock.nasNowMs() != null)

  override val nasTimeKnown: StateFlow<Boolean> = nasKnown.asStateFlow()

  /** Il lavoro avviato dall'utente sta ospitando il giro: non se ne chiede un altro (lo stesso id lo sostituirebbe). */
  @Volatile
  private var hosting = false

  /** Il giro che stava lavorando (guardiano o verifica): il suo passaggio a "pronto" si annuncia. */
  @Volatile
  private var working: FreeSpaceRunId? = null

  private val driver = FreeSpaceDriver(
    service = service,
    scope = scope,
    stepBudget = stepBudget,
    activeRun = active,
    abandonRun = abandon,
    ready = ready,
    nasTimeKnown = nasKnown,
    waitingForBackup = waitingForBackup,
    onDriving = ::driving,
    onChanged = ::changed,
  )

  override val current: StateFlow<FreeSpaceRun?> get() = driver.current

  /** L'app e' davanti all'utente (allora "Pronto" lo dice la pagina, non una notifica). */
  val appInForeground: Boolean get() = foreground()

  /** La selezione del giro di adesso: la pagina riaperta dalla notifica la ritrova (mai "tutto il sicuro" al posto di una scelta, LS-6). */
  @Volatile
  var selection: Set<MediaId>? = null
    private set

  private val openFlow = MutableStateFlow<FreeSpaceRunId?>(null)

  /** "Pronto" annunciato con la notifica: quando l'utente torna nell'app si apre "Libera spazio" (una volta per giro). */
  val openRequest: StateFlow<FreeSpaceRunId?> = openFlow.asStateFlow()

  fun consumeOpen(id: FreeSpaceRunId) {
    openFlow.compareAndSet(id, null)
  }

  override fun prepare() {
    nasKnown.value = clock.nasNowMs() != null
    if (nasKnown.value) return
    scope.launch {
      try {
        reachNas()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        GalleryLog.w(TAG) { "NAS non raggiunto per l'ora (${e.javaClass.simpleName})" }
      }
      nasKnown.value = clock.nasNowMs() != null
    }
  }

  override suspend fun resumeActive(): FreeSpaceRun? = driver.resumeActive()

  override fun start(plan: FreeSpacePlan, confirmation: PhoneConfirmation) {
    selection = plan.filter.selection
    openFlow.value = null
    driver.start(plan, confirmation)
  }

  override suspend fun cancel() = driver.cancel()

  override fun update(run: FreeSpaceRun) = driver.update(run)

  /** Il giro lavora a tappe: lo ospita il lavoro avviato dall'utente (chiesto con l'app davanti: dal tocco o dall'apertura della pagina). */
  private fun driving(run: FreeSpaceRun) {
    working = run.id
    if (!hosting && !startWork()) GalleryLog.d(TAG) { "verifica di Libera spazio senza lavoro dell'utente: va avanti finche' il processo vive" }
  }

  private fun changed(run: FreeSpaceRun) {
    when {
      run.state == FreeSpaceRunState.READY && working == run.id -> {
        working = null
        if (!foreground()) {
          notices.ready(run)
          openFlow.value = run.id
        }
      }
      run.state.terminal -> {
        if (working == run.id) working = null
        notices.clear()
        openFlow.compareAndSet(run.id, null)
      }
      else -> Unit
    }
  }

  /**
   * Il lavoro avviato dall'utente (`BackupJobService`) ospita il giro: lo ritrova (dopo la morte del processo, dal diario) e aspetta che guardiano
   * e verifica abbiano finito, raccontando ogni passo a [progress]. Restituisce il giro com'e' alla fine, o null se non c'e' niente da fare.
   */
  suspend fun host(progress: (FreeSpaceRun) -> Unit): FreeSpaceRun? {
    hosting = true
    try {
      val found = driver.resumeActive() ?: return null
      return driver.current.filterNotNull().onEach(progress).first { it.id != found.id || !it.isWorking }
    } finally {
      hosting = false
    }
  }

  private val FreeSpaceRun.isWorking: Boolean get() = state == FreeSpaceRunState.GUARDING || state == FreeSpaceRunState.VERIFYING

  private companion object {
    const val TAG = "BackupFreeSpace"
  }
}

/**
 * Senza backup (varianti senza NAS): "Libera spazio" non e' mai pronto. La pagina dice "non e' ancora disponibile" e non calcola niente; nessun
 * giro parte (fallisce chiuso).
 */
object NoFreeSpaceRuns : FreeSpaceRuns {
  override val ready: StateFlow<Boolean> = MutableStateFlow(false)
  override val nasTimeKnown: StateFlow<Boolean> = MutableStateFlow(true)
  override val waitingForBackup: StateFlow<Boolean> = MutableStateFlow(false)
  override val current: StateFlow<FreeSpaceRun?> = MutableStateFlow(null)

  override suspend fun resumeActive(): FreeSpaceRun? = null

  override fun start(plan: FreeSpacePlan, confirmation: PhoneConfirmation) = Unit

  override suspend fun cancel() = Unit

  override fun update(run: FreeSpaceRun) = Unit
}
