package dev.pampa.mycloudgallery.feature.library.grid

import androidx.compose.runtime.Immutable
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.library.contract.GridLayout
import dev.pampa.mycloudgallery.core.library.contract.RowKind
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlin.math.max
import kotlin.math.min

/** Le righe che contano per le miniature, in un istante: quelle sullo schermo e quelle che stanno per entrarci. */
@Immutable
internal data class ThumbWindowRows(
  val layout: GridLayout,
  val view: TimelineView,
  val visible: IntRange,
  val ahead: IntRange,
  val behind: IntRange,
)

/** Le richieste per il caricatore (`ThumbnailLoader.window`): prima le visibili, poi quelle in arrivo. */
internal class ThumbWindowPlan(val visible: List<ThumbRequest>, val ahead: List<ThumbRequest>, val visibleIds: List<MediaId>)

/**
 * Le richieste di miniature **guidate dalla finestra** (D5, §7): la griglia le fa una volta per cambio di riga, non a ogni
 * fotogramma, e le celle osservano soltanto. Prima le celle sullo schermo, poi quelle che stanno per entrare nella
 * direzione dello scorrimento (una schermata), poi un quarto di schermata alle spalle: tutto il resto in volo il
 * caricatore lo annulla.
 *
 * Le richieste si ricordano per posizione finche' layout e vista non cambiano: scorrendo di una riga se ne creano solo
 * quelle della riga nuova (un `MediaId` e una chiave per cella), non quelle di tutta la finestra.
 */
internal class GridThumbWindow(private val state: MediaGridState) {

  private var cachedLayout: GridLayout? = null
  private var cachedView: TimelineView? = null
  private val cache = HashMap<Int, ThumbRequest>()

  fun plan(rows: ThumbWindowRows): ThumbWindowPlan {
    if (rows.layout !== cachedLayout || rows.view !== cachedView) {
      cache.clear()
      cachedLayout = rows.layout
      cachedView = rows.view
    }
    val keep = HashSet<Int>()
    val visible = requests(rows, rows.visible, keep)
    val ahead = requests(rows, rows.ahead, keep) + requests(rows, rows.behind, keep)
    cache.keys.retainAll(keep)
    return ThumbWindowPlan(visible, ahead, visible.map { it.key.mediaId })
  }

  private fun requests(rows: ThumbWindowRows, range: IntRange, keep: MutableSet<Int>): List<ThumbRequest> {
    if (range.isEmpty()) return emptyList()
    val l = rows.layout
    val out = ArrayList<ThumbRequest>()
    for (row in max(0, range.first)..min(l.rowCount - 1, range.last)) {
      if (l.rowKind(row) != RowKind.CELLS) continue
      val first = l.firstItemIn(row)
      for (position in first until first + l.itemCountIn(row)) {
        if (!keep.add(position)) continue
        val request = cache[position] ?: request(rows, position)?.also { cache[position] = it } ?: continue
        out += request
      }
    }
    return out
  }

  /** La richiesta della cella [position], o null se adesso non c'e' una copia leggibile (la cella resta sul segnaposto). */
  private fun request(rows: ThumbWindowRows, position: Int): ThumbRequest? {
    val l = rows.layout
    val snapshot = rows.view.snapshot
    val index = rows.view.snapshotIndex(l.viewIndexAt(position))
    val ref = state.refAt(snapshot, index) ?: return null
    val id = snapshot.idAt(index)
    state.noteIndex(id, index)
    val bucket = GridGeometry.bucketFor(l.itemWidth(position), l.rowHeight(l.rowOfItem(position)))
    return ThumbRequest(state.thumbKeyAt(snapshot, index, id, bucket), ref)
  }

  companion object {
    /** Quanto della parte appena passata si tiene in finestra: chi inverte lo scorrimento ritrova le miniature. */
    private const val BehindShare = 0.25f

    /**
     * Le righe della finestra per la posizione corrente: [visible] sullo schermo (trasformazione del pinch inclusa: una
     * griglia rimpicciolita mostra piu' righe), [ahead] una schermata oltre il bordo verso cui si scorre, [behind] la
     * quarto di schermata appena passato (chi torna indietro ritrova le miniature). [direction] e' +1 verso il fondo (i piu' recenti), -1 verso l'alto.
     */
    fun rows(state: MediaGridState, layout: GridLayout, view: TimelineView, direction: Int): ThumbWindowRows {
      val v = state.viewport
      val contentTop = state.contentTop(layout)
      val t = state.transform(layout)
      val top = t.toGrid(0f, 0f).y
      val bottom = t.toGrid(0f, v.height.toFloat()).y
      val visible = GridGeometry.rowsBetween(layout, top, bottom, contentTop, v)
      val screen = (bottom - top).coerceAtLeast(1f)
      val over = screen * BehindShare
      val ahead = if (direction >= 0) {
        GridGeometry.rowsBetween(layout, bottom, bottom + screen, contentTop, v)
      } else {
        GridGeometry.rowsBetween(layout, top - screen, top, contentTop, v)
      }
      val behind = if (direction >= 0) {
        GridGeometry.rowsBetween(layout, top - over, top, contentTop, v)
      } else {
        GridGeometry.rowsBetween(layout, bottom, bottom + over, contentTop, v)
      }
      return ThumbWindowRows(layout, view, visible, ahead, behind)
    }
  }
}
