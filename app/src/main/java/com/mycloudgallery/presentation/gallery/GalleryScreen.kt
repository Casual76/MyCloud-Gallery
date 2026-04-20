package com.mycloudgallery.presentation.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.size.Size
import com.mycloudgallery.domain.model.MediaItem
import com.mycloudgallery.presentation.gallery.components.MediaThumbnail
import com.mycloudgallery.presentation.gallery.components.TimelineHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    animatedVisibilityScope: AnimatedVisibilityScope,
    onMediaClick: (String) -> Unit,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: GalleryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val mediaItems = viewModel.mediaItems.collectAsLazyPagingItems()
    val scrollBehavior = if (uiState.isSelectionMode) {
        TopAppBarDefaults.pinnedScrollBehavior()
    } else {
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column {
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
                AnimatedVisibility(visible = uiState.isSyncing) {
                    if (uiState.syncTotal > 0) {
                        LinearProgressIndicator(
                            progress = { uiState.syncProgress / uiState.syncTotal.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = !uiState.isSelectionMode,
                enter = scaleIn(animationSpec = spring(stiffness = Spring.StiffnessLow)) + fadeIn(),
                exit = scaleOut(animationSpec = spring(stiffness = Spring.StiffnessLow)) + fadeOut(),
            ) {
                FloatingActionButton(
                    onClick = onSearchClick,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Icon(Icons.Default.Search, contentDescription = "Cerca")
                }
            }
        }
    ) { innerPadding ->
        GalleryGrid(
            mediaItems = mediaItems,
            uiState = uiState,
            animatedVisibilityScope = animatedVisibilityScope,
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
    if (uiState.isSelectionMode) {
        CenterAlignedTopAppBar(
            title = { 
                Text(
                    "${uiState.selectedIds.size} selezionati",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                ) 
            },
            navigationIcon = {
                IconButton(onClick = onClearSelection) {
                    Icon(Icons.Default.Close, contentDescription = "Annulla selezione")
                }
            },
            actions = {
                IconButton(onClick = onSelectAll) {
                    Icon(Icons.Default.SelectAll, contentDescription = "Seleziona tutto")
                }
                IconButton(onClick = onDeleteSelected) {
                    Icon(Icons.Default.Delete, contentDescription = "Elimina", tint = MaterialTheme.colorScheme.error)
                }
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
            ),
        )
    } else {
        LargeTopAppBar(
            title = { 
                Text(
                    "MyCloud Gallery",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
                ) 
            },
            actions = {
                IconButton(onClick = onSettingsClick) {
                    Icon(Icons.Default.Menu, contentDescription = "Menu")
                }
            },
            scrollBehavior = scrollBehavior,
        )
    }
}

@Composable
private fun GalleryGrid(
    mediaItems: LazyPagingItems<MediaItem>,
    uiState: GalleryUiState,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onMediaClick: (String) -> Unit,
    onMediaLongClick: (String) -> Unit,
    onColumnCountChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    val context = LocalContext.current
    val imageLoader = SingletonImageLoader.get(context)

    // Aggressive prefetching for smooth scrolling
    LaunchedEffect(gridState.firstVisibleItemIndex) {
        val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        val totalCount = mediaItems.itemCount

        if (totalCount > 0 && lastVisible < totalCount - 1) {
            withContext(Dispatchers.Default) {
                val start = lastVisible + 1
                val end = (start + 24).coerceAtMost(totalCount) // 24 = 3-4 rows depending on columns

                for (i in start until end) {
                    val item = mediaItems[i]
                    if (item != null) {
                        val request = ImageRequest.Builder(context)
                            .data(item.thumbnailCachePath ?: "smb://${item.webDavPath}")
                            .size(Size.ORIGINAL) // Thumbnail size is already handled by IndexingWorker
                            .memoryCacheKey("${item.id}_thumb")
                            .build()
                        imageLoader.enqueue(request)
                    }
                }
            }
        }
    }

    var cumulativeScale by remember { mutableFloatStateOf(1f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(uiState.columnCount) {
                detectTransformGestures { _, _, zoom, _ ->
                    cumulativeScale *= zoom
                    if (cumulativeScale > 1.3f && uiState.columnCount > 2) {
                        onColumnCountChange(uiState.columnCount - 1)
                        cumulativeScale = 1f
                    } else if (cumulativeScale < 0.7f && uiState.columnCount < 5) {
                        onColumnCountChange(uiState.columnCount + 1)
                        cumulativeScale = 1f
                    }
                }
            },
    ) {
        if (mediaItems.itemCount == 0 && !uiState.isSyncing) {
            EmptyGallery(modifier = Modifier.align(Alignment.Center))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(uiState.columnCount),
                state = gridState,
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.animateContentSize(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                )
            ) {
                // Items already loaded into the snapshot — rendered with date headers
                val snapshot = mediaItems.itemSnapshotList.items
                var lastSectionKey: String? = null
                snapshot.forEachIndexed { index, item ->
                    val sectionKey = TimelineGrouper.getSectionKey(item.createdAt)
                    if (sectionKey != lastSectionKey) {
                        lastSectionKey = sectionKey
                        item(
                            key = "header_$sectionKey",
                            span = { GridItemSpan(maxLineSpan) },
                        ) {
                            TimelineHeader(
                                title = TimelineGrouper.getSectionTitle(item.createdAt),
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    }
                    item(key = item.id) {
                        MediaThumbnail(
                            mediaItem = item,
                            isSelected = uiState.selectedIds.contains(item.id),
                            isSelectionMode = uiState.isSelectionMode,
                            onClick = { onMediaClick(item.id) },
                            onLongClick = { onMediaLongClick(item.id) },
                            animatedVisibilityScope = animatedVisibilityScope,
                        )
                    }
                }

                // Remaining pages not yet loaded — no header, plain placeholders
                val loadedCount = snapshot.size
                val remaining = mediaItems.itemCount - loadedCount
                if (remaining > 0) {
                    items(
                        count = remaining,
                        key = { offset -> "placeholder_${loadedCount + offset}" },
                    ) { offset ->
                        val mediaItem = mediaItems[loadedCount + offset]
                        if (mediaItem != null) {
                            MediaThumbnail(
                                mediaItem = mediaItem,
                                isSelected = uiState.selectedIds.contains(mediaItem.id),
                                isSelectionMode = uiState.isSelectionMode,
                                onClick = { onMediaClick(mediaItem.id) },
                                onLongClick = { onMediaLongClick(mediaItem.id) },
                                animatedVisibilityScope = animatedVisibilityScope,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyGallery(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            modifier = Modifier.size(120.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
            shape = CircleShape
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.PhotoLibrary,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Ancora nulla qui",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Sincronizza il tuo NAS MyCloud per visualizzare qui le tue foto e i tuoi video.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
