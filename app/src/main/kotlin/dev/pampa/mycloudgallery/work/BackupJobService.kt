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
import dev.pampa.mycloudgallery.core.rules.ConfirmationKind
import dev.pampa.mycloudgallery.core.rules.WaitReason as RulesWaitReason
import dev.pampa.mycloudgallery.core.rules.run.StageOutcome
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Cio' che il servizio prende dal grafo (Hilt non inietta un `JobService` senza `@AndroidEntryPoint`: si chiede all'applicazione). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface BackupEntryPoint {
  fun gallery(): GalleryGraph

  fun graph(): AppGraph

  fun notifications(): AppNotifications
}

/**
 * Come finisce un lavoro del backup avviato dall'utente: tutto fatto (o niente da fare), da riprendere con il ritardo di `JobScheduler`, oppure
 * (D-6) in attesa della rete: il lavoro dell'utente passa a [BackupWorkScheduling.userResume], che riparte quando torna il Wi-Fi.
 */
enum class BackupJobEnd { DONE, RESCHEDULE, AWAIT_NETWORK }

/**
 * Il giro di un lavoro del backup, senza Android (provato su JVM): prima le **foto**, poi i **video** (D11), ognuno a tappe finche' c'e'
 * altro da fare. Un lavoro avviato dall'utente ([userInitiated], UIDT) continua finche' serve ([maxSlices] tappe); uno normale fa una
 * tappa sola per tipo (Android lo ferma dopo 10 minuti) e si fa riprogrammare.
 *
 * **D-7** (prove sul campo): prima della prima tappa [ready] aspetta che il processo sia pronto. Dopo la morte del processo Android rilancia il
 * lavoro in un processo appena nato, e la prima tappa diceva "non parte" (stato del dispositivo ancora di passaggio, account e impostazioni
 * non ancora letti): il lavoro finiva in 0,4 secondi con `DONE` e il backup restava fermo fino al giro periodico.
 *
 * **D-6** (prove sul campo): un lavoro dell'utente che trova la rete andata (nessuna rete, una rete che non e' casa, il NAS che non risponde, i
 * dati mobili senza la conferma) non finisce: aspetta che la rete cambi ([networkChange], al massimo [patience] in tutto) e riprova lo stesso
 * tipo di media, con le regole di un lavoro dell'utente. Se la rete non torna in tempo il lavoro finisce con [BackupJobEnd.AWAIT_NETWORK]: chi
 * lo guida lo passa a un lavoro che riparte da solo con il Wi-Fi (mai sui dati mobili senza conferma). Prima finiva `DONE` e niente lo
 * faceva ripartire fino al giro periodico (6 ore). I punti di ripresa restano quelli del diario.
 *
 * @param stage una tappa del tipo dato; null = non parte (niente NAS, galleria ferma, cartella non scelta).
 */
class BackupJobRun(
  private val stage: suspend (BackupKind) -> BackupStageOutcome?,
  private val userInitiated: Boolean,
  private val maxSlices: Int = MAX_SLICES,
  private val ready: suspend () -> Boolean = { true },
  private val networkChange: suspend (Duration) -> Boolean = { false },
  private val patience: Duration = NETWORK_PATIENCE,
  private val time: TimeSource = TimeSource.Monotonic,
) {
  suspend fun run(): BackupJobEnd {
    // D-7: se non diventa pronto in tempo la tappa lo dice da sola ("non parte"), come prima.
    ready()
    val started = time.markNow()
    var waited = Duration.ZERO
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
        // D-6: la rete se n'e' andata. Si aspetta che cambi (un evento, nessun polling) e si riprova lo stesso tipo.
        if (userInitiated && waitsForNetwork(outcome)) {
          val left = patience - waited
          val mark = time.markNow()
          val changed = left.isPositive() && networkChange(left)
          waited += mark.elapsedNow()
          if (changed) continue
          GalleryLog.d(TAG) { "rete non tornata in ${started.elapsedNow().inWholeSeconds} s: il lavoro dell'utente aspetta il Wi-Fi" }
          return BackupJobEnd.AWAIT_NETWORK
        }
        when (BackupFollowUp.of(outcome)) {
          BackupFollowUp.Continue -> Unit
          BackupFollowUp.Retry -> return BackupJobEnd.RESCHEDULE
          // Le altre attese delle regole (caldo, congelamento) o la conferma del tetto dei dati: lo dice la pagina, non un lavoro che ritenta.
          is BackupFollowUp.Wait -> return BackupJobEnd.DONE
          BackupFollowUp.None -> more = false
        }
      }
    }
    return BackupJobEnd.DONE
  }

  companion object {
    private const val TAG = "BackupJobRun"

    /** Foto prima dei video (D11). */
    val KINDS: List<BackupKind> = listOf(BackupKind.PHOTOS, BackupKind.VIDEOS)

    /** Un lavoro dell'utente: al massimo 2 ore di tappe, poi si fa riprogrammare (UIDT resta tale). */
    const val MAX_SLICES: Int = 15

    /**
     * D-6: quanto un lavoro dell'utente aspetta, in tutto, che la rete torni prima di passare al lavoro che riparte con il Wi-Fi. Copre un Wi-Fi
     * che cade per qualche secondo o il riavvio del router; oltre, tenere sveglio il telefono non vale la pena (il lavoro che aspetta il Wi-Fi
     * non costa niente finche' il Wi-Fi non c'e').
     */
    val NETWORK_PATIENCE: Duration = 10.minutes

    /**
     * D-6: la tappa aspetta la rete: nessuna rete, una rete che non e' casa (o non ammessa), il NAS che non risponde, la rete lenta, il roaming,
     * oppure i dati mobili o il roaming che chiedono la conferma dell'utente (mai spesi senza: si aspetta il Wi-Fi). Non lo e' la conferma per il
     * tetto dei dati, che chiede l'utente, ne' il caldo o il congelamento. Pura, provata su JVM.
     */
    fun waitsForNetwork(outcome: BackupStageOutcome): Boolean = when (val stage = outcome.stage) {
      is StageOutcome.Waiting -> networkWait(stage.reason)
      is StageOutcome.Stopped -> networkWait(stage.reason)
      is StageOutcome.NeedsConfirmation -> onlyNetworkConfirmation(stage.decision.kinds)
      is StageOutcome.Finished, is StageOutcome.Yielded -> false
    }

    private fun networkWait(reason: RulesWaitReason): Boolean = when (reason) {
      RulesWaitReason.Offline, RulesWaitReason.NetworkNotTrusted, RulesWaitReason.NasUnreachable, RulesWaitReason.PoorNetwork,
      RulesWaitReason.Roaming, RulesWaitReason.HeavyNeedsHomeWifi, is RulesWaitReason.NetworkNotAllowed,
      -> true
      is RulesWaitReason.ConfirmationRequired -> onlyNetworkConfirmation(reason.kinds)
      else -> false
    }

    private fun onlyNetworkConfirmation(kinds: Set<ConfirmationKind>): Boolean = kinds.isNotEmpty() && ConfirmationKind.OVER_CAP !in kinds
  }
}

/**
 * **D-7**: aspetta, al massimo [timeout], che il backup di questo processo sia pronto a dire se c'e' qualcosa da fare: la famiglia pronta
 * ([GalleryGraph.awaitReady]), le impostazioni del backup lette dal loro magazzino (prima la cartella del dispositivo e' nulla e le sorgenti sono
 * vuote: "niente da fare") e le parti del backup costruite (account, aree e chiave arrivano dai loro archivi un attimo dopo l'avvio). In un
 * processo gia' avviato non costa niente. Falso se non e' pronto in tempo: allora la tappa dice da sola "non parte", come prima.
 */
internal suspend fun awaitBackupReady(gallery: GalleryGraph, backup: BackupGraph, timeout: Duration = COLD_START_WAIT): Boolean =
  withTimeoutOrNull(timeout) {
    if (!gallery.awaitReady(timeout)) return@withTimeoutOrNull false
    backup.settings.awaitLoaded()
    // Solo qui, all'avvio del lavoro: l'account e le aree arrivano dai loro archivi; nessuna attesa se il backup e' gia' partito.
    while (!backup.start()) delay(READY_RETRY_MS)
    true
  } ?: false

/** D-7: quanto un lavoro aspetta, al massimo, che il processo appena nato sia pronto. */
internal val COLD_START_WAIT: Duration = 30.seconds

private const val READY_RETRY_MS = 250L

/**
 * D-6: "la rete e' cambiata" per un lavoro che la aspetta: il posto della rete (casa, fuori, nessuna rete, VPN) o la raggiungibilita' del NAS
 * cambiano, oppure Android dice al lavoro che la sua rete e' cambiata ([extra], `JobService.onNetworkChanged`). Ogni raccolta parte dallo stato
 * di quel momento: conta solo cio' che cambia dopo. Le regole decidono poi se si riparte.
 */
internal fun networkChanges(graph: AppGraph, extra: Flow<Unit> = emptyFlow()): Flow<Unit> = merge(
  graph.environment.snapshot.map { it.place }.distinctUntilChanged().drop(1).map { },
  graph.statusMonitor.isReachable.drop(1).map { },
  extra,
)

/**
 * Il lavoro del backup dell'utente ("Inizia ora", "Fai ora") come lo fa girare [BackupJobService], e come lo prova il banco dei test: le tappe
 * dentro `GalleryGraph.runIfActive`, prima l'attesa del processo pronto (D-7), a una caduta della rete l'attesa che cambi (D-6). La stima per la
 * conferma sui dati mobili (proposta 10) si chiede una volta, a processo pronto.
 */
internal fun userBackupRun(
  gallery: GalleryGraph,
  graph: AppGraph,
  backup: BackupGraph,
  trigger: BackupTrigger,
  confirmed: Boolean,
  userInitiated: Boolean,
  changes: Flow<Unit> = networkChanges(graph),
): BackupJobRun {
  val stages = BackupStages(graph, backup)
  var estimate: Long? = null
  var estimated = false
  return BackupJobRun(
    stage = { kind ->
      if (!estimated) {
        estimated = true
        estimate = backup.uploadEstimateBytes()
      }
      gallery.runIfActive { stages.round(kind, trigger, userEstimate = estimate, confirmed = confirmed) }
    },
    userInitiated = userInitiated,
    ready = { awaitBackupReady(gallery, backup) },
    networkChange = { within -> withTimeoutOrNull(within) { changes.first() } != null },
  )
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

  /** D-6: i cambi di rete che Android annuncia al lavoro in corso (Android 14+). */
  private val networkChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

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
      val graph = entry.graph()
      // D-6: Android dice al lavoro che la sua rete e' cambiata (dati mobili → Wi-Fi): conta come un cambio della rete per chi la aspetta.
      val changes = networkChanges(graph, networkChanged)
      val end = try {
        // R1: un errore inatteso si salva come problema visibile (la pagina non resta "in corso") e il lavoro finisce: mai un RESCHEDULE a
        // oltranza di un lavoro dell'utente che ogni volta muore allo stesso punto.
        when (
          val attempt = attemptStage(TAG, onError = { backup.noteUnexpectedError() }) {
            if (trigger == BackupTrigger.INCOMING) {
              // D24 (P14): una condivisione grande ("Salva sul NAS"): solo i file in arrivo, a tappe, con la stessa notifica e lo stesso "Ferma".
              val stages = BackupStages(graph, backup)
              BackupJobRun(
                stage = { kind -> if (kind == BackupKind.PHOTOS) gallery.runIfActive { stages.incoming(userInitiated, incomingStop::requested) } else null },
                userInitiated = userInitiated,
                ready = { awaitBackupReady(gallery, backup) },
                networkChange = { within -> withTimeoutOrNull(within) { changes.first() } != null },
              ).run()
            } else {
              userBackupRun(gallery, graph, backup, trigger, confirmed, userInitiated, changes).run()
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
      // D-6: la rete non e' tornata in tempo. Il lavoro dell'utente passa a quello che riparte da solo con il Wi-Fi (regole dell'utente, mai
      // i dati mobili senza conferma); i file ricevuti al loro lavoro, che aspetta il Wi-Fi allo stesso modo (IN-5).
      if (end == BackupJobEnd.AWAIT_NETWORK) {
        if (incoming) BackupWorkScheduling.incoming(this@BackupJobService) else BackupWorkScheduling.userResume(this@BackupJobService, trigger)
      }
      running.remove(params.jobId)
      jobFinished(params, end == BackupJobEnd.RESCHEDULE)
    }
    return true
  }

  /**
   * D-6: la rete del lavoro e' cambiata (per esempio dai dati mobili al Wi-Fi di casa). Il lavoro dell'utente che aspetta la rete riprova
   * subito; uno che lavora non cambia niente (le regole lo rivalutano da sole). Prima Android lo diceva al vuoto: "onNetworkChanged() not
   * implemented".
   */
  override fun onNetworkChanged(params: JobParameters) {
    networkChanged.tryEmit(Unit)
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
    val startProblems = dao.countInStates(IncomingStates.PROBLEMS)
    var last: BackupProgress? = null
    backup.status.map { dao.inState(IncomingStates.QUEUED).size to dao.countInStates(IncomingStates.PROBLEMS) }.distinctUntilChanged().collect { (queued, problemsNow) ->
      // IN-7: un file che non si e' caricato non e' "fatto": esce dalla coda ma la notifica lo conta a parte ("2 di 5 file ricevuti · 1 con un problema").
      val problems = (problemsNow - startProblems).coerceAtLeast(0)
      val done = (start - queued - problems).coerceAtLeast(0)
      val progress = BackupProgress.incoming(done, maxOf(start, done + queued + problems), problems)
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
