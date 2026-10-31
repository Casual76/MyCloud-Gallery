package dev.pampa.mycloudgallery.feature.map.net

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.map.contract.BudgetLimit
import dev.pampa.mycloudgallery.core.map.contract.DownloadBudget
import dev.pampa.mycloudgallery.core.map.contract.MapHost
import dev.pampa.mycloudgallery.core.map.contract.MapNetwork
import dev.pampa.mycloudgallery.core.map.contract.OpenFreeMapLimits
import dev.pampa.mycloudgallery.core.map.contract.RefuseReason
import dev.pampa.mycloudgallery.core.map.contract.RegionDownloadScope
import dev.pampa.mycloudgallery.core.map.contract.TileKey
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * I limiti di OpenFreeMap sugli scaricamenti, **attraverso il client vero** (Fase 9, P5; condizioni d'uso lette il 30/10; decisione dell'utente del 31/10: dentro i limiti): al piu' 4 richieste insieme,
 * stop ai 50 MB veri sul filo, stop alle 3.000 tessere, un User-Agent che identifica l'app e non il dispositivo, le tessere coperte mai richieste (contatore a zero). Sulla JVM, senza rete: il server
 * finto e' l'ultimo intercettore applicativo; l'intercettore che sta "sul filo" ([MapWireGuard]) lo si mette subito prima, cosi' i byte che il server finto manda passano da dove passerebbero quelli veri.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MapDownloadLimitsTest {

  /** Fa da "filo": controlla la richiesta e conta i byte della risposta come l'intercettore di rete vero (il server finto sta dopo e non chiama mai `proceed`). */
  private class WireStub(private val guard: MapWireGuard) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
      guard.checkOutgoing(chain.request())
      return guard.guarded(chain.proceed(chain.request()))
    }
  }

  private inner class Rig(val budget: DownloadBudget, bodyBytes: Int = 1_000, mobile: Boolean = false, hold: (() -> Unit)? = null) {
    val tileRig = TileRig()
    val inside = AtomicInteger()
    val peak = AtomicInteger()
    val userAgents = CopyOnWriteArrayList<String?>()
    val client: OkHttpClient

    init {
      tileRig.download = RegionDownloadScope({ it.z == 10 }, mobileConfirmed = mobile, budget = budget)
      tileRig.server.everything = { request ->
        val now = inside.incrementAndGet()
        peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
        userAgents += request.header("User-Agent")
        try {
          hold?.invoke()
          if (request.url.encodedPath == MapHost.TILEJSON_PATH) {
            FakeTileServer.ok(request, tileRig.server.tileJson, "application/json")
          } else {
            FakeTileServer.bytes(request, ByteArray(bodyBytes), "application/vnd.mapbox-vector-tile")
          }
        } finally {
          inside.decrementAndGet()
        }
      }
      val guard = MapWireGuard(tileRig.traffic, { tileRig.network }, tileRig.http.counters)
      // Prima del server finto: il "filo". Il client e' quello vero (cancello, DNS, limite di 4 richieste per host, niente cookie ne' cache).
      client = tileRig.http.client.newBuilder().addInterceptor(WireStub(guard)).addInterceptor(tileRig.server).build()
    }

    fun tile(x: Int, y: Int = 0): Result<Response> = runCatching {
      client.newCall(Request.Builder().url("https://${MapHost.TILES}/planet/v/10/$x/$y.pbf").header("User-Agent", "dev.pampa.mycloudgallery/0.1.0 MapLibre Android/13.7.1 (x) Android/36 (arm64-v8a)").build()).execute()
    }
  }

  @Test
  fun alPiu4RichiesteInsiemeVersoLHost() {
    val release = CountDownLatch(1)
    val holding = AtomicBoolean(false)
    val budget = DownloadBudget()
    val rig = Rig(budget, hold = { if (holding.get()) release.await(10, TimeUnit.SECONDS) })
    rig.tile(0, 0).getOrThrow().close() // il TileJSON esce una volta con la prima richiesta, prima che le altre si accumulino
    holding.set(true)
    val done = CountDownLatch(40)
    val failures = AtomicInteger()
    for (i in 1..40) {
      rig.client.newCall(Request.Builder().url("https://${MapHost.TILES}/planet/v/10/$i/0.pbf").build()).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
          failures.incrementAndGet()
          done.countDown()
        }

        override fun onResponse(call: Call, response: Response) {
          response.close()
          done.countDown()
        }
      })
    }
    // Aspetta che il server finto ne abbia in mano il massimo che gli arriva, poi le lascia andare.
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (rig.inside.get() < OpenFreeMapLimits.MAX_PARALLEL && System.nanoTime() < deadline) Thread.sleep(5)
    release.countDown()
    assertThat(done.await(30, TimeUnit.SECONDS)).isTrue()
    assertThat(failures.get()).isEqualTo(0)
    assertWithMessage("richieste insieme dentro il server finto").that(rig.peak.get()).isEqualTo(OpenFreeMapLimits.MAX_PARALLEL)
    assertThat(rig.tileRig.http.snapshot().peakInFlight).isAtMost(OpenFreeMapLimits.MAX_PARALLEL)
    assertThat(budget.peakInFlight).isAtMost(OpenFreeMapLimits.MAX_PARALLEL)
    assertThat(budget.tiles).isEqualTo(41)
    assertThat(MapTileHttp.MAX_REQUESTS_PER_HOST).isEqualTo(OpenFreeMapLimits.MAX_PARALLEL)
  }

  @Test
  fun alibera50MBVeriSulFiloLoScaricamentoSiFerma() {
    // 200 KB a tessera, tetto di 1 MB per la prova (stessa regola dei 50 MB): dopo la quinta tessera il conto e' pieno e le altre non escono.
    val budget = DownloadBudget(maxBytes = 1_000_000)
    val exhausted = CopyOnWriteArrayList<BudgetLimit>()
    budget.onExhausted = { exhausted += it }
    val rig = Rig(budget, bodyBytes = 200_000)
    val results = (0 until 20).map { rig.tile(it) }
    val ok = results.count { it.isSuccess }
    assertThat(ok).isEqualTo(5)
    results.drop(5).forEach { assertThat(it.exceptionOrNull()).isInstanceOf(MapRequestHeld::class.java) }
    assertThat(budget.bytes).isEqualTo(1_000_000L)
    assertThat(budget.tiles).isEqualTo(5)
    assertThat(exhausted).containsExactly(BudgetLimit.BYTES)
    assertThat(rig.tileRig.http.snapshot().refused[RefuseReason.LIMIT]).isEqualTo(15)
    // Il server ha visto il TileJSON e 5 tessere: nient'altro.
    assertThat(rig.tileRig.server.tiles()).hasSize(5)
    // I byte del registro del traffico sono gli stessi che il conto ha contato (una volta sola).
    assertThat(rig.tileRig.traffic.bytes[MapNetwork.WIFI]).isAtLeast(1_000_000L)
  }

  @Test
  fun alle3000TessereLoScaricamentoSiFerma() {
    val budget = DownloadBudget(maxTiles = 25)
    val rig = Rig(budget, bodyBytes = 10)
    val results = (0 until 40).map { rig.tile(it) }
    assertThat(results.count { it.isSuccess }).isEqualTo(25)
    assertThat(budget.limit).isEqualTo(BudgetLimit.TILES)
    assertThat(rig.tileRig.server.tiles()).hasSize(25)
    assertThat(OpenFreeMapLimits.MAX_TILES).isEqualTo(3_000)
    assertThat(OpenFreeMapLimits.MAX_BYTES).isAtMost(50L * 1024 * 1024)
  }

  /** Una tessera che non arriva (errore del server) non conta come scaricata: il conto non si consuma con i tentativi andati male. */
  @Test
  fun unErroreDelServerNonConsumaIlConto() {
    val budget = DownloadBudget(maxTiles = 10)
    val rig = Rig(budget)
    rig.tileRig.server.everything = { request -> if (request.url.encodedPath == MapHost.TILEJSON_PATH) FakeTileServer.ok(request, rig.tileRig.server.tileJson, "application/json") else FakeTileServer.status(request, 503) }
    rig.tile(1).getOrThrow().close()
    assertThat(budget.tiles).isEqualTo(0)
    assertThat(budget.inFlight).isEqualTo(0)
  }

  @Test
  fun lUserAgentDelloScaricamentoIdentificaLAppENonIlDispositivo() {
    val agent = MapTileHeaders.USER_AGENT_DOWNLOAD
    assertThat(agent).isEqualTo("MyCloudGallery-offline/1")
    // Un nome di app e una versione di protocollo: niente modello, niente sistema, niente ABI, niente id.
    assertThat(agent).matches("^[A-Za-z]+-[a-z]+/\\d+$")
    for (device in listOf("Android", "SM-", "Samsung", "arm64", "Build", "Linux", "pampa", "dev.")) assertThat(agent).doesNotContain(device)
    val rig = Rig(DownloadBudget())
    rig.tile(3).getOrThrow().close()
    // Il TileJSON che serve a sapere la versione porta lo stesso User-Agent, e anche la tessera (mai quello di MapLibre con app e dispositivo).
    assertThat(rig.userAgents).isNotEmpty()
    assertThat(rig.userAgents.toSet()).containsExactly(agent)
  }

  /** Le tessere coperte da una regione completa non si chiedono mai online: il server finto non vede niente e il contatore dei motivi dice `COVERED`. */
  @Test
  fun leTessereCopertenonSiChiedonoOnline() {
    val rig = TileRig().apply { covered = { it.z in 0..12 } }
    val before = rig.http.snapshot()
    val tiles = listOf(TileKey(12, 2170, 1480), TileKey(8, 135, 92), TileKey(0, 0, 0))
    for (tile in tiles) assertThat(rig.tile(tile.z, tile.x, tile.y).exceptionOrNull()).isInstanceOf(MapRequestHeld::class.java)
    assertThat(rig.server.seen).isEmpty()
    val after = rig.http.snapshot()
    assertThat(after.allowed).isEqualTo(before.allowed)
    assertThat(after.tileJsonFetches).isEqualTo(0)
    assertThat(after.refused[RefuseReason.COVERED]).isEqualTo(3)
    // Una tessera oltre il livello della regione (z13) esce come prima.
    assertThat(rig.tile(13, 4340, 2960).getOrThrow().code).isEqualTo(200)
  }
}
