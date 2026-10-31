package dev.pampa.mycloudgallery.feature.map.net

import android.app.Activity
import android.net.TrafficStats
import android.os.Bundle
import android.os.Process
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.map.contract.MapHost
import dev.pampa.mycloudgallery.core.map.contract.MapNetwork
import dev.pampa.mycloudgallery.core.map.contract.MapOnlineChoice
import dev.pampa.mycloudgallery.core.map.contract.RefuseReason
import dev.pampa.mycloudgallery.core.map.policy.TilePolicy
import dev.pampa.mycloudgallery.core.map.testing.FakeMapNetworkState
import dev.pampa.mycloudgallery.core.map.testing.InMemoryMapRegions
import dev.pampa.mycloudgallery.core.map.testing.InMemoryMapSettings
import dev.pampa.mycloudgallery.core.map.testing.RecordingMapTraffic
import dev.pampa.mycloudgallery.feature.map.map.MapLibreInit
import dev.pampa.mycloudgallery.feature.map.map.MapNetWiring
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/** L'activity che ospita una `MapView` vera, con le opzioni di [MapLibreInit] (solo nell'APK di prova). */
class MapTestActivity : Activity() {
  lateinit var mapView: MapView

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    mapView = MapView(this, MapLibreInit.mapOptions(this))
    mapView.onCreate(savedInstanceState)
    setContentView(mapView)
  }

  override fun onStart() { super.onStart(); mapView.onStart() }
  override fun onResume() { super.onResume(); mapView.onResume() }
  override fun onPause() { mapView.onPause(); super.onPause() }
  override fun onStop() { mapView.onStop(); super.onStop() }
  override fun onDestroy() { mapView.onDestroy(); super.onDestroy() }
}

/**
 * La rete della mappa sul dispositivo (Fase 9, P1; `docs/fase9-progetto.md` §2.7 verifiche (3), (4), (7), (8); §5.5 riga "Strumentato"; E-MAP1;
 * "Fatto quando": a mappa spenta **zero socket**). MapLibre vero, con [MapLibreInit] vero e il cancello vero (`TilePolicy`), una `MapView` vera
 * con uno stile che chiede le tessere all'URL stabile; il client e' quello di [MapLibreInit] con un `SocketFactory` e un DNS che **contano**.
 * In piu' i byte dell'UID dell'app (`TrafficStats`): un socket aperto fuori dal client (da MapLibre nativo) si vedrebbe li'. Mai Internet: a
 * mappa accesa risponde il server finto dentro l'intercettore.
 */
@RunWith(AndroidJUnit4::class)
class MapNetDeviceTest {

  private val instrumentation = InstrumentationRegistry.getInstrumentation()

  @Before
  fun prepara() {
    instrumentation.runOnMainSync { MapLibreInit.ensure(instrumentation.targetContext, wiring, CoroutineScope(SupervisorJob() + Dispatchers.Main)) }
    sockets.set(0)
    lookups.set(0)
    server.seen.clear()
  }

  /** Verifiche (4) e (7), E-MAP1: a mappa spenta MapLibre non e' "in rete", la mappa si apre e si muove, e non nasce nessun socket. */
  @Test
  fun aMappaSpentaZeroSocketEZeroDns() {
    setMap(online = false)
    val bytes = uidBytes()
    install(withServer = false)
    instrumentation.runOnMainSync { MapLibreInit.refreshConnectivity() }
    assertThat(MapLibre.isConnected()).isFalse()
    openMapAndMove()
    assertThat(sockets.get()).isEqualTo(0)
    assertThat(lookups.get()).isEqualTo(0)
    assertNoUidTraffic(bytes)
  }

  /**
   * Verifica (3): anche se MapLibre si crede in rete (setConnected(true) a forza) ogni sua richiesta passa dal cancello, che a mappa spenta la
   * rifiuta prima del DNS: rifiuti contati, zero socket, zero byte dell'UID.
   */
  @Test
  fun anchePerMapLibreInReteIlCancelloTrattieneTutto() {
    setMap(online = false)
    val bytes = uidBytes()
    install(withServer = false)
    val refusedBefore = MapLibreInit.counts().refused.getValue(RefuseReason.OFF)
    instrumentation.runOnMainSync { MapLibre.setConnected(true) }
    try {
      openMapAndMove { MapLibreInit.counts().refused.getValue(RefuseReason.OFF) > refusedBefore }
    } finally {
      instrumentation.runOnMainSync { MapLibreInit.refreshConnectivity() }
    }
    assertThat(sockets.get()).isEqualTo(0)
    assertThat(lookups.get()).isEqualTo(0)
    assertNoUidTraffic(bytes)
  }

  /**
   * Verifiche (3) e (8): a mappa accesa le richieste di MapLibre arrivano (dal cancello) al server finto solo come `GET` HTTPS verso l'unico host,
   * sul percorso versionato, con i soli header ammessi e il `User-Agent` fisso al posto di quello di MapLibre (nome dell'app, versione, Android,
   * ABI). Nessun socket: risponde il server finto.
   */
  @Test
  fun aMappaAccesaSoloLHostEGliHeaderAmmessi() {
    setMap(online = true)
    val bytes = uidBytes()
    install(withServer = true)
    instrumentation.runOnMainSync { MapLibreInit.refreshConnectivity() }
    assertThat(MapLibre.isConnected()).isTrue()
    openMapAndMove { server.seen.any { it.path != MapHost.TILEJSON_PATH } }
    val seen = server.seen.toList()
    for (request in seen) {
      assertWithMessage(request.url).that(request.url).startsWith("https://${MapHost.TILES}/planet")
      assertWithMessage(request.url).that(request.path == "/planet" || Regex("^/planet/$VERSION/\\d+/\\d+/\\d+\\.pbf$").matches(request.path)).isTrue()
      assertThat(request.headerNames - setOf("user-agent", "if-modified-since")).isEmpty()
      assertThat(request.userAgent).isEqualTo(MapTileHeaders.USER_AGENT)
    }
    assertThat(sockets.get()).isEqualTo(0)
    assertThat(lookups.get()).isEqualTo(0)
    assertNoUidTraffic(bytes)
  }

  /**
   * Verifica (5): stile e GeoJSON da `asset://` (cosi' saranno la carta di base, lo stile, i glifi e gli sprite di P2) si caricano dall'APK senza
   * il client: a mappa spenta lo stile c'e', i punti del GeoJSON sono disegnati, nessuna richiesta passa dal cancello e nessun socket nasce.
   */
  @Test
  fun stileEGeoJsonDaAssetSenzaRete() {
    setMap(online = false)
    val bytes = uidBytes()
    install(withServer = false)
    instrumentation.runOnMainSync { MapLibreInit.refreshConnectivity() }
    val before = MapLibreInit.counts()
    val styleLoaded = AtomicBoolean(false)
    var map: MapLibreMap? = null
    val scenario = ActivityScenario.launch(MapTestActivity::class.java)
    try {
      scenario.onActivity { activity ->
        activity.mapView.getMapAsync { m ->
          map = m
          MapLibreInit.configure(m)
          m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(64.14, -21.94), 9.0))
          m.setStyle(Style.Builder().fromUri("asset://map-test/style.json")) { styleLoaded.set(true) }
        }
      }
      until("lo stile da asset:// e' caricato") { styleLoaded.get() }
      until("i punti del GeoJSON da asset:// sono disegnati") {
        var drawn = false
        scenario.onActivity { activity ->
          val m = map ?: return@onActivity
          val center = android.graphics.PointF(activity.mapView.width / 2f, activity.mapView.height / 2f)
          drawn = m.queryRenderedFeatures(android.graphics.RectF(center.x - 60, center.y - 60, center.x + 60, center.y + 60), "punti").isNotEmpty()
        }
        drawn
      }
    } finally {
      scenario.close()
    }
    val after = MapLibreInit.counts()
    assertThat(after.allowed).isEqualTo(before.allowed)
    assertThat(after.tileJsonFetches).isEqualTo(before.tileJsonFetches)
    assertThat(sockets.get()).isEqualTo(0)
    assertThat(lookups.get()).isEqualTo(0)
    assertNoUidTraffic(bytes)
  }

  // --- pezzi --------------------------------------------------------------------------------------------------------------------

  private fun setMap(online: Boolean) = runBlocking {
    if (online) settings.enable(MapOnlineChoice(noteVersion = 1, atMs = 0)) else settings.disable()
  }

  /** Il client di [MapLibreInit] con DNS e socket che contano (e il server finto in fondo, se [withServer]) dentro MapLibre. */
  private fun install(withServer: Boolean) {
    val base = MapLibreInit.tileHttp!!.client
    val builder: OkHttpClient.Builder = base.newBuilder().socketFactory(CountingSocketFactory).dns(CountingDns(OnlyMapHostDns()))
    if (withServer) builder.addInterceptor(server)
    MapTileHttp.installInMapLibre(builder.build())
  }

  /** Apre la mappa, la sposta due volte e aspetta che abbia disegnato (e, se c'e', l'evento [until2]). */
  private fun openMapAndMove(until2: (() -> Boolean)? = null) {
    val styleLoaded = AtomicBoolean(false)
    val frames = AtomicInteger(0)
    var map: MapLibreMap? = null
    val scenario = ActivityScenario.launch(MapTestActivity::class.java)
    try {
      scenario.onActivity { activity ->
        activity.mapView.addOnDidFinishRenderingFrameListener(MapView.OnDidFinishRenderingFrameListener { _, _, _ -> frames.incrementAndGet() })
        activity.mapView.getMapAsync { m ->
          map = m
          MapLibreInit.configure(m)
          m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(48.85, 2.35), 11.0))
          m.setStyle(Style.Builder().fromJson(STYLE)) { styleLoaded.set(true) }
        }
      }
      until("lo stile della prova e' caricato") { styleLoaded.get() }
      until("la mappa ha disegnato") { frames.get() >= 1 }
      val after = frames.get()
      scenario.onActivity { map!!.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(40.71, -74.0), 13.0)) }
      until("la mappa ha disegnato dopo lo spostamento") { frames.get() > after }
      until2?.let { until("l'evento atteso dalla prova") { it() } }
    } finally {
      scenario.close()
    }
  }

  private fun uidBytes(): Long? {
    val uid = Process.myUid()
    val rx = TrafficStats.getUidRxBytes(uid)
    val tx = TrafficStats.getUidTxBytes(uid)
    return if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) null else rx + tx
  }

  private fun assertNoUidTraffic(before: Long?) {
    val now = uidBytes() ?: return
    val counts = MapLibreInit.counts()
    assertWithMessage("byte dell'UID dell'app durante la prova (socket del client ${sockets.get()}, DNS ${lookups.get()}, uscite ${counts.allowed})")
      .that(now - (before ?: now)).isEqualTo(0)
  }

  private object CountingSocketFactory : SocketFactory() {
    private val base = getDefault()
    override fun createSocket(): Socket = base.createSocket().also { sockets.incrementAndGet() }
    override fun createSocket(host: String?, port: Int): Socket = base.createSocket(host, port).also { sockets.incrementAndGet() }
    override fun createSocket(host: String?, port: Int, local: InetAddress?, localPort: Int): Socket =
      base.createSocket(host, port, local, localPort).also { sockets.incrementAndGet() }
    override fun createSocket(host: InetAddress?, port: Int): Socket = base.createSocket(host, port).also { sockets.incrementAndGet() }
    override fun createSocket(address: InetAddress?, port: Int, local: InetAddress?, localPort: Int): Socket =
      base.createSocket(address, port, local, localPort).also { sockets.incrementAndGet() }
  }

  private class CountingDns(private val delegate: Dns) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
      lookups.incrementAndGet()
      return delegate.lookup(hostname)
    }
  }

  /** Il server finto: risponde come OpenFreeMap (TileJSON con la versione, tessere vuote) e registra cosa gli arriva. */
  private class FakeServer : Interceptor {
    class Seen(val url: String, val path: String, val headerNames: Set<String>, val userAgent: String?)

    val seen = CopyOnWriteArrayList<Seen>()

    override fun intercept(chain: Interceptor.Chain): Response {
      val request = chain.request()
      seen += Seen(request.url.toString(), request.url.encodedPath, request.headers.names().map { it.lowercase() }.toSet(), request.header("User-Agent"))
      val (body, type) = if (request.url.encodedPath == MapHost.TILEJSON_PATH) {
        """{"tilejson":"3.0.0","tiles":["https://${MapHost.TILES}/planet/$VERSION/{z}/{x}/{y}.pbf"]}""" to "application/json"
      } else {
        "" to "application/vnd.mapbox-vector-tile"
      }
      return Response.Builder().request(request).protocol(Protocol.HTTP_2).code(200).message("OK").header("Content-Type", type)
        .body(body.toByteArray().toResponseBody(type.toMediaType())).build()
    }
  }

  private companion object {
    const val VERSION = "20261022_001001_pt"

    /** Uno stile minimo: sfondo e acqua dalle tessere dell'URL stabile; niente glifi, niente sprite (non chiedono niente). */
    const val STYLE = """{"version":8,"sources":{"omt":{"type":"vector","tiles":["https://tiles.openfreemap.org/planet/v/{z}/{x}/{y}.pbf"],""" +
      """"minzoom":0,"maxzoom":14}},"layers":[{"id":"bg","type":"background","paint":{"background-color":"#eeeeee"}},""" +
      """{"id":"water","type":"fill","source":"omt","source-layer":"water","paint":{"fill-color":"#99bbdd"}}]}"""

    val sockets = AtomicInteger(0)
    val lookups = AtomicInteger(0)
    val server = FakeServer()
    val settings = InMemoryMapSettings()
    private val network = FakeMapNetworkState(MapNetwork.WIFI)
    val wiring = MapNetWiring(TilePolicy, buildFlag = true, settings, network, InMemoryMapRegions(network), RecordingMapTraffic())
  }
}
