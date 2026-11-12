package dev.pampa.mycloudgallery.feature.map

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.pampa.mycloudgallery.core.map.contract.CameraSpot
import dev.pampa.mycloudgallery.core.map.contract.GeoE6
import dev.pampa.mycloudgallery.core.map.contract.GeoIndexState
import dev.pampa.mycloudgallery.core.map.contract.MapNetwork
import dev.pampa.mycloudgallery.core.map.contract.MapOnline
import dev.pampa.mycloudgallery.core.map.contract.MapPrivacyNote
import dev.pampa.mycloudgallery.core.map.contract.RebuildReason
import dev.pampa.mycloudgallery.core.map.noop.NoMapRegions
import dev.pampa.mycloudgallery.core.map.testing.FakeMapNetworkState
import dev.pampa.mycloudgallery.core.map.testing.InMemoryMapSettings
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntry
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntryGroup
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntryRows
import dev.pampa.mycloudgallery.feature.map.contract.MapPrivacyState
import dev.pampa.mycloudgallery.feature.map.contract.MapScreenState
import dev.pampa.mycloudgallery.feature.map.contract.NoMapMediaPositions
import dev.pampa.mycloudgallery.feature.map.contract.PlacesSectionState
import dev.pampa.mycloudgallery.feature.map.contract.ZoneGridState
import dev.pampa.mycloudgallery.feature.map.grid.NoZoneGridHandlers
import dev.pampa.mycloudgallery.feature.map.grid.ZoneGridController
import dev.pampa.mycloudgallery.feature.map.grid.ZoneGridHandlers
import dev.pampa.mycloudgallery.feature.map.grid.ZoneGridScreen
import dev.pampa.mycloudgallery.feature.map.grid.ZoneTarget
import dev.pampa.mycloudgallery.feature.map.map.MapController
import dev.pampa.mycloudgallery.feature.map.map.MapHandlers
import dev.pampa.mycloudgallery.feature.map.map.MapInitial
import dev.pampa.mycloudgallery.feature.map.map.MapScreen
import dev.pampa.mycloudgallery.feature.map.map.MapStyles
import dev.pampa.mycloudgallery.feature.map.map.MapUi
import dev.pampa.mycloudgallery.feature.map.map.NoMapHandlers
import dev.pampa.mycloudgallery.feature.map.places.PlacesEntryTarget
import dev.pampa.mycloudgallery.feature.map.places.PlacesSectionModel
import dev.pampa.mycloudgallery.feature.map.places.PlacesSectionSources
import dev.pampa.mycloudgallery.feature.map.places.PlacesStates
import dev.pampa.mycloudgallery.feature.map.privacy.MapPrivacyScreen
import dev.pampa.mycloudgallery.feature.map.privacy.NoMapPrivacyHandlers
import dev.pampa.mycloudgallery.feature.map.support.FakeMapPoints
import dev.pampa.mycloudgallery.feature.map.support.MapRig
import dev.pampa.mycloudgallery.feature.map.support.TestPlaceNames
import dev.pampa.mycloudgallery.feature.map.testing.FakeMapSurface
import dev.pampa.mycloudgallery.feature.map.ui.FinalFilter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * MA-1 della verifica funzionale della Fase 9 (31/10): **un errore nel leggere i punti non e' "nessuna foto con la posizione"**. L'indice dice `Failed` (se non c'era un indice buono da tenere), la pagina della
 * mappa, la griglia di una zona e la sezione Luoghi dicono "Non riesco a leggere le posizioni delle foto" e propongono "Riprova", che rifa `rebuild(OPEN)`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MapFailureStatesTest {

  private val rig = MapRig(count = 90)

  private fun TestScope.mapController(points: FakeMapPoints) = MapController(
    points = points, settings = InMemoryMapSettings(), regions = NoMapRegions, network = FakeMapNetworkState(MapNetwork.WIFI), names = TestPlaceNames(),
    filter = FinalFilter(rig.visibility), positions = NoMapMediaPositions, snapshot = rig.visibility.snapshot, scope = backgroundScope,
    compute = UnconfinedTestDispatcher(testScheduler),
  )

  private fun TestScope.gridController(target: ZoneTarget, points: FakeMapPoints) =
    ZoneGridController(target, points, rig.visibility, TestPlaceNames(), FinalFilter(rig.visibility), backgroundScope, UnconfinedTestDispatcher(testScheduler))

  @Test
  fun unErroreSullaMappaNonEUnaMappaVuota_ERiprovaRifaLaLettura() = runTest {
    val points = FakeMapPoints(GeoIndexState.Failed)
    val controller = mapController(points)
    runCurrent()
    assertThat(controller.ui.value.screen).isEqualTo(MapScreenState.Failed)
    assertThat(controller.ui.value.screen).isNotInstanceOf(MapScreenState.Empty::class.java)
    // La camera si decide lo stesso (l'errore non lascia la pagina senza carta): il mondo, mai un posto sbagliato.
    assertThat(controller.ui.value.initial).isNotNull()
    assertThat(points.rebuilds).containsExactly(RebuildReason.OPEN)
    controller.retry()
    runCurrent()
    assertThat(points.rebuilds).containsExactly(RebuildReason.OPEN, RebuildReason.OPEN)
    // La lettura riesce: la pagina e' pronta, con le sue bolle.
    points.set(rig.ready)
    runCurrent()
    assertThat(controller.ui.value.screen).isInstanceOf(MapScreenState.Ready::class.java)
  }

  @Test
  fun laGrigliaDiUnaZonaDiceCheNonRiesceALeggere_ERiprova() = runTest {
    val key = rig.cellOf(0, 24)
    val points = FakeMapPoints(GeoIndexState.Failed)
    val controller = gridController(ZoneTarget.Cell(key), points)
    runCurrent()
    assertThat(controller.state.value).isEqualTo(ZoneGridState.Failed)
    assertThat(controller.state.value).isNotEqualTo(ZoneGridState.Empty)
    assertThat(points.rebuilds).containsExactly(RebuildReason.OPEN)
    controller.retry()
    runCurrent()
    assertThat(points.rebuilds).containsExactly(RebuildReason.OPEN, RebuildReason.OPEN)
    points.set(rig.ready)
    runCurrent()
    assertThat(controller.state.value).isInstanceOf(ZoneGridState.Ready::class.java)
  }

  @Test
  fun laGrigliaDiUnLuogoNonDipendeDallIndice_QuindiNonHaErrori() = runTest {
    val points = FakeMapPoints(GeoIndexState.Failed)
    val controller = gridController(ZoneTarget.Place(3_000_001), points)
    runCurrent()
    // Un luogo si legge dall'istantanea, non dall'indice dei punti: l'errore dell'indice non lo tocca.
    assertThat(controller.state.value).isNotEqualTo(ZoneGridState.Failed)
  }

  @Test
  fun laSezioneLuoghiDiceCheNonRiesceALeggere_EHaLaRigaRiprova() = runTest {
    assertThat(PlacesStates.of(GeoIndexState.Failed, TestPlaceNames())).isEqualTo(PlacesSectionState.Failed)
    assertThat(PlacesStates.of(GeoIndexState.Empty(4), TestPlaceNames())).isEqualTo(PlacesSectionState.NoGps(4))
    val points = FakeMapPoints(GeoIndexState.Failed)
    val model = PlacesSectionModel(points, TestPlaceNames(), backgroundScope, UnconfinedTestDispatcher(testScheduler))
    val sources = PlacesSectionSources(androidResources(), model, backgroundScope)
    runCurrent()
    assertThat(model.state.value).isNull() // `Lazily`: finche' nessuno guarda non si legge niente
    assertThat(points.rebuilds).isEmpty()
    backgroundScope.launch { model.state.collect { } }
    runCurrent()
    assertThat(model.state.value).isEqualTo(PlacesSectionState.Failed)
    assertThat(points.rebuilds).containsExactly(RebuildReason.OPEN)
    sources.retry()
    runCurrent()
    assertThat(points.rebuilds).containsExactly(RebuildReason.OPEN, RebuildReason.OPEN)
  }

  private fun androidResources() = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>().resources

  @Test
  fun leRigheDellaSezioneDiconoLErroreEOffronoRiprova() {
    val resources = androidResources()
    val section = PlacesSectionSources.sectionOf(resources, PlacesSectionState.Failed)
    assertThat(section.entries.map { it.code }).containsExactly(PlacesEntryTarget.MAP, PlacesEntryTarget.RETRY).inOrder()
    assertThat(section.entries[0].subtitle).isEqualTo("Non riesco a leggere le posizioni delle foto")
    assertThat(section.entries[0].subtitle).isNotEqualTo(resources.getString(R.string.mappa_row_map_none))
    assertThat(section.entries[1].title).isEqualTo("Riprova")
    // Nessun conto dato per vero: non si sa quante foto sono senza posizione.
    assertThat(section.entries.map { it.code }).doesNotContain(PlacesEntryTarget.NO_POSITION)
    assertThat(PlacesEntryTarget.parse(PlacesEntryTarget.RETRY)).isEqualTo(PlacesEntryTarget.Retry)
    assertThat(PlacesEntryTarget.parse("places_retry_x")).isNull()
    // Gli altri stati non hanno la riga.
    for (state in listOf(null, PlacesSectionState.NoGps(3), PlacesSectionState.Ready(5, emptyList(), 0))) {
      assertThat(PlacesSectionSources.sectionOf(resources, state).entries.map { it.code }).doesNotContain(PlacesEntryTarget.RETRY)
    }
  }

  @Test
  fun lErroreHaIlSuoCodiceNelContratto() {
    assertThat(GeoIndexState.Failed.toString()).isEqualTo("Failed")
    assertThat(MapScreenState.Failed).isNotEqualTo(MapScreenState.Empty(0))
    assertThat(MapPrivacyNote.VERSION).isEqualTo(2)
  }
}

/**
 * Le schermate dell'errore composte: dicono la frase e hanno "Riprova" (bersaglio da 48 dp, anche a testo grande, che chiama il gestore), e gli screenshot (chiaro e scuro, telefono) in
 * `docs/screenshots/9-corr/` (`gradlew :feature-map:recordRoborazziDebug --tests "*MapFailureScreenshotTest*"`).
 */
internal abstract class MapFailureScenes(private val dark: Boolean) : MapScreenTest() {

  private val rig = MapRig()
  private val center = GeoE6(43_000_000, 12_000_000)
  private val look: String get() = if (dark) "scuro" else "chiaro"
  private val on = MapOnline.On(byDefault = true)

  private fun shoot(name: String, vararg expect: String, content: @Composable () -> Unit) {
    showAll(dark = dark, content = content)
    for (text in expect) assertShows(name, text)
    composeRule.onRoot().captureRoboImage("../docs/screenshots/9-corr/$name-$look-telefono.png")
  }

  private fun mapScene(handlers: MapHandlers = NoMapHandlers): @Composable () -> Unit = {
    val surface = FakeMapSurface(center, zoom = 6.0, widthDp = 412, heightDp = 915, density = 2f)
    val ui = MapUi(MapScreenState.Failed, MapStyles.choose(on, hasRegions = false, dark = dark), MapInitial.Saved(CameraSpot(center, 6.0)))
    MapScreen(ui, surface, surface.content(), images.thumbnails, rig.snapshot, handlers, Modifier.fillMaxSize())
  }

  @Composable
  private fun SectionScene() {
    val section = PlacesSectionSources.sectionOf(LocalContext.current.resources, PlacesSectionState.Failed)
    val group = CollectionEntryGroup(
      title = section.title, entries = section.entries.map { CollectionEntry(it.code, it.title, it.subtitle, it.icon, it.tone, it.badge, it.trailing) },
      code = section.code, hideable = section.hideable, hint = section.hint,
    )
    FluidScreen(title = "Raccolte", modifier = Modifier.fillMaxSize()) {
      item { FluidSectionHeader(title = section.title) }
      item { CollectionEntryRows(group, selectedCode = null, twoPane = false, onSelect = {}) }
    }
  }

  @Test
  fun mappaConErrore() = shoot("mappa-errore", "Non riesco a leggere le posizioni delle foto", "Riprova", content = mapScene())

  @Test
  fun zonaConErrore() = shoot("zona-errore", "Non riesco a leggere le posizioni delle foto", "Riprova", "Torna alla mappa") {
    ZoneGridScreen(ZoneGridState.Failed, images.thumbnails, NoZoneGridHandlers, Modifier.fillMaxSize())
  }

  @Test
  fun luoghiConErrore() = shoot("luoghi-errore", "Mappa", "Non riesco a leggere le posizioni delle foto", "Riprova", "Leggi di nuovo le posizioni delle foto") { SectionScene() }

  @Test
  fun impostazioniConLaSceltaRovinata() = shoot(
    "impostazioni-rovinata", "Spenta: resta la carta semplice dentro l’app", "La scelta si era rovinata: la mappa dettagliata è spenta finché non la riaccendi.",
    "Per non far sapere una zona, scaricala prima di aprirla, oppure spegni la mappa dettagliata.",
  ) { MapPrivacyScreen(MapPrivacyState.Shown(MapOnline.Off, true, MapPrivacyNote.VERSION, 0, damaged = true), NoMapPrivacyHandlers, Modifier.fillMaxSize()) }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = SHOT_PHONE_QUALIFIERS)
internal class MapFailureScreenshotTest(dark: Boolean) : MapFailureScenes(dark) {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

/** I gestori: "Riprova" chiama il gestore giusto, i bersagli sono da 48 dp e a testo grande non si sovrappongono. */
internal class MapFailureHandlersTest : MapScreenTest() {

  private val rig = MapRig()
  private val center = GeoE6(43_000_000, 12_000_000)

  private fun mapPage(handlers: MapHandlers): @Composable () -> Unit = {
    val surface = FakeMapSurface(center, zoom = 6.0, widthDp = 412, heightDp = 915, density = 2f)
    val ui = MapUi(MapScreenState.Failed, MapStyles.choose(MapOnline.On(true), hasRegions = false, dark = false), MapInitial.Saved(CameraSpot(center, 6.0)))
    MapScreen(ui, surface, surface.content(), images.thumbnails, rig.snapshot, handlers, Modifier.fillMaxSize())
  }

  @Test
  fun riprovaSullaMappaChiamaIlGestore() {
    var retried = 0
    showAll { mapPage(object : MapHandlers by NoMapHandlers { override fun onRetry() { retried++ } })() }
    assertShows("errore", "Non riesco a leggere le posizioni delle foto")
    assertDoesNotShow("errore", "Nessuna foto con la posizione")
    clickText("Riprova")
    assertThat(retried).isEqualTo(1)
  }

  @Test
  fun riprovaNellaGrigliaDiUnaZonaChiamaIlGestore() {
    var retried = 0
    var back = 0
    val handlers = object : ZoneGridHandlers by NoZoneGridHandlers {
      override fun onRetry() { retried++ }

      override fun onBack() { back++ }
    }
    showAll { ZoneGridScreen(ZoneGridState.Failed, images.thumbnails, handlers) }
    assertShows("errore", "Non riesco a leggere le posizioni delle foto")
    assertDoesNotShow("errore", "Nessuna foto qui")
    clickText("Riprova")
    clickText("Torna alla mappa")
    assertThat(retried).isEqualTo(1)
    assertThat(back).isEqualTo(1)
  }

  private fun checkErrorScenes(fontScale: Float?) = checkScenes(
    fontScale,
    "mappa" to mapPage(NoMapHandlers),
    "zona" to { ZoneGridScreen(ZoneGridState.Failed, images.thumbnails, NoZoneGridHandlers) },
    "impostazioni" to { MapPrivacyScreen(MapPrivacyState.Shown(MapOnline.Off, true, MapPrivacyNote.VERSION, 0, damaged = true), NoMapPrivacyHandlers) },
  )

  @Test
  fun leSchermateDellErroreHannoBersagliDa48Dp() = checkErrorScenes(null)

  @Test
  fun leSchermateDellErroreANestoGrandeNonSiSovrappongono() = checkErrorScenes(LARGE_TEXT)
}
