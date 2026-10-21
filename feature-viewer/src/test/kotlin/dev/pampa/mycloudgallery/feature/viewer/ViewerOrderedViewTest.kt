package dev.pampa.mycloudgallery.feature.viewer

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.index.IndexOrderedTimelineView
import org.junit.Test

/**
 * Fase 6 (P0, D28, §1.2 "core-library"): il viewer scorre una vista in un ordine che NON e' quello del tempo (i risultati di una
 * ricerca, "Pertinenti"), con gli elementi del viewer di prova. Si apre sull'elemento richiesto nella sua posizione della vista, e
 * scorrendo passa all'elemento dopo **nella vista**, non nella timeline.
 */
class ViewerOrderedViewTest : ViewerTestBase() {

  /** Dal piu' vecchio al piu' recente: le posizioni dell'indice decrescono (una ricerca binaria sbaglierebbe). */
  private val ordered by lazy {
    IndexOrderedTimelineView(fixture.library.snapshot, (fixture.library.count - 1 downTo 0).toList().toIntArray())
  }

  @Test
  fun ilViewerScorreLaVistaNellOrdineDeiRisultati() {
    fixture.items.publish(ordered)
    // Si parte dall'indice 4 della Libreria, che nella vista ordinata e' alla posizione 45 (46 di 50).
    show(request = fixture.request(startIndex = 4))
    composeRule.onNodeWithContentDescription("46 di 50").assertExists()

    composeRule.onNodeWithTag(ViewerTags.Pager).performTouchInput { swipeLeft() }
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("47 di 50").assertExists()
    // Il successivo nella vista e' l'indice 3 della Libreria (piu' recente), non il 5.
    assertThat(fixture.anchor.revealed.last()).isEqualTo(fixture.idAt(3))

    // Indietro di uno: di nuovo l'elemento di partenza (la sua cella e' gia' sullo schermo, non serve portarla in vista).
    composeRule.onNodeWithTag(ViewerTags.Pager).performTouchInput { swipeRight() }
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("46 di 50").assertExists()
    assertThat(fixture.anchor.revealed).doesNotContain(fixture.idAt(5))
  }
}
