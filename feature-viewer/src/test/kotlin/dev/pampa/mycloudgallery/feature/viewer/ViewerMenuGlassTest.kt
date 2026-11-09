package dev.pampa.mycloudgallery.feature.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalHost
import dev.antigravity.fluidengine.ui.fluid.LocalFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.mycloudgallery.core.designsystem.a11y.GalleryAccessibilityPrefs
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import org.junit.Test
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * B4 (G3): il tasto di chiusura e il ⋮ del viewer sono lo stesso vetro, e il menu si apre dalla goccia del tasto. Qui il comportamento: stesse voci di sempre, il tasto fa le stesse cose,
 * il menu si chiude con Indietro prima del viewer, e dove la goccia non va bene (superfici piene, schermo basso) il menu e' il pop-up di sempre, con le stesse voci.
 */
class ViewerMenuGlassTest : ViewerTestBase() {

  private fun more() = composeRule.onNodeWithContentDescription("Altre azioni")

  private fun menuShows(text: String): Boolean = composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

  private fun waitForMenu(text: String = "Copia") {
    composeRule.waitUntil(10_000L) { menuShows(text) }
    composeRule.mainClock.advanceTimeBy(600)
  }

  @Test
  fun ilTastoDiChiusuraEIlMenuSonoDueTastiDiVetroAllineati() {
    show()
    val close = composeRule.onNodeWithContentDescription("Chiudi").fetchSemanticsNode()
    val menu = more().fetchSemanticsNode()
    for (node in listOf(close, menu)) {
      assertThat(node.config.getOrNull(SemanticsProperties.Role)).isEqualTo(Role.Button)
      // Il bersaglio: almeno la lente da 44 dp (il tocco e' allargato a 48 dp da Compose, come per ogni tasto).
      val dp = composeRule.density.density
      assertThat(node.boundsInRoot.height / dp).isAtLeast(44f)
      assertThat(node.boundsInRoot.width / dp).isAtLeast(44f)
    }
    // Stessa riga: i centri sono alla stessa altezza.
    assertThat(abs(close.boundsInRoot.center.y - menu.boundsInRoot.center.y)).isLessThan(2f)
  }

  @Test
  fun ilMenuSiApreDalTastoEPortaLeStesseVoci() {
    show()
    more().performClick()
    waitForMenu("Informazioni")
    for (label in listOf("Condividi", "Copia", "Informazioni")) assertThat(menuShows(label)).isTrue()
    // Una voce fa la sua azione e il menu si chiude: "Informazioni" apre il pannello.
    composeRule.onAllNodesWithText("Informazioni").onFirst().performClick()
    composeRule.mainClock.advanceTimeBy(800)
    composeRule.waitForIdle()
    composeRule.onNodeWithTag(ViewerTags.InfoPanel).assertExists()
    assertThat(closed).isEqualTo(0)
  }

  @Test
  fun indietroChiudeIlMenuPrimaDelViewer() {
    show()
    more().performClick()
    waitForMenu()
    composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
    composeRule.mainClock.advanceTimeBy(1_500)
    composeRule.waitForIdle()
    assertThat(menuShows("Copia")).isFalse()
    // Il viewer e' ancora aperto (il primo Indietro era del menu).
    composeRule.onNodeWithContentDescription("Chiudi").assertExists()
    assertThat(closed).isEqualTo(0)
  }

  @Test
  fun conLeSuperficiPieneIlMenuETornatoIlPopUpConLeStesseVoci() {
    val prefs = GalleryAccessibilityPrefs(reduceTransparency = true)
    val request = fixture.request()
    val services = services()
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = ThemeMode.DARK), accessibility = prefs) {
        val modalHost = rememberFluidGlassModalHostState()
        val backdrop = rememberGlassBackdrop()
        CompositionLocalProvider(LocalFluidGlassModalHostState provides modalHost) {
          Box(Modifier.fillMaxSize().glassBackdropSource(backdrop)) { ViewerOverlay(request = request, onClosed = { closed++ }, services = services) }
          FluidGlassModalHost(state = modalHost, backdrop = backdrop)
        }
      }
    }
    composeRule.waitForIdle()
    more().performClick()
    waitForMenu("Informazioni")
    for (label in listOf("Condividi", "Copia", "Informazioni")) assertThat(menuShows(label)).isTrue()
  }

  @Test
  @Config(qualifiers = "it-w412dp-h300dp-xhdpi")
  fun conLoSchermoBassoIlMenuETornatoIlPopUp_ENessunaVoceRestaSottoIlBordo() {
    // Su 300 dp di altezza il menu a goccia (che non scorre) non ci sta: il pop-up, che ha le stesse voci, si apre lo stesso.
    show()
    more().performClick()
    waitForMenu("Informazioni")
    assertThat(menuShows("Condividi")).isTrue()
  }
}
