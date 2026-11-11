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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.antigravity.fluidengine.ui.fluid.FluidFloatingTabBarDefaults
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalHost
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.LocalFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.fluidGlassModalObscured
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTab
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTabBar
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTabBarDefaults
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeTagPrefix
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.library.contract.GridLevel
import dev.pampa.mycloudgallery.feature.library.grid.RegisterComponentActivity
import dev.pampa.mycloudgallery.feature.library.library.testing.syntheticLibraryState
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli screenshot del pacchetto H3 (fase H, i gesti della Libreria), in `docs/screenshots/beta-grafica/H3/`: il calendario del salto a una data
 * (R2) aperto su un mese con giorni pieni e spenti, la scelta rapida di anno e mese, il calendario con il testo grande. Chiaro e scuro, telefono
 * a 360 x 780 dp (il Galaxy S25 dell'utente) e tablet orizzontale (1280 x 800 dp). La scena e' quella di produzione (`GalleryRoot`): il host
 * dei pop-up alla radice, sopra la pagina e la pillola.
 *
 * `gradlew :feature-library:recordRoborazziDebug --tests dev.pampa.mycloudgallery.feature.library.library.LibraryH3PhoneScreenshotTest
 * --tests dev.pampa.mycloudgallery.feature.library.library.LibraryH3TabletScreenshotTest` scrive i PNG; nei normali `testDebugUnitTest` si
 * verifica solo che le schermate compongano. Robolectric registra un fotogramma a riposo e non sfoca: il vetro vero si giudica sul telefono.
 */
internal abstract class LibraryH3ScreenshotBase(private val dark: Boolean, private val device: String) {

  val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  private val look: String get() = if (dark) "scuro" else "chiaro"

  protected fun library(level: GridLevel = GridLevel.DAYS, commands: LibraryCommands = NoLibraryCommands): LibraryTabState =
    syntheticLibraryState(level = level, commands = commands)

  protected fun show(state: LibraryTabState, fontScale: Float = 1f) {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) { Scaffolded(state) }
      }
    }
    settle()
  }

  protected fun settle() {
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(SettleMillis)
    composeRule.waitForIdle()
  }

  @Composable
  private fun Scaffolded(state: LibraryTabState) {
    var backdrop by remember { mutableStateOf<GlassBackdropState?>(null) }
    val modal = rememberFluidGlassModalHostState()
    val rootBackdrop = rememberGlassBackdrop()
    CompositionLocalProvider(LocalFluidGlassModalHostState provides modal) {
      Box(modifier = Modifier.fillMaxSize()) {
        val rootSource = if (modal.isOnScreen) Modifier.glassBackdropSource(rootBackdrop) else Modifier
        Box(modifier = Modifier.fillMaxSize().then(rootSource).fluidGlassModalObscured()) {
          LibraryTab(
            contentPadding = PaddingValues(bottom = GalleryTabBarDefaults.ContentInset),
            backdropOut = { backdrop = it },
            onOpenViewer = {},
            onOpenSettings = {},
            state = state,
          )
          if (!state.selectionActive) {
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
        FluidGlassModalHost(state = modal, backdrop = rootBackdrop)
      }
    }
  }

  protected fun shootRoot(name: String) = composeRule.onRoot().captureRoboImage("$DIR/$name-$look-$device.png")

  protected fun scrollTo(state: LibraryTabState, fraction: Float) {
    composeRule.runOnIdle {
      val grid = state.grid
      grid.userScrolled = true
      grid.listState.requestScrollToItem((grid.layout!!.rowCount * fraction).toInt(), 0)
    }
    settle()
  }

  private fun openCalendar() {
    composeRule.onNodeWithTag(GalleryChromeTagPrefix + "titolo").performClick()
    settle()
  }

  // --- R2: il salto a una data ---------------------------------------------------------------------------------------------

  /** A meta' Libreria, la data in alto toccata: il calendario sul mese di quel giorno, il giorno segnato, i giorni con foto pieni e gli altri spenti. */
  @Test
  fun calendarioAperto() {
    val state = library()
    show(state)
    scrollTo(state, 0.5f)
    openCalendar()
    shootRoot("calendario-giorni")
  }

  /** Il titolo del mese toccato: la scelta rapida di anno e mese, con i mesi senza foto spenti. */
  @Test
  fun sceltaRapidaDiAnnoEMese() {
    val state = library()
    show(state)
    scrollTo(state, 0.5f)
    openCalendar()
    composeRule.onNodeWithTag(TitleTag).performClick()
    settle()
    shootRoot("calendario-scelta-mese")
  }

  /** Il calendario con il testo di sistema a 1,3x: le celle crescono, niente si taglia. */
  @Test
  fun calendarioConIlTestoGrande() {
    val state = library()
    show(state, fontScale = LargeText)
    scrollTo(state, 0.5f)
    openCalendar()
    shootRoot("calendario-testo-grande")
  }

  companion object {
    const val DIR = "../docs/screenshots/beta-grafica/H3"
    const val SettleMillis = 1_500L
    const val LargeText = 1.3f

    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w360dp-h780dp-xhdpi")
internal class LibraryH3PhoneScreenshotTest(dark: Boolean) : LibraryH3ScreenshotBase(dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w1280dp-h800dp-xhdpi")
internal class LibraryH3TabletScreenshotTest(dark: Boolean) : LibraryH3ScreenshotBase(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}
