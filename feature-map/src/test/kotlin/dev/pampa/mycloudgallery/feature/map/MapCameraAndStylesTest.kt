package dev.pampa.mycloudgallery.feature.map

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.map.contract.CameraSpot
import dev.pampa.mycloudgallery.core.map.contract.GeoBox
import dev.pampa.mycloudgallery.core.map.contract.GeoE6
import dev.pampa.mycloudgallery.core.map.contract.GeoGrid
import dev.pampa.mycloudgallery.core.map.contract.MapCameraRules
import dev.pampa.mycloudgallery.core.map.contract.MapNetwork
import dev.pampa.mycloudgallery.core.map.contract.MapOnline
import dev.pampa.mycloudgallery.core.places.contract.PlacesAttribution
import dev.pampa.mycloudgallery.feature.map.contract.MapNotice
import dev.pampa.mycloudgallery.feature.map.map.MapCameraPolicy
import dev.pampa.mycloudgallery.feature.map.map.MapInitial
import dev.pampa.mycloudgallery.feature.map.map.MapNotices
import dev.pampa.mycloudgallery.feature.map.map.MapStyles
import dev.pampa.mycloudgallery.feature.map.map.MapViewportMath
import dev.pampa.mycloudgallery.feature.map.map.WebMercator
import java.io.File
import kotlin.random.Random
import org.junit.Test

/**
 * La camera iniziale, la vista e lo stile della mappa (Fase 9, P4; docs/fase9-progetto.md §2.10 "Apertura", D24, D29, E-MAP1, E-MAP2, E-MAP10, E-MAP16, E-MAP17). Funzioni pure: **la camera iniziale ha lo zoom
 * non oltre 5, poi vale l'ultima salvata in locale**; il riquadro con l'antimeridiano; quale stile; quale avviso; la mini-mappa **mai** con la sorgente online.
 */
class MapCameraAndStylesTest {

  // --- Camera (D29) ------------------------------------------------------------------------------------------------------------------

  @Test
  fun laPrimaVoltaTuttoLoZoomNonOltreCinque_ComunqueSiaPiccolaLaZona() {
    val random = Random(11)
    repeat(2_000) {
      val lat = random.nextInt(-80_000_000, 80_000_000)
      val lon = random.nextInt(-179_000_000, 179_000_000)
      // Da un punto solo a mezzo mondo: il riquadro di "tutte le foto".
      val spanLat = if (it % 7 == 0) 0 else random.nextInt(0, 60_000_000)
      val spanLon = if (it % 5 == 0) 0 else random.nextInt(0, 100_000_000)
      val box = GeoBox(lon, lat, (lon + spanLon).coerceAtMost(180_000_000), (lat + spanLat).coerceAtMost(80_000_000))
      val width = random.nextInt(320, 1_300)
      val height = random.nextInt(480, 900)
      val spot = MapCameraPolicy.spotFor(MapInitial.FitAll(box), width, height)
      assertThat(spot.zoom).isAtMost(MapCameraRules.FIRST_MAX_ZOOM)
      assertThat(spot.zoom).isAtLeast(0.0)
    }
  }

  @Test
  fun unaFotoSolaStaAlloZoomCinque_MaiPiuVicino() {
    val point = GeoE6(43_769_600, 11_255_800)
    val box = GeoBox(point.lonE6, point.latE6, point.lonE6, point.latE6)
    assertThat(MapCameraPolicy.spotFor(MapInitial.FitAll(box), 412, 892).zoom).isEqualTo(5.0)
  }

  @Test
  fun poiVaLUltimaPosizioneSalvataInLocale_AncheConLoZoomAlto() {
    val saved = CameraSpot(GeoE6(41_902_800, 12_496_400), 15.0)
    val box = GeoBox(0, 0, 1_000_000, 1_000_000)
    assertThat(MapCameraPolicy.initial(saved, box)).isEqualTo(MapInitial.Saved(saved))
    assertThat(MapCameraPolicy.spotFor(MapInitial.Saved(saved), 412, 892).zoom).isEqualTo(15.0)
  }

  @Test
  fun senzaNienteIlMondoESenzaFavorirePosti() {
    assertThat(MapCameraPolicy.initial(null, null)).isEqualTo(MapInitial.World)
    assertThat(MapCameraPolicy.spotFor(MapInitial.World, 412, 892).zoom).isAtMost(MapCameraRules.FIRST_MAX_ZOOM)
    // Il fuoco ("Mostra sulla mappa", E-MAP19) e' un'azione dell'utente: vince anche sull'ultima camera e puo' essere vicino.
    val focus = CameraSpot(GeoE6(59_913_900, 10_752_200), MapCameraPolicy.FOCUS_ZOOM)
    assertThat(MapCameraPolicy.initial(CameraSpot(GeoE6(1_000_000, 1_000_000), 3.0), null, focus)).isEqualTo(MapInitial.Focus(focus))
  }

  @Test
  fun ilRiquadroConLAntimeridianoEIlSuoCentro() {
    // Una vista che da 170 gradi est va a -170: attraversa l'antimeridiano (E-MAP10).
    val box = MapViewportMath.boxOf(-10.0, 170.0, 10.0, 190.0)
    assertThat(box.crossesAntimeridian).isTrue()
    assertThat(box.contains(0, 179_000_000)).isTrue()
    assertThat(box.contains(0, -179_000_000)).isTrue()
    assertThat(box.contains(0, 0)).isFalse()
    assertThat(MapViewportMath.centerOf(box).lonE6).isEqualTo(180_000_000)
    // Piu' di un giro del mondo: tutto il mondo, senza attraversare niente.
    val world = MapViewportMath.boxOf(-90.0, -300.0, 90.0, 300.0)
    assertThat(world.crossesAntimeridian).isFalse()
    assertThat(world.contains(0, 0)).isTrue()
    assertThat(world.contains(0, 179_999_999)).isTrue()
    // Oltre i poli la latitudine si ferma al limite della proiezione.
    assertThat(world.northE6).isEqualTo(GeoGrid.LAT_E6)
  }

  @Test
  fun laProiezioneEReversibile() {
    val random = Random(5)
    repeat(500) {
      val lat = random.nextDouble(-80.0, 80.0)
      val lon = random.nextDouble(-179.0, 179.0)
      val zoom = random.nextDouble(0.0, 18.0)
      assertThat(WebMercator.lat(WebMercator.y(lat, zoom), zoom)).isWithin(1e-7).of(lat)
      assertThat(WebMercator.lon(WebMercator.x(lon, zoom), zoom)).isWithin(1e-7).of(lon)
    }
  }

  // --- Stile e avvisi (§2.8, §2.10, E-MAP1, E-MAP2) ----------------------------------------------------------------------------------

  @Test
  fun loStileOnlineSeLaMappaDettagliataEAccesaOCeUnaRegione_OffloneAltrimenti() {
    val on = MapOnline.On(byDefault = true)
    assertThat(MapStyles.choose(on, hasRegions = false, dark = false).offline).isFalse()
    assertThat(MapStyles.choose(MapOnline.Off, hasRegions = true, dark = false).offline).isFalse()
    // Spenta e nessuna regione: lo stile offline, cosi' non c'e' mai la terra senza mare sopra z8.
    val off = MapStyles.choose(MapOnline.Off, hasRegions = false, dark = true)
    assertThat(off.offline).isTrue()
    assertThat(off.asset).isEqualTo("style-scuro-offline.json")
    assertThat(off.maxZoom).isEqualTo(9.0)
    assertThat(MapStyles.choose(MapOnline.Unavailable, hasRegions = false, dark = false).asset).isEqualTo("style-chiaro-offline.json")
    assertThat(MapStyles.choose(on, hasRegions = false, dark = false).uri).isEqualTo("asset://map/style-chiaro.json")
  }

  @Test
  fun ilCreditoDellaCartaEQuelloGiusto_UnaScrittaMaiUnCollegamento() {
    assertThat(MapStyles.choose(MapOnline.On(byDefault = true), false, false).attribution).isEqualTo(PlacesAttribution.MAP_ONLINE_SHORT)
    assertThat(MapStyles.choose(MapOnline.Off, false, false).attribution).isEqualTo(PlacesAttribution.MAP_BASE_SHORT)
    assertThat(MapStyles.miniMap(dark = false).attribution).isEqualTo(PlacesAttribution.MAP_BASE_SHORT)
  }

  @Test
  fun leMiniMappeUsanoSoloLoStileOffline_ELoStileNonHaLaSorgenteOnline() {
    for (dark in listOf(false, true)) {
      val style = MapStyles.miniMap(dark)
      assertThat(style.offline).isTrue()
      assertThat(style.asset).endsWith("-offline.json")
      val text = File("src/main/assets/map/${style.asset}").readText()
      // Nessuna sorgente vettoriale con un indirizzo, nessun indirizzo di rete in tutto lo stile: la mini-mappa non puo' fare richieste.
      assertThat(text).doesNotContain("http")
      assertThat(text).doesNotContain("openfreemap")
      assertThat(text).doesNotContain("\"tiles\"")
      assertThat(text).doesNotContain("\"type\": \"vector\"")
      assertThat(text).doesNotContain("\"type\":\"vector\"")
      assertThat(text).contains("\"mcg:online\":false")
    }
    assertThat(MapStyles.ALL_FILES.all { File("src/main/assets/map/$it").isFile }).isTrue()
  }

  @Test
  fun gliAvvisiSonoUnaRigaDiscreta() {
    val on = MapOnline.On(byDefault = true)
    assertThat(MapNotices.of(MapOnline.Off, wifiOnly = true, network = MapNetwork.WIFI)).isEqualTo(MapNotice.BASE_ONLY)
    assertThat(MapNotices.of(on, wifiOnly = true, network = MapNetwork.MOBILE)).isEqualTo(MapNotice.WIFI_ONLY)
    assertThat(MapNotices.of(on, wifiOnly = false, network = MapNetwork.MOBILE)).isNull()
    assertThat(MapNotices.of(on, wifiOnly = true, network = MapNetwork.ROAMING)).isEqualTo(MapNotice.OFFLINE)
    assertThat(MapNotices.of(on, wifiOnly = false, network = MapNetwork.NONE)).isEqualTo(MapNotice.OFFLINE)
    assertThat(MapNotices.of(on, wifiOnly = true, network = MapNetwork.WIFI)).isNull()
    // L'interruttore di build spento: nessuna riga che offra la mappa dettagliata.
    assertThat(MapNotices.of(MapOnline.Unavailable, wifiOnly = true, network = MapNetwork.WIFI)).isNull()
  }
}
