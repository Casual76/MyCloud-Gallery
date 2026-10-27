package dev.pampa.mycloudgallery.work

import android.content.Context
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.pampa.mycloudgallery.ai.AiGraph
import dev.pampa.mycloudgallery.ai.AiKilledByMemory
import dev.pampa.mycloudgallery.ai.AiStack
import dev.pampa.mycloudgallery.ai.AiStageMark
import dev.pampa.mycloudgallery.core.ai.compute.AiCheckpoint
import dev.pampa.mycloudgallery.core.ai.compute.StageScopeControl
import dev.pampa.mycloudgallery.core.ai.contract.AiDiag
import dev.pampa.mycloudgallery.core.ai.contract.AiStop
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.models.contract.EnsureResult
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.ModelDiag
import dev.pampa.mycloudgallery.core.models.contract.ModelFailCode
import dev.pampa.mycloudgallery.core.models.contract.ModelId
import dev.pampa.mycloudgallery.core.models.contract.ModelState
import dev.pampa.mycloudgallery.core.models.contract.ModelTrigger
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.RuleDecision
import dev.pampa.mycloudgallery.core.rules.RunScope
import dev.pampa.mycloudgallery.core.rules.WaitReason
import dev.pampa.mycloudgallery.core.rules.WorkRequest
import dev.pampa.mycloudgallery.core.rules.run.BatchResult
import dev.pampa.mycloudgallery.core.rules.run.CheckpointStore
import dev.pampa.mycloudgallery.core.rules.run.StageOutcome
import dev.pampa.mycloudgallery.core.rules.run.StagedRun
import dev.pampa.mycloudgallery.feature.diagnostics.recording.DiagJob
import dev.pampa.mycloudgallery.feature.diagnostics.recording.DiagJobResult
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.graph.AppGraph
import dev.pampa.mycloudgallery.graph.HeartbeatStore
import java.io.File
import java.time.Duration as JavaDuration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * I lavori della Fase 6 (docs/fase6-progetto.md §1.4, §2.7, D17, D22), tutti dentro `GalleryGraph.runIfActive` (l'azzeramento li ferma e
 * li aspetta) e mai nella variante di misura:
 *
 * - [ModelFetchWorker]: i modelli che servono qui (il 270M per tutti, il 440M sugli indicizzatori). Periodico sulle regole di `AI_COMPUTE` e
 *   su una rete non a consumo (MO-9); **accelerato** per "Scarica ora" (il modello chiesto, anche se la pagina si chiude: MO-1) e per il cambio
 *   di ruolo (lavoro di WorkManager, D17: nessun servizio nuovo);
 * - [AiComputeWorker]: `AI_COMPUTE` a tappe da 8 minuti (`StagedRun`, `writesToNas`: prenotazioni e teste `ai`); dopo la sincronizzazione e la
 *   preparazione (lavoro unico) e periodico; "Indicizza ora" e' una richiesta dell'utente. **Le tappe si concatenano** (IX-1): la tappa dopo
 *   ha un nome suo e si accoda a quella in corso, un'attesa delle regole riprogramma il lavoro.
 */
object AiWorkScheduling {
  const val FETCH_WORK = "ai-model-fetch"
  const val FETCH_NOW_WORK = "ai-model-fetch-now"
  const val COMPUTE_WORK = "ai-compute"

  /** "Indicizza ora" e le sue tappe dopo (IX-1): una richiesta dell'utente alla volta; i giri "dopo una novita'" hanno il loro nome. */
  const val COMPUTE_NOW_WORK = "ai-compute-now"

  /** Il giro dopo una sincronizzazione o una preparazione con novita': lavoro unico, assorbe le novita' arrivate mentre gira. */
  const val COMPUTE_NEWS_WORK = "ai-compute-news"

  /** La tappa dopo di un giro non richiesto dall'utente (periodico o novita'): si accoda a quella in corso, mai buttata. */
  const val COMPUTE_NEXT_WORK = "ai-compute-next"
  const val TAG = "ai"
  const val KEY_TRIGGER = "trigger"
  const val KEY_USER = "user"
  const val KEY_MODEL = "model"

  val PERIOD: Duration = 6.hours

  /** Nessuna attesa breve di un'attesa delle regole e' un ciclo continuo: almeno questo prima di riprovare (la carica e la batteria sono vincoli). */
  val MIN_RETRY: Duration = 1.minutes

  /** Col telefono troppo caldo si riprova dopo 5 minuti (E-A5). */
  val THERMAL_RETRY: Duration = 5.minutes

  fun ensure(context: Context) {
    val wm = WorkManager.getInstance(context.applicationContext)
    wm.enqueueUniquePeriodicWork(
      FETCH_WORK,
      ExistingPeriodicWorkPolicy.KEEP,
      PeriodicWorkRequestBuilder<ModelFetchWorker>(PERIOD.inWholeHours, TimeUnit.HOURS).setConstraints(LanConstraints.unmetered()).addTag(TAG).build(),
    )
    wm.enqueueUniquePeriodicWork(
      COMPUTE_WORK,
      ExistingPeriodicWorkPolicy.KEEP,
      PeriodicWorkRequestBuilder<AiComputeWorker>(PERIOD.inWholeHours, TimeUnit.HOURS).addTag(TAG).build(),
    )
  }

  /**
   * "Scarica ora" e il cambio di ruolo: un lavoro accelerato (D17) **per il modello chiesto** ([model]; senza, tutti quelli voluti). Un nome per
   * modello: due richieste di modelli diversi non si buttano a vicenda. Il vincolo di rete e' una rete qualunque per "Scarica ora" (la rete la
   * giudica il cancello di rete dentro il lavoro: dati mobili con la conferma, mai roaming) e una rete non a consumo per il cambio di ruolo
   * (R11). Accelerato solo da Android 12 (MO-9): prima WorkManager lo vuole in primo piano e lancia.
   */
  fun fetchNow(context: Context, trigger: ModelTrigger, model: ModelId? = null) {
    val builder = OneTimeWorkRequestBuilder<ModelFetchWorker>()
      .setInputData(workDataOf(KEY_TRIGGER to trigger.name, KEY_MODEL to model?.value))
      .setConstraints(if (trigger == ModelTrigger.ROLE_CHANGED) LanConstraints.unmetered() else LanConstraints.anyNetwork())
      .addTag(TAG)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
    val name = if (model != null) "$FETCH_NOW_WORK-${model.value}" else FETCH_NOW_WORK
    WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, builder.build())
  }

  /** "Indicizza ora" (D22): una richiesta dell'utente; una sola alla volta (un secondo tocco mentre gira o aspetta non ne mette un altro). */
  fun indexNow(context: Context) {
    WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(COMPUTE_NOW_WORK, ExistingWorkPolicy.KEEP, computeRequest(user = true))
  }

  /**
   * Dopo la sincronizzazione o la preparazione con novita': un giro del calcolo. Ha un nome suo, **diverso da "Indicizza ora"**: prima i due
   * dividevano `COMPUTE_NOW_WORK` e un giro delle novita' in coda assorbiva il tocco dell'utente (o il contrario). Lavoro unico (`KEEP`): le
   * novita' arrivate mentre gira le raccoglie la tappa dopo.
   */
  fun afterNews(context: Context) {
    WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(COMPUTE_NEWS_WORK, ExistingWorkPolicy.KEEP, computeRequest(user = false))
  }

  /**
   * **La tappa dopo** (IX-1). Un lavoro che ha lasciato altro da fare (la tappa e' finita per tempo) la chiede dal proprio interno: il nome e' quello
   * della sua catena (la richiesta dell'utente continua come richiesta dell'utente) e la politica e' `APPEND_OR_REPLACE`, che si accoda a chi sta
   * girando. Con `KEEP` WorkManager la buttava, perche' il lavoro in corso e' "non finito": "Indicizza ora" analizzava circa 1.300 foto e si fermava.
   */
  fun continueCompute(context: Context, user: Boolean, delayMs: Long = 0, constraints: Constraints = Constraints.NONE) {
    val name = if (user) COMPUTE_NOW_WORK else COMPUTE_NEXT_WORK
    WorkManager.getInstance(context.applicationContext)
      .enqueueUniqueWork(name, ExistingWorkPolicy.APPEND_OR_REPLACE, computeRequest(user, delayMs, constraints))
  }

  /**
   * **Un'attesa delle regole riprogramma** (IX-1), come il backup: il lavoro dopo con il vincolo o il ritardo esatti ([followUpOf]). Prima il
   * calcolo ripartiva solo quando il periodico (ogni 6 ore) o una novita' cadevano per caso dentro la fascia e in carica.
   */
  fun afterWait(context: Context, user: Boolean, wait: WaitReason, now: LocalDateTime = LocalDateTime.now()) {
    val follow = followUpOf(wait, now) ?: return
    continueCompute(context, user, follow.delay.inWholeMilliseconds, follow.constraints())
  }

  /** Cosa chiedere a WorkManager dopo un'attesa: quanto aspettare e quali vincoli. */
  data class FollowUp(val delay: Duration, val charging: Boolean = false, val batteryNotLow: Boolean = false) {
    fun constraints(): Constraints = Constraints.Builder().setRequiresCharging(charging).setRequiresBatteryNotLow(batteryNotLow).build()
  }

  /**
   * Il lavoro dopo per un'attesa delle regole, o null se non serve programmare niente (niente polling: il NAS spento, il profilo inattivo, il
   * congelamento, i limiti di Android aspettano il giro periodico o una novita'). Esatto dove si puo' (l'inizio della fascia, la carica, la
   * batteria), con almeno [MIN_RETRY] di ritardo perche' un'attesa che non cambia non diventi un ciclo continuo. Pura, provata su JVM.
   */
  fun followUpOf(wait: WaitReason, now: LocalDateTime): FollowUp? = when (wait) {
    is WaitReason.OutsideWindow -> FollowUp(delay = maxOf(JavaDuration.between(now, wait.nextStart).toMillis(), 0L).toDuration(DurationUnit.MILLISECONDS) + MIN_RETRY)
    WaitReason.NeedsPower -> FollowUp(delay = MIN_RETRY, charging = true)
    is WaitReason.BatteryLow, WaitReason.PowerSaver -> FollowUp(delay = MIN_RETRY, batteryNotLow = true)
    is WaitReason.Thermal -> FollowUp(delay = THERMAL_RETRY)
    else -> null
  }

  private fun computeRequest(user: Boolean, delayMs: Long = 0, constraints: Constraints = Constraints.NONE): OneTimeWorkRequest =
    OneTimeWorkRequestBuilder<AiComputeWorker>()
      .setInputData(workDataOf(KEY_USER to user))
      .setConstraints(constraints)
      .apply { if (delayMs > 0) setInitialDelay(delayMs, TimeUnit.MILLISECONDS) }
      .addTag(TAG)
      .build()
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
  private val mark = AiStageMark(File(noBackup, AiStageMark.FILE))

  suspend fun compute(user: Boolean): StageOutcome {
    // DG-3: la tappa di prima e' morta? E se Android l'ha chiusa per memoria (E-A16), `AI_KILLED_MEMORY` in Diagnostica.
    reportInterruptedStage()
    mark.begin(graph.platform.deviceNow())
    val outcome = try {
      stage(user)
    } finally {
      mark.end()
    }
    // IX-2: cio' che la pagina Ricerca e modelli dira' dopo, anche in un altro processo (le attese delle regole non passavano dal motore).
    ai.noteStage(outcome)
    // DG-4: poco spazio e vettori non validi si perdevano con il processo della notte: ora arrivano anche dagli eventi.
    ai.computeCounters()?.let { c ->
      if (c.storageLow.get() > 0) graph.events.code(AiDiag.AI_STORAGE_LOW)
      if (c.vectorsInvalid.get() > 0) graph.events.code(AiDiag.AI_VECTOR_INVALID)
    }
    return outcome
  }

  private suspend fun reportInterruptedStage() {
    val markedAt = mark.interruptedAt() ?: return
    val exits = runCatching { graph.platform.healthReader.read().lastExitReasons }.getOrDefault(emptyList())
    if (AiKilledByMemory.wasKilled(markedAt, exits)) graph.events.code(AiDiag.AI_KILLED_MEMORY)
  }

  private suspend fun stage(user: Boolean): StageOutcome {
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

/**
 * I modelli che il lavoro dei modelli prepara (Fase 6 P11, Fase 8 P13): il 270M e il ritaglio sempre, il 440M sugli indicizzatori e, **solo dove Persone e'
 * attiva qui** ([facesActive]: accesa, non esclusa, avviso visto), YuNet e SFace (D8). `ModelCatalog.needed` e' l'unico elenco. Prima dell'attivazione i
 * due modelli dei volti non si estraggono dall'APK; alla disattivazione o all'esclusione li toglie la cancellazione locale.
 */
internal fun modelsWanted(indexer: Boolean, facesActive: Boolean): List<ModelId> {
  val roles = if (indexer) setOf(dev.pampa.mycloudgallery.core.trust.data.DeviceRole.INDEXER) else emptySet()
  val features = if (facesActive) setOf(dev.pampa.mycloudgallery.core.models.contract.ModelFeature.FACES) else emptySet()
  return ModelCatalog.needed(roles, features).map { it.id }
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
    // IX-1: la tappa dopo si accoda a questo lavoro (nome della sua catena, APPEND_OR_REPLACE), e un'attesa delle regole riprogramma.
    when (outcome) {
      is StageOutcome.Yielded -> AiWorkScheduling.continueCompute(applicationContext, user)
      is StageOutcome.Waiting -> AiWorkScheduling.afterWait(applicationContext, user, outcome.reason)
      is StageOutcome.Stopped -> AiWorkScheduling.afterWait(applicationContext, user, outcome.reason)
      else -> Unit
    }
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
  /** Fase 8 (P13, D8): i modelli dei volti servono solo dove Persone e' attiva qui (`ModelCatalog.needed` con la funzione `FACES`). */
  private val faces: dev.pampa.mycloudgallery.faces.FacesGraph,
) : CoroutineWorker(appContext, params) {

  override suspend fun doWork(): Result {
    if (!gallery.backgroundWorkAllowed) return Result.success()
    // MO-5: in un processo appena nato la famiglia non e' ancora pronta e `runIfActive` risponderebbe null, consumando il giro.
    gallery.awaitReady(AiComputeWorker.COLD_START_WAIT)
    val trigger = inputData.getString(AiWorkScheduling.KEY_TRIGGER)?.let { name -> ModelTrigger.entries.firstOrNull { it.name == name } }
      ?: ModelTrigger.RULES
    val asked: ModelId? = inputData.getString(AiWorkScheduling.KEY_MODEL)?.let { name -> ModelCatalog.ALL.firstOrNull { it.id.value == name }?.id }

    // MO-9: in background lo scaricamento segue le regole di AI_COMPUTE (pesante: fascia, carica, caldo, Wi-Fi di casa). Cio' che l'utente chiede
    // ("Scarica ora") o il cambio di ruolo che ha appena fatto non le aspetta; la rete la giudica sempre il cancello di rete dei modelli.
    if (trigger == ModelTrigger.RULES && !rulesAllow()) {
      graph.events.result(DiagJob.MODEL_FETCH, DiagJobResult.WAITING)
      return Result.success()
    }

    val results = gallery.runIfActive {
      // MO-7: una verifica per processo (ogni apertura verifica comunque), e nessun `ensure` per cio' che e' gia' pronto.
      runCatching { stack.localModels.refreshOnce() }
      val states = stack.localModels.manager.states.value
      val wanted = if (asked != null) {
        listOf(asked)
      } else {
        // Il 270M e il ritaglio del soggetto sempre (nell'APK, una copia locale fra i modelli verificati, senza rete), il 440M sugli indicizzatori e, solo dove
        // Persone e' attiva qui, YuNet e SFace (anch'essi nell'APK): `ModelCatalog.needed` e' l'unico elenco.
        modelsWanted(indexer = stack.ai.indexer.value, facesActive = faces.activeHere())
      }
      val out = ArrayList<Pair<ModelId, EnsureResult>>()
      // I4: gli 8 minuti sono del lavoro, non di ogni modello in fila (due modelli da 8 minuti superano i circa 10 di un lavoro e il secondo
      // verrebbe fermato a meta' dal sistema): il tempo che resta passa al modello dopo, e quello che non entra riprende al giro dopo.
      val started = System.nanoTime()
      for (id in wanted.filter { states[it] !is ModelState.Ready }) {
        val left = BUDGET - (System.nanoTime() - started).nanoseconds
        if (left <= Duration.ZERO) break
        // R10: ogni modello e' isolato: l'errore di uno non salta gli altri.
        val result = try {
          stack.localModels.manager.ensure(id, trigger, left)
        } catch (e: kotlinx.coroutines.CancellationException) {
          throw e
        } catch (e: Exception) {
          EnsureResult.Failed(ModelFailCode.FETCH_FAILED)
        }
        out += id to result
      }
      // D18, MO-4: il primo dispositivo che ha il modello lo mette sul NAS per gli altri; mai un'eccezione.
      runCatching { stack.localModels.shareOnNas() }
      out
    }
    GalleryLog.d(TAG) { "modelli: ${results?.map { it.second.javaClass.simpleName }}" }
    // DG-2: i guasti dei modelli arrivano in Diagnostica con il loro codice (solo il codice, D40) e il lavoro dice com'e' andato.
    if (results != null) report(results)
    if (results != null && results.all { it.second is EnsureResult.Ready } && stack.ai.indexer.value) {
      AiWorkScheduling.afterNews(applicationContext)
    }
    return Result.success()
  }

  /** Le regole di AI_COMPUTE lasciano partire un lavoro in background che usa la rete? (Il solo buffer locale non basta per scaricare.) */
  private suspend fun rulesAllow(): Boolean {
    val decision = graph.stageRules().decide(WorkRequest.Background(BackgroundActivity.AI_COMPUTE), running = false)
    return decision is RuleDecision.Run && decision.scope == RunScope.FULL
  }

  private fun report(results: List<Pair<ModelId, EnsureResult>>) {
    var failed = false
    for ((_, result) in results) {
      if (result !is EnsureResult.Failed) continue
      when (result.code) {
        ModelFailCode.FETCH_FAILED -> graph.events.code(ModelDiag.AI_MODEL_FETCH_FAILED)
        ModelFailCode.MISMATCH, ModelFailCode.VERIFY_FAILED -> graph.events.code(ModelDiag.AI_MODEL_MISMATCH)
        ModelFailCode.NO_SPACE -> graph.events.code(ModelDiag.AI_MODEL_NO_SPACE)
        // Serve la conferma sui dati mobili: la pagina la chiede, non e' un guasto.
        ModelFailCode.NETWORK_REFUSED -> Unit
      }
      if (result.code != ModelFailCode.NETWORK_REFUSED) failed = true
    }
    if (failed) graph.events.jobFailed(DiagJob.MODEL_FETCH)
    else if (results.any { it.second !is EnsureResult.Ready }) graph.events.result(DiagJob.MODEL_FETCH, DiagJobResult.WAITING)
    else if (results.isNotEmpty()) graph.events.result(DiagJob.MODEL_FETCH, DiagJobResult.FINISHED)
  }

  companion object {
    const val TAG = "ModelFetchWorker"
    val BUDGET: Duration = 8.minutes
  }
}
