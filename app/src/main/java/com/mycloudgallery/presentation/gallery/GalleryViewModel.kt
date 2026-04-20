package com.mycloudgallery.presentation.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.mycloudgallery.domain.model.MediaItem
import com.mycloudgallery.domain.model.NetworkMode
import com.mycloudgallery.domain.repository.MediaRepository
import com.mycloudgallery.core.network.NetworkDetector
import com.mycloudgallery.worker.SyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val networkDetector: NetworkDetector,
    private val workManager: WorkManager,
) : ViewModel() {

    val mediaItems: Flow<PagingData<MediaItem>> = mediaRepository.getAllMedia()
        .cachedIn(viewModelScope)

    private val _uiState = MutableStateFlow(GalleryUiState())
    val uiState: StateFlow<GalleryUiState> = _uiState.asStateFlow()

    val networkMode: StateFlow<NetworkMode> = networkDetector.networkMode

    init {
        observeMediaCount()
        observeSyncStatus()
    }

    private fun observeMediaCount() {
        viewModelScope.launch {
            mediaRepository.getTotalCount().collect { count ->
                _uiState.update { it.copy(totalMediaCount = count) }
            }
        }
    }

    private fun observeSyncStatus() {
        viewModelScope.launch {
            workManager.getWorkInfosByTagFlow(SyncWorker.WORK_NAME).collect { workInfos ->
                val activeWork = workInfos.find { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
                
                _uiState.update { state ->
                    state.copy(
                        isSyncing = activeWork != null,
                        syncProgress = activeWork?.progress?.getFloat(SyncWorker.KEY_PROCESSED, 0f) ?: 0f,
                        syncTotal = activeWork?.progress?.getInt(SyncWorker.KEY_TOTAL, 0) ?: 0
                    )
                }
            }
        }
    }

    fun setColumnCount(count: Int) {
        _uiState.update { it.copy(columnCount = count.coerceIn(2, 5)) }
    }

    fun toggleSelection(mediaId: String) {
        _uiState.update { state ->
            val newSelection = state.selectedIds.toMutableSet()
            if (newSelection.contains(mediaId)) {
                newSelection.remove(mediaId)
            } else {
                newSelection.add(mediaId)
            }
            state.copy(
                selectedIds = newSelection,
                isSelectionMode = newSelection.isNotEmpty(),
            )
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedIds = emptySet(), isSelectionMode = false) }
    }

    fun selectAll(ids: List<String>) {
        _uiState.update { it.copy(selectedIds = ids.toSet(), isSelectionMode = true) }
    }

    fun deleteSelected() {
        val ids = _uiState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            mediaRepository.moveToTrashBatch(ids)
            clearSelection()
        }
    }

    fun toggleFavorite(mediaId: String) {
        viewModelScope.launch {
            mediaRepository.toggleFavorite(mediaId)
        }
    }
}

data class GalleryUiState(
    val columnCount: Int = 3,
    val selectedIds: Set<String> = emptySet(),
    val isSelectionMode: Boolean = false,
    val totalMediaCount: Int = 0,
    val isSyncing: Boolean = false,
    val syncProgress: Float = 0f,
    val syncTotal: Int = 0,
)
