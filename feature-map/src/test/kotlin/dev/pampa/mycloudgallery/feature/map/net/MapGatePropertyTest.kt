package dev.pampa.mycloudgallery.feature.map.net

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.map.contract.MapHost
import dev.pampa.mycloudgallery.core.map.contract.MapNetwork
import dev.pampa.mycloudgallery.core.map.contract.RegionDownloadScope
import java.util.Random
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * La proprieta' del client intero (P1, §5.5 riga "JVM"): 20.000 richieste casuali e ostili, con stati casuali (mappa spenta, roaming, dati
 * mobili, tessere coperte, scaricamenti), attraverso il client vero fino al server finto. Ogni richiesta che il server vede rispetta **tutte** le
 * regole del §2.8 (host, schema, metodo, percorso versionato o TileJSON, nessuna query, soli header ammessi, `User-Agent` fisso); a mappa spenta
 * fuori da uno scaricamento, in roaming o senza rete il server non vede niente; nessuna eccezione che non sia un `IOException` arriva a MapLibre.
 */
@RunWith(RobolectricTestRunner::class)
class MapGatePropertyTest {

  private val versioned = Regex("^/planet/20261022_001001_pt/(0|[1-9][0-9]?)/(0|[1-9][0-9]{0,4})/(0|[1-9][0-9]{0,4})\\.pbf$")

  @Test
  fun ventimilaRichiesteOstiliNessunaEsceFuoriDalleRegole() {
    val random = Random(2026_10_30)
    val rig = TileRig()
    val hosts = listOf(MapHost.TILES, MapHost.TILES, MapHost.TILES, "tiles.openfreemap.org.example", "openfreemap.org", "127.0.0.1", "a.tiles.openfreemap.org")
    val schemes = listOf("https", "https", "https", "http")
    var reached = 0
    repeat(20_000) {
      rig.online = random.nextInt(4) != 0
      rig.network = MapNetwork.entries[random.nextInt(MapNetwork.entries.size)]
      rig.wifiOnly = random.nextBoolean()
      val parity = random.nextInt(3)
      rig.covered = { parity != 0 && (it.x + parity) % 2 == 0 }
      rig.download = if (random.nextInt(8) == 0) RegionDownloadScope({ it.y % 2 == 0 }, random.nextBoolean()) else null
      val z = random.nextInt(18) - 1
      val side = if (z in 0..30) 1L shl z else 1L
      val x = if (random.nextInt(6) == 0) side else (random.nextLong() and Long.MAX_VALUE) % side
      val y = if (random.nextInt(6) == 0) -1 else (random.nextLong() and Long.MAX_VALUE) % side
      val path = when (random.nextInt(10)) {
        0 -> "/planet"
        1 -> "/planet/v/$z/$x/$y.pbf?x=1"
        2 -> "/planet/v/0$z/$x/$y.pbf"
        3 -> "/fonts/a/0-255.pbf"
        else -> "/planet/v/$z/$x/$y.pbf"
      }
      val port = if (random.nextInt(12) == 0) ":8443" else ""
      val url = "${schemes[random.nextInt(schemes.size)]}://${hosts[random.nextInt(hosts.size)]}$port$path".toHttpUrlOrNull() ?: return@repeat
      val blocked = rig.network == MapNetwork.ROAMING || rig.network == MapNetwork.NONE || (!rig.online && rig.download == null)
      val before = rig.server.seen.size
      val result = rig.get(url.toString(), headers = mapOf("Cookie" to "c=1", "Referer" to "https://x.example/"))
      val error = result.exceptionOrNull()
      assertWithMessage("non IOException: $error").that(error == null || error is java.io.IOException).isTrue()
      result.getOrNull()?.close()
      val after = rig.server.seen.size
      if (blocked) assertWithMessage("uscita a mappa spenta o senza rete: $url").that(after).isEqualTo(before)
      reached += after - before
    }
    for (seen in rig.server.seen) {
      val url = seen.url.toHttpUrlOrNull()!!
      assertThat(seen.method).isEqualTo("GET")
      assertThat(url.scheme).isEqualTo("https")
      assertThat(url.host).isEqualTo(MapHost.TILES)
      assertThat(url.port).isEqualTo(443)
      assertThat(url.query).isNull()
      assertWithMessage(seen.path).that(seen.path == "/planet" || versioned.matches(seen.path)).isTrue()
      versioned.matchEntire(seen.path)?.let { assertThat(it.groupValues[1].toInt()).isAtMost(14) }
      assertThat(seen.headers.names().map { it.lowercase() }.toSet() - setOf("user-agent", "if-none-match", "if-modified-since")).isEmpty()
      assertThat(seen.headers["User-Agent"]).isAnyOf(MapTileHeaders.USER_AGENT, MapTileHeaders.USER_AGENT_DOWNLOAD)
    }
    assertThat(reached).isGreaterThan(300)
  }
}
