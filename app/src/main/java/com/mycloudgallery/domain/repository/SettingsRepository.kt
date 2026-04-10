package com.mycloudgallery.domain.repository

import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val cameraUploadEnabled: Flow<Boolean>
    val wifiOnlyUpload: Flow<Boolean>
    val autoIndex: Flow<Boolean>
    val indexingPaused: Flow<Boolean>

    suspend fun setCameraUploadEnabled(enabled: Boolean)
    suspend fun setWifiOnlyUpload(wifiOnly: Boolean)
    suspend fun setAutoIndex(enabled: Boolean)
    suspend fun setIndexingPaused(paused: Boolean)
}
