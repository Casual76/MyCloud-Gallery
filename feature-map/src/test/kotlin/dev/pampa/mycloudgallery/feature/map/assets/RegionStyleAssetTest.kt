package dev.pampa.mycloudgallery.feature.map.assets

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Lo stile con cui si scarica una regione (Fase 9, P5): `style-regione.json` ha **solo** la sorgente delle tessere, con lo stesso modello di URL degli stili veri (cosi' le tessere nel database di MapLibre sono quelle
 * che la mappa cerca) e nessun'altra risorsa: niente glifi, sprite o GeoJSON da scaricare, nessun altro host.
 */
class RegionStyleAssetTest {

  private val region = MapAssets.obj(MapAssets.json("style-regione.json"))

  @Test
  fun hasolaSorgenteDelleTessereDellURLStabile() {
    val sources = MapAssets.obj(region["sources"])
    assertThat(sources.keys).containsExactly("openmaptiles")
    val omt = MapAssets.obj(sources["openmaptiles"])
    assertThat(omt["type"]).isEqualTo("vector")
    assertThat(MapAssets.list(omt["tiles"])).containsExactly("https://tiles.openfreemap.org/planet/v/{z}/{x}/{y}.pbf")
    assertThat(omt["minzoom"]).isEqualTo(0.0)
    assertThat(omt["maxzoom"]).isEqualTo(14.0)
    assertThat(region.keys).containsNoneOf("glyphs", "sprite")
  }

  @Test
  fun laSorgenteEUgualeAQuelladegliStiliVeri() {
    val own = MapAssets.obj(MapAssets.obj(region["sources"])["openmaptiles"])
    for (name in listOf("style-chiaro.json", "style-scuro.json")) {
      val other = MapAssets.obj(MapAssets.obj(MapAssets.obj(MapAssets.json(name))["sources"])["openmaptiles"])
      assertThat(own["tiles"]).isEqualTo(other["tiles"])
      assertThat(own["minzoom"]).isEqualTo(other["minzoom"])
      assertThat(own["maxzoom"]).isEqualTo(other["maxzoom"])
      assertThat(own["type"]).isEqualTo(other["type"])
    }
  }

  @Test
  fun nessunUrlFuoriDallURLStabileDelleTessere() {
    val urls = MapAssets.strings(region).filter { it.startsWith("http") || it.startsWith("asset://") }.toList()
    assertThat(urls).containsExactly("https://tiles.openfreemap.org/planet/v/{z}/{x}/{y}.pbf")
  }
}
