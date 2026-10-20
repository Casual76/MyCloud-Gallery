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
import dev.pampa.mycloudgallery.backup.IncomingStates
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.core.backup.contract.StopBy
import dev.pampa.mycloudgallery.core.backup.upload.BackupStageOutcome
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.graph.AppGraph
import dev.pampa.mycloudgallery.notify.AppNotifications
import dev.pampa.mycloudgallery.notify.BackupNotification
import dev.pampa.mycloudgallery.notify.BackupProgress
import dev.pampa.mycloudgallery.notify.FreeSpaceNotification
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRunState
import java.util.concurrent.ConcurrentHashMap
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
        // BK-6: il turno e' occupato da un altro lavoro del backup (le verifiche, il guardiano) e non si e' liberato in tempo: il tocco
        // dell'utente non e' "niente da fare", si riprende con il ritardo di `JobScheduler`.
        if (outcome.busy) return BackupJobEnd.RESCHEDULE
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

  /** IN-7: il "Ferma" dei file ricevuti ha un ricevitore suo e arriva al lavoro **in corso** (uno nuovo a ogni avvio), mai a uno vecchio. */
  private var incomingStopReceiver: BroadcastReceiver? = null

  @Volatile
  private var currentIncomingStop: IncomingStop? = null

  private fun entry(): BackupEntryPoint = EntryPointAccessors.fromApplication(applicationContext, BackupEntryPoint::class.java)

  override fun onStartJob(params: JobParameters): Boolean {
    // Correzioni 5B (LS-2): la verifica di "Libera spazio" ha un id suo e non tocca il backup.
    if (params.jobId == JobSchedulerBackupJobs.JOB_FREE_SPACE) return startFreeSpace(params)
    val userInitiated = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && params.isUserInitiatedJob
    val trigger = JobSchedulerBackupJobs.triggerOf(params.extras.getString(JobSchedulerBackupJobs.EXTRA_TRIGGER))
    // IN-7: i file ricevuti ("Salva sul NAS") hanno la loro notifica, il loro avanzamento e il loro "Ferma", che ferma solo loro.
    val incoming = trigger == BackupTrigger.INCOMING
    val notificationId = if (incoming) AppNotifications.ID_INCOMING else AppNotifications.ID_BACKUP
    // Un lavoro dell'utente deve mostrare la sua notifica subito (Android lo chiede entro pochi secondi dall'avvio).
    if (userInitiated && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      val first = if (incoming) BackupProgress.incoming(0, 0) else BackupProgress.NONE
      setNotification(params, notificationId, BackupNotification.build(this, first), JOB_END_NOTIFICATION_POLICY_REMOVE)
    }
    val entry = runCatching { entry() }.getOrNull() ?: return false
    val gallery = entry.gallery()
    val backup = gallery.backup ?: return false
    if (!gallery.backgroundWorkAllowed) return false
    val confirmed = params.extras.getInt(JobSchedulerBackupJobs.EXTRA_CONFIRMED, 0) == 1
    val incomingStop = IncomingStop()
    if (incoming) currentIncomingStop = incomingStop
    registerStop(backup, incoming)
    running[params.jobId] = scope.launch {
      val notifier = launch {
        if (incoming) {
          followIncomingProgress(entry.notifications(), backup, gallery.database, userInitiated)
        } else {
          followProgress(entry.notifications(), backup, userInitiated)
        }
      }
      val end = try {
        // R1: un errore inatteso si salva come problema visibile (la pagina non resta "in corso") e il lavoro finisce: mai un RESCHEDULE a
        // oltranza di un lavoro dell'utente che ogni volta muore allo stesso punto.
        when (
          val attempt = attemptStage(TAG, onError = { backup.noteUnexpectedError() }) {
            val stages = BackupStages(entry.graph(), backup)
            if (trigger == BackupTrigger.INCOMING) {
              // D24 (P14): una condivisione grande ("Salva sul NAS"): solo i file in arrivo, a tappe, con la stessa notifica e lo stesso "Ferma".
              BackupJobRun(
                stage = { kind -> if (kind == BackupKind.PHOTOS) gallery.runIfActive { stages.incoming(userInitiated, incomingStop::requested) } else null },
                userInitiated = userInitiated,
              ).run()
            } else {
              // La stima serve alle regole per la conferma sui dati mobili (proposta 10): il riepilogo e' in cache.
              val estimate = backup.uploadEstimateBytes()
              BackupJobRun(
                stage = { kind -> gallery.runIfActive { stages.round(kind, trigger, userEstimate = estimate, confirmed = confirmed) } },
                userInitiated = userInitiated,
              ).run()
            }
          }
        ) {
          StageAttempt.Failed -> BackupJobEnd.DONE
          is StageAttempt.Done -> attempt.value
        }
      } finally {
        notifier.cancel()
        if (!userInitiated) NotificationManagerCompat.from(this@BackupJobService).cancel(notificationId)
      }
      GalleryLog.d(TAG) { "lavoro del backup finito: ${end.name}" }
      running.remove(params.jobId)
      jobFinished(params, end == BackupJobEnd.RESCHEDULE)
    }
    return true
  }

  override fun onStopJob(params: JobParameters): Boolean {
    running.remove(params.jobId)?.cancel()
    if (params.jobId == JobSchedulerBackupJobs.JOB_FREE_SPACE) return stopFreeSpace(params)
    // "Stop" dal Task Manager di Android: l'utente ha detto di fermarsi, come "Ferma" (nessuna ripresa automatica).
    val byUser = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && params.stopReason == JobParameters.STOP_REASON_USER
    // IN-7: lo "Stop" del lavoro dei file ricevuti ferma solo loro: non e' la pausa (R4) di tutto il backup. Restano in coda e riprendono col giro dopo.
    if (params.jobId == JobSchedulerBackupJobs.JOB_INCOMING) {
      NotificationManagerCompat.from(this).cancel(AppNotifications.ID_INCOMING)
      return !byUser
    }
    runCatching { entry().gallery().backup }.getOrNull()?.requestStop(if (byUser) StopBy.USER else StopBy.SYSTEM)
    return !byUser
  }

  override fun onDestroy() {
    stopReceiver?.let { runCatching { unregisterReceiver(it) } }
    stopReceiver = null
    incomingStopReceiver?.let { runCatching { unregisterReceiver(it) } }
    incomingStopReceiver = null
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

  /**
   * Correzioni 5B (LS-2): **la verifica di "Libera spazio" come lavoro avviato dall'utente**. Il lavoro ospita il giro del processo
   * (`FiveBFreeSpaceRuns.host`): se il giro va gia' avanti (la pagina l'ha appena fatto partire) lo aspetta, se il processo e' morto e Android ha
   * rilanciato il lavoro lo ritrova dal diario (nello stesso avvio, entro le 6 ore: altrimenti e' scaduto e non c'e' niente da fare). La notifica
   * dice l'avanzamento (solo numeri) e, a "pronto", "Pronto: N elementi, X GB. Tocca per liberare" (resta dopo la fine del lavoro). Il lavoro non
   * libera niente: i lotti e il dialogo del sistema partono solo dalla pagina, con un tocco. Dentro `GalleryGraph.runIfActive`: l'azzeramento lo
   * ferma.
   */
  private fun startFreeSpace(params: JobParameters): Boolean {
    val userInitiated = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && params.isUserInitiatedJob
    if (userInitiated && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      setNotification(params, AppNotifications.ID_FREE_SPACE, FreeSpaceNotification.progress(this, null), JOB_END_NOTIFICATION_POLICY_DETACH)
    }
    val entry = runCatching { entry() }.getOrNull() ?: return false
    val gallery = entry.gallery()
    val backup = gallery.backup ?: return false
    if (!gallery.backgroundWorkAllowed) return false
    val notifications = entry.notifications()
    val manager = NotificationManagerCompat.from(this)
    running[params.jobId] = scope.launch {
      val end = try {
        gallery.runIfActive {
          backup.fiveB.freeRuns.host { run ->
            if (notifications.canNotify()) {
              try {
                manager.notify(AppNotifications.ID_FREE_SPACE, FreeSpaceNotification.progress(this@BackupJobService, run))
              } catch (e: SecurityException) {
                GalleryLog.w(TAG) { "notifica di Libera spazio non consentita" }
              }
            }
          }
        }
      } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
      } catch (e: Exception) {
        GalleryLog.w(TAG) { "verifica di Libera spazio finita con un errore (${e.javaClass.simpleName})" }
        null
      }
      // Pronto, e l'utente non e' sulla pagina: la notifica lo dice e resta. Altrimenti non serve piu'.
      if (end?.state == FreeSpaceRunState.READY && !backup.fiveB.freeRuns.appInForeground && notifications.canNotify()) {
        try {
          manager.notify(AppNotifications.ID_FREE_SPACE, FreeSpaceNotification.ready(this@BackupJobService, end))
        } catch (e: SecurityException) {
          manager.cancel(AppNotifications.ID_FREE_SPACE)
        }
      } else {
        manager.cancel(AppNotifications.ID_FREE_SPACE)
      }
      running.remove(params.jobId)
      jobFinished(params, false)
    }
    return true
  }

  /**
   * LS-2: Android ferma il lavoro di "Libera spazio". Lo "Stop" dell'utente (Task Manager) e' un "Annulla": il giro si chiude. Per gli altri motivi
   * il lavoro si riprogramma (il giro, nel diario, aspetta lui o la pagina). Mai un "Ferma" del backup.
   */
  private fun stopFreeSpace(params: JobParameters): Boolean {
    val byUser = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && params.stopReason == JobParameters.STOP_REASON_USER
    if (byUser) {
      val runs = runCatching { entry().gallery().backup?.fiveB?.freeRuns }.getOrNull()
      if (runs != null) scope.launch { runCatching { runs.cancel() } }
    }
    // L'avanzamento non resta sullo schermo fermo: il lavoro ripreso (o la pagina) lo riporta.
    NotificationManagerCompat.from(this).cancel(AppNotifications.ID_FREE_SPACE)
    return !byUser
  }

  /**
   * IN-7: la notifica dei file ricevuti ("Salva sul NAS", lavoro grande): "N di M" dal diario degli arrivi (soli numeri), non lo stato del backup delle
   * foto del telefono (prima mostrava "Sto riprendendo il backup…" o i numeri di un altro lavoro). Si aggiorna a ogni cambio del motore.
   */
  private suspend fun followIncomingProgress(notifications: AppNotifications, backup: BackupGraph, database: GalleryDatabase, userInitiated: Boolean) {
    val dao = database.incomingDao()
    val start = dao.inState(IncomingStates.QUEUED).size
    var last: BackupProgress? = null
    backup.status.map { dao.inState(IncomingStates.QUEUED).size }.distinctUntilChanged().collect { queued ->
      val done = (start - queued).coerceAtLeast(0)
      val progress = BackupProgress.incoming(done, maxOf(start, done + queued))
      if (progress == last || !notifications.canNotify()) return@collect
      last = progress
      if (!userInitiated && queued == 0) return@collect
      try {
        NotificationManagerCompat.from(this).notify(AppNotifications.ID_INCOMING, BackupNotification.build(this, progress))
      } catch (e: SecurityException) {
        GalleryLog.w(TAG) { "notifica dei file ricevuti non consentita" }
      }
    }
  }

  /**
   * "Ferma" dalla notifica: un ricevitore non esportato, vivo solo mentre il servizio lavora. Per il lavoro dei file ricevuti ([incoming]) ferma
   * **solo loro** (IN-7: prima era la pausa R4 di tutto il backup, e chi non aveva ancora fatto il primo backup non vedeva "Riprendi"): il giro
   * finisce al passo dopo e i file restano in coda. Due ricevitori, uno per lavoro: nello stesso servizio possono girare tutti e due.
   */
  private fun registerStop(backup: BackupGraph, incoming: Boolean) {
    if ((if (incoming) incomingStopReceiver else stopReceiver) != null) return
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BackupNotification.stopAction(context, incoming)) return
        try {
          val id = if (incoming) AppNotifications.ID_INCOMING else AppNotifications.ID_BACKUP
          val stopping = if (incoming) BackupProgress.incoming(0, 0) else BackupProgress.NONE
          NotificationManagerCompat.from(context).notify(id, BackupNotification.build(context, stopping, stopping = true))
        } catch (e: SecurityException) {
          // Senza permesso delle notifiche non c'era nemmeno il tasto.
        }
        if (incoming) {
          currentIncomingStop?.request()
        } else {
          // La richiesta arriva al motore (nello scope della galleria, che vive piu' del servizio) e al diario subito.
          backup.requestStop(StopBy.USER)
        }
      }
    }
    ContextCompat.registerReceiver(this, receiver, IntentFilter(BackupNotification.stopAction(this, incoming)), ContextCompat.RECEIVER_NOT_EXPORTED)
    if (incoming) incomingStopReceiver = receiver else stopReceiver = receiver
  }

  private companion object {
    const val TAG = "BackupJobService"
  }
}
