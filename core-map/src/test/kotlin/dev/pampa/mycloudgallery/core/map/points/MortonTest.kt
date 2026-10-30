package dev.pampa.mycloudgallery.core.map.points

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.map.contract.CellKey
import dev.pampa.mycloudgallery.core.map.contract.GeoGrid
import java.util.Random
import org.junit.Test

/**
 * Fase 9 (P3; D28): l'aritmetica veloce di `Morton` coincide con la definizione di `GeoGrid` (contratto di P0) su valori casuali e ai bordi:
 * chiavi, cella di un punto, colonne e righe di una cella, prefisso di una cella data la sua colonna e riga. Una definizione, due implementazioni.
 */
class MortonTest {

  private val random = Random(77)

  @Test
  fun laChiaveVelocePerLaStessaDiGeoGrid() {
    val edges = listOf(0, 1, GeoGrid.SIDE - 1, GeoGrid.SIDE / 2, 0x555555, 0xAAAAAA)
    for (x in edges) for (y in edges) assertThat(Morton.key(x, y)).isEqualTo(GeoGrid.morton(x, y))
    repeat(20_000) {
      val x = random.nextInt(GeoGrid.SIDE)
      val y = random.nextInt(GeoGrid.SIDE)
      assertThat(Morton.key(x, y)).isEqualTo(GeoGrid.morton(x, y))
      assertThat(Morton.compact(Morton.spread(x))).isEqualTo(x)
    }
  }

  @Test
  fun colonneERigheDiUnaCellaSonoQuelleDiGeoGrid() {
    repeat(20_000) {
      val level = random.nextInt(GeoGrid.MAX_LEVEL + 1)
      val latE6 = random.nextInt(2 * 85_000_000) - 85_000_000
      val lonE6 = random.nextInt(2 * GeoGrid.LON_E6) - GeoGrid.LON_E6
      val cell = GeoGrid.cellOf(latE6, lonE6, level)
      val (xs, ys) = GeoGrid.ranges(cell)
      val first = Morton.firstKey(level, cell.prefix)
      assertWithMessage("livello $level").that(Morton.xMin(first).toLong()).isEqualTo(xs.first)
      assertThat(Morton.xMax(level, first).toLong()).isEqualTo(xs.last)
      assertThat(Morton.yMin(first).toLong()).isEqualTo(ys.first)
      assertThat(Morton.yMax(level, first).toLong()).isEqualTo(ys.last)
      // La cella contiene le chiavi dalla prima alla "fine" esclusa, e il punto e' dentro.
      val key = GeoGrid.key(latE6, lonE6)
      assertThat(key).isAtLeast(first)
      assertThat(key).isLessThan(Morton.endKey(level, cell.prefix))
    }
  }

  @Test
  fun ilPrefissoDiUnaCellaDallaSuaColonnaERiga() {
    repeat(20_000) {
      val level = random.nextInt(GeoGrid.MAX_LEVEL + 1)
      val x = random.nextInt(GeoGrid.SIDE)
      val y = random.nextInt(GeoGrid.SIDE)
      val prefix = GeoGrid.morton(x, y) ushr (GeoGrid.MAX_LEVEL - level)
      val cx = x shr (GeoGrid.BITS - GeoGrid.xBits(level))
      val cy = y shr (GeoGrid.BITS - GeoGrid.yBits(level))
      assertWithMessage("livello $level").that(Morton.prefix(level, cx, cy)).isEqualTo(prefix)
      assertThat(CellKey(level, prefix).prefix).isEqualTo(prefix)
    }
  }

  @Test
  fun alLivelloZeroEAlMassimoLaCellaEQuellaGiusta() {
    assertThat(Morton.firstKey(0, 0)).isEqualTo(0L)
    assertThat(Morton.endKey(0, 0)).isEqualTo(1L shl 48)
    assertThat(Morton.endKey(GeoGrid.MAX_LEVEL, (1L shl 48) - 1)).isEqualTo(1L shl 48)
    assertThat(Morton.xMax(0, 0)).isEqualTo(GeoGrid.SIDE - 1)
    assertThat(Morton.yMax(0, 0)).isEqualTo(GeoGrid.SIDE - 1)
    assertThat(Morton.xMax(GeoGrid.MAX_LEVEL, 12_345L)).isEqualTo(Morton.xMin(12_345L))
  }
}
