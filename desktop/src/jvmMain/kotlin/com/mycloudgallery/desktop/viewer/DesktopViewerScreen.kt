package com.mycloudgallery.desktop.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.mycloudgallery.desktop.data.repository.DesktopMediaRepository
import com.mycloudgallery.domain.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DesktopViewerScreen(
    mediaId: String,
    mediaRepository: DesktopMediaRepository,
    onClose: () -> Unit,
) {
    var currentItem by remember { mutableStateOf<MediaItem?>(null) }
    var allMedia by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var currentIndex by remember { mutableStateOf(0) }

    LaunchedEffect(mediaId) {
        withContext(Dispatchers.IO) {
            allMedia = mediaRepository.getRecentMedia(limit = 500)
            currentIndex = allMedia.indexOfFirst { it.id == mediaId }.coerceAtLeast(0)
            currentItem = allMedia.getOrNull(currentIndex)
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.Escape -> { onClose(); true }
                    Key.DirectionLeft, Key.PageUp -> {
                        if (currentIndex > 0) {
                            currentIndex--
                            currentItem = allMedia.getOrNull(currentIndex)
                        }
                        true
                    }
                    Key.DirectionRight, Key.PageDown -> {
                        if (currentIndex < allMedia.lastIndex) {
                            currentIndex++
                            currentItem = allMedia.getOrNull(currentIndex)
                        }
                        true
                    }
                    else -> false
                }
            },
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Toolbar superiore
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(onClick = onClose) { Text("✕") }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = currentItem?.fileName ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (allMedia.isNotEmpty()) "${currentIndex + 1} / ${allMedia.size}" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Contenuto principale
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                if (currentItem != null) {
                    ViewerContent(item = currentItem!!)
                }
            }

            // Navigazione inferiore
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(
                    onClick = {
                        if (currentIndex > 0) {
                            currentIndex--
                            currentItem = allMedia.getOrNull(currentIndex)
                        }
                    },
                    enabled = currentIndex > 0,
                ) { Text("◀") }
                Spacer(Modifier.weight(1f))

                // Info EXIF
                currentItem?.exifCameraModel?.let { camera ->
                    Text(
                        text = "📷 $camera",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))

                FilledTonalIconButton(
                    onClick = {
                        if (currentIndex < allMedia.lastIndex) {
                            currentIndex++
                            currentItem = allMedia.getOrNull(currentIndex)
                        }
                    },
                    enabled = currentIndex < allMedia.lastIndex,
                ) { Text("▶") }
            }
        }
    }
}

@Composable
private fun ViewerContent(item: MediaItem) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Placeholder visivo — in produzione si usa AsyncImage di Coil per Desktop
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.large,
        ) {
            Box(
                modifier = Modifier.padding(64.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (item.mediaType.name == "VIDEO") "🎬" else "🖼️",
                    style = MaterialTheme.typography.displayLarge,
                )
            }
        }
        Text(
            text = item.fileName,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = "${item.fileSize / 1024} KB · ${item.width ?: "?"}×${item.height ?: "?"} px",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
