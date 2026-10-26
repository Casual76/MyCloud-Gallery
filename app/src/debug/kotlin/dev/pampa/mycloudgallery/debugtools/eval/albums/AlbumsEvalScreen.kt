package dev.pampa.mycloudgallery.debugtools.eval.albums

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
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** SOLO debug: la schermata della valutazione degli album (Impostazioni > Sviluppo). La aggancia `DeveloperTools` (vedi [AlbumsEvalEntry]). */
@Serializable
data object AlbumsEvalRoute

/** Le pagine dello strumento. */
internal sealed interface AlbumsPage {
  data object Home : AlbumsPage

  data class Trip(val id: String) : AlbumsPage

  data class Blind(val id: String) : AlbumsPage

  data class Pending(val id: String, val suggested: Boolean) : AlbumsPage

  data class Missing(val id: String) : AlbumsPage

  data object Rehearsal : AlbumsPage

  data class Text(val title: String, val text: String) : AlbumsPage
}

/**
 * SOLO build debug (Fase 7, P12): lo strumento di **etichettatura e di corsa** della valutazione degli album e dei viaggi (docs/fase7-progetto.md §5.3, metodo "a
 * pozzo"; F88 e F89). Senza la porta [AlbumsEvalEnvironment] dice che gli album non sono collegati. I viaggi e le etichette sono dell'utente e restano sul telefono
 * ([AlbumsEvalStore]); il resoconto e' di soli numeri. L'album della corsa sta in memoria: la Libreria non cambia.
 */
@Composable
internal fun AlbumsEvalScreen(onBack: () -> Unit) {
  val env = AlbumsEvalEnvironmentHolder.current
  if (env == null) {
    FluidScreen(title = AlbumsEvalStrings.ENTRY_TITLE, subtitle = AlbumsEvalStrings.SCREEN_SUBTITLE, onBack = onBack) {
      item { FluidSectionFootnote(text = AlbumsEvalStrings.NOT_BOUND) }
    }
    return
  }
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val store = remember { AlbumsEvalStore.of(context) }
  val session = remember { AlbumsEvalSession(env, store).also { it.load() } }
  var page by remember { mutableStateOf<AlbumsPage>(AlbumsPage.Home) }
  val go: (AlbumsPage) -> Unit = { page = it }
  when (val p = page) {
    AlbumsPage.Home -> AlbumsHome(session, store, scope, onBack, go)
    is AlbumsPage.Trip -> AlbumsTripPage(session, scope, p.id, go)
    is AlbumsPage.Blind -> AlbumsBlindPage(session, env, p.id) { go(AlbumsPage.Trip(p.id)) }
    is AlbumsPage.Pending -> AlbumsPendingPage(session, env, p.id, p.suggested) { go(AlbumsPage.Trip(p.id)) }
    is AlbumsPage.Missing -> AlbumsMissingPage(session, env, p.id) { go(AlbumsPage.Trip(p.id)) }
    AlbumsPage.Rehearsal -> AlbumsRehearsalPage(env, remember { AlbumsEvalStore.rehearsalOf(context) }, scope) { go(AlbumsPage.Home) }
    is AlbumsPage.Text -> AlbumsTextPage(p) { go(AlbumsPage.Home) }
  }
}

@Composable
private fun AlbumsHome(session: AlbumsEvalSession, store: AlbumsEvalStore, scope: CoroutineScope, onBack: () -> Unit, go: (AlbumsPage) -> Unit) {
  val labels by session.labels.collectAsState()
  var title by remember { mutableStateOf("") }
  var from by remember { mutableStateOf("") }
  var to by remember { mutableStateOf("") }
  var busy by remember { mutableStateOf(false) }
  var confirm by remember { mutableStateOf(false) }
  var notice by remember { mutableStateOf(session.loadError?.let { AlbumsEvalStrings.LOAD_ERROR + it }) }
  FluidScreen(title = AlbumsEvalStrings.ENTRY_TITLE, subtitle = AlbumsEvalStrings.SCREEN_SUBTITLE, onBack = onBack) {
    item { FluidSectionHeader(title = AlbumsEvalStrings.SECTION_HOW) }
    item {
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (line in listOf(AlbumsEvalStrings.HOW_1, AlbumsEvalStrings.HOW_2, AlbumsEvalStrings.HOW_3, AlbumsEvalStrings.HOW_4, AlbumsEvalStrings.HOW_5)) {
          FluidSectionFootnote(text = line)
        }
      }
    }
    item { FluidSectionHeader(title = AlbumsEvalStrings.SECTION_TRIPS, detail = "${labels.trips.size} viaggi, ${labels.titled.size} con titolo") }
    if (labels.trips.isEmpty()) {
      item { FluidSectionFootnote(text = AlbumsEvalStrings.NO_TRIPS) }
    } else {
      item {
        FluidListGroup {
          labels.trips.forEachIndexed { index, trip ->
            if (index > 0) FluidListDivider()
            FluidListRow(
              title = trip.title ?: "Viaggio ${trip.id}",
              subtitle = "${trip.from} / ${trip.to}: " + AlbumsEvalStrings.tripRow(trip.days, labels.inTripOf(trip.id).size),
              onClick = { go(AlbumsPage.Trip(trip.id)) },
            )
          }
        }
      }
    }
    item { FluidSectionHeader(title = AlbumsEvalStrings.ADD_TITLE) }
    item {
      Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FluidTextField(value = title, onValueChange = { title = it.take(EvalTrip.MAX_TITLE) }, placeholder = AlbumsEvalStrings.TITLE_FIELD, modifier = Modifier.fillMaxWidth())
        FluidTextField(value = from, onValueChange = { from = it.take(10) }, placeholder = AlbumsEvalStrings.FROM_FIELD, modifier = Modifier.fillMaxWidth())
        FluidTextField(value = to, onValueChange = { to = it.take(10) }, placeholder = AlbumsEvalStrings.TO_FIELD, modifier = Modifier.fillMaxWidth())
        FluidButton(
          text = AlbumsEvalStrings.ADD_BUTTON,
          enabled = from.isNotBlank() && to.isNotBlank() && !labels.listClosed,
          fillWidth = true,
          onClick = {
            try {
              val trip = session.addTrip(title, LocalDate.parse(from.trim()), LocalDate.parse(to.trim()))
              title = ""
              from = ""
              to = ""
              notice = null
              go(AlbumsPage.Trip(trip.id))
            } catch (e: DateTimeParseException) {
              notice = AlbumsEvalStrings.BAD_DAY
            } catch (e: IllegalArgumentException) {
              notice = AlbumsEvalStrings.BAD_DAY
            }
          },
        )
        if (labels.listClosed) FluidSectionFootnote(text = AlbumsEvalStrings.LIST_CLOSED)
      }
    }
    item { FluidSectionHeader(title = AlbumsEvalStrings.SECTION_TOOLS) }
    item {
      FluidListGroup {
        FluidListRow(
          title = AlbumsEvalStrings.F88_TITLE,
          subtitle = AlbumsEvalStrings.F88_DETAIL,
          onClick = {
            val saved = session.saveF88Report()
            if (saved == null) notice = AlbumsEvalStrings.F88_NEEDS_RUN else go(AlbumsPage.Text(saved.first, saved.second))
          },
        )
        FluidListDivider()
        FluidListRow(
          title = AlbumsEvalStrings.F89_TITLE,
          subtitle = if (confirm) AlbumsEvalStrings.F89_CONFIRM else if (busy) AlbumsEvalStrings.RUNNING else AlbumsEvalStrings.F89_DETAIL,
          onClick = {
            if (busy) return@FluidListRow
            if (!confirm && !labels.listClosed) {
              confirm = true
              return@FluidListRow
            }
            confirm = false
            busy = true
            notice = null
            scope.launch {
              try {
                val result = withContext(Dispatchers.Default) { session.compareTrips() }
                if (result == null) notice = AlbumsEvalStrings.F89_NOT_AVAILABLE else go(AlbumsPage.Text(result.second, result.third))
              } catch (e: IllegalStateException) {
                notice = e.message
              } catch (e: Exception) {
                notice = AlbumsEvalStrings.RUN_FAILED + e.javaClass.simpleName
              } finally {
                busy = false
              }
            }
          },
        )
        FluidListDivider()
        FluidListRow(title = AlbumsEvalStrings.REHEARSAL_TITLE, subtitle = AlbumsEvalStrings.REHEARSAL_DETAIL, onClick = { go(AlbumsPage.Rehearsal) })
      }
    }
    notice?.let { message -> item { FluidSectionFootnote(text = message) } }
    item { FluidSectionHeader(title = AlbumsEvalStrings.SECTION_REPORTS) }
    item {
      Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (name in store.reports()) {
          FluidButton(text = name, style = FluidButtonStyle.Tinted, fillWidth = true, onClick = { store.readReport(name)?.let { go(AlbumsPage.Text(name, it)) } })
        }
        FluidButton(
          text = AlbumsEvalStrings.WIPE_TITLE,
          style = FluidButtonStyle.Tinted,
          fillWidth = true,
          onClick = {
            store.wipe()
            session.load()
            confirm = false
          },
        )
        FluidSectionFootnote(text = AlbumsEvalStrings.WIPE_DETAIL)
      }
    }
  }
}
