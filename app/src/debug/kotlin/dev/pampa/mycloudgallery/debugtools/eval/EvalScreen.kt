package dev.pampa.mycloudgallery.debugtools.eval

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidChip
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** SOLO debug: la schermata della valutazione della ricerca (Diagnostica > Sviluppo). La aggancia P11 (vedi [EvalEntry]). */
@Serializable
data object EvalRoute

/** Le pagine dello strumento. */
internal sealed interface EvalPage {
  data object Home : EvalPage

  data class Query(val id: String) : EvalPage

  data class Browse(val id: String) : EvalPage

  data class Pool(val id: String, val raw: Boolean) : EvalPage

  data object Documents : EvalPage

  data object Ocr : EvalPage

  data class Results(val text: String, val file: String) : EvalPage
}

/**
 * SOLO build debug (Fase 6, P9): lo strumento di **etichettatura e di corsa** della valutazione della ricerca (docs/fase6-progetto.md §5.3, metodo "a
 * pozzo"). Senza la porta [EvalEnvironment] (la lega P11) dice che la ricerca non e' collegata. Le query e le etichette sono dell'utente e restano sul
 * telefono ([EvalStore]); il resoconto e' di soli numeri.
 */
@Composable
internal fun EvalScreen(onBack: () -> Unit) {
  val env = EvalEnvironmentHolder.current
  if (env == null) {
    FluidScreen(title = EvalStrings.ENTRY_TITLE, subtitle = EvalStrings.SCREEN_SUBTITLE, onBack = onBack) {
      item { FluidSectionFootnote(text = EvalStrings.NOT_BOUND) }
    }
    return
  }
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val store = remember { EvalStore.of(context) }
  val session = remember { EvalSession(env, store).also { it.load() } }
  var page by remember { mutableStateOf<EvalPage>(EvalPage.Home) }
  val go: (EvalPage) -> Unit = { page = it }
  when (val p = page) {
    EvalPage.Home -> EvalHome(session, store, env, scope, onBack, go)
    is EvalPage.Query -> EvalQueryPage(session, p.id, go)
    is EvalPage.Browse -> EvalBrowsePage(session, env, scope, p.id) { go(EvalPage.Query(p.id)) }
    is EvalPage.Pool -> EvalPoolPage(session, env, p.id, p.raw) { go(EvalPage.Query(p.id)) }
    EvalPage.Documents -> EvalDocumentsPage(session, env, scope) { go(EvalPage.Home) }
    EvalPage.Ocr -> EvalOcrPage(env, store, scope) { go(EvalPage.Home) }
    is EvalPage.Results -> EvalResultsPage(p) { go(EvalPage.Home) }
  }
}

@Composable
private fun EvalHome(
  session: EvalSession,
  store: EvalStore,
  env: EvalEnvironment,
  scope: CoroutineScope,
  onBack: () -> Unit,
  go: (EvalPage) -> Unit,
) {
  val labels by session.labels.collectAsState()
  var text by remember { mutableStateOf("") }
  var category by remember { mutableStateOf(EvalCategory.OBJECTS) }
  var running by remember { mutableStateOf(false) }
  var notice by remember { mutableStateOf(session.loadError?.let { EvalStrings.LOAD_ERROR + it }) }
  FluidScreen(title = EvalStrings.ENTRY_TITLE, subtitle = EvalStrings.SCREEN_SUBTITLE, onBack = onBack) {
    item { FluidSectionHeader(title = EvalStrings.SECTION_HOW) }
    item {
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (line in listOf(EvalStrings.HOW_1, EvalStrings.HOW_2, EvalStrings.HOW_3, EvalStrings.HOW_4)) FluidSectionFootnote(text = line)
      }
    }
    item { FluidSectionHeader(title = EvalStrings.SECTION_QUERIES, detail = "${labels.queries.size} query, ${labels.expected.values.sumOf { it.size }} foto attese") }
    if (labels.queries.isNotEmpty()) {
      item {
        FluidListGroup {
          labels.queries.forEachIndexed { index, q ->
            if (index > 0) FluidListDivider()
            FluidListRow(
              title = q.text,
              subtitle = "${q.category.label} - " + EvalStrings.queryRow(q.id, labels.expectedOf(q.id).size),
              onClick = { go(EvalPage.Query(q.id)) },
            )
          }
        }
      }
    }
    item { FluidSectionHeader(title = EvalStrings.ADD_TITLE) }
    item {
      Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FluidTextField(value = text, onValueChange = { text = it.take(EvalQuery.MAX_TEXT) }, placeholder = EvalStrings.QUERY_FIELD, modifier = Modifier.fillMaxWidth())
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          for (c in EvalCategory.entries) FluidChip(label = c.label, selected = category == c, onClick = { category = c })
        }
        FluidButton(
          text = EvalStrings.ADD_BUTTON,
          enabled = text.isNotBlank() && labels.queries.size < LabelSet.MAX_QUERIES,
          fillWidth = true,
          onClick = {
            val q = session.addQuery(category, text)
            text = ""
            go(EvalPage.Query(q.id))
          },
        )
      }
    }
    item { FluidSectionHeader(title = "Strumenti") }
    item {
      FluidListGroup {
        FluidListRow(
          title = EvalStrings.DOCS_TITLE,
          subtitle = EvalStrings.DOCS_DETAIL + " (" + EvalStrings.marked(labels.documents.size) + ")",
          onClick = { go(EvalPage.Documents) },
        )
        FluidListDivider()
        FluidListRow(
          title = EvalStrings.RUN_TITLE,
          subtitle = if (running) EvalStrings.RUNNING else EvalStrings.RUN_DETAIL,
          onClick = {
            if (running) return@FluidListRow
            if (labels.expected.values.none { it.isNotEmpty() }) {
              notice = EvalStrings.RUN_NEEDS_LABELS
              return@FluidListRow
            }
            running = true
            notice = null
            scope.launch {
              val run = try {
                withContext(Dispatchers.Default) { session.run() }
              } finally {
                running = false
              }
              go(EvalPage.Results(run.outcome.text, run.reportName))
            }
          },
        )
        FluidListDivider()
        FluidListRow(title = EvalStrings.OCR_TITLE, subtitle = EvalStrings.OCR_DETAIL, onClick = { go(EvalPage.Ocr) })
      }
    }
    notice?.let { message -> item { FluidSectionFootnote(text = message) } }
    item { FluidSectionHeader(title = "Resoconti salvati") }
    item {
      Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (name in store.reports()) {
          FluidButton(
            text = name,
            style = FluidButtonStyle.Tinted,
            fillWidth = true,
            onClick = { store.readReport(name)?.let { go(EvalPage.Results(it, name)) } },
          )
        }
        FluidButton(
          text = EvalStrings.WIPE_TITLE,
          style = FluidButtonStyle.Tinted,
          fillWidth = true,
          onClick = {
            store.wipe()
            session.load()
          },
        )
        FluidSectionFootnote(text = EvalStrings.WIPE_DETAIL)
      }
    }
  }
}

@Composable
private fun EvalQueryPage(session: EvalSession, id: String, go: (EvalPage) -> Unit) {
  val labels by session.labels.collectAsState()
  val query = labels.query(id)
  FluidScreen(title = query?.text ?: EvalStrings.ENTRY_TITLE, subtitle = query?.let { "${it.category.label} - ${EvalStrings.marked(labels.expectedOf(id).size)}" }, onBack = { go(EvalPage.Home) }) {
    item {
      FluidListGroup {
        FluidListRow(title = EvalStrings.BROWSE_TITLE, subtitle = EvalStrings.BROWSE_DETAIL, onClick = { go(EvalPage.Browse(id)) })
        FluidListDivider()
        FluidListRow(title = EvalStrings.POOL_ENGINE_TITLE, subtitle = EvalStrings.POOL_DETAIL, onClick = { go(EvalPage.Pool(id, raw = false)) })
        FluidListDivider()
        FluidListRow(title = EvalStrings.POOL_RAW_TITLE, subtitle = EvalStrings.POOL_DETAIL, onClick = { go(EvalPage.Pool(id, raw = true)) })
      }
    }
    item {
      Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
        FluidButton(
          text = EvalStrings.REMOVE_BUTTON,
          style = FluidButtonStyle.Tinted,
          fillWidth = true,
          onClick = {
            session.removeQuery(id)
            go(EvalPage.Home)
          },
        )
      }
    }
  }
}

/** Per i test e per P11: il contesto non serve a niente qui, ma la firma di apertura e' una sola. */
internal fun evalStoreOf(context: Context): EvalStore = EvalStore.of(context)
