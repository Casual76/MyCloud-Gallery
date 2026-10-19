package dev.pampa.mycloudgallery.work

import android.os.Build
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import dev.pampa.mycloudgallery.core.rules.AndroidLimits
import dev.pampa.mycloudgallery.core.rules.AndroidLimitsSignal
import dev.pampa.mycloudgallery.feature.diagnostics.recording.DiagJob
import dev.pampa.mycloudgallery.feature.diagnostics.recording.DiagStopCause
import dev.pampa.mycloudgallery.graph.AppGraph
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * I limiti di Android raggiunti, dai motivi di stop dei lavori (verifica funzionale, RE-7): prima il motore delle regole riceveva un
 * `NoAndroidLimits` che non diceva mai niente, quindi `WaitReason.AndroidLimit` era codice morto e la Diagnostica non spiegava perche'
 * i lavori si fermavano.
 *
 * Quando WorkManager ferma un lavoro per la **quota dei job** (`STOP_REASON_QUOTA`) o perche' il bucket dell'app e' sceso o lo sfondo e'
 * limitato, la quota dei job risulta esaurita per [cooldown]: in quel tempo le regole dicono «limite di Android raggiunto» invece di
 * far partire lavori che Android fermerebbe subito. Si toglie da sola, e quando l'app torna in primo piano (la quota non vale per
 * un'app visibile) o si collega la carica non serve altro che un nuovo giro. I budget dei servizi in primo piano (`dataSync`,
 * `mediaProcessing`) restano spenti: l'app non usa servizi in primo piano di quei tipi (il lavoro lungo dell'utente e' un UIDT).
 */
class AndroidLimitsTracker(
  private val scope: CoroutineScope,
  private val cooldown: Duration = COOLDOWN,
) : AndroidLimitsSignal {

  private val state = MutableStateFlow(AndroidLimits())
  override val limits: StateFlow<AndroidLimits> = state.asStateFlow()

  private var expiry: Job? = null

  /** Un lavoro e' stato fermato da Android per [cause]. */
  @Synchronized
  fun noteStop(cause: DiagStopCause) {
    if (cause != DiagStopCause.QUOTA && cause != DiagStopCause.RESTRICTED) return
    state.update { it.copy(jobQuotaExhausted = true) }
    expiry?.cancel()
    expiry = scope.launch {
      delay(cooldown)
      state.update { it.copy(jobQuotaExhausted = false) }
    }
  }

  /** L'app e' tornata in primo piano: per un'app visibile la quota non vale. */
  @Synchronized
  fun clear() {
    expiry?.cancel()
    expiry = null
    state.update { it.copy(jobQuotaExhausted = false) }
  }

  companion object {
    /** Per quanto, dopo uno stop per quota, i lavori in background aspettano: sotto la finestra piu' corta dei bucket (4 ore, Working set). */
    val COOLDOWN: Duration = 1.hours
  }
}

/**
 * Il motivo per cui Android ha fermato un lavoro (`WorkInfo.STOP_REASON_*`, che prima non si salvava) nell'anello della Diagnostica e,
 * se e' un limite di Android, nei limiti delle regole ([AndroidLimitsTracker]).
 */
internal object StopReports {

  /** I motivi di Android nelle parole fisse della Diagnostica. I valori di `JobParameters.STOP_REASON_*` sono gli stessi. */
  fun causeOf(stopReason: Int): DiagStopCause = when (stopReason) {
    WorkInfo.STOP_REASON_QUOTA -> DiagStopCause.QUOTA
    WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION, WorkInfo.STOP_REASON_APP_STANDBY -> DiagStopCause.RESTRICTED
    WorkInfo.STOP_REASON_TIMEOUT -> DiagStopCause.TIMEOUT
    WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW,
    WorkInfo.STOP_REASON_CONSTRAINT_CHARGING,
    WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY,
    WorkInfo.STOP_REASON_CONSTRAINT_DEVICE_IDLE,
    WorkInfo.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW,
    -> DiagStopCause.CONSTRAINT
    WorkInfo.STOP_REASON_USER -> DiagStopCause.USER
    else -> DiagStopCause.SYSTEM
  }

  /**
   * Il motivo dello stop di un lavoro: `getStopReason` esiste da Android 12 (API 31); sotto non si sa, e non si registra niente
   * ([WorkInfo.STOP_REASON_NOT_STOPPED]) invece di dare la colpa al sistema per ogni annullamento.
   */
  fun reasonOf(worker: ListenableWorker): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) worker.stopReason else WorkInfo.STOP_REASON_NOT_STOPPED

  /**
   * Un lavoro e' stato fermato. [stopReason] e' quello di WorkManager; `STOP_REASON_NOT_STOPPED` vuol dire che l'annullamento non e' di
   * Android (per esempio l'azzeramento): non si registra.
   */
  fun report(graph: AppGraph, job: DiagJob, stopReason: Int) {
    if (stopReason == WorkInfo.STOP_REASON_NOT_STOPPED) return
    val cause = causeOf(stopReason)
    graph.events.jobStopped(job, cause)
    graph.androidLimits.noteStop(cause)
  }
}
