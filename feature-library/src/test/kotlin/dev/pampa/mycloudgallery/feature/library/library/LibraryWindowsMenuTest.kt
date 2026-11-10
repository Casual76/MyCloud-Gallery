package dev.pampa.mycloudgallery.feature.library.library

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.designsystem.adaptive.GalleryWidth
import dev.pampa.mycloudgallery.core.designsystem.adaptive.GalleryWindow
import dev.pampa.mycloudgallery.core.designsystem.adaptive.LocalNewWindow
import dev.pampa.mycloudgallery.core.designsystem.adaptive.NewWindowPort
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTabBarDefaults
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
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
 * "Apri in una nuova finestra" nel menu della Libreria (Fase 10, P4, D16, E-T6): la voce c'e' solo dove la porta la offre (finestra estesa o in DeX), apre la Libreria in una
 * seconda finestra e non c'e' senza porta (test, anteprime, Android sotto la 12). Telefono e tablet, chiaro e scuro: gli screenshot del menu (`gradlew
 * :feature-library:recordRoborazziDebug --tests "*LibraryWindowsMenu*"` scrive i PNG in `docs/screenshots/10-P4/`; nei test normali si verifica solo la voce).
 */
internal abstract class LibraryWindowsMenuBase(private val dark: Boolean, private val device: String, private val expected: Boolean) {

  val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  /** La porta di `:app` con le stesse regole di `WindowPolicy` (840 dp), che registra cosa si apre. */
  private class RecordingPort : NewWindowPort {
    val opened = mutableListOf<String>()

    override fun isOffered(window: GalleryWindow): Boolean = window.width >= GalleryWidth.EXPANDED

    override fun openLibrary() {
      opened += "libreria"
    }

    override fun openAlbum(albumId: String) {
      opened += "album"
    }

    override fun openMemory(memoryId: String) {
      opened += "ricordo"
    }

    override fun openMap() {
      opened += "mappa"
    }
  }

  private fun show(port: NewWindowPort?) {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
        CompositionLocalProvider(LocalNewWindow provides port) {
          Box(modifier = Modifier.fillMaxSize()) {
            LibraryTab(
              contentPadding = PaddingValues(bottom = GalleryTabBarDefaults.ContentInset),
              backdropOut = {},
              onOpenViewer = {},
              onOpenSettings = {},
              state = syntheticLibraryState(),
            )
          }
        }
      }
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("Vista").performClick()
    composeRule.waitForIdle()
  }

  private val look: String get() = if (dark) "scuro" else "chiaro"

  @Test
  fun laVoceSiOffreSoloDoveLaPortaLaOffre() {
    show(RecordingPort())
    composeRule.onAllNodesWithText(ENTRY).assertCountEquals(if (expected) 1 else 0)
    composeRule.onRoot().captureRoboImage("$DIR/libreria-menu-nuova-finestra-$look-$device.png")
  }

  @Test
  fun scegliendolaApreLaLibreriaInUnaSecondaFinestra() {
    if (!expected) return
    val port = RecordingPort()
    show(port)
    // L'azione di tocco della voce (la voce del menu sta in un pop-up che nel test non ha ancora una misura: un tocco a coordinate non la raggiungerebbe).
    composeRule.onNodeWithText(ENTRY).performSemanticsAction(SemanticsActions.OnClick)
    composeRule.waitForIdle()
    assertThat(port.opened).containsExactly("libreria")
  }

  @Test
  fun senzaPortaLaVoceNonCe() {
    show(null)
    composeRule.onAllNodesWithText(ENTRY).assertCountEquals(0)
    // Le altre voci del menu ci sono sempre.
    composeRule.onNodeWithText("Impostazioni").assertExists()
  }

  companion object {
    const val DIR = "../docs/screenshots/10-P4"
    const val ENTRY = "Apri in una nuova finestra"

    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w412dp-h915dp-xhdpi")
internal class LibraryWindowsMenuPhoneTest(dark: Boolean) : LibraryWindowsMenuBase(dark, "telefono", expected = false) {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Alto abbastanza perche' il menu (lungo: livelli, filtri, stile, le due voci in coda) si veda per intero.
@Config(application = Application::class, qualifiers = "w1280dp-h1100dp-xhdpi")
internal class LibraryWindowsMenuTabletTest(dark: Boolean) : LibraryWindowsMenuBase(dark, "tablet", expected = true) {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}
