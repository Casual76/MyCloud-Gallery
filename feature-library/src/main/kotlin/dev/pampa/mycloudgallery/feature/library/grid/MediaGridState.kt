package dev.pampa.mycloudgallery.feature.library.grid

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.imaging.contract.PlaceholderCache
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.library.contract.GridLayout
import dev.pampa.mycloudgallery.core.library.contract.GridLayoutEngine
import dev.pampa.mycloudgallery.core.library.contract.GridLevel
import dev.pampa.mycloudgallery.core.library.contract.GridStyle
import dev.pampa.mycloudgallery.core.library.contract.PixelRect
import dev.pampa.mycloudgallery.core.library.contract.SelectionModel
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.contract.ViewerAnchor
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.places.contract.PlaceNames

/**
 * Tutto cio' che [MediaGrid] disegna, e solo quello: la vista (con il suo filtro), come si caricano le miniature, come
 * si impagina e come si chiamano i luoghi. E' il contratto fra la griglia e chi la usa: la Libreria, le Raccolte
 * filtrate, i Nascosti e gli Eliminati di recente costruiscono ciascuno il proprio [MediaGridState] e passano la
 * stessa griglia (docs/fase3-progetto.md §1.2, `MediaGrid` "riusata da tutte le liste").
 *
 * **Stub di P0.** Tiene il livello, lo stile, la selezione e la geometria delle celle disegnate (per
 * [ViewerAnchor]); il pinch, lo scrubber e la finestra delle miniature sono di P7.
 */
@Stable
class MediaGridState(
  val view: TimelineView,
  val loader: ThumbnailLoader,
  val placeholders: PlaceholderCache,
  val placeNames: PlaceNames,
  val engine: GridLayoutEngine,
  /** Dove stanno i byte di un media: la miniatura la chiede al caricatore con questo riferimento. */
  val refOf: (MediaId) -> MediaRef,
  level: GridLevel = GridLevel.DAYS,
  style: GridStyle = GridStyle.SQUARE,
) {

  var level: GridLevel by mutableStateOf(level)
  var style: GridStyle by mutableStateOf(style)

  /** La selezione a trascinamento. */
  val selection: SelectionModel = SelectionModel()

  /** Lo scorrimento. P7 lo salva come elemento ancorato + scostamento (E-P18), mai come indice. */
  val listState: LazyListState = LazyListState()

  /** L'ultimo layout calcolato: la geometria per il "porta in vista" e per l'ancoraggio. */
  var layout: GridLayout? = null
    internal set

  internal val cellBounds: MutableMap<MediaId, PixelRect> = HashMap()

  /** Vero dopo il primo trascinamento: da li' in poi la griglia non si riporta piu' in fondo da sola. */
  internal var userScrolled: Boolean = false

  /**
   * "Torna a oggi" (D1): toccare di nuovo "Libreria" riporta in fondo, sui piu' recenti. Con `newestAtBottom` il piu'
   * recente e' l'ultima riga; il piede (riepilogo e stato globale) e' l'elemento dopo.
   */
  suspend fun scrollToNewest() {
    val rows = layout?.rowCount ?: return
    listState.animateScrollToItem(rows)
  }

  /** La chiave della miniatura di [id], per partire dalla stessa bitmap della cella (`ThumbnailLoader.peek`). */
  fun thumbKey(id: MediaId, bucket: ThumbBucket = ThumbBucket.L): ThumbKey = ThumbKey(id, versionKey = 1, bucket = bucket)

  /**
   * L'ancora per il viewer: i bordi delle celle disegnate e il "porta in vista" per il ritorno. Stub: se la cella non
   * e' sullo schermo scorre fino alla sua riga (P7 la porta al centro e aspetta l'assestamento).
   */
  val viewerAnchor: ViewerAnchor = object : ViewerAnchor {
    override fun boundsOf(id: MediaId): PixelRect? = cellBounds[id]

    override suspend fun reveal(id: MediaId) {
      val current = layout ?: return
      val viewIndex = view.viewIndexOf(id)
      if (viewIndex < 0) return
      val row = current.rowOfItem(current.positionOfViewIndex(viewIndex))
      listState.scrollToItem(row)
    }

    override fun thumbKey(id: MediaId): ThumbKey = this@MediaGridState.thumbKey(id)
  }
}
