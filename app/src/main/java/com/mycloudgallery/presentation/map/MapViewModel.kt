package com.mycloudgallery.presentation.map

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

data class MapUiState(
    val mediaWithGps: List<MediaItem> = emptyList(),
    val selectedYearFilter: Int? = null,
    val availableYears: List<Int> = emptyList(),
    val selectedItem: MediaItem? = null,
    val isLoading: Boolean = true,
)

@HiltViewModel
class MapViewModel @Inject constructor(
    private val mediaItemDao: MediaItemDao,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    init {
        observeGpsMedia()
    }

    private fun observeGpsMedia() {
        viewModelScope.launch {
            mediaItemDao.getAllWithGps().collectLatest { entities ->
                val items = entities.map { it.toDomain() }
                val years = items
                    .map { java.util.Calendar.getInstance().apply { timeInMillis = it.createdAt }.get(java.util.Calendar.YEAR) }
                    .distinct()
                    .sortedDescending()
                _uiState.update { state ->
                    state.copy(
                        mediaWithGps = applyYearFilter(items, state.selectedYearFilter),
                        availableYears = years,
                        isLoading = false,
                    )
                }
            }
        }
    }

    fun onYearFilterChanged(year: Int?) {
        _uiState.update { state ->
            state.copy(
                selectedYearFilter = year,
                mediaWithGps = applyYearFilter(
                    mediaItemDao.let { _ ->
                        // Viene aggiornato dal collector sopra; filtriamo in memoria
                        state.mediaWithGps
                    },
                    year,
                ),
            )
        }
        // Rilancia la query con il nuovo filtro
        viewModelScope.launch {
            val all = mediaItemDao.getAll().map { it.toDomain() }.filter {
                it.exifLatitude != null && it.exifLongitude != null
            }
            _uiState.update { it.copy(mediaWithGps = applyYearFilter(all, year)) }
        }
    }

    fun onItemSelected(item: MediaItem?) {
        _uiState.update { it.copy(selectedItem = item) }
    }

    private fun applyYearFilter(items: List<MediaItem>, year: Int?): List<MediaItem> {
        if (year == null) return items
        return items.filter {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = it.createdAt }
            cal.get(java.util.Calendar.YEAR) == year
        }
    }
}
