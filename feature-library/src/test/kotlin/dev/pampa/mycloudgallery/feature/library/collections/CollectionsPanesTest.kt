package dev.pampa.mycloudgallery.feature.library.collections

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.adaptive.GalleryWindow
import dev.pampa.mycloudgallery.core.designsystem.adaptive.ProvideGalleryWindow
import dev.pampa.mycloudgallery.feature.library.collections.testing.FakeCollectionsWorld
import org.junit.Test

/**
 * Fase 10 (P5, D12, D13 (1), E-T2): le Raccolte a elenco e dettaglio secondo la finestra comune, alle larghezze del riquadro (360, 600, 840 e 1280 dp): una pagina sola sotto 840 dp, due pannelli sopra, senza
 * sovrapposizioni; passare la soglia e tornare non perde la raccolta scelta.
 */
class CollectionsPanesTest : ComposeScreenTest() {

  private val world = FakeCollectionsWorld()
  private val opened = mutableListOf<CollectionKind>()
  private var widthDp by mutableStateOf(1280)

  /** Compone le Raccolte una volta sola (`setContent` si chiama una volta per test): la larghezza della finestra la cambia il test. */
  private fun start(initialWidthDp: Int) {
    widthDp = initialWidthDp
    show {
      ProvideGalleryWindow(GalleryWindow.of(widthDp, 900)) {
        Box(Modifier.requiredSize(widthDp.dp, 900.dp)) {
          CollectionsScreen(
            state = layoutOf(world),
            contentPadding = PaddingValues(),
            backdropOut = {},
            onOpenCollection = { opened += it },
            onOpenSettings = {},
            statusHeader = {},
            detail = { kind -> Text("dettaglio ${kind.code}") },
          )
        }
      }
    }
  }

  private fun resizeTo(dp: Int) {
    widthDp = dp
    composeRule.waitForIdle()
  }

  @Test
  fun sottoLaSoglia_unaPaginaSola_eUnaVoceApreLaRotta() {
    start(360)
    for (dp in listOf(360, 600, 839)) {
      resizeTo(dp)
      composeRule.onAllNodesWithText("Scegli una raccolta").assertCountEquals(0)
    }
    // CS-17: le righe che si scelgono sono quelle di produzione (i Nascosti), non piu' le raccolte-filtro del vecchio "Telefono".
    composeRule.onNodeWithText("Nascosti").performClick()
    composeRule.waitForIdle()
    assertThat(opened).containsExactly(CollectionKind.HIDDEN)
  }

  @Test
  fun daEstesa_elencoEDettaglioAffiancati_senzaSovrapporsi() {
    start(840)
    for (dp in listOf(840, 1280)) {
      resizeTo(dp)
      composeRule.onNodeWithText("Scegli una raccolta").assertExists()
      val list = composeRule.onNodeWithText("Nascosti").getUnclippedBoundsInRoot()
      val detail = composeRule.onNodeWithText("Scegli una raccolta").getUnclippedBoundsInRoot()
      // L'elenco sta a sinistra e il dettaglio comincia dopo di lui.
      assertThat(list.right).isAtMost(detail.left)
    }
    composeRule.onNodeWithText("Nascosti").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("dettaglio hidden").assertExists()
    assertThat(opened).isEmpty()
  }

  @Test
  fun attraversareLaSogliaNonPerdeLaRaccoltaScelta() {
    start(1280)
    composeRule.onNodeWithText("Nascosti").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("dettaglio hidden").assertExists()
    // Una finestra libera che si stringe (E-T2): una pagina sola, la scelta non e' persa; poi si riallarga e il dettaglio e' quello di prima.
    for (dp in listOf(700, 1280, 360, 900)) {
      resizeTo(dp)
      if (dp >= 840) composeRule.onNodeWithText("dettaglio hidden").assertExists() else composeRule.onAllNodesWithText("dettaglio hidden").assertCountEquals(0)
    }
    assertThat(opened).isEmpty()
  }
}
