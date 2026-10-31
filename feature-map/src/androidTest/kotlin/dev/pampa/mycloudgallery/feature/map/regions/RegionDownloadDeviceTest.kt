package dev.pampa.mycloudgallery.feature.map.regions

import android.os.Process
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.map.contract.DownloadStart
import dev.pampa.mycloudgallery.core.map.contract.GeoE6
import dev.pampa.mycloudgallery.core.map.contract.MapNetwork
import dev.pampa.mycloudgallery.core.map.contract.OpenFreeMapLimits
import dev.pampa.mycloudgallery.core.map.contract.RefuseReason
import dev.pampa.mycloudgallery.core.map.contract.RegionDownloadConfirmation
import dev.pampa.mycloudgallery.core.map.contract.RegionId
import dev.pampa.mycloudgallery.core.map.contract.RegionRadius
import dev.pampa.mycloudgallery.core.map.contract.RegionSpec
import dev.pampa.mycloudgallery.core.map.contract.RegionState
import dev.pampa.mycloudgallery.core.map.contract.RegionStatus
import dev.pampa.mycloudgallery.core.map.contract.RegionStop
import dev.pampa.mycloudgallery.core.map.contract.TileMath
import dev.pampa.mycloudgallery.core.map.noop.NoPlaceCenters
import dev.pampa.mycloudgallery.core.map.regions.FileRegionStateStore
import dev.pampa.mycloudgallery.core.map.regions.OfflineMapRegions
import dev.pampa.mycloudgallery.core.map.regions.RegionCatalog
import dev.pampa.mycloudgallery.core.map.regions.RegionPlanner
import dev.pampa.mycloudgallery.feature.map.contract.MapEngineStarter
import dev.pampa.mycloudgallery.feature.map.map.MapLibreInit
import dev.pampa.mycloudgallery.feature.map.net.DeviceTileServer
import dev.pampa.mycloudgallery.feature.map.net.MapDeviceRig
import dev.pampa.mycloudgallery.feature.map.net.MapTestActivity
import dev.pampa.mycloudgallery.feature.map.net.MapTileHttp
import dev.pampa.mycloudgallery.feature.map.net.MapWireGuard
import dev.pampa.mycloudgallery.feature.map.net.OnlyMapHostDns
import dev.pampa.mycloudgallery.feature.map.net.WireStub
import dev.pampa.mycloudgallery.feature.map.net.until
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * Le regioni offline sul dispositivo (Fase 9, P5; `docs/fase9-progetto.md` §2.9, D26, E-MAP11; §5.5 riga "Strumentato"; decisione dell'utente del 31/10: dentro i limiti di OpenFreeMap): **MapLibre vero** (`OfflineManager`),
 * il gestore vero (`OfflineMapRegions`), il cancello vero (`TilePolicy`, `MapGateInterceptor`, il conto dello scaricamento) e un **server finto** dentro l'intercettore applicativo: **mai Internet** (un client con DNS e socket
 * che contano lo prova: zero e zero). Le prove:
 *  - una regione si scarica fino in fondo: le richieste sono **tutte e sole** le tessere della regione (lo stesso conto del piano), con lo User-Agent dello scaricamento, al piu' 4 insieme; poi, con la mappa aperta sulla zona,
 *    **nessuna tessera coperta si chiede online** (il database le serve);
 *  - lo scaricamento si ferma ai 50 MB veri (con un server che manda tessere piu' grosse della stima): la regione resta parziale con il motivo e non riparte;
 *  - **dopo la morte del processo** la regione riprende dalle tessere che il database ha gia', senza richiederle (nello stesso processo, sempre; con un vero `kill`, due corse separate: vedi [killeResume]).
 * Le regioni di prova si tolgono a fine prova; il database e' quello vero di MapLibre in `noBackupFilesDir/map/` (la cartella dell'app di prova).
 */
@RunWith(AndroidJUnit4::class)
class RegionDownloadDeviceTest {

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = instrumentation.targetContext
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val server = DeviceTileServer()
  private val stateFiles = ArrayList<File>()

  private val sanMarino = RegionCatalog.entryOf(RegionId("country-sm"))!!.spec
  private val around = RegionSpec.AroundPlace(424_242, RegionRadius.KM_15, GeoE6(44_494_900, 11_342_600), urban = false)
  private val starter = MapEngineStarter { MapLibreInit.isReady }

  @Before
  fun prepara() {
    instrumentation.runOnMainSync { MapLibreInit.ensure(context, MapDeviceRig.wiring, CoroutineScope(SupervisorJob() + Dispatchers.Main)) }
    MapDeviceRig.sockets.set(0)
    MapDeviceRig.lookups.set(0)
    MapDeviceRig.network.set(MapNetwork.WIFI)
    runBlocking { MapDeviceRig.settings.enable(dev.pampa.mycloudgallery.core.map.contract.MapOnlineChoice(noteVersion = 2, atMs = 0)) }
    install()
    // Un database pulito a ogni prova: le tessere di una prova prima (restano nella cache anche dopo aver tolto la regione) farebbero finire le regioni di questa prima di chiedere niente.
    // Non nelle due fasi della morte vera: il database deve sopravvivere fra una corsa e l'altra.
    if (InstrumentationRegistry.getArguments().getString("mcg.resume") != "1") resetDatabase()
  }

  /** Quante tessere ha **il database di MapLibre** per la regione [key] (la verita': non il conto che il gestore ha salvato), o -1. Da una regione ferma. */
  private fun tilesInDatabase(key: String): Long {
    var result = -1L
    val done = java.util.concurrent.CountDownLatch(1)
    instrumentation.runOnMainSync {
      org.maplibre.android.offline.OfflineManager.getInstance(context).listOfflineRegions(
        object : org.maplibre.android.offline.OfflineManager.ListOfflineRegionsCallback {
          override fun onList(offlineRegions: Array<org.maplibre.android.offline.OfflineRegion>?) {
            val region = offlineRegions?.firstOrNull { it.metadata?.toString(Charsets.UTF_8) == key }
            if (region == null) {
              done.countDown()
              return
            }
            region.getStatus(
              object : org.maplibre.android.offline.OfflineRegion.OfflineRegionStatusCallback {
                override fun onStatus(status: org.maplibre.android.offline.OfflineRegionStatus?) {
                  result = status?.completedTileCount ?: -1L
                  done.countDown()
                }

                override fun onError(error: String?) = done.countDown()
              },
            )
          }

          override fun onError(error: String) = done.countDown()
        },
      )
    }
    done.await(20, java.util.concurrent.TimeUnit.SECONDS)
    return result
  }

  /** Svuota il database di MapLibre con la sua API (come l'azzeramento, ma senza chiudere la rete). */
  private fun resetDatabase() {
    val done = java.util.concurrent.CountDownLatch(1)
    instrumentation.runOnMainSync {
      org.maplibre.android.offline.OfflineManager.getInstance(context).resetDatabase(
        object : org.maplibre.android.offline.OfflineManager.FileSourceCallback {
          override fun onSuccess() = done.countDown()

          override fun onError(message: String) = done.countDown()
        },
      )
    }
    assertThat(done.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue()
  }

  @After
  fun pulisci() {
    runBlocking {
      for (key in listOf(sanMarino.id.code, around.id.code)) runCatching { MapLibreRegionEngine(context, starter).delete(key) }
    }
    stateFiles.forEach { it.delete() }
    MapDeviceRig.regions = dev.pampa.mycloudgallery.core.map.testing.InMemoryMapRegions(MapDeviceRig.network)
    scope.cancel()
  }

  /** Il client del cancello con il filo (i byte veri) e il server finto in fondo, DNS e socket che contano: dentro MapLibre. */
  private fun install() {
    val http = MapLibreInit.tileHttp!!
    val guard = MapWireGuard(MapDeviceRig.traffic, { MapNetwork.WIFI }, http.counters)
    val client = http.client.newBuilder()
      .socketFactory(MapDeviceRig.CountingSocketFactory)
      .dns(MapDeviceRig.CountingDns(OnlyMapHostDns()))
      .addInterceptor(WireStub(guard))
      .addInterceptor(server)
      .build()
    MapTileHttp.installInMapLibre(client)
  }

  private fun manager(file: File, engine: MapLibreRegionEngine = MapLibreRegionEngine(context, starter)): OfflineMapRegions {
    stateFiles += file
    val regions = OfflineMapRegions(
      engine = engine,
      store = FileRegionStateStore(file),
      network = MapDeviceRig.network,
      settings = MapDeviceRig.settings,
      places = NoPlaceCenters,
      freeBytes = { 64L shl 30 },
      nowMs = { SystemClock.elapsedRealtime() },
      today = { 20261031 },
      scope = scope,
    )
    MapDeviceRig.regions = regions
    return regions
  }

  private fun stateFile(name: String) = File(context.cacheDir, "regioni-prova-$name.state").also { it.delete() }

  private fun confirm(spec: RegionSpec) = RegionDownloadConfirmation(spec.id, 1, false, SystemClock.elapsedRealtime())

  private fun OfflineMapRegions.state(spec: RegionSpec): RegionState? = regions.value.firstOrNull { it.spec.id == spec.id }

  private fun assertNoRealNetwork() {
    assertWithMessage("socket aperti dal client").that(MapDeviceRig.sockets.get()).isEqualTo(0)
    assertWithMessage("risoluzioni DNS").that(MapDeviceRig.lookups.get()).isEqualTo(0)
  }

  // --- una regione si scarica ---------------------------------------------------------------------------------------------------

  @Test
  fun unaRegioneSiScaricaFinoInFondoEPoiNonSiChiedePiuOnline() {
    val regions = manager(stateFile("sm"))
    val plan = RegionPlanner.plan(sanMarino)
    server.reset()
    assertThat(runBlocking { regions.download(sanMarino, confirm(sanMarino)) }).isEqualTo(DownloadStart.Started)
    until("la regione e' completa") { regions.state(sanMarino)?.status == RegionStatus.READY }

    // Le richieste sono tutte e sole le tessere della regione (lo stesso conto del piano: tessere di MapLibre = tessere nostre), una volta l'una, con lo User-Agent dello scaricamento.
    val requested = server.tiles.toList()
    assertThat(requested).hasSize(plan.tiles.toInt())
    assertThat(requested.toSet()).hasSize(requested.size)
    for (tile in requested) {
      assertWithMessage("tessera fuori dal riquadro o oltre z${plan.maxZoom}").that(tile.z <= plan.maxZoom && TileMath.touches(tile, sanMarino.box, 0, plan.maxZoom)).isTrue()
    }
    assertThat(requested.map { it.z }.max()).isEqualTo(plan.maxZoom)
    assertThat(server.agents.toSet()).containsExactly("MyCloudGallery-offline/1")
    assertThat(server.tileJsonRequests.get()).isAtMost(1)
    // Al piu' 4 richieste insieme (dentro il server finto e nei contatori del client).
    assertThat(server.peak.get()).isAtMost(OpenFreeMapLimits.MAX_PARALLEL)
    assertThat(MapLibreInit.counts().peakInFlight).isAtMost(OpenFreeMapLimits.MAX_PARALLEL)
    val counts = MapLibreInit.counts()
    assertThat(counts.refused.getValue(RefuseReason.HOST)).isEqualTo(0)
    assertThat(counts.refused.getValue(RefuseReason.PATH)).isEqualTo(0)
    val done = regions.state(sanMarino)!!
    assertThat(done.completedTiles).isEqualTo(done.totalTiles)
    assertThat(done.completedDay).isEqualTo(20261031)
    assertNoRealNetwork()

    // Con la mappa aperta sulla zona le tessere coperte non si chiedono: le serve il database. Quelle fuori dal riquadro si'.
    server.reset()
    openMapAt(43.9367, 12.4464, listOf(9.0, 11.0))
    val covered = server.tiles.filter { TileMath.touches(it, sanMarino.box, 0, plan.maxZoom) }
    assertWithMessage("tessere coperte chieste online: ${covered.size}").that(covered).isEmpty()
    assertThat(server.tiles.toList()).isNotEmpty() // la prova non e' vuota: i dintorni (fuori dal riquadro) si chiedono
    assertNoRealNetwork()
  }

  // --- il tetto dei 50 MB veri --------------------------------------------------------------------------------------------------

  @Test
  fun ai50MBVeriLoScaricamentoSiFermaELaRegioneResta_ParzialeConIlMotivo() {
    val regions = manager(stateFile("limite"))
    server.reset()
    server.bodyBytes = 300_000 // tessere piu' grosse della stima: a ~167 tessere si superano i 50 MB
    server.delayMs = 2
    assertThat(runBlocking { regions.download(around, confirm(around)) }).isEqualTo(DownloadStart.Started)
    until("lo scaricamento si e' fermato al limite") { regions.state(around)?.stop?.terminal == true }
    val partial = regions.state(around)!!
    assertThat(partial.status).isEqualTo(RegionStatus.PARTIAL)
    assertThat(partial.stop).isEqualTo(RegionStop.LIMIT_BYTES)
    // I byte veri sul filo hanno superato (di poco: al piu' 4 tessere in volo) i 50 MB, e nient'altro e' uscito dopo il tetto.
    assertThat(partial.bytes).isAtLeast(OpenFreeMapLimits.MAX_BYTES)
    assertThat(partial.bytes).isAtMost(OpenFreeMapLimits.MAX_BYTES + 6L * 300_000)
    val seenAtStop = server.tiles.size
    assertThat(seenAtStop).isAtMost((OpenFreeMapLimits.MAX_BYTES / 300_000).toInt() + 6)
    Thread.sleep(1_500) // niente riparte da solo: il conteggio non cresce
    assertThat(server.tiles.size).isEqualTo(seenAtStop)
    assertThat(runBlocking { regions.resume(around.id) }).isEqualTo(DownloadStart.TooLarge)
    runBlocking { regions.resumeInterrupted() }
    assertThat(regions.state(around)!!.status).isEqualTo(RegionStatus.PARTIAL)
    // Il cancello tiene lo scaricamento chiuso per qualche secondo dopo lo stop (le richieste in coda di MapLibre non escono), poi lo lascia.
    until("lo scaricamento esce dal cancello") { regions.downloadScope() == null }
    assertThat(server.tiles.size).isEqualTo(seenAtStop)
    assertNoRealNetwork()
  }

  // --- la ripresa dopo la morte del processo ------------------------------------------------------------------------------------

  /**
   * Il processo muore a meta': lo stato su disco dice "in scaricamento" e il database di MapLibre ha le tessere gia' arrivate. Qui **nello stesso processo**: il primo gestore e il suo motore si abbandonano senza una pausa
   * ne' un "completa" (come se il processo fosse morto), un gestore nuovo legge lo stesso file, e alla riapertura la regione riprende: chiede **solo le tessere mancanti** e arriva a "completa".
   */
  @Test
  fun dopoLaMortePerduta_laRegioneRiprendeSoloLeTessereMancanti_NelloStessoProcesso() {
    val file = stateFile("morte")
    val plan = RegionPlanner.plan(around)
    val engine = MapLibreRegionEngine(context, starter)
    val first = manager(file, engine)
    server.reset()
    server.delayMs = 60
    runBlocking { first.download(around, confirm(around)) }
    until("un po' di tessere sono arrivate e salvate") { (first.state(around)?.completedTiles ?: 0L) >= 60L && FileRegionStateStore(file).load().firstOrNull()?.completedTiles?.let { it >= 40 } == true }
    // "Morte": si ferma il motore di MapLibre senza passare dal gestore, e il gestore si lascia (nessuna pausa, nessuna scrittura da li').
    scope.cancel()
    runBlocking { engine.stop(around.id.code) }
    val savedTiles = FileRegionStateStore(file).load().single().completedTiles
    val requestedBefore = server.tiles.size
    val firstPhase = server.tiles.toSet()
    assertThat(requestedBefore).isLessThan(plan.tiles.toInt())
    // Le tessere che il database ha davvero (MapLibre ne scrive a gruppi: un'interruzione brusca puo' perdere le ultime): da qui si conta cosa manca.
    val inDatabase = tilesInDatabase(around.id.code)
    assertThat(inDatabase).isAtLeast(20L)

    val resumeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val second = OfflineMapRegions(
      engine = MapLibreRegionEngine(context, starter), store = FileRegionStateStore(file), network = MapDeviceRig.network, settings = MapDeviceRig.settings,
      places = NoPlaceCenters, freeBytes = { 64L shl 30 }, nowMs = { SystemClock.elapsedRealtime() }, today = { 20261031 }, scope = resumeScope,
    )
    MapDeviceRig.regions = second
    try {
      val loaded = second.state(around)!!
      assertThat(loaded.status).isEqualTo(RegionStatus.PARTIAL)
      assertThat(loaded.stop).isEqualTo(RegionStop.INTERRUPTED)
      assertThat(loaded.completedTiles).isEqualTo(savedTiles)
      server.reset()
      server.delayMs = 0
      runBlocking { second.resumeInterrupted() }
      until("la regione e' completa dopo la ripresa") { second.state(around)?.status == RegionStatus.READY }
      // Ha richiesto **esattamente** le tessere che mancavano nel database (E-MAP11: le tessere gia' arrivate valgono), e di quelle gia' chieste prima della morte solo quelle che erano in volo e non erano arrivate.
      val again = server.tiles.toSet().filter { it in firstPhase }
      assertWithMessage("tessere diverse chieste dopo la ripresa: ${server.tiles.toSet().size}; il database ne aveva $inDatabase su ${plan.tiles}").that(server.tiles.toSet().size).isEqualTo(plan.tiles.toInt() - inDatabase.toInt())
      assertThat(again.size).isAtMost(requestedBefore - inDatabase.toInt())
      // MapLibre puo' chiedere due volte qualche tessera del primo gruppo (provato: da 4 a 16 con un server istantaneo): il piano lascia il margine (`OpenFreeMapLimits.PLAN_MAX_TILES`).
      assertWithMessage("richieste doppie: ${server.tiles.size - server.tiles.toSet().size}").that(server.tiles.size - server.tiles.toSet().size).isAtMost(OpenFreeMapLimits.MAX_TILES - OpenFreeMapLimits.PLAN_MAX_TILES)
      assertThat(second.state(around)!!.completedTiles).isEqualTo(second.state(around)!!.totalTiles)
      assertNoRealNetwork()
    } finally {
      resumeScope.cancel()
    }
  }

  /**
   * La morte **vera**: due corse separate dello stesso APK di prova (il processo muore davvero fra l'una e l'altra), da lanciare a mano (`adb shell am instrument -e mcg.kill 1 ... #fasePartenzaEMorte`, poi
   * `-e mcg.resume 1 ... #faseRipresa`); senza questi argomenti le due prove si saltano. Lo stato sta in `filesDir` e il database di MapLibre in `noBackupFilesDir/map/`: sopravvivono entrambi.
   */
  @Test
  fun fasePartenzaEMorte() {
    assumeTrue("Solo con -e mcg.kill 1", InstrumentationRegistry.getArguments().getString("mcg.kill") == "1")
    val file = File(context.filesDir, KILL_STATE).also { it.delete() }
    runBlocking { MapLibreRegionEngine(context, starter).delete(around.id.code) }
    val regions = manager(file)
    stateFiles.clear() // lo stato deve restare dopo la morte
    server.reset()
    server.delayMs = 60
    runBlocking { regions.download(around, confirm(around)) }
    until("tessere arrivate e salvate") { FileRegionStateStore(file).load().firstOrNull()?.completedTiles?.let { it >= 60 } == true }
    // Il processo muore qui, senza una pausa, un "completa" o una chiusura del database.
    Process.killProcess(Process.myPid())
  }

  @Test
  fun faseRipresa() {
    assumeTrue("Solo con -e mcg.resume 1", InstrumentationRegistry.getArguments().getString("mcg.resume") == "1")
    val file = File(context.filesDir, KILL_STATE)
    assertThat(file.isFile).isTrue()
    val plan = RegionPlanner.plan(around)
    val regions = manager(file)
    stateFiles.clear()
    val loaded = regions.state(around)!!
    assertWithMessage("dopo la morte del processo la regione e' parziale e interrotta").that(loaded.status).isEqualTo(RegionStatus.PARTIAL)
    assertThat(loaded.stop).isEqualTo(RegionStop.INTERRUPTED)
    assertThat(loaded.completedTiles).isAtLeast(40L)
    val inDatabase = tilesInDatabase(around.id.code)
    assertWithMessage("il database di MapLibre ha le tessere arrivate prima della morte del processo").that(inDatabase).isAtLeast(20L)
    server.reset()
    runBlocking { regions.resumeInterrupted() }
    until("la regione e' completa dopo la ripresa") { regions.state(around)?.status == RegionStatus.READY }
    assertWithMessage("tessere diverse chieste dopo la ripresa: ${server.tiles.toSet().size}; il database ne aveva $inDatabase su ${plan.tiles}").that(server.tiles.toSet().size).isEqualTo(plan.tiles.toInt() - inDatabase.toInt())
    assertThat(server.tiles.size - server.tiles.toSet().size).isAtMost(OpenFreeMapLimits.MAX_TILES - OpenFreeMapLimits.PLAN_MAX_TILES)
    assertThat(server.agents.toSet()).containsExactly("MyCloudGallery-offline/1")
    assertNoRealNetwork()
    File(context.filesDir, KILL_STATE).delete()
  }

  // --- pezzi --------------------------------------------------------------------------------------------------------------------

  /** Apre una mappa vera con lo stile online dell'app sulla zona e la porta ai livelli [zooms]: aspetta che abbia disegnato dopo ogni passo. */
  private fun openMapAt(lat: Double, lon: Double, zooms: List<Double>) {
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
          m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), zooms.first()))
          m.setStyle(Style.Builder().fromUri("asset://map/style-chiaro.json")) { styleLoaded.set(true) }
        }
      }
      until("lo stile e' caricato") { styleLoaded.get() }
      for (zoom in zooms) {
        val before = frames.get()
        scenario.onActivity { map!!.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), zoom)) }
        until("la mappa ha disegnato a zoom $zoom") { frames.get() > before + 1 }
        Thread.sleep(800) // le richieste delle tessere partono dopo il primo disegno
      }
    } finally {
      scenario.close()
    }
  }

  private companion object {
    const val KILL_STATE = "regioni-prova-kill.state"
  }
}
