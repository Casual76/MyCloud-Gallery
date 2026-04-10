package com.mycloudgallery.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mycloudgallery.core.database.dao.MediaItemDao
import com.mycloudgallery.domain.repository.SettingsRepository
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
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val mediaItemDao: MediaItemDao,
    private val workScheduler: WorkScheduler,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        observeSettings()
        refreshIndexingStats()
    }

    private fun observeSettings() {
        viewModelScope.launch {
            combine(
                settingsRepository.cameraUploadEnabled,
                settingsRepository.wifiOnlyUpload,
                settingsRepository.autoIndex,
                settingsRepository.indexingPaused,
                mediaItemDao.getTotalCount(),
            ) { uploadEnabled, wifiOnly, autoIndex, indexPaused, total ->
                _uiState.update { state ->
                    state.copy(
                        cameraUploadEnabled = uploadEnabled,
                        wifiOnlyUpload = wifiOnly,
                        autoIndex = autoIndex,
                        indexingPaused = indexPaused,
                        totalMediaCount = total,
                    )
                }
            }.collect {}
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
        viewModelScope.launch {
            _uiState.update { it.copy(isForcingSyncNow = true) }
            workScheduler.forceSync()
            _uiState.update { it.copy(isForcingSyncNow = false) }
        }
    }

    fun onForceIndexNow() {
        workScheduler.startImmediateIndexing()
        refreshIndexingStats()
    }
}
