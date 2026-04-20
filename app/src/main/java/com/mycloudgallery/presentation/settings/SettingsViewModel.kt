package com.mycloudgallery.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.mycloudgallery.core.database.dao.MediaItemDao
import com.mycloudgallery.domain.repository.SettingsRepository
import com.mycloudgallery.worker.IndexingWorker
import com.mycloudgallery.worker.SyncWorker
import com.mycloudgallery.worker.WorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val cameraUploadEnabled: Boolean = false,
    val wifiOnlyUpload: Boolean = true,
    val autoIndex: Boolean = true,
    val indexingPaused: Boolean = false,
    val totalMediaCount: Int = 0,
    val unindexedCount: Int = 0,
    val isForcingSyncNow: Boolean = false,
    val isIndexing: Boolean = false,
    val indexingProgress: Int = 0,
    val lastSyncTime: Long = 0,
    val lastSyncResult: String = "",
    val aiEngineProvider: String = "none",
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val mediaItemDao: MediaItemDao,
    private val workScheduler: WorkScheduler,
    private val workManager: WorkManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        observeSettings()
        observeAiEngineProvider()
        refreshIndexingStats()
        observeSyncWorker()
        observeIndexingWorker()
    }

    private fun observeIndexingWorker() {
        viewModelScope.launch {
            workManager.getWorkInfosByTagFlow(IndexingWorker.WORK_NAME).collect { workInfos ->
                val activeWork = workInfos.find { it.state == WorkInfo.State.RUNNING }
                _uiState.update { state ->
                    state.copy(
                        isIndexing = activeWork != null,
                        indexingProgress = activeWork?.progress?.getInt(IndexingWorker.KEY_INDEXED, 0) ?: 0
                    )
                }
                if (workInfos.any { it.state == WorkInfo.State.SUCCEEDED }) {
                    refreshIndexingStats()
                }
            }
        }
    }

    private fun observeSyncWorker() {
        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow("${SyncWorker.WORK_NAME}_manual")
                .collect { workInfos ->
                    val isSyncing = workInfos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
                    _uiState.update { it.copy(isForcingSyncNow = isSyncing) }
                    if (workInfos.any { it.state == WorkInfo.State.SUCCEEDED }) {
                        refreshIndexingStats()
                    }
                }
        }
    }

    private fun observeSettings() {
        viewModelScope.launch {
            combine(
                settingsRepository.cameraUploadEnabled,
                settingsRepository.wifiOnlyUpload,
                settingsRepository.autoIndex,
                settingsRepository.indexingPaused,
                settingsRepository.lastSyncTime,
                settingsRepository.lastSyncResult,
                mediaItemDao.getTotalCount(),
            ) { array ->
                val uploadEnabled = array[0] as Boolean
                val wifiOnly = array[1] as Boolean
                val autoIndex = array[2] as Boolean
                val indexPaused = array[3] as Boolean
                val syncTime = array[4] as Long
                val syncResult = array[5] as String
                val total = array[6] as Int

                _uiState.update { state ->
                    state.copy(
                        cameraUploadEnabled = uploadEnabled,
                        wifiOnlyUpload = wifiOnly,
                        autoIndex = autoIndex,
                        indexingPaused = indexPaused,
                        lastSyncTime = syncTime,
                        lastSyncResult = syncResult,
                        totalMediaCount = total,
                    )
                }
            }.collect {}
        }
    }

    private fun observeAiEngineProvider() {
        viewModelScope.launch {
            settingsRepository.aiEngineProvider.collect { provider ->
                _uiState.update { it.copy(aiEngineProvider = provider) }
            }
        }
    }

    private fun refreshIndexingStats() {
        viewModelScope.launch {
            val unindexed = mediaItemDao.getUnindexedCount()
            _uiState.update { it.copy(unindexedCount = unindexed) }
        }
    }

    fun onCameraUploadToggled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setCameraUploadEnabled(enabled)
            if (enabled) workScheduler.scheduleCameraUpload()
        }
    }

    fun onWifiOnlyToggled(wifiOnly: Boolean) {
        viewModelScope.launch { settingsRepository.setWifiOnlyUpload(wifiOnly) }
    }

    fun onAutoIndexToggled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoIndex(enabled)
            if (enabled) workScheduler.startImmediateIndexing()
        }
    }

    fun onIndexingPausedToggled(paused: Boolean) {
        viewModelScope.launch {
            settingsRepository.setIndexingPaused(paused)
            if (!paused) workScheduler.startImmediateIndexing()
        }
    }

    fun onForceSyncNow() {
        workScheduler.forceSync()
    }

    fun onForceIndexNow() {
        workScheduler.startImmediateIndexing()
        refreshIndexingStats()
    }

    fun onAiEngineProviderSelected(provider: String) {
        viewModelScope.launch { settingsRepository.setAiEngineProvider(provider) }
    }
}
