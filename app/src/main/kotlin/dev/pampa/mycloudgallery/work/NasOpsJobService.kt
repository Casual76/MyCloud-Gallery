package dev.pampa.mycloudgallery.work

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.nasops.contract.RunResult
import dev.pampa.mycloudgallery.core.nasops.contract.RunStop
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.nas.NasOrganizer
import dev.pampa.mycloudgallery.notify.AppNotifications
import dev.pampa.mycloudgallery.notify.NasOpsNotification
import dev.pampa.mycloudgallery.notify.NasOpsProgress
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Cio' che il servizio prende dal grafo (Hilt non inietta un `JobService` senza `@AndroidEntryPoint`: si chiede all'applicazione). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface NasOpsEntryPoint {
  fun gallery(): GalleryGraph

  fun notifications(): AppNotifications
}

/**
 * Come finisce un lavoro delle operazioni: tutto fatto, da riprovare con il ritardo di `JobScheduler` (NAS sparito, scritture ferme,
 * tempo finito), oppure da riprendere fra [Later.delayMs] (file aperti dal PC, E-N13).
 */
sealed interface NasOpsJobEnd {
  data object Done : NasOpsJobEnd

  data object Reschedule : NasOpsJobEnd

  data class Later(val delayMs: Long) : NasOpsJobEnd
}

/**
 * Il giro di un lavoro delle operazioni, senza Android (provato su JVM): riprende **dal diario** a tappe da [slice] finche' c'e' qualcosa
 * da fare. Un lavoro avviato dall'utente ([userInitiated], UIDT) continua finche' serve; uno normale fa una tappa sola (Android lo
 * ferma dopo 10 minuti) e si fa riprogrammare.
 *
 * @param resume `NasOrganizer.resumeOperations`: null = niente NAS o galleria ferma (l'azzeramento).
 * @param nextLocked fra quanto scade la prima attesa di un file aperto dal PC.
 */
class NasOpsJobRun(
  private val resume: suspend (Duration) -> RunResult?,
  private val nextLocked: suspend () -> Long?,
  private val userInitiated: Boolean,
  private val slice: Duration = SLICE,
  private val maxSlices: Int = MAX_SLICES,
) {
  suspend fun run(): NasOpsJobEnd {
    repeat(if (userInitiated) maxSlices else 1) {
      val result = resume(slice) ?: return NasOpsJobEnd.Done
      when (result.stoppedBy) {
        RunStop.COMPLETE -> return NasOpsJobEnd.Done
        RunStop.WAITING_LOCKED -> return NasOpsJobEnd.Later((nextLocked() ?: DEFAULT_LOCKED_DELAY_MS).coerceAtLeast(MIN_DELAY_MS))
        RunStop.NAS_UNREACHABLE, RunStop.BLOCKED -> return NasOpsJobEnd.Reschedule
        RunStop.BUDGET -> Unit
      }
    }
    return NasOpsJobEnd.Reschedule
  }

  companion object {
    /** Una tappa: come i lavori del NAS (D22). */
    val SLICE: Duration = 8.minutes

    /** Un lavoro dell'utente: al massimo 2 ore di tappe, poi si fa riprogrammare (UIDT resta tale). */
    const val MAX_SLICES: Int = 15
    private const val DEFAULT_LOCKED_DELAY_MS = 15L * 60 * 1000
    private const val MIN_DELAY_MS = 60_000L
  }
}

/**
 * Il `JobService` delle operazioni lunghe sui file del NAS avviate dall'utente (UIDT, `JobInfo.Builder.setUserInitiated(true)`,
 * Android 14+; `docs/fase4-progetto.md` D29, §1.4, §2.7; `docs/android.md` 1.3 punto 4; `docs/sicurezza.md` 4-bis). Una mossa di 2.000
 * file non si ferma se l'utente esce dall'app: c'e' una notifica di avanzamento (solo numeri) con "Ferma".
 *
 * **Cosa fa.** Solo `NasOperations.resumeAll` (tramite `NasOrganizer.resumeOperations`), a tappe ([NasOpsJobRun]): **riprende dal diario
 * locale** cio' che l'utente ha gia' confermato. Non crea mai un'operazione e non legge niente che la possa creare. Gira dentro
 * `GalleryGraph.runIfActive`: l'azzeramento lo ferma e lo aspetta (e toglie i lavori in coda, `NasOpsJobs.cancelAll`).
 *
 * **"Ferma"** dalla notifica: un broadcast immutabile sul pacchetto, ricevuto da un ricevitore registrato **mentre il lavoro gira** e
 * **non esportato** (`RECEIVER_NOT_EXPORTED`): nessun componente nuovo nel manifest, nessun'altra app lo puo' mandare. Ferma le
 * operazioni attive prima dell'elemento successivo (non distrugge niente). Lo "Stop" del Task Manager di Android (motivo
 * `STOP_REASON_USER`) vale lo stesso.
 *
 * **Sicurezza.** Nel manifest e' `exported="false"` e protetto da `BIND_JOB_SERVICE`: lo lega solo il sistema (`JobScheduler`).
 * `verifyReleaseManifest` e `tools/check.ps1` pretendono entrambe le cose per ogni `*JobService`.
 */
class NasOpsJobService : JobService() {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val running = ConcurrentHashMap<Int, Job>()
  private var stopReceiver: BroadcastReceiver? = null

  private fun entry(): NasOpsEntryPoint = EntryPointAccessors.fromApplication(applicationContext, NasOpsEntryPoint::class.java)

  override fun onStartJob(params: JobParameters): Boolean {
    val userInitiated = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && params.isUserInitiatedJob
    // Un lavoro dell'utente deve mostrare la sua notifica subito (Android lo chiede entro pochi secondi dall'avvio).
    if (userInitiated && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      setNotification(params, AppNotifications.ID_NAS_OPS, NasOpsNotification.build(this, NasOpsProgress.NONE), JOB_END_NOTIFICATION_POLICY_REMOVE)
    }
    val gallery = runCatching { entry().gallery() }.getOrNull()
    val organizer = gallery?.nas?.organizer ?: return false
    if (!gallery.backgroundWorkAllowed) return false
    registerStop(organizer)
    running[params.jobId] = scope.launch {
      val notifier = launch { followProgress(organizer, userInitiated) }
      val end = try {
        NasOpsJobRun(
          resume = { budget -> organizer.resumeOperations(budget) },
          nextLocked = { organizer.nextLockedRetryMs() },
          userInitiated = userInitiated,
        ).run()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        GalleryLog.w(TAG) { "operazioni non riprese: ${e.javaClass.simpleName}" }
        NasOpsJobEnd.Reschedule
      } finally {
        notifier.cancel()
        if (!userInitiated) NotificationManagerCompat.from(this@NasOpsJobService).cancel(AppNotifications.ID_NAS_OPS)
      }
      GalleryLog.d(TAG) { "lavoro delle operazioni finito: ${end.javaClass.simpleName}" }
      if (end is NasOpsJobEnd.Later) JobSchedulerNasOpsJobs(this@NasOpsJobService).resumeLater(end.delayMs)
      running.remove(params.jobId)
      jobFinished(params, end is NasOpsJobEnd.Reschedule)
    }
    return true
  }

  override fun onStopJob(params: JobParameters): Boolean {
    running.remove(params.jobId)?.cancel()
    // "Stop" dal Task Manager di Android: l'utente ha detto di fermarsi, come "Ferma" (nessuna ripresa automatica).
    val byUser = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && params.stopReason == JobParameters.STOP_REASON_USER
    if (byUser) {
      // R9: l'annullamento nello scope della galleria: il servizio sta per essere distrutto e con lui il suo scope.
      runCatching { entry().gallery().nas?.organizer }.getOrNull()?.stopActiveFromService()
      return false
    }
    return true
  }

  override fun onDestroy() {
    stopReceiver?.let { runCatching { unregisterReceiver(it) } }
    stopReceiver = null
    scope.cancel()
    super.onDestroy()
  }

  /** La notifica segue lo stato delle operazioni (solo numeri). Prima di Android 14 la posta il servizio, se si puo' notificare. */
  private suspend fun followProgress(organizer: NasOrganizer, userInitiated: Boolean) {
    val notifications = entry().notifications()
    organizer.operations.operations.map { NasOpsProgress.of(it) }.distinctUntilChanged().collect { progress ->
      if (!notifications.canNotify()) return@collect
      if (!userInitiated && progress.active == 0) return@collect
      try {
        NotificationManagerCompat.from(this).notify(AppNotifications.ID_NAS_OPS, NasOpsNotification.build(this, progress))
      } catch (e: SecurityException) {
        GalleryLog.w(TAG) { "notifica delle operazioni non consentita" }
      }
    }
  }

  /** "Ferma" dalla notifica: un ricevitore non esportato, vivo solo mentre il servizio lavora. */
  private fun registerStop(organizer: NasOrganizer) {
    if (stopReceiver != null) return
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NasOpsNotification.stopAction(context)) return
        try {
          NotificationManagerCompat.from(context).notify(AppNotifications.ID_NAS_OPS, NasOpsNotification.build(context, NasOpsProgress.NONE, stopping = true))
        } catch (e: SecurityException) {
          // Senza permesso delle notifiche non c'era nemmeno il tasto.
        }
        // R9: nello scope della galleria (vive piu' del servizio); la richiesta si scrive subito nel diario.
        organizer.stopActiveFromService()
      }
    }
    ContextCompat.registerReceiver(this, receiver, IntentFilter(NasOpsNotification.stopAction(this)), ContextCompat.RECEIVER_NOT_EXPORTED)
    stopReceiver = receiver
  }

  private companion object {
    const val TAG = "NasOpsJobService"
  }
}
