package com.mycloudgallery.presentation.album

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.mycloudgallery.domain.model.MediaItem

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AlbumDetailScreen(
    onBack: () -> Unit,
    onMediaClick: (mediaId: String) -> Unit,
    viewModel: AlbumDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isSelecting = uiState.selectedMediaIds.isNotEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (isSelecting)
                            "${uiState.selectedMediaIds.size} selezionati"
                        else
                            uiState.albumName
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = if (isSelecting) viewModel::onClearSelection else onBack,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                },
                actions = {
                    if (isSelecting && !uiState.isFavoritesAlbum) {
                        IconButton(onClick = viewModel::onRequestRemoveSelected) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Rimuovi dall'album",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->

        if (uiState.media.isEmpty() && !uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (uiState.isFavoritesAlbum)
                        "Nessun preferito ancora.\nPremi ♥ su una foto per aggiungerla."
                    else
                        "Album vuoto.\nAggiungi foto dall'app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding() + 4.dp,
                    bottom = innerPadding.calculateBottomPadding() + 4.dp,
                    start = 4.dp,
                    end = 4.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(
                    items = uiState.media,
                    key = { it.id },
                ) { item ->
                    MediaGridCell(
                        item = item,
                        isSelected = item.id in uiState.selectedMediaIds,
                        isSelecting = isSelecting,
                        onTap = {
                            if (isSelecting)
                                viewModel.onToggleSelection(item.id)
                            else
                                onMediaClick(item.id)
                        },
                        onLongPress = { viewModel.onToggleSelection(item.id) },
                    )
                }
            }
        }
    }

    // Conferma rimozione
    if (uiState.showDeleteSelectionConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::onDismissRemoveSelected,
            title = { Text("Rimuovi dall'album") },
            text = {
                Text(
                    "Rimuovere ${uiState.selectedMediaIds.size} elementi dall'album? " +
                        "I file sul NAS non verranno toccati."
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::onConfirmRemoveSelected) { Text("Rimuovi") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::onDismissRemoveSelected) { Text("Annulla") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaGridCell(
    item: MediaItem,
    isSelected: Boolean,
    isSelecting: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
    ) {
        AsyncImage(
            model = item.webDavPath,
            contentDescription = item.fileName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        // Overlay selezione
        if (isSelecting) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp),
            ) {
                Icon(
                    imageVector = if (isSelected) Icons.Default.CheckCircle
                    else Icons.Default.RadioButtonUnchecked,
                    contentDescription = if (isSelected) "Selezionato" else "Non selezionato",
                    tint = if (isSelected)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}
