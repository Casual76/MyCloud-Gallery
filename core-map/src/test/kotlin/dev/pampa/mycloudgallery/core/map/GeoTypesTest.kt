package dev.pampa.mycloudgallery.core.map

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.map.contract.CameraSpot
import dev.pampa.mycloudgallery.core.map.contract.CellKey
import dev.pampa.mycloudgallery.core.map.contract.GeoBox
import dev.pampa.mycloudgallery.core.map.contract.GeoCluster
import dev.pampa.mycloudgallery.core.map.contract.GeoE6
import dev.pampa.mycloudgallery.core.map.contract.GeoGrid
import dev.pampa.mycloudgallery.core.map.contract.MapCoverScore
import dev.pampa.mycloudgallery.core.map.contract.MapNetState
import dev.pampa.mycloudgallery.core.map.contract.MapNetwork
import dev.pampa.mycloudgallery.core.map.contract.MapPoint
import dev.pampa.mycloudgallery.core.map.contract.MapPointValidity
import dev.pampa.mycloudgallery.core.map.contract.MapRequestFacts
import dev.pampa.mycloudgallery.core.map.contract.MapResponseRules
import dev.pampa.mycloudgallery.core.map.contract.RegionDownloadConfirmation
import dev.pampa.mycloudgallery.core.map.contract.RegionId
import dev.pampa.mycloudgallery.core.map.contract.RegionRadius
import dev.pampa.mycloudgallery.core.map.contract.RegionSpec
import dev.pampa.mycloudgallery.core.map.contract.TileKey
import dev.pampa.mycloudgallery.core.map.contract.TileMath
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import java.util.Random
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Fase 9 (P0; `docs/fase9-progetto.md` D27, D28, E-MAP7, E-MAP10, D33): la geometria condivisa dall'indice di P3, dalla politica di P1 e dalle
 * regioni di P5 (proiezione, Morton, livelli, tessere), le coordinate che entrano, i `toString()` senza coordinate ne' tessere.
 */
class GeoTypesTest {

  @Test
  fun laProiezioneEMonotonaENeiBordi() {
    assertThat(GeoGrid.x(-180_000_000)).isEqualTo(0)
    assertThat(GeoGrid.x(180_000_000)).isEqualTo(GeoGrid.SIDE - 1)
    assertThat(GeoGrid.x(0)).isEqualTo(GeoGrid.SIDE / 2)
    assertThat(GeoGrid.y(GeoGrid.LAT_E6)).isEqualTo(0)
    assertThat(GeoGrid.y(-GeoGrid.LAT_E6)).isEqualTo(GeoGrid.SIDE - 1)
    assertThat(GeoGrid.y(0)).isEqualTo(GeoGrid.SIDE / 2)
    assertThat(GeoGrid.y(89_000_000)).isEqualTo(0)
    var lastX = -1
    var lastY = Int.MAX_VALUE
    for (v in -179 until 180) {
      val x = GeoGrid.x(v * 1_000_000)
      assertThat(x).isGreaterThan(lastX)
      lastX = x
    }
    for (v in -84 until 85) {
      val y = GeoGrid.y(v * 1_000_000)
      assertThat(y).isLessThan(lastY)
      lastY = y
    }
  }

  /** D28: la cella di un punto contiene il punto, a ogni livello; il prefisso di un livello e' il prefisso del livello dopo. */
  @Test
  fun leCelleSonoPrefissiEContengonoIPunti() {
    val random = Random(5)
    repeat(2_000) {
      val lat = random.nextInt(2 * 85_000_000) - 85_000_000
      val lon = random.nextInt(2 * 180_000_000) - 180_000_000
      val x = GeoGrid.x(lon).toLong()
      val y = GeoGrid.y(lat).toLong()
      var previous: CellKey? = null
      for (level in 0..GeoGrid.MAX_LEVEL) {
        val cell = GeoGrid.cellOf(lat, lon, level)
        val (xs, ys) = GeoGrid.ranges(cell)
        assertThat(x in xs && y in ys).isTrue()
        previous?.let { assertThat(cell.prefix ushr 1).isEqualTo(it.prefix) }
        previous = cell
      }
    }
    assertThat(GeoGrid.ranges(CellKey(0, 0))).isEqualTo(0L..(GeoGrid.SIDE - 1).toLong() to 0L..(GeoGrid.SIDE - 1).toLong())
  }

  @Test
  fun ilLivelloCresceConLoZoom() {
    assertThat(GeoGrid.levelFor(0.0, 512)).isEqualTo(0)
    assertThat(GeoGrid.levelFor(0.0, 72)).isEqualTo(5)
    var last = 0
    var zoom = 0.0
    while (zoom <= 22.0) {
      val level = GeoGrid.levelFor(zoom, 72)
      assertThat(level).isAtLeast(last)
      assertThat(level).isAtMost(GeoGrid.MAX_LEVEL)
      last = level
      zoom += 0.25
    }
    assertThat(GeoGrid.levelFor(30.0, 1)).isEqualTo(GeoGrid.MAX_LEVEL)
  }

  /** E-MAP10: un riquadro che attraversa l'antimeridiano tocca le celle dei due lati. */
  @Test
  fun lAntimeridiano() {
    val view = GeoBox(170_000_000, -10_000_000, -170_000_000, 10_000_000)
    assertThat(view.crossesAntimeridian).isTrue()
    assertThat(view.contains(0, 175_000_000)).isTrue()
    assertThat(view.contains(0, -175_000_000)).isTrue()
    assertThat(view.contains(0, 0)).isFalse()
    assertThat(GeoGrid.touches(GeoGrid.cellOf(0, 179_000_000, 10), view)).isTrue()
    assertThat(GeoGrid.touches(GeoGrid.cellOf(0, -179_000_000, 10), view)).isTrue()
    assertThat(GeoGrid.touches(GeoGrid.cellOf(0, 0, 10), view)).isFalse()
  }

  /** E-MAP7: fuori dai limiti, (0, 0) esatto, latitudini oltre +-85 non entrano; i gradi non finiti nemmeno. */
  @Test
  fun leCoordinateCheEntrano() {
    assertThat(MapPointValidity.isValid(0, 0)).isFalse()
    assertThat(MapPointValidity.isValid(1, 0)).isTrue()
    assertThat(MapPointValidity.isValid(85_000_001, 0)).isFalse()
    assertThat(MapPointValidity.isValid(-85_000_000, 180_000_000)).isTrue()
    assertThat(MapPointValidity.isValid(10, 180_000_001)).isFalse()
    assertThat(MapPointValidity.toE6(43.77, 11.25)).isEqualTo(GeoE6(43_770_000, 11_250_000))
    assertThat(MapPointValidity.toE6(Double.NaN, 1.0)).isNull()
    assertThat(MapPointValidity.toE6(0.0, 0.0)).isNull()
    assertThat(MapPointValidity.toE6(1e12, 0.0)).isNull()
  }

  /** D28: la copertina preferisce preferiti e modificati; screenshot e documenti mai se c'e' altro. */
  @Test
  fun ilPunteggioDellaCopertina() {
    val favorite = MapCoverScore.of(CellFlags.FAVORITE, MediaSource.CAMERA, MediaKind.PHOTO, 0)
    val plain = MapCoverScore.of(0, MediaSource.CAMERA, MediaKind.PHOTO, 0)
    val whatsapp = MapCoverScore.of(0, MediaSource.WHATSAPP, MediaKind.PHOTO, 0)
    val screenshot = MapCoverScore.of(CellFlags.FAVORITE, MediaSource.SCREENSHOT, MediaKind.PHOTO, 0)
    assertThat(favorite).isGreaterThan(plain)
    assertThat(plain).isGreaterThan(whatsapp)
    assertThat(whatsapp).isGreaterThan(screenshot)
  }

  /** Le tessere: z0 e' il mondo; contare e toccare dicono la stessa cosa; il riquadro di una tessera contiene i suoi punti. */
  @Test
  fun leTessere() {
    assertThat(TileMath.tileOf(45_000_000, 10_000_000, 0)).isEqualTo(TileKey(0, 0, 0))
    val world = GeoBox(-180_000_000, -GeoGrid.LAT_E6, 180_000_000, GeoGrid.LAT_E6)
    assertThat(TileMath.count(world, 0, 2)).isEqualTo(1L + 4 + 16)
    val box = GeoBox(10_000_000, 44_000_000, 11_000_000, 45_000_000)
    var counted = 0L
    for (z in 0..10) {
      val side = 1 shl z
      for (x in 0 until side) for (y in 0 until side) if (TileMath.touches(TileKey(z, x, y), box)) counted++
    }
    assertThat(counted).isEqualTo(TileMath.count(box, 0, 10))
    val tile = TileMath.tileOf(43_770_000, 11_250_000, 14)
    assertThat(TileMath.boxOf(tile).contains(43_770_000, 11_250_000)).isTrue()
    assertThrows(IllegalArgumentException::class.java) { TileKey(3, 8, 0) }
    assertThrows(IllegalArgumentException::class.java) { TileKey(23, 0, 0) }
  }

  @Test
  fun leRisposteAmmesse() {
    assertThat(MapResponseRules.accepts(tile = true, "application/x-protobuf", 30_000)).isTrue()
    assertThat(MapResponseRules.accepts(tile = true, "Application/vnd.mapbox-vector-tile; charset=x", null)).isTrue()
    assertThat(MapResponseRules.accepts(tile = true, "text/html", 100)).isFalse()
    assertThat(MapResponseRules.accepts(tile = true, "application/x-protobuf", 3L * 1024 * 1024)).isFalse()
    assertThat(MapResponseRules.accepts(tile = false, "application/json", 2_000)).isTrue()
    assertThat(MapResponseRules.accepts(tile = false, null, 2_000)).isFalse()
  }

  /** D33: nessun `toString()` mostra coordinate, tessere, riquadri o regioni. */
  @Test
  fun iToStringNonMostranoNiente() {
    val point = MapPoint(MediaId.random(), 43_770_000, 11_250_000, 20250301, 10, 3_176_959)
    val shown = listOf(
      GeoE6(43_770_000, 11_250_000), GeoBox(11_250_000, 43_770_000, 11_260_000, 43_780_000), point, GeoGrid.cellOf(43_770_000, 11_250_000, 30),
      GeoCluster(GeoGrid.cellOf(43_770_000, 11_250_000, 30), GeoE6(43_770_000, 11_250_000), 3, point.id, 3_176_959),
      TileKey(14, 8_703, 5_949), CameraSpot(GeoE6(43_770_000, 11_250_000), 12.5),
      MapRequestFacts("GET", "https", "tiles.openfreemap.org", 443, "/planet/v/14/8703/5949.pbf", null),
      MapNetState(true, MapNetwork.WIFI, false, { false }, "20261020_001001_pt"),
      RegionSpec.AroundPlace(3_176_959, RegionRadius.KM_15, GeoE6(43_770_000, 11_250_000)), RegionId("around-3176959-15"),
      RegionDownloadConfirmation(RegionId("it-prova-nord"), 1_000, mobile = false, issuedAtMs = 1),
    ).map { it.toString() }
    for (text in shown) for (secret in listOf("43", "11.2", "11_25", "1125", "8703", "5949", "3176959", "openfreemap", point.id.value, "prova", "planet")) {
      assertThat(text).doesNotContain(secret)
    }
  }
}
