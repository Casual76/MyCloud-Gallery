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
import dev.pampa.mycloudgallery.core.library.contract.LibraryIndexSync
import dev.pampa.mycloudgallery.core.library.contract.LibraryNasStatus
import dev.pampa.mycloudgallery.feature.library.grid.RegisterComponentActivity
import dev.pampa.mycloudgallery.feature.library.library.testing.syntheticLibraryState
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Grafica XU (12/11/2026), rifatta nel Pacchetto XN (13/11, decisione dell'utente): la **sezione dell'analisi** in fondo al pop-up della nuvola, nella
 * scena di produzione (pop-up alla radice, sopra la Libreria e la pillola). Il titolo e il tasto restano del backup («Tutto al sicuro»); sotto il filo
 * l'anello indaco con il segno sull'arco, lo stato detto una volta, il conto o il perche', e la riga «Sul NAS». In corso, in coda, in pausa (caldo e
 * fuori dall'orario), finita, ferma per un problema, mai sul NAS, solo sul telefono; chiaro e scuro, telefono (360 x 780 dp, il S25) e tablet, piu' il
 * testo a 1,3x. Scrive in docs/screenshots/beta-grafica/X-indice-visibile.
 */
internal abstract class LibraryCloudAnalysisScreenshotBase(private val dark: Boolean, private val device: String) {

  val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  private val look: String get() = if (dark) "scuro" else "chiaro"

  private object CloudCommands : LibraryCommands {
    override val canDeleteFromPhone: Boolean get() = true
    override val canFreeSpace: Boolean get() = true
    override suspend fun nasSpace(): LibraryNasSpace = LibraryNasSpace.Ready(usedBytes = 1_250_000_000_000L, totalBytes = 3_600_000_000_000L)
  }

  private fun shoot(name: String, analysis: LibraryAnalysisStatus, fontScale: Float = 1f, indexSync: LibraryIndexSync = LibraryIndexSync.At(todayAt(14, 1))) {
    val state = syntheticLibraryState(
      nas = LibraryNasStatus.UpToDate(atNasMillis = todayAt(3, 12)),
      backup = LibraryBackupStatus.AllSafe(atNasMillis = todayAt(3, 12)),
      commands = CloudCommands,
      analysis = analysis,
      indexSync = indexSync,
    )
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) { Scaffolded(state) }
      }
    }
    settle()
    composeRule.onAllNodesWithContentDescription("Stato della sincronizzazione", substring = true)[0].performClick()
    settle()
    val suffix = if (fontScale != 1f) "-testo-grande" else ""
    composeRule.onRoot().captureRoboImage("$DIR/nuvola-$name-$look-$device$suffix.png")
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

  @Test
  fun inCorso() = shoot("analisi-in-corso", LibraryAnalysisStatus.Running(remaining = 2_700, etaMinutes = null, ready = 12_300, total = 15_000))

  @Test
  fun inCorsoTestoGrande() {
    org.junit.Assume.assumeTrue(device == "telefono" && !dark)
    shoot("analisi-in-corso", LibraryAnalysisStatus.Running(remaining = 2_700, etaMinutes = null, ready = 12_300, total = 15_000), fontScale = LARGE_TEXT)
  }

  @Test
  fun inPausa() = shoot("analisi-in-pausa", LibraryAnalysisStatus.Paused(LibraryAnalysisWait.THERMAL, remaining = 2_700, ready = 12_300, total = 15_000))

  @Test
  fun inCoda() {
    org.junit.Assume.assumeTrue(device == "telefono")
    shoot("analisi-in-coda", LibraryAnalysisStatus.Queued(remaining = 2_700, ready = 12_300, total = 15_000))
  }

  @Test
  fun unProblema() {
    org.junit.Assume.assumeTrue(device == "telefono")
    shoot("analisi-problema", LibraryAnalysisStatus.Problem("AI_STORAGE_LOW"))
  }

  /** XN: la pausa pianificata si vede, come stato tranquillo (il segno grigio della pausa, nessun colore d'allarme). */
  @Test
  fun fuoriDallOrario() {
    org.junit.Assume.assumeTrue(device == "telefono")
    shoot("analisi-fuori-orario", LibraryAnalysisStatus.Paused(LibraryAnalysisWait.NIGHT_WINDOW, remaining = 2_700, ready = 12_300, total = 15_000))
  }

  /** XN: finita, l'anello pieno e verde con la spunta comune. */
  @Test
  fun finita() = shoot("analisi-finita", LibraryAnalysisStatus.AllReady)

  @Test
  fun finitaTestoGrande() {
    org.junit.Assume.assumeTrue(device == "telefono" && !dark)
    shoot("analisi-finita", LibraryAnalysisStatus.AllReady, fontScale = LARGE_TEXT)
  }

  /** XN: l'indice non e' ancora andato sul NAS. */
  @Test
  fun maiSulNas() {
    org.junit.Assume.assumeTrue(device == "telefono")
    shoot("analisi-mai-sul-nas", LibraryAnalysisStatus.Running(remaining = 900, etaMinutes = null, ready = 100, total = 1_000), indexSync = LibraryIndexSync.Never)
  }

  /** XN: l'indice resta sul telefono, la riga «Sul NAS» non c'e'. */
  @Test
  fun soloSulTelefono() {
    org.junit.Assume.assumeTrue(device == "telefono" && !dark)
    shoot("analisi-solo-telefono", LibraryAnalysisStatus.Running(remaining = 900, etaMinutes = null, ready = 100, total = 1_000), indexSync = LibraryIndexSync.NotApplicable)
  }

  /** XN: un giorno passato, la data intera (lo stesso formato dell'ultimo giro del backup). */
  @Test
  fun sulNasGiorniFa() {
    org.junit.Assume.assumeTrue(device == "telefono" && !dark)
    val earlier = LocalDate.now().minusDays(2).atTime(3, 12).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    shoot("analisi-sul-nas-giorni-fa", LibraryAnalysisStatus.Queued(remaining = 2_700, ready = 12_300, total = 15_000), indexSync = LibraryIndexSync.At(earlier))
  }

  private fun todayAt(hour: Int, minute: Int): Long = LocalDate.now().atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

  companion object {
    const val DIR = "../docs/screenshots/beta-grafica/X-indice-visibile"
    const val SETTLE_MILLIS = 1_500L
    const val LARGE_TEXT = 1.3f

    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w360dp-h780dp-xhdpi")
internal class LibraryCloudAnalysisPhoneScreenshotTest(dark: Boolean) : LibraryCloudAnalysisScreenshotBase(dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w1280dp-h800dp-xhdpi")
internal class LibraryCloudAnalysisTabletScreenshotTest(dark: Boolean) : LibraryCloudAnalysisScreenshotBase(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}
