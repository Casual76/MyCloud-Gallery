package com.mycloudgallery

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.mycloudgallery.worker.WorkScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MyCloudGalleryApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var workScheduler: WorkScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        workScheduler.schedulePeriodicSync()
        workScheduler.scheduleTrashCleanup()
        workScheduler.schedulePeriodicIndexing() // Fase 2: indicizzazione AI in background
        workScheduler.scheduleSharedAlbumSync()  // Fase 3: sync album condivisi
    }
}
