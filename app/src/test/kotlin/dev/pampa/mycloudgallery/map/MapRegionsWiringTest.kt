package dev.pampa.mycloudgallery.map

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.noop.NoVisibleMedia
import dev.pampa.mycloudgallery.core.map.contract.DownloadStart
import dev.pampa.mycloudgallery.core.map.contract.MapHost
import dev.pampa.mycloudgallery.core.map.contract.MapNetwork
import dev.pampa.mycloudgallery.core.map.contract.MapRequestFacts
import dev.pampa.mycloudgallery.core.map.contract.MapVerdict
import dev.pampa.mycloudgallery.core.map.contract.RefuseReason
import dev.pampa.mycloudgallery.core.map.contract.RegionDownloadConfirmation
import dev.pampa.mycloudgallery.core.map.contract.RegionId
import dev.pampa.mycloudgallery.core.map.contract.RegionState
import dev.pampa.mycloudgallery.core.map.contract.RegionStatus
import dev.pampa.mycloudgallery.core.map.contract.TileKey
import dev.pampa.mycloudgallery.core.map.contract.TileMath
import dev.pampa.mycloudgallery.core.map.noop.NoMapTraffic
import dev.pampa.mycloudgallery.core.map.policy.TilePolicy
import dev.pampa.mycloudgallery.core.map.regions.FileRegionStateStore
import dev.pampa.mycloudgallery.core.map.regions.MemoryRegionStateStore
import dev.pampa.mycloudgallery.core.map.regions.OfflineMapRegions
import dev.pampa.mycloudgallery.core.map.regions.RegionCatalog
import dev.pampa.mycloudgallery.core.map.testing.FakeMapNetworkState
import dev.pampa.mycloudgallery.core.map.testing.FakeRegionEngine
import dev.pampa.mycloudgallery.core.map.testing.InMemoryMapSettings
import dev.pampa.mycloudgallery.core.network.contract.NetworkSnapshot
import dev.pampa.mycloudgallery.core.places.dataset.PlacesDataset
import dev.pampa.mycloudgallery.feature.map.contract.MapPlaceNames
import dev.pampa.mycloudgallery.feature.map.map.MapLibreInit
import dev.pampa.mycloudgallery.feature.map.map.MapNetWiring
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Le regioni offline nel grafo della mappa (Fase 9, P5; §1.4 `MapGraph`, §2.9, D26, D37, I3): nascono **alla prima richiesta** (mai all'avvio) e leggono il loro stato in `noBackupFilesDir/map/`; una regione completa
 * rende `COVERED` la sua tessera nel cancello vero; lo scaricamento in corso passa dal cancello con il suo conto; l'azzeramento le toglie; la ricerca dei luoghi per nome sta sul dataset che l'app ha gia' (mai la rete).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MapRegionsWiringTest {

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
  private val database = GalleryDatabase.build(context, null)
  private val mapDir: File get() = File(context.noBackupFilesDir, MapLibreInit.MAP_DIR)
  private val umbria = RegionCatalog.entryOf(RegionId("it-umbria"))!!.spec

  @After
  fun tearDown() {
    scope.cancel()
    database.close()
  }

  private fun graph(places: () -> dev.pampa.mycloudgallery.core.places.dataset.PlacesData? = { null }) = MapGraph(
    context = context,
    parent = scope,
    database = database,
    visible = NoVisibleMedia,
    mask = HiddenMask { emptySet() },
    places = MapPlaceNames { null },
    catalogVersion = { null },
    network = { MutableStateFlow(NetworkSnapshot.OFFLINE) },
    ledger = { error("il registro del traffico non serve") },
    buildFlag = true,
    eraseEngine = { true },
    placesData = places,
    freeBytes = { 64L shl 30 },
    today = { 20261031 },
    resumeDelayMs = 0,
  )

  private fun facts(tile: TileKey) = MapRequestFacts("GET", "https", MapHost.TILES, 443, "/planet/v/${tile.z}/${tile.x}/${tile.y}.pbf", null)

  @Test
  fun leRegioniNasconoAllaPrimaRichiestaELeggonoLoStatoSuDisco() {
    mapDir.deleteRecursively()
    FileRegionStateStore(File(mapDir, FileRegionStateStore.FILE_NAME)).save(listOf(RegionState(umbria, RegionStatus.READY, 528, 528, 21_000_000, 20261001, maxZoom = 12)))
    val map = graph()
    // Il grafo non ha toccato niente: nemmeno il file di stato e' stato letto finche' nessuno chiede.
    assertThat(MapLibreInit.isReady).isFalse()
    val inside = TileMath.tileOf(42_900_000, 12_600_000, 10)
    // Il cancello vero con le regioni del grafo (lette a ogni richiesta) e le impostazioni in memoria (il DataStore vero parte "spento", fallisce chiuso): la prima domanda legge lo stato, e una
    // regione completa copre la sua tessera (`COVERED`).
    val wiring = MapNetWiring(TilePolicy, true, InMemoryMapSettings(), FakeMapNetworkState(MapNetwork.WIFI), { map.regions }, NoMapTraffic)
    assertThat(TilePolicy.decide(facts(inside), wiring.state("v1"))).isEqualTo(MapVerdict.Refuse(RefuseReason.COVERED))
    assertThat(map.regions.regions.value.single().spec.id).isEqualTo(umbria.id)
    // Oltre il livello scaricato (z12 per le regioni) o fuori dal riquadro la tessera esce come prima.
    assertThat(TilePolicy.decide(facts(TileMath.tileOf(42_900_000, 12_600_000, 13)), wiring.state("v1"))).isInstanceOf(MapVerdict.Allow::class.java)
    assertThat(TilePolicy.decide(facts(TileMath.tileOf(10_000_000, 100_000_000, 10)), wiring.state("v1"))).isInstanceOf(MapVerdict.Allow::class.java)
  }

  @Test
  fun unoScaricamentoInCorsoPassaDalCancelloConIlSuoConto() = runBlocking<Unit> {
    val map = graph()
    val engine = FakeRegionEngine()
    val network = FakeMapNetworkState(MapNetwork.WIFI)
    val manager = OfflineMapRegions(
      engine = engine, store = MemoryRegionStateStore(), network = network, settings = InMemoryMapSettings(), places = map.placeFinder,
      freeBytes = { 64L shl 30 }, nowMs = { 1_000 }, today = { 20261031 }, scope = scope,
    )
    map.regions = manager
    assertThat(map.wiring().activeDownload()).isNull()
    assertThat(manager.download(umbria, RegionDownloadConfirmation(umbria.id, 1, false, 1_000))).isEqualTo(DownloadStart.Started)
    // Lo scaricamento confermato arriva al cancello (`MapNetWiring.downloadScope`) con il suo conto: 3.000 tessere e 50 MB.
    val active = map.wiring().activeDownload()!!
    assertThat(active.budget!!.maxBytes).isEqualTo(50_000_000L)
    assertThat(active.budget!!.maxTiles).isEqualTo(3_000)
    assertThat(active.includes(TileMath.tileOf(42_900_000, 12_600_000, 8))).isTrue()
    assertThat(active.includes(TileMath.tileOf(10_000_000, 100_000_000, 8))).isFalse()
    // Il conto finisce: la regione resta parziale e il cancello non ha piu' uno scaricamento in corso.
    active.budget!!.tryAdmit()
    active.budget!!.received(50_000_001)
    active.budget!!.arrived()
    assertThat(manager.regions.value.single().status).isEqualTo(RegionStatus.PARTIAL)
    assertThat(map.wiring().activeDownload()).isNull()
  }

  @Test
  fun l_AzzeramentoToglieLeRegioniEDaLiNessunaScrittura() = runBlocking<Unit> {
    mapDir.deleteRecursively()
    val stateFile = File(mapDir, FileRegionStateStore.FILE_NAME)
    FileRegionStateStore(stateFile).save(listOf(RegionState(umbria, RegionStatus.READY, 528, 528, 21_000_000, 20261001, maxZoom = 12)))
    val map = graph()
    assertThat(map.regions.regions.value).hasSize(1)
    map.stop()
    assertThat(map.regions.regions.value).isEmpty()
    assertWithMessage("il file di stato delle regioni e' tolto").that(stateFile.exists()).isFalse()
    assertThat(map.regions.covers(TileMath.tileOf(42_900_000, 12_600_000, 8))).isFalse()
  }

  @Test
  fun senzaRegioniNateL_AzzeramentoNonLeCrea() = runBlocking<Unit> {
    mapDir.deleteRecursively()
    val map = graph()
    map.stop()
    assertWithMessage("nessun file della mappa creato dall'azzeramento").that(File(mapDir, FileRegionStateStore.FILE_NAME).exists()).isFalse()
  }

  /** La ricerca per nome sta sul dataset dei luoghi che l'app ha gia' (mai la rete): senza dataset la pagina dice che non c'e'. */
  @Test
  fun laRicercaDeiLuoghiPerNomeSta_SulDatasetDellApp() {
    assertThat(graph().placeFinder.available).isFalse()
    assertThat(graph().placeFinder.search("siena", 8)).isEmpty()
    val asset = File("../core-places/src/main/assets/places/places.mcgp1")
    assumeTrue("Nessun dataset reale in core-places.", asset.isFile)
    val data = PlacesDataset.openForTest(asset.readBytes())
    val finder = graph { data }.placeFinder
    assertThat(finder.available).isTrue()
    val hits = finder.search("bologna", 8)
    assertThat(hits).isNotEmpty()
    val bologna = hits.first()
    assertThat(bologna.urban).isTrue()
    assertThat(bologna.center.latE6).isIn(44_300_000..44_700_000)
    assertThat(bologna.center.lonE6).isIn(11_100_000..11_600_000)
    // Il centro e il nome per id (per attorno-a-un-luogo): gli stessi.
    assertThat(finder.center(bologna.placeId)).isEqualTo(bologna.center)
    assertThat(finder.urban(bologna.placeId)).isTrue()
    assertThat(finder.name(bologna.placeId)).isEqualTo(bologna.name)
    assertThat(finder.center(0)).isNull()
    assertThat(finder.center(-5)).isNull()
    // Un luogo piccolo non e' una "citta'" (la stima usa la campagna).
    val small = finder.search("sulmona", 8).first()
    assertThat(small.urban).isFalse()
    // Nel toString dei risultati niente luoghi.
    assertThat(bologna.toString()).isEqualTo("PlaceHit")
  }
}
