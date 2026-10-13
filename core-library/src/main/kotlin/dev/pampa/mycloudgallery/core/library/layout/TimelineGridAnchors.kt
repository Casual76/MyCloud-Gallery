package dev.pampa.mycloudgallery.core.library.layout

import dev.pampa.mycloudgallery.core.library.contract.Anchor
import dev.pampa.mycloudgallery.core.library.contract.GridAnchors
import dev.pampa.mycloudgallery.core.library.contract.GridLayout
import dev.pampa.mycloudgallery.core.library.contract.RowKind
import dev.pampa.mycloudgallery.core.library.contract.ScrollTarget
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlin.math.roundToInt

/**
 * L'ancoraggio del pinch, del cambio di stile, del ridimensionamento e del ritorno dal viewer (§2.4): **l'elemento
 * sotto il dito resta sotto il dito**. Solo aritmetica sulle somme prefisse, O(log n), nessuna misura.
 *
 * Una posizione di lettura e' la stessa in tutti i layout della stessa vista (livelli, stili, larghezze): l'ancora
 * dice quale cella, dove nella cella (frazioni) e a che altezza dello schermo stava il dito; [place] trova lo
 * scorrimento che rimette quel punto della cella alla stessa altezza.
 *
 * Il risultato di [place] e' **canonico**: la riga e' quella che sta in cima allo schermo e lo scostamento e' fra 0 e
 * l'altezza della riga piu' lo spazio, come `LazyListState.requestScrollToItem` lo accetta senza correzioni. In cima al
 * contenuto lo scorrimento si ferma a 0 (l'elemento allora sta piu' in alto del dito: non c'e' contenuto sopra).
 */
object TimelineGridAnchors : GridAnchors {

  override fun anchorAt(l: GridLayout, row: Int, x: Float, yInRow: Float, screenY: Float): Anchor {
    if (l.itemCount == 0 || l.rowCount == 0) return Anchor(0, 0f, 0f, screenY)
    val r = row.coerceIn(0, l.rowCount - 1)
    if (l.rowKind(r) == RowKind.HEADER) {
      // Sopra un'intestazione: la prima cella della sezione, con il suo bordo alto sotto il dito.
      val next = (r + 1).coerceAtMost(l.rowCount - 1)
      val first = firstItem(l, next).coerceIn(0, l.itemCount - 1)
      return Anchor(first, fraction(x - l.itemLeft(first), l.itemWidth(first)), 0f, screenY)
    }
    val hit = l.itemAt(r, x).let { if (it >= 0) it else nearest(l, r, x) }
    val fx = fraction(x - l.itemLeft(hit), l.itemWidth(hit))
    val fy = fraction(yInRow, l.rowHeight(r))
    return Anchor(hit, fx, fy, screenY)
  }

  override fun place(l2: GridLayout, a: Anchor): ScrollTarget {
    if (l2.itemCount == 0 || l2.rowCount == 0) return ScrollTarget(0, 0)
    val row = l2.rowOfItem(a.position.coerceIn(0, l2.itemCount - 1))
    // In Double: a qualche milione di pixel un Float sbaglia di mezzo pixel.
    val y = (l2.rowTop(row).toDouble() + a.fy.toDouble() * l2.rowHeight(row) - a.screenY).roundToInt()
    return at(l2, y)
  }

  /**
   * Lo scorrimento che porta la cella [position] al centro di uno schermo alto [viewportPx] (il "porta in vista" del
   * ritorno dal viewer quando la cella era fuori schermo).
   */
  fun center(l: GridLayout, position: Int, viewportPx: Int): ScrollTarget {
    if (l.itemCount == 0 || l.rowCount == 0) return ScrollTarget(0, 0)
    val row = l.rowOfItem(position.coerceIn(0, l.itemCount - 1))
    return at(l, l.rowTop(row) + l.rowHeight(row) / 2 - viewportPx / 2)
  }

  /** Lo scorrimento con il bordo alto dello schermo alla coordinata [y] del contenuto (canonico, mai negativo). */
  fun at(l: GridLayout, y: Int): ScrollTarget {
    if (l.rowCount == 0) return ScrollTarget(0, 0)
    val clamped = y.coerceIn(0, maxOf(0, l.totalHeight - 1))
    val row = l.rowAtOffset(clamped)
    return ScrollTarget(row, clamped - l.rowTop(row))
  }

  /**
   * La stessa ancora in un'altra vista o in un altro indice (filtro cambiato, nuovo indice dal catalogo, finestra
   * ridimensionata, E-P18): si passa per l'identita' dell'elemento. Se l'elemento non c'e' piu' resta la posizione,
   * limitata alla nuova vista.
   */
  fun retarget(a: Anchor, from: GridLayout, fromView: TimelineView, to: GridLayout, toView: TimelineView): Anchor {
    if (from.itemCount == 0 || to.itemCount == 0) return a.copy(position = 0)
    val id = idAt(from, fromView, a.position.coerceIn(0, from.itemCount - 1))
    val viewIndex = toView.viewIndexOf(id)
    val position = if (viewIndex >= 0) to.positionOfViewIndex(viewIndex) else a.position.coerceIn(0, to.itemCount - 1)
    return a.copy(position = position)
  }

  /** L'identita' dell'elemento in posizione [position] del layout [l] costruito su [view]. */
  fun idAt(l: GridLayout, view: TimelineView, position: Int): MediaId =
    view.snapshot.idAt(view.snapshotIndex(l.viewIndexAt(position)))

  private fun firstItem(l: GridLayout, row: Int): Int =
    if (l is TimelineGridLayout) l.firstItemOf(row) else l.itemsOf(row).let { if (it.isEmpty()) 0 else it.first }

  /** La cella piu' vicina a [x] nella riga [row] (il dito in uno spazio fra due celle o oltre l'ultima). */
  private fun nearest(l: GridLayout, row: Int, x: Float): Int {
    val first = firstItem(l, row)
    val count = if (l is TimelineGridLayout) l.itemCountOf(row) else l.itemsOf(row).count()
    var best = first
    var bestDistance = Float.MAX_VALUE
    for (p in first until first + count) {
      val left = l.itemLeft(p).toFloat()
      val right = left + l.itemWidth(p)
      val d = if (x < left) left - x else if (x >= right) x - right else 0f
      if (d < bestDistance) {
        bestDistance = d
        best = p
      }
    }
    return best
  }

  private fun fraction(offset: Float, size: Int): Float = if (size <= 0) 0f else (offset / size).coerceIn(0f, 1f)
}
