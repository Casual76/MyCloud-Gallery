package dev.pampa.mycloudgallery.debugtools.widget

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.widget.RemoteViews
import dev.pampa.mycloudgallery.R

// SOLO build debug (Fase 7, P9, D22, L65): dal contenuto del widget di prova ai `RemoteViews` della piattaforma (niente Glance, §6.4). Una cella
// per ogni elemento che `WidgetFeed` ha dato, con la descrizione per TalkBack; le celle senza elemento spariscono. Nessun id, nessun nome di file.

/** Una cella pronta da disegnare: la descrizione e, se si e' riusciti a caricarla, la miniatura. */
internal class FieldProbeRenderedCell(val description: String, val thumbnail: Bitmap?)

internal object FieldProbeWidgetViews {

  private val CELL_IDS: IntArray = intArrayOf(
    R.id.probe_widget_cell_0,
    R.id.probe_widget_cell_1,
    R.id.probe_widget_cell_2,
    R.id.probe_widget_cell_3,
  )

  /** Il widget con le celle di [cells] (al piu' [FieldProbeWidgetContent.CELLS]); con nessuna cella dice "Niente da mostrare". */
  fun build(context: Context, cells: List<FieldProbeRenderedCell>): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.field_probe_widget)
    val shown = cells.take(CELL_IDS.size)
    views.setViewVisibility(R.id.probe_widget_empty, if (shown.isEmpty()) View.VISIBLE else View.GONE)
    views.setViewVisibility(R.id.probe_widget_cells, if (shown.isEmpty()) View.GONE else View.VISIBLE)
    for ((index, viewId) in CELL_IDS.withIndex()) {
      val cell = shown.getOrNull(index)
      if (cell == null) {
        views.setViewVisibility(viewId, View.INVISIBLE)
        views.setContentDescription(viewId, null)
        continue
      }
      views.setViewVisibility(viewId, View.VISIBLE)
      views.setContentDescription(viewId, cell.description)
      if (cell.thumbnail != null) {
        views.setImageViewBitmap(viewId, cell.thumbnail)
      } else {
        // Niente miniatura (NAS spento, file illeggibile): una tessera grigia, la descrizione resta.
        views.setImageViewBitmap(viewId, null)
        views.setInt(viewId, "setBackgroundColor", PLACEHOLDER_COLOR)
      }
    }
    return views
  }

  private const val PLACEHOLDER_COLOR: Int = 0xFF55555A.toInt()
}
