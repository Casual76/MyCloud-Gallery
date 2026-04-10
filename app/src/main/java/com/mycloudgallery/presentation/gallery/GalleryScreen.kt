package com.mycloudgallery.presentation.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.mycloudgallery.domain.model.MediaItem
import com.mycloudgallery.presentation.gallery.components.MediaThumbnail
import com.mycloudgallery.presentation.gallery.components.TimelineHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    onMediaClick: (String) -> Unit,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: GalleryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val mediaItems = viewModel.mediaItems.collectAsLazyPagingItems()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GalleryTopBar(
                uiState = uiState,
                scrollBehavior = scrollBehavior,
                onSearchClick = onSearchClick,
                onSettingsClick = onSettingsClick,
                onClearSelection = viewModel::clearSelection,
                onDeleteSelected = viewModel::deleteSelected,
                onSelectAll = {
                    val ids = (0 until mediaItems.itemCount).mapNotNull { mediaItems[it]?.id }
                    viewModel.selectAll(ids)
                },
            )
        },
    ) { innerPadding ->
        GalleryGrid(
            mediaItems = mediaItems,
            uiState = uiState,
            onMediaClick = { mediaId ->
                if (uiState.isSelectionMode) {
                    viewModel.toggleSelection(mediaId)
                } else {
                    onMediaClick(mediaId)
                }
            },
            onMediaLongClick = { mediaId -> viewModel.toggleSelection(mediaId) },
            onColumnCountChange = viewModel::setColumnCount,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryTopBar(
    uiState: GalleryUiState,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
    onSelectAll: () -> Unit,
) {
    TopAppBar(
        title = {
            if (uiState.isSelectionMode) {
                Text("${uiState.selectedIds.size} selezionati")
            } else {
                Text("MyCloud Gallery")
            }
        },
        navigationIcon = {
            if (uiState.isSelectionMode) {
                IconButton(onClick = onClearSelection) {
                    Icon(Icons.Default.Close, contentDescription = "Annulla selezione")
                }
            }
        },
        actions = {
            if (uiState.isSelectionMode) {
                IconButton(onClick = onSelectAll) {
                    Icon(Icons.Default.SelectAll, contentDescription = "Seleziona tutto")
                }
                IconButton(onClick = onDeleteSelected) {
                    Icon(Icons.Default.Delete, contentDescription = "Elimina")
                }
            } else {
                IconButton(onClick = onSearchClick) {
                    Icon(Icons.Default.Search, contentDescription = "Cerca")
                }
                IconButton(onClick = onSettingsClick) {
                    Icon(Icons.Default.Menu, contentDescription = "Menu")
                }
            }
        },
    )
}

@Composable
private fun GalleryGrid(
    mediaItems: LazyPagingItems<MediaItem>,
    uiState: GalleryUiState,
    onMediaClick: (String) -> Unit,
    onMediaLongClick: (String) -> Unit,
    onColumnCountChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    var cumulativeScale by remember { mutableFloatStateOf(1f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(uiState.columnCount) {
                detectTransformGestures { _, _, zoom, _ ->
                    cumulativeScale *= zoom
                    if (cumulativeScale > 1.3f) {
                        onColumnCountChange(uiState.columnCount - 1)
                        cumulativeScale = 1f
                    } else if (cumulativeScale < 0.7f) {
                        onColumnCountChange(uiState.columnCount + 1)
                        cumulativeScale = 1f
                    }
                }
            },
    ) {
        if (mediaItems.itemCount == 0) {
            EmptyGallery(modifier = Modifier.align(Alignment.Center))
        } else {
            // Costruisci la lista con header temporali
            val itemsWithHeaders = buildTimelineList(mediaItems)

            LazyVerticalGrid(
                columns = GridCells.Fixed(uiState.columnCount),
                state = gridState,
                contentPadding = PaddingValues(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsWithHeaders.forEach { item ->
                    when (item) {
                        is TimelineItem.Header -> {
                            item(
                                key = "header_${item.title}",
                                span = { GridItemSpan(maxLineSpan) },
                            ) {
                                TimelineHeader(title = item.title)
                            }
                        }
                        is TimelineItem.Media -> {
                            item(key = item.mediaItem.id) {
                                MediaThumbnail(
                                    mediaItem = item.mediaItem,
                                    isSelected = uiState.selectedIds.contains(item.mediaItem.id),
                                    isSelectionMode = uiState.isSelectionMode,
                                    onClick = { onMediaClick(item.mediaItem.id) },
                                    onLongClick = { onMediaLongClick(item.mediaItem.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyGallery(modifier: Modifier = Modifier) {
    Text(
        text = "Nessuna foto o video.\nSincronizza il NAS per iniziare.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

private sealed interface TimelineItem {
    data class Header(val title: String) : TimelineItem
    data class Media(val mediaItem: MediaItem) : TimelineItem
}

private fun buildTimelineList(mediaItems: LazyPagingItems<MediaItem>): List<TimelineItem> {
    val result = mutableListOf<TimelineItem>()
    var lastSectionKey: String? = null

    for (i in 0 until mediaItems.itemCount) {
        val item = mediaItems.peek(i) ?: continue
        val sectionKey = TimelineGrouper.getSectionKey(item.createdAt)

        if (sectionKey != lastSectionKey) {
            result.add(TimelineItem.Header(TimelineGrouper.getSectionTitle(item.createdAt)))
            lastSectionKey = sectionKey
        }
        result.add(TimelineItem.Media(item))
    }
    return result
}
