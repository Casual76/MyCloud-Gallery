package dev.pampa.mycloudgallery.feature.map.net

import dev.pampa.mycloudgallery.core.map.contract.MapHost
import dev.pampa.mycloudgallery.core.map.contract.MapNetState
import dev.pampa.mycloudgallery.core.map.contract.MapNetwork
import dev.pampa.mycloudgallery.core.map.contract.RegionDownloadScope
import dev.pampa.mycloudgallery.core.map.contract.TileKey
import dev.pampa.mycloudgallery.core.map.policy.TilePolicy
import dev.pampa.mycloudgallery.core.map.testing.RecordingMapTraffic
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Il server finto delle tessere (P1, §5.5 riga "JVM"; regola del brief: mai Internet nei test). E' l'**ultimo intercettore applicativo** del
 * client: risponde lui e non chiama mai `proceed()`, quindi nessun DNS e nessun socket; registra ogni richiesta che gli arriva (cioe' ogni
 * richiesta che il cancello ha lasciato uscire), con gli header che il cancello le ha lasciato. Di serie risponde come OpenFreeMap (TileJSON con
 * l'URL versionato, tessere `application/vnd.mapbox-vector-tile`); [answer] cambia la risposta per un percorso.
 */
internal class FakeTileServer(val version: String = "20261022_001001_pt") : Interceptor {

  class Seen(val method: String, val url: String, val path: String, val query: String?, val headers: Headers)

  val seen: MutableList<Seen> = CopyOnWriteArrayList()

  /** Risposte su misura per percorso (con la versione gia' al suo posto). */
  val answers: MutableMap<String, (Request) -> Response> = HashMap()

  /** Tutte le risposte, se non null (es. "il server e' giu'"). */
  var everything: ((Request) -> Response)? = null

  val tileJson: String
    get() = """{"tilejson":"3.0.0","tiles":["https://${MapHost.TILES}/planet/$version/{z}/{x}/{y}.pbf"],"maxzoom":14,"minzoom":0}"""

  fun answer(path: String, response: (Request) -> Response) {
    answers[path] = response
  }

  override fun intercept(chain: Interceptor.Chain): Response {
    val request = chain.request()
    seen += Seen(request.method, request.url.toString(), request.url.encodedPath, request.url.encodedQuery, request.headers)
    everything?.let { return it(request) }
    answers[request.url.encodedPath]?.let { return it(request) }
    return when {
      request.url.encodedPath == MapHost.TILEJSON_PATH -> ok(request, tileJson, "application/json")
      request.url.encodedPath.startsWith("/planet/$version/") -> ok(request, "tile", "application/vnd.mapbox-vector-tile")
      else -> status(request, 404)
    }
  }

  fun tiles(): List<Seen> = seen.filter { it.path != MapHost.TILEJSON_PATH }

  fun tileJsonRequests(): List<Seen> = seen.filter { it.path == MapHost.TILEJSON_PATH }

  companion object {
    fun ok(request: Request, body: String, type: String, headers: Map<String, String> = emptyMap()): Response =
      Response.Builder().request(request).protocol(Protocol.HTTP_2).code(200).message("OK")
        .header("Content-Type", type)
        .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
        .body(body.toByteArray().toResponseBody(type.toMediaType()))
        .build()

    fun bytes(request: Request, body: ByteArray, type: String): Response =
      Response.Builder().request(request).protocol(Protocol.HTTP_2).code(200).message("OK")
        .header("Content-Type", type)
        .body(body.toResponseBody(type.toMediaType()))
        .build()

    fun status(request: Request, code: Int, headers: Map<String, String> = emptyMap()): Response =
      Response.Builder().request(request).protocol(Protocol.HTTP_2).code(code).message("x")
        .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
        .body(ByteArray(0).toResponseBody(null))
        .build()
  }
}

/** L'orologio monotono finto: [boot] e [ms] si cambiano a mano. */
internal class TestClock(var boot: Long = 1, var ms: Long = 1_000) : MapNetClock {
  override fun now(): MonoStamp = MonoStamp(boot, ms)
}

/**
 * Il client vero (cancello vero `TilePolicy`, intercettori veri) con il server finto dentro, e lo stato della mappa che il test cambia a mano.
 */
internal class TileRig(
  val server: FakeTileServer = FakeTileServer(),
  val clock: TestClock = TestClock(),
  stateFile: File? = null,
) {
  @Volatile var online = true
  @Volatile var network = MapNetwork.WIFI
  @Volatile var wifiOnly = true
  @Volatile var covered: (TileKey) -> Boolean = { false }
  @Volatile var download: RegionDownloadScope? = null

  val traffic = RecordingMapTraffic()
  val http = MapTileHttp(
    gate = TilePolicy,
    conditions = { version -> MapNetState(online, network, !wifiOnly, covered, version, download) },
    traffic = traffic,
    network = { network },
    stateFile = stateFile,
    clock = clock,
  )
  val client: OkHttpClient = http.client.newBuilder().addInterceptor(server).build()

  /** Una richiesta come la farebbe MapLibre (con i suoi header, piu' quelli in [headers]); il risultato o l'errore. */
  fun get(url: String, headers: Map<String, String> = emptyMap(), method: String = "GET"): Result<Response> = runCatching {
    val builder = Request.Builder().url(url).header("User-Agent", "dev.pampa.mycloudgallery/0.1.0 MapLibre Android/13.7.1 (4cf121e) Android/36 (arm64-v8a)")
    headers.forEach { (k, v) -> builder.header(k, v) }
    if (method != "GET") builder.method(method, if (method == "HEAD") null else ByteArray(0).toRequestBody())
    client.newCall(builder.build()).execute()
  }

  fun tile(z: Int, x: Int, y: Int, headers: Map<String, String> = emptyMap()) = get("https://${MapHost.TILES}/planet/v/$z/$x/$y.pbf", headers)
}
