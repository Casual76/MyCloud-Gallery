package com.mycloudgallery.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val dateFormatter = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { 
            LargeTopAppBar(
                title = { 
                    Text(
                        "Impostazioni",
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
                    ) 
                },
                scrollBehavior = scrollBehavior
            ) 
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {

            // --- Sezione Sincronizzazione ---
            SectionHeader("Sincronizzazione")
            
            SettingsCard {
                ListItem(
                    headlineContent = { Text("Sincronizza ora") },
                    supportingContent = { 
                        Column {
                            Text("Aggiorna l'indice con i file del NAS")
                            if (uiState.lastSyncTime > 0) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Ultima sync: ${dateFormatter.format(Date(uiState.lastSyncTime))}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (uiState.lastSyncResult.isNotEmpty()) {
                                    Text(
                                        text = uiState.lastSyncResult,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (uiState.lastSyncResult.contains("Errore")) 
                                            MaterialTheme.colorScheme.error 
                                        else 
                                            MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    },
                    leadingContent = {
                        if (uiState.isForcingSyncNow) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(Icons.Default.CloudSync, contentDescription = null)
                        }
                    },
                    trailingContent = {
                        OutlinedButton(
                            onClick = viewModel::onForceSyncNow,
                            enabled = !uiState.isForcingSyncNow,
                        ) { Text("Avvia") }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }

            // --- Sezione Upload automatico ---
            SectionHeader("Upload automatico")

            SettingsCard {
                Column {
                    ListItem(
                        headlineContent = { Text("Carica foto automaticamente") },
                        supportingContent = { Text("Carica le nuove foto dal rullino sul NAS") },
                        leadingContent = { Icon(Icons.Default.PhotoCamera, null) },
                        trailingContent = {
                            Switch(
                                checked = uiState.cameraUploadEnabled,
                                onCheckedChange = viewModel::onCameraUploadToggled,
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    ListItem(
                        headlineContent = { Text("Solo con WiFi") },
                        supportingContent = { Text("Non usare dati mobili per l'upload") },
                        leadingContent = { Icon(Icons.Default.Wifi, null) },
                        trailingContent = {
                            Switch(
                                checked = uiState.wifiOnlyUpload,
                                onCheckedChange = viewModel::onWifiOnlyToggled,
                                enabled = uiState.cameraUploadEnabled,
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            }

            // --- Sezione Indicizzazione AI ---
            SectionHeader("Intelligenza Artificiale")

            SettingsCard {
                Column {
                    // Progresso indicizzazione
                    val indexingProgress = if (uiState.totalMediaCount > 0) {
                        (uiState.totalMediaCount - uiState.unindexedCount).toFloat() / uiState.totalMediaCount
                    } else 0f

                    ListItem(
                        headlineContent = {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text("Stato analisi")
                                    Text(
                                        text = "${(indexingProgress * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { 
                                        if (uiState.isIndexing) {
                                            (uiState.totalMediaCount - uiState.unindexedCount).toFloat() / uiState.totalMediaCount 
                                        } else {
                                            indexingProgress 
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                )
                            }
                        },
                        supportingContent = {
                            Text(
                                "${uiState.totalMediaCount - uiState.unindexedCount} / ${uiState.totalMediaCount} foto analizzate"
                            )
                        },
                        leadingContent = { Icon(Icons.Default.FindInPage, null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )

                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                    ListItem(
                        headlineContent = { Text("Indicizzazione automatica") },
                        supportingContent = { Text("Analizza foto in background con ML Kit") },
                        trailingContent = {
                            Switch(
                                checked = uiState.autoIndex,
                                onCheckedChange = viewModel::onAutoIndexToggled,
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )

                    if (uiState.unindexedCount > 0) {
                        ListItem(
                            headlineContent = { Text("Indicizza ora") },
                            supportingContent = { Text("${uiState.unindexedCount} foto in attesa") },
                            trailingContent = {
                                Button(onClick = viewModel::onForceIndexNow) {
                                    Text("Avvia")
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            }

            // --- Sezione AI Engine ---
            SectionHeader("Motore AI")

            SettingsCard {
                Column {
                    AiEngineOption(
                        label = "Nessuno",
                        description = "Analisi AI disabilitata",
                        selected = uiState.aiEngineProvider == "none",
                        onClick = { viewModel.onAiEngineProviderSelected("none") },
                    )
                    AiEngineOption(
                        label = "Gemini Nano (on-device)",
                        description = "Richiede Android 14+ su dispositivi compatibili",
                        selected = uiState.aiEngineProvider == "gemini_nano",
                        onClick = { viewModel.onAiEngineProviderSelected("gemini_nano") },
                    )
                    AiEngineOption(
                        label = "Gemma 4 (modello locale)",
                        description = "Alta qualita, richiede download di ~2 GB.",
                        selected = uiState.aiEngineProvider == "gemma4",
                        onClick = { viewModel.onAiEngineProviderSelected("gemma4") },
                    )
                }
            }

            // --- Sezione Info ---
            SectionHeader("Informazioni")

            SettingsCard {
                Column {
                    ListItem(
                        headlineContent = { Text("Versione") },
                        trailingContent = { Text("1.1.0 (Expressive)", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    ListItem(
                        headlineContent = { Text("Archivio media") },
                        trailingContent = {
                            Text("${uiState.totalMediaCount} elementi")
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        content()
    }
}

@Composable
private fun AiEngineOption(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(description) },
        leadingContent = {
            RadioButton(
                selected = selected,
                onClick = onClick,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall.copy(
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        ),
        modifier = Modifier.padding(start = 32.dp, top = 24.dp, bottom = 8.dp),
    )
}

