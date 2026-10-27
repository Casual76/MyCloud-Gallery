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
 *   notte e periodico; "Analizza ora" e' una richiesta dell'utente (`UserInitiated`). Le tappe si concatenano; un'attesa delle regole riprogramma;
 * - [FacesEraseWorker]: riprende il diario di "Disattiva e cancella" (i passi locali non chiedono la rete); si riaccoda finche' la cancellazione non e'
 *   finita o non aspetta qualcosa di dichiarato (NAS, congelamento, dispositivi);
 * - la sincronizzazione dei volti gira nello stesso `IndexSyncWorker`, **dopo** i dati personali (la generazione e' fresca: D30), e il raggruppamento notturno
 *   dentro `LibraryMaintenanceWorker`.
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
  const val TAG = "faces"
  const val KEY_USER = "user"

  val PERIOD: Duration = 6.hours

  fun ensure(context: Context) {
    WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
      COMPUTE_WORK,
      ExistingPeriodicWorkPolicy.KEEP,
      PeriodicWorkRequestBuilder<FacesComputeWorker>(PERIOD.inWholeHours, TimeUnit.HOURS).addTag(TAG).build(),
    )
  }

  /** "Analizza ora": una richiesta dell'utente, una sola alla volta (un secondo tocco mentre gira o aspetta non ne mette un altro). */
  fun computeNow(context: Context) {
    enqueue(context, COMPUTE_NOW_WORK, ExistingWorkPolicy.KEEP, request(user = true))
  }

  /** Dopo un giro con novita' (calcolo AI finito, sincronizzazione dell'indice): un giro del calcolo dei volti, lavoro unico. */
  fun afterNews(context: Context) {
    enqueue(context, COMPUTE_NEWS_WORK, ExistingWorkPolicy.KEEP, request(user = false))
  }

  /** La tappa dopo (IX-1): il nome della catena, accodata a quella che gira. */
  fun continueCompute(context: Context, user: Boolean, delayMs: Long = 0, constraints: Constraints = Constraints.NONE) {
    val name = if (user) COMPUTE_NOW_WORK else COMPUTE_NEXT_WORK
    enqueue(context, name, ExistingWorkPolicy.APPEND_OR_REPLACE, request(user, delayMs, constraints))
  }

  /** Un'attesa delle regole riprogramma (come il calcolo AI): il lavoro dopo con il vincolo o il ritardo esatti. */
  fun afterWait(context: Context, user: Boolean, wait: WaitReason) {
    val follow = AiWorkScheduling.followUpOf(wait, java.time.LocalDateTime.now()) ?: return
    continueCompute(context, user, follow.delay.inWholeMilliseconds, follow.constraints())
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

  private fun request(user: Boolean, delayMs: Long = 0, constraints: Constraints = Constraints.NONE): OneTimeWorkRequest =
    OneTimeWorkRequestBuilder<FacesComputeWorker>()
      .setInputData(workDataOf(KEY_USER to user))
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
  private val watch = FacesStageWatch(
    mark = AiStageMark(File(noBackup, FacesWiring.STAGE_MARK)),
    exits = { graph.platform.healthReader.read().lastExitReasons },
    report = { code -> graph.events.code(code) },
    now = { graph.platform.deviceNow() },
  )

  suspend fun compute(user: Boolean): StageOutcome = watch.around { stage(user) }

  private suspend fun stage(user: Boolean): StageOutcome {
    val request = if (user) {
      WorkRequest.UserInitiated(BackgroundActivity.AI_COMPUTE, confirmed = true, writesToNas = true)
    } else {
      WorkRequest.Background(BackgroundActivity.AI_COMPUTE, writesToNas = true)
    }
    val outcome = StagedRun(request = request, rules = graph.stageRules(), ledger = graph.trafficLedger, checkpoints = NoCheckpoints)
      .run({ scope, _ ->
        val results = faces.computeStage(scope) ?: return@run BatchResult(hasMore = false)
        BatchResult(hasMore = results.any { it.second.stop == FacesStop.BUDGET || it.second.stop == FacesStop.RULES })
      })
    if (outcome is StageOutcome.Finished) beat.beat(graph.platform.deviceNow())
    return outcome
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
    val outcome = try {
      gallery.runIfActive {
        gallery.nas?.start()
        FacesStages(graph, faces, applicationContext.noBackupFilesDir).compute(user)
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
      is StageOutcome.Waiting -> FacesWorkScheduling.afterWait(applicationContext, user, outcome.reason)
      is StageOutcome.Stopped -> FacesWorkScheduling.afterWait(applicationContext, user, outcome.reason)
      else -> Unit
    }
    return Result.success()
  }

  companion object {
    const val TAG = "FacesComputeWorker"
    val COLD_START_WAIT: Duration = 20.seconds
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
