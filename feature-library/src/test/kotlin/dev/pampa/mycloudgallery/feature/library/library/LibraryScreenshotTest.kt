package dev.pampa.mycloudgallery.feature.library.library

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.antigravity.fluidengine.ui.fluid.FluidFloatingTabBarDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTab
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTabBar
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTabBarDefaults
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.library.contract.GridLevel
import dev.pampa.mycloudgallery.core.library.contract.GridStyle
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncStatus
import dev.pampa.mycloudgallery.feature.library.grid.RegisterComponentActivity
import dev.pampa.mycloudgallery.feature.library.library.testing.syntheticLibraryState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli screenshot della Libreria (P7): livelli, stile in proporzione, scorsa con l'intestazione agganciata, selezione,
 * menu della vista, un pinch a meta', e gli stati (permesso, accesso parziale e foto in attesa, prima lettura, vuota,
 * filtro senza risultati). Chiaro e scuro, telefono (412 dp) e tablet (1280 dp), piu' il testo grande sul telefono.
 * La pillola delle schede e' disegnata come in `MainScaffold`.
 *
 * `gradlew :feature-library:recordRoborazziDebug --tests "*LibraryScreenshot*"` scrive i PNG in `docs/screenshots/3-P7/`;
 * nei normali `testDebugUnitTest` si verifica solo che le schermate compongano.
 */
internal abstract class LibraryScreenshotBase(private val dark: Boolean, private val device: String) {

  val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  private val look: String get() = if (dark) "scuro" else "chiaro"
  private var scope: CoroutineScope? = null

  private fun show(state: LibraryTabState, fontScale: Float?) {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
        val content: @Composable () -> Unit = { Scaffolded(state) }
        if (fontScale == null) {
          content()
        } else {
          val density = LocalDensity.current
          CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) { content() }
        }
      }
    }
    composeRule.waitForIdle()
  }

  @Composable
  private fun Scaffolded(state: LibraryTabState) {
    scope = rememberCoroutineScope()
    var backdrop by remember { mutableStateOf<GlassBackdropState?>(null) }
    Box(modifier = Modifier.fillMaxSize()) {
      LibraryTab(
        contentPadding = PaddingValues(bottom = GalleryTabBarDefaults.ContentInset),
        backdropOut = { backdrop = it },
        onOpenViewer = {},
        onOpenSettings = {},
        state = state,
      )
      GalleryTabBar(
        selected = GalleryTab.Library,
        onSelect = {},
        backdrop = backdrop,
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .navigationBarsPadding()
          .padding(horizontal = FluidFloatingTabBarDefaults.HorizontalMargin)
          .padding(bottom = FluidFloatingTabBarDefaults.BottomMargin),
      )
    }
  }

  private fun shoot(name: String, state: LibraryTabState, fontScale: Float? = null, before: (LibraryTabState) -> Unit = {}) {
    show(state, fontScale)
    before(state)
    composeRule.waitForIdle()
    val suffix = if (fontScale != null) "-testo-grande" else ""
    composeRule.onRoot().captureRoboImage("$DIR/$name-$look-$device$suffix.png")
  }

  /** Porta la griglia a [fraction] della Libreria (0 i piu' vecchi, 1 i piu' recenti), come se l'utente avesse scorso. */
  private fun scrollTo(state: LibraryTabState, fraction: Float) {
    composeRule.runOnIdle {
      val grid = state.grid
      grid.userScrolled = true
      grid.listState.requestScrollToItem((grid.layout!!.rowCount * fraction).toInt(), 0)
    }
    composeRule.waitForIdle()
  }

  @Test
  fun giorni() = shoot("libreria-giorni", syntheticLibraryState())

  @Test
  fun giorniTestoGrande() = shoot("libreria-giorni", syntheticLibraryState(), fontScale = LARGE_TEXT)

  @Test
  fun anni() = shoot("libreria-anni", syntheticLibraryState(level = GridLevel.YEARS))

  @Test
  fun mesi() = shoot("libreria-mesi", syntheticLibraryState(level = GridLevel.MONTHS))

  @Test
  fun mesiTestoGrande() = shoot("libreria-mesi", syntheticLibraryState(level = GridLevel.MONTHS), fontScale = LARGE_TEXT)

  @Test
  fun tutte() = shoot("libreria-tutte", syntheticLibraryState(level = GridLevel.ALL))

  @Test
  fun proporzioni() = shoot("libreria-proporzioni", syntheticLibraryState(style = GridStyle.JUSTIFIED))

  @Test
  fun scorsa() = shoot("libreria-scorsa", syntheticLibraryState()) { state ->
    scrollTo(state, 0.62f)
    composeRule.runOnIdle { scope!!.launch { state.grid.scrubberState.show(reducedMotion = true) } }
  }

  @Test
  fun scorsaTestoGrande() = shoot("libreria-scorsa", syntheticLibraryState(), fontScale = LARGE_TEXT) { state -> scrollTo(state, 0.62f) }

  @Test
  fun selezione() = shoot("libreria-selezione", syntheticLibraryState()) { state ->
    scrollTo(state, 0.97f)
    composeRule.runOnIdle {
      val grid = state.grid
      grid.startSelecting()
      val l = grid.layout!!
      val center = grid.rowAtCenter(l)
      var picked = 0
      for (row in center - 3..center + 3) {
        if (row !in 0 until l.rowCount) continue
        for (p in l.itemsOf(row)) if ((p + row) % 3 != 0 && picked < 6) grid.idAt(p)?.let { grid.selection.toggle(it); picked++ }
      }
    }
  }

  @Test
  fun menuDellaVista() = shoot("libreria-menu", syntheticLibraryState(filter = LibraryFilter.ALL)) {
    composeRule.onNodeWithContentDescription("Vista").performClick()
  }

  @Test
  fun filtroVideo() = shoot("libreria-filtro-video", syntheticLibraryState(filter = LibraryFilter.VIDEOS, level = GridLevel.ALL))

  @Test
  fun pinchAMeta() = shoot("libreria-pinch", syntheticLibraryState()) { state ->
    scrollTo(state, 0.6f)
    composeRule.runOnIdle {
      val grid = state.grid
      val center = Offset(grid.viewport.width / 2f, grid.viewport.padTop + grid.viewport.contentHeight / 2f)
      scope!!.launch(start = CoroutineStart.UNDISPATCHED) { grid.pinchBy(0.9f, center) }
    }
  }

  @Test
  fun statoPermesso() = shoot("stato-permesso", syntheticLibraryState(phone = LibraryPhoneStatus(access = MediaAccess.NONE)))

  @Test
  fun statoParzialeEInAttesa() = shoot(
    "stato-parziale-in-attesa",
    syntheticLibraryState(phone = LibraryPhoneStatus(access = MediaAccess.PARTIAL, pendingLocation = 12)),
  )

  @Test
  fun statoPrimaLettura() = shoot(
    "stato-prima-lettura",
    syntheticLibraryState(count = 0, phone = LibraryPhoneStatus(sync = SyncStatus.Scanning(done = 1_240, total = 6_700, full = true))),
  )

  @Test
  fun statoPrimaLetturaInCorso() = shoot(
    "stato-prima-lettura-in-fondo",
    syntheticLibraryState(count = 300, phone = LibraryPhoneStatus(sync = SyncStatus.Scanning(done = 300, total = 6_700, full = true))),
  )

  @Test
  fun statoVuota() = shoot("stato-vuota", syntheticLibraryState(count = 0))

  @Test
  fun statoFiltroVuoto() = shoot("stato-filtro-vuoto", syntheticLibraryState(count = 0, filter = LibraryFilter.FAVORITES))

  companion object {
    const val DIR = "../docs/screenshots/3-P7"
    const val LARGE_TEXT = 1.3f

    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w412dp-h915dp-xhdpi")
internal class LibraryPhoneScreenshotTest(dark: Boolean) : LibraryScreenshotBase(dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w1280dp-h800dp-xhdpi")
internal class LibraryTabletScreenshotTest(dark: Boolean) : LibraryScreenshotBase(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}
