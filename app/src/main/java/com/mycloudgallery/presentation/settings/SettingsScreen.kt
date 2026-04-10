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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Impostazioni") }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {

            // --- Sezione Sincronizzazione ---
            SectionHeader("Sincronizzazione")

            ListItem(
                headlineContent = { Text("Sincronizza ora") },
                supportingContent = { Text("Aggiorna l'indice con i file del NAS") },
                leadingContent = {
                    if (uiState.isForcingSyncNow) {
                        CircularProgressIndicator(modifier = Modifier.height(24.dp).width(24.dp))
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
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // --- Sezione Upload automatico ---
            SectionHeader("Upload automatico fotocamera")

            ListItem(
                headlineContent = { Text("Carica foto automaticamente") },
                supportingContent = { Text("Carica le nuove foto dal rullino sul NAS") },
                leadingContent = {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null)
                },
                trailingContent = {
                    Switch(
                        checked = uiState.cameraUploadEnabled,
                        onCheckedChange = viewModel::onCameraUploadToggled,
                    )
                },
            )

            ListItem(
                headlineContent = { Text("Solo con WiFi") },
                supportingContent = { Text("Non usare dati mobili per l'upload") },
                leadingContent = {
                    Icon(Icons.Default.Wifi, contentDescription = null)
                },
                trailingContent = {
                    Switch(
                        checked = uiState.wifiOnlyUpload,
                        onCheckedChange = viewModel::onWifiOnlyToggled,
                        enabled = uiState.cameraUploadEnabled,
                    )
                },
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // --- Sezione Indicizzazione AI ---
            SectionHeader("Indicizzazione AI (Fase 2)")

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
                            Text("Progresso indicizzazione")
                            Text(
                                text = "${(indexingProgress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { indexingProgress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                supportingContent = {
                    Text(
                        "${uiState.totalMediaCount - uiState.unindexedCount} / ${uiState.totalMediaCount} foto analizzate"
                    )
                },
                leadingContent = {
                    Icon(Icons.Default.FindInPage, contentDescription = null)
                },
            )

            ListItem(
                headlineContent = { Text("Indicizzazione automatica") },
                supportingContent = { Text("Analizza foto in background con ML Kit") },
                trailingContent = {
                    Switch(
                        checked = uiState.autoIndex,
                        onCheckedChange = viewModel::onAutoIndexToggled,
                    )
                },
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
                )
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // --- Sezione Info ---
            SectionHeader("Informazioni")

            ListItem(
                headlineContent = { Text("Versione") },
                trailingContent = { Text("1.0.0") },
            )
            ListItem(
                headlineContent = { Text("Archivio media") },
                trailingContent = {
                    Text("${uiState.totalMediaCount} elementi")
                },
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
