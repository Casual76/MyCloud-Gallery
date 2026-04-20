package com.mycloudgallery

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.mycloudgallery.core.network.NetworkDetector
import com.mycloudgallery.core.security.TokenManager
import com.mycloudgallery.worker.WorkScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MyCloudGalleryApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var networkDetector: NetworkDetector
    @Inject lateinit var tokenManager: TokenManager
    @Inject lateinit var workScheduler: WorkScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        
        if (tokenManager.isLoggedIn || !tokenManager.nasLocalIp.isNullOrBlank()) {
            networkDetector.start()
        }
        workScheduler.schedulePeriodicSync()
        workScheduler.scheduleTrashCleanup()
        workScheduler.schedulePeriodicIndexing()
        workScheduler.scheduleSharedAlbumSync()
    }
}
