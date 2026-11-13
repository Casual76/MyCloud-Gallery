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
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import dev.pampa.mycloudgallery.core.library.contract.LibraryBackupWait
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
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.library.contract.LibraryAnalysisStatus
import dev.pampa.mycloudgallery.core.library.contract.LibraryAnalysisWait
import dev.pampa.mycloudgallery.core.library.contract.LibraryBackupStatus
import dev.pampa.mycloudgallery.core.library.contract.LibraryNasStatus
import dev.pampa.mycloudgallery.feature.library.grid.RegisterComponentActivity
import dev.pampa.mycloudgallery.feature.library.library.testing.syntheticLibraryState
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

import dev.pampa.mycloudgallery.core.designsystem.state.LocalBatteryThreshold
/**
 * Pacchetto B (seconda beta): il pop-up della nuvola della Libreria quando il backup e l'analisi sono fermi per la batteria, col livello scelto al 60%: «Batteria sotto il 60%»
 * (prima l'analisi diceva «Batteria scarica», falso con la soglia al 60% e il telefono al 55%). Stessa scena di produzione dell'analisi (pop-up alla radice, sopra la Libreria e la
 * pillola). `gradlew :feature-library:recordRoborazziDebug --tests "*LibraryCloudBatteryScreenshot*"` scrive in docs/screenshots/beta-grafica/B-soglia-batteria.
 */
internal abstract class LibraryCloudBatteryScreenshotBase(private val dark: Boolean) {

  val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  private object CloudCommands : LibraryCommands {
    override val canDeleteFromPhone: Boolean get() = true
    override val canFreeSpace: Boolean get() = true
    override suspend fun nasSpace(): LibraryNasSpace = LibraryNasSpace.Ready(usedBytes = 1_250_000_000_000L, totalBytes = 3_600_000_000_000L)
  }

  @Test
  fun backupEAnalisiFermiPerLaBatteria() {
    val state = syntheticLibraryState(
      nas = LibraryNasStatus.UpToDate(atNasMillis = System.currentTimeMillis()),
      backup = LibraryBackupStatus.Queued(items = 312, wait = LibraryBackupWait.BATTERY),
      commands = CloudCommands,
      analysis = LibraryAnalysisStatus.Paused(LibraryAnalysisWait.BATTERY, remaining = 2_700, ready = 12_300, total = 15_000),
    )
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
        CompositionLocalProvider(LocalBatteryThreshold provides 60) { Scaffolded(state) }
      }
    }
    settle()
    composeRule.onAllNodesWithContentDescription("Stato della sincronizzazione", substring = true)[0].performClick()
    settle()
    composeRule.onAllNodesWithText("Batteria sotto il 60%", substring = true, useUnmergedTree = true).onFirst().assertExists()
    composeRule.onRoot().captureRoboImage("$DIR/nuvola-fermo-batteria-60-${if (dark) "scuro" else "chiaro"}-telefono.png")
  }

  private fun settle() {
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(SETTLE_MILLIS)
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
            network = null,
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
        FluidGlassModalHost(state = modal, backdrop = rootBackdrop)
      }
    }
  }

  companion object {
    const val DIR = "../docs/screenshots/beta-grafica/B-soglia-batteria"
    const val SETTLE_MILLIS = 1_500L
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w360dp-h780dp-xhdpi")
internal class LibraryCloudBatteryPhoneScreenshotTest(dark: Boolean) : LibraryCloudBatteryScreenshotBase(dark) {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}
