package dev.pampa.mycloudgallery.spike.nasprobe

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ProbeScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ProbeScreen(vm: ProbeViewModel = viewModel()) {
    val ctx = LocalContext.current
    val view = LocalView.current
    DisposableEffect(vm.running) {
        view.keepScreenOn = vm.running // durante i test lo schermo resta acceso, altrimenti la rete si addormenta
        onDispose { view.keepScreenOn = false }
    }

    Scaffold { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 840.dp)
                    .fillMaxWidth()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("NAS Probe", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Misure SMB per la Fase 0.2. La password resta solo in memoria. I test scrivono solo file .nasprobe- creati e poi cancellati dall'app.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(vm.selfCheck, style = MaterialTheme.typography.bodySmall)

                val editable = !vm.running
                Field("Host", vm.host, { vm.host = it }, editable)
                Field("Share", vm.share, { vm.share = it }, editable)
                Field("Cartella dentro la share (vuota = radice)", vm.folder, { vm.folder = it }, editable)
                Field("Utente", vm.user, { vm.user = it }, editable)
                Field("Password (solo in memoria)", vm.password, { vm.password = it }, editable, password = true)
                Field("Seconda share per la copia tra share (vuota = salta)", vm.share2, { vm.share2 = it }, editable)
                Field("MAC address per Wake-on-LAN (AA:BB:CC:DD:EE:FF)", vm.mac, { vm.mac = it }, editable)
                Field("Inventario: share o share/cartella separate da virgola (es. Public, Alessio)", vm.inventory, { vm.inventory = it }, editable)

                SwitchRow("Richiedi cifratura SMB", vm.requireEncryption, { vm.requireEncryption = it }, editable)
                SwitchRow("Lega SMB alla rete Wi-Fi (aggira una VPN)", vm.bindToWifi, { vm.bindToWifi = it }, editable)

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = vm::runAll, enabled = !vm.running) { Text("Esegui tutti i test") }
                    FilledTonalButton(onClick = vm::runNetworkOnly, enabled = !vm.running) { Text("Solo info rete") }
                    FilledTonalButton(onClick = vm::runInventory, enabled = !vm.running) { Text("Inventario (sola lettura)") }
                    OutlinedButton(onClick = vm::sendWakeOnLan, enabled = !vm.running) { Text("Invia Wake-on-LAN") }
                    if (vm.running) {
                        OutlinedButton(onClick = vm::stop) { Text("Interrompi") }
                    }
                    OutlinedButton(
                        onClick = {
                            val ok = vm.copyResults()
                            Toast.makeText(ctx, if (ok) "Risultati copiati" else "Nessun risultato da copiare", Toast.LENGTH_SHORT).show()
                        },
                        enabled = vm.results.isNotEmpty(),
                    ) { Text("Copia risultati") }
                }

                if (vm.running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(vm.status, style = MaterialTheme.typography.bodyMedium)
                vm.savedPath?.let { Text("JSON: $it", style = MaterialTheme.typography.bodySmall) }

                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        vm.results.forEach { ResultCard(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    enabled: Boolean,
    password: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = if (password) KeyboardType.Password else KeyboardType.Uri,
        ),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun ResultCard(r: TestResult) {
    val statusText = when (r.status) {
        Status.OK -> "OK"
        Status.FAIL -> "ERRORE"
        Status.SKIPPED -> "SALTATO"
    }
    val statusColor = when (r.status) {
        Status.OK -> Color(0xFF2E7D32)
        Status.FAIL -> MaterialTheme.colorScheme.error
        Status.SKIPPED -> MaterialTheme.colorScheme.outline
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("${r.id}. ${r.title}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("$statusText  ${r.elapsedMs} ms", color = statusColor, style = MaterialTheme.typography.labelLarge)
            }
            r.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            val body = r.dataText()
            if (body.isNotEmpty()) {
                Text(body, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
            }
        }
    }
}
