package dev.pampa.mycloudgallery.work

import dev.pampa.mycloudgallery.backup.BackupGraph
import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.BackupRunResult
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.GuardScope
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.core.backup.contract.SafetyVerifier
import dev.pampa.mycloudgallery.core.backup.contract.VerifyReport
import dev.pampa.mycloudgallery.core.backup.contract.VerifyScope
import dev.pampa.mycloudgallery.core.backup.upload.BackupStageOutcome
import dev.pampa.mycloudgallery.core.backup.upload.UploadStages
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.RuleDecision
import dev.pampa.mycloudgallery.core.rules.RuleEvaluator
import dev.pampa.mycloudgallery.core.rules.SystemStateProvider
import dev.pampa.mycloudgallery.core.rules.WorkRequest
import dev.pampa.mycloudgallery.core.rules.run.BatchResult
import dev.pampa.mycloudgallery.core.rules.run.StageOutcome
import dev.pampa.mycloudgallery.core.rules.run.StageRules
import dev.pampa.mycloudgallery.core.rules.run.StageStats
import dev.pampa.mycloudgallery.core.rules.run.StagedRun
import dev.pampa.mycloudgallery.core.rules.settings.QuickPhotoProtection
import dev.pampa.mycloudgallery.core.rules.settings.RuleSettingsStore
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficLedger
import dev.pampa.mycloudgallery.feature.diagnostics.recording.DiagJob
import dev.pampa.mycloudgallery.graph.AppGraph
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException

/**
 * Le regole di una corsa "Proteggi subito le foto nuove" (D34, Q7 (a)): le impostazioni dell'utente con la sola `PHOTO_BACKUP` sostituita
 * da [QuickPhotoProtection.RULE] (nessuna fascia, anche a batteria sopra la soglia, solo il Wi-Fi di casa). Il valutatore e' lo stesso
 * di tutti i lavori; qui cambia solo l'insieme di regole. Spenta la regola, la corsa non parte.
 */
class QuickPhotoStageRules(
  private val settings: RuleSettingsStore,
  private val evaluator: RuleEvaluator,
  private val state: SystemStateProvider,
  private val ledger: TrafficLedger,
) : StageRules {
  override suspend fun decide(request: WorkRequest, running: Boolean): RuleDecision =
    QuickPhotoProtection.evaluate(evaluator, settings.current(), state.current(), ledger.currentUsage(), running)

  override suspend fun isPluggedIn(): Boolean = state.current().power.pluggedIn
}

/** Che cosa ha fatto una tappa delle verifiche e del guardiano, solo numeri. */
class SafetyStageOutcome(
  val stages: List<StageOutcome>,
  val verified: Int,
  val mismatched: Int,
  val guardCreated: Int,
  val problem: BackupProblemCode?,
) {
  /** Almeno una tappa ha lasciato altro da fare: il lavoro dopo riprende. */
  val yielded: Boolean get() = stages.any { it is StageOutcome.Yielded }

  /** Il motivo per cui la prima tappa che non e' partita aspetta, se c'e'. */
  val waiting: StageOutcome.Waiting? get() = stages.filterIsInstance<StageOutcome.Waiting>().firstOrNull()

  override fun toString(): String = "SafetyStageOutcome(${stages.size} tappe, verificate=$verified, non integre=$mismatched, copie=$guardCreated)"
}

/**
 * Il backup nei **lavori a tappe** (Fase 5A, P7; docs/fase5-progetto.md §2.8, D11, D34, F4-D22): ogni tappa e' un `StagedRun` di core-rules
 * (regole prima di partire e a ogni lotto, traffico registrato subito, otto minuti al massimo) dentro [BackupGraph.exclusive] (motore,
 * verifiche e guardiano uno alla volta). Testabile senza WorkManager.
 *
 * - [photos] e [videos]: `PHOTO_BACKUP` (leggera) e `VIDEO_BACKUP` (pesante, solo Wi-Fi di casa); [quick]: D34 con le sue regole;
 * - [user]: il primo backup e "Fai ora", `UserInitiated` (scavalca fascia e carica; sui dati mobili chiede conferma, mai a caso);
 * - [safety]: il guardiano (D9), poi le verifiche, `FULL_HASH` e la rotazione (D14) con la regola dell'attivita' del tipo di media: le
 *   copie leggere sotto `PHOTO_BACKUP`, quelle pesanti sotto `VIDEO_BACKUP`. Le verifiche leggono soltanto: non vedono il congelamento
 *   ne' la pausa dell'utente (R4); girano anche se il guardiano fallisce (R1, [guardThenVerify]).
 */
class BackupStages(private val graph: AppGraph, private val backup: BackupGraph) {

  /** RE-3: il motore delle regole preceduto dall'attesa della rete (un solo punto per tutti i lavori a tappe). */
  private fun rules(): StageRules = graph.stageRules()

  private fun staged(request: WorkRequest, rules: StageRules = rules()) = StagedRun(
    request = request,
    rules = rules,
    ledger = graph.trafficLedger,
    checkpoints = NoCheckpoints,
  )

  /** Una tappa di backup di [kind] (lavoro normale, o `trigger` dell'utente con [confirmed]). Null: niente da fare / il backup non parte. */
  suspend fun round(kind: BackupKind, trigger: BackupTrigger, userEstimate: Long? = null, confirmed: Boolean = false): BackupStageOutcome? {
    if (!backup.start()) return null
    val parts = backup.parts ?: return null
    val activity = if (kind == BackupKind.PHOTOS) BackgroundActivity.PHOTO_BACKUP else BackgroundActivity.VIDEO_BACKUP
    val userInitiated = trigger == BackupTrigger.FIRST_BACKUP || trigger == BackupTrigger.USER_NOW
    val request = if (userInitiated) {
      WorkRequest.UserInitiated(activity, userEstimate, confirmed, writesToNas = true)
    } else {
      WorkRequest.Background(activity, writesToNas = true)
    }
    val stageRules = if (trigger == BackupTrigger.QUICK_NEW_PHOTOS) {
      graph.settled(QuickPhotoStageRules(backup.ruleSettings, RuleEvaluator(), graph.stateProvider, graph.trafficLedger))
    } else {
      rules()
    }
    // Il primo backup lo avvia l'utente (cartella del dispositivo, D7); la scansione dell'area privata deve essere finita (§2.11 punto 1).
    if (backup.settings.deviceFolder.value == null || !backup.privateAreaScanned()) return null
    // Il cancello chiuso (D12, emergenza, congelamento, sola lettura) non e' "tutto al sicuro": non si tocca la memoria. La pausa dell'utente
    // (R4) e' un "Ferma": il giro dice `STOPPED`, e nemmeno una corsa del lavoro a tappe la scavalca.
    val gate = backup.gate.stateOf(parts.share)
    if (!gate.open) {
      val stop = if (gate == BackupGateState.PAUSED) RunStop.STOPPED else RunStop.GATE
      return BackupStageOutcome(StageOutcome.Finished(StageStats(0, 0L, 0L, Duration.ZERO, resumed = false)), BackupRunResult.nothing(stop, gate.problem))
    }
    backup.refreshArea()
    val outcome = backup.exclusive {
      // Niente da fare (nessun media nuovo in una sorgente, niente a meta' nel diario): il backup e' al passo.
      if (!backup.hasWork(kind, trigger)) return@exclusive idle()
      UploadStages.stage(staged(request, stageRules), parts.engine, kind, trigger)
    } ?: return null
    afterRound(kind, outcome)
    // RE-4: l'esito della tappa nell'anello della Diagnostica (solo il lavoro e numeri; un giro a vuoto non scrive niente).
    graph.events.stage(diagJob(kind, trigger), outcome.stage)
    return outcome
  }

  private fun diagJob(kind: BackupKind, trigger: BackupTrigger): DiagJob = when {
    trigger == BackupTrigger.QUICK_NEW_PHOTOS -> DiagJob.QUICK_PHOTOS
    trigger == BackupTrigger.FIRST_BACKUP || trigger == BackupTrigger.USER_NOW -> DiagJob.BACKUP_USER
    kind == BackupKind.PHOTOS -> DiagJob.PHOTO_BACKUP
    else -> DiagJob.VIDEO_BACKUP
  }

  /** La tappa di quando non c'e' niente da caricare. */
  private fun idle(): BackupStageOutcome =
    BackupStageOutcome(StageOutcome.Finished(StageStats(0, 0L, 0L, Duration.ZERO, resumed = false)), BackupRunResult.nothing(RunStop.COMPLETE))

  suspend fun photos(): BackupStageOutcome? = round(BackupKind.PHOTOS, BackupTrigger.SCHEDULED)

  suspend fun videos(): BackupStageOutcome? = round(BackupKind.VIDEOS, BackupTrigger.SCHEDULED)

  /** "Proteggi subito le foto nuove" (D34, Q7 (a)): solo foto della fotocamera, Wi-Fi di casa, anche fuori fascia. */
  suspend fun quick(): BackupStageOutcome? = round(BackupKind.PHOTOS, BackupTrigger.QUICK_NEW_PHOTOS)

  /** Bilancio di un giro: memoria (ultimo problema, ultimo giro finito, battito), indice, cancelli e stato che le schermate leggono. */
  private suspend fun afterRound(kind: BackupKind, outcome: BackupStageOutcome) {
    val last = outcome.last
    if (last != null) {
      val memory = backup.memory
      when (last.stoppedBy) {
        RunStop.COMPLETE -> {
          memory.setProblem(null)
          backup.clock.nasNowMs()?.let { memory.markComplete(it) }
          beat(kind)
        }
        RunStop.BUDGET -> {
          memory.setProblem(null)
          if (last.done + last.linked > 0) beat(kind)
        }
        RunStop.NAS, RunStop.PHONE -> {
          // Il motivo che le schermate mostrano: pieno, quota, password, sola lettura, permessi del telefono.
          memory.setProblem(last.problem)
          last.problem?.let { graph.events.backupProblem(it.name) }
          if (last.problem == BackupProblemCode.READ_ONLY_AREA) backup.parts?.share?.let { backup.noteReadOnly(it) }
        }
        RunStop.GATE, RunStop.STOPPED -> Unit
      }
      backup.lastRound.value = BackupGraph.RoundNumbers(
        last.done, last.linked, last.gone, last.failed, last.waiting, last.bytesUploaded, last.bytesReread, last.stoppedBy.code,
      )
      GalleryLog.d(TAG) {
        "giro ${kind.code}: fatti=${last.done} collegati=${last.linked} persi=${last.gone} falliti=${last.failed} aperti=${last.waiting} stop=${last.stoppedBy.code}"
      }
    }
    // I record e le miniature dei media caricati vanno all'indice (D10): sulle share vere restano solo locali (D31), mai un errore del giro.
    runCatching { backup.flushIndex() }
    backup.kick()
  }

  private suspend fun beat(kind: BackupKind) {
    val now = graph.platform.deviceNow()
    if (kind == BackupKind.PHOTOS) backup.memory.photoBeat.beat(now) else backup.memory.videoBeat.beat(now)
  }

  // --- Verifiche e guardiano ----------------------------------------------------------------------------------------------------

  /**
   * Il guardiano, poi le verifiche (non verificate, `FULL_HASH`, rotazione) leggere e pesanti. Le verifiche non vengono mai fermate dal
   * congelamento (leggono soltanto); il guardiano scrive nell'area privata, quindi si ferma con il cancello e con il congelamento (D10).
   */
  suspend fun safety(): SafetyStageOutcome? {
    if (!backup.start()) return null
    val parts = backup.parts ?: return null
    backup.refreshArea()
    return backup.exclusive {
      val stages = ArrayList<StageOutcome>()
      var created = 0
      var verified = 0
      var mismatched = 0
      var problem: BackupProblemCode? = null

      // 1. Il guardiano (D9): copie private dei media la cui sola copia e' nell'area comune. Solo dal catalogo, mai da dati del NAS.
      //    Scrive: con la pausa dell'utente (R4), il congelamento o il cancello chiuso non parte nemmeno. Se fallisce con un errore che
      //    nessuno prevedeva (R1) lo dice (problema visibile) e le verifiche girano lo stesso.
      guardThenVerify(
        guard = {
          if (backup.gate.stateOf(parts.share).open && backup.guardianHasWork()) {
            val run = staged(WorkRequest.Background(BackgroundActivity.PHOTO_BACKUP, writesToNas = true))
            stages += run.run({ scope, _ ->
              val report = parts.guardian.ensure(GuardScope.All, SLICE)
              created += report.created
              problem = problem ?: report.problem
              // La copia lato server non passa dal telefono: nessun traffico da registrare (se ripiega, i byte sono nel conto del giro).
              scope.record(0)
              BatchResult(hasMore = report.stoppedBy == RunStop.BUDGET)
            })
          }
        },
        onGuardError = { error ->
          GalleryLog.e(TAG, error) { "guardiano non riuscito" }
          problem = problem ?: BackupProblemCode.UNEXPECTED
          backup.noteUnexpectedError()
        },
        // 2. Le verifiche: le copie leggere con la regola delle foto, quelle pesanti con quella dei video.
        verify = {
          for ((verifier, activity) in listOf(parts.lightVerifier to BackgroundActivity.PHOTO_BACKUP, parts.heavyVerifier to BackgroundActivity.VIDEO_BACKUP)) {
            val totals = VerifyTotals()
            stages += verifyStage(verifier, activity, totals)
            verified += totals.verified
            mismatched += totals.mismatched
            problem = problem ?: totals.problem
          }
        },
      )
      backup.kick()
      stages.forEach { graph.events.stage(DiagJob.BACKUP_SAFETY, it) }
      problem?.let { graph.events.backupProblem(it.name) }
      SafetyStageOutcome(stages, verified, mismatched, created, problem)
    }
  }

  private class VerifyTotals {
    var verified = 0
    var mismatched = 0
    var problem: BackupProblemCode? = null

    fun add(report: VerifyReport) {
      verified += report.verified
      mismatched += report.mismatched
      problem = problem ?: report.problem
    }
  }

  private suspend fun verifyStage(verifier: SafetyVerifier, activity: BackgroundActivity, totals: VerifyTotals): StageOutcome {
    val order = listOf(VerifyScope.Unverified, VerifyScope.FullHash, VerifyScope.Rotation)
    var index = 0
    // Una rilettura non scrive sul NAS: nessuna richiesta `writesToNas`, il congelamento non la ferma (leggere durante un incidente serve).
    val run = staged(WorkRequest.Background(activity))
    return run.run({ scope, _ ->
      while (index < order.size) {
        val report = verifier.verify(order[index], SLICE)
        totals.add(report)
        scope.record(report.bytesRead)
        when (report.stoppedBy) {
          RunStop.BUDGET -> return@run BatchResult(hasMore = true)
          RunStop.COMPLETE -> index++
          // Cancello chiuso, NAS o telefono non disponibili: le altre verifiche non andrebbero meglio.
          else -> index = order.size
        }
        if (index < order.size && !scope.proceed()) return@run BatchResult(hasMore = true)
      }
      BatchResult(hasMore = false)
    })
  }

  companion object {
    private const val TAG = "BackupStages"

    /**
     * R1: il guardiano e poi le verifiche. Un guardiano che fallisce con un'eccezione inattesa (non l'annullamento, non un `Error`) non
     * toglie le verifiche, che leggono soltanto e servono proprio quando qualcosa non va: [onGuardError] dice l'errore (e lo rende
     * visibile) e [verify] gira comunque. Pura, provata su JVM.
     */
    internal suspend fun guardThenVerify(
      guard: suspend () -> Unit,
      onGuardError: suspend (Exception) -> Unit,
      verify: suspend () -> Unit,
    ) {
      try {
        guard()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        onGuardError(e)
      }
      verify()
    }

    /** Un lotto: abbastanza lungo per lavorare, abbastanza breve per fermarsi presto se le regole cambiano (come i lavori del NAS). */
    val SLICE: Duration = 60.seconds
  }
}
