package com.mycloudgallery.presentation.album

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.mycloudgallery.domain.model.Album

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AlbumsScreen(
    onAlbumClick: (albumId: String, isFavorites: Boolean) -> Unit,
    viewModel: AlbumsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // Mostra errori via Snackbar
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onErrorShown()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { 
                    Text(
                        "I tuoi Album",
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
                    ) 
                },
                scrollBehavior = scrollBehavior
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = viewModel::onShowCreateDialog,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = MaterialTheme.shapes.large
            ) {
                Icon(Icons.Default.Add, contentDescription = "Nuovo album")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 88.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // Card "Preferiti" sempre in cima
            item(key = "favorites") {
                FavoritesAlbumCard(
                    count = uiState.favoritesCount,
                    onClick = { onAlbumClick("favorites", true) },
                )
            }

            items(
                items = uiState.albums,
                key = { it.id },
            ) { album ->
                AlbumCard(
                    album = album,
                    isMenuOpen = uiState.contextMenuAlbumId == album.id,
                    onAlbumClick = { onAlbumClick(album.id, false) },
                    onLongClick = { viewModel.onShowContextMenu(album.id) },
                    onDismissMenu = viewModel::onDismissContextMenu,
                    onRenameClick = { viewModel.onStartRename(album.id) },
                    onDeleteClick = { viewModel.onRequestDelete(album.id) },
                )
            }
        }
    }

    // Dialog: nuovo album
    if (uiState.showCreateDialog) {
        AlbumNameDialog(
            title = "Nuovo album",
            confirmLabel = "Crea",
            value = uiState.dialogInputName,
            onValueChange = viewModel::onDialogNameChanged,
            onConfirm = viewModel::onCreateAlbum,
            onDismiss = viewModel::onDismissDialog,
        )
    }

    // Dialog: rinomina album
    if (uiState.renameAlbumId != null) {
        AlbumNameDialog(
            title = "Rinomina album",
            confirmLabel = "Salva",
            value = uiState.dialogInputName,
            onValueChange = viewModel::onDialogNameChanged,
            onConfirm = viewModel::onConfirmRename,
            onDismiss = viewModel::onDismissDialog,
        )
    }

    // Dialog: conferma eliminazione
    if (uiState.deleteAlbumId != null) {
        AlertDialog(
            onDismissRequest = viewModel::onDismissDelete,
            title = { Text("Elimina album") },
            text = { Text("L'album verrà eliminato. I file sul NAS non verranno toccati.") },
            confirmButton = {
                TextButton(onClick = viewModel::onConfirmDelete) { Text("Elimina") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::onDismissDelete) { Text("Annulla") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumCard(
    album: Album,
    isMenuOpen: Boolean,
    onAlbumClick: () -> Unit,
    onLongClick: () -> Unit,
    onDismissMenu: () -> Unit,
    onRenameClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onAlbumClick, onLongClick = onLongClick),
            shape = shape,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Column {
                if (album.coverMediaId != null) {
                    val coverModel = album.coverMediaId.let { path ->
                        if (path != null && (path.startsWith("smb://") || path.startsWith("http://") || path.startsWith("https://"))) path
                        else if (path != null) "smb://$path"
                        else null
                    }
                    AsyncImage(
                        model = coverModel,
                        contentDescription = album.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1.1f)
                            .clip(shape),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1.1f)
                            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Collections,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                        )
                    }
                }

                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        text = album.name,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (album.mediaCount > 0) {
                        Text(
                            text = "${album.mediaCount} elementi",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            text = "Vuoto",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }
            }
        }

        DropdownMenu(
            expanded = isMenuOpen,
            onDismissRequest = onDismissMenu,
        ) {
            DropdownMenuItem(
                text = { Text("Rinomina") },
                leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) },
                onClick = {
                    onDismissMenu()
                    onRenameClick()
                },
            )
            DropdownMenuItem(
                text = { Text("Elimina") },
                leadingIcon = { Icon(Icons.Default.Delete, null) },
                onClick = {
                    onDismissMenu()
                    onDeleteClick()
                },
            )
        }
    }
}

@Composable
private fun FavoritesAlbumCard(
    count: Int,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    Card(
        onClick = onClick,
        shape = shape,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
        ),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.1f)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Favorite,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = "Preferiti",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                )
                if (count > 0) {
                    Text(
                        text = "$count elementi",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                } else {
                    Text(
                        text = "Aggiungi ai preferiti",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}

@Composable
private fun AlbumNameDialog(
    title: String,
    confirmLabel: String,
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text("Nome album") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = value.isNotBlank(),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annulla") }
        },
    )
}

