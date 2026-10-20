package dev.pampa.mycloudgallery.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.pampa.mycloudgallery.backup.BackupGraph
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.core.backup.upload.BackupStageOutcome
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.rules.WaitReason
import dev.pampa.mycloudgallery.core.rules.run.StageOutcome
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.feature.diagnostics.recording.DiagJob
import dev.pampa.mycloudgallery.graph.AppGraph
import java.time.Duration as JavaDuration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * I lavori del **backup** (Fase 5A, P7; docs/fase5-progetto.md §1.4, §2.8, D11, D34). Tutti **a tappe** (`StagedRun`: regole prima di
 * partire e a meta' lotto, traffico registrato subito, otto minuti al massimo) e dentro `GalleryGraph.runIfActive` (l'azzeramento li ferma
 * e li aspetta); le regole decidono quando si parte, i vincoli di WorkManager solo se vale la pena provarci:
 *
 * - [PhotoBackupWorker] (`PHOTO_BACKUP`) e [VideoBackupWorker] (`VIDEO_BACKUP`, pesante: solo sul Wi-Fi di casa);
 * - [QuickPhotoWorker] (`PHOTO_BACKUP` con le regole di D34): le foto appena scattate, anche fuori fascia;
 * - [SafetyVerifyWorker]: il guardiano, le copie non verificate, `FULL_HASH` e la rotazione annuale (Q10), con la regola dell'attivita'
 *   del tipo di media;
 * - [IncomingUploadWorker] (P14, D24): i file ricevuti da un'altra app e messi in coda con "Salva sul NAS" (`PHOTO_BACKUP`);
 * - il primo backup e "Fai ora" non sono lavori di WorkManager: li avvia l'utente ([BackupJobService], UIDT).
 *
 * **Trigger, nessun polling**: a fine giro di `PhoneSyncWorker`, [QuickPhotoWorker] o [PhotoBackupWorker] ([BackupWorkScheduling.afterPhoneRead])
 * si chiede il lavoro del tipo giusto (lavoro unico, `KEEP`) se ci sono media nuovi in una sorgente; il guardiano dopo la lettura del
 * telefono (E-B13) e di notte. Come per i lavori del NAS, un'attesa per la rete non ne programma un'altra: il giro periodico, la lettura
 * del telefono e l'apertura dell'app bastano; solo carica, batteria e fascia notturna hanno un vincolo (o un ritardo) esatto di WorkManager.
 * I punti di ripresa stanno nel diario (`backup_item`): le tappe non ne hanno uno proprio.
 */
object BackupWorkScheduling {

  const val PHOTOS_WORK = "backup-photos"
  const val VIDEOS_WORK = "backup-videos"
  const val QUICK_WORK = "backup-quick-photos"
  const val SAFETY_WORK = "backup-safety"
  const val INCOMING_WORK = "backup-incoming"
  const val PHOTOS_PERIODIC_WORK = "backup-photos-periodic"
  const val VIDEOS_PERIODIC_WORK = "backup-videos-periodic"
  const val SAFETY_PERIODIC_WORK = "backup-safety-periodic"
  const val TAG = "backup"

  /** Il giro periodico di chi non e' sveglio: ogni sei ore, le regole decidono se si parte. */
  val PERIOD: kotlin.time.Duration = NasWorkScheduling.PERIOD

  /** Le verifiche e il guardiano: una volta al giorno (la rotazione e' calibrata su 365 giri l'anno, Q10). */
  val SAFETY_PERIOD: kotlin.time.Duration = NasWorkScheduling.DAILY_PERIOD

  /** Il margine dopo l'inizio della fascia: l'ora del telefono e quella di WorkManager non sono mai identiche. */
  private const val WINDOW_MARGIN_MS = 60_000L

  private const val BACKOFF_MS = 60_000L

  /**
   * BK-2: fra due prove di "Proteggi subito" che aspettano il Wi-Fi di casa. Il vincolo di rete e' soddisfatto da qualunque Wi-Fi non a
   * consumo: su uno che non e' casa (un bar) le regole dicono di nuovo "non e' casa" e la prova si ripete al massimo ogni quarto d'ora, senza
   * un ciclo. Con i dati mobili il vincolo non e' soddisfatto e non gira niente.
   */
  private const val HOME_RETRY_MS = 15 * 60_000L

  /** Che cosa chiedere: il tipo di lavoro. */
  enum class Kind(val workName: String) {
    PHOTOS(PHOTOS_WORK),
    VIDEOS(VIDEOS_WORK),
    QUICK(QUICK_WORK),
    SAFETY(SAFETY_WORK),
    INCOMING(INCOMING_WORK),
  }

  /** RE-11: una rete qualunque, anche senza internet (il NAS e' in LAN): vedi [LanConstraints]. */
  private fun connected(): Constraints = LanConstraints.anyNetwork()

  /** I lavori periodici: idempotente (non sposta quelli gia' pianificati). Le regole decidono comunque se si parte. */
  fun ensure(context: Context) {
    val wm = WorkManager.getInstance(context.applicationContext)
    wm.enqueueUniquePeriodicWork(
      PHOTOS_PERIODIC_WORK,
      ExistingPeriodicWorkPolicy.KEEP,
      PeriodicWorkRequestBuilder<PhotoBackupWorker>(PERIOD.inWholeHours, TimeUnit.HOURS).setConstraints(connected()).addTag(TAG).build(),
    )
    wm.enqueueUniquePeriodicWork(
      VIDEOS_PERIODIC_WORK,
      ExistingPeriodicWorkPolicy.KEEP,
      PeriodicWorkRequestBuilder<VideoBackupWorker>(PERIOD.inWholeHours, TimeUnit.HOURS).setConstraints(connected()).addTag(TAG).build(),
    )
    wm.enqueueUniquePeriodicWork(
      SAFETY_PERIODIC_WORK,
      ExistingPeriodicWorkPolicy.KEEP,
      PeriodicWorkRequestBuilder<SafetyVerifyWorker>(SAFETY_PERIOD.inWholeHours, TimeUnit.HOURS).setConstraints(connected()).addTag(TAG).build(),
    )
  }

  /**
   * I vincoli per un'attesa delle regole: la carica e la batteria si dicono a WorkManager, che avvisa quando cambiano (nessun polling).
   * Per tutte le altre attese: solo la rete. Pura, provata su JVM.
   */
  fun constraintsFor(wait: WaitReason?): Constraints {
    val builder = LanConstraints.builder()
    when (wait) {
      WaitReason.NeedsPower -> builder.setRequiresCharging(true)
      is WaitReason.BatteryLow, WaitReason.PowerSaver -> builder.setRequiresBatteryNotLow(true)
      else -> Unit
    }
    return builder.build()
  }

  /**
   * Fra quanto riprovare per un'attesa delle regole, o null se non serve programmare niente. Solo quello che e' esatto: l'inizio della
   * fascia notturna (`OutsideWindow`); la carica e la batteria sono vincoli ([constraintsFor]) e ripartono subito. Le attese per la rete,
   * il profilo, il roaming, il congelamento o l'attivita' spenta non ne programmano un'altra (niente polling). Pura, provata su JVM.
   */
  fun delayFor(wait: WaitReason, now: LocalDateTime): Long? = when (wait) {
    is WaitReason.OutsideWindow -> JavaDuration.between(now, wait.nextStart).toMillis().coerceAtLeast(0L) + WINDOW_MARGIN_MS
    WaitReason.NeedsPower, is WaitReason.BatteryLow, WaitReason.PowerSaver -> 0L
    else -> null
  }

  /**
   * BK-2: la ragione dell'attesa e' "non sono sul Wi-Fi di casa" (nessuna rete, dati mobili, un altro Wi-Fi, roaming). Il NAS irraggiungibile, il
   * congelamento e le altre no: a casa con il NAS spento riprovare ogni quarto d'ora sarebbe polling.
   */
  fun waitsForHomeNetwork(wait: WaitReason): Boolean =
    wait == WaitReason.Offline || wait is WaitReason.NetworkNotAllowed || wait == WaitReason.NetworkNotTrusted || wait == WaitReason.Roaming

  fun request(kind: Kind, wait: WaitReason? = null, delayMs: Long = 0L, onWifi: Boolean = false): OneTimeWorkRequest {
    val builder = when (kind) {
      Kind.PHOTOS -> OneTimeWorkRequestBuilder<PhotoBackupWorker>()
      Kind.VIDEOS -> OneTimeWorkRequestBuilder<VideoBackupWorker>()
      Kind.QUICK -> OneTimeWorkRequestBuilder<QuickPhotoWorker>()
      Kind.SAFETY -> OneTimeWorkRequestBuilder<SafetyVerifyWorker>()
      Kind.INCOMING -> OneTimeWorkRequestBuilder<IncomingUploadWorker>()
    }
    return builder
      .setConstraints(if (onWifi) LanConstraints.unmetered() else constraintsFor(wait))
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MS, TimeUnit.MILLISECONDS)
      .apply { if (delayMs > 0L) setInitialDelay(delayMs, TimeUnit.MILLISECONDS) }
      .addTag(TAG)
      .build()
  }

  /** Il lavoro dopo un'attesa delle regole, se c'e' un vincolo esatto ([delayFor]); chiamato dal lavoro stesso, quindi in coda dopo di lui. */
  fun afterWait(context: Context, kind: Kind, wait: WaitReason, now: LocalDateTime = LocalDateTime.now()) {
    val delay = delayFor(wait, now)
    if (delay != null) {
      enqueue(context, request(kind, wait, delay), kind, ExistingWorkPolicy.APPEND_OR_REPLACE)
      return
    }
    // BK-2: la foto scattata fuori casa. "Proteggi subito" aspetta il Wi-Fi di casa e nessun altro evento lo avrebbe fatto ripartire al rientro
    // (la lettura del telefono non ha niente di nuovo, il giro periodico e' fra ore e con Notte parte di notte): il lavoro si rimette in coda su
    // una rete non a consumo, e WorkManager lo avvia quando il Wi-Fi c'e'. Per gli altri lavori le attese per la rete restano senza lavoro dopo.
    // IN-5: lo stesso per i file ricevuti ("Salva sul NAS" e' un gesto dell'utente): fuori casa, o col NAS spento, il file aspetta e riparte da solo.
    if (kind == Kind.QUICK && waitsForHomeNetwork(wait)) {
      enqueue(context, request(kind, wait, HOME_RETRY_MS, onWifi = true), kind, ExistingWorkPolicy.APPEND_OR_REPLACE)
    } else if (kind == Kind.INCOMING && (waitsForHomeNetwork(wait) || wait == WaitReason.NasUnreachable)) {
      enqueue(context, request(kind, wait, HOME_RETRY_MS, onWifi = true), kind, ExistingWorkPolicy.APPEND_OR_REPLACE)
    }
  }

  /** Il lavoro dopo, per un lavoro che ha lasciato altro da fare ([StageOutcome.Yielded]): in coda dopo quello in corso. */
  fun continueLater(context: Context, kind: Kind) {
    enqueue(context, request(kind), kind, ExistingWorkPolicy.APPEND_OR_REPLACE)
  }

  /**
   * Dopo la lettura del telefono (o un lavoro del backup): il lavoro del tipo giusto, se ci sono media nuovi in una sorgente, e il
   * guardiano se c'e' una copia da fare (E-B13). Lavoro unico, `KEEP`: se un lavoro dello stesso tipo e' gia' in coda o in corso non se ne
   * aggiunge un altro. Nessun polling: solo dal catalogo e dal diario, mai dal NAS.
   *
   * @return che cosa e' stato chiesto (per i test).
   */
  suspend fun afterPhoneRead(context: Context, backup: BackupGraph): Set<Kind> {
    if (!backup.start()) return emptySet()
    val asked = LinkedHashSet<Kind>()
    // BK-2, BK-10: tutti i giri che servono, non il primo soltanto (la foto nuova non toglie il lavoro delle foto, ne' quello dei video).
    for (next in backup.nextAll()) {
      when (next) {
        BackupGraph.Next.Quick -> asked += Kind.QUICK
        BackupGraph.Next.Photos -> asked += Kind.PHOTOS
        BackupGraph.Next.Videos -> asked += Kind.VIDEOS
        BackupGraph.Next.Nothing -> Unit
      }
    }
    if (backup.guardianHasWork()) asked += Kind.SAFETY
    // D24 (P14): file ricevuti rimasti in coda (processo morto, NAS spento, regole): il lavoro degli arrivi, dal diario.
    if (backup.hasOpenIncoming()) asked += Kind.INCOMING
    asked.forEach { kind -> enqueue(context, request(kind), kind, ExistingWorkPolicy.KEEP) }
    return asked
  }

  /** D24 (P14): "Salva sul NAS" ha messo dei file in coda: il lavoro degli arrivi (unico, `KEEP`: uno in coda li prende tutti). */
  fun incoming(context: Context) {
    enqueue(context, request(Kind.INCOMING), Kind.INCOMING, ExistingWorkPolicy.KEEP)
  }

  /**
   * VE-10, MC-3: un lavoro delle versioni nato adesso (uno scambio, la modifica di un solo-NAS, le versioni ferme per il PC dopo una scansione):
   * il lavoro del tipo del media (unico, `KEEP`), che le regole del suo tipo fanno partire. Nessun polling: lo chiede chi ha creato il lavoro.
   */
  fun versionWork(context: Context, kind: BackupKind) {
    val work = if (kind == BackupKind.VIDEOS) Kind.VIDEOS else Kind.PHOTOS
    enqueue(context, request(work), work, ExistingWorkPolicy.KEEP)
  }

  private fun enqueue(context: Context, request: OneTimeWorkRequest, kind: Kind, policy: ExistingWorkPolicy) {
    WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(kind.workName, policy, request)
  }
}

/** Cosa fare dopo una tappa del backup. */
sealed interface BackupFollowUp {
  data object None : BackupFollowUp

  /** Altro lavoro da fare: la tappa dopo (WorkManager decide quando, secondo il bucket dell'app). */
  data object Continue : BackupFollowUp

  /** Le regole non lasciano partire: il motivo, per programmare un vincolo esatto se c'e'. */
  data class Wait(val reason: WaitReason) : BackupFollowUp

  /** Un errore del NAS: si riprova con l'attesa crescente di WorkManager. */
  data object Retry : BackupFollowUp

  companion object {
    /** Il seguito di una tappa del motore (foto, video, foto nuove). Pura, provata su JVM. */
    fun of(outcome: BackupStageOutcome?): BackupFollowUp {
      // BK-6: il turno era occupato da un altro lavoro del backup: si riprova con l'attesa crescente di WorkManager.
      if (outcome?.busy == true) return Retry
      val stage = outcome?.stage ?: return None
      return when (stage) {
        is StageOutcome.Yielded -> Continue
        is StageOutcome.Waiting -> Wait(stage.reason)
        is StageOutcome.Stopped -> Wait(stage.reason)
        // Serve la conferma dell'utente (dati mobili): la chiede la pagina, non un lavoro.
        is StageOutcome.NeedsConfirmation -> None
        is StageOutcome.Finished -> if (outcome.last?.stoppedBy == RunStop.NAS) Retry else None
      }
    }

    /** Il seguito di una tappa delle verifiche e del guardiano. Pura, provata su JVM. */
    fun ofSafety(outcome: SafetyStageOutcome?): BackupFollowUp {
      outcome ?: return None
      if (outcome.yielded) return Continue
      val waiting = outcome.waiting
      return if (waiting != null) Wait(waiting.reason) else None
    }
  }
}

/** L'esito di una tappa lanciata con la difesa di R1 ([attemptStage]): il valore, oppure l'errore inatteso gia' registrato. */
internal sealed interface StageAttempt<out T> {
  class Done<T>(val value: T) : StageAttempt<T>

  data object Failed : StageAttempt<Nothing>
}

/**
 * R1 della revisione 5A: una tappa o un lavoro del backup che finisce con un'eccezione che nessuno prevedeva **non** diventa un successo
 * silenzioso (`Result.success()` senza traccia) ne' una ripianificazione all'infinito del lavoro dell'utente (`RESCHEDULE`): l'errore si
 * registra come problema visibile ([onError], di norma `BackupGraph.noteUnexpectedError`: la pagina dice `UNEXPECTED`, "Riprova" lo toglie) e
 * il chiamante chiude il lavoro. L'annullamento passa (il lavoro e' stato fermato) e `Error` non si cattura. Pura, provata su JVM.
 */
internal suspend fun <T> attemptStage(tag: String, onError: suspend () -> Unit, block: suspend () -> T): StageAttempt<T> = try {
  StageAttempt.Done(block())
} catch (e: kotlinx.coroutines.CancellationException) {
  throw e
} catch (e: Exception) {
  GalleryLog.e(tag, e) { "tappa non riuscita" }
  try {
    onError()
  } catch (c: kotlinx.coroutines.CancellationException) {
    throw c
  } catch (_: Exception) {
    GalleryLog.w(tag) { "problema non salvato" }
  }
  StageAttempt.Failed
}

/** La parte comune dei lavori del backup: niente senza NAS, niente nella variante di misura, tutto dentro `runIfActive`. */
abstract class BackupWorker(
  appContext: Context,
  params: WorkerParameters,
  protected val gallery: GalleryGraph,
  protected val graph: AppGraph,
) : CoroutineWorker(appContext, params) {

  protected abstract val tag: String

  protected abstract val kind: BackupWorkScheduling.Kind

  /** Il lavoro, per l'anello della Diagnostica (solo il nome fisso, RE-4). */
  protected abstract val diagJob: DiagJob

  /** La tappa del lavoro; null = non parte (niente da fare, NAS o backup non configurati). */
  protected abstract suspend fun stage(stages: BackupStages): BackupFollowUp?

  final override suspend fun doWork(): Result {
    val backup = gallery.backup ?: return Result.success()
    if (!gallery.backgroundWorkAllowed) return Result.success()
    // R1: un errore inatteso si salva come problema visibile e il lavoro finisce `failure` (mai un successo silenzioso, mai un `retry` a oltranza:
    // il lavoro dopo lo chiede la lettura del telefono o il giro periodico, e "Riprova" toglie il segno).
    val followUp = try {
      when (
        val attempt = attemptStage(tag, onError = {
          backup.noteUnexpectedError()
          graph.events.jobFailed(diagJob)
        }) { gallery.runIfActive { stage(BackupStages(graph, backup)) } }
      ) {
        StageAttempt.Failed -> return Result.failure()
        is StageAttempt.Done -> attempt.value
      }
    } catch (e: kotlinx.coroutines.CancellationException) {
      // Android ha fermato il lavoro: il motivo si salva e, se e' un limite, arriva alle regole (RE-4, RE-7).
      StopReports.report(graph, diagJob, StopReports.reasonOf(this))
      throw e
    }
    GalleryLog.d(tag) { "tappa: ${followUp?.javaClass?.simpleName}" }
    when (followUp) {
      BackupFollowUp.Continue -> BackupWorkScheduling.continueLater(applicationContext, kind)
      is BackupFollowUp.Wait -> BackupWorkScheduling.afterWait(applicationContext, kind, followUp.reason)
      BackupFollowUp.Retry -> return Result.retry()
      BackupFollowUp.None, null -> {
        // Fine giro: se nel frattempo e' arrivato altro in una sorgente (o il giro delle foto ha lasciato i video), il lavoro del tipo giusto.
        try {
          BackupWorkScheduling.afterPhoneRead(applicationContext, backup)
        } catch (e: kotlinx.coroutines.CancellationException) {
          throw e
        } catch (e: Exception) {
          GalleryLog.w(tag) { "lavoro dopo non chiesto: ${e.javaClass.simpleName}" }
        }
      }
    }
    return Result.success()
  }
}

@HiltWorker
class PhotoBackupWorker @AssistedInject constructor(
  @Assisted appContext: Context,
  @Assisted params: WorkerParameters,
  gallery: GalleryGraph,
  graph: AppGraph,
) : BackupWorker(appContext, params, gallery, graph) {
  override val tag: String = "PhotoBackupWorker"
  override val kind = BackupWorkScheduling.Kind.PHOTOS
  override val diagJob: DiagJob = DiagJob.PHOTO_BACKUP

  override suspend fun stage(stages: BackupStages): BackupFollowUp? = stages.photos()?.let { BackupFollowUp.of(it) }
}

@HiltWorker
class VideoBackupWorker @AssistedInject constructor(
  @Assisted appContext: Context,
  @Assisted params: WorkerParameters,
  gallery: GalleryGraph,
  graph: AppGraph,
) : BackupWorker(appContext, params, gallery, graph) {
  override val tag: String = "VideoBackupWorker"
  override val kind = BackupWorkScheduling.Kind.VIDEOS
  override val diagJob: DiagJob = DiagJob.VIDEO_BACKUP

  override suspend fun stage(stages: BackupStages): BackupFollowUp? = stages.videos()?.let { BackupFollowUp.of(it) }
}

/** D34, "Proteggi subito le foto nuove": le foto appena scattate si caricano appena c'e' il Wi-Fi di casa, anche fuori dalla fascia notte. */
@HiltWorker
class QuickPhotoWorker @AssistedInject constructor(
  @Assisted appContext: Context,
  @Assisted params: WorkerParameters,
  gallery: GalleryGraph,
  graph: AppGraph,
) : BackupWorker(appContext, params, gallery, graph) {
  override val tag: String = "QuickPhotoWorker"
  override val kind = BackupWorkScheduling.Kind.QUICK
  override val diagJob: DiagJob = DiagJob.QUICK_PHOTOS

  override suspend fun stage(stages: BackupStages): BackupFollowUp? = stages.quick()?.let { BackupFollowUp.of(it) }
}

/**
 * D24 (P14): i file ricevuti da un'altra app ("Salva sul NAS"), dal diario del backup: caricati con il caricamento sicuro, riletti, collegati;
 * solo dopo la copia locale si toglie. Il cancello e' quello della share di destinazione (D12, R2, D13).
 */
@HiltWorker
class IncomingUploadWorker @AssistedInject constructor(
  @Assisted appContext: Context,
  @Assisted params: WorkerParameters,
  gallery: GalleryGraph,
  graph: AppGraph,
) : BackupWorker(appContext, params, gallery, graph) {
  override val tag: String = "IncomingUploadWorker"
  override val kind = BackupWorkScheduling.Kind.INCOMING
  override val diagJob: DiagJob = DiagJob.BACKUP_INCOMING

  override suspend fun stage(stages: BackupStages): BackupFollowUp? = stages.incoming()?.let { outcome ->
    // IN-5: sui dati mobili o in roaming le regole chiedono la conferma dell'utente e il file ricevuto aspetta il Wi-Fi: lo si riprova su una rete non
    // a consumo (come "Proteggi subito", BK-2). Gli altri lavori lo lasciano alla pagina; qui l'utente aveva solo condiviso un file.
    if (outcome.stage is StageOutcome.NeedsConfirmation) BackupFollowUp.Wait(WaitReason.NetworkNotTrusted) else BackupFollowUp.of(outcome)
  }
}

/** Le verifiche (non verificate, `FULL_HASH`, rotazione) e il guardiano delle copie private (D9, D14). */
@HiltWorker
class SafetyVerifyWorker @AssistedInject constructor(
  @Assisted appContext: Context,
  @Assisted params: WorkerParameters,
  gallery: GalleryGraph,
  graph: AppGraph,
) : BackupWorker(appContext, params, gallery, graph) {
  override val tag: String = "SafetyVerifyWorker"
  override val kind = BackupWorkScheduling.Kind.SAFETY
  override val diagJob: DiagJob = DiagJob.BACKUP_SAFETY

  override suspend fun stage(stages: BackupStages): BackupFollowUp? = stages.safety()?.let { BackupFollowUp.ofSafety(it) }
}
