package com.mycloudgallery.presentation.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mycloudgallery.domain.model.MediaItem
import com.mycloudgallery.domain.model.MediaType
import com.mycloudgallery.domain.model.SearchFilter
import com.mycloudgallery.domain.repository.SearchRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val results: List<MediaItem> = emptyList(),
    val isLoading: Boolean = false,
    val filter: SearchFilter = SearchFilter(),
    val recentQueries: List<String> = emptyList(),
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchRepository: SearchRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val _queryFlow = MutableStateFlow("")

    init {
        observeQuery()
    }

    @OptIn(FlowPreview::class)
    private fun observeQuery() {
        viewModelScope.launch {
            _queryFlow
                .debounce(300)
                .collectLatest { query ->
                    performSearch(query, _uiState.value.filter)
                }
        }
    }

    fun onQueryChanged(query: String) {
        _uiState.update { it.copy(query = query) }
        _queryFlow.value = query
    }

    fun onFilterChanged(filter: SearchFilter) {
        _uiState.update { it.copy(filter = filter) }
        performSearch(_uiState.value.query, filter)
    }

    fun onSearchSubmit(query: String) {
        if (query.isBlank()) return
        val recent = (_uiState.value.recentQueries + query)
            .distinct()
            .takeLast(10)
        _uiState.update { it.copy(recentQueries = recent) }
    }

    fun clearQuery() {
        _uiState.update { it.copy(query = "", results = emptyList()) }
        _queryFlow.value = ""
    }

    private fun performSearch(query: String, filter: SearchFilter) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val results = runCatching {
                searchRepository.search(query, filter)
            }.getOrDefault(emptyList())
            _uiState.update { it.copy(results = results, isLoading = false) }
        }
    }
}
