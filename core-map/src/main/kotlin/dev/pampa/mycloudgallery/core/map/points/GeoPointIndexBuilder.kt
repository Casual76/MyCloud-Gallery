package dev.pampa.mycloudgallery.core.map.points

import dev.pampa.mycloudgallery.core.map.contract.GeoBox
import dev.pampa.mycloudgallery.core.map.contract.GeoGrid
import dev.pampa.mycloudgallery.core.map.contract.MapPoint
import dev.pampa.mycloudgallery.core.map.contract.MapPointValidity
import java.util.UUID

/**
 * Costruisce un [GeoPointIndex] (D27, D28): si danno i punti a pagine con [add]/[addAll], alla fine [build] calcola le chiavi di Morton, li ordina
 * (una radice a 6 passi da 8 bit, stabile: nessun oggetto per punto, 100 mila punti in decine di millisecondi) e consegna le colonne. Come il
 * riferimento a forza bruta (`ReferenceClusterIndex`) **scarta i punti non validi** (E-MAP7: fuori dai limiti, (0, 0) esatto, latitudine oltre
 * +-85 gradi, contati in [invalid]) e **tiene il primo di due punti con lo stesso id** (i doppioni si contano in [duplicates]; il catalogo non ne
 * da', la chiave primaria li vieta).
 *
 * Non e' thread-safe: un costruttore, un thread (quello fuori dal principale che legge le pagine).
 */
class GeoPointIndexBuilder(expected: Int = 4_096) {

  private var capacity = expected.coerceAtLeast(16)
  private var lat = IntArray(capacity)
  private var lon = IntArray(capacity)
  private var day = IntArray(capacity)
  private var score = ShortArray(capacity)
  private var place = IntArray(capacity)
  private var idHi = LongArray(capacity)
  private var idLo = LongArray(capacity)
  private var table = IntArray(tableSizeFor(capacity)).also { it.fill(EMPTY) }

  /** I punti accettati finora. */
  var size: Int = 0
    private set

  /** I punti scartati perche' le coordinate non entrano (E-MAP7). */
  var invalid: Int = 0
    private set

  /** I punti scartati perche' il loro id c'era gia'. */
  var duplicates: Int = 0
    private set

  fun addAll(points: List<MapPoint>) {
    for (point in points) add(point)
  }

  /** Aggiunge [point]; falso se e' stato scartato (coordinate non valide o id gia' visto). */
  fun add(point: MapPoint): Boolean {
    if (!MapPointValidity.isValid(point.latE6, point.lonE6)) {
      invalid++
      return false
    }
    val uuid = UUID.fromString(point.id.value)
    val hi = uuid.mostSignificantBits
    val lo = uuid.leastSignificantBits
    if (size + 1 > capacity) grow()
    if (!claim(hi, lo)) {
      duplicates++
      return false
    }
    lat[size] = point.latE6
    lon[size] = point.lonE6
    day[size] = point.day
    score[size] = point.score
    place[size] = point.placeId
    idHi[size] = hi
    idLo[size] = lo
    size++
    return true
  }

  fun build(): GeoPointIndex {
    val n = size
    if (n == 0) return GeoPointIndex.EMPTY
    val keys = LongArray(n)
    var west = Int.MAX_VALUE
    var east = Int.MIN_VALUE
    var south = Int.MAX_VALUE
    var north = Int.MIN_VALUE
    for (i in 0 until n) {
      keys[i] = Morton.key(GeoGrid.x(lon[i]), GeoGrid.y(lat[i]))
      if (lon[i] < west) west = lon[i]
      if (lon[i] > east) east = lon[i]
      if (lat[i] < south) south = lat[i]
      if (lat[i] > north) north = lat[i]
    }
    val order = IntArray(n) { it }
    sortByKey(keys, order)
    return GeoPointIndex(
      keys,
      IntArray(n) { lat[order[it]] },
      IntArray(n) { lon[order[it]] },
      IntArray(n) { day[order[it]] },
      ShortArray(n) { score[order[it]] },
      IntArray(n) { place[order[it]] },
      LongArray(n) { idHi[order[it]] },
      LongArray(n) { idLo[order[it]] },
      GeoBox(west, south, east, north),
    )
  }

  override fun toString(): String = "GeoPointIndexBuilder($size)"

  /** Ordina [keys] e, con loro, [order] (le posizioni): radice stabile a 8 bit, i passi in cui tutte le cifre sono uguali si saltano. */
  private fun sortByKey(keys: LongArray, order: IntArray) {
    val n = keys.size
    var keysIn = keys
    var orderIn = order
    var keysOut = LongArray(n)
    var orderOut = IntArray(n)
    val counts = IntArray(RADIX + 1)
    for (pass in 0 until PASSES) {
      val shift = pass * 8
      counts.fill(0)
      for (i in 0 until n) counts[((keysIn[i] ushr shift) and MASK).toInt() + 1]++
      if (counts.any { it == n }) continue
      for (d in 0 until RADIX) counts[d + 1] += counts[d]
      for (i in 0 until n) {
        val digit = ((keysIn[i] ushr shift) and MASK).toInt()
        val at = counts[digit]++
        keysOut[at] = keysIn[i]
        orderOut[at] = orderIn[i]
      }
      val k = keysIn
      keysIn = keysOut
      keysOut = k
      val o = orderIn
      orderIn = orderOut
      orderOut = o
    }
    if (keysIn !== keys) {
      System.arraycopy(keysIn, 0, keys, 0, n)
      System.arraycopy(orderIn, 0, order, 0, n)
    }
  }

  /** Registra l'id nella tabella a indirizzamento aperto: vero se e' nuovo, falso se c'era gia'. */
  private fun claim(hi: Long, lo: Long): Boolean {
    var slot = hash(hi, lo) and (table.size - 1)
    while (true) {
      val at = table[slot]
      if (at == EMPTY) {
        table[slot] = size
        return true
      }
      if (idHi[at] == hi && idLo[at] == lo) return false
      slot = (slot + 1) and (table.size - 1)
    }
  }

  private fun grow() {
    capacity = capacity + (capacity shr 1) + 16
    lat = lat.copyOf(capacity)
    lon = lon.copyOf(capacity)
    day = day.copyOf(capacity)
    score = score.copyOf(capacity)
    place = place.copyOf(capacity)
    idHi = idHi.copyOf(capacity)
    idLo = idLo.copyOf(capacity)
    table = IntArray(tableSizeFor(capacity)).also { it.fill(EMPTY) }
    for (i in 0 until size) {
      var slot = hash(idHi[i], idLo[i]) and (table.size - 1)
      while (table[slot] != EMPTY) slot = (slot + 1) and (table.size - 1)
      table[slot] = i
    }
  }

  private companion object {
    const val EMPTY = -1
    const val RADIX = 256
    const val MASK = 0xFFL
    const val PASSES = 6

    fun tableSizeFor(capacity: Int): Int = Integer.highestOneBit((capacity * 2).coerceAtLeast(16) - 1) shl 1

    fun hash(hi: Long, lo: Long): Int {
      var h = (hi xor (lo * -0x61c8864680b583ebL))
      h = h xor (h ushr 32)
      h *= -0x61c8864680b583ebL
      return (h ushr 29).toInt()
    }
  }
}
