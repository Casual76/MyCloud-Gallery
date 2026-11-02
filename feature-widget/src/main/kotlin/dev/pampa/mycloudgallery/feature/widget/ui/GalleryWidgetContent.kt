package dev.pampa.mycloudgallery.feature.widget.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import dev.antigravity.fluidengine.widget.EngineWidgetHeader
import dev.antigravity.fluidengine.widget.EngineWidgetIconTile
import dev.antigravity.fluidengine.widget.EngineWidgetLayout
import dev.antigravity.fluidengine.widget.EngineWidgetMessage
import dev.antigravity.fluidengine.widget.EngineWidgetMetrics
import dev.antigravity.fluidengine.widget.EngineWidgetPalette
import dev.antigravity.fluidengine.widget.EngineWidgetShape
import dev.antigravity.fluidengine.widget.EngineWidgetSurface
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSize
import dev.pampa.mycloudgallery.feature.widget.R
import dev.pampa.mycloudgallery.feature.widget.contract.WidgetUiCell
import dev.pampa.mycloudgallery.feature.widget.contract.WidgetUiState
import dev.pampa.mycloudgallery.feature.widget.frame.WidgetFrame

// IL DISEGNO DEL WIDGET (Fase 10, P3, docs/fase10-progetto.md D3, D4, D8, D10, §1.5, §2.2). Glance sopra il kit di `engine-widget`: **nessun colore, dimensione di testo o
// raggio scritto qui** (regola 1 dell'engine; `NoHandWrittenStyleTest`): colori dalla palette del kit (`engineWidgetPalette`, che segue tema e accento dell'app), testo e
// angoli dai componenti del kit (`EngineWidgetHeader`, `EngineWidgetMessage`, `EngineWidgetIconTile`, `EngineWidgetShape`), distanze da `EngineWidgetMetrics`. Il disegno non
// sceglie niente: cosa mostrare lo decide il piano, e questo e' solo il suo fotogramma (`WidgetFrame`).
//
// **Foto intere, mai ritagli** (promessa 4): `ContentScale.Fit` dentro una cella che ha il suo fondo (la scheda del kit). Un video ha il suo segno (E-W17). Ogni cella ha il suo
// tocco (D9) e la sua descrizione (solo tipo e data); la copertina del ricordo e il suo titolo portano allo stesso posto, la pagina del ricordo; il resto della superficie apre
// l'app. Con il blocco dell'app (`Locked`) non c'e' titolo (Q3 (c)).

/** Le azioni di tocco del widget: una per cella e quella di "Apri MyCloud Gallery". Le costruisce `tap/` (D9); qui si usano soltanto. */
internal interface WidgetActions {
  fun cell(index: Int): Action

  val openApp: Action
}

/** Il widget intero: la superficie del kit con dentro un messaggio, o (con il titolo, per un ricordo) le celle delle foto. */
@Composable
internal fun GalleryWidgetContent(frame: WidgetFrame, palette: EngineWidgetPalette, layout: EngineWidgetLayout, actions: WidgetActions) {
  EngineWidgetSurface(palette = palette, layout = layout, onClick = actions.openApp) {
    when (val state = frame.state) {
      is WidgetUiState.Message -> MessageBody(frame.message.orEmpty(), state.size, palette, layout)
      is WidgetUiState.Memory -> {
        MemoryHeader(frame.title.orEmpty(), palette, layout, actions.cell(0))
        Spacer(GlanceModifier.height(EngineWidgetMetrics.Gap / 2))
        PhotoGrid(frame, palette, layout, actions, GlanceModifier.fillMaxWidth().defaultWeight())
      }
      is WidgetUiState.Photos, is WidgetUiState.Locked -> {
        // Senza titolo (foto a caso, o blocco dell'app: nessun titolo, Q3 (c)).
        PhotoGrid(frame, palette, layout, actions, GlanceModifier.fillMaxWidth().defaultWeight())
      }
    }
  }
}

/**
 * "Apri MyCloud Gallery" / "Configura MyCloud Gallery": una riga, non un errore (E-W1), al centro della superficie. Nella misura piccola **senza il glifo**: il testo ha tutta la
 * larghezza e a testo grande (1,3x) "MyCloud" non si taglia (il kit lo manda a capo su due righe, ma una parola sola non la spezza).
 */
@Composable
private fun MessageBody(message: String, size: WidgetSize, palette: EngineWidgetPalette, layout: EngineWidgetLayout) {
  Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    val glyph = if (size == WidgetSize.SMALL) null else R.drawable.ic_widget_gallery
    EngineWidgetMessage(message = message, palette = palette, layout = layout, icon = glyph, tone = palette.primaryTone)
  }
}

/** Il titolo del ricordo (D4): il tocco porta alla pagina del ricordo, come la copertina. La descrizione dice il titolo intero anche se la riga lo accorcia. */
@Composable
private fun MemoryHeader(title: String, palette: EngineWidgetPalette, layout: EngineWidgetLayout, action: Action) {
  Box(modifier = GlanceModifier.fillMaxWidth().clickable(action).semantics { contentDescription = title }) {
    EngineWidgetHeader(title = title, palette = palette, layout = layout)
  }
}

/** Le celle, nella disposizione di `WidgetGridPlan` per la misura dello stato. */
@Composable
private fun PhotoGrid(frame: WidgetFrame, palette: EngineWidgetPalette, layout: EngineWidgetLayout, actions: WidgetActions, modifier: GlanceModifier) {
  val cells = uiCellsOf(frame.state)
  val groups = WidgetGridPlan.groups(frame.state.size, cells.size)
  val gap = EngineWidgetMetrics.Gap / 2
  Column(modifier = modifier) {
    groups.forEachIndexed { groupIndex, rows ->
      if (groupIndex > 0) Spacer(GlanceModifier.height(gap))
      Column(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
        rows.forEachIndexed { rowIndex, indexes ->
          if (rowIndex > 0) Spacer(GlanceModifier.height(gap))
          Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
            indexes.forEachIndexed { position, index ->
              if (position > 0) Spacer(GlanceModifier.width(gap))
              PhotoCell(frame, cells[index], palette, actions.cell(index), GlanceModifier.fillMaxHeight().defaultWeight())
            }
            // Una riga corta resta corta: le celle che mancano sono spazio, non segnaposto.
            repeat(WidgetGridPlan.paddingOf(frame.state.size, groupIndex, indexes.size)) {
              Spacer(GlanceModifier.width(gap))
              Spacer(GlanceModifier.fillMaxHeight().defaultWeight())
            }
          }
        }
      }
    }
  }
}

/** Una cella: il fondo della scheda del kit, la foto intera, il segno del video, il tocco e la descrizione. */
@Composable
private fun PhotoCell(frame: WidgetFrame, ui: WidgetUiCell, palette: EngineWidgetPalette, action: Action, modifier: GlanceModifier) {
  val cell = frame.cells[ui.index]
  Box(
    modifier = modifier.background(palette.card).cornerRadius(EngineWidgetShape.Tile).clickable(action),
    contentAlignment = Alignment.Center,
  ) {
    val bitmap = cell.bitmap
    if (bitmap != null) {
      Image(
        provider = ImageProvider(bitmap),
        contentDescription = cell.description,
        modifier = GlanceModifier.fillMaxSize(),
        contentScale = ContentScale.Fit,
      )
    }
    if (ui.kind == MediaKind.VIDEO) {
      Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        EngineWidgetIconTile(icon = R.drawable.ic_widget_play, tone = palette.primaryTone, compact = true)
      }
    }
  }
}

/** Le celle dello stato in ordine di indice (la copertina di un ricordo e' la 0). */
internal fun uiCellsOf(state: WidgetUiState): List<WidgetUiCell> = when (state) {
  is WidgetUiState.Memory -> listOf(state.cover) + state.cells
  is WidgetUiState.Photos -> state.cells
  is WidgetUiState.Locked -> state.cells
  is WidgetUiState.Message -> emptyList()
}
