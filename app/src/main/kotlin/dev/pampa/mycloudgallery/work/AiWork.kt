package dev.pampa.mycloudgallery.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.pampa.mycloudgallery.ai.AiGraph
import dev.pampa.mycloudgallery.ai.AiStack
import dev.pampa.mycloudgallery.core.ai.compute.AiCheckpoint
import dev.pampa.mycloudgallery.core.ai.compute.StageScopeControl
import dev.pampa.mycloudgallery.core.ai.contract.AiStop
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.ModelTrigger
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.WorkRequest
import dev.pampa.mycloudgallery.core.rules.run.BatchResult
import dev.pampa.mycloudgallery.core.rules.run.CheckpointStore
import dev.pampa.mycloudgallery.core.rules.run.StageOutcome
import dev.pampa.mycloudgallery.core.rules.run.StagedRun
import dev.pampa.mycloudgallery.feature.diagnostics.recording.DiagJob
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
 * I lavori della Fase 6 (docs/fase6-progetto.md §1.4, §2.7, D17, D22), tutti dentro `GalleryGraph.runIfActive` (l'azzeramento li ferma e
 * li aspetta) e mai nella variante di misura:
 *
 * - [ModelFetchWorker]: i modelli che servono qui (il 270M per tutti, il 440M sugli indicizzatori). Periodico secondo le regole di
 *   `AI_COMPUTE` (pesante: solo Wi-Fi, mai i dati mobili in background: il cancello di rete di core-models); **accelerato** per "Scarica
 *   ora" e per il cambio di ruolo (lavoro di WorkManager, D17: nessun servizio nuovo);
 * - [AiComputeWorker]: `AI_COMPUTE` a tappe da 8 minuti (`StagedRun`, `writesToNas`: prenotazioni e teste `ai`); dopo la
 *   sincronizzazione e la preparazione (lavoro unico, `KEEP`) e periodico; "Indicizza ora" e' una richiesta dell'utente.
 */
object AiWorkScheduling {
  const val FETCH_WORK = "ai-model-fetch"
  const val FETCH_NOW_WORK = "ai-model-fetch-now"
  const val COMPUTE_WORK = "ai-compute"
  const val COMPUTE_NOW_WORK = "ai-compute-now"
  const val TAG = "ai"
  const val KEY_TRIGGER = "trigger"
  const val KEY_USER = "user"

  val PERIOD: Duration = 6.hours

  fun ensure(context: Context) {
    val wm = WorkManager.getInstance(context.applicationContext)
    val unmetered = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()
    wm.enqueueUniquePeriodicWork(
      FETCH_WORK,
      ExistingPeriodicWorkPolicy.KEEP,
      PeriodicWorkRequestBuilder<ModelFetchWorker>(PERIOD.inWholeHours, TimeUnit.HOURS).setConstraints(unmetered).addTag(TAG).build(),
    )
    wm.enqueueUniquePeriodicWork(
      COMPUTE_WORK,
      ExistingPeriodicWorkPolicy.KEEP,
      PeriodicWorkRequestBuilder<AiComputeWorker>(PERIOD.inWholeHours, TimeUnit.HOURS).addTag(TAG).build(),
    )
  }

  /** "Scarica ora" e il cambio di ruolo: un lavoro accelerato (D17). */
  fun fetchNow(context: Context, trigger: ModelTrigger) {
    val request = OneTimeWorkRequestBuilder<ModelFetchWorker>()
      .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
      .setInputData(workDataOf(KEY_TRIGGER to trigger.name))
      .addTag(TAG)
      .build()
    WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(FETCH_NOW_WORK, ExistingWorkPolicy.REPLACE, request)
  }

  /** "Indicizza ora" (D22): una richiesta dell'utente; una sola alla volta. */
  fun indexNow(context: Context) {
    val request = OneTimeWorkRequestBuilder<AiComputeWorker>()
      .setInputData(workDataOf(KEY_USER to true))
      .addTag(TAG)
      .build()
    WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(COMPUTE_NOW_WORK, ExistingWorkPolicy.KEEP, request)
  }

  /** Dopo la sincronizzazione o la preparazione con novita': un giro del calcolo (lavoro unico, `KEEP`). */
  fun afterNews(context: Context) {
    val request = OneTimeWorkRequestBuilder<AiComputeWorker>().addTag(TAG).build()
    WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(COMPUTE_NOW_WORK, ExistingWorkPolicy.KEEP, request)
  }
}

/** Nessun punto di ripresa proprio: la ripresa vera e' `AiResults.saved` (ogni media si salva subito, D22). */
object NoAiCheckpoints : CheckpointStore<AiCheckpoint> {
  override suspend fun load(): AiCheckpoint? = null

  override suspend fun save(checkpoint: AiCheckpoint) = Unit

  override suspend fun clear() = Unit
}

/** Una tappa del calcolo AI (testabile senza WorkManager): regole di `AI_COMPUTE`, 8 minuti, battito a lavoro finito. */
class AiStages(private val graph: AppGraph, private val ai: AiGraph, noBackup: File) {
  val beat = HeartbeatStore(File(noBackup, HEARTBEAT_AI))

  suspend fun compute(user: Boolean): StageOutcome {
    val request = if (user) {
      WorkRequest.UserInitiated(BackgroundActivity.AI_COMPUTE, confirmed = true, writesToNas = true)
    } else {
      WorkRequest.Background(BackgroundActivity.AI_COMPUTE, writesToNas = true)
    }
    val outcome = StagedRun(request = request, rules = graph.stageRules(), ledger = graph.trafficLedger, checkpoints = NoAiCheckpoints)
      .run({ scope, _ ->
        val results = ai.runStage(Duration.INFINITE, StageScopeControl(scope)) ?: return@run BatchResult(hasMore = false)
        BatchResult(hasMore = results.any { it.second.stoppedBy == AiStop.BUDGET || it.second.stoppedBy == AiStop.RULES })
      })
    if (outcome is StageOutcome.Finished) beat.beat(graph.platform.deviceNow())
    return outcome
  }

  companion object {
    const val HEARTBEAT_AI = "heartbeat-ai-compute"
  }
}

/** Il calcolo AI in background (§2.7, D22). */
@HiltWorker
class AiComputeWorker @AssistedInject constructor(
  @Assisted appContext: Context,
  @Assisted params: WorkerParameters,
  private val gallery: GalleryGraph,
  private val graph: AppGraph,
  private val stack: AiStack,
) : CoroutineWorker(appContext, params) {

  override suspend fun doWork(): Result {
    if (!gallery.backgroundWorkAllowed) return Result.success()
    gallery.awaitReady(COLD_START_WAIT)
    val user = inputData.getBoolean(AiWorkScheduling.KEY_USER, false)
    val outcome = try {
      gallery.runIfActive {
        gallery.nas?.start()
        AiStages(graph, stack.ai, applicationContext.noBackupFilesDir).compute(user)
      }
    } catch (e: kotlinx.coroutines.CancellationException) {
      StopReports.report(graph, DiagJob.AI_COMPUTE, StopReports.reasonOf(this))
      throw e
    } catch (e: Exception) {
      GalleryLog.e(TAG, e) { "tappa del calcolo non riuscita" }
      graph.events.jobFailed(DiagJob.AI_COMPUTE)
      return Result.success()
    }
    outcome?.let { graph.events.stage(DiagJob.AI_COMPUTE, it) }
    if (outcome is StageOutcome.Yielded) AiWorkScheduling.afterNews(applicationContext)
    return Result.success()
  }

  companion object {
    const val TAG = "AiComputeWorker"
    val COLD_START_WAIT: Duration = 20.seconds
  }
}

/** I modelli della ricerca (D16, D17): verifica e scaricamento con ripresa, dalle sorgenti in ordine (NAS, HTTPS, cartella dal PC). */
@HiltWorker
class ModelFetchWorker @AssistedInject constructor(
  @Assisted appContext: Context,
  @Assisted params: WorkerParameters,
  private val gallery: GalleryGraph,
  private val graph: AppGraph,
  private val stack: AiStack,
) : CoroutineWorker(appContext, params) {

  override suspend fun doWork(): Result {
    if (!gallery.backgroundWorkAllowed) return Result.success()
    val trigger = inputData.getString(AiWorkScheduling.KEY_TRIGGER)?.let { name -> ModelTrigger.entries.firstOrNull { it.name == name } }
      ?: ModelTrigger.RULES
    val outcome = gallery.runIfActive {
      runCatching { stack.localModels.refresh() }
      val wanted = buildList {
        add(ModelCatalog.QUERY_270M.id)
        // Il ritaglio del soggetto (P13): il modello e' nell'APK, una copia locale fra i modelli verificati, senza rete.
        add(ModelCatalog.CUTOUT_MAGICTOUCH.id)
        if (stack.ai.indexer.value) add(ModelCatalog.INDEX_440M.id)
      }
      wanted.map { id -> stack.localModels.manager.ensure(id, trigger, BUDGET) }
    }
    GalleryLog.d(TAG) { "modelli: ${outcome?.map { it.javaClass.simpleName }}" }
    // Dopo uno "Scarica ora" la conferma dei dati mobili non vale per il prossimo.
    if (trigger == ModelTrigger.USER_NOW) stack.mobileConfirmed.set(false)
    if (outcome != null && outcome.all { it is dev.pampa.mycloudgallery.core.models.contract.EnsureResult.Ready } && stack.ai.indexer.value) {
      AiWorkScheduling.afterNews(applicationContext)
    }
    return Result.success()
  }

  companion object {
    const val TAG = "ModelFetchWorker"
    val BUDGET: Duration = 8.minutes
  }
}
