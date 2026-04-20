package com.mycloudgallery.presentation.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mycloudgallery.domain.model.MediaType
import com.mycloudgallery.domain.model.SearchFilter
import com.mycloudgallery.presentation.gallery.components.MediaThumbnail

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    animatedVisibilityScope: AnimatedVisibilityScope,
    onMediaClick: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var searchBarActive by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {

        // --- SearchBar M3 ---
        SearchBar(
            inputField = {
                SearchBarDefaults.InputField(
                    query = uiState.query,
                    onQueryChange = viewModel::onQueryChanged,
                    onSearch = { query ->
                        viewModel.onSearchSubmit(query)
                        searchBarActive = false
                    },
                    expanded = searchBarActive,
                    onExpandedChange = { searchBarActive = it },
                    placeholder = { Text("Cerca foto, luoghi, testi…") },
                    leadingIcon = {
                        if (searchBarActive) {
                            IconButton(onClick = { searchBarActive = false }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Chiudi ricerca")
                            }
                        } else {
                            IconButton(onClick = onBack) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Indietro")
                            }
                        }
                    },
                    trailingIcon = {
                        if (uiState.query.isNotBlank()) {
                            IconButton(onClick = viewModel::clearQuery) {
                                Icon(Icons.Default.Close, contentDescription = "Cancella")
                            }
                        }
                    },
                )
            },
            expanded = searchBarActive,
            onExpandedChange = { searchBarActive = it },
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Suggerimenti recenti
            if (uiState.recentQueries.isNotEmpty()) {
                Text(
                    text = "Ricerche recenti",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                uiState.recentQueries.reversed().forEach { recent ->
                    SuggestionChip(
                        onClick = {
                            viewModel.onQueryChanged(recent)
                            searchBarActive = false
                        },
                        label = { Text(recent) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                }
            }
        }

        // --- Chips filtri ---
        FilterChipsRow(
            filter = uiState.filter,
            onFilterChanged = viewModel::onFilterChanged,
        )

        // --- Risultati ---
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                uiState.query.isBlank() && uiState.results.isEmpty() -> {
                    SearchEmptyHint(modifier = Modifier.align(Alignment.Center))
                }
                !uiState.isLoading && uiState.results.isEmpty() -> {
                    Text(
                        text = "Nessun risultato per \"${uiState.query}\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                    )
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        item(span = { GridItemSpan(3) }) {
                            Text(
                                text = "${uiState.results.size} risultati",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                        items(uiState.results, key = { it.id }) { item ->
                            MediaThumbnail(
                                mediaItem = item,
                                isSelected = false,
                                isSelectionMode = false,
                                onClick = { onMediaClick(item.id) },
                                onLongClick = { /* No-op in search if not needed */ },
                                animatedVisibilityScope = animatedVisibilityScope
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterChipsRow(
    filter: SearchFilter,
    onFilterChanged: (SearchFilter) -> Unit,
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(
            selected = filter.mediaType == MediaType.IMAGE,
            onClick = {
                onFilterChanged(filter.copy(mediaType = if (filter.mediaType == MediaType.IMAGE) null else MediaType.IMAGE))
            },
            label = { Text("Foto") },
            leadingIcon = { Icon(Icons.Default.PhotoCamera, contentDescription = null) },
        )
        FilterChip(
            selected = filter.mediaType == MediaType.VIDEO,
            onClick = {
                onFilterChanged(filter.copy(mediaType = if (filter.mediaType == MediaType.VIDEO) null else MediaType.VIDEO))
            },
            label = { Text("Video") },
            leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
        )
        FilterChip(
            selected = filter.hasGps == true,
            onClick = {
                onFilterChanged(filter.copy(hasGps = if (filter.hasGps == true) null else true))
            },
            label = { Text("Con GPS") },
            leadingIcon = { Icon(Icons.Default.LocationOn, contentDescription = null) },
        )
        FilterChip(
            selected = filter.hasOcr == true,
            onClick = {
                onFilterChanged(filter.copy(hasOcr = if (filter.hasOcr == true) null else true))
            },
            label = { Text("Con testo") },
            leadingIcon = { Icon(Icons.Default.TextFields, contentDescription = null) },
        )
        FilterChip(
            selected = filter.favoritesOnly,
            onClick = {
                onFilterChanged(filter.copy(favoritesOnly = !filter.favoritesOnly))
            },
            label = { Text("Preferiti") },
            leadingIcon = { Icon(Icons.Default.Favorite, contentDescription = null) },
        )
    }
}

@Composable
private fun SearchEmptyHint(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Cerca nelle tue foto",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "Prova: \"cane\", \"spiaggia\", \"2024\", \"ricevuta\"",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
