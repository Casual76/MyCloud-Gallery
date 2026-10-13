package dev.pampa.mycloudgallery.core.library.layout

import androidx.compose.runtime.Stable
import dev.pampa.mycloudgallery.core.library.contract.GridLayout
import dev.pampa.mycloudgallery.core.library.contract.GridLayoutEngine
import dev.pampa.mycloudgallery.core.library.contract.GridLevel
import dev.pampa.mycloudgallery.core.library.contract.GridSpec
import dev.pampa.mycloudgallery.core.library.contract.GridStyle
import dev.pampa.mycloudgallery.core.library.contract.RowKind
import dev.pampa.mycloudgallery.core.library.contract.SectionInfo
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Il motore di layout della griglia (D3, D4, §2.4): dalla vista e dallo schermo alle righe precalcolate di una
 * `LazyColumn`. Puro, senza stato, O(n) in due passate; si chiama fuori dal thread principale per i quattro livelli a
 * ogni nuovo indice.
 *
 * - **Sezioni**: posizioni contigue con lo stesso anno, mese o giorno locale (per [GridLevel.ALL] mesi, senza riga di
 *   intestazione). L'ordine e' quello della vista (istante di scatto): in un viaggio con un altro fuso puo' capitare che
 *   un giorno ricompaia dopo il successivo intorno alla mezzanotte, e allora le sezioni sono due (mai un elemento sotto
 *   l'intestazione sbagliata).
 * - **[GridStyle.SQUARE]**: [GridSpec.columns] celle quadrate per riga; posizioni orizzontali aritmetiche (nessun array
 *   per cella).
 * - **[GridStyle.JUSTIFIED]** (D4): righe all'altezza della cella del livello; la larghezza naturale e' la proporzione
 *   entro 1:2 e 2:1; ogni riga si chiude dove il fattore che la riempie e' piu' vicino a 1 (includendo o no l'elemento
 *   che sborda) e si scala per riempire esattamente la larghezza. L'ultima riga di una sezione si allarga al piu' di un
 *   quarto, altrimenti resta naturale e allineata a sinistra.
 * - Le intestazioni sono alte [headerDp] per livello (le stesse misure del motore di riferimento di P0).
 */
class TimelineLayoutEngine(
  private val headerDp: (GridLevel) -> Float = ::defaultHeaderDp,
) : GridLayoutEngine {

  override fun layout(v: TimelineView, s: GridSpec): TimelineGridLayout {
    val n = v.size
    val snapshot = v.snapshot
    val cell = s.cellPx
    val headerPx = if (s.level.hasHeaders) (headerDp(s.level) * s.densityDp).roundToInt().coerceAtLeast(1) else 0

    // 1. Giorno e posizione nell'indice per ogni posizione di lettura (una lettura dell'indice per elemento).
    val index = IntArray(n)
    val day = IntArray(n)
    for (p in 0 until n) {
      val si = v.snapshotIndex(if (s.newestAtBottom) n - 1 - p else p)
      index[p] = si
      day[p] = snapshot.localDayAt(si)
    }

    // 2. Righe.
    val rows = Rows(n / s.columns + 64)
    val left: CharArray? = if (s.style == GridStyle.JUSTIFIED) CharArray(n) else null
    val width: CharArray? = if (s.style == GridStyle.JUSTIFIED) CharArray(n) else null
    val natural = if (s.style == GridStyle.JUSTIFIED) DoubleArray(n) { naturalWidth(snapshot.aspectMilliAt(index[it]), cell) } else null
    val sections = ArrayList<SectionInfo>()
    val placesScratch = IntArray(n.coerceAtMost(1 shl 16).coerceAtLeast(1))
    var y = 0L
    var from = 0
    while (from < n) {
      val key = keyOf(s.level, day[from])
      var to = from + 1
      while (to < n && keyOf(s.level, day[to]) == key) to++
      val sec = sections.size
      sections += sectionInfo(s.level, snapshot, index, day, from, to, placesScratch)
      if (s.level.hasHeaders) {
        rows.add(y, from, header = true, sec, month = day[from] / 100)
        y += headerPx + s.gapPx
      }
      var p = from
      while (p < to) {
        val end = if (natural == null) minOf(p + s.columns, to) else justifiedEnd(natural, s, p, to)
        if (natural != null) placeJustified(natural, s, p, end, isLast = end == to, left!!, width!!)
        rows.add(y, p, header = false, sec, month = day[p] / 100)
        y += cell + s.gapPx
        p = end
      }
      from = to
    }
    val total = if (rows.size == 0) 0L else y - s.gapPx
    require(total <= Int.MAX_VALUE) { "La griglia supera l'altezza massima." }
    return TimelineGridLayout(
      spec = s, itemCount = n, totalHeight = total.toInt(), sections = sections,
      rowTop = rows.top.copyOf(rows.size), rowFirst = rows.first.copyOf(rows.size + 1).also { it[rows.size] = n },
      header = rows.header.copyOf(rows.size), rowSection = rows.section.copyOf(rows.size), rowMonth = rows.month.copyOf(rows.size),
      headerPx = headerPx, cellPx = cell, left = left, width = width,
    )
  }

  private fun keyOf(level: GridLevel, day: Int): Int = when (level) {
    GridLevel.YEARS -> day / 10_000
    GridLevel.MONTHS, GridLevel.ALL -> day / 100
    GridLevel.DAYS -> day
  }

  /** Giorni estremi, conteggio e luoghi piu' frequenti (a parita', l'id piu' piccolo) della sezione [from, to). */
  private fun sectionInfo(
    level: GridLevel,
    snapshot: dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot,
    index: IntArray,
    day: IntArray,
    from: Int,
    to: Int,
    scratch: IntArray,
  ): SectionInfo {
    var first = Int.MAX_VALUE
    var last = Int.MIN_VALUE
    var places = scratch
    var count = 0
    for (p in from until to) {
      val d = day[p]
      if (d < first) first = d
      if (d > last) last = d
      val place = snapshot.placeAt(index[p])
      if (place != 0) {
        if (count == places.size) places = places.copyOf(places.size * 2)
        places[count++] = place
      }
    }
    var top = IntArray(0)
    var distinct = 0
    if (count > 0) {
      places.sort(0, count)
      // Corse di valori uguali: le TOP_PLACES piu' lunghe (a parita' di lunghezza vince l'id piu' piccolo).
      val topIds = IntArray(TOP_PLACES)
      val topCounts = IntArray(TOP_PLACES)
      var filled = 0
      var r = 0
      while (r < count) {
        var e = r + 1
        while (e < count && places[e] == places[r]) e++
        distinct++
        val c = e - r
        var slot = filled
        while (slot > 0 && topCounts[slot - 1] < c) slot--
        if (slot < TOP_PLACES) {
          val limit = minOf(filled, TOP_PLACES - 1)
          for (k in limit downTo slot + 1) {
            topIds[k] = topIds[k - 1]
            topCounts[k] = topCounts[k - 1]
          }
          topIds[slot] = places[r]
          topCounts[slot] = c
          if (filled < TOP_PLACES) filled++
        }
        r = e
      }
      top = topIds.copyOf(filled)
    }
    return SectionInfo(
      level = if (level == GridLevel.ALL) GridLevel.MONTHS else level,
      firstDay = first, lastDay = last, count = to - from, topPlaces = top, distinctPlaces = distinct,
    )
  }

  /** Dove chiudere la riga giustificata che parte da [from]: il fattore di riempimento piu' vicino a 1. */
  private fun justifiedEnd(natural: DoubleArray, s: GridSpec, from: Int, to: Int): Int {
    val w = s.widthPx.toDouble()
    var sum = natural[from]
    var k = from + 1
    while (k < to) {
      val gapsWith = (k - from) * s.gapPx
      if (sum + natural[k] + gapsWith <= w) {
        sum += natural[k]
        k++
        continue
      }
      val available = w - gapsWith
      if (available > 0) {
        val without = (w - (k - from - 1) * s.gapPx) / sum
        val with = available / (sum + natural[k])
        if (abs(ln(with)) < abs(ln(without))) k++
      }
      break
    }
    return k
  }

  private fun placeJustified(natural: DoubleArray, s: GridSpec, from: Int, to: Int, isLast: Boolean, left: CharArray, width: CharArray) {
    val count = to - from
    var sum = 0.0
    for (p in from until to) sum += natural[p]
    val available = (s.widthPx - s.gapPx * (count - 1)).coerceAtLeast(count)
    val fill = available / sum
    val factor = if (!isLast || fill <= 1.0) fill else if (fill <= MAX_LAST_ROW_STRETCH) fill else 1.0
    val fills = factor == fill
    var cumulative = 0.0
    for (k in 0 until count) {
      val p = from + k
      val start = (cumulative * factor).roundToLong()
      cumulative += natural[p]
      // La riga che riempie finisce esattamente sul bordo destro (niente pixel persi negli arrotondamenti).
      val end = if (fills && k == count - 1) available.toLong() else (cumulative * factor).roundToLong()
      left[p] = (start + k.toLong() * s.gapPx).coerceIn(0L, MAX_COORD).toInt().toChar()
      width[p] = (end - start).coerceIn(1L, MAX_COORD).toInt().toChar()
    }
  }

  /** La larghezza naturale: l'altezza della riga per la proporzione, entro 1:2 e 2:1 (D4). */
  private fun naturalWidth(aspectMilli: Int, cell: Int): Double = cell * (aspectMilli.coerceIn(500, 2000) / 1000.0)

  /** Le righe che crescono raddoppiando. */
  private class Rows(initial: Int) {
    var size = 0
    var top = IntArray(initial.coerceAtLeast(16))
    var first = IntArray(top.size + 1)
    var header = BooleanArray(top.size)
    var section = IntArray(top.size)
    var month = IntArray(top.size)

    fun add(y: Long, firstItem: Int, header: Boolean, section: Int, month: Int) {
      if (size == top.size) {
        val c = top.size * 2
        top = top.copyOf(c)
        first = first.copyOf(c + 1)
        this.header = this.header.copyOf(c)
        this.section = this.section.copyOf(c)
        this.month = this.month.copyOf(c)
      }
      require(y <= Int.MAX_VALUE) { "La griglia supera l'altezza massima." }
      top[size] = y.toInt()
      first[size] = firstItem
      this.header[size] = header
      this.section[size] = section
      this.month[size] = month
      size++
    }
  }

  companion object {
    private const val TOP_PLACES = 3
    private const val MAX_LAST_ROW_STRETCH = 1.25
    private const val MAX_COORD = 0xFFFFL

    /** Le altezze delle intestazioni in dp per livello (Anni 48, Mesi 60, Giorni 56; Tutte non ne ha). */
    fun defaultHeaderDp(level: GridLevel): Float = when (level) {
      GridLevel.YEARS -> 48f
      GridLevel.MONTHS -> 60f
      GridLevel.DAYS -> 56f
      GridLevel.ALL -> 0f
    }
  }
}

/**
 * Il layout calcolato da [TimelineLayoutEngine]: righe in array (bordo alto, prima posizione, intestazione, sezione,
 * mese), e per lo stile giustificato bordo sinistro e larghezza di ogni cella (16 bit l'uno). Tutte le funzioni sono
 * O(1) o O(log n) e non allocano, tranne [itemsOf] (un `IntRange`, contratto di P0): nel percorso caldo si usano
 * [firstItemOf] e [itemCountOf].
 */
@Stable
class TimelineGridLayout internal constructor(
  override val spec: GridSpec,
  override val itemCount: Int,
  override val totalHeight: Int,
  override val sections: List<SectionInfo>,
  private val rowTop: IntArray,
  /** `rowCount + 1` valori: le celle della riga `r` sono `[rowFirst[r], rowFirst[r + 1])` (vuoto per un'intestazione). */
  private val rowFirst: IntArray,
  private val header: BooleanArray,
  private val rowSection: IntArray,
  /** `aaaamm` della prima cella della riga (per un'intestazione, della sua sezione): la bolla dello scrubber. */
  private val rowMonth: IntArray,
  val headerPx: Int,
  val cellPx: Int,
  private val left: CharArray?,
  private val width: CharArray?,
) : GridLayout {

  override val rowCount: Int get() = rowTop.size

  override fun rowKind(row: Int): RowKind = if (header[row]) RowKind.HEADER else RowKind.CELLS

  override fun rowTop(row: Int): Int = rowTop[row]

  override fun rowHeight(row: Int): Int = if (header[row]) headerPx else cellPx

  override fun itemsOf(row: Int): IntRange = if (header[row]) IntRange.EMPTY else rowFirst[row] until rowFirst[row + 1]

  /** La prima cella della riga (per un'intestazione, la prima della sua sezione). */
  fun firstItemOf(row: Int): Int = rowFirst[row]

  /** Quante celle ha la riga (0 per un'intestazione). */
  fun itemCountOf(row: Int): Int = if (header[row]) 0 else rowFirst[row + 1] - rowFirst[row]

  /** `aaaamm` della riga: lo scrubber ne fa la bolla ("marzo 2024"). */
  fun monthOfRow(row: Int): Int = rowMonth[row]

  override fun rowOfItem(position: Int): Int {
    if (position !in 0 until itemCount) throw IndexOutOfBoundsException("Posizione fuori dal layout.")
    // L'ultima riga con rowFirst <= position: e' sempre una riga di celle (l'intestazione ha lo stesso rowFirst della
    // riga che la segue, quindi la ricerca va oltre).
    var lo = 0
    var hi = rowTop.size - 1
    while (lo < hi) {
      val mid = (lo + hi + 1) ushr 1
      if (rowFirst[mid] <= position) lo = mid else hi = mid - 1
    }
    return lo
  }

  override fun sectionOfRow(row: Int): Int = rowSection[row]

  override fun itemLeft(position: Int): Int {
    val l = left
    if (l != null) return l[position].code
    val row = rowOfItem(position)
    return (position - rowFirst[row]) * (cellPx + spec.gapPx)
  }

  override fun itemWidth(position: Int): Int = width?.get(position)?.code ?: cellPx

  override fun itemAt(row: Int, x: Float): Int {
    if (row !in 0 until rowCount || header[row] || x < 0f) return -1
    val first = rowFirst[row]
    val end = rowFirst[row + 1]
    val l = left
    if (l == null) {
      val pitch = cellPx + spec.gapPx
      val col = (x / pitch).toInt()
      if (first + col >= end) return -1
      return if (x - col * pitch < cellPx) first + col else -1
    }
    var lo = first
    var hi = end - 1
    if (x < l[lo].code) return -1
    while (lo < hi) {
      val mid = (lo + hi + 1) ushr 1
      if (l[mid].code <= x) lo = mid else hi = mid - 1
    }
    return if (x < l[lo].code + width!![lo].code) lo else -1
  }

  override fun rowAtOffset(y: Int): Int {
    if (rowTop.isEmpty() || y <= 0) return 0
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

  /** Byte occupati dal layout (budget di memoria dei quattro livelli precalcolati). */
  fun estimatedBytes(): Long =
    16L * 7 + rowTop.size * (4L + 4 + 1 + 4 + 4) + (left?.size ?: 0) * 4L
}
