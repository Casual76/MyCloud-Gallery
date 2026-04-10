package com.mycloudgallery.presentation.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.mycloudgallery.domain.model.MediaItem
import com.mycloudgallery.domain.repository.MediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TrashViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
) : ViewModel() {

    val trashedItems: Flow<PagingData<MediaItem>> = mediaRepository.getTrash()
        .cachedIn(viewModelScope)

    private val _uiState = MutableStateFlow(TrashUiState())
    val uiState: StateFlow<TrashUiState> = _uiState.asStateFlow()

    fun toggleSelection(mediaId: String) {
        _uiState.update { state ->
            val updated = state.selectedIds.toMutableSet()
            if (!updated.add(mediaId)) updated.remove(mediaId)
            state.copy(selectedIds = updated, isSelectionMode = updated.isNotEmpty())
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedIds = emptySet(), isSelectionMode = false) }
    }

    fun restoreSelected() {
        val ids = _uiState.value.selectedIds.toList()
        viewModelScope.launch {
            ids.forEach { mediaRepository.restoreFromTrash(it) }
            clearSelection()
        }
    }

    fun deleteSelectedPermanently() {
        val ids = _uiState.value.selectedIds.toList()
        viewModelScope.launch {
            mediaRepository.deletePermanentlyBatch(ids)
            clearSelection()
        }
    }

    fun emptyTrash() {
        viewModelScope.launch {
            mediaRepository.deleteExpiredTrash()
            // Forza eliminazione di tutto ciò che è nel cestino
            _uiState.update { it.copy(showEmptyConfirmDialog = false) }
        }
    }

    fun showEmptyConfirmDialog() {
        _uiState.update { it.copy(showEmptyConfirmDialog = true) }
    }

    fun dismissEmptyConfirmDialog() {
        _uiState.update { it.copy(showEmptyConfirmDialog = false) }
    }
}

data class TrashUiState(
    val selectedIds: Set<String> = emptySet(),
    val isSelectionMode: Boolean = false,
    val showEmptyConfirmDialog: Boolean = false,
)
