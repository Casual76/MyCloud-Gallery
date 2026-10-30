package dev.pampa.mycloudgallery.core.map.points

import dev.pampa.mycloudgallery.core.map.contract.CellKey
import dev.pampa.mycloudgallery.core.map.contract.GeoBox
import dev.pampa.mycloudgallery.core.map.contract.GeoCluster
import dev.pampa.mycloudgallery.core.map.contract.GeoClusterIndex
import dev.pampa.mycloudgallery.core.map.contract.GeoE6
import dev.pampa.mycloudgallery.core.map.contract.GeoGrid
import dev.pampa.mycloudgallery.core.map.contract.MapPoint
import dev.pampa.mycloudgallery.core.model.MediaId
import java.util.UUID

/**
 * L'indice dei punti della mappa (Fase 9, P3; `docs/fase9-progetto.md` §2.10, D27, D28): i punti ordinati per chiave di Morton a 48 bit
 * (z-order a 2^24 per lato, `GeoGrid`), una colonna per campo (nessun oggetto per punto). **Immutabile**: lo costruisce [GeoPointIndexBuilder]
 * fuori dal thread principale e `GeoMapPoints` lo scambia in modo atomico.
 *
 * Le celle di un livello sono i **prefissi** della chiave, quindi una cella e' un intervallo contiguo dell'array e il suo conteggio e' la
 * differenza di due ricerche binarie. [clusters] sceglie fra due strade che danno lo stesso risultato: per una vista di poche celle le
 * elenca e cerca ciascuna (centinaia di ricerche), per una vista enorme (il mondo a uno zoom alto, come fanno i test) cammina l'array una
 * volta e raggruppa i prefissi uguali. I contenuti di una cella (centro, copertina, luogo) si calcolano al volo dal suo intervallo; quelli
 * delle celle **pesanti** (da [HEAVY] punti, dove il calcolo costa) restano in una cache LRU piccola, cosi' le bolle di una citta' non si
 * rifanno a ogni fotogramma. Nessun livello si pre-calcola per intero: costerebbe quanto i punti nei livelli fini.
 *
 * Le regole sono quelle di `GeoClusterIndex` (e di `ReferenceClusterIndex`, il confronto a forza bruta): centro = media intera per difetto
 * di latitudini e longitudini, copertina = punteggio piu' alto, poi il giorno piu' recente, poi l'id minore, luogo = il piu' frequente non
 * nullo, a parita' l'id minore, membri dal piu' recente. **Nessun `toString()` mostra coordinate o id.**
 */
class GeoPointIndex internal constructor(
  private val keys: LongArray,
  private val lat: IntArray,
  private val lon: IntArray,
  private val day: IntArray,
  private val score: ShortArray,
  private val place: IntArray,
  private val idHi: LongArray,
  private val idLo: LongArray,
  private val bounds: GeoBox?,
) : GeoClusterIndex {

  override val size: Int get() = keys.size

  /** Le colonne di cui l'indice ha bisogno per raggruppare: chiave, posizione esatta, giorno, punteggio, luogo (26 byte a punto). */
  val geometryBytes: Long
    get() = keys.size * 8L + lat.size * 4L + lon.size * 4L + day.size * 4L + score.size * 2L + place.size * 4L

  /** Gli identificativi dei media (un UUID in due `Long`, 16 byte a punto: come le colonne `idMsb`/`idLsb` dell'istantanea della Libreria). */
  val idBytes: Long get() = idHi.size * 8L + idLo.size * 8L

  /** Tutto l'indice, senza la cache delle celle pesanti (che e' limitata: al piu' [HEAVY_CACHE] voci). */
  val approxBytes: Long get() = geometryBytes + idBytes

  private val heavy = object : LinkedHashMap<Long, Cell>(HEAVY_CACHE, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Cell>?): Boolean = size > HEAVY_CACHE
  }

  /** Il contenuto calcolato di una cella: quanti punti, il centro, la posizione della copertina, il luogo (0 = nessuno). */
  private class Cell(val count: Int, val lat: Int, val lon: Int, val cover: Int, val place: Int)

  override fun clusters(view: GeoBox, zoom: Double, cellPx: Int): List<GeoCluster> {
    val n = keys.size
    val level = GeoGrid.levelFor(zoom, cellPx)
    if (n == 0) return emptyList()
    val (cols, rows) = GeoGrid.viewRanges(view)
    val sx = GeoGrid.BITS - GeoGrid.xBits(level)
    val sy = GeoGrid.BITS - GeoGrid.yBits(level)
    val cy0 = (rows.first shr sy).toInt()
    val cy1 = (rows.last shr sy).toInt()
    var cells = 0L
    for (c in cols) cells += ((c.last shr sx) - (c.first shr sx) + 1L) * (cy1 - cy0 + 1L)
    val out = ArrayList<GeoCluster>()
    val tally = PlaceTally()
    if (cells * WINDOW_COST <= n) {
      byWindow(level, cols, sx, cy0, cy1, cells.toInt(), tally, out)
    } else {
      byWalk(level, cols, rows, tally, out)
    }
    return out
  }

  /** Poche celle: si elencano i prefissi della finestra e per ciascuno si cerca l'intervallo. */
  private fun byWindow(level: Int, cols: List<LongRange>, sx: Int, cy0: Int, cy1: Int, cells: Int, tally: PlaceTally, out: MutableList<GeoCluster>) {
    val prefixes = LongArray(cells)
    var k = 0
    for (c in cols) {
      for (cx in (c.first shr sx).toInt()..(c.last shr sx).toInt()) {
        for (cy in cy0..cy1) prefixes[k++] = Morton.prefix(level, cx, cy)
      }
    }
    prefixes.sort()
    var previous = -1L
    for (prefix in prefixes) {
      // Le due meta' di una vista che attraversa l'antimeridiano possono condividere una colonna di celle: una cella una volta sola.
      if (prefix == previous) continue
      previous = prefix
      val lo = lowerBound(Morton.firstKey(level, prefix), 0)
      if (lo >= keys.size || keys[lo] >= Morton.endKey(level, prefix)) continue
      val hi = lowerBound(Morton.endKey(level, prefix), lo)
      out += cluster(level, prefix, lo, hi, tally)
    }
  }

  /** Una vista enorme: si cammina l'array una volta e si raggruppano i prefissi uguali, tenendo quelli che toccano la vista. */
  private fun byWalk(level: Int, cols: List<LongRange>, rows: LongRange, tally: PlaceTally, out: MutableList<GeoCluster>) {
    val shift = Morton.freeBits(level)
    val n = keys.size
    var i = 0
    while (i < n) {
      val prefix = keys[i] ushr shift
      var j = i + 1
      while (j < n && (keys[j] ushr shift) == prefix) j++
      if (touches(level, prefix, cols, rows)) out += cluster(level, prefix, i, j, tally)
      i = j
    }
  }

  /** Lo stesso criterio di `GeoGrid.touches` (celle che intersecano le colonne e le righe della vista), senza ricalcolare la vista a ogni cella. */
  private fun touches(level: Int, prefix: Long, cols: List<LongRange>, rows: LongRange): Boolean {
    val first = Morton.firstKey(level, prefix)
    if (Morton.yMax(level, first) < rows.first || Morton.yMin(first) > rows.last) return false
    val xMin = Morton.xMin(first)
    val xMax = Morton.xMax(level, first)
    for (c in cols) if (xMax >= c.first && xMin <= c.last) return true
    return false
  }

  private fun cluster(level: Int, prefix: Long, lo: Int, hi: Int, tally: PlaceTally): GeoCluster {
    val cell = if (hi - lo >= HEAVY) heavyCell(level, prefix, lo, hi, tally) else compute(lo, hi, tally)
    return GeoCluster(CellKey(level, prefix), GeoE6(cell.lat, cell.lon), cell.count, mediaId(cell.cover), cell.place.takeIf { it > 0 })
  }

  private fun heavyCell(level: Int, prefix: Long, lo: Int, hi: Int, tally: PlaceTally): Cell {
    val id = (level.toLong() shl GeoGrid.MAX_LEVEL) or prefix
    synchronized(heavy) { heavy[id] }?.let { return it }
    val cell = compute(lo, hi, tally)
    synchronized(heavy) { heavy[id] = cell }
    return cell
  }

  private fun compute(lo: Int, hi: Int, tally: PlaceTally): Cell {
    val count = hi - lo
    if (count == 1) return Cell(1, lat[lo], lon[lo], lo, place[lo].coerceAtLeast(0))
    var sumLat = 0L
    var sumLon = 0L
    var cover = lo
    tally.reset()
    for (i in lo until hi) {
      sumLat += lat[i]
      sumLon += lon[i]
      if (i != lo && betterCover(i, cover)) cover = i
      val p = place[i]
      if (p > 0) tally.add(p)
    }
    val best = tally.best()
    tally.reset()
    return Cell(
      count,
      Math.floorDiv(sumLat, count.toLong()).toInt(),
      Math.floorDiv(sumLon, count.toLong()).toInt(),
      cover,
      best,
    )
  }

  override fun members(key: CellKey, limit: Int): List<MediaId> {
    require(limit >= 0) { "Limite negativo." }
    if (limit == 0 || keys.isEmpty()) return emptyList()
    val lo = lowerBound(Morton.firstKey(key.level, key.prefix), 0)
    if (lo >= keys.size) return emptyList()
    val end = Morton.endKey(key.level, key.prefix)
    if (keys[lo] >= end) return emptyList()
    val hi = lowerBound(end, lo)
    val wanted = minOf(limit, hi - lo)
    val heap = IntArray(wanted)
    var filled = 0
    for (i in lo until hi) {
      if (filled < wanted) {
        heap[filled] = i
        siftUp(heap, filled)
        filled++
      } else if (newer(i, heap[0])) {
        heap[0] = i
        siftDown(heap, filled, 0)
      }
    }
    // Dal piu' recente: l'ordine totale (giorno decrescente, poi id crescente) non ha pareggi, un'inserzione basta (al piu' `limit` voci).
    for (a in 1 until filled) {
      val v = heap[a]
      var b = a - 1
      while (b >= 0 && newer(v, heap[b])) {
        heap[b + 1] = heap[b]
        b--
      }
      heap[b + 1] = v
    }
    return List(filled) { mediaId(heap[it]) }
  }

  override fun boundsOfAll(): GeoBox? = bounds

  override fun toString(): String = "GeoPointIndex(${keys.size})"

  /** Il primo indice da [from] in poi la cui chiave e' >= [key] (`keys.size` se non c'e'). */
  private fun lowerBound(key: Long, from: Int): Int {
    var lo = from
    var hi = keys.size
    while (lo < hi) {
      val mid = (lo + hi) ushr 1
      if (keys[mid] < key) lo = mid + 1 else hi = mid
    }
    return lo
  }

  /** Copertina: punteggio piu' alto, poi il giorno piu' recente, poi l'id minore. */
  private fun betterCover(a: Int, b: Int): Boolean {
    if (score[a] != score[b]) return score[a] > score[b]
    if (day[a] != day[b]) return day[a] > day[b]
    return idLess(a, b)
  }

  /** "Dal piu' recente": giorno decrescente, poi id crescente. */
  private fun newer(a: Int, b: Int): Boolean = if (day[a] != day[b]) day[a] > day[b] else idLess(a, b)

  /** L'ordine degli id come testo (UUID canonico minuscolo): msb e lsb senza segno. */
  private fun idLess(a: Int, b: Int): Boolean {
    val c = java.lang.Long.compareUnsigned(idHi[a], idHi[b])
    return if (c != 0) c < 0 else java.lang.Long.compareUnsigned(idLo[a], idLo[b]) < 0
  }

  // Un mucchio binario con il peggiore in cima: tiene i `limit` piu' recenti di una cella di qualunque dimensione.
  private fun siftUp(heap: IntArray, from: Int) {
    var i = from
    while (i > 0) {
      val parent = (i - 1) ushr 1
      if (!newer(heap[parent], heap[i])) return
      val t = heap[parent]
      heap[parent] = heap[i]
      heap[i] = t
      i = parent
    }
  }

  private fun siftDown(heap: IntArray, size: Int, from: Int) {
    var i = from
    while (true) {
      val left = 2 * i + 1
      if (left >= size) return
      val right = left + 1
      val worse = if (right < size && newer(heap[left], heap[right])) right else left
      if (!newer(heap[i], heap[worse])) return
      val t = heap[i]
      heap[i] = heap[worse]
      heap[worse] = t
      i = worse
    }
  }

  private fun mediaId(position: Int): MediaId = MediaId(UUID(idHi[position], idLo[position]).toString())

  companion object {
    /** Da questi punti in su una cella e' "pesante": il suo contenuto si tiene nella cache. */
    const val HEAVY: Int = 256

    /** Quante celle pesanti tiene la cache (un centinaio di byte l'una). */
    const val HEAVY_CACHE: Int = 1_024

    /** Una cella della finestra costa due ricerche binarie (~40 passi): oltre un passo ogni [WINDOW_COST] punti conviene camminare l'array. */
    private const val WINDOW_COST: Long = 24L

    val EMPTY: GeoPointIndex =
      GeoPointIndex(LongArray(0), IntArray(0), IntArray(0), IntArray(0), ShortArray(0), IntArray(0), LongArray(0), LongArray(0), null)

    /** Un indice da una lista di punti (i non validi e i doppioni non entrano). */
    fun of(points: Iterable<MapPoint>): GeoPointIndex =
      GeoPointIndexBuilder().also { builder -> points.forEach { builder.add(it) } }.build()
  }
}
