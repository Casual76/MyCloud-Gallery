package com.mycloudgallery.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mycloudgallery.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : SettingsRepository {

    private object Keys {
        val CAMERA_UPLOAD_ENABLED = booleanPreferencesKey("camera_upload_enabled")
        val WIFI_ONLY_UPLOAD = booleanPreferencesKey("wifi_only_upload")
        val AUTO_INDEX = booleanPreferencesKey("auto_index")
        val INDEXING_PAUSED = booleanPreferencesKey("indexing_paused")
        val LAST_SYNC_TIME = longPreferencesKey("last_sync_time")
        val LAST_SYNC_RESULT = stringPreferencesKey("last_sync_result")
        val AI_ENGINE_PROVIDER = stringPreferencesKey("ai_engine_provider")
    }

    override val cameraUploadEnabled: Flow<Boolean>
        get() = context.dataStore.data.map { it[Keys.CAMERA_UPLOAD_ENABLED] ?: false }

    override val wifiOnlyUpload: Flow<Boolean>
        get() = context.dataStore.data.map { it[Keys.WIFI_ONLY_UPLOAD] ?: true }

    override val autoIndex: Flow<Boolean>
        get() = context.dataStore.data.map { it[Keys.AUTO_INDEX] ?: true }

    override val indexingPaused: Flow<Boolean>
        get() = context.dataStore.data.map { it[Keys.INDEXING_PAUSED] ?: false }

    override val lastSyncTime: Flow<Long>
        get() = context.dataStore.data.map { it[Keys.LAST_SYNC_TIME] ?: 0L }

    override val lastSyncResult: Flow<String>
        get() = context.dataStore.data.map { it[Keys.LAST_SYNC_RESULT] ?: "" }

    override val aiEngineProvider: Flow<String>
        get() = context.dataStore.data.map { it[Keys.AI_ENGINE_PROVIDER] ?: "none" }

    override suspend fun setCameraUploadEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.CAMERA_UPLOAD_ENABLED] = enabled }
    }

    override suspend fun setWifiOnlyUpload(wifiOnly: Boolean) {
        context.dataStore.edit { it[Keys.WIFI_ONLY_UPLOAD] = wifiOnly }
    }

    override suspend fun setAutoIndex(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_INDEX] = enabled }
    }

    override suspend fun setIndexingPaused(paused: Boolean) {
        context.dataStore.edit { it[Keys.INDEXING_PAUSED] = paused }
    }

    override suspend fun setSyncResult(time: Long, result: String) {
        context.dataStore.edit {
            it[Keys.LAST_SYNC_TIME] = time
            it[Keys.LAST_SYNC_RESULT] = result
        }
    }

    override suspend fun setAiEngineProvider(provider: String) {
        context.dataStore.edit { it[Keys.AI_ENGINE_PROVIDER] = provider }
    }
}
