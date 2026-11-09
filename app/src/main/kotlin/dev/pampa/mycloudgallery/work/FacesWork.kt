package dev.pampa.mycloudgallery.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.pampa.mycloudgallery.ai.AiKilledByMemory
import dev.pampa.mycloudgallery.ai.AiStageMark
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.faces.contract.FacesDiag
import dev.pampa.mycloudgallery.core.rules.platform.health.ExitRecord
import dev.pampa.mycloudgallery.core.faces.contract.FacesStop
import dev.pampa.mycloudgallery.core.model.FacesSetting
import dev.pampa.mycloudgallery.core.personal.contract.FacesSettingCodec
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.WaitReason
import dev.pampa.mycloudgallery.core.rules.WorkRequest
import dev.pampa.mycloudgallery.core.rules.run.BatchResult
import dev.pampa.mycloudgallery.core.rules.run.StageOutcome
import dev.pampa.mycloudgallery.core.rules.run.StagedRun
import dev.pampa.mycloudgallery.faces.FacesGraph
import dev.pampa.mycloudgallery.faces.FacesWiring
import dev.pampa.mycloudgallery.feature.diagnostics.recording.DiagJob
import dev.pampa.mycloudgallery.feature.diagnostics.recording.DiagJobResult
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.graph.AppGraph
import dev.pampa.mycloudgallery.graph.HeartbeatStore
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * I lavori delle persone (Fase 8, P13; docs/fase8-progetto.md §1.4, §2.3, §2.8). Tutti dentro `GalleryGraph.runIfActive` (l'azzeramento li ferma e li aspetta)
 * e mai nella variante di misura, con **nomi distinti** da quelli degli altri lavori (come IX-1 della revisione B della Fase 6):
 *
 * - [FacesComputeWorker]: il calcolo dei volti (`AI_COMPUTE`, tappe da 8 minuti, scrive sul NAS: prenotazioni e teste), **dopo** `AiComputeWorker` della stessa
 *   notte e periodico; "Analizza ora" e' una richiesta dell'utente (`UserInitiated`, confermata sulla pagina), l'unica che scavalca le regole: **il primo giro dopo
 *   l'accensione e ogni altro giro seguono le regole di sottofondo** (CA-3: notte, carica, Wi-Fi, batteria, caldo). Le tappe si concatenano; un'attesa delle
 *   regole riprogramma;
 * - [FacesEraseWorker]: riprende il diario di "Disattiva e cancella" (i passi locali non chiedono la rete); si riaccoda finche' la cancellazione non e'
 *   finita o non aspetta qualcosa di dichiarato (NAS, congelamento, dispositivi);
 * - [FacesNightlyWorker] (correzioni E, RG-1): il raggruppamento notturno del custode (nascite sotto soglia, unioni, esemplari), **un lavoro suo**: parte **dopo la
 *   tappa del calcolo** che ha analizzato qualcosa (in coda a lei, nella stessa notte) e una volta al giorno in carica come rete di sicurezza, con le regole di
 *   `AI_COMPUTE` (notte, carica, Wi-Fi). Prima stava in coda a `LibraryMaintenanceWorker`: dopo sette manutenzioni da 14 minuti di budget (oltre i 10 che Android
 *   da' a un lavoro) e senza aspettare la famiglia pronta;
 * - la sincronizzazione dei volti gira nello stesso `IndexSyncWorker`, **dopo** i dati personali (la generazione e' fresca: D30).
 */
object FacesWorkScheduling {
  const val COMPUTE_WORK = "faces-compute"

  /** "Analizza ora" e le sue tappe dopo: una richiesta dell'utente alla volta. */
  const val COMPUTE_NOW_WORK = "faces-compute-now"

  /** Il giro dopo una sincronizzazione o un calcolo AI con novita': lavoro unico, assorbe le novita' arrivate mentre gira. */
  const val COMPUTE_NEWS_WORK = "faces-compute-news"

  /** La tappa dopo di un giro non richiesto dall'utente: si accoda a quella in corso, mai buttata. */
  const val COMPUTE_NEXT_WORK = "faces-compute-next"
  const val ERASE_WORK = "faces-erase"

  /** RG-1: il raggruppamento notturno, periodico una volta al giorno in carica (la rete di sicurezza: anche senza un calcolo che lo chiami). */
  const val NIGHTLY_WORK = "faces-nightly"

  /** RG-1: il raggruppamento notturno subito dopo una tappa del calcolo che ha analizzato qualcosa, o dopo un'attesa delle regole. Lavoro unico. */
  const val NIGHTLY_NEXT_WORK = "faces-nightly-next"
  const val TAG = "faces"
  const val KEY_USER = "user"

  val PERIOD: Duration = 6.hours

  /** RG-1: una volta al giorno (come la manutenzione della Libreria, da cui il raggruppamento e' uscito). */
  val NIGHTLY_PERIOD: Duration = 24.hours

  fun ensure(context: Context) {
    WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
      COMPUTE_WORK,
      ExistingPeriodicWorkPolicy.KEEP,
      PeriodicWorkRequestBuilder<FacesComputeWorker>(PERIOD.inWholeHours, TimeUnit.HOURS).addTag(TAG).build(),
    )
    // RG-1: il raggruppamento notturno e' un lavoro suo, in carica e a batteria non scarica come la manutenzione da cui e' uscito (le regole di `AI_COMPUTE` si
    // rivedono dentro: notte, Wi-Fi, caldo).
    WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
      NIGHTLY_WORK,
      ExistingPeriodicWorkPolicy.KEEP,
      PeriodicWorkRequestBuilder<FacesNightlyWorker>(NIGHTLY_PERIOD.inWholeHours, TimeUnit.HOURS)
        .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build())
        .addTag(TAG)
        .build(),
    )
  }

  /**
   * RG-1: il raggruppamento notturno **dopo** la tappa del calcolo (nella stessa notte, in una finestra di Android tutta sua): lavoro unico, `KEEP` (una richiesta
   * in coda basta: assorbe quelle che arrivano mentre aspetta).
   */
  fun nightlyAfterCompute(context: Context) {
    enqueue(context, NIGHTLY_NEXT_WORK, ExistingWorkPolicy.KEEP, nightlyRequest())
  }

  /**
   * RG-1: un'attesa delle regole riprogramma anche il raggruppamento notturno (come il calcolo), con la regola unica di [RuleWaits]: il vincolo o il
   * ritardo esatti dove ci sono, altrimenti un ritardo che cresce (FAB-5; [attempt] dagli input del lavoro).
   */
  fun nightlyAfterWait(context: Context, wait: WaitReason, attempt: Int = 0) {
    val follow = RuleWaits.followUpOf(wait, java.time.LocalDateTime.now(), attempt) ?: return
    enqueue(
      context, NIGHTLY_NEXT_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE,
      nightlyRequest(follow.delay.inWholeMilliseconds, follow.constraints(), follow.attempt),
    )
  }

  private fun nightlyRequest(delayMs: Long = 0, constraints: Constraints = Constraints.NONE, attempt: Int = 0): OneTimeWorkRequest =
    OneTimeWorkRequestBuilder<FacesNightlyWorker>()
      .setInputData(workDataOf(RuleWaits.KEY_ATTEMPT to attempt))
      .setConstraints(constraints)
      .apply { if (delayMs > 0) setInitialDelay(delayMs, TimeUnit.MILLISECONDS) }
      .addTag(TAG)
      .build()

  /**
   * "Analizza ora" (CA-2, confermata dall'utente sulla pagina): una richiesta dell'utente, una sola alla volta (un secondo tocco mentre gira o aspetta non ne mette
   * un altro). **E' l'unico lavoro dei volti che scavalca le regole** (notte, carica, Wi-Fi: `UserInitiated`); non la temperatura, e non scarica originali.
   */
  fun computeNow(context: Context) {
    enqueue(context, COMPUTE_NOW_WORK, ExistingWorkPolicy.KEEP, request(user = true))
  }

  /** Dopo un giro con novita' (calcolo AI finito, sincronizzazione dell'indice): un giro del calcolo dei volti, lavoro unico, con le regole di sottofondo. */
  fun afterNews(context: Context) {
    enqueue(context, COMPUTE_NEWS_WORK, ExistingWorkPolicy.KEEP, request(user = false))
  }

  /**
   * CA-4: dopo un giro con novita' (sincronizzazione dell'indice, preparazione) il calcolo dei volti segue, **solo se Persone e' accesa per l'account** (la voce
   * `faces` dei dati personali): senza, il lavoro non avrebbe niente da fare e ogni giro dell'indice ne accoderebbe uno per niente. Come
   * [AiWorkScheduling.afterNews]: lavoro unico, `KEEP`.
   */
  fun afterNewsIfOn(context: Context, setting: FacesSetting?) {
    if (setting is FacesSetting.On) afterNews(context)
  }

  /** La voce `faces` dei dati personali di adesso (null = non c'e' o la famiglia non e' pronta): per [afterNewsIfOn]. Una lettura in memoria, nessun I/O. */
  internal fun settingOf(gallery: GalleryGraph): FacesSetting? =
    gallery.nas?.organizer?.parts?.personalStore?.effectiveEntries?.value?.get(FacesSettingCodec.KEY)?.let { FacesSettingCodec.parse(it.value) }

  /**
   * CA-3 e CA-5: Persone si accende qui (o torna attiva, anche dopo "Disattiva e cancella" nello stesso processo). Il primo calcolo **segue le regole di
   * sottofondo** (notte, carica, Wi-Fi: lo stesso lavoro delle novita', non `UserInitiated`): la pagina dice «di notte, in carica» e ora e' vero; chi vuole
   * subito tocca «Analizza ora». E il lavoro periodico si riprogramma (`ensure`: lo toglie «Disattiva e cancella», e prima tornava solo al riavvio dell'app).
   */
  fun afterActivation(context: Context) {
    try {
      ensure(context)
    } catch (e: IllegalStateException) {
      // WorkManager non inizializzato (test senza lavori): il giro lo fa quello dell'indice.
      GalleryLog.w(TAG) { "lavoro periodico delle persone non programmato: ${e.javaClass.simpleName}" }
    }
    afterNews(context)
  }

  /** La richiesta di lavoro del grafo delle persone (`FacesGraph.workRequest`) e il lavoro di WorkManager che ne nasce: un posto solo per la mappa. */
  fun request(context: Context, work: FacesGraph.FacesWork) {
    when (work) {
      FacesGraph.FacesWork.COMPUTE -> afterActivation(context)
      FacesGraph.FacesWork.COMPUTE_NOW -> computeNow(context)
      FacesGraph.FacesWork.ERASE -> eraseNow(context)
    }
  }

  /** La tappa dopo (IX-1): il nome della catena, accodata a quella che gira. */
  fun continueCompute(context: Context, user: Boolean, delayMs: Long = 0, constraints: Constraints = Constraints.NONE, attempt: Int = 0) {
    val name = if (user) COMPUTE_NOW_WORK else COMPUTE_NEXT_WORK
    enqueue(context, name, ExistingWorkPolicy.APPEND_OR_REPLACE, request(user, delayMs, constraints, attempt))
  }

  /**
   * Un'attesa delle regole riprogramma (come il calcolo AI e il backup, con la stessa regola: [RuleWaits.followUpOf]): il lavoro dopo con il vincolo o
   * il ritardo esatti dove ci sono, altrimenti con un ritardo che cresce (FAB-5: niente risvegli al minuto col risparmio energetico acceso).
   */
  fun afterWait(context: Context, user: Boolean, wait: WaitReason, attempt: Int = 0) {
    val follow = RuleWaits.followUpOf(wait, java.time.LocalDateTime.now(), attempt) ?: return
    continueCompute(context, user, follow.delay.inWholeMilliseconds, follow.constraints(), follow.attempt)
  }

  /** Riprende la cancellazione adesso (unico, `KEEP`: se ce n'e' gia' uno resta quello). */
  fun eraseNow(context: Context) = enqueueErase(context, 0, ExistingWorkPolicy.KEEP)

  /** Riprende fra [delay] (unico, `APPEND_OR_REPLACE`: mai annullare quello in corso). */
  fun eraseIn(context: Context, delay: Duration) = enqueueErase(context, delay.inWholeMilliseconds, ExistingWorkPolicy.APPEND_OR_REPLACE)

  private fun enqueueErase(context: Context, delayMs: Long, policy: ExistingWorkPolicy) {
    // Nessun vincolo di rete: i passi locali non chiedono la rete e la parte sul NAS aspetta da sola con il suo motivo (E-F30).
    val request = OneTimeWorkRequestBuilder<FacesEraseWorker>().setInitialDelay(delayMs, TimeUnit.MILLISECONDS).addTag(TAG).build()
    enqueue(context, ERASE_WORK, policy, request)
  }

  private fun request(user: Boolean, delayMs: Long = 0, constraints: Constraints = Constraints.NONE, attempt: Int = 0): OneTimeWorkRequest =
    OneTimeWorkRequestBuilder<FacesComputeWorker>()
      .setInputData(workDataOf(KEY_USER to user, RuleWaits.KEY_ATTEMPT to attempt))
      .setConstraints(constraints)
      .apply { if (delayMs > 0) setInitialDelay(delayMs, TimeUnit.MILLISECONDS) }
      .addTag(TAG)
      .build()

  private fun enqueue(context: Context, name: String, policy: ExistingWorkPolicy, request: OneTimeWorkRequest) {
    try {
      WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(name, policy, request)
    } catch (e: IllegalStateException) {
      // WorkManager non inizializzato (test senza lavori): il giro lo fa quello dell'indice.
      GalleryLog.w(TAG) { "lavoro delle persone non programmato: ${e.javaClass.simpleName}" }
    }
  }
}

/**
 * E-A16 per i volti (come `AiStages`, DG-3): il segno "una tappa e' in corso", scritto all'inizio e tolto alla fine anche se la tappa e' fermata o annullata;
 * se alla tappa dopo c'e' ancora, il processo e' morto durante la tappa, e se Android l'ha chiuso per memoria (`ApplicationExitInfo`) si registra
 * `FACES_KILLED_MEMORY` (solo il codice). Senza WorkManager, per provarlo.
 */
class FacesStageWatch(
  private val mark: AiStageMark,
  private val exits: suspend () -> List<ExitRecord>,
  private val report: (DiagCode) -> Unit,
  private val now: () -> Long,
) {
  suspend fun <T> around(block: suspend () -> T): T {
    reportInterrupted()
    mark.begin(now())
    try {
      return block()
    } finally {
      mark.end()
    }
  }

  private suspend fun reportInterrupted() {
    val markedAt = mark.interruptedAt() ?: return
    val recent = try {
      exits()
    } catch (e: kotlinx.coroutines.CancellationException) {
      throw e
    } catch (e: Exception) {
      emptyList()
    }
    if (AiKilledByMemory.wasKilled(markedAt, recent)) report(DiagCode(FacesDiag.FACES_KILLED_MEMORY))
  }
}

/** Una tappa del calcolo dei volti (testabile senza WorkManager): regole di `AI_COMPUTE`, 8 minuti, battito a lavoro finito. */
class FacesStages(private val graph: AppGraph, private val faces: FacesGraph, noBackup: File) {
  val beat = HeartbeatStore(File(noBackup, FacesWiring.HEARTBEAT))

  /** RG-1: l'ultimo raggruppamento notturno finito (il battito, come `beat` per il calcolo): la Diagnostica e le prove sanno se e quando e' girato. */
  val nightlyBeat = HeartbeatStore(File(noBackup, NIGHTLY_BEAT))
  private val watch = FacesStageWatch(
    mark = AiStageMark(File(noBackup, FacesWiring.STAGE_MARK)),
    exits = { graph.platform.healthReader.read().lastExitReasons },
    report = { code -> graph.events.code(code) },
    now = { graph.platform.deviceNow() },
  )

  /** RG-1: quanti media l'ultima chiamata di [compute] ha analizzato. Zero = niente di nuovo: il raggruppamento notturno non ha motivo di seguire. */
  @Volatile var lastAnalyzed: Int = 0
    private set

  suspend fun compute(user: Boolean): StageOutcome {
    lastAnalyzed = 0
    // OB-3: con l'app in uso i thread dei volti scendono di priorita' e i motori si aprono con meno thread (anche per «Analizza ora»).
    return watch.around { graph.quietly { stage(user) } }
  }

  private suspend fun stage(user: Boolean): StageOutcome {
    val request = if (user) {
      WorkRequest.UserInitiated(BackgroundActivity.AI_COMPUTE, confirmed = true, writesToNas = true)
    } else {
      WorkRequest.Background(BackgroundActivity.AI_COMPUTE, writesToNas = true)
    }
    val outcome = StagedRun(request = request, rules = graph.stageRules(), ledger = graph.trafficLedger, checkpoints = NoCheckpoints)
      .run({ scope, _ ->
        val results = faces.computeStage(scope) ?: return@run BatchResult(hasMore = false)
        lastAnalyzed += results.sumOf { it.second.analyzed }
        BatchResult(hasMore = results.any { it.second.stop == FacesStop.BUDGET || it.second.stop == FacesStop.RULES })
      })
    if (outcome is StageOutcome.Finished) beat.beat(graph.platform.deviceNow())
    return outcome
  }

  /**
   * RG-1: il raggruppamento notturno come **una tappa sua**: stesse regole del calcolo (`AI_COMPUTE`, in background: notte, carica, Wi-Fi, caldo; e scrive sul NAS),
   * un solo lotto di [FACES_NIGHTLY_BUDGET]. Senza Persone attiva qui, o se questo dispositivo non e' il custode, `FacesGraph.nightly` non fa niente e la tappa e'
   * comunque finita. Il battito si scrive solo a tappa finita.
   */
  suspend fun nightly(): StageOutcome {
    val request = WorkRequest.Background(BackgroundActivity.AI_COMPUTE, writesToNas = true)
    val outcome = StagedRun(request = request, rules = graph.stageRules(), ledger = graph.trafficLedger, checkpoints = NoCheckpoints)
      .run({ _, _ ->
        // OB-3: il raggruppamento e' CPU pura; se l'utente apre l'app mentre gira, si fa da parte.
        graph.quietly { faces.nightly(FACES_NIGHTLY_BUDGET) }
        BatchResult(hasMore = false)
      })
    if (outcome is StageOutcome.Finished) nightlyBeat.beat(graph.platform.deviceNow())
    return outcome
  }

  companion object {
    /** Il battito del raggruppamento notturno (file di stato nuovo, sotto `noBackupFilesDir`, scritto da [HeartbeatStore] con `StateFiles`). */
    const val NIGHTLY_BEAT: String = "faces-nightly-beat"

    /** Quanto dura il raggruppamento notturno: come prima, dentro la manutenzione (due minuti); ora ha pero' la sua finestra di Android. */
    val FACES_NIGHTLY_BUDGET: Duration = 2.minutes
  }
}

/** Il calcolo dei volti in background (§2.3, D16). */
@HiltWorker
class FacesComputeWorker @AssistedInject constructor(
  @Assisted appContext: Context,
  @Assisted params: WorkerParameters,
  private val gallery: GalleryGraph,
  private val graph: AppGraph,
  private val faces: FacesGraph,
) : CoroutineWorker(appContext, params) {

  override suspend fun doWork(): Result {
    if (!gallery.backgroundWorkAllowed) return Result.success()
    gallery.awaitReady(COLD_START_WAIT)
    val user = inputData.getBoolean(FacesWorkScheduling.KEY_USER, false)
    val attempt = inputData.getInt(RuleWaits.KEY_ATTEMPT, 0)
    val stages = FacesStages(graph, faces, applicationContext.noBackupFilesDir)
    val outcome = try {
      gallery.runIfActive {
        gallery.nas?.start()
        stages.compute(user)
      }
    } catch (e: kotlinx.coroutines.CancellationException) {
      StopReports.report(graph, DiagJob.FACES_COMPUTE, StopReports.reasonOf(this))
      throw e
    } catch (e: Exception) {
      GalleryLog.e(TAG) { "tappa del calcolo dei volti non riuscita: ${e.javaClass.simpleName}" }
      graph.events.jobFailed(DiagJob.FACES_COMPUTE)
      return Result.success()
    }
    outcome?.let { graph.events.stage(DiagJob.FACES_COMPUTE, it) }
    when (outcome) {
      is StageOutcome.Yielded -> FacesWorkScheduling.continueCompute(applicationContext, user)
      is StageOutcome.Waiting -> FacesWorkScheduling.afterWait(applicationContext, user, outcome.reason, attempt)
      is StageOutcome.Stopped -> FacesWorkScheduling.afterWait(applicationContext, user, outcome.reason, attempt)
      // RG-1: il calcolo e' finito e ha analizzato qualcosa: il raggruppamento notturno segue, nella stessa notte (nascite sotto soglia, unioni, esemplari), in una
      // finestra di Android tutta sua e con le sue regole.
      is StageOutcome.Finished -> if (stages.lastAnalyzed > 0) FacesWorkScheduling.nightlyAfterCompute(applicationContext)
      else -> Unit
    }
    return Result.success()
  }

  companion object {
    const val TAG = "FacesComputeWorker"
    val COLD_START_WAIT: Duration = 20.seconds
  }
}

/**
 * Il raggruppamento notturno delle persone (RG-1; docs/fase8-progetto.md §2.5 "Manutenzione notturna"): un lavoro suo. Aspetta la famiglia pronta come gli altri lavori
 * ([GalleryGraph.awaitReady], D-7: in un processo nato per lui lo stato "non configurato" dura un attimo) e poi gira dentro `runIfActive`, con le regole di
 * `AI_COMPUTE` ([FacesStages.nightly]). Solo il custode raggruppa davvero; senza Persone attiva qui non fa niente.
 */
@HiltWorker
class FacesNightlyWorker @AssistedInject constructor(
  @Assisted appContext: Context,
  @Assisted params: WorkerParameters,
  private val gallery: GalleryGraph,
  private val graph: AppGraph,
  private val faces: FacesGraph,
) : CoroutineWorker(appContext, params) {

  override suspend fun doWork(): Result {
    if (!gallery.backgroundWorkAllowed) return Result.success()
    gallery.awaitReady(FacesComputeWorker.COLD_START_WAIT)
    val attempt = inputData.getInt(RuleWaits.KEY_ATTEMPT, 0)
    val outcome = try {
      gallery.runIfActive {
        gallery.nas?.start()
        FacesStages(graph, faces, applicationContext.noBackupFilesDir).nightly()
      }
    } catch (e: kotlinx.coroutines.CancellationException) {
      StopReports.report(graph, DiagJob.FACES_NIGHTLY, StopReports.reasonOf(this))
      throw e
    } catch (e: Exception) {
      GalleryLog.e(TAG) { "raggruppamento notturno dei volti non riuscito: ${e.javaClass.simpleName}" }
      graph.events.jobFailed(DiagJob.FACES_NIGHTLY)
      return Result.success()
    }
    outcome?.let { graph.events.stage(DiagJob.FACES_NIGHTLY, it) }
    when (outcome) {
      is StageOutcome.Waiting -> FacesWorkScheduling.nightlyAfterWait(applicationContext, outcome.reason, attempt)
      is StageOutcome.Stopped -> FacesWorkScheduling.nightlyAfterWait(applicationContext, outcome.reason, attempt)
      else -> Unit
    }
    return Result.success()
  }

  private companion object {
    const val TAG = "FacesNightlyWorker"
  }
}

/** "Disattiva e cancella" ripreso dal diario (§2.8): finche' non e' finita, o finche' non aspetta qualcosa che il lavoro non puo' accelerare. */
@HiltWorker
class FacesEraseWorker @AssistedInject constructor(
  @Assisted appContext: Context,
  @Assisted params: WorkerParameters,
  private val gallery: GalleryGraph,
  private val graph: AppGraph,
  private val faces: FacesGraph,
) : CoroutineWorker(appContext, params) {

  override suspend fun doWork(): Result {
    if (!gallery.backgroundWorkAllowed) return Result.success()
    gallery.awaitReady(FacesComputeWorker.COLD_START_WAIT)
    val next = try {
      gallery.runIfActive {
        gallery.nas?.start()
        faces.eraseRound(BUDGET)
      }
    } catch (e: kotlinx.coroutines.CancellationException) {
      StopReports.report(graph, DiagJob.FACES_ERASE, StopReports.reasonOf(this))
      throw e
    } catch (e: Exception) {
      GalleryLog.e(TAG) { "ripresa della cancellazione dei volti non riuscita: ${e.javaClass.simpleName}" }
      graph.events.jobFailed(DiagJob.FACES_ERASE)
      return Result.success()
    }
    graph.events.result(DiagJob.FACES_ERASE, if (next != null) DiagJobResult.YIELDED else DiagJobResult.FINISHED)
    if (next != null) FacesWorkScheduling.eraseIn(applicationContext, next)
    return Result.success()
  }

  private companion object {
    const val TAG = "FacesEraseWorker"
    val BUDGET: Duration = 3.minutes
  }
}
