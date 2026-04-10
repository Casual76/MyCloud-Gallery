package com.mycloudgallery.presentation.viewer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.mycloudgallery.domain.model.MediaItem
import com.mycloudgallery.domain.repository.MediaRepository
import com.mycloudgallery.presentation.navigation.ViewerRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val mediaRepository: MediaRepository,
) : ViewModel() {

    private val route: ViewerRoute = savedStateHandle.toRoute()

    private val _uiState = MutableStateFlow(ViewerUiState())
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    init {
        loadMedia()
    }

    private fun loadMedia() {
        viewModelScope.launch {
            val item = mediaRepository.getMediaById(route.mediaId)
            if (item != null) {
                _uiState.update {
                    it.copy(
                        currentItem = item,
                        isLoading = false,
                    )
                }
            } else {
                _uiState.update { it.copy(isLoading = false, error = "Media non trovato") }
            }
        }
    }

    fun toggleOverlay() {
        _uiState.update { it.copy(showOverlay = !it.showOverlay) }
    }

    fun toggleFavorite() {
        val item = _uiState.value.currentItem ?: return
        viewModelScope.launch {
            mediaRepository.toggleFavorite(item.id)
            _uiState.update {
                it.copy(currentItem = item.copy(isFavorite = !item.isFavorite))
            }
        }
    }

    fun moveToTrash() {
        val item = _uiState.value.currentItem ?: return
        viewModelScope.launch {
            mediaRepository.moveToTrash(item.id)
            _uiState.update { it.copy(deleted = true) }
        }
    }

    fun showExifSheet() {
        _uiState.update { it.copy(showExifSheet = true) }
    }

    fun hideExifSheet() {
        _uiState.update { it.copy(showExifSheet = false) }
    }
}

data class ViewerUiState(
    val currentItem: MediaItem? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val showOverlay: Boolean = true,
    val showExifSheet: Boolean = false,
    val deleted: Boolean = false,
)
