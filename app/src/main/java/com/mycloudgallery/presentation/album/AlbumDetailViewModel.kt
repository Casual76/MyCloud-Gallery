package com.mycloudgallery.presentation.album

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.mycloudgallery.domain.model.MediaItem
import com.mycloudgallery.domain.repository.AlbumRepository
import com.mycloudgallery.domain.repository.MediaRepository
import com.mycloudgallery.presentation.navigation.AlbumDetailRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AlbumDetailUiState(
    val albumName: String = "",
    val media: List<MediaItem> = emptyList(),
    val isFavoritesAlbum: Boolean = false,
    val isLoading: Boolean = true,
    val selectedMediaIds: Set<String> = emptySet(),
    val showDeleteSelectionConfirm: Boolean = false,
)

@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val albumRepository: AlbumRepository,
    private val mediaRepository: MediaRepository,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<AlbumDetailRoute>()
    val albumId: String = route.albumId
    val isFavoritesAlbum: Boolean = route.isFavorites

    private val _extraState = MutableStateFlow(
        AlbumDetailUiState(
            albumName = if (isFavoritesAlbum) "Preferiti" else "",
            isFavoritesAlbum = isFavoritesAlbum,
        )
    )

    val uiState: StateFlow<AlbumDetailUiState> = if (isFavoritesAlbum) {
        // Album virtuale — media da MediaRepository (Paging non usato qui per semplicità)
        _extraState.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AlbumDetailUiState(
                albumName = "Preferiti",
                isFavoritesAlbum = true,
            ),
        )
    } else {
        albumRepository.getMediaForAlbum(albumId)
            .map { media ->
                _extraState.value.copy(
                    media = media,
                    isLoading = false,
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = AlbumDetailUiState(isLoading = true),
            )
    }

    init {
        if (isFavoritesAlbum) loadFavorites()
    }

    private fun loadFavorites() {
        viewModelScope.launch {
            mediaRepository.getFavorites()
                // Colleziona il primo PagingData e si ferma (per semplicità nella vista Album)
                .collect { _ ->
                    // Il Pager emette dati paginati; per AlbumDetail usiamo una lista flat
                    // dal DAO direttamente — MediaRepository.getFavoritesList() non è ancora
                    // esposta, quindi qui lo stato rimane gestito con il DAO via flow separato
                }
        }
    }

    // --- Selezione multipla ---

    fun onToggleSelection(mediaId: String) = _extraState.update { state ->
        val newSet = state.selectedMediaIds.toMutableSet()
        if (newSet.contains(mediaId)) newSet.remove(mediaId) else newSet.add(mediaId)
        state.copy(selectedMediaIds = newSet)
    }

    fun onClearSelection() = _extraState.update { it.copy(selectedMediaIds = emptySet()) }

    fun onRequestRemoveSelected() = _extraState.update {
        it.copy(showDeleteSelectionConfirm = true)
    }

    fun onDismissRemoveSelected() = _extraState.update {
        it.copy(showDeleteSelectionConfirm = false)
    }

    /** Rimuove i media selezionati dall'album (non li elimina dal NAS). */
    fun onConfirmRemoveSelected() {
        val ids = _extraState.value.selectedMediaIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            albumRepository.removeMediaFromAlbum(albumId, ids)
            _extraState.update {
                it.copy(selectedMediaIds = emptySet(), showDeleteSelectionConfirm = false)
            }
        }
    }
}
