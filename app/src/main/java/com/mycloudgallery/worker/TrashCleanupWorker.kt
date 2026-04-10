package com.mycloudgallery.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mycloudgallery.core.database.dao.MediaItemDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Worker periodico (ogni 24h) che elimina definitivamente
 * i file nel cestino con trashedAt > 30 giorni.
 */
@HiltWorker
class TrashCleanupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val mediaItemDao: MediaItemDao,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val thirtyDaysAgo = System.currentTimeMillis() - THIRTY_DAYS_MS
            mediaItemDao.deleteExpiredTrash(thirtyDaysAgo)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "trash_cleanup_worker"
        private const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000
    }
}
