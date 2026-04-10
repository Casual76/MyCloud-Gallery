package com.mycloudgallery.desktop.di

import com.mycloudgallery.desktop.data.network.DesktopWebDavClient
import com.mycloudgallery.desktop.data.repository.DesktopAuthRepository
import com.mycloudgallery.desktop.data.repository.DesktopMediaRepository
import com.mycloudgallery.desktop.data.repository.DesktopSettingsRepository
import com.mycloudgallery.desktop.data.security.DesktopKeyStore
import com.mycloudgallery.domain.repository.AuthRepository
import com.mycloudgallery.domain.repository.SettingsRepository

/**
 * Contenitore delle dipendenze per la versione Desktop.
 * Equivalente manuale di Hilt (non disponibile su JVM Desktop).
 * Istanziato una volta in Main.kt e passato per composizione.
 */
class DesktopDependencies {
    val keyStore = DesktopKeyStore()

    val authRepository: AuthRepository = DesktopAuthRepository(keyStore)

    private val webDavClient: DesktopWebDavClient by lazy {
        val nasIp = keyStore.nasIp ?: "192.168.1.1"
        DesktopWebDavClient(
            baseUrl = "http://$nasIp",
            username = keyStore.username ?: "",
            password = "",  // Il token viene usato per auth, non la password
        )
    }

    val mediaRepository: DesktopMediaRepository by lazy {
        DesktopMediaRepository(webDavClient)
    }

    val settingsRepository: SettingsRepository = DesktopSettingsRepository()
}
