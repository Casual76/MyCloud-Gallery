package com.mycloudgallery.domain.repository

import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val cameraUploadEnabled: Flow<Boolean>
    val wifiOnlyUpload: Flow<Boolean>
    val autoIndex: Flow<Boolean>
    val indexingPaused: Flow<Boolean>

    val lastSyncTime: Flow<Long>
    val lastSyncResult: Flow<String>

    /** AI engine to use for scene analysis: "gemini_nano" | "gemma4" | "none" */
    val aiEngineProvider: Flow<String>

    suspend fun setCameraUploadEnabled(enabled: Boolean)
    suspend fun setWifiOnlyUpload(wifiOnly: Boolean)
    suspend fun setAutoIndex(enabled: Boolean)
    suspend fun setIndexingPaused(paused: Boolean)

    suspend fun setSyncResult(time: Long, result: String)
    suspend fun setAiEngineProvider(provider: String)
}
