package dev.pampa.mycloudgallery.feature.library.library

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.antigravity.fluidengine.ui.fluid.FluidFloatingTabBarDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTab
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTabBar
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTabBarDefaults
import dev.pampa.mycloudgallery.core.designsystem.design.ContentRange
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeContent
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeIconButton
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeTagPrefix
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryCloudState
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeCloudButton
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryTopChrome
import dev.pampa.mycloudgallery.core.designsystem.design.galleryTopChromeHeight
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.library.contract.LibraryBackupStatus
import dev.pampa.mycloudgallery.core.library.contract.LibraryBackupWait
import dev.pampa.mycloudgallery.core.library.contract.LibraryNasStatus
import dev.pampa.mycloudgallery.core.model.FreezeReason
import dev.pampa.mycloudgallery.core.model.NasArea
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
 * Gli screenshot del pacchetto G1 della beta (B1, B2, B7): la Libreria in fondo senza le tre scritte, a meta' con "torna in fondo" e
 * in cima; la nuvola in ogni stato; il pop-up senza problemi e con un problema; l'ingresso in selezione (prima e dopo); la chrome su
 * una foto molto chiara. Chiaro e scuro, telefono (412 dp) e tablet (1280 dp). La pillola e' disegnata come in `MainScaffold`.
 *
 * `gradlew :feature-library:recordRoborazziDebug --tests "*LibraryCloudScreenshot*"` scrive i PNG in `docs/screenshots/beta-grafica/G1/`;
 * nei normali `testDebugUnitTest` si verifica solo che le schermate compongano. Robolectric registra un fotogramma a riposo e non
 * sfoca: il movimento (B1) e il vetro vero si giudicano sul telefono.
 */
internal abstract class LibraryCloudScreenshotBase(private val dark: Boolean, private val device: String) {

  val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  private val look: String get() = if (dark) "scuro" else "chiaro"

  /** Lo spazio del NAS per il pop-up e "Libera spazio" disponibile. */
  private object CloudCommands : LibraryCommands {
    override val canDeleteFromPhone: Boolean get() = true
    override val canFreeSpace: Boolean get() = true
    override suspend fun nasSpace(): LibraryNasSpace = LibraryNasSpace.Ready(usedBytes = 1_250_000_000_000L, totalBytes = 3_600_000_000_000L)
  }

  private val homeNetwork = LibraryNetworkStatus(severity = LibraryNetworkSeverity.Fine, label = "In casa", detail = "NAS collegato")

  private fun library(
    nas: LibraryNasStatus = LibraryNasStatus.UpToDate(atNasMillis = System.currentTimeMillis()),
    backup: LibraryBackupStatus = LibraryBackupStatus.AllSafe(atNasMillis = System.currentTimeMillis()),
  ): LibraryTabState = syntheticLibraryState(nas = nas, backup = backup, commands = CloudCommands)

  private fun show(state: LibraryTabState, network: LibraryNetworkStatus? = homeNetwork) {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
        Scaffolded(state, network)
      }
    }
    settle()
  }

  /** Il tempo che serve alla molla e alla soglia dell'etichetta (600 ms) per posarsi: lo screenshot e' il fotogramma a riposo. */
  private fun settle() {
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(SettleMillis)
    composeRule.waitForIdle()
  }

  @Composable
  private fun Scaffolded(state: LibraryTabState, network: LibraryNetworkStatus?) {
    var backdrop by remember { mutableStateOf<GlassBackdropState?>(null) }
    Box(modifier = Modifier.fillMaxSize()) {
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
  }

  private fun shootRoot(name: String) = composeRule.onRoot().captureRoboImage("$DIR/$name-$look-$device.png")

  private fun scrollTo(state: LibraryTabState, fraction: Float) {
    composeRule.runOnIdle {
      val grid = state.grid
      grid.userScrolled = true
      grid.listState.requestScrollToItem((grid.layout!!.rowCount * fraction).toInt(), 0)
    }
    settle()
  }

  // --- La Libreria (B1, B2) ----------------------------------------------------------------------------------------------

  /** In fondo, sui piu' recenti: sotto la griglia solo il riepilogo, niente piu' rete, NAS e "tutto al sicuro". */
  @Test
  fun inFondoSenzaLeTreScritte() {
    show(library())
    shootRoot("libreria-in-fondo-senza-scritte")
  }

  @Test
  fun aMetaConTornaInFondo() {
    val state = library()
    show(state)
    scrollTo(state, 0.5f)
    shootRoot("libreria-a-meta-con-torna-in-fondo")
  }

  @Test
  fun inCima() {
    val state = library()
    show(state)
    scrollTo(state, 0f)
    shootRoot("libreria-in-cima")
  }

  @Test
  fun selezionePrimaEDopo() {
    val state = library()
    show(state)
    shootRoot("selezione-prima")
    composeRule.runOnIdle {
      val grid = state.grid
      grid.startSelecting()
      val l = grid.layout!!
      val center = grid.rowAtCenter(l)
      var picked = 0
      for (row in center - 2..center + 2) {
        if (row !in 0 until l.rowCount) continue
        for (p in l.itemsOf(row)) if (picked < 3) grid.idAt(p)?.let { grid.selection.toggle(it); picked++ }
      }
    }
    settle()
    shootRoot("selezione-dopo")
  }

  // --- La nuvola (B2) ----------------------------------------------------------------------------------------------------

  private fun shootCloud(name: String, state: LibraryTabState, network: LibraryNetworkStatus? = homeNetwork) {
    show(state, network)
    composeRule.onNodeWithTag("${GalleryChromeTagPrefix}comandi").captureRoboImage("$DIR/nuvola-$name-$look-$device.png")
  }

  @Test
  fun nuvolaTuttoSalvato() = shootCloud("salvato", library())

  @Test
  fun nuvolaInCorso() = shootCloud("in-corso", library(backup = LibraryBackupStatus.Running(done = 12, total = 40, etaMillis = 120_000L)))

  @Test
  fun nuvolaInAttesa() = shootCloud("in-attesa", library(backup = LibraryBackupStatus.Queued(items = 312, wait = LibraryBackupWait.CHARGING)))

  @Test
  fun nuvolaSenzaRete() = shootCloud(
    "senza-rete",
    library(nas = LibraryNasStatus.AreasUnavailable(setOf(NasArea.PRIVATE, NasArea.COMMON))),
    LibraryNetworkStatus(severity = LibraryNetworkSeverity.Offline, label = "Offline", detail = "Libreria sul telefono"),
  )

  @Test
  fun nuvolaDaGuardare() = shootCloud("da-guardare", library(nas = LibraryNasStatus.AreasUnavailable(setOf(NasArea.COMMON))))

  @Test
  fun nuvolaServeIntervento() = shootCloud("serve-intervento", library(nas = LibraryNasStatus.Frozen(FreezeReason.VANISHED, sinceNasMillis = 0L)))

  // --- Il pop-up (B2) ----------------------------------------------------------------------------------------------------

  private fun openCloud() {
    composeRule.onAllNodesWithContentDescription("Stato della sincronizzazione", substring = true)[0].performClick()
    settle()
  }

  @Test
  fun popUpSenzaProblemi() {
    show(library())
    openCloud()
    shootRoot("pop-up-senza-problemi")
  }

  @Test
  fun popUpSenzaProblemiConUnBackupInCorso() {
    show(library(backup = LibraryBackupStatus.Running(done = 1_240, total = 6_658, etaMillis = 80 * 60_000L)))
    openCloud()
    shootRoot("pop-up-backup-in-corso")
  }

  @Test
  fun popUpConUnProblema() {
    show(library(nas = LibraryNasStatus.Frozen(FreezeReason.VANISHED, sinceNasMillis = 0L), backup = LibraryBackupStatus.Problem(code = "nas_full", count = 0)))
    openCloud()
    shootRoot("pop-up-con-problema")
  }

  @Test
  fun popUpConLaReteDaSistemare() {
    show(
      library(),
      LibraryNetworkStatus(
        severity = LibraryNetworkSeverity.Attention,
        label = "VPN spenta",
        detail = "Apri WireGuard",
        action = LibraryNetworkAction.WIREGUARD,
      ),
    )
    openCloud()
    shootRoot("pop-up-rete")
  }

  // --- La chrome su una foto molto chiara (B7) ---------------------------------------------------------------------------

  /** Sotto la chrome una foto quasi bianca (cielo, neve, un foglio): inchiostro scuro, pellicola minima, il testo si legge. */
  @Test
  fun chromeSuUnaFotoMoltoChiara() {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
        val backdrop = rememberGlassBackdrop()
        val content = remember { LightPhoto }
        Box(modifier = Modifier.fillMaxSize()) {
          Box(modifier = Modifier.fillMaxSize().glassBackdropSource(backdrop).background(LightPhoto.color))
          GalleryTopChrome(
            content = content,
            backdrop = backdrop,
            title = "Sabato 12 ottobre 2026",
            subtitle = "Passo dello Stelvio",
            topChrome = galleryTopChromeHeight(),
          ) {
            GalleryChromeCloudButton(state = GalleryCloudState.Attention, contentDescription = "Nuvola", onClick = {})
            GalleryChromeIconButton(icon = Icons.Rounded.CheckCircleOutline, contentDescription = "Seleziona", onClick = {})
            GalleryChromeIconButton(icon = Icons.Rounded.MoreHoriz, contentDescription = "Vista", onClick = {})
          }
        }
      }
    }
    settle()
    shootRoot("chrome-su-foto-chiara")
  }

  /** Una foto uniforme quasi bianca come contenuto sotto la chrome (quello che i ThumbHash della griglia direbbero di una neve). */
  private object LightPhoto : GalleryChromeContent {
    val color = Color(0xFFF4F6F8)

    override fun chromeRowsUnder(bounds: Rect): Long = 1L

    override fun chromeContentUnder(bounds: Rect, page: Color, text: Color): ContentRange = ContentRange(listOf(color))

    override val chromeOriginInWindow: Offset get() = Offset.Zero

    override val chromeContentVersion: Any? get() = null
  }

  companion object {
    const val DIR = "../docs/screenshots/beta-grafica/G1"
    const val SettleMillis = 1_500L

    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w412dp-h891dp-xhdpi")
internal class LibraryCloudPhoneScreenshotTest(dark: Boolean) : LibraryCloudScreenshotBase(dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w1280dp-h800dp-xhdpi")
internal class LibraryCloudTabletScreenshotTest(dark: Boolean) : LibraryCloudScreenshotBase(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}
