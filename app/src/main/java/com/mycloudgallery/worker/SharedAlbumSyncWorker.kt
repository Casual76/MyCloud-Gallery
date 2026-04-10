package com.mycloudgallery.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mycloudgallery.domain.repository.SharedAlbumRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Worker che sincronizza gli album condivisi col NAS.
 * Legge i file JSON da /NAS/.mycloudgallery/shared_albums/ e aggiorna il Room locale.
 * Schedulato ogni 30 minuti su WiFi; può essere forzato manualmente da UI.
 */
@HiltWorker
class SharedAlbumSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sharedAlbumRepository: SharedAlbumRepository,
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "shared_album_sync"
    }

    override suspend fun doWork(): Result = try {
        sharedAlbumRepository.syncWithNas()
        Result.success()
    } catch (e: Exception) {
        // Retry su errori di rete transitori (max 3 tentativi automatici di WorkManager)
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}
