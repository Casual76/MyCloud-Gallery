package dev.pampa.mycloudgallery.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.pampa.mycloudgallery.albums.AlbumsGraph
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * I lavori degli album (Fase 7, P13; docs/fase7-progetto.md §1.4). Tutti dentro `GalleryGraph.runIfActive` (l'azzeramento li ferma e li aspetta):
 *
 * - [ShareWorker]: la condivisione e il "Riporta" (§2.5, §2.6) dal diario, finche' un'operazione e' aperta. Il lavoro pesante (spostamenti tra
 *   share, caricamenti dal telefono, guardiano) lo fanno i lavori che lo fanno gia' (operazioni della 4B come UIDT, arrivi e `safety` del backup),
 *   con le loro regole: questo avanza i passi, accoda le voci e pubblica. Si riaccoda da solo, con un minuto di attesa, finche' resta qualcosa;
 * - la replica degli album condivisi gira nel giro dell'indice (`IndexSyncWorker`, dopo indice e dati personali: `NasStages.albums`);
 * - viaggi e ricalcolo di sicurezza degli smart una volta per notte, in carica (`LibraryMaintenanceWorker`).
 */
object AlbumWorkScheduling {
  const val SHARE_WORK = "albums-share"
  const val TAG = "albums"

  /** Un passo della condivisione (unico, `KEEP`: se ce n'e' gia' uno, in attesa o in corso, resta quello; mai annullare un giro a meta'). */
  fun shareNow(context: Context) = enqueue(context, 0)

  /** Il passo dopo, fra un minuto (unico, `APPEND_OR_REPLACE`: mai annullare quello in corso). */
  fun shareLater(context: Context) = enqueue(context, RETRY.inWholeMilliseconds)

  private fun enqueue(context: Context, delayMs: Long) {
    val request = OneTimeWorkRequestBuilder<ShareWorker>()
      .setConstraints(LanConstraints.anyNetwork())
      .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
      .addTag(TAG)
      .build()
    val policy = if (delayMs == 0L) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.APPEND_OR_REPLACE
    try {
      WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(SHARE_WORK, policy, request)
    } catch (e: IllegalStateException) {
      // WorkManager non inizializzato (test senza lavori): il passo lo fa il giro dell'indice.
      GalleryLog.w(TAG) { "lavoro della condivisione non programmato: ${e.javaClass.simpleName}" }
    }
  }

  val RETRY: Duration = 1.minutes
}

/** Un giro della condivisione dal diario (§2.5): avanza, accoda, pubblica; se resta un'operazione aperta si riaccoda. */
@HiltWorker
class ShareWorker @AssistedInject constructor(
  @Assisted appContext: Context,
  @Assisted params: WorkerParameters,
  private val gallery: GalleryGraph,
  private val albums: AlbumsGraph,
) : CoroutineWorker(appContext, params) {

  override suspend fun doWork(): Result {
    if (!gallery.backgroundWorkAllowed) return Result.success()
    val open = gallery.runIfActive { albums.shareRound(BUDGET) } ?: return Result.success()
    if (open) AlbumWorkScheduling.shareLater(applicationContext)
    return Result.success()
  }

  private companion object {
    val BUDGET: Duration = 3.minutes
  }
}
