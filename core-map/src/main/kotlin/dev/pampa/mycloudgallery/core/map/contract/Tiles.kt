package dev.pampa.mycloudgallery.core.map.contract

// LE TESSERE (docs/fase9-progetto.md §2.8, §2.9, D22, D23, D26). Contratto di P0: la tessera `z/x/y` dello schema delle tessere web (Mercatore,
// 2^z x 2^z) e la matematica che serve alla politica di rete (P1), alla copertura delle regioni (P5) e alle stime: **una definizione sola**, cosi'
// "coperta" vuol dire la stessa cosa per chi scarica e per chi rifiuta. Una tessera dice la zona guardata: **mai nei log** (D33).

/** Una tessera: livello [z] (0..22), colonna [x] e riga [y] (0..2^z-1, y da nord). */
data class TileKey(val z: Int, val x: Int, val y: Int) {
  init {
    require(z in 0..MAX_Z) { "Livello fuori intervallo." }
    require(x in 0 until (1 shl z) && y in 0 until (1 shl z)) { "Tessera fuori dai limiti del livello." }
  }

  override fun toString(): String = "TileKey(z=$z)"

  companion object {
    const val MAX_Z: Int = 22
  }
}

object TileMath {
  /** La tessera di livello [z] che contiene il punto (latitudine oltre il bordo di Mercatore: la riga di bordo). */
  fun tileOf(latE6: Int, lonE6: Int, z: Int): TileKey {
    val shift = GeoGrid.BITS - z
    require(shift >= 0) { "Livello oltre la risoluzione della griglia." }
    return TileKey(z, GeoGrid.x(lonE6) ushr shift, GeoGrid.y(latE6) ushr shift)
  }

  /** Le colonne e le righe delle tessere di livello [z] che toccano [box] (due intervalli di colonne attraverso l'antimeridiano). */
  fun ranges(box: GeoBox, z: Int): Pair<List<IntRange>, IntRange> {
    val shift = GeoGrid.BITS - z
    require(z in 0..GeoGrid.BITS) { "Livello fuori intervallo." }
    val (cols, rows) = GeoGrid.viewRanges(box)
    return cols.map { (it.first ushr shift).toInt()..(it.last ushr shift).toInt() } to (rows.first ushr shift).toInt()..(rows.last ushr shift).toInt()
  }

  /** Se [tile] tocca [box] (la copertura di una regione: MapLibre scarica le tessere che toccano il riquadro, da [minZ] a [maxZ]). */
  fun touches(tile: TileKey, box: GeoBox, minZ: Int = 0, maxZ: Int = MapHost.MAX_ZOOM): Boolean {
    if (tile.z !in minZ..maxZ) return false
    val (cols, rows) = ranges(box, tile.z)
    return tile.y in rows && cols.any { tile.x in it }
  }

  /** Quante tessere toccano [box] da [minZ] a [maxZ] (la stima di una regione, D26). */
  fun count(box: GeoBox, minZ: Int = 0, maxZ: Int = MapHost.MAX_ZOOM): Long {
    var total = 0L
    for (z in minZ..maxZ) {
      val (cols, rows) = ranges(box, z)
      val height = (rows.last - rows.first + 1).toLong()
      total += cols.sumOf { (it.last - it.first + 1).toLong() } * height
    }
    return total
  }

  /** Il riquadro di una tessera (bordi in microgradi). */
  fun boxOf(tile: TileKey): GeoBox {
    val shift = GeoGrid.BITS - tile.z
    val x0 = tile.x.toLong() shl shift
    val y0 = tile.y.toLong() shl shift
    val x1 = ((tile.x + 1).toLong() shl shift) - 1
    val y1 = ((tile.y + 1).toLong() shl shift) - 1
    return GeoBox(GeoGrid.lonE6(x0), GeoGrid.latE6(y1 + 1).coerceAtLeast(-GeoGrid.LAT_E6), GeoGrid.lonE6(x1 + 1).coerceAtMost(GeoGrid.LON_E6), GeoGrid.latE6(y0).coerceAtMost(GeoGrid.LAT_E6))
  }
}
