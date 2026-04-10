package com.mycloudgallery.presentation.trash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.RestoreFromTrash
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import com.mycloudgallery.presentation.gallery.components.MediaThumbnail

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    onBack: () -> Unit,
    viewModel: TrashViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val items = viewModel.trashedItems.collectAsLazyPagingItems()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (uiState.isSelectionMode) {
                        Text("${uiState.selectedIds.size} selezionati")
                    } else {
                        Text("Cestino")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = if (uiState.isSelectionMode) viewModel::clearSelection else onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro")
                    }
                },
                actions = {
                    if (uiState.isSelectionMode) {
                        IconButton(onClick = viewModel::restoreSelected) {
                            Icon(Icons.Default.RestoreFromTrash, "Ripristina")
                        }
                        IconButton(onClick = viewModel::deleteSelectedPermanently) {
                            Icon(Icons.Default.DeleteForever, "Elimina definitivamente")
                        }
                    } else {
                        IconButton(onClick = viewModel::showEmptyConfirmDialog) {
                            Icon(Icons.Default.DeleteForever, "Svuota cestino")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        if (items.itemCount == 0) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Il cestino è vuoto.\nI file eliminati vengono conservati 30 giorni.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.padding(innerPadding),
            ) {
                items(items.itemCount, key = { items.peek(it)?.id ?: it }) { index ->
                    val item = items[index] ?: return@items
                    MediaThumbnail(
                        mediaItem = item,
                        isSelected = uiState.selectedIds.contains(item.id),
                        isSelectionMode = uiState.isSelectionMode,
                        onClick = { viewModel.toggleSelection(item.id) },
                        onLongClick = { viewModel.toggleSelection(item.id) },
                    )
                }
            }
        }
    }

    if (uiState.showEmptyConfirmDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissEmptyConfirmDialog,
            title = { Text("Svuota cestino") },
            text = { Text("Questa azione è irreversibile. Eliminare definitivamente tutti gli elementi nel cestino?") },
            confirmButton = {
                TextButton(
                    onClick = viewModel::emptyTrash,
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Elimina tutto") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissEmptyConfirmDialog) { Text("Annulla") }
            },
        )
    }
}
