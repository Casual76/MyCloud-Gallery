package dev.pampa.mycloudgallery.feature.library

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.library.contract.GridLevel
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.library.grid.MediaGrid
import dev.pampa.mycloudgallery.feature.library.library.LibraryTab
import dev.pampa.mycloudgallery.feature.library.library.emptyLibraryTabState
import dev.pampa.mycloudgallery.feature.library.testing.syntheticLibraryTabState
import dev.pampa.mycloudgallery.feature.library.grid.cellBelowChrome
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli stub di P0 di `feature-library` su Robolectric: la griglia disegna le celle della Libreria sintetica e un tocco apre
 * l'elemento; la scheda vuota dice perche' e' vuota; la scheda sui fake consegna il fondale e manda la richiesta del
 * viewer con la geometria della miniatura.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w412dp-h915dp-xhdpi")
class LibraryStubTest {

  val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  /** L'activity vuota della regola di Compose non e' nel manifest di un modulo libreria: la si registra a mano. */
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

  private fun show(content: @Composable () -> Unit) {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = ThemeMode.LIGHT)) { content() }
    }
    composeRule.waitForIdle()
  }

  @Test
  fun laGrigliaDisegnaLeCelleSinteticheEUnToccoApreLElemento() {
    val state = syntheticLibraryTabState(count = 400, level = GridLevel.DAYS)
    val opened = mutableListOf<MediaId>()
    show { MediaGrid(state = state.grid, contentPadding = PaddingValues(), onOpen = { opened += it }) }

    // Si apre in fondo (D1) e ci sono celle disegnate, con una descrizione per i lettori di schermo.
    val cells = composeRule.onAllNodesWithContentDescription("Foto, ", substring = true)
    assertThat(cells.fetchSemanticsNodes()).isNotEmpty()
    cells[0].performClick()
    assertThat(opened).hasSize(1)
    // La geometria della cella toccata e' nota al viewer (ViewerAnchor).
    assertThat(state.grid.viewerAnchor.boundsOf(opened.single())).isNotNull()
  }

  @Test
  fun laSchedaVuotaDicePercheEVuota() {
    show {
      LibraryTab(
        contentPadding = PaddingValues(),
        backdropOut = {},
        onOpenViewer = {},
        onOpenSettings = {},
        state = emptyLibraryTabState(),
      )
    }
    composeRule.onNodeWithText("La Libreria è vuota").assertExists()
    composeRule.onNodeWithText("Libreria").assertExists()
    // B2 della beta: lo stato non sta piu' sotto lo stato vuoto (lo dice la nuvola in alto), e senza niente da dire la nuvola non c'e'.
    assertThat(composeRule.onAllNodesWithContentDescription("Stato della sincronizzazione", substring = true).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun laSchedaSuiFakeConsegnaIlFondaleEMandaLaRichiestaDelViewer() {
    val state = syntheticLibraryTabState(count = 400)
    var backdrop: GlassBackdropState? = null
    val requests = mutableListOf<ViewerRequest>()
    show {
      LibraryTab(
        contentPadding = PaddingValues(),
        backdropOut = { backdrop = it },
        onOpenViewer = { requests += it },
        onOpenSettings = {},
        state = state,
      )
    }
    assertThat(backdrop).isNotNull()
    // Fase H (D2): una cella fuori dalla chrome in alto (la prima della lista sta sotto l'etichetta, che trattiene i tocchi).
    composeRule.cellBelowChrome().performClick()
    assertThat(requests).hasSize(1)
    val request = requests.single()
    assertThat(request.secure).isFalse()
    assertThat(request.items.view.value.viewIndexOf(request.start)).isAtLeast(0)
  }
}
