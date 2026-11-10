package dev.pampa.mycloudgallery

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.rememberFluidFloatingTabBarScrollConnection
import dev.pampa.mycloudgallery.albums.LibraryAlbumRefs
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTab
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTabBar
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTabBarSlotVisibility
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.feature.albums.contract.AccountNamesAsIs
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumThumbnails
import dev.pampa.mycloudgallery.feature.albums.list.AlbumListScreen
import dev.pampa.mycloudgallery.feature.albums.list.AlbumListUi
import dev.pampa.mycloudgallery.feature.albums.list.NoAlbumListHandlers
import dev.pampa.mycloudgallery.feature.albums.testing.AlbumSamples
import dev.pampa.mycloudgallery.feature.library.library.LibraryTab
import dev.pampa.mycloudgallery.feature.library.library.LibraryTabState
import dev.pampa.mycloudgallery.feature.library.testing.syntheticLibraryTabState
import dev.pampa.mycloudgallery.main.AlbumsTabFrame
import dev.pampa.mycloudgallery.main.MainScaffold
import dev.pampa.mycloudgallery.status.GlobalStatus
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli screenshot del pacchetto G2 della fase grafica (per l'utente, prima della beta): la pillola **Libreria · Album · Raccolte** su ogni scheda,
 * il cerchio di Cerca chiuso e aperto, la pillola ripiegata dallo scorrimento della Libreria (la scheda scelta e il cerchio) e la Libreria in
 * selezione con la barra delle azioni al posto della pillola. Chiaro e scuro, telefono (412 dp) e tablet (1280 dp). Il foglio "Prima mettilo al
 * sicuro" dalla selezione ha i suoi in feature-backup (`G2SelectionSheet*Shots`), le Raccolte alleggerite in feature-library.
 *
 * `gradlew :app:recordRoborazziDebug --tests "*G2*Screenshot*"` scrive i PNG in `docs/screenshots/beta-grafica/G2/`.
 */
internal abstract class G2ScreenshotBase(private val dark: Boolean, private val device: String) {

  val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

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

  private val look: String get() = if (dark) "scuro" else "chiaro"

  private fun shoot(name: String, afterShow: () -> Unit = {}, content: @Composable () -> Unit) {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) { content() }
    }
    composeRule.waitForIdle()
    afterShow()
    composeRule.waitForIdle()
    composeRule.onRoot().captureRoboImage("$DIR/$name-$look-$device.png")
  }

  @Composable
  private fun Scaffold(tab: GalleryTab, state: LibraryTabState, searchOpen: Boolean = false) {
    MainScaffold(
      status = GlobalStatus.HOME_CONNECTED,
      selectedTab = tab,
      onSelectTab = {},
      onStatusClick = {},
      onOpenSettings = {},
      libraryState = state,
      // La scheda Album con l'elenco di sempre su uno stato dato (in produzione `AlbumsTabHost`, con il ViewModel).
      albums = { slot ->
        val thumbnails = remember(state) { AlbumThumbnails(state.grid.loader, state.grid.placeholders, LibraryAlbumRefs { state.grid.view.snapshot }) }
        AlbumsTabFrame(slot) {
          AlbumListScreen(AlbumListUi(AlbumSamples.lists[1]), thumbnails, AccountNamesAsIs, NoAlbumListHandlers)
        }
      },
      searchOpen = searchOpen,
    )
  }

  @Test
  fun pillolaLibreria() {
    val state = syntheticLibraryTabState()
    shoot("pillola-libreria") { Scaffold(GalleryTab.Library, state) }
  }

  @Test
  fun pillolaAlbum() {
    val state = syntheticLibraryTabState()
    shoot("pillola-album") { Scaffold(GalleryTab.Albums, state) }
  }

  @Test
  fun pillolaRaccolte() {
    val state = syntheticLibraryTabState()
    shoot("pillola-raccolte") { Scaffold(GalleryTab.Collections, state) }
  }

  /** Il cerchio aperto: la ricerca sopra la scheda di prima (qui il segnaposto: la schermata di Cerca e' quella di sempre), il cerchio d'accento. */
  @Test
  fun cercaAperta() {
    val state = syntheticLibraryTabState()
    shoot("cerca-aperta") { Scaffold(GalleryTab.Library, state, searchOpen = true) }
  }

  /** La pillola ripiegata dallo scorrimento della Libreria: la scheda scelta e, accanto, il cerchio di Cerca. */
  @Test
  fun pillolaRipiegata() {
    val state = syntheticLibraryTabState()
    shoot("pillola-ripiegata") {
      var backdrop by remember { mutableStateOf<GlassBackdropState?>(null) }
      Box(Modifier.fillMaxSize()) {
        LibraryTab(
          contentPadding = PaddingValues(bottom = 76.dp),
          backdropOut = { backdrop = it },
          onOpenViewer = {},
          onOpenSettings = {},
          state = state,
        )
        GalleryTabBarSlotVisibility(visible = true, modifier = Modifier.align(Alignment.BottomCenter)) {
          GalleryTabBar(
            selected = GalleryTab.Library,
            onSelect = {},
            backdrop = backdrop,
            scrollConnection = rememberFluidFloatingTabBarScrollConnection(initialIsInline = true),
            onOpenSearch = {},
          )
        }
      }
    }
  }

  /** B3: la Libreria in selezione, con la barra delle azioni al posto della pillola (e del cerchio). */
  @Test
  fun libreriaInSelezione() {
    val state = syntheticLibraryTabState()
    shoot(
      "libreria-selezione",
      afterShow = {
        composeRule.runOnIdle {
          state.grid.startSelecting()
          val view = state.grid.view
          state.grid.selection.selectAll((0 until 3).map { view.snapshot.idAt(view.snapshotIndex(it)) })
        }
      },
    ) { Scaffold(GalleryTab.Library, state) }
  }

  companion object {
    const val DIR = "../docs/screenshots/beta-grafica/G2"

    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w412dp-h915dp-xhdpi")
internal class G2PhoneScreenshotTest(dark: Boolean) : G2ScreenshotBase(dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w1280dp-h800dp-land-xhdpi")
internal class G2TabletScreenshotTest(dark: Boolean) : G2ScreenshotBase(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}
