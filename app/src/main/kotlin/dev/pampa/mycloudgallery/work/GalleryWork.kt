package dev.pampa.mycloudgallery.work

import android.content.Context
import android.provider.MediaStore
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
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncReason
import dev.pampa.mycloudgallery.core.mediastore.noop.NoMetaPriority
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * La pianificazione dei lavori della galleria del telefono (D10, docs/fase3-progetto.md §1.4, §2.1). **Fuori dal motore
 * delle regole**: e' lavoro locale e leggero, senza rete.
 *
 * - [PhoneSyncWorker]: un lavoro singolo con **trigger di contenuto** sulle immagini e sui video del MediaStore (con i
 *   discendenti; ritardo 5 s, al massimo 60 s), batteria non scarica. Quando il processo non vive e' cio' che porta le foto
 *   nuove nel catalogo (T-13). Un trigger di contenuto vale una volta: il lavoro si **riaccoda da solo** dopo ogni giro.
 * - [LibraryMaintenanceWorker]: una volta al giorno, **in carica**: META residua, hash completi rimandati, riconciliazione
 *   degli `_ID`, pulizia delle assenze con tetto.
 *
 * Idempotente: [ensure] non sposta lavori gia' pianificati. Si chiama quando la galleria parte (famiglia pronta). Nella
 * variante di misura non si pianifica niente (il dataset e' sintetico).
 */
object GalleryWorkScheduling {

  const val PHONE_SYNC_WORK = "gallery-phone-sync"
  const val MAINTENANCE_WORK = "gallery-maintenance"
  const val TAG = "gallery"

  /** Il ritardo del trigger di contenuto: le raffiche diventano un giro solo. */
  val TRIGGER_DELAY: Duration = 5.seconds

  /** Il ritardo massimo: una foto nuova entra nel catalogo al piu' un minuto dopo (con il processo morto). */
  val TRIGGER_MAX_DELAY: Duration = 60.seconds

  val MAINTENANCE_PERIOD: Duration = 24.hours

  fun ensure(context: Context) {
    val workManager = WorkManager.getInstance(context.applicationContext)
    workManager.enqueueUniqueWork(PHONE_SYNC_WORK, ExistingWorkPolicy.KEEP, phoneSyncRequest())
    val maintenance = PeriodicWorkRequestBuilder<LibraryMaintenanceWorker>(MAINTENANCE_PERIOD.inWholeHours, TimeUnit.HOURS)
      .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build())
      .addTag(TAG)
      .build()
    workManager.enqueueUniquePeriodicWork(MAINTENANCE_WORK, ExistingPeriodicWorkPolicy.KEEP, maintenance)
  }

  /** Il lavoro a trigger di contenuto (anche quello che [PhoneSyncWorker] riaccoda dopo di se'). */
  fun phoneSyncRequest(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<PhoneSyncWorker>()
    .setConstraints(
      Constraints.Builder()
        .setRequiresBatteryNotLow(true)
        .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
        .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
        .setTriggerContentUpdateDelay(TRIGGER_DELAY.toJavaDuration())
        .setTriggerContentMaxDelay(TRIGGER_MAX_DELAY.toJavaDuration())
        .build(),
    )
    .addTag(TAG)
    .build()
}

/**
 * La lettura del telefono in background (D10): un giro entro **8 minuti** (le tappe di WorkManager durano al massimo
 * 10), poi META dei media appena arrivati con il tempo che resta (`PhoneSyncRunner`, P2). Un giro interrotto riprende da
 * dove era. Alla fine il lavoro **si riaccoda** (trigger di contenuto: vale una volta sola).
 */
@HiltWorker
class PhoneSyncWorker @AssistedInject constructor(
  @Assisted appContext: Context,
  @Assisted params: WorkerParameters,
  private val gallery: GalleryGraph,
) : CoroutineWorker(appContext, params) {

  override suspend fun doWork(): Result {
    if (!gallery.backgroundWorkAllowed) return Result.success()
    // Famiglia non pronta (non configurato, in attesa, azzerato) o galleria ferma: niente da fare e niente da riaccodare.
    // `runIfActive` permette all'azzeramento di fermare un giro in corso e di aspettarlo (R7).
    val outcome = gallery.runIfActive { core -> core.phone.runner.run(BUDGET) } ?: return Result.success()
    GalleryLog.d(TAG) { "lettura del telefono in background: ${outcome.javaClass.simpleName}" }
    // Fase 5A (D11, E-B13): media nuovi in una sorgente del backup => il lavoro del tipo giusto (lavoro unico, KEEP); la copia del telefono
    // puo' essere sparita mentre la sola copia sul NAS e' nell'area comune => il guardiano. Dal catalogo e dal diario, nessun polling.
    gallery.backup?.let { backup ->
      try {
        // Fase 5B (P14, P9): gli esiti in dubbio di "Libera spazio" (dialogo di sistema di un processo morto) si risolvono con il telefono.
        gallery.runIfActive { backup.afterPhoneRead() }
        BackupWorkScheduling.afterPhoneRead(applicationContext, backup)
      } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
      } catch (e: Exception) {
        GalleryLog.w(TAG) { "backup dopo la lettura del telefono non chiesto: ${e.javaClass.simpleName}" }
      }
    }
    // Fase 5A (D36): dopo META, un giro del confronto delle copie simili (a tappe, su Default; chi lo chiede non aspetta).
    gallery.similarCopies?.requestRun()
    // Il trigger di contenuto vale una volta: il prossimo cambiamento del MediaStore deve trovarne uno nuovo, accodato
    // dopo questo (APPEND: mai annullare il lavoro in corso).
    WorkManager.getInstance(applicationContext)
      .enqueueUniqueWork(GalleryWorkScheduling.PHONE_SYNC_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, GalleryWorkScheduling.phoneSyncRequest())
    return Result.success()
  }

  companion object {
    const val TAG = "PhoneSyncWorker"
    val BUDGET: Duration = 8.minutes
  }
}

/**
 * La manutenzione quotidiana in carica (§1.4): riconciliazione degli `_ID` e pulizia delle assenze scadute (il giro
 * `MAINTENANCE` della sincronizzazione), META residua, hash completi rimandati. Tutto entro un budget: cio' che resta si fa
 * domani. Il ricalcolo dei luoghi a un dataset nuovo non e' ancora qui (vedi docs/decisioni.md, Fase 3).
 */
@HiltWorker
class LibraryMaintenanceWorker @AssistedInject constructor(
  @Assisted appContext: Context,
  @Assisted params: WorkerParameters,
  private val gallery: GalleryGraph,
) : CoroutineWorker(appContext, params) {

  override suspend fun doWork(): Result {
    if (!gallery.backgroundWorkAllowed) return Result.success()
    val done = gallery.runIfActive { core ->
      val phone = core.phone
      val report = phone.sync.runWithin(SyncReason.MAINTENANCE, SYNC_BUDGET)
      val meta = phone.metadataPass.run(META_BUDGET, NoMetaPriority)
      val hashes = phone.deferredHashes.run(HASH_BUDGET)
      GalleryLog.d(TAG) { "manutenzione: giro ${report.completed}, META ${meta.javaClass.simpleName}, hash ${hashes.hashed}" }
    }
    // Come tutte le scritture dell'istantanea, passa da `flush` della galleria (mai a galleria ferma).
    if (done != null) {
      runCatching { gallery.flush() }
      // Fase 5A (D36): META residua finita, quindi un giro del confronto delle copie simili.
      gallery.similarCopies?.requestRun()
    }
    return Result.success()
  }

  companion object {
    const val TAG = "LibraryMaintenance"
    val SYNC_BUDGET: Duration = 3.minutes
    val META_BUDGET: Duration = 3.minutes
    val HASH_BUDGET: Duration = 2.minutes
  }
}
