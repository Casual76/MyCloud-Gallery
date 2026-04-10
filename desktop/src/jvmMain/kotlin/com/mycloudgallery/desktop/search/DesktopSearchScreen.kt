package com.mycloudgallery.desktop.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mycloudgallery.desktop.data.repository.DesktopMediaRepository
import com.mycloudgallery.domain.model.MediaItem
import com.mycloudgallery.domain.model.SearchFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DesktopSearchScreen(
    mediaRepository: DesktopMediaRepository,
    onMediaClick: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    val scope = rememberCoroutineScope()
    var debounceJob by remember { mutableStateOf<Job?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { q ->
                query = q
                debounceJob?.cancel()
                debounceJob = scope.launch {
                    delay(300)
                    results = withContext(Dispatchers.IO) {
                        mediaRepository.search(q, SearchFilter())
                    }
                }
            },
            label = { Text("Cerca per nome, etichette AI, testo OCR…") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Text(
            text = if (query.isBlank()) "Digita per cercare" else "${results.size} risultati",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )

        LazyColumn(
            contentPadding = PaddingValues(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(items = results, key = { it.id }) { item ->
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { onMediaClick(item.id) },
                    shape = MaterialTheme.shapes.small,
                    tonalElevation = 1.dp,
                ) {
                    Row(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = if (item.mediaType.name == "VIDEO") "🎬" else "🖼",
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Column {
                            Text(item.fileName, style = MaterialTheme.typography.bodyMedium)
                            if (item.aiLabels.isNotEmpty()) {
                                Text(
                                    text = item.aiLabels.take(5).joinToString(", "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
