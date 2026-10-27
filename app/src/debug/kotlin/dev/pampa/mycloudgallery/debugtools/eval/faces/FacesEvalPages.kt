package dev.pampa.mycloudgallery.debugtools.eval.faces

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.mycloudgallery.core.model.FaceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// SOLO debug (Fase 8, P11): le pagine dello strumento che mostrano gruppi, volti o numeri. I volti sono sempre ritagli locali ([FacesEvalEnvironment.crop]).

private fun title(kind: EvalSessionKind): String = if (kind == EvalSessionKind.CALIBRATION) FacesEvalStrings.SESSION_1 else FacesEvalStrings.SESSION_2

/** L'elenco dei gruppi scelti per una sessione, con lo stato di ognuno; da qui si guarda un gruppo e si chiude la sessione. */
@Composable
internal fun FacesGroupsPage(session: FacesEvalSession, kind: EvalSessionKind, go: (FacesPage) -> Unit) {
  val labels by session.labels.collectAsState()
  val s = labels.session(kind)
  var confirmClose by remember(kind) { mutableStateOf(false) }
  var confirmRestart by remember(kind) { mutableStateOf(false) }
  if (s == null) {
    LaunchedEffect(Unit) { go(FacesPage.Home) }
    return
  }
  FluidScreen(
    title = title(kind),
    subtitle = FacesEvalStrings.sessionProgress(s.reviewedGroups.size, s.groups.size, s.closed),
    onBack = { go(FacesPage.Home) },
  ) {
    item { FluidSectionFootnote(text = if (s.closed) FacesEvalStrings.CLOSED else FacesEvalStrings.GROUPS_HINT) }
    if (s.groups.isEmpty()) {
      item { FluidSectionFootnote(text = FacesEvalStrings.NO_GROUPS) }
    } else {
      item {
        FluidListGroup {
          s.groups.forEachIndexed { index, g ->
            if (index > 0) FluidListDivider()
            FluidListRow(
              title = FacesEvalStrings.groupRow(index + 1, g.size),
              subtitle = FacesEvalStrings.groupStatus(g.reviewed, g.mixed, g.wrong.size, g.sameAs.size),
              onClick = { go(FacesPage.Group(kind, g.person)) },
            )
          }
        }
      }
    }
    item { FluidSectionHeader(title = FacesEvalStrings.SECTION_NUMBERS) }
    item {
      Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FluidButton(
          text = FacesEvalStrings.SHOW_NUMBERS,
          style = FluidButtonStyle.Tinted,
          fillWidth = true,
          onClick = { session.saveReport(kind)?.let { go(FacesPage.Text(it.first, it.second)) } },
        )
        if (!s.closed) {
          FluidButton(
            text = if (confirmClose) FacesEvalStrings.CLOSE_CONFIRM else FacesEvalStrings.CLOSE_TITLE,
            style = FluidButtonStyle.Tinted,
            fillWidth = true,
            onClick = {
              if (!confirmClose) {
                confirmClose = true
              } else {
                session.close(kind)
                confirmClose = false
              }
            },
          )
        }
        FluidButton(
          text = if (confirmRestart) FacesEvalStrings.RESTART_CONFIRM else FacesEvalStrings.RESTART_TITLE,
          style = FluidButtonStyle.Tinted,
          fillWidth = true,
          onClick = {
            if (!confirmRestart) {
              confirmRestart = true
            } else {
              session.restart(kind)
              go(FacesPage.Home)
            }
          },
        )
      }
    }
  }
}

/** Un gruppo: i suoi volti (fino a 60, piu' i 10 meno simili), da toccare se non sono di quella persona; "Gruppo misto"; "e' la stessa di ...". */
@Composable
internal fun FacesGroupPage(session: FacesEvalSession, env: FacesEvalEnvironment, kind: EvalSessionKind, person: String, go: (FacesPage) -> Unit) {
  val labels by session.labels.collectAsState()
  val s = labels.session(kind)
  val g = s?.group(person)
  // Le foto da mostrare si fissano all'apertura: toccandone una non cambiano di sotto al dito.
  var choice by remember(kind, person) { mutableStateOf<FacesEvalSampling.FaceChoice?>(null) }
  LaunchedEffect(kind, person) { choice = withContext(Dispatchers.Default) { session.choice(kind, person) } }
  if (s == null || g == null) {
    LaunchedEffect(Unit) { go(FacesPage.Home) }
    return
  }
  val index = s.groups.indexOfFirst { it.person == person } + 1
  val open = !s.closed
  FluidScreen(title = FacesEvalStrings.groupTitle(index, s.groups.size), subtitle = FacesEvalStrings.marked(g.wrong.size), onBack = { go(FacesPage.Groups(kind)) }) {
    val c = choice
    when {
      c == null -> item { FluidSectionFootnote(text = FacesEvalStrings.LOADING) }
      c.all.isEmpty() -> item { FluidSectionFootnote(text = FacesEvalStrings.GROUP_GONE) }
      else -> {
        val total = c.all.size
        item { FluidSectionFootnote(text = FacesEvalStrings.GROUP_HINT) }
        item { FluidSectionHeader(title = FacesEvalStrings.ALL_FACES, detail = FacesEvalStrings.groupSubtitle(g.size, total, g.wrong.size)) }
        val cells = c.all.mapIndexed { i, f -> FaceCell(f.locator, i + 1, total) }
        facesGrid(
          cells.take(c.main.size),
          env,
          isWrong = { g.wrong.any { w -> w.matches(it.locator) } },
          onToggle = { session.toggleWrong(kind, person, it.locator, total) },
          enabled = open,
        )
        if (c.leastSimilar.isNotEmpty()) {
          item { FluidSectionHeader(title = FacesEvalStrings.LEAST_SIMILAR) }
          facesGrid(
            cells.drop(c.main.size),
            env,
            isWrong = { g.wrong.any { w -> w.matches(it.locator) } },
            onToggle = { session.toggleWrong(kind, person, it.locator, total) },
            enabled = open,
          )
        }
        item {
          Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FluidButton(
              text = FacesEvalStrings.ALL_RIGHT,
              enabled = open && !g.mixed && g.wrong.isEmpty(),
              fillWidth = true,
              onClick = {
                session.setReviewed(kind, person, total)
                val next = session.nextToReview(kind, person)
                go(if (next != null) FacesPage.Group(kind, next) else FacesPage.Groups(kind))
              },
            )
            if (g.reviewed && !g.mixed && g.wrong.isEmpty()) {
              FluidButton(text = FacesEvalStrings.UNREVIEW, style = FluidButtonStyle.Tinted, enabled = open, fillWidth = true, onClick = { session.setReviewed(kind, person, total, reviewed = false) })
            }
            FluidButton(
              text = if (g.mixed) FacesEvalStrings.MIXED_OFF else FacesEvalStrings.MIXED,
              style = FluidButtonStyle.Tinted,
              enabled = open,
              fillWidth = true,
              onClick = { session.setMixed(kind, person, !g.mixed, total) },
            )
            FluidSectionFootnote(text = FacesEvalStrings.MIXED_HINT)
            FluidButton(text = FacesEvalStrings.SAME_TITLE, style = FluidButtonStyle.Tinted, enabled = open, fillWidth = true, onClick = { go(FacesPage.Same(kind, person)) })
            val next = session.nextToReview(kind, person)
            FluidButton(
              text = if (next != null) FacesEvalStrings.NEXT_GROUP else FacesEvalStrings.ALL_DONE,
              style = FluidButtonStyle.Tinted,
              fillWidth = true,
              onClick = { go(if (next != null) FacesPage.Group(kind, next) else FacesPage.Groups(kind)) },
            )
          }
        }
      }
    }
  }
}

/** "E' la stessa persona di ...": gli altri gruppi che Persone mostra, con un volto di copertina; si toccano quelli che sono la stessa persona del gruppo che si guarda. */
@Composable
internal fun FacesSamePage(session: FacesEvalSession, env: FacesEvalEnvironment, kind: EvalSessionKind, person: String, go: (FacesPage) -> Unit) {
  val labels by session.labels.collectAsState()
  val linked = labels.session(kind)?.group(person)?.sameAs.orEmpty()
  var groups by remember(kind, person) { mutableStateOf<List<EvalGroupInfo>?>(null) }
  LaunchedEffect(kind, person) { groups = withContext(Dispatchers.Default) { session.allGroups().filter { it.id.value != person }.sortedByDescending { it.faces } } }
  FluidScreen(title = FacesEvalStrings.SAME_TITLE, subtitle = FacesEvalStrings.marked(linked.size), onBack = { go(FacesPage.Group(kind, person)) }) {
    item { FluidSectionFootnote(text = FacesEvalStrings.SAME_HINT) }
    val all = groups
    when {
      all == null -> item { FluidSectionFootnote(text = FacesEvalStrings.LOADING) }
      all.isEmpty() -> item { FluidSectionFootnote(text = FacesEvalStrings.SAME_NONE) }
      else -> item {
        FluidListGroup {
          all.forEachIndexed { index, info ->
            if (index > 0) FluidListDivider()
            val picked = info.id.value in linked
            FluidListRow(
              title = "Gruppo di ${info.faces} volti",
              subtitle = "${info.photos} foto" + (if (info.named) " · con nome" else "") + if (picked) " · ✓ stessa persona" else "",
              leading = { Cover(env, info.cover) },
              selected = picked,
              onClick = { session.toggleSame(kind, person, info.id.value) },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun Cover(env: FacesEvalEnvironment, cover: FaceLocator?) {
  val bitmap by produceState<ImageBitmap?>(initialValue = null, cover) { value = cover?.let { env.crop(it) } }
  val image = bitmap
  if (image != null) {
    Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp))
  } else {
    Text(text = "·", style = MaterialTheme.typography.titleLarge, modifier = Modifier.size(48.dp))
  }
}

/** Un resoconto di soli numeri, da copiare in `docs/decisioni.md`. */
@Composable
internal fun FacesTextPage(page: FacesPage.Text, onBack: () -> Unit) {
  FluidScreen(title = page.title, subtitle = "Soli numeri: copia in docs/decisioni.md", onBack = onBack) {
    item { FacesMonoText(page.text) }
    item {
      Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
        FluidButton(text = FacesEvalStrings.BACK, style = FluidButtonStyle.Tinted, fillWidth = true, onClick = onBack)
      }
    }
  }
}

@Composable
internal fun FacesMonoText(text: String) {
  // A capo come viene: sul telefono una riga di resoconto non sta in 40 caratteri, e una riga tagliata fa leggere male un numero.
  Text(text = text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(4.dp))
}
