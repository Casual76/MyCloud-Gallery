package com.mycloudgallery.desktop.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mycloudgallery.domain.repository.SettingsRepository
import kotlinx.coroutines.launch

@Composable
fun DesktopSettingsScreen(settingsRepository: SettingsRepository) {
    val scope = rememberCoroutineScope()
    val wifiOnly by settingsRepository.wifiOnlyUpload.collectAsState(initial = true)
    val autoIndex by settingsRepository.autoIndex.collectAsState(initial = true)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Impostazioni", style = MaterialTheme.typography.headlineSmall)

        SettingCard(
            title = "Solo WiFi",
            description = "Sincronizza solo quando connesso a una rete WiFi",
            checked = wifiOnly,
            onCheckedChange = { scope.launch { settingsRepository.setWifiOnlyUpload(it) } },
        )

        SettingCard(
            title = "Indicizzazione automatica",
            description = "Analizza le foto con AI in background per attivare la ricerca semantica",
            checked = autoIndex,
            onCheckedChange = { scope.launch { settingsRepository.setAutoIndex(it) } },
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Scorciatoie da tastiera", style = MaterialTheme.typography.titleSmall)
                ShortcutRow("← / →", "Foto precedente / successiva nel viewer")
                ShortcutRow("Spazio", "Play/pausa video")
                ShortcutRow("Esc", "Chiudi viewer / deseleziona")
                ShortcutRow("Ctrl+A", "Seleziona tutti")
                ShortcutRow("Canc", "Sposta nel cestino")
            }
        }
    }
}

@Composable
private fun SettingCard(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun ShortcutRow(keys: String, action: String) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            keys,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(action, style = MaterialTheme.typography.bodySmall)
    }
}
