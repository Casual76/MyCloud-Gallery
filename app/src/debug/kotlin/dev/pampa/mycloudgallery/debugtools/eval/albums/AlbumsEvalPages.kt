package dev.pampa.mycloudgallery.debugtools.eval.albums

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
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// SOLO debug (Fase 7, P12): le pagine dello strumento che mostrano foto o numeri. Le foto sono sempre miniature locali ([AlbumsEvalEnvironment.thumbnail]).

/** Un viaggio: i tre passi del metodo a pozzo e i numeri di questo viaggio. */
@Composable
internal fun AlbumsTripPage(session: AlbumsEvalSession, scope: CoroutineScope, id: String, go: (AlbumsPage) -> Unit) {
  val labels by session.labels.collectAsState()
  val trip = labels.trip(id)
  var run by remember(id) { mutableStateOf(session.runOf(id)) }
  var running by remember(id) { mutableStateOf(false) }
  var notice by remember(id) { mutableStateOf<String?>(null) }
  var newTitle by remember(id) { mutableStateOf("") }
  if (trip == null) {
    LaunchedEffect(Unit) { go(AlbumsPage.Home) }
    return
  }
  val metrics = remember(labels, run) { session.metrics(id) }
  val pending = remember(labels, run) { session.pending(id) }
  FluidScreen(
    title = trip.title ?: "Viaggio ${trip.id}",
    subtitle = "${trip.from} / ${trip.to}: " + AlbumsEvalStrings.marked(labels.inTripOf(id).size),
    onBack = { go(AlbumsPage.Home) },
  ) {
    item {
      FluidListGroup {
        FluidListRow(title = AlbumsEvalStrings.TRIP_BLIND_TITLE, subtitle = AlbumsEvalStrings.TRIP_BLIND_DETAIL, onClick = { go(AlbumsPage.Blind(id)) })
        FluidListDivider()
        FluidListRow(
          title = AlbumsEvalStrings.TRIP_RUN_TITLE,
          subtitle = if (running) AlbumsEvalStrings.RUNNING else AlbumsEvalStrings.TRIP_RUN_DETAIL,
          onClick = {
            if (running) return@FluidListRow
            if (trip.title == null) {
              notice = AlbumsEvalStrings.NEEDS_TITLE
              return@FluidListRow
            }
            running = true
            notice = null
            scope.launch {
              try {
                run = withContext(Dispatchers.Default) { session.runTrip(id) }
              } catch (e: IllegalStateException) {
                notice = e.message
              } catch (e: Exception) {
                notice = AlbumsEvalStrings.RUN_FAILED + e.javaClass.simpleName
              } finally {
                running = false
              }
            }
          },
        )
        FluidListDivider()
        FluidListRow(
          title = AlbumsEvalStrings.TRIP_SURE_TITLE,
          subtitle = "${pending.sure.size} da giudicare",
          onClick = { go(AlbumsPage.Pending(id, suggested = false)) },
        )
        FluidListDivider()
        FluidListRow(
          title = AlbumsEvalStrings.TRIP_SUGGESTED_TITLE,
          subtitle = "${pending.suggested.size} da giudicare",
          onClick = { go(AlbumsPage.Pending(id, suggested = true)) },
        )
        FluidListDivider()
        FluidListRow(title = AlbumsEvalStrings.TRIP_MISSING_TITLE, subtitle = AlbumsEvalStrings.TRIP_MISSING_DETAIL, onClick = { go(AlbumsPage.Missing(id)) })
      }
    }
    notice?.let { message -> item { FluidSectionFootnote(text = message) } }
    val m = metrics
    if (m != null && run != null) {
      item { FluidSectionHeader(title = "Numeri di questo viaggio") }
      item {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          FluidSectionFootnote(text = "Sicuri ${m.sure}: giusti ${m.sureRight}, sbagliati ${m.sureWrong}, da giudicare ${m.sureUnjudged}")
          FluidSectionFootnote(text = "Suggeriti ${m.suggested}, di cui del viaggio ${m.suggestedRight}")
          FluidSectionFootnote(text = "Foto del viaggio ${m.truth}, coperte ${m.covered}, mancanti ${m.missing}")
          FluidSectionFootnote(text = "Precisione ${AlbumsEvalReport.pct(m.precision)}, copertura ${AlbumsEvalReport.pct(m.coverage)}")
        }
      }
    }
    item { FluidSectionHeader(title = "Altro") }
    item {
      Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FluidTextField(
          value = newTitle,
          onValueChange = { newTitle = it.take(EvalTrip.MAX_TITLE) },
          placeholder = AlbumsEvalStrings.TITLE_FIELD,
          modifier = Modifier.fillMaxWidth(),
        )
        FluidButton(
          text = AlbumsEvalStrings.TRIP_SET_TITLE,
          style = FluidButtonStyle.Tinted,
          enabled = newTitle.isNotBlank(),
          fillWidth = true,
          onClick = {
            try {
              session.setTitle(id, newTitle)
              run = null
              newTitle = ""
            } catch (e: IllegalArgumentException) {
              notice = e.message
            }
          },
        )
        FluidButton(
          text = AlbumsEvalStrings.TRIP_REMOVE,
          style = FluidButtonStyle.Tinted,
          enabled = !labels.listClosed,
          fillWidth = true,
          onClick = {
            session.removeTrip(id)
            go(AlbumsPage.Home)
          },
        )
        if (labels.listClosed) FluidSectionFootnote(text = AlbumsEvalStrings.LIST_CLOSED)
      }
    }
  }
}

/** Prima fase del pozzo: i giorni del viaggio nella Libreria, senza guardare l'album; si segnano le foto che sono del viaggio. */
@Composable
internal fun AlbumsBlindPage(session: AlbumsEvalSession, env: AlbumsEvalEnvironment, id: String, onBack: () -> Unit) {
  val labels by session.labels.collectAsState()
  var cells by remember(id) { mutableStateOf<List<AlbumsEvalCell>?>(null) }
  LaunchedEffect(id) { cells = withContext(Dispatchers.Default) { session.blindCells(id) } }
  val inTrip = labels.inTripOf(id)
  FluidScreen(title = AlbumsEvalStrings.TRIP_BLIND_TITLE, subtitle = AlbumsEvalStrings.marked(inTrip.size), onBack = onBack) {
    item { FluidSectionFootnote(text = AlbumsEvalStrings.BLIND_HINT) }
    val all = cells
    when {
      all == null -> item { FluidSectionFootnote(text = AlbumsEvalStrings.RUNNING) }
      all.isEmpty() -> item { FluidSectionFootnote(text = AlbumsEvalStrings.NO_CELLS) }
      else -> for ((day, ofDay) in all.groupBy { it.day }.toSortedMap()) {
        val marked = ofDay.count { it.id in inTrip }
        item { FluidSectionHeader(title = day.toString(), detail = "$marked / ${ofDay.size}") }
        item {
          Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FluidButton(text = AlbumsEvalStrings.ALL_DAY, style = FluidButtonStyle.Tinted, onClick = { session.setInTrip(id, ofDay.map { it.id }, true) })
            FluidButton(text = AlbumsEvalStrings.NONE_OF_DAY, style = FluidButtonStyle.Tinted, onClick = { session.setInTrip(id, ofDay.map { it.id }, false) })
          }
        }
        albumsGrid(
          ofDay,
          env,
          verdictOf = { if (it.id in inTrip) Verdict.RIGHT else Verdict.NONE },
          onTap = { session.setInTrip(id, listOf(it.id), it.id !in inTrip) },
        )
      }
    }
  }
}

/**
 * Seconda fase: i sicuri (o i suggeriti) dell'album. Di norma solo quelli su cui l'utente non ha ancora detto niente (il resto e' gia' d'accordo); "Mostra tutti" li
 * mostra tutti, anche quelli gia' giudicati. Ogni tocco cambia il giudizio: da giudicare, del viaggio, non del viaggio.
 */
@Composable
internal fun AlbumsPendingPage(session: AlbumsEvalSession, env: AlbumsEvalEnvironment, id: String, suggested: Boolean, onBack: () -> Unit) {
  val labels by session.labels.collectAsState()
  val title = if (suggested) AlbumsEvalStrings.TRIP_SUGGESTED_TITLE else AlbumsEvalStrings.TRIP_SURE_TITLE
  var everything by remember(id, suggested) { mutableStateOf(false) }
  // Le foto da mostrare si fissano all'apertura (o quando si cambia vista): giudicandone una non sparisce di sotto al dito.
  var cells by remember(id, suggested, everything) { mutableStateOf<List<AlbumsEvalCell>?>(null) }
  LaunchedEffect(id, suggested, everything) {
    val ids = if (everything) session.listed(id, suggested) else session.pending(id).let { if (suggested) it.suggested else it.sure }
    cells = withContext(Dispatchers.Default) { session.cellsOf(ids) }
  }
  FluidScreen(title = title, subtitle = AlbumsEvalStrings.PENDING_HINT, onBack = onBack) {
    item {
      Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FluidButton(
          text = if (everything) AlbumsEvalStrings.SHOW_PENDING else AlbumsEvalStrings.SHOW_ALL,
          style = FluidButtonStyle.Tinted,
          onClick = { everything = !everything },
        )
      }
    }
    val shown = cells
    when {
      shown == null -> item { FluidSectionFootnote(text = AlbumsEvalStrings.RUNNING) }
      shown.isEmpty() -> item { FluidSectionFootnote(text = AlbumsEvalStrings.NOTHING_PENDING) }
      else -> {
        item {
          Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FluidButton(text = AlbumsEvalStrings.ALL_RIGHT, style = FluidButtonStyle.Tinted, onClick = { session.setInTrip(id, shown.map { it.id }, true) })
            FluidButton(text = AlbumsEvalStrings.ALL_WRONG, style = FluidButtonStyle.Tinted, onClick = { session.setNotInTrip(id, shown.map { it.id }) })
          }
        }
        albumsGrid(
          shown,
          env,
          verdictOf = { cell -> labels.let { if (cell.id in it.inTripOf(id)) Verdict.RIGHT else if (cell.id in it.notInTripOf(id)) Verdict.WRONG else Verdict.NONE } },
          onTap = { cell ->
            session.setVerdict(
              id,
              cell.id,
              when (session.verdictOf(id, cell.id)) {
                Verdict.NONE -> Verdict.RIGHT
                Verdict.RIGHT -> Verdict.WRONG
                Verdict.WRONG -> Verdict.NONE
              },
            )
          },
        )
      }
    }
  }
}

/** Terza fase: le foto dei giorni attorno che l'album non ha preso; si segnano quelle che sono del viaggio. */
@Composable
internal fun AlbumsMissingPage(session: AlbumsEvalSession, env: AlbumsEvalEnvironment, id: String, onBack: () -> Unit) {
  val labels by session.labels.collectAsState()
  var cells by remember(id) { mutableStateOf<List<AlbumsEvalCell>?>(null) }
  LaunchedEffect(id) { cells = withContext(Dispatchers.Default) { session.missingCells(id) } }
  val inTrip = labels.inTripOf(id)
  FluidScreen(title = AlbumsEvalStrings.TRIP_MISSING_TITLE, subtitle = AlbumsEvalStrings.marked(inTrip.size), onBack = onBack) {
    item { FluidSectionFootnote(text = if (session.runOf(id) == null) AlbumsEvalStrings.MISSING_NEEDS_RUN else AlbumsEvalStrings.MISSING_HINT) }
    val shown = cells
    when {
      shown == null -> item { FluidSectionFootnote(text = AlbumsEvalStrings.RUNNING) }
      shown.isEmpty() -> item { FluidSectionFootnote(text = AlbumsEvalStrings.NO_CELLS) }
      else -> albumsGrid(
        shown,
        env,
        verdictOf = { if (it.id in inTrip) Verdict.RIGHT else Verdict.NONE },
        onTap = { session.setInTrip(id, listOf(it.id), it.id !in inTrip) },
      )
    }
  }
}

/** La prova generale sui viaggi sintetici: un tocco, i numeri contro quelli attesi dalla JVM. */
@Composable
internal fun AlbumsRehearsalPage(env: AlbumsEvalEnvironment, store: AlbumsEvalStore, scope: CoroutineScope, onBack: () -> Unit) {
  val rehearsal = remember { AlbumsEvalRehearsal(env, store) }
  var result by remember { mutableStateOf<String?>(null) }
  var running by remember { mutableStateOf(false) }
  var notice by remember { mutableStateOf<String?>(null) }
  val albums = remember { rehearsal.albumsInImport() }
  FluidScreen(title = AlbumsEvalStrings.REHEARSAL_TITLE, subtitle = AlbumsEvalStrings.REHEARSAL_DETAIL, onBack = onBack) {
    item {
      Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FluidSectionFootnote(text = if (albums == 0) AlbumsEvalStrings.REHEARSAL_NO_FILE else "$albums album nel file portato dentro con adb.")
        FluidButton(
          text = if (running) AlbumsEvalStrings.RUNNING else AlbumsEvalStrings.REHEARSAL_RUN,
          enabled = albums > 0 && !running,
          fillWidth = true,
          onClick = {
            running = true
            notice = null
            scope.launch {
              try {
                result = withContext(Dispatchers.Default) { rehearsal.run() }?.text
              } catch (e: IllegalArgumentException) {
                notice = e.message
              } catch (e: Exception) {
                notice = AlbumsEvalStrings.RUN_FAILED + e.javaClass.simpleName
              } finally {
                running = false
              }
            }
          },
        )
        notice?.let { FluidSectionFootnote(text = it) }
      }
    }
    result?.let { text -> item { AlbumsMonoText(text) } }
  }
}

@Composable
internal fun AlbumsTextPage(page: AlbumsPage.Text, onBack: () -> Unit) {
  FluidScreen(title = page.title, subtitle = "Soli numeri: copia in docs/decisioni.md", onBack = onBack) {
    item { AlbumsMonoText(page.text) }
    item {
      Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
        FluidButton(text = AlbumsEvalStrings.BACK, style = FluidButtonStyle.Tinted, fillWidth = true, onClick = onBack)
      }
    }
  }
}

@Composable
private fun AlbumsMonoText(text: String) {
  Text(text = text, fontFamily = FontFamily.Monospace, softWrap = false, modifier = Modifier.horizontalScroll(rememberScrollState()).padding(4.dp))
}
