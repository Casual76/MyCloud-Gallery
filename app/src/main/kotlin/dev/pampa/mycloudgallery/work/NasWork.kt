package dev.pampa.mycloudgallery.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.index.contract.QualityLevel
import dev.pampa.mycloudgallery.core.index.contract.UnitPriority
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanMode
import dev.pampa.mycloudgallery.core.nasops.contract.PurgeStop
import dev.pampa.mycloudgallery.core.personal.contract.PersonalStop
import dev.pampa.mycloudgallery.core.prepare.contract.PrepControl
import dev.pampa.mycloudgallery.core.prepare.contract.PrepVerdict
import dev.pampa.mycloudgallery.feature.diagnostics.recording.DiagJob
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.NetworkClass
import dev.pampa.mycloudgallery.core.rules.Pace
import dev.pampa.mycloudgallery.core.rules.WorkRequest
import dev.pampa.mycloudgallery.core.rules.run.BatchResult
import dev.pampa.mycloudgallery.core.rules.run.CheckpointStore
import dev.pampa.mycloudgallery.core.rules.run.DetectionCap
import dev.pampa.mycloudgallery.core.rules.run.StageOutcome
import dev.pampa.mycloudgallery.core.rules.run.StagedRun
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.graph.AppGraph
import dev.pampa.mycloudgallery.graph.HeartbeatStore
import dev.pampa.mycloudgallery.nas.NasGraph
import dev.pampa.mycloudgallery.nas.NoUnitPriority
import dev.pampa.mycloudgallery.notify.NasDailySummaryWorker
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * I lavori del NAS nella Libreria (Fase 4A, P11; docs/fase4-progetto.md §1.4, §2.8, D22). Tutti **a tappe** (`StagedRun`
 * di P9: regole prima di partire, regole a meta' lotto, traffico registrato subito, 8 minuti al massimo) e dentro
 * `GalleryGraph.runIfActive` (l'azzeramento li ferma e li aspetta, R7):
 *
 * - [NasScanWorker] (`INDEX_SYNC`): verifica delle aree, rilevamento dei file nuovi (fuori casa al massimo 50 MB per giro,
 *   `DetectionCap`); ogni 6 ore e a ogni apertura dell'app se l'ultima verifica ha piu' di 15 minuti;
 * - [IndexSyncWorker] (`INDEX_SYNC`): teste e segmenti secondo la qualita' (meta, S, M); dopo ogni scansione e ogni 6 ore;
 * - [PreparationWorker] (`PREPARATION`, scrive sul NAS: `writesToNas`): solo i Preparatori, le regole decidono quando;
 * - [HqPreviewsWorker] (`HQ_PREVIEWS`): l'HQ della replica, solo con la qualita' "Alta";
 * - [NasDailySummaryWorker]: il **riepilogo giornaliero** dei problemi (proposta 12; congelamento compreso, risposta Q6).
 *
 * Fase 4B (P16, §1.4, §2.7–§2.9):
 * - [NasTrashPurgeWorker] (`INDEX_SYNC`, scrive sul NAS): la pulizia del cestino scaduto, solo dal diario locale, una volta al giorno;
 * - [IndexMaintenanceWorker] (`INDEX_SYNC`, scrive sul NAS): compattazione dell'indice e pulizia dei propri file, una volta al giorno;
 * - [PersonalSyncWorker] (`INDEX_SYNC`): i dati personali fra i dispositivi dello stesso account, ogni 6 ore e dopo ogni verifica
 *   (oltre all'accorpamento di 30 s dopo una modifica con l'app aperta, `NasOrganizer.personalChanged`);
 * - le operazioni sui file non sono lavori di WorkManager: le riprende `NasOpsJobService` (UIDT per quelle lunghe avviate dall'utente).
 *
 * I punti di ripresa stanno nel database (cursore della scansione, `HeadMark` e stati dei segmenti, unita' prenotate):
 * le tappe non hanno un punto proprio ([NoCheckpoints]).
 */
object NasWorkScheduling {

  const val SCAN_WORK = "nas-scan"
  const val SCAN_NOW_WORK = "nas-scan-now"
  const val SYNC_WORK = "nas-index-sync"
  const val SYNC_NOW_WORK = "nas-index-sync-now"
  const val PREP_WORK = "nas-preparation"
  const val PREP_NOW_WORK = "nas-preparation-now"
  const val HQ_WORK = "nas-hq-previews"
  const val SUMMARY_WORK = "nas-daily-summary"

  // Fase 4B (P16): cestino, manutenzione dell'indice, dati personali.
  const val TRASH_PURGE_WORK = "nas-trash-purge"
  const val TRASH_PURGE_NOW_WORK = "nas-trash-purge-now"
  const val MAINTENANCE_WORK = "nas-index-maintenance"
  const val MAINTENANCE_NOW_WORK = "nas-index-maintenance-now"
  const val PERSONAL_WORK = "nas-personal-sync"
  const val PERSONAL_NOW_WORK = "nas-personal-sync-now"
  const val TAG = "nas"

  val PERIOD: Duration = 6.hours
  val SUMMARY_PERIOD: Duration = 24.hours

  /** Pulizia del cestino e manutenzione dell'indice: al massimo una volta al giorno (§2.8). */
  val DAILY_PERIOD: Duration = 24.hours

  /** A ogni apertura dell'app si verifica di nuovo solo se l'ultima verifica ha piu' di tanto (§1.4). */
  val VERIFY_ON_OPEN_AFTER: Duration = 15.minutes

  /** RE-11: una rete qualunque, anche senza internet (il NAS e' in LAN): vedi [LanConstraints]. */
  private fun connected(): Constraints = LanConstraints.anyNetwork()

  /** I lavori periodici: idempotente (non sposta quelli gia' pianificati). Le regole decidono comunque se si parte. */
  fun ensure(context: Context) {
    val wm = WorkManager.getInstance(context.applicationContext)
    fun periodic(name: String, request: androidx.work.PeriodicWorkRequest) = wm.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.KEEP, request)
    periodic(SCAN_WORK, PeriodicWorkRequestBuilder<NasScanWorker>(PERIOD.inWholeHours, TimeUnit.HOURS).setConstraints(connected()).addTag(TAG).build())
    periodic(SYNC_WORK, PeriodicWorkRequestBuilder<IndexSyncWorker>(PERIOD.inWholeHours, TimeUnit.HOURS).setConstraints(connected()).addTag(TAG).build())
    periodic(PREP_WORK, PeriodicWorkRequestBuilder<PreparationWorker>(PERIOD.inWholeHours, TimeUnit.HOURS).setConstraints(connected()).addTag(TAG).build())
    periodic(HQ_WORK, PeriodicWorkRequestBuilder<HqPreviewsWorker>(PERIOD.inWholeHours, TimeUnit.HOURS).setConstraints(connected()).addTag(TAG).build())
    periodic(SUMMARY_WORK, PeriodicWorkRequestBuilder<NasDailySummaryWorker>(SUMMARY_PERIOD.inWholeHours, TimeUnit.HOURS).addTag(TAG).build())
    // Fase 4B: le regole decidono quando (INDEX_SYNC, §2.8); i lavori non partono senza le condizioni del §2.7 (cancello, ora del NAS).
    periodic(TRASH_PURGE_WORK, PeriodicWorkRequestBuilder<NasTrashPurgeWorker>(DAILY_PERIOD.inWholeHours, TimeUnit.HOURS).setConstraints(connected()).addTag(TAG).build())
    periodic(MAINTENANCE_WORK, PeriodicWorkRequestBuilder<IndexMaintenanceWorker>(DAILY_PERIOD.inWholeHours, TimeUnit.HOURS).setConstraints(connected()).addTag(TAG).build())
    periodic(PERSONAL_WORK, PeriodicWorkRequestBuilder<PersonalSyncWorker>(PERIOD.inWholeHours, TimeUnit.HOURS).setConstraints(connected()).addTag(TAG).build())
  }

  /**
   * Una verifica adesso (apertura dell'app, "aggiorna", un'operazione sui file finita), poi la sincronizzazione dell'indice e quella dei
   * dati personali (4B): senza spostare un giro gia' in coda.
   */
  fun scanNow(context: Context) {
    val wm = WorkManager.getInstance(context.applicationContext)
    wm.beginUniqueWork(SCAN_NOW_WORK, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<NasScanWorker>().setConstraints(connected()).addTag(TAG).build())
      .then(OneTimeWorkRequestBuilder<IndexSyncWorker>().setConstraints(connected()).addTag(TAG).build())
      .then(OneTimeWorkRequestBuilder<PersonalSyncWorker>().setConstraints(connected()).addTag(TAG).build())
      .enqueue()
  }

  /** La tappa dopo, per un lavoro che ha lasciato altro da fare ([StageOutcome.Yielded]). */
  fun continueLater(context: Context, name: String, request: androidx.work.OneTimeWorkRequest) {
    WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(name, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
  }
}

/** Nessun punto di ripresa proprio: la scansione, l'indice e la preparazione salvano il loro nel database. */
object NoCheckpoints : CheckpointStore<Unit> {
  override suspend fun load(): Unit? = null

  override suspend fun save(checkpoint: Unit) = Unit

  override suspend fun clear() = Unit
}

/**
 * Il protocollo delle tappe applicato al NAS: lotti brevi ([SLICE]) fra un controllo delle regole e l'altro, il traffico
 * registrato dopo ogni lotto (i lettori del NAS non lo dicono a meta' lotto), la fine quando il lavoro e' finito o la tappa
 * si ferma. Testabile senza WorkManager (`NasStagesTest`).
 */
class NasStages(private val graph: AppGraph, private val nas: NasGraph, private val noBackup: File) {

  val scanBeat = HeartbeatStore(File(noBackup, HEARTBEAT_SCAN))
  val syncBeat = HeartbeatStore(File(noBackup, HEARTBEAT_SYNC))
  val prepBeat = HeartbeatStore(File(noBackup, HEARTBEAT_PREP))

  private fun stage(request: WorkRequest, capped: Boolean = false) = StagedRun(
    request = request,
    // RE-3: il motore delle regole preceduto dall'attesa della rete (un solo punto per tutti i lavori a tappe).
    rules = graph.stageRules(),
    ledger = graph.trafficLedger,
    checkpoints = NoCheckpoints,
    roundCap = if (capped) DetectionCap::forNetwork else null,
  )

  /** Una tappa di scansione (`INDEX_SYNC`): verifica di tutte le aree, rilevamento con il tetto fuori casa (D22). */
  suspend fun scan(mode: ScanMode = ScanMode.Verify): StageOutcome {
    nas.start()
    var read = false
    val outcome = stage(WorkRequest.Background(BackgroundActivity.INDEX_SYNC), capped = true).run({ scope, _ ->
      val cap = DetectionCap.byteCap(scope.request, scope.run)
      val reports = nas.scan(mode, SLICE, detectionByteCap = cap) ?: return@run BatchResult(hasMore = false)
      scope.record(reports.sumOf { it.bytesRead })
      if (readSomething(reports)) read = true
      BatchResult(hasMore = reports.isEmpty() || reports.any { !it.complete && it.stoppedBy != null && it.stoppedBy !in FINAL_STOPS })
    })
    // NA-8: il battito dice "ho guardato il NAS"; una tappa che non ha letto niente (share negata, NAS spento) non lo e'. Prima batteva
    // lo stesso, e "fermo da N giorni" del riepilogo giornaliero non compariva mai.
    if (outcome is StageOutcome.Finished && read) scanBeat.beat(graph.platform.deviceNow())
    return outcome
  }

  /** Una tappa di sincronizzazione dell'indice (`INDEX_SYNC`): meta, miniature S e M secondo la qualita' (D16). */
  suspend fun sync(): StageOutcome {
    nas.start()
    val outcome = stage(WorkRequest.Background(BackgroundActivity.INDEX_SYNC)).run({ scope, _ ->
      // D23 (Q7): sui dati mobili solo metadati e cio' che e' sullo schermo; le miniature aspettano il Wi-Fi.
      val mobile = scope.network == NetworkClass.MOBILE || scope.network == NetworkClass.ROAMING
      val report = nas.syncIndex(SLICE, visibleOnly = mobile) ?: return@run BatchResult(hasMore = false)
      scope.record(report.bytesDownloaded)
      BatchResult(hasMore = !report.complete)
    })
    if (outcome is StageOutcome.Finished) syncBeat.beat(graph.platform.deviceNow())
    return outcome
  }

  /**
   * Una tappa di preparazione (`PREPARATION`, scrive sul NAS: con una share congelata non parte, D10). [priority] = le unita' da preparare
   * per prime (Fase 5A, D36: le copie del NAS che aspettano la miniatura per il confronto delle copie simili).
   */
  suspend fun prepare(priority: UnitPriority = NoUnitPriority): StageOutcome {
    nas.start()
    val outcome = stage(WorkRequest.Background(BackgroundActivity.PREPARATION, writesToNas = true)).run({ scope, _ ->
      val control = PrepControl {
        when {
          !scope.proceed() -> PrepVerdict.Stop()
          scope.pace == Pace.REDUCED -> PrepVerdict.Slow
          else -> PrepVerdict.Continue
        }
      }
      val result = nas.prepare(SLICE, control, priority) ?: return@run BatchResult(hasMore = false)
      scope.record(result.bytesRead)
      nas.flushOutbox()
      BatchResult(hasMore = result.stoppedBy == dev.pampa.mycloudgallery.core.prepare.contract.PrepStop.BUDGET)
    })
    if (outcome is StageOutcome.Finished) prepBeat.beat(graph.platform.deviceNow())
    return outcome
  }

  // --- Fase 4B (P16, §2.7, §2.8, §2.9) ---------------------------------------------------------------------------------------

  /**
   * Una tappa di pulizia del cestino scaduto (`INDEX_SYNC` + condizioni del §2.7, scrive sul NAS: con una share congelata non parte):
   * **solo dal diario locale** di questo dispositivo, 30 giorni di ora del NAS, tetti per giro, mai con un salto dell'ora (E-D4).
   */
  suspend fun purgeTrash(): StageOutcome? {
    nas.start()
    if (nas.organizer.parts == null) return null
    return stage(WorkRequest.Background(BackgroundActivity.INDEX_SYNC, writesToNas = true)).run({ scope, _ ->
      val report = nas.organizer.purgeTrash(SLICE) ?: return@run BatchResult(hasMore = false)
      scope.record(0)
      BatchResult(hasMore = report.stoppedBy == PurgeStop.BUDGET)
    })
  }

  /** Una tappa di manutenzione dell'indice: compattazione e pulizia dei propri file (M6), una volta al giorno per share. */
  suspend fun maintainIndex(): StageOutcome? {
    nas.start()
    if (nas.organizer.parts == null) return null
    return stage(WorkRequest.Background(BackgroundActivity.INDEX_SYNC, writesToNas = true)).run({ scope, _ ->
      val report = nas.organizer.maintainIndex(SLICE) ?: return@run BatchResult(hasMore = false)
      scope.record(0)
      BatchResult(hasMore = report.clean.stoppedBy == PurgeStop.BUDGET)
    })
  }

  /**
   * Una tappa di sincronizzazione dei dati personali (`INDEX_SYNC`, §2.9). Non e' `writesToNas`: anche con una share congelata si
   * leggono i dati degli altri dispositivi; se pubblicare lo decide la sincronizzazione (D31, D10, D32).
   */
  suspend fun syncPersonal(): StageOutcome? {
    nas.start()
    if (nas.organizer.parts?.personalSync == null) return null
    return stage(WorkRequest.Background(BackgroundActivity.INDEX_SYNC)).run({ scope, _ ->
      val report = nas.organizer.syncPersonal(SLICE) ?: return@run BatchResult(hasMore = false)
      scope.record(0)
      BatchResult(hasMore = report.stoppedBy == PersonalStop.BUDGET)
    })
  }

  /** Una tappa di scaricamento dell'HQ (`HQ_PREVIEWS`), solo con la qualita' "Alta" (D16). */
  suspend fun hqPreviews(): StageOutcome? {
    nas.start()
    if (nas.quality.value?.level != QualityLevel.HIGH) return null
    return stage(WorkRequest.Background(BackgroundActivity.HQ_PREVIEWS)).run({ scope, _ ->
      val report = nas.syncIndex(SLICE) ?: return@run BatchResult(hasMore = false)
      scope.record(report.bytesDownloaded)
      BatchResult(hasMore = !report.complete)
    })
  }

  companion object {
    /** Un lotto: abbastanza lungo per lavorare, abbastanza breve per fermarsi presto se le regole cambiano. */
    val SLICE: Duration = 60.seconds

    /** NA-8: almeno un'area e' stata elencata davvero in questi giri (una cartella letta): solo allora c'e' un battito da registrare. */
    internal fun readSomething(reports: List<dev.pampa.mycloudgallery.core.nasmedia.contract.ScanReport>): Boolean = reports.any { it.folders > 0 }

    const val HEARTBEAT_SCAN = "heartbeat-nas-scan"
    const val HEARTBEAT_SYNC = "heartbeat-nas-sync"
    const val HEARTBEAT_PREP = "heartbeat-nas-prep"

    /** Fermate che non si riprendono subito (congelata, non raggiungibile, tetto di giro): la tappa finisce qui. */
    private val FINAL_STOPS = setOf(
      dev.pampa.mycloudgallery.core.nasmedia.contract.ScanStop.FROZEN,
      dev.pampa.mycloudgallery.core.nasmedia.contract.ScanStop.UNAVAILABLE,
      dev.pampa.mycloudgallery.core.nasmedia.contract.ScanStop.BYTE_CAP,
    )
  }
}

/** La parte comune dei lavori del NAS: niente senza NAS, niente nella variante di misura, tutto dentro `runIfActive`. */
abstract class NasWorker(
  appContext: Context,
  params: WorkerParameters,
  protected val gallery: GalleryGraph,
  protected val graph: AppGraph,
) : CoroutineWorker(appContext, params) {

  protected abstract val tag: String

  /** Il lavoro, per l'anello della Diagnostica (solo il nome fisso, RE-4). */
  protected abstract val diagJob: DiagJob

  protected abstract suspend fun stage(stages: NasStages): StageOutcome?

  /**
   * Dopo la tappa si chiede un giro del confronto delle copie simili (Fase 5A, D36, §2.12): per la sincronizzazione dell'indice e quella dei
   * dati personali, che portano miniature e scelte nuove. A tappe, su `Dispatchers.Default`, dentro `runIfActive`; chi lo chiede non aspetta.
   */
  protected open val triggersSimilarCopies: Boolean = false

  /** Il lavoro della tappa dopo, se questa ha lasciato altro da fare. */
  protected abstract fun continuation(): Pair<String, androidx.work.OneTimeWorkRequest>?

  final override suspend fun doWork(): Result {
    val nas = gallery.nas ?: return Result.success()
    if (!gallery.backgroundWorkAllowed) return Result.success()
    // D-7 (prove sul campo): in un processo appena nato (il giro periodico parte di solito a processo morto) lo stato del dispositivo puo' passare
    // un attimo per "non configurato" e `runIfActive` rispondeva "niente da fare": il lavoro finiva consumato. Si aspetta, poco, la famiglia pronta.
    gallery.awaitReady(COLD_START_WAIT)
    val outcome = try {
      gallery.runIfActive { NasStages(graph, nas, applicationContext.noBackupFilesDir).let { stage(it) } }
    } catch (e: kotlinx.coroutines.CancellationException) {
      // Android ha fermato il lavoro: il motivo si salva (prima non si leggeva) e, se e' un limite, arriva alle regole (RE-4, RE-7).
      StopReports.report(graph, diagJob, StopReports.reasonOf(this))
      throw e
    } catch (e: Exception) {
      GalleryLog.e(tag, e) { "tappa non riuscita" }
      graph.events.jobFailed(diagJob)
      return Result.success()
    }
    GalleryLog.d(tag) { "tappa: ${outcome?.javaClass?.simpleName}" }
    outcome?.let { graph.events.stage(diagJob, it) }
    if (triggersSimilarCopies && outcome != null) gallery.similarCopies?.requestRun()
    if (outcome is StageOutcome.Yielded) continuation()?.let { (name, request) -> NasWorkScheduling.continueLater(applicationContext, name, request) }
    return Result.success()
  }
}

@HiltWorker
class NasScanWorker @AssistedInject constructor(
  @Assisted appContext: Context,
  @Assisted params: WorkerParameters,
  gallery: GalleryGraph,
  graph: AppGraph,
) : NasWorker(appContext, params, gallery, graph) {
  override val tag: String = "NasScanWorker"
  override val diagJob: DiagJob = DiagJob.NAS_SCAN

  override suspend fun stage(stages: NasStages): StageOutcome = stages.scan().also { outcome ->
    // VE-10 (Fase 5B): a scansione finita le versioni ferme perche' il PC aveva cambiato il file ripartono con il lavoro del loro tipo (la
    // scansione ha registrato il file del PC). Prima aspettavano il giro periodico, fino a 12 ore. Solo il diario: nessun polling.
    if (outcome is StageOutcome.Finished) {
      try {
        gallery.backup?.afterNasScan()
      } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
      } catch (e: Exception) {
        GalleryLog.w(tag) { "versioni ferme non richieste dopo la scansione: ${e.javaClass.simpleName}" }
      }
    }
  }

  override fun continuation() = NasWorkScheduling.SCAN_NOW_WORK to OneTimeWorkRequestBuilder<NasScanWorker>().addTag(NasWorkScheduling.TAG).build()
}

@HiltWorker
class IndexSyncWorker @AssistedInject constructor(
  @Assisted appContext: Context,
  @Assisted params: WorkerParameters,
  gallery: GalleryGraph,
  graph: AppGraph,
) : NasWorker(appContext, params, gallery, graph) {
  override val tag: String = "IndexSyncWorker"
  override val diagJob: DiagJob = DiagJob.INDEX_SYNC

  override val triggersSimilarCopies: Boolean = true

  override suspend fun stage(stages: NasStages): StageOutcome = stages.sync()

  override fun continuation() = NasWorkScheduling.SYNC_NOW_WORK to OneTimeWorkRequestBuilder<IndexSyncWorker>().addTag(NasWorkScheduling.TAG).build()
}

@HiltWorker
class PreparationWorker @AssistedInject constructor(
  @Assisted appContext: Context,
  @Assisted params: WorkerParameters,
  gallery: GalleryGraph,
  graph: AppGraph,
) : NasWorker(appContext, params, gallery, graph) {
  override val tag: String = "PreparationWorker"
  override val diagJob: DiagJob = DiagJob.PREPARATION

  override suspend fun stage(stages: NasStages): StageOutcome = stages.prepare(gallery.similarCopies?.priority ?: NoUnitPriority)

  override fun continuation() = NasWorkScheduling.PREP_NOW_WORK to OneTimeWorkRequestBuilder<PreparationWorker>().addTag(NasWorkScheduling.TAG).build()
}

/** Fase 4B: la pulizia del cestino scaduto del NAS (§2.7, D25, E-D4), una volta al giorno. */
@HiltWorker
class NasTrashPurgeWorker @AssistedInject constructor(
  @Assisted appContext: Context,
  @Assisted params: WorkerParameters,
  gallery: GalleryGraph,
  graph: AppGraph,
) : NasWorker(appContext, params, gallery, graph) {
  override val tag: String = "NasTrashPurgeWorker"
  override val diagJob: DiagJob = DiagJob.TRASH_PURGE

  override suspend fun stage(stages: NasStages): StageOutcome? = stages.purgeTrash()

  override fun continuation() =
    NasWorkScheduling.TRASH_PURGE_NOW_WORK to OneTimeWorkRequestBuilder<NasTrashPurgeWorker>().addTag(NasWorkScheduling.TAG).build()
}

/** Fase 4B: la compattazione dell'indice e la pulizia dei propri file (M6), una volta al giorno. */
@HiltWorker
class IndexMaintenanceWorker @AssistedInject constructor(
  @Assisted appContext: Context,
  @Assisted params: WorkerParameters,
  gallery: GalleryGraph,
  graph: AppGraph,
) : NasWorker(appContext, params, gallery, graph) {
  override val tag: String = "IndexMaintenanceWorker"
  override val diagJob: DiagJob = DiagJob.INDEX_MAINTENANCE

  override suspend fun stage(stages: NasStages): StageOutcome? = stages.maintainIndex()

  override fun continuation() =
    NasWorkScheduling.MAINTENANCE_NOW_WORK to OneTimeWorkRequestBuilder<IndexMaintenanceWorker>().addTag(NasWorkScheduling.TAG).build()
}

/** Fase 4B: i dati personali fra i dispositivi dello stesso account (§2.9, `INDEX_SYNC`). */
@HiltWorker
class PersonalSyncWorker @AssistedInject constructor(
  @Assisted appContext: Context,
  @Assisted params: WorkerParameters,
  gallery: GalleryGraph,
  graph: AppGraph,
) : NasWorker(appContext, params, gallery, graph) {
  override val tag: String = "PersonalSyncWorker"
  override val diagJob: DiagJob = DiagJob.PERSONAL_SYNC

  override val triggersSimilarCopies: Boolean = true

  override suspend fun stage(stages: NasStages): StageOutcome? = stages.syncPersonal()

  override fun continuation() =
    NasWorkScheduling.PERSONAL_NOW_WORK to OneTimeWorkRequestBuilder<PersonalSyncWorker>().addTag(NasWorkScheduling.TAG).build()
}

@HiltWorker
class HqPreviewsWorker @AssistedInject constructor(
  @Assisted appContext: Context,
  @Assisted params: WorkerParameters,
  gallery: GalleryGraph,
  graph: AppGraph,
) : NasWorker(appContext, params, gallery, graph) {
  override val tag: String = "HqPreviewsWorker"
  override val diagJob: DiagJob = DiagJob.HQ_PREVIEWS

  override suspend fun stage(stages: NasStages): StageOutcome? = stages.hqPreviews()

  override fun continuation(): Pair<String, androidx.work.OneTimeWorkRequest>? = null
}
