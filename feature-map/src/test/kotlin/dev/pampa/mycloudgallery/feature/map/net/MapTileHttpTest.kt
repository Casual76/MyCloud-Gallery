package dev.pampa.mycloudgallery.feature.map.net

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.map.contract.MapHost
import dev.pampa.mycloudgallery.core.map.contract.MapNetwork
import dev.pampa.mycloudgallery.core.map.contract.RefuseReason
import dev.pampa.mycloudgallery.core.map.contract.RegionDownloadScope
import java.io.File
import java.nio.file.Files
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Il client delle tessere con il cancello vero e il server finto dentro l'intercettore (Fase 9, P1; `docs/fase9-progetto.md` §2.8, §5.5 riga
 * "JVM"; E-MAP1-E-MAP4, E-MAP13). Robolectric solo per `org.json` (il TileJSON): nessuna rete, nessun socket (il server finto non chiama mai
 * `proceed()`). "Il server ha visto" = la richiesta e' uscita dal cancello.
 */
@RunWith(RobolectricTestRunner::class)
class MapTileHttpTest {

  private val v = "20261022_001001_pt"

  /** E-MAP1: a mappa online spenta nessuna richiesta esce, nemmeno il TileJSON; MapLibre riceve "rete assente" (riprova quando torna). */
  @Test
  fun aMappaSpentaNienteEsce() {
    val rig = TileRig().apply { online = false }
    val tile = rig.tile(10, 544, 370)
    val json = rig.get("https://${MapHost.TILES}/planet")
    assertThat(tile.exceptionOrNull()).isInstanceOf(MapRequestHeld::class.java)
    assertThat(json.exceptionOrNull()).isInstanceOf(MapRequestHeld::class.java)
    assertThat(rig.server.seen).isEmpty()
    assertThat(rig.http.snapshot().refused[RefuseReason.OFF]).isEqualTo(2)
    assertThat(rig.http.snapshot().allowed).isEqualTo(0)
  }

  /** E-MAP2, E-R3, E-R13: roaming, nessuna rete, dati mobili con "Solo sul Wi-Fi": niente; dati mobili permessi: si'. */
  @Test
  fun laReteDecide() {
    for (net in listOf(MapNetwork.ROAMING, MapNetwork.NONE, MapNetwork.MOBILE)) {
      val rig = TileRig().apply { network = net }
      assertWithMessage(net.code).that(rig.tile(5, 17, 11).exceptionOrNull()).isInstanceOf(MapRequestHeld::class.java)
      assertWithMessage(net.code).that(rig.server.seen).isEmpty()
      assertThat(rig.http.snapshot().refused[RefuseReason.NETWORK]).isEqualTo(1)
    }
    val mobile = TileRig().apply { network = MapNetwork.MOBILE; wifiOnly = false }
    assertThat(mobile.tile(5, 17, 11).getOrThrow().code).isEqualTo(200)
    assertThat(mobile.traffic.bytes).isEmpty() // i byte veri li conta l'intercettore di rete, che il server finto non raggiunge
  }

  /** D22, E-MAP13: la prima tessera fa chiedere il TileJSON una volta; poi ogni tessera esce sul percorso della versione, senza query. */
  @Test
  fun laTesseraEsceSullaVersioneCorrente() {
    val rig = TileRig()
    val first = rig.tile(14, 8703, 5949).getOrThrow()
    assertThat(first.code).isEqualTo(200)
    assertThat(first.body!!.string()).isEqualTo("tile")
    assertThat(first.request.url.encodedPath).isEqualTo("/planet/v/14/8703/5949.pbf") // MapLibre vede il suo URL stabile
    rig.tile(14, 8704, 5949).getOrThrow().close()
    rig.tile(3, 4, 2).getOrThrow().close()
    assertThat(rig.server.seen.map { it.path }).containsExactly(
      "/planet", "/planet/$v/14/8703/5949.pbf", "/planet/$v/14/8704/5949.pbf", "/planet/$v/3/4/2.pbf",
    ).inOrder()
    assertThat(rig.server.seen.all { it.url.startsWith("https://${MapHost.TILES}/planet") && it.query == null }).isTrue()
    val counts = rig.http.snapshot()
    assertThat(counts.allowed).isEqualTo(3)
    assertThat(counts.byZoom[14]).isEqualTo(2)
    assertThat(counts.byZoom[3]).isEqualTo(1)
    assertThat(counts.tileJsonFetches).isEqualTo(1)
  }

  /** §2.8, I1: degli header di MapLibre (e di chiunque) esce solo la data della validazione della cache (mai `If-None-Match`); il `User-Agent` e' quello fisso. */
  @Test
  fun gliHeaderSonoRipuliti() {
    val rig = TileRig()
    rig.tile(
      9, 271, 186,
      headers = mapOf(
        "Cookie" to "sid=1", "Referer" to "https://example.org/", "Range" to "bytes=0-10", "Authorization" to "Bearer x",
        "X-Device-Id" to "abc", "Accept-Language" to "it-IT", "Origin" to "app://x", "If-None-Match" to "\"etag-1\"",
        "If-Modified-Since" to "Wed, 21 Oct 2026 07:28:00 GMT",
      ),
    ).getOrThrow().close()
    for (seen in rig.server.seen) {
      val names = seen.headers.names().map { it.lowercase() }.toSet()
      assertWithMessage(seen.path).that(names - setOf("user-agent", "if-modified-since")).isEmpty()
      assertThat(seen.headers["User-Agent"]).isEqualTo(MapTileHeaders.USER_AGENT)
    }
    val tile = rig.server.tiles().single()
    assertThat(tile.headers["If-Modified-Since"]).isEqualTo("Wed, 21 Oct 2026 07:28:00 GMT")
    // I1: un `If-None-Match` (un valore scelto dal server, che tornerebbe a ogni convalida) non esce mai, ne' con le tessere ne' con il TileJSON.
    assertThat(rig.server.seen.none { it.headers["If-None-Match"] != null }).isTrue()
    // Un valore troppo lungo non esce nemmeno nella validazione della cache.
    rig.tile(9, 272, 186, headers = mapOf("If-Modified-Since" to "x".repeat(300))).getOrThrow().close()
    assertThat(rig.server.tiles().last().headers["If-Modified-Since"]).isNull()
  }

  /** D26 e condizioni d'uso di OpenFreeMap: le tessere di uno scaricamento confermato escono con il `User-Agent` dello scaricamento. */
  @Test
  fun loScaricamentoSiPresentaComeTale() {
    val rig = TileRig().apply { online = false; download = RegionDownloadScope({ it.z == 8 }, mobileConfirmed = false) }
    rig.tile(8, 136, 93).getOrThrow().close()
    assertThat(rig.server.tiles().single().headers["User-Agent"]).isEqualTo(MapTileHeaders.USER_AGENT_DOWNLOAD)
    assertThat(rig.server.tileJsonRequests().single().headers["User-Agent"]).isEqualTo(MapTileHeaders.USER_AGENT)
    assertThat(rig.tile(9, 272, 186).exceptionOrNull()).isInstanceOf(MapRequestHeld::class.java)
  }

  /** D22: un altro host, un altro schema, un altro metodo, un percorso fuori dalle regole: rifiutati per sempre, senza uscire. */
  @Test
  fun soloLUnicoHostEIPercorsiAmmessi() {
    val rig = TileRig()
    val refusedHost = listOf(
      "https://tiles.openfreemap.org.example/planet/v/1/0/0.pbf", "https://openfreemap.org/planet", "https://127.0.0.1/planet",
      "https://tiles.openfreemap.org:8443/planet", "http://tiles.openfreemap.org/planet", "https://u:p@tiles.openfreemap.org/planet",
      "https://tiles.openfreemap.org./planet",
    )
    for (url in refusedHost) {
      val e = rig.get(url).exceptionOrNull()
      assertWithMessage(url).that(e).isInstanceOf(MapRequestRefused::class.java)
      assertWithMessage(url).that((e as MapRequestRefused).reason).isEqualTo(RefuseReason.HOST)
    }
    assertThat((rig.get("https://${MapHost.TILES}/planet", method = "POST").exceptionOrNull() as MapRequestRefused).reason).isEqualTo(RefuseReason.HOST)
    val refusedPath = listOf(
      "/styles/positron", "/fonts/Noto%20Sans%20Regular/0-255.pbf", "/planet/v/15/0/0.pbf", "/planet/v/2/4/0.pbf", "/planet/$v/1/0/0.pbf",
      "/planet/latest/1/0/0.pbf", "/planet/v/1/0/0.pbf?key=1", "/planet/v/1/%30/0.pbf", "/planet/v/01/0/0.pbf", "/natural_earth/ne2sr/1/0/0.png",
    )
    for (path in refusedPath) {
      val e = rig.get("https://${MapHost.TILES}$path").exceptionOrNull()
      assertWithMessage(path).that((e as MapRequestRefused).reason).isEqualTo(RefuseReason.PATH)
    }
    assertThat(rig.server.seen).isEmpty()
  }

  /** D26: una tessera di una regione completa non si chiede mai online (MapLibre usa quella del suo database). */
  @Test
  fun leTessereCoperteNonEscono() {
    val rig = TileRig().apply { covered = { it.z == 12 && it.x in 2170..2180 } }
    assertThat((rig.tile(12, 2175, 1487).exceptionOrNull() as MapRequestHeld).reason).isEqualTo(RefuseReason.COVERED)
    rig.tile(12, 2181, 1487).getOrThrow().close()
    assertThat(rig.server.tiles().map { it.path }).containsExactly("/planet/$v/12/2181/1487.pbf")
    assertThat(rig.http.snapshot().refused[RefuseReason.COVERED]).isEqualTo(1)
  }

  /** E-MAP4: un redirect verso un altro host (o fuori dalle regole) si rifiuta; verso lo stesso host e un percorso ammesso si segue a mano. */
  @Test
  fun iRedirect() {
    val rig = TileRig()
    rig.server.answer("/planet/$v/1/0/0.pbf") { FakeTileServer.status(it, 302, mapOf("Location" to "https://evil.example/planet/$v/1/0/0.pbf")) }
    rig.server.answer("/planet/$v/1/1/0.pbf") { FakeTileServer.status(it, 301, mapOf("Location" to "/planet/v/1/1/1.pbf")) }
    rig.server.answer("/planet/$v/1/0/1.pbf") { FakeTileServer.status(it, 307, mapOf("Location" to "/planet/$v/1/0/1.pbf")) }
    rig.server.answer("/planet/$v/2/0/0.pbf") { FakeTileServer.status(it, 308, mapOf("Location" to "http://${MapHost.TILES}/planet/v/2/0/0.pbf")) }
    rig.server.answer("/planet/$v/2/1/0.pbf") { FakeTileServer.status(it, 302, mapOf("Location" to "/planet/v/2/1/1.pbf")) }
    rig.server.answer("/planet/$v/2/1/1.pbf") { FakeTileServer.status(it, 302, mapOf("Location" to "/planet/v/2/1/2.pbf")) }
    rig.server.answer("/planet/$v/2/1/2.pbf") { FakeTileServer.status(it, 302, mapOf("Location" to "/planet/v/2/1/3.pbf")) }

    assertThat((rig.tile(1, 0, 0).exceptionOrNull() as MapRequestRefused).reason).isEqualTo(RefuseReason.REDIRECT)
    assertThat(rig.tile(1, 1, 0).getOrThrow().code).isEqualTo(200) // seguito: stesso host, percorso ammesso (riscritto sulla versione)
    assertThat((rig.tile(1, 0, 1).exceptionOrNull() as MapRequestRefused).reason).isEqualTo(RefuseReason.REDIRECT) // percorso versionato
    assertThat((rig.tile(2, 0, 0).exceptionOrNull() as MapRequestRefused).reason).isEqualTo(RefuseReason.REDIRECT) // in chiaro
    assertThat((rig.tile(2, 1, 0).exceptionOrNull() as MapRequestRefused).reason).isEqualTo(RefuseReason.REDIRECT) // troppi salti
    assertThat(rig.server.seen.none { it.url.contains("evil") || it.url.startsWith("http:") }).isTrue()
    assertThat(rig.server.seen.map { it.path }).contains("/planet/$v/1/1/1.pbf")
    assertThat(rig.http.snapshot().refused[RefuseReason.REDIRECT]).isEqualTo(4)
  }

  /** E-MAP4, I1: tipo inatteso o risposta enorme dopo la decompressione: buttata e contata; gli header della risposta sono solo quelli della cache, senza `ETag`. */
  @Test
  fun leRisposteSonoControllate() {
    val rig = TileRig()
    rig.server.answer("/planet/$v/4/0/0.pbf") { FakeTileServer.ok(it, "<html>", "text/html") }
    rig.server.answer("/planet/$v/4/0/1.pbf") { FakeTileServer.bytes(it, ByteArray(MapGateInterceptor.MAX_DECODED_BYTES.toInt() + 1), "application/x-protobuf") }
    rig.server.answer("/planet/$v/4/0/2.pbf") {
      FakeTileServer.ok(it, "t", "application/x-protobuf", mapOf("Set-Cookie" to "a=b", "ETag" to "\"e\"", "Last-Modified" to "Wed, 21 Oct 2026 07:28:00 GMT", "X-Tracking" to "1", "Cache-Control" to "max-age=60"))
    }
    assertThat((rig.tile(4, 0, 0).exceptionOrNull() as MapRequestRefused).reason).isEqualTo(RefuseReason.CONTENT)
    assertThat((rig.tile(4, 0, 1).exceptionOrNull() as MapRequestRefused).reason).isEqualTo(RefuseReason.CONTENT)
    val ok = rig.tile(4, 0, 2).getOrThrow()
    assertThat(ok.headers.names().map { it.lowercase() }.toSet()).containsExactly("content-type", "last-modified", "cache-control")
    assertThat(ok.body!!.string()).isEqualTo("t")
    // Un TileJSON che non e' JSON non si accetta come tessera e viceversa.
    rig.server.answer("/planet/$v/4/0/3.pbf") { FakeTileServer.ok(it, "{}", "application/json") }
    assertThat((rig.tile(4, 0, 3).exceptionOrNull() as MapRequestRefused).reason).isEqualTo(RefuseReason.CONTENT)
    assertThat(rig.http.snapshot().refused[RefuseReason.CONTENT]).isEqualTo(3)
  }

  /** E-MAP3: un 5xx (o una rete che non risponde) ferma tutto per un'attesa crescente; poi si riprende; un successo azzera l'attesa. */
  @Test
  fun dopoUnErroreSiAspetta() {
    val rig = TileRig()
    rig.tile(6, 0, 0).getOrThrow().close() // versione nota
    rig.server.everything = { FakeTileServer.status(it, 503) }
    assertThat(rig.tile(6, 1, 0).getOrThrow().code).isEqualTo(503)
    val before = rig.server.seen.size
    assertThat(rig.tile(6, 2, 0).exceptionOrNull()).isInstanceOf(MapRequestHeld::class.java)
    assertThat(rig.server.seen.size).isEqualTo(before)
    rig.clock.ms += 2_000
    assertThat(rig.tile(6, 2, 0).getOrThrow().code).isEqualTo(503) // seconda: ora l'attesa e' di 4 s
    rig.clock.ms += 3_000
    assertThat(rig.tile(6, 3, 0).exceptionOrNull()).isInstanceOf(MapRequestHeld::class.java)
    rig.server.everything = null
    rig.clock.ms += 1_000
    assertThat(rig.tile(6, 3, 0).getOrThrow().code).isEqualTo(200)
    assertThat(rig.tile(6, 4, 0).getOrThrow().code).isEqualTo(200) // nessuna attesa dopo il successo
    val counts = rig.http.snapshot()
    assertThat(counts.errors).isEqualTo(2)
    assertThat(counts.paused).isEqualTo(2)
  }

  /** D22: un TileJSON ostile non mette niente in un percorso; nessuna tessera esce senza versione; il TileJSON non si richiede a raffica. */
  @Test
  fun unTileJsonOstileNonEntra() {
    for (bad in listOf(
      """{"tiles":["https://evil.example/planet/x/{z}/{x}/{y}.pbf"]}""",
      """{"tiles":["https://tiles.openfreemap.org/planet/../../x/{z}/{x}/{y}.pbf"]}""",
      """{"tiles":["https://tiles.openfreemap.org/planet/a b/{z}/{x}/{y}.pbf"]}""",
      """{"tiles":["https://tiles.openfreemap.org/planet/ok/{z}/{x}/{y}.pbf?k=1"]}""",
      """{"tiles":[]}""", """{"tiles":"x"}""", """[1,2]""", """not json""", "",
    )) {
      val rig = TileRig()
      rig.server.answer("/planet") { FakeTileServer.ok(it, bad, "application/json") }
      assertWithMessage(bad).that(rig.tile(7, 1, 1).exceptionOrNull()).isInstanceOf(MapRequestHeld::class.java)
      assertWithMessage(bad).that(rig.tile(7, 1, 2).exceptionOrNull()).isInstanceOf(MapRequestHeld::class.java)
      assertWithMessage(bad).that(rig.server.tiles()).isEmpty()
      assertWithMessage(bad).that(rig.server.tileJsonRequests()).hasSize(1)
      rig.clock.ms += 10L * 60 * 1000
      rig.tile(7, 1, 3)
      assertWithMessage(bad).that(rig.server.tileJsonRequests()).hasSize(2)
      assertThat(rig.http.snapshot().noVersion).isEqualTo(3)
    }
  }

  /** D22: la versione si ricorda su file; si rilegge dopo 24 ore, dopo un riavvio, o un'ora dopo un 404 di una tessera. */
  @Test
  fun laVersioneSiRicordaESiRinnova() {
    val dir = Files.createTempDirectory("mapnet").toFile()
    val file = File(dir, "map/${MapTileHttp.STATE_FILE}")
    val clock = TestClock(boot = 3, ms = 50_000)
    TileRig(clock = clock, stateFile = file).tile(5, 1, 1).getOrThrow().close()
    assertThat(file.readText()).startsWith("mcg-tilejson-1\n$v\n3\n")

    val again = TileRig(clock = clock, stateFile = file)
    again.tile(5, 1, 2).getOrThrow().close()
    assertThat(again.server.tileJsonRequests()).isEmpty()

    clock.ms += 24L * 60 * 60 * 1000
    val nextDay = TileRig(server = FakeTileServer(version = "20261029_001001_pt"), clock = clock, stateFile = file)
    nextDay.tile(5, 1, 3).getOrThrow().close()
    assertThat(nextDay.server.seen.map { it.path }).containsExactly("/planet", "/planet/20261029_001001_pt/5/1/3.pbf").inOrder()

    val afterReboot = TileRig(clock = TestClock(boot = 4, ms = 10), stateFile = file)
    afterReboot.tile(5, 1, 4).getOrThrow().close()
    assertThat(afterReboot.server.tileJsonRequests()).hasSize(1)

    val gone = TileRig(clock = TestClock(boot = 4, ms = 20), stateFile = file)
    gone.server.answer("/planet/$v/5/1/5.pbf") { FakeTileServer.status(it, 404) }
    assertThat(gone.tile(5, 1, 5).getOrThrow().code).isEqualTo(404)
    gone.tile(5, 1, 6).getOrThrow().close()
    assertThat(gone.server.tileJsonRequests()).isEmpty()
    gone.clock.ms += 60L * 60 * 1000
    gone.tile(5, 1, 7).getOrThrow().close()
    assertThat(gone.server.tileJsonRequests()).hasSize(1)
    dir.deleteRecursively()
  }

  /** Un file della versione rovinato (o di un altro formato) vale "nessuna versione": si chiede il TileJSON, mai un percorso strano. */
  @Test
  fun unFileRovinatoNonEntraInUnPercorso() {
    val dir = Files.createTempDirectory("mapnet").toFile()
    val file = File(dir, MapTileHttp.STATE_FILE)
    for (content in listOf("mcg-tilejson-1\n../x\n1\n1\n", "altro\n$v\n1\n1\n", "mcg-tilejson-1\n$v\nx\n1\n", "")) {
      file.writeText(content)
      val rig = TileRig(clock = TestClock(boot = 1, ms = 2), stateFile = file)
      rig.tile(5, 2, 2).getOrThrow().close()
      assertWithMessage(content).that(rig.server.seen.map { it.path }).containsExactly("/planet", "/planet/$v/5/2/2.pbf").inOrder()
    }
    dir.deleteRecursively()
  }
}
