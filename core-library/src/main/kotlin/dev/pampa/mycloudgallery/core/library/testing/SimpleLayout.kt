package dev.pampa.mycloudgallery.core.library.testing

import dev.pampa.mycloudgallery.core.library.contract.Anchor
import dev.pampa.mycloudgallery.core.library.contract.GridAnchors
import dev.pampa.mycloudgallery.core.library.contract.GridLayout
import dev.pampa.mycloudgallery.core.library.contract.GridLayoutEngine
import dev.pampa.mycloudgallery.core.library.contract.GridLevel
import dev.pampa.mycloudgallery.core.library.contract.GridSpec
import dev.pampa.mycloudgallery.core.library.contract.GridStyle
import dev.pampa.mycloudgallery.core.library.contract.RowKind
import dev.pampa.mycloudgallery.core.library.contract.ScrollTarget
import dev.pampa.mycloudgallery.core.library.contract.ScrubberModel
import dev.pampa.mycloudgallery.core.library.contract.SectionInfo
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import java.time.YearMonth
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Il motore di layout, l'ancoraggio e lo scrubber di riferimento (D23): servono a far girare la griglia di P7 sui fake
// prima che P5 consegni i motori veri e ottimizzati. Sono corretti ma non misurati: niente budget, niente ottimizzazioni.
// P5 li sostituisce (stesse interfacce); questi restano come oracolo per i test.

/**
 * Un layout a righe calcolato in un passaggio: sezioni per anno, mese o giorno (per `ALL` mesi senza intestazione), righe
 * di celle quadrate o giustificate, somme prefisse per la posizione verticale.
 */
class SimpleGridLayoutEngine(
  /** L'altezza di un'intestazione di sezione per livello, in dp (prima della densita'). */
  private val headerDp: (GridLevel) -> Float = { level ->
    when (level) {
      GridLevel.YEARS -> 48f
      GridLevel.MONTHS -> 60f
      GridLevel.DAYS -> 56f
      GridLevel.ALL -> 0f
    }
  },
) : GridLayoutEngine {

  override fun layout(v: TimelineView, s: GridSpec): GridLayout {
    val n = v.size
    val snapshot = v.snapshot
    fun viewIndexAt(p: Int) = if (s.newestAtBottom) n - 1 - p else p
    fun dayAt(p: Int) = snapshot.localDayAt(v.snapshotIndex(viewIndexAt(p)))
    fun keyOf(day: Int) = when (s.level) {
      GridLevel.YEARS -> day / 10_000
      GridLevel.MONTHS, GridLevel.ALL -> day / 100
      GridLevel.DAYS -> day
    }

    // 1. Le sezioni: posizioni contigue con la stessa chiave.
    val sectionStart = ArrayList<Int>()
    var lastKey = Int.MIN_VALUE
    for (p in 0 until n) {
      val key = keyOf(dayAt(p))
      if (p == 0 || key != lastKey) sectionStart += p
      lastKey = key
    }
    sectionStart += n

    // 2. Righe e posizioni orizzontali.
    val top = ArrayList<Int>()
    val height = ArrayList<Int>()
    val kind = ArrayList<RowKind>()
    val firstItem = ArrayList<Int>()
    val itemCountInRow = ArrayList<Int>()
    val rowSection = ArrayList<Int>()
    val left = IntArray(n)
    val width = IntArray(n)
    val rowOf = IntArray(n)
    val sections = ArrayList<SectionInfo>()
    var y = 0
    val headerPx = (headerDp(s.level) * s.densityDp).roundToInt()
    val cell = s.cellPx

    fun addRow(rowKind: RowKind, rowHeight: Int, first: Int, count: Int, section: Int) {
      top += y
      height += rowHeight
      kind += rowKind
      firstItem += first
      itemCountInRow += count
      rowSection += section
      y += rowHeight + s.gapPx
    }

    for (sec in 0 until sectionStart.size - 1) {
      val from = sectionStart[sec]
      val to = sectionStart[sec + 1]
      sections += sectionInfo(v, s, ::viewIndexAt, from, to)
      if (s.level.hasHeaders) addRow(RowKind.HEADER, headerPx, from, 0, sec)
      var p = from
      while (p < to) {
        val rowEnd = if (s.style == GridStyle.SQUARE) min(p + s.columns, to) else justifiedEnd(snapshot, v, ::viewIndexAt, s, cell, p, to)
        val row = top.size
        if (s.style == GridStyle.SQUARE) {
          for (k in p until rowEnd) {
            left[k] = (k - p) * (cell + s.gapPx)
            width[k] = cell
            rowOf[k] = row
          }
        } else {
          placeJustified(snapshot, v, ::viewIndexAt, s, cell, p, rowEnd, isLast = rowEnd == to, left, width)
          for (k in p until rowEnd) rowOf[k] = row
        }
        addRow(RowKind.CELLS, cell, p, rowEnd - p, sec)
        p = rowEnd
      }
    }
    val total = if (top.isEmpty()) 0 else max(0, y - s.gapPx)
    return ArrayGridLayout(
      spec = s, itemCount = n, totalHeight = total, sections = sections,
      rowTop = top.toIntArray(), rowHeight = height.toIntArray(), rowKind = kind.toTypedArray(),
      rowFirst = firstItem.toIntArray(), rowItems = itemCountInRow.toIntArray(), rowSection = rowSection.toIntArray(),
      left = left, width = width, rowOf = rowOf,
    )
  }

  private fun sectionInfo(v: TimelineView, s: GridSpec, viewIndexAt: (Int) -> Int, from: Int, to: Int): SectionInfo {
    val snapshot = v.snapshot
    var first = Int.MAX_VALUE
    var last = Int.MIN_VALUE
    val places = HashMap<Int, Int>()
    for (p in from until to) {
      val si = v.snapshotIndex(viewIndexAt(p))
      val day = snapshot.localDayAt(si)
      first = min(first, day)
      last = max(last, day)
      val place = snapshot.placeAt(si)
      if (place != 0) places.merge(place, 1, Int::plus)
    }
    val top = places.entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }).take(TOP_PLACES).map { it.key }
    return SectionInfo(
      level = if (s.level == GridLevel.ALL) GridLevel.MONTHS else s.level,
      firstDay = first, lastDay = last, count = to - from, topPlaces = top.toIntArray(), distinctPlaces = places.size,
    )
  }

  /** Dove finisce la riga giustificata che comincia in [from]: quando la somma delle larghezze supera lo schermo. */
  private fun justifiedEnd(
    snapshot: TimelineSnapshot,
    v: TimelineView,
    viewIndexAt: (Int) -> Int,
    s: GridSpec,
    cell: Int,
    from: Int,
    to: Int,
  ): Int {
    var sum = 0
    var p = from
    while (p < to) {
      val w = justifiedWidth(snapshot.aspectMilliAt(v.snapshotIndex(viewIndexAt(p))), cell)
      val next = sum + w + if (p > from) s.gapPx else 0
      if (p > from && next > s.widthPx) break
      sum = next
      p++
    }
    return p
  }

  private fun placeJustified(
    snapshot: TimelineSnapshot,
    v: TimelineView,
    viewIndexAt: (Int) -> Int,
    s: GridSpec,
    cell: Int,
    from: Int,
    to: Int,
    isLast: Boolean,
    left: IntArray,
    width: IntArray,
  ) {
    val natural = IntArray(to - from) { justifiedWidth(snapshot.aspectMilliAt(v.snapshotIndex(viewIndexAt(from + it))), cell) }
    val gaps = s.gapPx * (natural.size - 1)
    val available = s.widthPx - gaps
    val sum = natural.sum()
    // Si riempie la riga ripartendo lo spazio avanzato; l'ultima riga di una sezione non si allarga oltre il quarto.
    val factor = (available.toFloat() / sum).let { if (isLast) min(it, 1.25f) else it }
    var x = 0
    for (k in natural.indices) {
      val w = if (k == natural.size - 1 && !isLast) s.widthPx - x else max(1, (natural[k] * factor).roundToInt())
      left[from + k] = x
      width[from + k] = w
      x += w + s.gapPx
    }
  }

  /** La larghezza naturale di una cella giustificata: altezza della riga per la proporzione, entro 1:2 e 2:1 (D4). */
  private fun justifiedWidth(aspectMilli: Int, cell: Int): Int = (cell * (aspectMilli.coerceIn(500, 2000) / 1000f)).roundToInt().coerceAtLeast(1)

  private companion object {
    const val TOP_PLACES = 3
  }
}

/** Il layout come array: la geometria di ogni riga e di ogni cella. */
internal class ArrayGridLayout(
  override val spec: GridSpec,
  override val itemCount: Int,
  override val totalHeight: Int,
  override val sections: List<SectionInfo>,
  private val rowTop: IntArray,
  private val rowHeight: IntArray,
  private val rowKind: Array<RowKind>,
  private val rowFirst: IntArray,
  private val rowItems: IntArray,
  private val rowSection: IntArray,
  private val left: IntArray,
  private val width: IntArray,
  private val rowOf: IntArray,
) : GridLayout {

  override val rowCount: Int get() = rowTop.size

  override fun rowKind(row: Int): RowKind = rowKind[row]

  override fun rowTop(row: Int): Int = rowTop[row]

  override fun rowHeight(row: Int): Int = rowHeight[row]

  override fun itemsOf(row: Int): IntRange = if (rowKind[row] == RowKind.HEADER) IntRange.EMPTY else rowFirst[row] until rowFirst[row] + rowItems[row]

  override fun rowOfItem(position: Int): Int = rowOf[position]

  override fun sectionOfRow(row: Int): Int = rowSection[row]

  override fun itemLeft(position: Int): Int = left[position]

  override fun itemWidth(position: Int): Int = width[position]

  override fun itemAt(row: Int, x: Float): Int {
    for (p in itemsOf(row)) {
      if (x >= left[p] && x < left[p] + width[p]) return p
    }
    return -1
  }

  override fun rowAtOffset(y: Int): Int {
    if (rowTop.isEmpty()) return 0
    var lo = 0
    var hi = rowTop.size - 1
    while (lo < hi) {
      val mid = (lo + hi + 1) ushr 1
      if (rowTop[mid] <= y) lo = mid else hi = mid - 1
    }
    return lo
  }

  override fun viewIndexAt(position: Int): Int = if (spec.newestAtBottom) itemCount - 1 - position else position

  override fun positionOfViewIndex(viewIndex: Int): Int = viewIndexAt(viewIndex)
}

/** L'ancoraggio di riferimento: la cella sotto il dito resta sotto il dito. O(log n). */
class SimpleGridAnchors : GridAnchors {

  override fun anchorAt(l: GridLayout, row: Int, x: Float, yInRow: Float, screenY: Float): Anchor {
    val r = row.coerceIn(0, max(0, l.rowCount - 1))
    if (l.itemCount == 0 || l.rowCount == 0) return Anchor(0, 0f, 0f, screenY)
    val items = l.itemsOf(r)
    if (items.isEmpty()) {
      // Un'intestazione: si ancora alla prima cella della sua sezione (la riga dopo).
      val next = (r + 1).coerceAtMost(l.rowCount - 1)
      val first = l.itemsOf(next).first.coerceIn(0, l.itemCount - 1)
      return Anchor(first, 0f, 0f, screenY)
    }
    val hit = l.itemAt(r, x).let { if (it >= 0) it else if (x < l.itemLeft(items.first)) items.first else items.last }
    val fx = ((x - l.itemLeft(hit)) / l.itemWidth(hit)).coerceIn(0f, 1f)
    val fy = (yInRow / l.rowHeight(r)).coerceIn(0f, 1f)
    return Anchor(hit, fx, fy, screenY)
  }

  override fun place(l2: GridLayout, a: Anchor): ScrollTarget {
    if (l2.itemCount == 0 || l2.rowCount == 0) return ScrollTarget(0, 0)
    val row = l2.rowOfItem(a.position.coerceIn(0, l2.itemCount - 1))
    // Il bordo alto della riga deve stare a screenY - fy * altezza: lo scostamento e' quanto la riga sale sopra il bordo.
    return ScrollTarget(row, (a.fy * l2.rowHeight(row) - a.screenY).roundToInt())
  }
}

/** Lo scrubber di riferimento: somme prefisse e il mese della sezione della riga. */
class SimpleScrubberModel : ScrubberModel {

  override fun rowAt(l: GridLayout, fraction: Float): Int = l.rowAtOffset((fraction.coerceIn(0f, 1f) * l.totalHeight).toInt())

  override fun fractionOf(l: GridLayout, row: Int): Float =
    if (l.totalHeight <= 0 || l.rowCount == 0) 0f else (l.rowTop(row.coerceIn(0, l.rowCount - 1)).toFloat() / l.totalHeight).coerceIn(0f, 1f)

  override fun label(l: GridLayout, row: Int): YearMonth =
    if (l.sections.isEmpty()) YearMonth.of(1970, 1) else l.sections[l.sectionOfRow(row.coerceIn(0, l.rowCount - 1))].firstMonth
}
