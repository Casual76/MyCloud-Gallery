package dev.pampa.mycloudgallery.feature.map.net

import dev.pampa.mycloudgallery.core.map.contract.MapHost
import dev.pampa.mycloudgallery.core.map.contract.MapNetwork
import dev.pampa.mycloudgallery.core.map.contract.MapRegions
import dev.pampa.mycloudgallery.core.map.contract.TileKey
import dev.pampa.mycloudgallery.core.map.policy.TilePolicy
import dev.pampa.mycloudgallery.core.map.testing.FakeMapNetworkState
import dev.pampa.mycloudgallery.core.map.testing.InMemoryMapRegions
import dev.pampa.mycloudgallery.core.map.testing.InMemoryMapSettings
import dev.pampa.mycloudgallery.core.map.testing.RecordingMapTraffic
import dev.pampa.mycloudgallery.feature.map.map.MapNetWiring
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Cio' che i test strumentati della mappa condividono (Fase 9, P1 e P5): MapLibre si inizializza **una volta per processo** con un solo [MapNetWiring], quindi tutti i test strumentati lo usano da qui (impostazioni, rete e
 * regioni cambiano da qui, il cancello le legge a ogni richiesta). Mai Internet: il server finto sta dentro l'intercettore applicativo e un client con DNS e socket che contano ne prova l'assenza.
 */
internal object MapDeviceRig {
  val settings = InMemoryMapSettings()
  val network = FakeMapNetworkState(MapNetwork.WIFI)
  val traffic = RecordingMapTraffic()

  /** Le regioni che il cancello legge a ogni richiesta (le prove strumentate delle regioni mettono qui il gestore vero). */
  @Volatile
  var regions: MapRegions = InMemoryMapRegions(network)

  val wiring = MapNetWiring(TilePolicy, buildFlag = true, settings, network, { regions }, traffic) { regions.downloadScope() }

  val sockets = AtomicInteger(0)
  val lookups = AtomicInteger(0)

  object CountingSocketFactory : SocketFactory() {
    private val base = getDefault()
    override fun createSocket(): Socket = base.createSocket().also { sockets.incrementAndGet() }
    override fun createSocket(host: String?, port: Int): Socket = base.createSocket(host, port).also { sockets.incrementAndGet() }
    override fun createSocket(host: String?, port: Int, local: InetAddress?, localPort: Int): Socket =
      base.createSocket(host, port, local, localPort).also { sockets.incrementAndGet() }
    override fun createSocket(host: InetAddress?, port: Int): Socket = base.createSocket(host, port).also { sockets.incrementAndGet() }
    override fun createSocket(address: InetAddress?, port: Int, local: InetAddress?, localPort: Int): Socket =
      base.createSocket(address, port, local, localPort).also { sockets.incrementAndGet() }
  }

  class CountingDns(private val delegate: Dns) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
      lookups.incrementAndGet()
      return delegate.lookup(hostname)
    }
  }
}

/**
 * Il server finto delle tessere per le prove strumentate delle regioni: risponde come OpenFreeMap (TileJSON con la versione; tessere `application/vnd.mapbox-vector-tile` **vuote** o di [bodyBytes] byte, con la cache di
 * 10 anni del server vero) e registra cosa gli arriva: le tessere, gli User-Agent, quante richieste sono dentro insieme. [delayMs] rallenta ogni tessera (per fermarsi a meta').
 */
internal class DeviceTileServer(val version: String = "20261022_001001_pt") : Interceptor {
  val tiles = CopyOnWriteArrayList<TileKey>()
  val agents = CopyOnWriteArrayList<String?>()
  val tileJsonRequests = AtomicInteger(0)
  val inside = AtomicInteger(0)
  val peak = AtomicInteger(0)

  @Volatile var delayMs: Long = 0

  @Volatile var bodyBytes: Int = 0

  fun reset() {
    tiles.clear()
    agents.clear()
    tileJsonRequests.set(0)
    peak.set(0)
  }

  override fun intercept(chain: Interceptor.Chain): Response {
    val request = chain.request()
    val path = request.url.encodedPath
    agents += request.header("User-Agent")
    val (body, type) = if (path == MapHost.TILEJSON_PATH) {
      tileJsonRequests.incrementAndGet()
      """{"tilejson":"3.0.0","tiles":["https://${MapHost.TILES}/planet/$version/{z}/{x}/{y}.pbf"]}""".toByteArray() to "application/json"
    } else {
      val m = PATH.matchEntire(path) ?: throw IllegalStateException("richiesta fuori dalle regole arrivata al server finto")
      tiles += TileKey(m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[4].toInt())
      val now = inside.incrementAndGet()
      peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
      try {
        if (delayMs > 0) Thread.sleep(delayMs)
      } finally {
        inside.decrementAndGet()
      }
      ByteArray(bodyBytes) to "application/vnd.mapbox-vector-tile"
    }
    return Response.Builder().request(request).protocol(Protocol.HTTP_2).code(200).message("OK")
      .header("Content-Type", type).header("Cache-Control", "public, max-age=315360000")
      .body(body.toResponseBody(type.toMediaType())).build()
  }

  private companion object {
    val PATH = Regex("^/planet/([^/]+)/(\\d+)/(\\d+)/(\\d+)\\.pbf$")
  }
}

/** Fa da "filo" per i byte: controlla la richiesta e conta la risposta come l'intercettore di rete vero (il server finto sta dopo e non chiama mai `proceed`). */
internal class WireStub(private val guard: MapWireGuard) : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response {
    guard.checkOutgoing(chain.request())
    return guard.guarded(chain.proceed(chain.request()))
  }
}
