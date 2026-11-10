package dev.pampa.mycloudgallery.integration

import android.app.Application
import android.content.pm.ActivityInfo
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertWithMessage
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.imaging.testing.FakePlaceholderCache
import dev.pampa.mycloudgallery.core.imaging.testing.FakeThumbnailLoader
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.noop.EmptyViewerItems
import dev.pampa.mycloudgallery.core.map.contract.GeoE6
import dev.pampa.mycloudgallery.core.map.contract.MapOnline
import dev.pampa.mycloudgallery.core.map.contract.MapSettings
import dev.pampa.mycloudgallery.core.map.testing.InMemoryMapSettings
import dev.pampa.mycloudgallery.core.map.contract.RebuildReason
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneMediaIntents
import dev.pampa.mycloudgallery.core.memories.contract.NotifyPrefs
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.network.contract.NetworkSnapshot
import dev.pampa.mycloudgallery.feature.library.collections.CollectionItemsSource
import dev.pampa.mycloudgallery.feature.library.collections.CollectionsLayoutViewModel
import dev.pampa.mycloudgallery.GalleryScaffold
import dev.pampa.mycloudgallery.SHOT_PHONE
import dev.pampa.mycloudgallery.ScaffoldScenes
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTab
import dev.pampa.mycloudgallery.feature.map.contract.MapPlaceNames
import dev.pampa.mycloudgallery.feature.map.contract.MapRefs
import dev.pampa.mycloudgallery.feature.map.contract.MapScreenState
import dev.pampa.mycloudgallery.feature.map.contract.MapThumbnails
import dev.pampa.mycloudgallery.feature.map.map.MapRoute
import dev.pampa.mycloudgallery.feature.map.map.MapViewModel
import dev.pampa.mycloudgallery.feature.map.nav.MapDependencies
import dev.pampa.mycloudgallery.feature.map.noop.NoMapActions
import dev.pampa.mycloudgallery.feature.map.testing.FakeMapSurface
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesRefs
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesThumbnails
import dev.pampa.mycloudgallery.feature.memories.contract.MemoryPageState
import dev.pampa.mycloudgallery.feature.memories.contract.MemoryPlaceNames
import dev.pampa.mycloudgallery.feature.memories.contract.NotifyPermission
import dev.pampa.mycloudgallery.feature.memories.nav.MemoriesDependencies
import dev.pampa.mycloudgallery.feature.memories.noop.NoMemoriesActions
import dev.pampa.mycloudgallery.feature.memories.page.MemoryPageExits
import dev.pampa.mycloudgallery.feature.memories.page.MemoryPageRoute
import dev.pampa.mycloudgallery.feature.memories.page.MemoryPageViewModel
import dev.pampa.mycloudgallery.feature.settings.SettingsLinks
import dev.pampa.mycloudgallery.feature.settings.SettingsScreen
import dev.pampa.mycloudgallery.feature.settings.SettingsUiState
import dev.pampa.mycloudgallery.hidden.HiddenWorldBuilder
import dev.pampa.mycloudgallery.hidden.MemoriesDay
import dev.pampa.mycloudgallery.hidden.RealMemoriesStack
import dev.pampa.mycloudgallery.hidden.WorldSpec
import dev.pampa.mycloudgallery.map.AppMapEntryStatus
import dev.pampa.mycloudgallery.map.MapGraph
import dev.pampa.mycloudgallery.map.placesSectionProvider
import dev.pampa.mycloudgallery.memories.AppMemoriesEntryStatus
import dev.pampa.mycloudgallery.memories.memoriesSectionProvider
import java.io.File
import java.util.Optional
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli screenshot della **Fase 9 legata in `:app`** (P13): le schermate disegnate con i grafi veri sopra un catalogo vero (il mondo sintetico dei Nascosti: niente foto
 * vere, coordinate di citta' pubbliche, persone e titoli inventati). Raccolte con le sezioni Ricordi e Luoghi dai **fornitori di `:app`** (`memoriesSectionProvider`,
 * `placesSectionProvider`) sopra il motore dei ricordi vero e l'indice dei punti vero; la pagina di un ricordo dello scaffale vero; la mappa (con `FakeMapSurface`:
 * MapLibre non si disegna in Robolectric, quindi la carta e' finta e le bolle sono vere); Impostazioni con le voci "Ricordi e notifiche" e "Mappa". Chiaro e scuro.
 *
 * `gradlew :app:recordRoborazziDebug --tests "*Phase9Screenshot*"` scrive i PNG in `docs/screenshots/9-P13/`; nei normali `testDebugUnitTest` si verifica solo che
 * le schermate compongano e dicano le cose giuste.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = SHOT_PHONE)
class Phase9ScreenshotTest(private val dark: Boolean) {

  val composeRule = createComposeRule()
  val temp = TemporaryFolder()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(temp).around(RegisterComponentActivity()).around(composeRule)

  private class RegisterComponentActivity : TestWatcher() {
    override fun starting(description: Description) {
      val app = ApplicationProvider.getApplicationContext<Application>()
      val info = ActivityInfo().apply {
        name = ComponentActivity::class.java.name
        packageName = app.packageName
        flags = flags or ActivityInfo.FLAG_HARDWARE_ACCELERATED
      }
      shadowOf(app.packageManager).addOrUpdateActivity(info)
    }
  }

  private val app: Application get() = ApplicationProvider.getApplicationContext()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val look: String get() = if (dark) "scuro" else "chiaro"
  private val loader = FakeThumbnailLoader()
  private val closing = mutableListOf<AutoCloseable>()

  @After
  fun tearDown() {
    scope.cancel()
    closing.forEach { runCatching { it.close() } }
  }

  /** Il mondo: il catalogo vero con le coordinate di tre citta' pubbliche, il motore vero dei ricordi con un giro fatto, il grafo vero della mappa. */
  private class World(val stack: RealMemoriesStack, val map: MapGraph)

  private fun world(): World = runBlocking {
    val w = HiddenWorldBuilder.build(app, WorldSpec.random(SEED))
    val db = w.db.openHelper.writableDatabase
    for ((i, id) in w.ids.withIndex()) {
      val (lat, lon, place) = CITIES[i % CITIES.size]
      val version = checkNotNull(w.catalog.media(id)).currentVersion.id.value
      db.execSQL("UPDATE version_metadata SET latitude = ?, longitude = ? WHERE version_id = ?", arrayOf<Any?>(lat + (i % 7) * 0.01, lon + (i % 5) * 0.01, version))
      db.execSQL("UPDATE media SET place_id = ? WHERE media_id = ?", arrayOf<Any?>(place, id.value))
    }
    val stack = RealMemoriesStack(w, MemoriesDay.ANNIVERSARIES).also { closing += it }
    stack.refresh()
    val map = MapGraph(
      context = app, parent = scope, database = w.db, visible = stack.visible, mask = stack.mask, places = MapPlaceNames { NAMES[it] },
      catalogVersion = { id -> w.catalog.media(id)?.currentVersion?.id?.value }, network = { MutableStateFlow(NetworkSnapshot.OFFLINE) },
      ledger = { error("nessuna tessera in Robolectric") }, buildFlag = true,
    )
    map.points.rebuild(RebuildReason.OPEN)
    World(stack, map)
  }

  private val memoriesRefs = object : MemoriesRefs {
    override fun refAt(snapshot: TimelineSnapshot, index: Int): MediaRef = MediaRef.Synthetic(snapshot.idAt(index).hashCode().toLong(), 4, 3)
  }

  private val mapRefs = object : MapRefs {
    override fun refAt(snapshot: TimelineSnapshot, index: Int): MediaRef = MediaRef.Synthetic(snapshot.idAt(index).hashCode().toLong(), 4, 3)
  }

  /** [expect]: una frase che la schermata dice quando e' pronta; [ready] e [then]: per la mappa, lo stato del controllore e la camera ferma. */
  private fun shoot(
    name: String,
    expect: String,
    ready: () -> Boolean = { true },
    then: () -> Unit = {},
    done: () -> Boolean = { true },
    content: @Composable () -> Unit,
  ) {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) { content() }
    }
    composeRule.waitUntil(Attese.TETTO_MS) { composeRule.onAllNodes(hasText(expect, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    composeRule.waitUntil(Attese.TETTO_MS) { ready() }
    composeRule.runOnIdle(then)
    composeRule.waitUntil(Attese.TETTO_MS) { done() }
    composeRule.waitForIdle()
    composeRule.onRoot().captureRoboImage("$DIR/$name-$look.png")
  }

  @Test
  fun raccolteConRicordiELuoghi() {
    val w = world()
    val resources = app.resources
    val memories = memoriesSectionProvider(resources, w.stack.engine, w.stack.people, MemoryPlaceNames { ids -> ids.mapNotNull { NAMES[it] } }, w.stack.mask, scope)
    val places = placesSectionProvider(resources, w.map)
    val viewModel = CollectionsLayoutViewModel(CollectionItemsSource { EmptyViewerItems }, FakePhoneMediaIntents(), setOf(memories, places), Optional.empty())
    // K3a: le Raccolte nello scaffold delle prove, come in produzione (la pillola, la capsula della rete in testa, il fondo della pagina).
    val scenes = ScaffoldScenes(collections = viewModel)
    shoot("raccolte-ricordi-luoghi", "Firenze") { GalleryScaffold(scenes, GalleryTab.Collections) }
  }

  @Test
  fun unRicordo() {
    val w = world()
    val card = w.stack.engine.shelf.value.items.first()
    val deps = MemoriesDependencies(
      Optional.of(w.stack.engine), Optional.of(w.stack.people), Optional.of(MemoryPlaceNames { ids -> ids.mapNotNull { NAMES[it] } }), w.stack.mask,
      Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
    )
    val viewModel = MemoryPageViewModel(SavedStateHandle(mapOf("id" to card.id.value)), deps)
    assertWithMessage("l'id del ricordo dalla rotta").that(viewModel.id).isEqualTo(card.id)
    val thumbnails = MemoriesThumbnails(loader, FakePlaceholderCache(), memoriesRefs)
    shoot("ricordo", "Riproduci", ready = { viewModel.controller?.ui?.value?.page is MemoryPageState.Ready }) {
      MemoryPageRoute(NoMemoriesActions, thumbnails, MemoryPageExits(onBack = {}, onPlay = {}), Modifier.fillMaxSize(), viewModel)
    }
  }

  @Test
  fun laMappaConLaCartaFinta() {
    val w = world()
    val surface = FakeMapSurface(GeoE6(43_300_000, 11_800_000), zoom = 6.0, widthDp = 360, heightDp = 780, density = 2f)
    // Le impostazioni in memoria: il DataStore vero tiene il suo file "attivo" per tutto il processo dei test, e un secondo grafo sullo stesso file non lo legge.
    val deps = MapDependencies(
      Optional.of(w.map.points), Optional.of<MapSettings>(InMemoryMapSettings()), Optional.of(w.map.regions), Optional.of(w.map.networkState), Optional.of(w.map.places),
      Optional.of(w.map.positions), Optional.of(w.stack.visible), w.stack.mask, Optional.of(w.map.starter), Optional.of(surface.content()),
    )
    val viewModel = MapViewModel(SavedStateHandle(), deps)
    val thumbnails = MapThumbnails(loader, FakePlaceholderCache(), mapRefs)
    // La pagina e' pronta quando l'indice c'e'; poi la camera si ferma (come dopo un gesto) e le bolle si calcolano.
    shoot(
      "mappa", "Mappa",
      ready = { viewModel.controller.ui.value.screen is MapScreenState.Ready },
      then = { surface.settle() },
      // Finche' le bolle non ci sono, la camera si ferma di nuovo (un viewport appena diverso: lo stesso non sarebbe un cambiamento per il controllore).
      done = {
        // Il calcolo delle bolle torna sul thread principale: il suo looper (in pausa in Robolectric) va fatto girare.
        shadowOf(Looper.getMainLooper()).idle()
        val ready = viewModel.controller.ui.value.bubbles.isNotEmpty()
        if (!ready) {
          surface.zoomTo(surface.viewport.zoom + NUDGE)
          surface.settle()
        }
        ready
      },
    ) { MapRoute(NoMapActions, thumbnails, onBack = {}, viewModel = viewModel) }
  }

  @Test
  @Config(qualifiers = "w360dp-h1500dp-xhdpi")
  fun impostazioniConLeVociNuove() {
    val w = world()
    val device = TestDevice(app, File(temp.root, "telefono"), TestPlatform(TestTime(), TestNas.backend(TestTime()))).also { closing += AutoCloseable { it.close() } }
    val state = runBlocking {
      device.graph.start()
      device.graph.foundFamily()
      SettingsUiState(trust = device.graph.registry.status.value, deviceCount = 1, pendingRequests = 0, pendingSteps = emptyList())
    }
    val links = SettingsLinks(openMemories = {}, openMap = {})
    shoot("impostazioni-ricordi-mappa", "Ricordi e notifiche") {
      SettingsScreen(
        state = state, onBack = {}, openDiagnostics = {}, resumeStep = {}, links = links,
        memoriesSummary = AppMemoriesEntryStatus.summaryOf(NotifyPrefs(), NotifyPermission.GRANTED),
        mapSummary = AppMapEntryStatus.summaryOf(MapOnline.On(byDefault = true)),
      )
    }
  }

  companion object {
    const val DIR = "../docs/screenshots/9-P13"
    private const val SEED = 7L
    private const val NUDGE = 0.001

    /** Tre citta' pubbliche (coordinate del centro) con un id di luogo inventato: nessun posto della famiglia. */
    private val CITIES = listOf(Triple(43.7696, 11.2558, 3_000_101), Triple(43.3188, 11.3308, 3_000_102), Triple(41.9028, 12.4964, 3_000_103))
    private val NAMES = mapOf(3_000_101 to "Firenze", 3_000_102 to "Siena", 3_000_103 to "Roma")

    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun looks(): List<Array<Any>> = listOf(arrayOf(false), arrayOf(true))
  }
}
