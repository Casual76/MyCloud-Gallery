package dev.pampa.mycloudgallery.feature.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * H2: il pannello informazioni nel viewer vero, sui fake. Si apre con uno swipe in su; si chiude trascinandolo in giu' (anche dalla foto:
 * chiude il pannello, non il viewer), toccando la meta' alta o con due dita; resta aperto passando all'elemento dopo; su un telefono in
 * orizzontale e' la colonna laterale. La geometria (dove sta la foto, quanto e' grande) e lo scorrimento di un pannello lungo hanno i loro test
 * puri (`InfoGeometryTest`, `InfoPanelStateTest`).
 */
class ViewerInfoPanelTest : ViewerTestBase() {

  private fun pager() = composeRule.onNodeWithTag(ViewerTags.Pager)

  private fun panelShown(): Boolean = composeRule.onAllNodesWithTag(ViewerTags.InfoPanel).fetchSemanticsNodes().isNotEmpty()

  private fun openBySwipe() {
    pager().performTouchInput { swipeUp() }
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(SETTLE_MS)
    composeRule.waitForIdle()
    assertThat(panelShown()).isTrue()
  }

  private fun settle() {
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(SETTLE_MS)
    composeRule.waitForIdle()
  }

  @Test
  fun unToccoSullaMetaAltaChiudeIlPannello() {
    show()
    openBySwipe()
    // A pannello aperto la foto sta nella meta' alta: toccarla chiude il pannello (come nella Galleria di Samsung).
    pager().performTouchInput { click(Offset(centerX, height * 0.2f)) }
    // Il tocco arriva dopo l'attesa del doppio tocco (che a pannello aperto non fa niente).
    composeRule.waitUntil(TIMEOUT) { !panelShown() }
    assertThat(closed).isEqualTo(0)
  }

  @Test
  fun inGiuAPannelloApertoChiudeIlPannello_nonIlViewer() {
    show()
    openBySwipe()
    pager().performTouchInput { swipeDown(startY = height * 0.15f, endY = height * 0.45f) }
    settle()
    assertThat(panelShown()).isFalse()
    assertThat(closed).isEqualTo(0)
    composeRule.onNodeWithContentDescription("5 di 50").assertExists()
  }

  @Test
  fun dueDitaAPannelloApertoChiudonoIlPannello() {
    show()
    openBySwipe()
    pager().performTouchInput {
      val middle = Offset(centerX, height * 0.25f)
      pinch(
        start0 = middle - Offset(60f, 0f),
        end0 = middle - Offset(220f, 0f),
        start1 = middle + Offset(60f, 0f),
        end1 = middle + Offset(220f, 0f),
      )
    }
    settle()
    // Il pizzico non e' andato allo zoom: l'arbitro l'ha preso per il pannello (e il resto del gesto non ha ingrandito la foto).
    assertWithMessage("il pizzico ha ingrandito la foto")
      .that(composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Ingrandita")).fetchSemanticsNodes())
      .isEmpty()
    composeRule.waitUntil(TIMEOUT) { !panelShown() }
    assertThat(closed).isEqualTo(0)
  }

  @Test
  fun unoSwipeInGiuSubitoDopoAverAperto_chiudeIlPannello_nonIlViewer() {
    show()
    pager().performTouchInput { swipeUp() }
    // Senza aspettare la molla dell'apertura: lo stato dice gia' "aperto", lo swipe in giu' e' del pannello.
    pager().performTouchInput { swipeDown(startY = height * 0.15f, endY = height * 0.45f) }
    settle()
    assertThat(panelShown()).isFalse()
    assertThat(closed).isEqualTo(0)
  }

  @Test
  fun passandoAllElementoDopoIlPannelloRestaAperto() {
    show()
    openBySwipe()
    // Di lato, sulla foto in alto: il pager passa all'elemento accanto e il pannello resta dov'e', con le informazioni nuove.
    pager().performTouchInput {
      swipe(start = Offset(width * 0.15f, height * 0.25f), end = Offset(width * 0.85f, height * 0.25f), durationMillis = 200)
    }
    composeRule.waitForIdle()
    assertThat(composeRule.onAllNodesWithContentDescription("5 di 50").fetchSemanticsNodes()).isEmpty()
    assertThat(panelShown()).isTrue()
  }

  @Config(qualifiers = "it-w780dp-h360dp-land-xhdpi")
  @Test
  fun unTelefonoInOrizzontaleHaLaColonnaLaterale() {
    show()
    // In orizzontale la meta' schermo sarebbe una striscia: la colonna laterale, come sul tablet, si apre dal tasto.
    pager().performTouchInput { swipeUp() }
    composeRule.waitForIdle()
    assertThat(panelShown()).isFalse()
    composeRule.onNodeWithContentDescription("Informazioni").performClick()
    composeRule.waitUntil(TIMEOUT) { composeRule.onAllNodesWithContentDescription("Chiudi le informazioni").fetchSemanticsNodes().isNotEmpty() }
    assertThat(panelShown()).isTrue()
  }

  private companion object {
    const val TIMEOUT = 10_000L
    const val SETTLE_MS = 800L
  }
}
