package dev.pampa.mycloudgallery.feature.viewer

import android.view.WindowManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.center
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.up
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Il viewer su Robolectric, sui fake: si apre dall'elemento richiesto, scorre, si chiude (tasto, swipe giu'), mostra e
 * nasconde le barre, apre le informazioni. Le prove piu' specifiche (video, motion photo, stati, E-V*) stanno nei loro
 * file.
 */
class ViewerOverlayTest : ViewerTestBase() {

  private fun pager() = composeRule.onNodeWithTag(ViewerTags.Pager)

  @Test
  fun siApreSullElementoRichiestoEMostraLeBarre() {
    show()
    composeRule.onNodeWithContentDescription("5 di 50").assertExists()
    composeRule.onNodeWithContentDescription("Chiudi").assertExists()
    // La data come titolo, "ora · luogo" come sottotitolo: vengono dai dettagli del catalogo (fuso dello scatto: Oslo).
    composeRule.onNodeWithText("12 marzo 2024").assertExists()
    composeRule.onNodeWithText("12:41 · Oslo").assertExists()
  }

  @Test
  fun senzaRichiestaNonDisegnaNiente() {
    composeRule.setContent { ViewerTestHost { ViewerOverlay(request = null, onClosed = {}) } }
    composeRule.waitForIdle()
    composeRule.onAllNodesWithText("12 marzo 2024").assertCountEquals(0)
  }

  @Test
  fun ilTastoChiudeEChiamaOnClosedUnaVolta() {
    show()
    composeRule.onNodeWithContentDescription("Chiudi").performClick()
    composeRule.waitUntil(TIMEOUT) { closed == 1 }
    composeRule.waitForIdle()
    assertThat(closed).isEqualTo(1)
  }

  @Test
  fun scorrendoSiPassaAllaPaginaSuccessivaEPrecaricaSoloLeVicine() {
    show()
    pager().performTouchInput { swipeLeft() }
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("6 di 50").assertExists()
    pager().performTouchInput { swipeRight() }
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("5 di 50").assertExists()
    // Precarico di una pagina per lato: mai piu' di tre decodifiche insieme.
    assertThat(images.delegate.maxInFlight).isAtMost(3)
  }

  @Test
  fun chiudendoDopoAverScorsoLaLibreriaSegueIlViewer() {
    show()
    pager().performTouchInput { swipeLeft() }
    composeRule.waitForIdle()
    // La cella di cio' che si guarda e' portata in vista mentre si scorre, cosi' la chiusura e' gia' pronta.
    assertThat(fixture.anchor.revealed.last()).isEqualTo(fixture.idAt(5))
    fixture.placeCell(5)
    composeRule.onNodeWithContentDescription("Chiudi").performClick()
    composeRule.waitUntil(TIMEOUT) { closed == 1 }
  }

  @Test
  fun unTocco_nascondeELeMostraLeBarre() {
    show()
    composeRule.onNodeWithContentDescription("Chiudi").assertExists()
    pager().performTouchInput { click(center) }
    composeRule.waitUntil(TIMEOUT) { composeRule.onAllNodesWithContentDescription("Chiudi").fetchSemanticsNodes().isEmpty() }
    pager().performTouchInput { click(center) }
    composeRule.waitUntil(TIMEOUT) { composeRule.onAllNodesWithContentDescription("Chiudi").fetchSemanticsNodes().isNotEmpty() }
  }

  @Test
  fun unoSwipeVersoIlBassoChiude() {
    show()
    pager().performTouchInput { swipeDown() }
    composeRule.waitUntil(TIMEOUT) { closed == 1 }
  }

  @Test
  fun unoSwipeBreveNonChiudeETornaAPosto() {
    show()
    pager().performTouchInput {
      down(center)
      moveBy(Offset(0f, 90f), delayMillis = 50)
      up()
    }
    composeRule.waitForIdle()
    assertThat(closed).isEqualTo(0)
    composeRule.onNodeWithContentDescription("5 di 50").assertExists()
    composeRule.onNodeWithContentDescription("Chiudi").assertExists()
  }

  @Test
  fun unoSwipeVersoLAltoApreLeInformazioni() {
    show()
    pager().performTouchInput { swipeUp() }
    composeRule.waitForIdle()
    composeRule.onNodeWithTag(ViewerTags.InfoPanel).assertExists()
    // Data e ora con il fuso dello scatto, luogo con regione e paese, e la riga per aprire Mappe.
    composeRule.onNodeWithText("martedì 12 marzo 2024, 12:41", substring = true).assertExists()
    composeRule.onNodeWithText("ora di Oslo, UTC+1").assertExists()
    composeRule.onNodeWithText("Oslo, Norvegia", substring = true).assertExists()
    composeRule.onNodeWithText("Apri in Mappe").assertExists()
  }

  @Test
  fun conLeInformazioniApertePerOra_L_EXIFSiLeggeSoloAllora() {
    show()
    composeRule.onAllNodesWithText("Samsung Galaxy S25").assertCountEquals(0)
    pager().performTouchInput { swipeUp() }
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Samsung Galaxy S25").assertExists()
    composeRule.onNodeWithText("f/1.8 · 1/250 s · ISO 50".replace('.', ',')).assertExists()
  }

  @Test
  fun IndietroChiudePrimaLeInformazioniEPoiIlViewer() {
    show()
    pager().performTouchInput { swipeUp() }
    composeRule.waitForIdle()
    composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
    composeRule.waitForIdle()
    assertThat(closed).isEqualTo(0)
    composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
    composeRule.waitUntil(TIMEOUT) { closed == 1 }
  }

  @Test
  fun conIlViewerDeiNascostiLaFinestraEProtetta() {
    show(request = fixture.request(secure = true))
    val flags = composeRule.activity.window.attributes.flags
    assertThat(flags and WindowManager.LayoutParams.FLAG_SECURE).isNotEqualTo(0)
  }

  @Test
  fun senzaHiddenNonCeProtezione() {
    show()
    val flags = composeRule.activity.window.attributes.flags
    assertThat(flags and WindowManager.LayoutParams.FLAG_SECURE).isEqualTo(0)
  }

  @Test
  fun ingrandita_loSwipeVersoIlBassoNonChiude() {
    show()
    pager().performTouchInput { doubleClick(center) }
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("5 di 50").assertExists()
    composeRule.onNode(androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Ingrandita")).assertExists()
    pager().performTouchInput { swipeDown() }
    composeRule.waitForIdle()
    assertThat(closed).isEqualTo(0)
  }

  private companion object {
    const val TIMEOUT = 10_000L
  }
}
