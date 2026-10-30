package dev.pampa.mycloudgallery.debugtools.eval.memories

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** SOLO debug: la schermata della valutazione dei ricordi (Impostazioni > Sviluppo). La aggancia `DeveloperTools` (vedi [MemoriesEvalEntry]). */
@Serializable
data object MemoriesEvalRoute

/** Le pagine dello strumento. */
internal sealed interface MemoriesPage {
  data object Home : MemoriesPage

  data class Memory(val id: String) : MemoriesPage

  data class Text(val title: String, val text: String) : MemoriesPage
}

/**
 * SOLO build debug (Fase 9, P11): lo strumento di **valutazione dei ricordi** sui dati veri (docs/fase9-progetto.md §5.3, F125). Senza la porta
 * [MemoriesEvalEnvironment] dice che i ricordi non sono collegati. I giudizi sono dell'utente e restano sul telefono ([MemoriesEvalStore]); il resoconto e' di soli numeri.
 * La corsa sta in memoria: lo scaffale e le scelte dell'utente non cambiano.
 */
@Composable
internal fun MemoriesEvalScreen(onBack: () -> Unit) {
  val env = MemoriesEvalEnvironmentHolder.current
  if (env == null) {
    FluidScreen(title = MemoriesEvalStrings.ENTRY_TITLE, subtitle = MemoriesEvalStrings.SCREEN_SUBTITLE, onBack = onBack) {
      item { FluidSectionFootnote(text = MemoriesEvalStrings.NOT_BOUND) }
    }
    return
  }
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val store = remember { MemoriesEvalStore.of(context) }
  val session = remember { MemoriesEvalSession(env, store).also { it.load() } }
  var page by remember { mutableStateOf<MemoriesPage>(MemoriesPage.Home) }
  val go: (MemoriesPage) -> Unit = { page = it }
  when (val p = page) {
    MemoriesPage.Home -> MemoriesHome(session, store, scope, onBack, go)
    is MemoriesPage.Memory -> MemoriesMemoryPage(session, env, p.id, go)
    is MemoriesPage.Text -> MemoriesTextPage(p) { go(MemoriesPage.Home) }
  }
}

@Composable
private fun MemoriesHome(session: MemoriesEvalSession, store: MemoriesEvalStore, scope: CoroutineScope, onBack: () -> Unit, go: (MemoriesPage) -> Unit) {
  val labels by session.labels.collectAsState()
  var busy by remember { mutableStateOf(false) }
  var confirm by remember { mutableStateOf(false) }
  var notice by remember { mutableStateOf(session.loadError?.let { MemoriesEvalStrings.LOAD_ERROR + it }) }
  var reports by remember { mutableStateOf(store.reports()) }
  // Se qualcosa e' stato nascosto dopo la corsa, esce dallo strumento (D31).
  LaunchedEffect(Unit) {
    try {
      if (withContext(Dispatchers.Default) { session.dropHidden() }) notice = MemoriesEvalStrings.HIDDEN_DROPPED
    } catch (e: Exception) {
      // La porta non risponde: la sessione resta com'e' e lo si vede alla prossima apertura.
    }
  }
  FluidScreen(title = MemoriesEvalStrings.ENTRY_TITLE, subtitle = MemoriesEvalStrings.SCREEN_SUBTITLE, onBack = onBack) {
    item { FluidSectionHeader(title = MemoriesEvalStrings.SECTION_HOW) }
    item {
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (line in listOf(MemoriesEvalStrings.HOW_1, MemoriesEvalStrings.HOW_2, MemoriesEvalStrings.HOW_3, MemoriesEvalStrings.HOW_4, MemoriesEvalStrings.HOW_5)) {
          FluidSectionFootnote(text = line)
        }
      }
    }
    item { FluidSectionHeader(title = MemoriesEvalStrings.SECTION_RUN) }
    item {
      Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FluidButton(
          text = if (busy) MemoriesEvalStrings.RUNNING else MemoriesEvalStrings.RUN_BUTTON,
          enabled = !busy,
          fillWidth = true,
          onClick = {
            if (labels.judgements.isNotEmpty() && !confirm) {
              confirm = true
              return@FluidButton
            }
            confirm = false
            busy = true
            notice = null
            scope.launch {
              try {
                val count = withContext(Dispatchers.Default) { session.start() }
                notice = MemoriesEvalStrings.RUN_DONE + count
              } catch (e: IllegalStateException) {
                notice = e.message
              } catch (e: Exception) {
                notice = MemoriesEvalStrings.RUN_FAILED + e.javaClass.simpleName
              } finally {
                busy = false
              }
            }
          },
        )
        if (confirm) FluidSectionFootnote(text = MemoriesEvalStrings.RUN_AGAIN_CONFIRM)
        notice?.let { FluidSectionFootnote(text = it) }
      }
    }
    item { FluidSectionHeader(title = MemoriesEvalStrings.SESSION_HEADER, detail = "${labels.judged.size} giudicati su ${labels.run.size}") }
    if (labels.run.isEmpty()) {
      item { FluidSectionFootnote(text = MemoriesEvalStrings.NO_SESSION) }
    } else {
      item {
        FluidListGroup {
          labels.run.forEachIndexed { index, memory ->
            if (index > 0) FluidListDivider()
            FluidListRow(
              title = MemoriesEvalStrings.row(index, memory.kind, memory.members.size, labels.judgement(memory.id), labels.faultsOf(memory.id).size),
              subtitle = memory.title,
              onClick = { go(MemoriesPage.Memory(memory.id)) },
            )
          }
        }
      }
    }
    item { FluidSectionHeader(title = MemoriesEvalStrings.SECTION_TOOLS) }
    item {
      FluidListGroup {
        FluidListRow(
          title = MemoriesEvalStrings.NUMBERS_TITLE,
          subtitle = MemoriesEvalStrings.NUMBERS_DETAIL,
          onClick = {
            scope.launch {
              try {
                withContext(Dispatchers.Default) { session.dropHidden() }
              } catch (e: Exception) {
                // Senza la porta si contano i giudizi com'e': niente di nascosto e' entrato dopo l'ultima apertura che ha funzionato.
              }
              val saved = session.saveReport()
              reports = store.reports()
              if (saved == null) notice = MemoriesEvalStrings.NUMBERS_NEEDS_JUDGEMENT else go(MemoriesPage.Text(saved.first, saved.second))
            }
          },
        )
      }
    }
    item { FluidSectionHeader(title = MemoriesEvalStrings.SECTION_REPORTS) }
    item {
      Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (name in reports) {
          FluidButton(text = name, style = FluidButtonStyle.Tinted, fillWidth = true, onClick = { store.readReport(name)?.let { go(MemoriesPage.Text(name, it)) } })
        }
        FluidButton(
          text = MemoriesEvalStrings.WIPE_TITLE,
          style = FluidButtonStyle.Tinted,
          fillWidth = true,
          onClick = {
            session.wipe()
            reports = store.reports()
            confirm = false
            notice = null
          },
        )
        FluidSectionFootnote(text = MemoriesEvalStrings.WIPE_DETAIL)
      }
    }
  }
}

/** Un ricordo: titolo, la griglia dove si tocca una foto che non doveva esserci, e il tocco sul ricordo intero. */
@Composable
private fun MemoriesMemoryPage(session: MemoriesEvalSession, env: MemoriesEvalEnvironment, id: String, go: (MemoriesPage) -> Unit) {
  val labels by session.labels.collectAsState()
  val memory = labels.memory(id)
  var selected by remember(id) { mutableStateOf<String?>(null) }
  var finished by remember(id) { mutableStateOf(false) }
  if (memory == null) {
    LaunchedEffect(Unit) { go(MemoriesPage.Home) }
    return
  }
  val index = labels.run.indexOfFirst { it.id == id }
  val judgement = labels.judgement(id)
  val faults = labels.faultsOf(id)
  FluidScreen(
    title = "Ricordo ${index + 1}",
    subtitle = "${MemoriesEvalStrings.kind(memory.kind)}, ${memory.members.size} foto: ${MemoriesEvalStrings.verdict(judgement?.verdict)}",
    onBack = { go(MemoriesPage.Home) },
  ) {
    item { FluidSectionHeader(title = memory.title.ifEmpty { MemoriesEvalStrings.kind(memory.kind) }) }
    item { FluidSectionFootnote(text = MemoriesEvalStrings.GRID_HINT) }
    memoriesGrid(memory, env, { faults[it] }, selected) { media -> selected = if (selected == media) null else media }
    selected?.let { media ->
      item {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          FluidSectionFootnote(text = MemoriesEvalStrings.SELECTED_HINT)
          for (fault in EvalFault.entries) {
            FluidButton(
              text = MemoriesEvalStrings.fault(fault),
              style = if (faults[media] == fault) FluidButtonStyle.Filled else FluidButtonStyle.Tinted,
              fillWidth = true,
              onClick = {
                session.setFault(id, media, fault)
                selected = null
              },
            )
          }
          if (faults[media] != null) {
            FluidButton(
              text = MemoriesEvalStrings.FAULT_REMOVE,
              style = FluidButtonStyle.Plain,
              fillWidth = true,
              onClick = {
                session.setFault(id, media, null)
                selected = null
              },
            )
          }
        }
      }
    }
    item { FluidSectionHeader(title = MemoriesEvalStrings.SESSION_HEADER, detail = MemoriesEvalStrings.verdict(judgement?.verdict)) }
    item {
      Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (verdict in EvalVerdict.entries) {
          FluidButton(
            text = MemoriesEvalStrings.verdict(verdict),
            style = if (judgement?.verdict == verdict) FluidButtonStyle.Filled else FluidButtonStyle.Tinted,
            fillWidth = true,
            onClick = { session.judge(id, verdict, judgement?.takeIf { it.verdict == verdict }?.why) },
          )
        }
        if (judgement != null && judgement.verdict == EvalVerdict.DONT_SHOW) {
          FluidSectionFootnote(text = MemoriesEvalStrings.WHY_HINT)
          for (why in EvalWhy.entries) {
            FluidButton(
              text = MemoriesEvalStrings.why(why),
              style = if (judgement.why == why) FluidButtonStyle.Filled else FluidButtonStyle.Tinted,
              fillWidth = true,
              onClick = { session.judge(id, EvalVerdict.DONT_SHOW, why) },
            )
          }
          FluidButton(
            text = MemoriesEvalStrings.NO_WHY,
            style = if (judgement.why == null) FluidButtonStyle.Filled else FluidButtonStyle.Tinted,
            fillWidth = true,
            onClick = { session.judge(id, EvalVerdict.DONT_SHOW, null) },
          )
        }
        if (faults.isNotEmpty()) FluidButton(text = MemoriesEvalStrings.ALL_RIGHT, style = FluidButtonStyle.Tinted, fillWidth = true, onClick = { session.clearFaults(id) })
        FluidButton(
          text = MemoriesEvalStrings.NEXT_PENDING,
          style = FluidButtonStyle.Tinted,
          fillWidth = true,
          onClick = {
            val next = session.nextPending(id)
            if (next == null) finished = true else go(MemoriesPage.Memory(next))
          },
        )
        if (judgement != null) FluidButton(text = MemoriesEvalStrings.CLEAR_JUDGEMENT, style = FluidButtonStyle.Plain, fillWidth = true, onClick = { session.clearJudgement(id) })
        if (finished) FluidSectionFootnote(text = MemoriesEvalStrings.NO_MORE)
      }
    }
  }
}

@Composable
private fun MemoriesTextPage(page: MemoriesPage.Text, onBack: () -> Unit) {
  FluidScreen(title = page.title, subtitle = "Soli numeri: copia in docs/decisioni.md", onBack = onBack) {
    item { Text(text = page.text, fontFamily = FontFamily.Monospace, softWrap = false, modifier = Modifier.horizontalScroll(rememberScrollState()).padding(4.dp)) }
    item {
      Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
        FluidButton(text = MemoriesEvalStrings.BACK, style = FluidButtonStyle.Tinted, fillWidth = true, onClick = onBack)
      }
    }
  }
}
