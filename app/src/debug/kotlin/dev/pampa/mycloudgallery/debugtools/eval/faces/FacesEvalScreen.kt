package dev.pampa.mycloudgallery.debugtools.eval.faces

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** SOLO debug: la schermata della valutazione delle persone (Diagnostica > Sviluppo). La aggancia `DeveloperTools` (vedi [FacesEvalEntry]). */
@Serializable
data object FacesEvalRoute

/** Le pagine dello strumento. */
internal sealed interface FacesPage {
  data object Home : FacesPage

  data class Groups(val kind: EvalSessionKind) : FacesPage

  data class Group(val kind: EvalSessionKind, val person: String) : FacesPage {
    /** D37: mai un id di persona in un `toString`. */
    override fun toString(): String = "Group(${kind.code})"
  }

  data class Same(val kind: EvalSessionKind, val person: String) : FacesPage {
    override fun toString(): String = "Same(${kind.code})"
  }

  data class Text(val title: String, val text: String) : FacesPage
}

/**
 * SOLO build debug (Fase 8, P11): lo strumento di **etichettatura e di misura** della qualita' delle persone (docs/fase8-progetto.md §5.3, F105). Senza la porta
 * [FacesEvalEnvironment] dice che le persone non sono collegate. Le etichette sono dell'utente e restano sul telefono ([FacesEvalStore]); i resoconti sono di soli
 * numeri. Lo strumento non scrive mai nell'archivio dei volti.
 */
@Composable
internal fun FacesEvalScreen(onBack: () -> Unit) {
  val env = FacesEvalEnvironmentHolder.current
  if (env == null) {
    FluidScreen(title = FacesEvalStrings.ENTRY_TITLE, subtitle = FacesEvalStrings.SCREEN_SUBTITLE, onBack = onBack) {
      item { FluidSectionFootnote(text = FacesEvalStrings.NOT_BOUND) }
    }
    return
  }
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val store = remember { FacesEvalStore.of(context) }
  val session = remember { FacesEvalSession(env, store).also { it.load() } }
  var page by remember { mutableStateOf<FacesPage>(FacesPage.Home) }
  val go: (FacesPage) -> Unit = { page = it }
  when (val p = page) {
    FacesPage.Home -> FacesHome(session, store, scope, onBack, go)
    is FacesPage.Groups -> FacesGroupsPage(session, p.kind, go)
    is FacesPage.Group -> FacesGroupPage(session, env, p.kind, p.person, go)
    is FacesPage.Same -> FacesSamePage(session, env, p.kind, p.person, go)
    is FacesPage.Text -> FacesTextPage(p) { go(FacesPage.Home) }
  }
}

@Composable
private fun FacesHome(session: FacesEvalSession, store: FacesEvalStore, scope: CoroutineScope, onBack: () -> Unit, go: (FacesPage) -> Unit) {
  val labels by session.labels.collectAsState()
  var busy by remember { mutableStateOf(false) }
  var confirmWipe by remember { mutableStateOf(false) }
  var notice by remember { mutableStateOf(session.loadError?.let { FacesEvalStrings.LOAD_ERROR + it }) }
  var reports by remember { mutableStateOf(store.reports()) }

  fun open(kind: EvalSessionKind) {
    if (busy) return
    busy = true
    notice = null
    scope.launch {
      try {
        val s = withContext(Dispatchers.Default) { session.start(kind) }
        if (s.groups.isEmpty()) notice = FacesEvalStrings.NO_GROUPS else go(FacesPage.Groups(kind))
      } catch (e: Exception) {
        notice = FacesEvalStrings.START_FAILED + e.javaClass.simpleName
      } finally {
        busy = false
      }
    }
  }

  fun showNumbers(kind: EvalSessionKind) {
    val saved = session.saveReport(kind)
    if (saved == null) {
      notice = FacesEvalStrings.NUMBERS_NEED_SESSION
    } else {
      reports = store.reports()
      go(FacesPage.Text(saved.first, saved.second))
    }
  }

  FluidScreen(title = FacesEvalStrings.ENTRY_TITLE, subtitle = FacesEvalStrings.SCREEN_SUBTITLE, onBack = onBack) {
    item { FluidSectionHeader(title = FacesEvalStrings.SECTION_HOW) }
    item {
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (line in listOf(FacesEvalStrings.HOW_1, FacesEvalStrings.HOW_2, FacesEvalStrings.HOW_3, FacesEvalStrings.HOW_4, FacesEvalStrings.HOW_5)) {
          FluidSectionFootnote(text = line)
        }
      }
    }
    item { FluidSectionHeader(title = FacesEvalStrings.SESSION_1, detail = FacesEvalStrings.SESSION_1_DETAIL) }
    item { SessionRow(labels.session(EvalSessionKind.CALIBRATION), busy, onOpen = { open(EvalSessionKind.CALIBRATION) }) }
    item { FluidSectionHeader(title = FacesEvalStrings.SESSION_2, detail = FacesEvalStrings.SESSION_2_DETAIL) }
    item { SessionRow(labels.session(EvalSessionKind.MEASURE), busy, onOpen = { open(EvalSessionKind.MEASURE) }) }
    item { FluidSectionHeader(title = FacesEvalStrings.SECTION_NUMBERS) }
    item {
      FluidListGroup {
        FluidListRow(title = FacesEvalStrings.NUMBERS_1_TITLE, subtitle = FacesEvalStrings.NUMBERS_DETAIL, onClick = { showNumbers(EvalSessionKind.CALIBRATION) })
        FluidListDivider()
        FluidListRow(
          title = FacesEvalStrings.PROPOSAL_TITLE,
          subtitle = if (busy) FacesEvalStrings.RUNNING else FacesEvalStrings.PROPOSAL_DETAIL,
          onClick = {
            if (busy) return@FluidListRow
            busy = true
            notice = null
            scope.launch {
              try {
                val saved = withContext(Dispatchers.Default) { session.saveProposal() }
                if (saved == null) notice = FacesEvalStrings.PROPOSAL_NEEDS else go(FacesPage.Text(saved.first, saved.second))
                reports = store.reports()
              } catch (e: Exception) {
                notice = FacesEvalStrings.RUN_FAILED + e.javaClass.simpleName
              } finally {
                busy = false
              }
            }
          },
        )
        FluidListDivider()
        FluidListRow(title = FacesEvalStrings.NUMBERS_2_TITLE, subtitle = FacesEvalStrings.NUMBERS_DETAIL, onClick = { showNumbers(EvalSessionKind.MEASURE) })
      }
    }
    notice?.let { message -> item { FluidSectionFootnote(text = message) } }
    item { FluidSectionHeader(title = FacesEvalStrings.SECTION_REPORTS) }
    item {
      Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (name in reports) {
          FluidButton(text = name, style = FluidButtonStyle.Tinted, fillWidth = true, onClick = { store.readReport(name)?.let { go(FacesPage.Text(name, it)) } })
        }
        FluidButton(
          text = if (confirmWipe) FacesEvalStrings.WIPE_CONFIRM else FacesEvalStrings.WIPE_TITLE,
          style = FluidButtonStyle.Tinted,
          fillWidth = true,
          onClick = {
            if (!confirmWipe) {
              confirmWipe = true
            } else {
              try {
                session.wipe()
                reports = store.reports()
                notice = null
              } catch (e: java.io.IOException) {
                notice = e.message
              }
              confirmWipe = false
            }
          },
        )
        FluidSectionFootnote(text = FacesEvalStrings.WIPE_DETAIL)
      }
    }
  }
}

/** Lo stato di una sessione (non iniziata, a che punto, chiusa) e il tasto che la apre. */
@Composable
private fun SessionRow(session: SessionLabels?, busy: Boolean, onOpen: () -> Unit) {
  Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    FluidSectionFootnote(text = if (session == null) FacesEvalStrings.NOT_STARTED else FacesEvalStrings.sessionProgress(session.reviewedGroups.size, session.groups.size, session.closed))
    FluidButton(
      text = if (busy) FacesEvalStrings.STARTING else if (session == null) FacesEvalStrings.START else FacesEvalStrings.CONTINUE,
      enabled = !busy,
      fillWidth = true,
      onClick = onOpen,
    )
  }
}
