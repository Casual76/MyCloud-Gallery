package com.mycloudgallery.presentation.duplicates

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mycloudgallery.domain.model.MediaItem
import com.mycloudgallery.presentation.gallery.components.MediaThumbnail

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicatesScreen(
    onBack: () -> Unit,
    onMediaClick: (String) -> Unit,
    viewModel: DuplicatesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showConfirmDialog by remember { mutableStateOf(false) }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text("Sposta nel cestino") },
            text = {
                Text("Spostare ${uiState.selectedIds.size} elementi nel cestino?\nPotrai recuperarli entro 30 giorni.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteSelected()
                        showConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Sposta nel cestino") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) { Text("Annulla") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Foto simili") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Indietro")
                    }
                },
                actions = {
                    if (uiState.selectedIds.isNotEmpty()) {
                        Badge(containerColor = MaterialTheme.colorScheme.primary) {
                            Text("${uiState.selectedIds.size}")
                        }
                        IconButton(onClick = { showConfirmDialog = true }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Elimina selezionati",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                        TextButton(onClick = viewModel::onClearSelection) {
                            Text("Deseleziona")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }
            uiState.groups.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Nessun duplicato trovato",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "L'analisi viene eseguita durante l'indicizzazione",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 16.dp,
                        vertical = 8.dp,
                    ),
                ) {
                    item {
                        Text(
                            text = "${uiState.groups.size} gruppi di foto simili",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(uiState.groups, key = { it.groupId }) { group ->
                        DuplicateGroupCard(
                            group = group,
                            selectedIds = uiState.selectedIds,
                            onToggleSelect = viewModel::onToggleSelect,
                            onSelectDuplicates = { viewModel.onSelectAllInGroup(group) },
                            onMediaClick = onMediaClick,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DuplicateGroupCard(
    group: DuplicateGroup,
    selectedIds: Set<String>,
    onToggleSelect: (String) -> Unit,
    onSelectDuplicates: () -> Unit,
    onMediaClick: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${group.items.size} foto simili",
                style = MaterialTheme.typography.titleSmall,
            )
            TextButton(onClick = onSelectDuplicates) {
                Text("Seleziona duplicati")
            }
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(group.items, key = { it.id }) { item ->
                val isSelected = item.id in selectedIds
                val isOriginal = item.id == group.items.first().id

                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(MaterialTheme.shapes.small)
                        .border(
                            width = if (isSelected) 3.dp else 0.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            shape = MaterialTheme.shapes.small,
                        )
                        .clickable { onToggleSelect(item.id) },
                ) {
                    MediaThumbnail(
                        item = item,
                        onClick = { onMediaClick(item.id) },
                        modifier = Modifier.aspectRatio(1f),
                    )
                    if (isOriginal) {
                        Text(
                            text = "Originale",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 4.dp),
                        )
                    }
                    Icon(
                        imageVector = if (isSelected) Icons.Default.CheckCircle
                                      else Icons.Default.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(20.dp),
                    )
                }
            }
        }
    }
}

