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
import dev.pampa.mycloudgallery.backup.BackupGraph
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.core.backup.contract.StopBy
import dev.pampa.mycloudgallery.core.backup.upload.BackupStageOutcome
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.graph.AppGraph
import dev.pampa.mycloudgallery.notify.AppNotifications
import dev.pampa.mycloudgallery.notify.BackupNotification
import dev.pampa.mycloudgallery.notify.BackupProgress
import java.util.concurrent.ConcurrentHashMap
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
interface BackupEntryPoint {
  fun gallery(): GalleryGraph

  fun graph(): AppGraph

  fun notifications(): AppNotifications
}

/** Come finisce un lavoro del backup avviato dall'utente: tutto fatto (o niente da fare), oppure da riprendere con il ritardo di `JobScheduler`. */
enum class BackupJobEnd { DONE, RESCHEDULE }

/**
 * Il giro di un lavoro del backup, senza Android (provato su JVM): prima le **foto**, poi i **video** (D11), ognuno a tappe finche' c'e'
 * altro da fare. Un lavoro avviato dall'utente ([userInitiated], UIDT) continua finche' serve ([maxSlices] tappe); uno normale fa una
 * tappa sola per tipo (Android lo ferma dopo 10 minuti) e si fa riprogrammare.
 *
 * @param stage una tappa del tipo dato; null = non parte (niente NAS, galleria ferma, cartella non scelta).
 */
class BackupJobRun(
  private val stage: suspend (BackupKind) -> BackupStageOutcome?,
  private val userInitiated: Boolean,
  private val maxSlices: Int = MAX_SLICES,
) {
  suspend fun run(): BackupJobEnd {
    for (kind in KINDS) {
      var slices = if (userInitiated) maxSlices else 1
      var more = true
      while (more) {
        if (slices-- <= 0) return BackupJobEnd.RESCHEDULE
        val outcome = stage(kind) ?: return BackupJobEnd.DONE
        // "Ferma", cancello chiuso o permessi del telefono: niente da riprovare, nemmeno con l'altro tipo di media.
        when (outcome.last?.stoppedBy) {
          RunStop.STOPPED, RunStop.GATE, RunStop.PHONE -> return BackupJobEnd.DONE
          else -> Unit
        }
        when (BackupFollowUp.of(outcome)) {
          BackupFollowUp.Continue -> Unit
          BackupFollowUp.Retry -> return BackupJobEnd.RESCHEDULE
          // Le regole non lasciano partire o serve la conferma dell'utente: lo dice la pagina, non un lavoro che ritenta.
          is BackupFollowUp.Wait -> return BackupJobEnd.DONE
          BackupFollowUp.None -> more = false
        }
      }
    }
    return BackupJobEnd.DONE
  }

  companion object {
    /** Foto prima dei video (D11). */
    val KINDS: List<BackupKind> = listOf(BackupKind.PHOTOS, BackupKind.VIDEOS)

    /** Un lavoro dell'utente: al massimo 2 ore di tappe, poi si fa riprogrammare (UIDT resta tale). */
    const val MAX_SLICES: Int = 15
  }
}

/**
 * Il `JobService` del **backup avviato dall'utente** ("Inizia ora" del primo backup, "Fai ora"): un lavoro avviato dall'utente (UIDT,
 * `JobInfo.Builder.setUserInitiated(true)`, Android 14+; docs/fase5-progetto.md D11, §2.8; `docs/android.md` 1.3 punto 4). Il primo backup
 * dura ore e non si ferma se l'utente esce dall'app: c'e' una notifica di avanzamento (solo numeri) con "Ferma".
 *
 * **Cosa fa.** Solo le tappe del backup ([BackupStages.round] con il trigger scelto dal tocco). Non crea elementi del diario che non
 * nascano dal catalogo locale. Gira dentro `GalleryGraph.runIfActive`: l'azzeramento lo ferma e lo aspetta (e toglie i lavori in coda,
 * `BackupJobs.cancelAll`). Con una share vera (D12) o un cancello chiuso la tappa non parte e il servizio finisce.
 *
 * **"Ferma"** dalla notifica: un broadcast immutabile sul pacchetto, ricevuto da un ricevitore registrato **mentre il lavoro gira** e
 * **non esportato** (`RECEIVER_NOT_EXPORTED`): nessun componente nuovo nel manifest, nessun'altra app lo puo' mandare. Ferma il giro
 * prima del file successivo (non distrugge niente: ogni temporaneo resta con il suo punto di ripresa). Lo "Stop" del Task Manager di
 * Android (motivo `STOP_REASON_USER`) vale lo stesso.
 *
 * **Sicurezza.** Nel manifest e' `exported="false"` e protetto da `BIND_JOB_SERVICE`: lo lega solo il sistema (`JobScheduler`).
 * `verifyReleaseManifest` e `tools/check.ps1` pretendono entrambe le cose per ogni `*JobService`.
 */
class BackupJobService : JobService() {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val running = ConcurrentHashMap<Int, Job>()
  private var stopReceiver: BroadcastReceiver? = null

  private fun entry(): BackupEntryPoint = EntryPointAccessors.fromApplication(applicationContext, BackupEntryPoint::class.java)

  override fun onStartJob(params: JobParameters): Boolean {
    val userInitiated = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && params.isUserInitiatedJob
    // Un lavoro dell'utente deve mostrare la sua notifica subito (Android lo chiede entro pochi secondi dall'avvio).
    if (userInitiated && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      setNotification(params, AppNotifications.ID_BACKUP, BackupNotification.build(this, BackupProgress.NONE), JOB_END_NOTIFICATION_POLICY_REMOVE)
    }
    val entry = runCatching { entry() }.getOrNull() ?: return false
    val gallery = entry.gallery()
    val backup = gallery.backup ?: return false
    if (!gallery.backgroundWorkAllowed) return false
    val trigger = JobSchedulerBackupJobs.triggerOf(params.extras.getString(JobSchedulerBackupJobs.EXTRA_TRIGGER))
    val confirmed = params.extras.getInt(JobSchedulerBackupJobs.EXTRA_CONFIRMED, 0) == 1
    registerStop(backup)
    running[params.jobId] = scope.launch {
      val notifier = launch { followProgress(entry.notifications(), backup, userInitiated) }
      val end = try {
        val stages = BackupStages(entry.graph(), backup)
        // La stima serve alle regole per la conferma sui dati mobili (proposta 10): il riepilogo e' in cache.
        val estimate = runCatching { backup.summary().toUpload.bytes }.getOrNull()
        BackupJobRun(
          stage = { kind -> gallery.runIfActive { stages.round(kind, trigger, userEstimate = estimate, confirmed = confirmed) } },
          userInitiated = userInitiated,
        ).run()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        GalleryLog.w(TAG) { "backup non ripreso: ${e.javaClass.simpleName}" }
        BackupJobEnd.RESCHEDULE
      } finally {
        notifier.cancel()
        if (!userInitiated) NotificationManagerCompat.from(this@BackupJobService).cancel(AppNotifications.ID_BACKUP)
      }
      GalleryLog.d(TAG) { "lavoro del backup finito: ${end.name}" }
      running.remove(params.jobId)
      jobFinished(params, end == BackupJobEnd.RESCHEDULE)
    }
    return true
  }

  override fun onStopJob(params: JobParameters): Boolean {
    running.remove(params.jobId)?.cancel()
    // "Stop" dal Task Manager di Android: l'utente ha detto di fermarsi, come "Ferma" (nessuna ripresa automatica).
    val byUser = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && params.stopReason == JobParameters.STOP_REASON_USER
    runCatching { entry().gallery().backup }.getOrNull()?.requestStop(if (byUser) StopBy.USER else StopBy.SYSTEM)
    return !byUser
  }

  override fun onDestroy() {
    stopReceiver?.let { runCatching { unregisterReceiver(it) } }
    stopReceiver = null
    scope.cancel()
    super.onDestroy()
  }

  /** La notifica segue lo stato del backup (solo numeri). Prima di Android 14 la posta il servizio, se si puo' notificare. */
  private suspend fun followProgress(notifications: AppNotifications, backup: BackupGraph, userInitiated: Boolean) {
    backup.status.map { BackupProgress.of(it) }.distinctUntilChanged().collect { progress ->
      if (!notifications.canNotify()) return@collect
      if (!userInitiated && !progress.running) return@collect
      try {
        NotificationManagerCompat.from(this).notify(AppNotifications.ID_BACKUP, BackupNotification.build(this, progress))
      } catch (e: SecurityException) {
        GalleryLog.w(TAG) { "notifica del backup non consentita" }
      }
    }
  }

  /** "Ferma" dalla notifica: un ricevitore non esportato, vivo solo mentre il servizio lavora. */
  private fun registerStop(backup: BackupGraph) {
    if (stopReceiver != null) return
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BackupNotification.stopAction(context)) return
        try {
          NotificationManagerCompat.from(context).notify(AppNotifications.ID_BACKUP, BackupNotification.build(context, BackupProgress.NONE, stopping = true))
        } catch (e: SecurityException) {
          // Senza permesso delle notifiche non c'era nemmeno il tasto.
        }
        // La richiesta arriva al motore (nello scope della galleria, che vive piu' del servizio) e al diario subito.
        backup.requestStop(StopBy.USER)
      }
    }
    ContextCompat.registerReceiver(this, receiver, IntentFilter(BackupNotification.stopAction(this)), ContextCompat.RECEIVER_NOT_EXPORTED)
    stopReceiver = receiver
  }

  private companion object {
    const val TAG = "BackupJobService"
  }
}
