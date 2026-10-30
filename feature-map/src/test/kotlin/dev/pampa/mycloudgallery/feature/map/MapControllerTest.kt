package dev.pampa.mycloudgallery.feature.map

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.map.contract.CameraSpot
import dev.pampa.mycloudgallery.core.map.contract.GeoBox
import dev.pampa.mycloudgallery.core.map.contract.GeoE6
import dev.pampa.mycloudgallery.core.map.contract.GeoIndexState
import dev.pampa.mycloudgallery.core.map.contract.MapNetwork
import dev.pampa.mycloudgallery.core.map.contract.MapOnline
import dev.pampa.mycloudgallery.core.map.contract.MapPoint
import dev.pampa.mycloudgallery.core.map.contract.MapPoints
import dev.pampa.mycloudgallery.core.map.contract.MapSettings
import dev.pampa.mycloudgallery.core.map.contract.RebuildReason
import dev.pampa.mycloudgallery.core.map.noop.NoMapRegions
import dev.pampa.mycloudgallery.core.map.testing.FakeMapNetworkState
import dev.pampa.mycloudgallery.core.map.testing.InMemoryMapSettings
import dev.pampa.mycloudgallery.core.map.testing.ReferenceClusterIndex
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.map.contract.MapMediaPositions
import dev.pampa.mycloudgallery.feature.map.contract.MapNotice
import dev.pampa.mycloudgallery.feature.map.contract.MapScreenState
import dev.pampa.mycloudgallery.feature.map.contract.NoMapMediaPositions
import dev.pampa.mycloudgallery.feature.map.map.BubbleEngine
import dev.pampa.mycloudgallery.feature.map.map.MapCameraPolicy
import dev.pampa.mycloudgallery.feature.map.map.MapController
import dev.pampa.mycloudgallery.feature.map.map.MapInitial
import dev.pampa.mycloudgallery.feature.map.map.MapViewport
import dev.pampa.mycloudgallery.feature.map.support.CountingIndex
import dev.pampa.mycloudgallery.feature.map.support.FakeMapPoints
import dev.pampa.mycloudgallery.feature.map.support.MapRig
import dev.pampa.mycloudgallery.feature.map.support.TestPlaceNames
import dev.pampa.mycloudgallery.feature.map.testing.FakeMapSurface
import dev.pampa.mycloudgallery.feature.map.ui.FinalFilter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Il controllore della mappa (Fase 9, P4; docs/fase9-progetto.md §1.5 `MapScreenState`, §2.10, D24, D28, D29, D31, E-MAP5, E-MAP15, E-MAP17, E-MAP19, E-H9; "Avvio e pagine istantanei"). Tempo virtuale: nessuna
 * attesa scritta a mano. **Camera**: la prima volta tutte le foto con lo zoom non oltre 5, poi l'ultima posizione salvata in locale, **senza aspettare l'indice**; **bolle** ricalcolate a camera ferma o al
 * cambio di mezzo livello, mai a ogni fotogramma; **posizione salvata** dopo la calma, e subito uscendo; il fuoco di "Mostra sulla mappa" mai per un nascosto.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapControllerTest {

  private val rig = MapRig()

  private fun TestScope.controller(
    points: MapPoints,
    settings: MapSettings = InMemoryMapSettings(),
    network: MapNetwork = MapNetwork.WIFI,
    positions: MapMediaPositions = NoMapMediaPositions,
    focus: MediaId? = null,
  ) = MapController(
    points = points, settings = settings, regions = NoMapRegions, network = FakeMapNetworkState(network), names = TestPlaceNames(),
    filter = FinalFilter(rig.visibility), positions = positions, snapshot = rig.visibility.snapshot, scope = backgroundScope, focus = focus,
    compute = UnconfinedTestDispatcher(testScheduler),
  )

  private fun viewport(center: GeoE6, zoom: Double, box: GeoBox) = MapViewport(center, zoom, box, 412, 892)

  private val world = GeoBox(-180_000_000, -85_000_000, 180_000_000, 85_000_000)

  // --- Camera ---------------------------------------------------------------------------------------------------------------------

  @Test
  fun laPrimaVoltaPartonoTutteLeFotoConLoZoomNonOltreCinque() = runTest {
    val points = FakeMapPoints()
    val controller = controller(points)
    runCurrent()
    // L'indice si sta costruendo: la camera non e' ancora decisa (primo fotogramma: niente carta in un posto sbagliato) e la pagina dice che cerca.
    assertThat(controller.ui.value.initial).isNull()
    assertThat(controller.ui.value.screen).isEqualTo(MapScreenState.Loading)
    assertThat(points.rebuilds).containsExactly(RebuildReason.OPEN)
    points.set(rig.ready)
    runCurrent()
    val initial = controller.ui.value.initial
    assertThat(initial).isInstanceOf(MapInitial.FitAll::class.java)
    assertThat(MapCameraPolicy.spotFor(initial!!, 412, 892).zoom).isAtMost(5.0)
  }

  @Test
  fun poiVaLUltimaPosizioneSalvata_SenzaAspettareLIndice() = runTest {
    val saved = CameraSpot(GeoE6(41_902_800, 12_496_400), 14.0)
    val settings = InMemoryMapSettings().also { it.saveCamera(saved) }
    val controller = controller(FakeMapPoints(), settings)
    runCurrent()
    assertThat(controller.ui.value.initial).isEqualTo(MapInitial.Saved(saved))
    assertThat(controller.ui.value.screen).isEqualTo(MapScreenState.Loading)
  }

  @Test
  fun laPosizioneSiSalvaDopoLaCalma_ESubitoUscendo_SoloInLocale() = runTest {
    val settings = InMemoryMapSettings()
    val controller = controller(FakeMapPoints(rig.ready), settings, network = MapNetwork.WIFI)
    runCurrent()
    val vp = viewport(GeoE6(43_000_000, 11_000_000), 9.0, world)
    controller.onIdle(vp)
    advanceTimeBy(MapController.SAVE_CALM_MS - 1)
    runCurrent()
    assertThat(settings.lastCamera.value).isNull()
    advanceTimeBy(1)
    runCurrent()
    assertThat(settings.lastCamera.value).isEqualTo(CameraSpot(vp.center, 9.0))
    // Un'altra sosta e subito l'uscita dalla pagina: si salva senza aspettare.
    val other = viewport(GeoE6(45_000_000, 9_000_000), 11.0, world)
    controller.onIdle(other)
    controller.flushCamera()
    runCurrent()
    assertThat(settings.lastCamera.value).isEqualTo(CameraSpot(other.center, 11.0))
  }

  // --- Bolle ----------------------------------------------------------------------------------------------------------------------

  @Test
  fun leBolleSiRicalcolanoACameraFermaOAlCambioDiLivello_MaiAOgniFotogramma() = runTest {
    val counting = CountingIndex(rig.index)
    val points = FakeMapPoints(GeoIndexState.Ready(counting, 5, 0))
    val controller = controller(points)
    runCurrent()
    val surface = FakeMapSurface(GeoE6(43_000_000, 12_000_000), 6.0).also { it.listener = controller }
    surface.settle()
    runCurrent()
    val afterIdle = counting.clusterCalls
    assertThat(afterIdle).isAtLeast(1)
    assertThat(controller.ui.value.bubbles).isNotEmpty()
    // Cento fotogrammi di movimento allo stesso livello: nessun ricalcolo.
    repeat(100) { surface.pan(1.0, 0.5) }
    runCurrent()
    assertThat(counting.clusterCalls).isEqualTo(afterIdle)
    // Un pizzico che cambia il mezzo livello (un'intera tacca di zoom lo cambia di sicuro) ricalcola subito, una volta sola per livello.
    surface.zoomTo(7.0)
    runCurrent()
    assertThat(counting.clusterCalls).isEqualTo(afterIdle + 1)
    surface.zoomTo(7.0)
    runCurrent()
    assertThat(counting.clusterCalls).isEqualTo(afterIdle + 1)
    // Fermi alla stessa vista gia' calcolata: niente di nuovo. Un altro movimento e una sosta: si ricalcola.
    surface.settle()
    runCurrent()
    assertThat(counting.clusterCalls).isEqualTo(afterIdle + 1)
    surface.pan(10.0, 10.0)
    surface.settle()
    runCurrent()
    assertThat(counting.clusterCalls).isEqualTo(afterIdle + 2)
  }

  @Test
  fun quandoLIndiceCambiaLeBolleSiRifannoDaSole() = runTest {
    val points = FakeMapPoints(rig.ready)
    val controller = controller(points)
    runCurrent()
    controller.onIdle(viewport(GeoE6(43_000_000, 12_000_000), 3.0, world))
    runCurrent()
    val before = controller.ui.value.bubbles
    assertThat(before).isNotEmpty()
    // Un media nascosto (E-MAP15): l'indice ricostruito non ha piu' i suoi punti, e le bolle lo seguono senza muovere la camera.
    val remaining = rig.points.drop(rig.points.size / 2)
    points.set(GeoIndexState.Ready(ReferenceClusterIndex(remaining), 5, 0))
    runCurrent()
    assertThat(controller.ui.value.bubbles.sumOf { it.count }).isEqualTo(remaining.size)
    // Senza piu' punti: "nessuna foto con la posizione" e nessuna bolla.
    points.set(GeoIndexState.Empty(7))
    runCurrent()
    assertThat(controller.ui.value.screen).isEqualTo(MapScreenState.Empty(7))
    assertThat(controller.ui.value.bubbles).isEmpty()
  }

  @Test
  fun alPiuSessantaBolle() = runTest {
    // 400 punti ognuno nella sua cella a zoom 9 (distanti mezzo grado): la vista ne vede centinaia, le bolle sono 60.
    val many = List(400) { i ->
      MapPoint(MediaId.random(), 30_000_000 + (i / 20) * 500_000, -10_000_000 + (i % 20) * 500_000, 20_260_101, 0, 0)
    }
    val engine = BubbleEngine(TestPlaceNames(), FinalFilter(null))
    val index = ReferenceClusterIndex(many)
    val vp = viewport(GeoE6(35_000_000, -5_000_000), 9.0, GeoBox(-10_000_000, 30_000_000, 0, 50_000_000))
    assertThat(index.clusters(vp.box, vp.zoom, BubbleEngine.CELL_DP).size).isGreaterThan(60)
    assertThat(engine.compute(index, vp).size).isEqualTo(60)
  }

  // --- Schermata e avvisi ---------------------------------------------------------------------------------------------------------

  @Test
  fun gliStatiDellaPaginaSeguonoLIndiceEL_Avviso() = runTest {
    val points = FakeMapPoints()
    val settings = InMemoryMapSettings()
    val controller = controller(points, settings, network = MapNetwork.MOBILE)
    runCurrent()
    assertThat(controller.ui.value.screen).isEqualTo(MapScreenState.Loading)
    points.set(rig.ready)
    runCurrent()
    // Dati mobili con "Solo sul Wi-Fi" (acceso di serie): la riga lo dice, nessuna richiesta (E-MAP2).
    assertThat(controller.ui.value.screen).isEqualTo(MapScreenState.Ready(MapOnline.On(byDefault = true), MapNotice.WIFI_ONLY, 5))
    settings.disable()
    runCurrent()
    assertThat((controller.ui.value.screen as MapScreenState.Ready).notice).isEqualTo(MapNotice.BASE_ONLY)
    // A mappa spenta e nessuna regione lo stile e' quello offline: la carta di base a ogni zoom.
    assertThat(controller.ui.value.style.offline).isTrue()
  }

  // --- "Mostra sulla mappa" (E-MAP19, E-H9) ----------------------------------------------------------------------------------------

  @Test
  fun ilFuocoVaSulPuntoDelMedia_MaMaiSeEUnNascosto() = runTest {
    val target = rig.ids[3]
    val spot = GeoE6(59_913_900, 10_752_200)
    val positions = MapMediaPositions { if (it == target || it == rig.ids[4]) spot else null }
    val visible = controller(FakeMapPoints(rig.ready), positions = positions, focus = target)
    runCurrent()
    val initial = visible.ui.value.initial as MapInitial.Focus
    assertThat(initial.spot.center).isEqualTo(spot)
    assertThat(visible.ui.value.selection).isEqualTo(spot)
    // Un media nascosto: nessun fuoco, nessuna selezione, la mappa si apre come sempre (la riga non si offre dai Nascosti, e la porta non si chiama).
    rig.visibility.hidden += rig.ids[4]
    val hidden = controller(FakeMapPoints(rig.ready), positions = positions, focus = rig.ids[4])
    runCurrent()
    assertThat(hidden.ui.value.selection).isNull()
    assertThat(hidden.ui.value.initial).isNotInstanceOf(MapInitial.Focus::class.java)
  }
}
