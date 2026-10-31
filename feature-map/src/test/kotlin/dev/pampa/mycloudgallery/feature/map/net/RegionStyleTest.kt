package dev.pampa.mycloudgallery.feature.map.net

import android.app.Application
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.map.contract.MapHost
import dev.pampa.mycloudgallery.core.map.contract.RefuseReason
import dev.pampa.mycloudgallery.feature.map.assets.MapAssets
import java.net.UnknownHostException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Lo stile con cui si scarica una regione (Fase 9, P5): ha **solo** la sorgente delle tessere, con lo stesso modello di URL degli stili veri (cosi' le tessere nel database di MapLibre sono quelle che la mappa cerca) e
 * nessun'altra risorsa; e **lo serve il cancello da se'**, da un indirizzo che non esiste: il server finto non vede niente, nessun DNS, nessun socket.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RegionStyleTest {

  private val style = MapAssets.obj(dev.pampa.mycloudgallery.feature.map.assets.MiniJson.parse(RegionStyleSource.JSON))

  @Test
  fun hasolaSorgenteDelleTessereDellURLStabile() {
    val sources = MapAssets.obj(style["sources"])
    assertThat(sources.keys).containsExactly("openmaptiles")
    val omt = MapAssets.obj(sources["openmaptiles"])
    assertThat(omt["type"]).isEqualTo("vector")
    assertThat(MapAssets.list(omt["tiles"])).containsExactly("https://tiles.openfreemap.org/planet/v/{z}/{x}/{y}.pbf")
    assertThat(omt["minzoom"]).isEqualTo(0.0)
    assertThat(omt["maxzoom"]).isEqualTo(14.0)
    assertThat(style.keys).containsNoneOf("glyphs", "sprite")
    assertThat(MapAssets.list(style["layers"])).hasSize(1)
  }

  /** Il modello d'URL e' la chiave con cui MapLibre cerca le tessere nel database: uguale a quello degli stili veri (e la sorgente ha gli stessi livelli). */
  @Test
  fun laSorgenteEUgualeAQuelladegliStiliVeri() {
    val own = MapAssets.obj(MapAssets.obj(style["sources"])["openmaptiles"])
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
    val urls = MapAssets.strings(style).filter { it.startsWith("http") || it.startsWith("asset://") }.toList()
    assertThat(urls).containsExactly("https://tiles.openfreemap.org/planet/v/{z}/{x}/{y}.pbf")
  }

  /** Il cancello serve lo stile da se': 200, JSON, e il server finto non vede niente (nessuna richiesta esce). */
  @Test
  fun ilCancelloLoServeDaSeSenzaUscire() {
    val rig = TileRig().apply { online = false }
    val response = rig.get(RegionStyleSource.URL).getOrThrow()
    assertThat(response.code).isEqualTo(200)
    assertThat(response.header("Content-Type")).isEqualTo("application/json")
    // Una scadenza lontana: con una immediata MapLibre ricontrolla lo stile durante lo scaricamento e rimette in coda le tessere (le richieste uscirebbero due volte).
    assertThat(response.header("Cache-Control")).isEqualTo("public, max-age=315360000")
    assertThat(response.body!!.string()).isEqualTo(RegionStyleSource.JSON)
    assertThat(rig.server.seen).isEmpty()
    assertThat(rig.http.snapshot().allowed).isEqualTo(0)
    assertThat(rig.http.snapshot().tileJsonFetches).isEqualTo(0)
  }

  /** Su quell'host non si chiede altro: un altro percorso, una query o un altro metodo sono rifiutati (`HOST`), e il DNS non risolve mai quel nome. */
  @Test
  fun suQuellHostSiChiedeSoloLoStile() {
    val rig = TileRig()
    for (url in listOf("https://${RegionStyleSource.HOST}/altro.json", "https://${RegionStyleSource.HOST}/stile.json?x=1", "https://${RegionStyleSource.HOST}/")) {
      val e = rig.get(url).exceptionOrNull()
      assertThat(e).isInstanceOf(MapRequestRefused::class.java)
      assertThat((e as MapRequestRefused).reason).isEqualTo(RefuseReason.HOST)
    }
    assertThat(rig.get(RegionStyleSource.URL, method = "POST").exceptionOrNull()).isInstanceOf(MapRequestRefused::class.java)
    assertThat(rig.server.seen).isEmpty()
    assertThat(RegionStyleSource.HOST).endsWith(".invalid")
    assertThat(runCatching { OnlyMapHostDns().lookup(RegionStyleSource.HOST) }.exceptionOrNull()).isInstanceOf(UnknownHostException::class.java)
    assertThat(MapHost.TILES).isNotEqualTo(RegionStyleSource.HOST)
  }
}
