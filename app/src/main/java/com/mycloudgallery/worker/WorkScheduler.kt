package com.mycloudgallery.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedula e gestisce tutti i worker dell'app.
 */
@Singleton
class WorkScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val workManager = WorkManager.getInstance(context)

    /** Avvia sync immediata (app in foreground) */
    fun startImmediateSync() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(wifiConstraints())
            .build()
        workManager.enqueueUniqueWork(
            SyncWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /** Schedula sync periodica ogni 15 minuti su WiFi */
    fun schedulePeriodicSync() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(wifiConstraints())
            .build()
        workManager.enqueueUniquePeriodicWork(
            "${SyncWorker.WORK_NAME}_periodic",
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Schedula upload camera periodico */
    fun scheduleCameraUpload() {
        val request = PeriodicWorkRequestBuilder<CameraUploadWorker>(15, TimeUnit.MINUTES)
            .setConstraints(wifiConstraints())
            .build()
        workManager.enqueueUniquePeriodicWork(
            CameraUploadWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Schedula pulizia cestino ogni 24 ore */
    fun scheduleTrashCleanup() {
        val request = PeriodicWorkRequestBuilder<TrashCleanupWorker>(24, TimeUnit.HOURS)
            .build()
        workManager.enqueueUniquePeriodicWork(
            TrashCleanupWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Ferma tutti i worker */
    fun cancelAll() {
        workManager.cancelAllWork()
    }

    /** Avvia indicizzazione AI immediata e schedula il clustering volti a seguire */
    fun startImmediateIndexing() {
        val request = OneTimeWorkRequestBuilder<IndexingWorker>()
            .build()
        workManager.enqueueUniqueWork(
            IndexingWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
        scheduleFaceClustering()
    }

    /** Schedula clustering volti (one-shot, richiede ricarica) */
    fun scheduleFaceClustering() {
        val request = OneTimeWorkRequestBuilder<FaceClusteringWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiresCharging(true)
                    .build()
            )
            .addTag(FaceClusteringWorker.WORK_NAME)
            .build()
        workManager.enqueueUniqueWork(
            FaceClusteringWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /** Schedula indicizzazione AI periodica (ogni 30 min, richiede ricarica) */
    fun schedulePeriodicIndexing() {
        val request = PeriodicWorkRequestBuilder<IndexingWorker>(30, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresCharging(true)
                    .build()
            )
            .build()
        workManager.enqueueUniquePeriodicWork(
            "${IndexingWorker.WORK_NAME}_periodic",
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Schedula sync album condivisi ogni 30 minuti su WiFi (Fase 3). */
    fun scheduleSharedAlbumSync() {
        val request = PeriodicWorkRequestBuilder<SharedAlbumSyncWorker>(30, TimeUnit.MINUTES)
            .setConstraints(wifiConstraints())
            .build()
        workManager.enqueueUniquePeriodicWork(
            SharedAlbumSyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Forza sync manuale (da widget o UI) */
    fun forceSync() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .build()
        workManager.enqueueUniqueWork(
            "${SyncWorker.WORK_NAME}_manual",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private fun wifiConstraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.UNMETERED)
        .build()
}
