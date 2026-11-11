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
import androidx.compose.ui.geometry.Offset
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
import dev.pampa.mycloudgallery.feature.library.grid.GridPullState
import dev.pampa.mycloudgallery.feature.library.grid.RegisterComponentActivity
import androidx.compose.ui.test.performTouchInput
import dev.pampa.mycloudgallery.feature.library.library.testing.syntheticLibraryState
import dev.pampa.mycloudgallery.core.library.contract.LibraryBackupStatus
import dev.pampa.mycloudgallery.core.library.contract.LibraryNasStatus
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
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

  /**
   * La Libreria per gli screenshot del calendario (H3, giro 1, voce 3): 12.000 elementi sugli stessi 15 anni della sintetica, circa due al giorno
   * come in una libreria vera. Con i 2.000 di serie un mese aveva uno o tre giorni con foto e il calendario sembrava vuoto.
   */
  protected fun denseLibrary(): LibraryTabState = syntheticLibraryState(count = CalendarLibrarySize)

  protected fun show(state: LibraryTabState, fontScale: Float = 1f, network: LibraryNetworkStatus? = null) {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) { Scaffolded(state, network) }
      }
    }
    // Oltre 6.000 elementi il layout si impagina su Default, che l'attesa di Compose non vede: si aspetta che ci sia.
    composeRule.waitUntil(CalendarReadyMillis) { (state.grid.layout?.rowCount ?: 0) > 0 }
    settle()
  }

  protected fun settle() {
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(SettleMillis)
    composeRule.waitForIdle()
  }

  @Composable
  private fun Scaffolded(state: LibraryTabState, network: LibraryNetworkStatus?) {
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
            network = network,
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

  /**
   * Tocca la data e aspetta il calendario **pronto** (H3, giro 1, CG-4: il PNG scuro col testo grande aveva fotografato lo stato di prima): i
   * giorni della vista contati e un giorno con foto del mese mostrato che si tocca. Solo allora lo screenshot.
   */
  private fun openCalendar(state: LibraryTabState) {
    composeRule.onNodeWithTag(GalleryChromeTagPrefix + "titolo").performClick()
    composeRule.waitUntil(CalendarReadyMillis) { state.grid.days.current(state.grid.view) != null }
    settle()
    val dayNodes = composeRule.onAllNodes(SemanticsMatcher("giorno del calendario") { node ->
      node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("calendario-giorno-") == true
    }).fetchSemanticsNodes()
    check(dayNodes.size >= 28) { "Il mese del calendario non c'e'." }
    check(dayNodes.any { SemanticsActions.OnClick in it.config }) { "Nessun giorno con foto pronto nel mese mostrato." }
  }

  // --- R2: il salto a una data ---------------------------------------------------------------------------------------------

  /** A meta' Libreria, la data in alto toccata: il calendario sul mese di quel giorno, il giorno segnato, i giorni con foto pieni e gli altri spenti. */
  @Test
  fun calendarioAperto() {
    val state = denseLibrary()
    show(state)
    scrollTo(state, 0.5f)
    openCalendar(state)
    shootRoot("calendario-giorni")
  }

  /** Il titolo del mese toccato: la scelta rapida di anno e mese, con i mesi senza foto spenti. */
  @Test
  fun sceltaRapidaDiAnnoEMese() {
    val state = denseLibrary()
    show(state)
    scrollTo(state, 0.5f)
    openCalendar(state)
    composeRule.onNodeWithTag(TitleTag).performClick()
    settle()
    shootRoot("calendario-scelta-mese")
  }

  /**
   * Il calendario con il testo di sistema a 1,3x: le celle crescono, niente si taglia. In fondo alla Libreria, cioe' sul mese di oggi: si vede
   * anche il segno di oggi.
   */
  @Test
  fun calendarioConIlTestoGrande() {
    val state = denseLibrary()
    show(state, fontScale = LargeText)
    openCalendar(state)
    shootRoot("calendario-testo-grande")
  }

  // --- D10: aggiornare tirando oltre il fondo ------------------------------------------------------------------------------

  /**
   * Il comando dell'aggiornamento c'e' (come nell'app): il gesto e' attivo. Come in produzione, l'aggiornamento fa partire un giro e la nuvola in
   * alto lo dice (H3, giro 1, CU-13): qui il backup passa a "in corso" e la lettura del telefono resta aperta per tutta la scena.
   */
  private class RefreshCommands(private val backup: MutableStateFlow<LibraryBackupStatus>) : LibraryCommands {
    override val canRefresh: Boolean get() = true
    override suspend fun refresh() {
      backup.value = LibraryBackupStatus.Running(done = 12, total = 40, etaMillis = 4 * 60_000L)
      awaitCancellation()
    }
  }

  /** La Libreria con la nuvola come in produzione: in casa, NAS aggiornato, tutto al sicuro; e il comando dell'aggiornamento. */
  private fun refreshable(): LibraryTabState {
    val base = syntheticLibraryState()
    val backup = MutableStateFlow<LibraryBackupStatus>(LibraryBackupStatus.AllSafe(atNasMillis = System.currentTimeMillis()))
    return LibraryTabState(
      grid = base.grid, viewerItems = base.viewerItems, viewerImages = base.viewerImages, photoCount = 0, videoCount = 0,
      timeline = base.timeline, preferences = base.preferences, actions = base.actions, phone = base.phone,
      commands = RefreshCommands(backup),
      nas = MutableStateFlow(LibraryNasStatus.UpToDate(atNasMillis = System.currentTimeMillis())),
      backup = backup,
    )
  }

  /**
   * In fondo alla Libreria, il dito sale oltre il fondo fino a uno spostamento visivo di [visualDp] e **resta giu'**: lo screenshot e' il
   * fotogramma del tiro (l'indicatore nello spazio aperto sopra la pillola).
   */
  private fun pullAndHold(visualDp: Float) {
    composeRule.onRoot().performTouchInput {
      val dimension = height * GridPullState.MaximumBounceFraction
      val raw = GridPullState.inverseRubberBand(visualDp * density, dimension) + viewConfiguration.touchSlop
      val start = Offset(width * 0.5f, height * 0.7f)
      down(start)
      val steps = 30
      for (i in 1..steps) {
        moveTo(start - Offset(0f, raw * i / steps))
        advanceEventTime(16)
      }
    }
    composeRule.waitForIdle()
  }

  /** A meta' strada verso la soglia: i raggi dello spinner si rivelano col dito. */
  @Test
  fun aggiornaAMeta() {
    show(refreshable(), network = HomeNetwork)
    pullAndHold(visualDp = 40f)
    shootRoot("aggiorna-a-meta")
  }

  /** Oltre la soglia (76 dp): lo spinner e' intero; al rilascio la Libreria si aggiorna. La nuvola dice ancora "tutto al sicuro". */
  @Test
  fun aggiornaOltreLaSoglia() {
    show(refreshable(), network = HomeNetwork)
    pullAndHold(visualDp = 96f)
    shootRoot("aggiorna-oltre-soglia")
  }

  /** Il dito si alza oltre la soglia: lo spazio resta aperto con lo spinner che gira, e l'esito si legge nella nuvola, che sincronizza (CU-13). */
  @Test
  fun aggiornaRilasciato() {
    show(refreshable(), network = HomeNetwork)
    pullAndHold(visualDp = 96f)
    composeRule.onRoot().performTouchInput { up() }
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(ReleasedMillis)
    composeRule.waitForIdle()
    shootRoot("aggiorna-rilasciato")
  }

  companion object {
    const val DIR = "../docs/screenshots/beta-grafica/H3"
    const val SettleMillis = 1_500L
    const val LargeText = 1.3f
    const val CalendarReadyMillis = 5_000L
    const val CalendarLibrarySize = 12_000

    /** Dopo il rilascio: la molla ha portato lo spazio a 56 dp, e il tetto di 2,5 s non e' ancora passato. */
    const val ReleasedMillis = 700L

    val HomeNetwork = LibraryNetworkStatus(severity = LibraryNetworkSeverity.Fine, label = "In casa", detail = "NAS collegato")

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
