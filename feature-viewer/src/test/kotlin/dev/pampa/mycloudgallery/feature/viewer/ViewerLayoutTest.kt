package dev.pampa.mycloudgallery.feature.viewer

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.center
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * E-V8 (ricreazione dell'activity, rotazione, split screen: id corrente, barre e zoom si ritrovano) e il pannello
 * informazioni sul tablet (da 840 dp: colonna laterale, niente swipe in su).
 */
class ViewerLayoutTest : ViewerTestBase() {

  private fun pager() = composeRule.onNodeWithTag(ViewerTags.Pager)

  private fun zoomedMatcher() = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Ingrandita")

  @Test
  fun e_V8_dopoLaRicreazioneSiRitrovanoLElementoCorrenteELeBarre() {
    val tester = StateRestorationTester(composeRule)
    val request = fixture.request()
    val services = services()
    tester.setContent { ViewerTestHost { ViewerOverlay(request = request, onClosed = { closed++ }, services = services) } }
    composeRule.waitForIdle()
    pager().performTouchInput { swipeToNext() }
    composeRule.waitForIdle()
    pager().performTouchInput { swipeToNext() }
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("7 di 50").assertExists()
    pager().performTouchInput { click(center) }
    composeRule.waitUntil(10_000L) { composeRule.onAllNodesWithContentDescription("Chiudi").fetchSemanticsNodes().isEmpty() }

    tester.emulateSavedInstanceStateRestore()
    composeRule.waitForIdle()

    composeRule.onNodeWithContentDescription("7 di 50").assertExists()
    composeRule.onAllNodesWithContentDescription("Chiudi").assertCountEquals(0)
    assertThat(closed).isEqualTo(0)
  }

  @Test
  fun e_V8_ilPannelloInformazioniApertoRestaApertoDopoLaRotazione() {
    val tester = StateRestorationTester(composeRule)
    val request = fixture.request()
    val services = services()
    tester.setContent { ViewerTestHost { ViewerOverlay(request = request, onClosed = { closed++ }, services = services) } }
    composeRule.waitForIdle()
    pager().performTouchInput { swipeUp() }
    composeRule.waitForIdle()
    composeRule.onNodeWithTag(ViewerTags.InfoPanel).assertExists()

    tester.emulateSavedInstanceStateRestore()
    composeRule.waitForIdle()

    composeRule.onNodeWithTag(ViewerTags.InfoPanel).assertExists()
    composeRule.onNodeWithContentDescription("Apri in Mappe").assertExists()
  }

  @Test
  fun e_V8_loZoomSiRitrovaDopoLaRicreazione() {
    val tester = StateRestorationTester(composeRule)
    val request = fixture.request()
    val services = services()
    tester.setContent { ViewerTestHost { ViewerOverlay(request = request, onClosed = { closed++ }, services = services) } }
    composeRule.waitForIdle()
    pager().performTouchInput { doubleClick(center) }
    composeRule.waitForIdle()
    composeRule.onNode(zoomedMatcher()).assertExists()

    tester.emulateSavedInstanceStateRestore()
    composeRule.waitForIdle()

    composeRule.onNode(zoomedMatcher()).assertExists()
    composeRule.onNodeWithContentDescription("5 di 50").assertExists()
  }

  @Test
  fun ilDoppioTocco_ingrandisceEUnSecondoDoppioTocco_Richiude() {
    show()
    pager().performTouchInput { doubleClick(center) }
    composeRule.waitForIdle()
    composeRule.onNode(zoomedMatcher()).assertExists()
    pager().performTouchInput { doubleClick(center) }
    composeRule.waitForIdle()
    composeRule.onAllNodesWithContentDescription("5 di 50").assertCountEquals(1)
    assertThat(composeRule.onAllNodes(zoomedMatcher()).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun ingrandita_ilPanArrivaAlBordoEDaLiPassaAlPager() {
    show()
    pager().performTouchInput { doubleClick(center) }
    composeRule.waitForIdle()
    composeRule.onNode(zoomedMatcher()).assertExists()
    var swipes = 0
    while (swipes < 12 && composeRule.onAllNodesWithContentDescription("6 di 50").fetchSemanticsNodes().isEmpty()) {
      pager().performTouchInput { swipeToNext() }
      composeRule.waitForIdle()
      swipes++
    }
    // Il primo swipe sposta la foto ingrandita, non il pager: serve piu' di un gesto per cambiare pagina.
    composeRule.onNodeWithContentDescription("6 di 50").assertExists()
    assertThat(swipes).isAtLeast(2)
    // Lasciata la pagina, la foto torna interamente nello schermo.
    assertThat(composeRule.onAllNodes(zoomedMatcher()).fetchSemanticsNodes()).isEmpty()
  }

  @Config(qualifiers = TABLET)
  @Test
  fun sulTabletLeInformazioniSonoUnaColonnaLateraleConLaFotoChePerdePosto() {
    show()
    composeRule.onNodeWithTag(ViewerTags.InfoPanel).assertDoesNotExist()
    // Su tablet lo swipe in su non apre niente: si apre dal tasto.
    pager().performTouchInput { swipeUp() }
    composeRule.waitForIdle()
    composeRule.onNodeWithTag(ViewerTags.InfoPanel).assertDoesNotExist()
    composeRule.onNodeWithContentDescription("Informazioni").performClick()
    composeRule.waitUntil(10_000L) { composeRule.onAllNodesWithContentDescription("Chiudi le informazioni").fetchSemanticsNodes().isNotEmpty() }
    composeRule.onNodeWithTag(ViewerTags.InfoPanel).assertExists()
    composeRule.onNodeWithContentDescription("Apri in Mappe").assertExists()
    // Le barre restano (accanto alla colonna) e la barra azioni anche.
    composeRule.onNodeWithContentDescription("Chiudi").assertExists()
    composeRule.onNodeWithContentDescription("Condividi").assertExists()
  }

  @Config(qualifiers = TABLET)
  @Test
  fun sulTabletIlTastoDellaColonnaLaChiude() {
    show()
    composeRule.onNodeWithContentDescription("Informazioni").performClick()
    composeRule.waitUntil(10_000L) { composeRule.onAllNodesWithContentDescription("Chiudi le informazioni").fetchSemanticsNodes().isNotEmpty() }
    composeRule.onNodeWithContentDescription("Chiudi le informazioni").performClick()
    composeRule.waitForIdle()
    composeRule.waitUntil(10_000L) { composeRule.onAllNodesWithContentDescription("Chiudi le informazioni").fetchSemanticsNodes().isEmpty() }
  }
}
