package com.mycloudgallery.presentation.album

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mycloudgallery.domain.model.Album
import com.mycloudgallery.domain.repository.AlbumRepository
import com.mycloudgallery.domain.repository.SharedAlbumRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AlbumsUiState(
    val albums: List<Album> = emptyList(),
    val favoritesCount: Int = 0,
    val pendingRequestsCount: Int = 0,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    /** Album selezionato per opzioni rapide (rinomina/elimina). */
    val contextMenuAlbumId: String? = null,
    /** true = dialog "Nuovo album" visibile. */
    val showCreateDialog: Boolean = false,
    /** Nome digitato nel dialog di creazione/rinomina. */
    val dialogInputName: String = "",
    /** Se non null → dialog di rinomina per quell'album ID. */
    val renameAlbumId: String? = null,
    /** Se non null → dialog di conferma eliminazione per quell'album ID. */
    val deleteAlbumId: String? = null,
)

@HiltViewModel
class AlbumsViewModel @Inject constructor(
    private val albumRepository: AlbumRepository,
    private val sharedAlbumRepository: SharedAlbumRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AlbumsUiState())
    val uiState: StateFlow<AlbumsUiState> = combine(
        albumRepository.getAll(),
        albumRepository.getFavoritesCount(),
        sharedAlbumRepository.getPendingRequestsCount(),
    ) { albums, favCount, pendingCount ->
        _uiState.value.copy(
            albums = albums,
            favoritesCount = favCount,
            pendingRequestsCount = pendingCount,
            isLoading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AlbumsUiState(isLoading = true),
    )

    // --- Dialog "Nuovo album" ---

    fun onShowCreateDialog() = _uiState.update {
        it.copy(showCreateDialog = true, dialogInputName = "")
    }

    fun onDialogNameChanged(name: String) = _uiState.update {
        it.copy(dialogInputName = name)
    }

    fun onDismissDialog() = _uiState.update {
        it.copy(showCreateDialog = false, renameAlbumId = null, dialogInputName = "")
    }

    fun onCreateAlbum() {
        val name = _uiState.value.dialogInputName.trim()
        if (name.isBlank()) return
        viewModelScope.launch {
            try {
                albumRepository.createAlbum(name)
                _uiState.update { it.copy(showCreateDialog = false, dialogInputName = "") }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message) }
            }
        }
    }

    // --- Context menu (long press su card) ---

    fun onShowContextMenu(albumId: String) = _uiState.update {
        it.copy(contextMenuAlbumId = albumId)
    }

    fun onDismissContextMenu() = _uiState.update {
        it.copy(contextMenuAlbumId = null)
    }

    // --- Rinomina ---

    fun onStartRename(albumId: String) {
        val currentName = _uiState.value.albums.find { it.id == albumId }?.name.orEmpty()
        _uiState.update {
            it.copy(
                renameAlbumId = albumId,
                dialogInputName = currentName,
                contextMenuAlbumId = null,
            )
        }
    }

    fun onConfirmRename() {
        val albumId = _uiState.value.renameAlbumId ?: return
        val newName = _uiState.value.dialogInputName.trim()
        if (newName.isBlank()) return
        viewModelScope.launch {
            try {
                albumRepository.renameAlbum(albumId, newName)
                _uiState.update { it.copy(renameAlbumId = null, dialogInputName = "") }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message, renameAlbumId = null) }
            }
        }
    }

    // --- Elimina ---

    fun onRequestDelete(albumId: String) = _uiState.update {
        it.copy(deleteAlbumId = albumId, contextMenuAlbumId = null)
    }

    fun onDismissDelete() = _uiState.update { it.copy(deleteAlbumId = null) }

    fun onConfirmDelete() {
        val albumId = _uiState.value.deleteAlbumId ?: return
        viewModelScope.launch {
            try {
                albumRepository.deleteAlbum(albumId)
                _uiState.update { it.copy(deleteAlbumId = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message, deleteAlbumId = null) }
            }
        }
    }

    fun onErrorShown() = _uiState.update { it.copy(errorMessage = null) }
}
