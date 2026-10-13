package dev.pampa.mycloudgallery.feature.library.grid

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.library.contract.GridLayout
import dev.pampa.mycloudgallery.core.library.contract.RowKind
import dev.pampa.mycloudgallery.core.library.layout.TimelineGridLayout
import kotlin.math.max

// La geometria della griglia, tutta aritmetica sul layout precalcolato (D3): dove sta una riga sullo schermo, quale cella
// e' sotto il dito, come si legge la trasformazione del pinch. Niente misure, niente Compose: si prova su JVM.

/**
 * Lo spazio della griglia sullo schermo, in pixel: la sua altezza e i margini in alto (la barra di vetro) e in basso (la
 * pillola e i segmenti), sotto cui il contenuto scorre rifratto.
 */
@Immutable
data class GridViewport(
  val width: Int = 0,
  val height: Int = 0,
  val padTop: Int = 0,
  val padBottom: Int = 0,
) {
  /** L'altezza dell'area in cui le celle si vedono intere (fra la barra e la pillola). */
  val contentHeight: Int get() = max(0, height - padTop - padBottom)
}

/** La posizione della lista, letta da `LazyListState`: l'elemento in cima all'area dei contenuti e di quanto e' salito. */
@Immutable
data class GridScroll(val firstIndex: Int, val firstOffset: Int)

/**
 * La trasformazione del pinch applicata alla lista: scala [scale] attorno all'origine ([originX], [originY], pixel
 * della griglia: il punto fra le dita, o il centro per i segmenti). A riposo e' l'identita'.
 */
@Immutable
data class GridTransform(
  val originX: Float = 0f,
  val originY: Float = 0f,
  val scale: Float = 1f,
) {
  val isIdentity: Boolean get() = scale == 1f

  /** Dove finisce sullo schermo il punto [x], [y] della griglia non trasformata. */
  fun toScreen(x: Float, y: Float): Offset = Offset(originX + scale * (x - originX), originY + scale * (y - originY))

  /** Il punto della griglia non trasformata che sta sotto il punto [x], [y] dello schermo. */
  fun toGrid(x: Float, y: Float): Offset {
    val s = if (scale == 0f) 1f else scale
    return Offset(originX + (x - originX) / s, originY + (y - originY) / s)
  }

  companion object {
    val Identity: GridTransform = GridTransform()
  }
}

internal object GridGeometry {

  /** La coordinata del contenuto (pixel del layout) che sta al bordo alto dell'area dei contenuti. */
  fun contentTop(layout: GridLayout, scroll: GridScroll): Int {
    val index = scroll.firstIndex
    return when {
      layout.rowCount == 0 -> scroll.firstOffset
      index < layout.rowCount -> layout.rowTop(index) + scroll.firstOffset
      // Il piede (riepilogo e stato globale) e' l'elemento dopo l'ultima riga.
      else -> layout.totalHeight + layout.spec.gapPx + scroll.firstOffset
    }
  }

  /** La coordinata del contenuto alla [screenY] della griglia (non trasformata). */
  fun contentY(screenY: Float, contentTop: Int, viewport: GridViewport): Float = contentTop + (screenY - viewport.padTop)

  /** L'ordinata sulla griglia (non trasformata) del bordo alto della riga [row]. */
  fun rowScreenTop(layout: GridLayout, row: Int, contentTop: Int, viewport: GridViewport): Int =
    viewport.padTop + layout.rowTop(row) - contentTop

  /** La riga che contiene la coordinata di contenuto [y], o -1 fuori dal layout o in uno spazio fra due righe. */
  fun rowAt(layout: GridLayout, y: Float): Int {
    if (layout.rowCount == 0 || y < 0f || y >= layout.totalHeight) return -1
    val row = layout.rowAtOffset(y.toInt())
    return if (y < layout.rowTop(row) + layout.rowHeight(row)) row else -1
  }

  /** La cella (posizione) sotto il punto [x], [screenY] della griglia non trasformata, o -1. */
  fun positionAt(layout: GridLayout, x: Float, screenY: Float, contentTop: Int, viewport: GridViewport): Int {
    val row = rowAt(layout, contentY(screenY, contentTop, viewport))
    if (row < 0 || layout.rowKind(row) != RowKind.CELLS) return -1
    return layout.itemAt(row, x)
  }

  /** Le righe che toccano la fascia [fromY] .. [toY] della griglia (non trasformata). Vuoto se non ce ne sono. */
  fun rowsBetween(layout: GridLayout, fromY: Float, toY: Float, contentTop: Int, viewport: GridViewport): IntRange {
    if (layout.rowCount == 0 || toY <= fromY) return IntRange.EMPTY
    val top = contentY(fromY, contentTop, viewport)
    val bottom = contentY(toY, contentTop, viewport)
    if (bottom <= 0f || top >= layout.totalHeight) return IntRange.EMPTY
    val first = layout.rowAtOffset(top.coerceAtLeast(0f).toInt())
    val last = layout.rowAtOffset((bottom.coerceAtMost(layout.totalHeight.toFloat()) - 1f).coerceAtLeast(0f).toInt())
    return first..last
  }

  /** Lo scorrimento massimo (pixel del contenuto) perche' il piede arrivi in fondo all'area dei contenuti. */
  fun maxContentTop(layout: GridLayout, footerHeight: Int, viewport: GridViewport): Int =
    max(0, layout.totalHeight + layout.spec.gapPx + footerHeight - viewport.contentHeight)

  /**
   * La taglia di miniatura per una cella larga [width] e alta [height]: la piu' piccola che ne copre il lato lungo, cosi'
   * la stessa bitmap serve a livelli vicini e all'espansione nel viewer.
   */
  fun bucketFor(width: Int, height: Int): ThumbBucket = ThumbBucket.covering(max(width, height))
}

/** La prima cella della riga [row] senza allocare (`TimelineGridLayout.firstItemOf` nel percorso caldo). */
internal fun GridLayout.firstItemIn(row: Int): Int =
  if (this is TimelineGridLayout) firstItemOf(row) else itemsOf(row).let { if (it.isEmpty()) 0 else it.first }

/** Quante celle ha la riga [row] (0 per un'intestazione), senza allocare. */
internal fun GridLayout.itemCountIn(row: Int): Int =
  if (this is TimelineGridLayout) itemCountOf(row) else itemsOf(row).let { if (it.isEmpty()) 0 else it.last - it.first + 1 }
