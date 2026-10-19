package dev.pampa.mycloudgallery.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.feature.diagnostics.recording.DiagJob
import dev.pampa.mycloudgallery.feature.diagnostics.recording.DiagJobResult
import dev.pampa.mycloudgallery.graph.AppGraph
import dev.pampa.mycloudgallery.notify.AppNotifications
import dev.pampa.mycloudgallery.notify.NotifiedRequests
import java.io.File

/**
 * Il controllo del registro di famiglia in background (docs/fase2-progetto.md §2.3, riga "Autodistruzione"; §2.2
 * "notifica all'admin"). Lo pianifica [WorkManagerTrustSyncScheduler]: una volta al giorno, ogni 15 minuti nella
 * finestra d'invito, all'apertura dell'app e all'arrivo a casa. Il lavoro vero e' in [TrustSyncRun] (provato senza
 * WorkManager); qui ci sono solo l'aggancio a Hilt e l'esito.
 *
 * Una tappa corta (android.md 1.3): nessun ciclo, nessun nuovo tentativo immediato. Se oggi non si puo', si riprova al
 * prossimo giro pianificato.
 */
@HiltWorker
class TrustSyncWorker @AssistedInject constructor(
  @Assisted appContext: Context,
  @Assisted params: WorkerParameters,
  private val graph: AppGraph,
  private val notifications: AppNotifications,
) : CoroutineWorker(appContext, params) {

  override suspend fun doWork(): Result {
    val scheduler = graph.scheduler as? WorkManagerTrustSyncScheduler
    val run = TrustSyncRun(
      graph = graph,
      notifications = notifications,
      notified = NotifiedRequests(File(applicationContext.noBackupFilesDir, NOTIFIED_FILE)),
    )
    val outcome = try {
      run.execute()
    } catch (e: kotlinx.coroutines.CancellationException) {
      StopReports.report(graph, DiagJob.TRUST_SYNC, StopReports.reasonOf(this))
      throw e
    }
    GalleryLog.d(TAG) { "controllo del registro: $outcome" }
    recordOutcome(outcome)
    scheduler?.closeInviteWindowIfExpired()
    return Result.success()
  }

  /** L'esito nell'anello della Diagnostica (RE-4): un dispositivo non configurato non ha niente da dire. */
  private fun recordOutcome(outcome: TrustSyncOutcome) {
    val result = when (outcome) {
      TrustSyncOutcome.NOT_CONFIGURED -> return
      TrustSyncOutcome.RULES_WAIT -> DiagJobResult.WAITING
      TrustSyncOutcome.STOPPED -> DiagJobResult.STOPPED
      TrustSyncOutcome.READ_ONLY, TrustSyncOutcome.WIPE_STARTED, TrustSyncOutcome.DONE -> DiagJobResult.FINISHED
    }
    graph.events.result(DiagJob.TRUST_SYNC, result, reason = outcome)
  }

  companion object {
    const val TAG = "TrustSyncWorker"
    const val KEY_REASON = "reason"
    private const val NOTIFIED_FILE = "notified-requests"
  }
}
