package com.mycloudgallery.desktop.data.repository

import com.mycloudgallery.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.prefs.Preferences

/** Implementazione delle impostazioni per Desktop via java.util.prefs. */
class DesktopSettingsRepository : SettingsRepository {
    private val prefs = Preferences.userNodeForPackage(DesktopSettingsRepository::class.java)

    private val _cameraUpload = MutableStateFlow(prefs.getBoolean("camera_upload", false))
    private val _wifiOnly = MutableStateFlow(prefs.getBoolean("wifi_only", true))
    private val _autoIndex = MutableStateFlow(prefs.getBoolean("auto_index", true))
    private val _indexingPaused = MutableStateFlow(prefs.getBoolean("indexing_paused", false))

    override val cameraUploadEnabled: Flow<Boolean> = _cameraUpload
    override val wifiOnlyUpload: Flow<Boolean> = _wifiOnly
    override val autoIndex: Flow<Boolean> = _autoIndex
    override val indexingPaused: Flow<Boolean> = _indexingPaused

    override suspend fun setCameraUploadEnabled(enabled: Boolean) {
        prefs.putBoolean("camera_upload", enabled); _cameraUpload.value = enabled
    }
    override suspend fun setWifiOnlyUpload(wifiOnly: Boolean) {
        prefs.putBoolean("wifi_only", wifiOnly); _wifiOnly.value = wifiOnly
    }
    override suspend fun setAutoIndex(enabled: Boolean) {
        prefs.putBoolean("auto_index", enabled); _autoIndex.value = enabled
    }
    override suspend fun setIndexingPaused(paused: Boolean) {
        prefs.putBoolean("indexing_paused", paused); _indexingPaused.value = paused
    }
}
