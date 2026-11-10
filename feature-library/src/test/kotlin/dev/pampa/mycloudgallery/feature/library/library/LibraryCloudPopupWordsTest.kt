package dev.pampa.mycloudgallery.feature.library.library

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
import dev.pampa.mycloudgallery.core.library.contract.LibraryBackupStatus
import dev.pampa.mycloudgallery.core.library.contract.LibraryBackupWait
import dev.pampa.mycloudgallery.core.library.contract.LibraryNasStatus
import dev.pampa.mycloudgallery.core.model.FreezeReason
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
 * Le frasi del pop-up della nuvola dopo la review generale (pacchetto RG-D):
 *  - **3-bis**: sotto «Lavori fermi: cambiamenti anomali» c'e' il tasto "Apri la Diagnostica"; la frase non dice piu' «Tocca per i dettagli.»
 *    (non c'e' piu' niente da toccare sulla riga). Stesso residuo nella frase generica del backup;
 *  - **FAB-4**: il backup in coda per il risparmio energetico di Android dice «Risparmio energetico acceso: riprende quando lo spegni», non
 *    «Attesa batteria».
 *
 * `gradlew :feature-library:recordRoborazziDebug --tests "*LibraryCloudPopupWords*"` scrive i PNG in `docs/screenshots/beta-grafica/RG-D/`;
 * nei normali `testDebugUnitTest` si verifica solo che il pop-up compaia e dica le frasi giuste. Il telefono e' a 360 x 780 dp, il
 * Galaxy S25 dell'utente (pacchetto K2).
 */
internal abstract class LibraryCloudPopupWordsBase(private val dark: Boolean, private val device: String) {

  val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  private val look: String get() = if (dark) "scuro" else "chiaro"

  private val homeNetwork = LibraryNetworkStatus(severity = LibraryNetworkSeverity.Fine, label = "In casa", detail = "NAS collegato")

  private fun openPopup(nas: LibraryNasStatus = LibraryNasStatus.Idle, backup: LibraryBackupStatus = LibraryBackupStatus.Idle) {
    val state = syntheticLibraryState(nas = nas, backup = backup)
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
        // GF-23 b: come in produzione (`GalleryRoot`): il host dei pop-up sta alla radice, sopra la pagina e sopra la pillola delle schede, che il pop-up sfoca come il resto.
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
                network = homeNetwork,
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
    }
    settle()
    composeRule.onAllNodesWithContentDescription("Stato della sincronizzazione", substring = true)[0].performClick()
    settle()
  }

  /** Il tempo che serve alla molla e alla soglia dell'etichetta per posarsi: lo screenshot e' il fotogramma a riposo. */
  private fun settle() {
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(SettleMillis)
    composeRule.waitForIdle()
  }

  private fun shoot(name: String) = composeRule.onRoot().captureRoboImage("$DIR/$name-$look-$device.png")

  @Test
  fun iLavoriFermiNonDiconoPiuToccaPerIDettagliMaHannoIlTastoDellaDiagnostica() {
    openPopup(nas = LibraryNasStatus.Frozen(FreezeReason.VANISHED, sinceNasMillis = 0L))

    composeRule.onAllNodesWithText("Lavori fermi: cambiamenti anomali", substring = true).onFirst().assertExists()
    composeRule.onAllNodesWithText("Finché non controlli, sul NAS non si scrive e non si cancella niente.", substring = true).onFirst().assertExists()
    composeRule.onAllNodesWithText("Apri la Diagnostica").onFirst().assertExists()
    composeRule.onAllNodesWithText("Tocca per i dettagli", substring = true).assertCountEquals(0)
    shoot("pop-up-lavori-fermi")
  }

  @Test
  fun unProblemaDelBackupSenzaFraseSua_rimandaAllaPaginaNonAUnTocco() {
    // Un codice che il piede non conosce ancora: la frase generica, con il tasto "Apri Backup" sotto.
    openPopup(backup = LibraryBackupStatus.Problem(code = "codice_nuovo", count = 0))

    composeRule.onAllNodesWithText("Problema con il backup", substring = true).onFirst().assertExists()
    composeRule.onAllNodesWithText("I dettagli sono nella pagina Backup.", substring = true).onFirst().assertExists()
    composeRule.onAllNodesWithText("Apri Backup").onFirst().assertExists()
    composeRule.onAllNodesWithText("Tocca per i dettagli", substring = true).assertCountEquals(0)
  }

  @Test
  fun ilRisparmioEnergeticoHaLaSuaVoceNellaNuvola() {
    openPopup(backup = LibraryBackupStatus.Queued(items = 312, wait = LibraryBackupWait.POWER_SAVER))

    composeRule.onAllNodesWithText("312 in coda", substring = true).onFirst().assertExists()
    composeRule.onAllNodesWithText("Risparmio energetico acceso: riprende quando lo spegni", substring = true).onFirst().assertExists()
    composeRule.onAllNodesWithText("Attesa batteria", substring = true).assertCountEquals(0)
    shoot("pop-up-risparmio-energetico")
  }

  companion object {
    const val DIR = "../docs/screenshots/beta-grafica/RG-D"
    const val SettleMillis = 1_500L

    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w360dp-h780dp-xhdpi")
internal class LibraryCloudPopupWordsPhoneTest(dark: Boolean) : LibraryCloudPopupWordsBase(dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w1280dp-h800dp-xhdpi")
internal class LibraryCloudPopupWordsTabletTest(dark: Boolean) : LibraryCloudPopupWordsBase(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}
