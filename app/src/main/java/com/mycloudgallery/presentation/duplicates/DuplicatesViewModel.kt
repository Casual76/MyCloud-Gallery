package com.mycloudgallery.presentation.duplicates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mycloudgallery.core.database.dao.MediaItemDao
import com.mycloudgallery.core.util.toDomain
import com.mycloudgallery.domain.model.MediaItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Raggruppa i media per duplicateGroupId. */
data class DuplicateGroup(
    val groupId: String,
    val items: List<MediaItem>,
)

data class DuplicatesUiState(
    val groups: List<DuplicateGroup> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val isLoading: Boolean = true,
    val isDeletingSelection: Boolean = false,
)

@HiltViewModel
class DuplicatesViewModel @Inject constructor(
    private val mediaItemDao: MediaItemDao,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DuplicatesUiState())
    val uiState: StateFlow<DuplicatesUiState> = _uiState.asStateFlow()

    init {
        loadDuplicates()
    }

    private fun loadDuplicates() {
        viewModelScope.launch {
            mediaItemDao.getAllDuplicates().collectLatest { entities ->
                val groups = entities
                    .filter { it.duplicateGroupId != null }
                    .groupBy { it.duplicateGroupId!! }
                    .filter { (_, items) -> items.size > 1 }
                    .map { (groupId, items) ->
                        DuplicateGroup(
                            groupId = groupId,
                            items = items.sortedBy { it.createdAt }.map { it.toDomain() },
                        )
                    }
                    .sortedByDescending { it.items.size }
                _uiState.update { it.copy(groups = groups, isLoading = false) }
            }
        }
    }

    fun onToggleSelect(id: String) {
        _uiState.update { state ->
            val updated = if (id in state.selectedIds) state.selectedIds - id
                          else state.selectedIds + id
            state.copy(selectedIds = updated)
        }
    }

    fun onSelectAllInGroup(group: DuplicateGroup) {
        _uiState.update { state ->
            // Seleziona tutti tranne il primo (il più antico = l'originale)
            val toSelect = group.items.drop(1).map { it.id }.toSet()
            state.copy(selectedIds = state.selectedIds + toSelect)
        }
    }

    fun onClearSelection() {
        _uiState.update { it.copy(selectedIds = emptySet()) }
    }

    /** Sposta nel cestino tutti i media selezionati. Non elimina mai automaticamente. */
    fun deleteSelected() {
        val ids = _uiState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isDeletingSelection = true) }
            mediaItemDao.moveToTrashBatch(ids)
            _uiState.update { it.copy(selectedIds = emptySet(), isDeletingSelection = false) }
        }
    }
}
