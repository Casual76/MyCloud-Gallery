package dev.pampa.mycloudgallery.spike.embedprobe

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        // Percorso di debug via adb (vedi DebugActions): non fa nulla senza l'extra debugAction.
        if (savedInstanceState == null) DebugActions.handle(applicationContext, intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        DebugActions.handle(applicationContext, intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProbeScreen(vm: ProbeViewModel = viewModel()) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val idle = vm.busy == null

    DisposableEffect(vm.busy) {
        view.keepScreenOn = vm.busy != null // durante le misure lo schermo resta acceso
        onDispose { view.keepScreenOn = false }
    }

    // Permesso foto: chiesto all'utente solo quando serve.
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pending?.invoke() else Toast.makeText(ctx, "Permesso foto negato", Toast.LENGTH_LONG).show()
        pending = null
    }
    fun needPhotos(action: () -> Unit) {
        val ok = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        if (ok) action() else {
            pending = action
            launcher.launch(Manifest.permission.READ_MEDIA_IMAGES)
        }
    }

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Barra fissa: cosa sta facendo l'app e il tasto Ferma.
            if (vm.busy != null) {
                Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(vm.busy ?: "", style = MaterialTheme.typography.titleSmall)
                            if (vm.progressText.isNotEmpty()) Text(vm.progressText, style = MaterialTheme.typography.bodySmall)
                            val p = vm.progress
                            if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        }
                        Spacer(Modifier.padding(6.dp))
                        OutlinedButton(onClick = { vm.stop() }) { Text("Ferma") }
                    }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 980.dp).fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Embed Probe - EmbeddingGemma 2", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Spike Fase 0.3. Tieni lo schermo acceso e il dispositivo in carica durante le misure. " +
                            "Nessun dato lascia il dispositivo, tranne il download dei modelli da Hugging Face.",
                        style = MaterialTheme.typography.bodySmall,
                    )

                    ModelsSection(vm, idle)
                    SettingsSection(vm, idle)
                    IndexSection(vm, idle, ::needPhotos)
                    ConsistencySection(vm, idle, ::needPhotos)
                    SearchSection(vm, idle)
                    ResultsSection(vm)
                    LogSection(vm)
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(
    label: String,
    options: List<T>,
    selected: T,
    enabled: Boolean,
    text: (T) -> String,
    optionEnabled: (T) -> Boolean = { true },
    onSelect: (T) -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { o ->
                FilterChip(
                    selected = o == selected,
                    onClick = { onSelect(o) },
                    enabled = enabled && optionEnabled(o),
                    label = { Text(text(o)) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------- 1. modelli

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelsSection(vm: ProbeViewModel, idle: Boolean) {
    val npu = ModelId.deviceNpu()
    SectionCard("1. Modelli") {
        ModelId.relevant().forEach { m ->
            val st = vm.modelStates[m] ?: ModelStatus(ModelState.MISSING, 0)
            val state = when (st.state) {
                ModelState.READY -> "scaricato, SHA-256 verificato"
                ModelState.PARTIAL -> "parziale ${Fmt.mib(st.bytesOnDisk)} (riprendibile)"
                ModelState.MISSING -> "non scaricato"
            }
            Column {
                Text("${m.label}  -  ${Fmt.mib(m.sizeBytes)}", style = MaterialTheme.typography.bodyMedium)
                Text(state, style = MaterialTheme.typography.bodySmall)
                if (st.state == ModelState.PARTIAL && st.bytesOnDisk > 0) {
                    LinearProgressIndicator(progress = { (st.bytesOnDisk.toFloat() / m.sizeBytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        Text(
            "Il download parte solo su Wi-Fi, riprende da dove si e interrotto e confronta lo SHA-256 del documento " +
                "(se non corrisponde cancella il file). Tieni l'app aperta mentre scarica.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            if (npu != null) "SoC rilevato: ${android.os.Build.SOC_MODEL}. Variante NPU: ${npu.fileName} (${Fmt.mib(npu.sizeBytes)})."
            else "SoC rilevato: ${android.os.Build.SOC_MODEL}. NPU non supportata da questo spike (solo SM8750 e SM8550): download NPU disabilitato.",
            style = MaterialTheme.typography.bodySmall,
        )
        val npuReady = npu != null && (vm.modelStates[npu]?.state == ModelState.READY)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.downloadModels() }, enabled = idle) { Text("Scarica modelli") }
            Button(onClick = { vm.downloadNpuModel() }, enabled = idle && npu != null && !npuReady) {
                Text(
                    when {
                        npu == null -> "Scarica modello NPU (SoC non supportato)"
                        npuReady -> "Modello NPU gia scaricato"
                        else -> "Scarica modello NPU (${Fmt.mib(npu.sizeBytes)})"
                    },
                )
            }
            FilledTonalButton(onClick = { vm.showModelInfo() }, enabled = idle) { Text("Mostra ModelInfo") }
        }
    }
}

// ---------------------------------------------------------------------------- impostazioni

@Composable
private fun SettingsSection(vm: ProbeViewModel, idle: Boolean) {
    val s = vm.settings
    SectionCard("Impostazioni di misura") {
        ChipRow(
            "Backend (testo e visione) - vale per Indicizza foto e per la ricerca. Nessun ripiego automatico: se la NPU non parte vedrai l'errore reale",
            BackendKind.entries, s.backend, idle,
            { if (it == BackendKind.NPU && ModelId.deviceNpu() == null) "NPU (SoC non supportato)" else it.label },
            optionEnabled = { it != BackendKind.NPU || ModelId.deviceNpu() != null },
        ) { vm.settings = s.copy(backend = it) }
        ChipRow("Budget token visivi", listOf(70, 140), s.tokens, idle, { it.toString() }) { vm.settings = s.copy(tokens = it) }
        ChipRow("Risoluzione anteprima (lato lungo, px)", listOf(512, 768, 1024), s.res, idle, { it.toString() }) { vm.settings = s.copy(res = it) }
        ChipRow("Conversione in byte", ConvertFmt.entries, s.convert, idle, { it.label }) { vm.settings = s.copy(convert = it) }
        ChipRow(
            "Lunghezza max input (signature testo). Auto = 128 con 70 token, 256 con 140",
            listOf(0, 128, 256, 512), s.maxLen, idle, { if (it == 0) "auto (${s.resolvedMaxLen()})" else it.toString() },
        ) { vm.settings = s.copy(maxLen = it) }
        ChipRow("Tipo di attivazioni (auto = FP16 su GPU, FP32 su CPU)", ActKind.entries, s.activation, idle, { it.label }) {
            vm.settings = s.copy(activation = it)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = s.pipelined, onCheckedChange = { vm.settings = s.copy(pipelined = it) }, enabled = idle)
            Spacer(Modifier.padding(6.dp))
            Text("Decodifica in parallelo all'embedding (pipeline)", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ---------------------------------------------------------------------------- 2. indicizzazione

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IndexSection(vm: ProbeViewModel, idle: Boolean, needPhotos: (() -> Unit) -> Unit) {
    SectionCard("2. Indicizzazione di prova") {
        OutlinedTextField(
            value = vm.countText,
            onValueChange = { vm.countText = it.filter(Char::isDigit).take(5) },
            label = { Text("Numero di foto (N)") },
            singleLine = true,
            enabled = idle,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        if (vm.indexes.isEmpty()) {
            Text("Nessun indice in memoria.", style = MaterialTheme.typography.bodySmall)
        } else {
            vm.indexes.forEach { (key, idx) ->
                Text(
                    (if (key == (vm.activeIndexKey ?: vm.indexes.keys.first())) "> " else "  ") +
                        "Indice $key: ${idx.size} vettori ${idx.meta.dim}d (${idx.meta.visionTokens} token, " +
                        "${idx.meta.resolution} px, ${SimpleDateFormat("dd/MM HH:mm", Locale.ITALY).format(Date(idx.meta.createdAtMs))})",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (vm.indexes.size > 1) {
                ChipRow("Indice attivo (usato da ricerca e test)", vm.indexes.keys.toList(), vm.activeIndexKey ?: vm.indexes.keys.first(), idle, { it }) {
                    vm.selectIndex(it)
                }
            }
        }
        Text(
            "Un indice per backend, salvati su file separati: indicizza con un backend, cambia backend nelle impostazioni e indicizza di nuovo " +
                "con lo stesso N (stesse foto, stesso ordine) per poterli confrontare.",
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { needPhotos { vm.startIndexing() } }, enabled = idle) { Text("Indicizza foto") }
            FilledTonalButton(onClick = { vm.releaseEngine() }, enabled = idle) { Text("Libera motore") }
            FilledTonalButton(onClick = { vm.clearCompileCache() }, enabled = idle) { Text("Svuota cache motore") }
        }
        Text(
            "Libera motore: close() e PSS prima/dopo. Svuota cache: il prossimo initialize() sara a freddo.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

// ---------------------------------------------------------------------------- 3. coerenza

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConsistencySection(vm: ProbeViewModel, idle: Boolean, needPhotos: (() -> Unit) -> Unit) {
    SectionCard("3. Coerenza numerica") {
        Text(
            "CPU vs GPU e NPU vs CPU: 20 immagini + 20 testi, coseno medio/minimo (soglia 0,99), latenze e core CPU occupati. " +
                "270M vs 440M: 50 testi italiani, coseno, latenza query a freddo e a caldo, RAM, e verifica del troncamento MRL del motore (solo CPU/GPU). " +
                "Confronto incrociato: 30 query italiane su ogni indice (NPU/GPU/CPU), con query calcolate da ogni backend e dal 270M su CPU; " +
                "sovrapposizione top-5/10/20 e correlazione di rango rispetto al riferimento \"stesso backend\", punteggi top-1 e top-20.",
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { needPhotos { vm.runCpuVsGpu() } }, enabled = idle) { Text("CPU vs GPU") }
            Button(onClick = { needPhotos { vm.runNpuVsCpu() } }, enabled = idle && ModelId.deviceNpu() != null) { Text("NPU vs CPU") }
            Button(onClick = { vm.runModelCompare() }, enabled = idle) { Text("270M vs 440M") }
            Button(onClick = { vm.runCrossCompare() }, enabled = idle && vm.indexes.isNotEmpty()) { Text("Confronto incrociato") }
        }
    }
}

// ---------------------------------------------------------------------------- 4. ricerca

@Composable
private fun SearchSection(vm: ProbeViewModel, idle: Boolean) {
    val s = vm.settings
    SectionCard("4. Ricerca") {
        OutlinedTextField(
            value = vm.query,
            onValueChange = { vm.query = it },
            label = { Text("Query in italiano") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = idle,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.search() }),
        )
        ChipRow("Formato del prefisso della query", PrefixFormat.entries, s.prefix, idle, { it.label }) { vm.settings = s.copy(prefix = it) }
        Text("Invio al motore: ${s.prefix.example}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        ChipRow("Modello che calcola la query (l'indice e sempre del 440M; il 270M non va su NPU)", ModelId.generic, s.queryModel, idle, { it.label }) {
            vm.settings = s.copy(queryModel = it)
        }
        ChipRow("Troncamento MRL dei vettori indicizzati", listOf(768, 512, 256, 128), s.dim, idle, { it.toString() }) {
            vm.settings = s.copy(dim = it)
            vm.rerank()
        }
        ChipRow("Precisione dei vettori indicizzati", Precision.entries, s.precision, idle, { it.label }) {
            vm.settings = s.copy(precision = it)
            vm.rerank()
        }
        Button(onClick = { vm.search() }, enabled = idle) { Text("Cerca") }
        if (vm.searchInfo.isNotEmpty()) Text(vm.searchInfo, style = MaterialTheme.typography.bodySmall)
        if (vm.hits.isNotEmpty()) HitGrid(vm.hits)
    }
}

@Composable
private fun HitGrid(hits: List<Hit>) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cols = if (maxWidth > 700.dp) 5 else 3
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            hits.chunked(cols).forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEachIndexed { c, h -> HitCell(r * cols + c + 1, h, Modifier.weight(1f)) }
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun HitCell(rank: Int, hit: Hit, modifier: Modifier) {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, hit.id) {
        value = withContext(Dispatchers.IO) { Photos.thumbnail(ctx, hit.id, 320)?.asImageBitmap() }
    }
    Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        bmp?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        Text(
            "#$rank", color = Color.White, fontSize = 11.sp,
            modifier = Modifier.align(Alignment.TopStart).background(Color(0x99000000)).padding(horizontal = 5.dp, vertical = 1.dp),
        )
        Text(
            Fmt.d(hit.score, 3), color = Color.White, fontSize = 11.sp,
            modifier = Modifier.align(Alignment.BottomEnd).background(Color(0x99000000)).padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

// ---------------------------------------------------------------------------- 5. risultati

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultsSection(vm: ProbeViewModel) {
    val ctx = LocalContext.current
    SectionCard("5. Risultati") {
        Text("Eventi registrati: ${vm.eventCount}. File (si aggiorna a ogni misura):", style = MaterialTheme.typography.bodySmall)
        SelectionContainer {
            Text(vm.resultsPath, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
        Button(onClick = {
            val json = vm.resultsJson()
            val cm = ctx.getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("embedprobe", json))
            Toast.makeText(ctx, "Copiati ${json.length / 1024} KiB di JSON", Toast.LENGTH_SHORT).show()
        }) { Text("Copia risultati") }
    }
}

// ---------------------------------------------------------------------------- log

@Composable
private fun LogSection(vm: ProbeViewModel) {
    SectionCard("Registro (il piu recente in alto)") {
        SelectionContainer {
            Text(
                vm.logLines.asReversed().joinToString("\n"),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }
    }
}
