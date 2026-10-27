package dev.pampa.mycloudgallery.core.map.contract

import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource

// LA GEOMETRIA DELLA MAPPA (docs/fase9-progetto.md §1.2 "core-map", §2.10, D27, D28, E-MAP7, E-MAP10). Contratto di P0: punti, riquadri, la
// griglia di Morton e la sua proiezione **in un posto solo**, cosi' l'indice vero di P3 e il riferimento a forza bruta (`ReferenceClusterIndex`)
// contano le stesse celle; la proiezione usa `StrictMath` (bit per bit uguale su ogni JVM). **Nessun `toString()` mostra coordinate** (D33): una
// coordinata delle foto dice dove si vive.

/** Un punto in microgradi (gradi x 10^6, interi): latitudine e longitudine. */
data class GeoE6(val latE6: Int, val lonE6: Int) {
  override fun toString(): String = "GeoE6"
}

/**
 * Un riquadro in microgradi: [westE6], [southE6], [eastE6], [northE6]. Con [westE6] > [eastE6] il riquadro **attraversa l'antimeridiano**
 * (E-MAP10): va da ovest fino a 180 e da -180 fino a est.
 */
data class GeoBox(val westE6: Int, val southE6: Int, val eastE6: Int, val northE6: Int) {
  init {
    require(southE6 <= northE6) { "Riquadro rovesciato." }
    require(westE6 in -GeoGrid.LON_E6..GeoGrid.LON_E6 && eastE6 in -GeoGrid.LON_E6..GeoGrid.LON_E6) { "Longitudine fuori intervallo." }
    require(southE6 >= -GeoGrid.LAT_E6 && northE6 <= GeoGrid.LAT_E6) { "Latitudine fuori intervallo." }
  }

  val crossesAntimeridian: Boolean get() = westE6 > eastE6

  fun contains(latE6: Int, lonE6: Int): Boolean {
    if (latE6 !in southE6..northE6) return false
    return if (crossesAntimeridian) lonE6 >= westE6 || lonE6 <= eastE6 else lonE6 in westE6..eastE6
  }

  override fun toString(): String = "GeoBox"
}

/**
 * Un punto della mappa (D27): un media **visibile** con coordinate effettive (la correzione se c'e', altrimenti i metadati; anche solo sul
 * NAS), il giorno locale (`aaaammgg`, per "dal piu' recente"), il punteggio della copertina ([MapCoverScore]) e il luogo (`place_id`, 0 = nessuno)
 * per l'etichetta delle bolle grandi.
 */
data class MapPoint(val id: MediaId, val latE6: Int, val lonE6: Int, val day: Int, val score: Short, val placeId: Int = 0) {
  override fun toString(): String = "MapPoint"
}

/** Le coordinate che entrano nella mappa (E-MAP7): nei limiti, non esattamente (0, 0), latitudine entro +-85 gradi. */
object MapPointValidity {
  fun isValid(latE6: Int, lonE6: Int): Boolean =
    latE6 in -MAX_LAT_E6..MAX_LAT_E6 && lonE6 in -GeoGrid.LON_E6..GeoGrid.LON_E6 && !(latE6 == 0 && lonE6 == 0)

  /** Da gradi decimali (la colonna `version_metadata`, la correzione): null se non e' un numero o se la coordinata non entra. */
  fun toE6(latitude: Double, longitude: Double): GeoE6? {
    if (!latitude.isFinite() || !longitude.isFinite()) return null
    val lat = StrictMath.round(latitude * 1_000_000.0)
    val lon = StrictMath.round(longitude * 1_000_000.0)
    if (lat !in Int.MIN_VALUE..Int.MAX_VALUE || lon !in Int.MIN_VALUE..Int.MAX_VALUE) return null
    return GeoE6(lat.toInt(), lon.toInt()).takeIf { isValid(it.latE6, it.lonE6) }
  }

  const val MAX_LAT_E6: Int = 85_000_000
}

/**
 * Il punteggio della copertina di una bolla (D28): il sottoinsieme di D6 che la riga della Libreria conosce (preferito, modificato, panorama,
 * movimento, video corti o lunghi, WhatsApp), in decimi; screenshot e documenti non fanno da copertina se c'e' altro. Lo calcola chi legge i punti
 * (P3, dalla riga della query); una definizione sola.
 */
object MapCoverScore {
  fun of(flags: Int, source: MediaSource, kind: MediaKind, durationMs: Long): Short {
    var score = 0
    if (CellFlags.has(flags, CellFlags.FAVORITE)) score += 30
    if (CellFlags.has(flags, CellFlags.EDITED)) score += 10
    if (CellFlags.has(flags, CellFlags.PANORAMA)) score += 5
    if (CellFlags.has(flags, CellFlags.MOTION)) score += 3
    if (kind == MediaKind.VIDEO) {
      if (durationMs in 3_000..60_000) score += 5
      if (durationMs > 300_000) score -= 5
    }
    if (source == MediaSource.WHATSAPP) score -= 15
    if (source == MediaSource.SCREENSHOT || CellFlags.has(flags, CellFlags.DOCUMENT)) score -= 50
    return score.toShort()
  }
}

/** Una cella della griglia di Morton (D28): [level] i bit del prefisso (0..48), [prefix] il prefisso della chiave. Mai coordinate nei log. */
data class CellKey(val level: Int, val prefix: Long) {
  init {
    require(level in 0..GeoGrid.MAX_LEVEL) { "Livello fuori intervallo." }
    require(prefix >= 0 && (level == 64 || prefix ushr level == 0L)) { "Prefisso fuori dal livello." }
  }

  override fun toString(): String = "CellKey($level)"
}

/**
 * La griglia (D28): proiezione di Mercatore in un intero a 24 bit per lato, chiave di Morton a 48 bit (x sui bit dispari, quindi il bit piu' alto
 * e' di x), celle = prefissi della chiave; ogni bit in piu' e' **mezzo livello** (dimezza la cella in una sola direzione). Il livello per uno
 * zoom di MapLibre (tessere da 512 px) e una cella di [cellPx] pixel: `floor(2 x log2(512 x 2^zoom / cellPx))`, fra 0 e 48.
 */
object GeoGrid {
  const val BITS: Int = 24
  const val SIDE: Int = 1 shl BITS
  const val MAX_LEVEL: Int = 2 * BITS
  const val TILE_PX: Int = 512
  const val LON_E6: Int = 180_000_000
  const val LAT_E6: Int = 85_051_128

  /** La x di Mercatore (0..2^24-1) di una longitudine in microgradi. */
  fun x(lonE6: Int): Int {
    val fraction = (lonE6.toDouble() / 1_000_000.0 + 180.0) / 360.0
    return clamp(StrictMath.floor(fraction * SIDE).toLong())
  }

  /** La y di Mercatore (0 a nord, 2^24-1 a sud) di una latitudine in microgradi (oltre +-85,0511 si ferma al bordo). */
  fun y(latE6: Int): Int {
    val lat = (latE6.coerceIn(-LAT_E6, LAT_E6)).toDouble() / 1_000_000.0
    val phi = StrictMath.toRadians(lat)
    val fraction = (1.0 - StrictMath.log(StrictMath.tan(phi) + 1.0 / StrictMath.cos(phi)) / StrictMath.PI) / 2.0
    return clamp(StrictMath.floor(fraction * SIDE).toLong())
  }

  /** La longitudine (microgradi) del bordo ovest della colonna [x]. */
  fun lonE6(x: Long): Int = StrictMath.round((x.toDouble() / SIDE * 360.0 - 180.0) * 1_000_000.0).toInt()

  /** La latitudine (microgradi) del bordo nord della riga [y]. */
  fun latE6(y: Long): Int {
    val n = StrictMath.PI * (1.0 - 2.0 * y.toDouble() / SIDE)
    return StrictMath.round(StrictMath.toDegrees(StrictMath.atan(StrictMath.sinh(n))) * 1_000_000.0).toInt()
  }

  /** La chiave di Morton (48 bit) di (x, y). */
  fun morton(x: Int, y: Int): Long {
    var key = 0L
    for (bit in BITS - 1 downTo 0) {
      key = (key shl 1) or ((x.toLong() ushr bit) and 1L)
      key = (key shl 1) or ((y.toLong() ushr bit) and 1L)
    }
    return key
  }

  fun key(latE6: Int, lonE6: Int): Long = morton(x(lonE6), y(latE6))

  /** La cella di livello [level] che contiene il punto. */
  fun cellOf(latE6: Int, lonE6: Int, level: Int): CellKey = CellKey(level, key(latE6, lonE6) ushr (MAX_LEVEL - level))

  /** I bit di x e di y di un prefisso di [level] bit: x ne ha ceil(level/2), y floor(level/2). */
  fun xBits(level: Int): Int = (level + 1) / 2

  fun yBits(level: Int): Int = level / 2

  /** Le colonne e le righe (a 24 bit, estremi compresi) coperte dalla cella [key]. */
  fun ranges(key: CellKey): Pair<LongRange, LongRange> {
    var xs = 0L
    var ys = 0L
    for (i in key.level - 1 downTo 0) {
      val bit = (key.prefix ushr i) and 1L
      // Dall'alto: i bit pari dall'inizio (contando da 0) sono di x.
      if ((key.level - 1 - i) % 2 == 0) xs = (xs shl 1) or bit else ys = (ys shl 1) or bit
    }
    val xShift = BITS - xBits(key.level)
    val yShift = BITS - yBits(key.level)
    return (xs shl xShift)..(((xs + 1) shl xShift) - 1) to (ys shl yShift)..(((ys + 1) shl yShift) - 1)
  }

  /** Il livello della griglia per lo zoom [zoom] di MapLibre e celle di [cellPx] pixel. */
  fun levelFor(zoom: Double, cellPx: Int): Int {
    require(cellPx > 0 && zoom.isFinite()) { "Zoom o cella non validi." }
    val cellsPerSide = TILE_PX.toDouble() * StrictMath.pow(2.0, zoom) / cellPx
    if (cellsPerSide <= 1.0) return 0
    val level = StrictMath.floor(2.0 * StrictMath.log(cellsPerSide) / StrictMath.log(2.0)).toInt()
    return level.coerceIn(0, MAX_LEVEL)
  }

  /** Le colonne e le righe toccate da [box] (due intervalli di colonne se attraversa l'antimeridiano). */
  fun viewRanges(box: GeoBox): Pair<List<LongRange>, LongRange> {
    val rows = y(box.northE6).toLong()..y(box.southE6).toLong()
    val cols = if (box.crossesAntimeridian) {
      listOf(x(box.westE6).toLong()..(SIDE - 1).toLong(), 0L..x(box.eastE6).toLong())
    } else {
      listOf(x(box.westE6).toLong()..x(box.eastE6).toLong())
    }
    return cols to rows
  }

  /** Se la cella [key] tocca il riquadro [box] (D28: le celle del livello che toccano la vista). */
  fun touches(key: CellKey, box: GeoBox): Boolean {
    val (xs, ys) = ranges(key)
    val (cols, rows) = viewRanges(box)
    if (ys.last < rows.first || ys.first > rows.last) return false
    return cols.any { !(xs.last < it.first || xs.first > it.last) }
  }

  private fun clamp(v: Long): Int = v.coerceIn(0L, (SIDE - 1).toLong()).toInt()
}
