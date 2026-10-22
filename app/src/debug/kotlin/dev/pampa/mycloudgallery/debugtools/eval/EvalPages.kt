package dev.pampa.mycloudgallery.debugtools.eval

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// SOLO debug (Fase 6, P9): le pagine dello strumento che mostrano foto o numeri. Le foto sono sempre miniature locali ([EvalEnvironment.thumbnail]).

/** Prima fase del pozzo: sfogliare la Libreria per anno e luogo e segnare le foto attese. */
@Composable
internal fun EvalBrowsePage(session: EvalSession, env: EvalEnvironment, scope: CoroutineScope, id: String, onBack: () -> Unit) {
  val labels by session.labels.collectAsState()
  var from by remember { mutableStateOf("") }
  var to by remember { mutableStateOf("") }
  var place by remember { mutableStateOf("") }
  var cells by remember { mutableStateOf<List<EvalCell>?>(null) }
  FluidScreen(title = EvalStrings.BROWSE_TITLE, subtitle = EvalStrings.marked(labels.expectedOf(id).size), onBack = onBack) {
    item {
      Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FluidTextField(value = from, onValueChange = { from = it.filter(Char::isDigit).take(4) }, placeholder = EvalStrings.FROM_YEAR, modifier = Modifier.weight(1f))
          FluidTextField(value = to, onValueChange = { to = it.filter(Char::isDigit).take(4) }, placeholder = EvalStrings.TO_YEAR, modifier = Modifier.weight(1f))
        }
        FluidTextField(value = place, onValueChange = { place = it.take(60) }, placeholder = EvalStrings.PLACE, modifier = Modifier.fillMaxWidth())
        FluidButton(
          text = EvalStrings.SEARCH_BUTTON,
          fillWidth = true,
          onClick = {
            val filter = BrowseFilter(from.toIntOrNull(), to.toIntOrNull(), place)
            scope.launch { cells = withContext(Dispatchers.Default) { session.browse(filter) } }
          },
        )
      }
    }
    pickItems(cells, env, labels.expectedOf(id), onToggle = { cell, on -> session.setExpected(id, cell.key, on) })
  }
}

/** Seconda fase del pozzo: i primi 100 del motore o del coseno grezzo, dove si segna cio' che e' davvero atteso. */
@Composable
internal fun EvalPoolPage(session: EvalSession, env: EvalEnvironment, id: String, raw: Boolean, onBack: () -> Unit) {
  val labels by session.labels.collectAsState()
  var cells by remember(id, raw) { mutableStateOf<List<EvalCell>?>(null) }
  LaunchedEffect(id, raw) {
    cells = withContext(Dispatchers.Default) { if (raw) session.rawPool(id) else session.enginePool(id) }
  }
  FluidScreen(
    title = if (raw) EvalStrings.POOL_RAW_TITLE else EvalStrings.POOL_ENGINE_TITLE,
    subtitle = EvalStrings.marked(labels.expectedOf(id).size),
    onBack = onBack,
  ) {
    pickItems(cells, env, labels.expectedOf(id), onToggle = { cell, on -> session.setExpected(id, cell.key, on) })
  }
}

/** Il campione di "Documenti" (D34): si toccano le foto che SONO documenti; le altre del lotto, salvando, sono "non documento". */
@Composable
internal fun EvalDocumentsPage(session: EvalSession, env: EvalEnvironment, scope: CoroutineScope, onBack: () -> Unit) {
  val labels by session.labels.collectAsState()
  var batch by remember { mutableStateOf<List<EvalCell>?>(null) }
  var marked by remember { mutableStateOf(emptySet<String>()) }
  LaunchedEffect(Unit) { batch = withContext(Dispatchers.Default) { session.documentBatch() } }
  FluidScreen(title = EvalStrings.DOCS_TITLE, subtitle = EvalStrings.DOCS_DETAIL, onBack = onBack) {
    item { FluidSectionFootnote(text = EvalStrings.marked(labels.documents.size) + ", di cui documenti: " + labels.documents.values.count { it }) }
    pickItems(batch, env, marked, onToggle = { cell, on -> marked = if (on) marked + cell.key else marked - cell.key })
    item {
      Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
        FluidButton(
          text = EvalStrings.DOCS_NEXT,
          fillWidth = true,
          enabled = !batch.isNullOrEmpty(),
          onClick = {
            batch?.forEach { session.setDocument(it.key, it.key in marked) }
            marked = emptySet()
            batch = null
            scope.launch { batch = withContext(Dispatchers.Default) { session.documentBatch() } }
          },
        )
      }
    }
  }
}

/** La lettura di prova del set OCR sintetico con il lettore vero (solo se la lettura del testo e' attiva). */
@Composable
internal fun EvalOcrPage(env: EvalEnvironment, store: EvalStore, scope: CoroutineScope, onBack: () -> Unit) {
  var status by remember { mutableStateOf("") }
  var report by remember { mutableStateOf<String?>(null) }
  var running by remember { mutableStateOf(false) }
  val reader = env.textReader
  val runner = remember(reader) { reader?.let { EvalOcrRunner(it, store) } }
  FluidScreen(title = EvalStrings.OCR_TITLE, subtitle = EvalStrings.OCR_DETAIL, onBack = onBack) {
    item {
      Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (runner == null) FluidSectionFootnote(text = EvalStrings.OCR_OFF)
        FluidButton(
          text = if (running) EvalStrings.RUNNING else "Leggi il set",
          fillWidth = true,
          enabled = runner != null && !running,
          onClick = {
            val problem = runner?.problem()
            if (runner == null || problem != null) {
              status = problem ?: EvalStrings.OCR_OFF
              return@FluidButton
            }
            running = true
            scope.launch {
              status = try {
                val result = runner.run("lettore di questo dispositivo") { done, total -> status = "$done / $total" }
                report = result.report
                "Lette ${result.read}, rifiutate ${result.refused}, mancanti ${result.failed}. Resoconto in ocr/ocr-resoconto.txt"
              } catch (e: Exception) {
                "Corsa fermata: ${e.javaClass.simpleName}"
              } finally {
                running = false
              }
            }
          },
        )
        if (status.isNotEmpty()) FluidSectionFootnote(text = status)
      }
    }
    report?.let { text -> item { MonoText(text) } }
  }
}

@Composable
internal fun EvalResultsPage(page: EvalPage.Results, onBack: () -> Unit) {
  FluidScreen(title = page.file, subtitle = "Soli numeri: copia in docs/decisioni.md", onBack = onBack) {
    item { MonoText(page.text) }
    item {
      Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
        FluidButton(text = EvalStrings.BACK, style = FluidButtonStyle.Tinted, fillWidth = true, onClick = onBack)
      }
    }
  }
}

@Composable
private fun MonoText(text: String) {
  Text(text = text, fontFamily = FontFamily.Monospace, softWrap = false, modifier = Modifier.horizontalScroll(rememberScrollState()).padding(4.dp))
}

/** La griglia, oppure "Calcolo..." mentre le celle non ci sono, oppure l'avviso se sono zero. */
private fun androidx.compose.foundation.lazy.LazyListScope.pickItems(
  cells: List<EvalCell>?,
  env: EvalEnvironment,
  marked: Set<String>,
  onToggle: (EvalCell, Boolean) -> Unit,
) {
  when {
    cells == null -> item { FluidSectionFootnote(text = EvalStrings.RUNNING) }
    cells.isEmpty() -> item { FluidSectionFootnote(text = EvalStrings.NO_CELLS) }
    else -> evalGrid(cells, env, isMarked = { it.key in marked }, onToggle = { onToggle(it, it.key !in marked) })
  }
}
