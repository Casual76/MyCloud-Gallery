package dev.pampa.mycloudgallery.feature.viewer

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.IntSize
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.imaging.contract.ViewerImageSource
import dev.pampa.mycloudgallery.core.imaging.contract.ViewerImageState
import dev.pampa.mycloudgallery.core.library.index.IndexOrderedTimelineView
import dev.pampa.mycloudgallery.core.model.MediaRef
import kotlinx.coroutines.flow.Flow
import org.junit.Test

/**
 * B5 (G3): "le foto e i video piu' vecchi si dovrebbero trovare a sinistra di quello messo fullscreen". Nella Libreria la posizione 0 e' la piu' recente, quindi le piu' vecchie sono le
 * posizioni alte e devono stare a sinistra: uno swipe verso destra porta alla piu' vecchia. Qui il pager vero con i gesti, la tastiera, il precarico, l'eliminazione, la ricreazione e il
 * ritorno alla cella; la regola pura e' in [ViewerPagingTest]. Le prove con `swipeToNext` (le altre del modulo) dicono "alla foto dopo" e cambiano con l'ordine; queste dicono il verso.
 */
@OptIn(ExperimentalTestApi::class)
class ViewerPagerOrderTest : ViewerTestBase() {

  private fun pager() = composeRule.onNodeWithTag(ViewerTags.Pager)

  private fun press(key: Key) {
    composeRule.onRoot().performKeyInput { pressKey(key) }
    composeRule.waitForIdle()
  }

  /** Un'immagine che ricorda quali riferimenti le sono stati chiesti: serve a vedere chi precarica. */
  private class RecordingImages(private val inner: ViewerImageSource) : ViewerImageSource by inner {
    val requested = LinkedHashSet<MediaRef>()

    override fun state(ref: MediaRef, screen: IntSize): Flow<ViewerImageState> {
      requested += ref
      return inner.state(ref, screen)
    }
  }

  @Test
  fun unoSwipeVersoDestraPortaAllaFotoPiuVecchia_versoSinistraAllaPiuRecente() {
    show()
    composeRule.onNodeWithContentDescription("5 di 50").assertExists()

    pager().performTouchInput { swipeRight() }
    composeRule.waitForIdle()
    // La posizione 5 della Libreria e' piu' vecchia della 4: la sua cella e' portata in vista.
    composeRule.onNodeWithContentDescription("6 di 50").assertExists()
    assertThat(fixture.library.snapshot.sortTimeAt(5)).isLessThan(fixture.library.snapshot.sortTimeAt(4))
    assertThat(fixture.anchor.revealed.last()).isEqualTo(fixture.idAt(5))

    pager().performTouchInput { swipeLeft() }
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("5 di 50").assertExists()
    pager().performTouchInput { swipeLeft() }
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("4 di 50").assertExists()
    assertThat(fixture.library.snapshot.sortTimeAt(3)).isGreaterThan(fixture.library.snapshot.sortTimeAt(4))
    assertThat(fixture.anchor.revealed.last()).isEqualTo(fixture.idAt(3))
  }

  @Test
  fun lePagineVicineSiPrecaricanoDaTuttiEDueIlati() {
    val recording = RecordingImages(images)
    show(services = services(imageSource = recording))
    composeRule.waitForIdle()
    val phone = { index: Int -> fixture.items.ref(fixture.idAt(index)) }
    // Aperto sulla posizione 4: il precarico copre la 3 (piu' recente, a destra) e la 5 (piu' vecchia, a sinistra), e non le altre.
    assertThat(recording.requested).containsAtLeast(phone(3), phone(4), phone(5))
    assertThat(recording.requested).doesNotContain(phone(7))
    assertThat(recording.requested).doesNotContain(phone(1))
  }

  @Test
  fun leFreccePortanoDoveSiVede() {
    // La freccia sinistra va alla pagina a sinistra (la piu' vecchia), la destra a quella a destra: lo stesso verso dei gesti.
    show()
    composeRule.onNodeWithContentDescription("5 di 50").assertExists()
    press(Key.DirectionLeft)
    composeRule.onNodeWithContentDescription("6 di 50").assertExists()
    press(Key.DirectionRight)
    press(Key.DirectionRight)
    composeRule.onNodeWithContentDescription("4 di 50").assertExists()
  }

  @Test
  fun leFrecceNonUsciranoDallaLista() {
    // In cima (la piu' recente) non c'e' una pagina a destra: la freccia destra non fa niente e quella sinistra va alla piu' vecchia.
    show(request = fixture.request(startIndex = 0))
    press(Key.DirectionRight)
    composeRule.onNodeWithContentDescription("1 di 50").assertExists()
    press(Key.DirectionLeft)
    composeRule.onNodeWithContentDescription("2 di 50").assertExists()
  }

  @Test
  fun eliminandoLaFotoSiVaAQuellaCheNeTieneIlPosto_laPiuVecchia() {
    // E-V6 e l'eliminazione: la foto che sparisce lascia il posto a quella che la seguiva nella vista (la piu' vecchia, che sta a sinistra), come prima di B5; se era l'ultima, alla
    // precedente. Cambia da che parte si vede il vicino, non quale.
    show()
    fixture.items.publish(fixture.without(setOf(fixture.idAt(4))))
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("5 di 49").assertExists()
    // La foto che e' li' adesso e' la ex 5, la piu' vecchia: da qui, uno swipe verso sinistra torna a quella piu' recente (la ex 3).
    pager().performTouchInput { swipeLeft() }
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("4 di 49").assertExists()
    assertThat(fixture.anchor.revealed.last()).isEqualTo(fixture.idAt(3))
  }

  @Test
  fun dopoLaRicreazioneLeVecchieSonoAncoraASinistra() {
    val tester = StateRestorationTester(composeRule)
    val request = fixture.request()
    val services = services()
    tester.setContent { ViewerTestHost { ViewerOverlay(request = request, onClosed = { closed++ }, services = services) } }
    composeRule.waitForIdle()
    pager().performTouchInput { swipeRight() }
    composeRule.waitForIdle()
    pager().performTouchInput { swipeRight() }
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("7 di 50").assertExists()

    tester.emulateSavedInstanceStateRestore()
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("7 di 50").assertExists()
    // Si salva l'id, non la posizione: il verso e' lo stesso di prima.
    pager().performTouchInput { swipeRight() }
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("8 di 50").assertExists()
    pager().performTouchInput { swipeLeft() }
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("7 di 50").assertExists()
  }

  @Test
  fun chiudendoDopoAverScorsoVersoLePiuVecchieLaFotoTornaAllaSuaCella() {
    show()
    pager().performTouchInput { swipeRight() }
    composeRule.waitForIdle()
    pager().performTouchInput { swipeRight() }
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("7 di 50").assertExists()
    assertThat(fixture.anchor.revealed.last()).isEqualTo(fixture.idAt(6))
    fixture.placeCell(6)
    composeRule.onNodeWithContentDescription("Chiudi").performClick()
    composeRule.waitUntil(10_000L) { closed == 1 }
    assertThat(fixture.anchor.revealed.last()).isEqualTo(fixture.idAt(6))
  }

  @Test
  fun unaVistaDalPiuVecchioAlPiuRecenteNonSiRovescia() {
    // Un album "Dal piu' vecchio": la posizione 0 e' la piu' vecchia e a sinistra ci sta gia'. Verso destra si va alle posizioni basse, cioe' alle piu' recenti.
    val oldestFirst = IndexOrderedTimelineView(fixture.library.snapshot, (fixture.library.count - 1 downTo 0).toList().toIntArray())
    fixture.items.publish(oldestFirst)
    show(request = fixture.request(startIndex = 4))
    // L'indice 4 della Libreria e' alla posizione 45 della vista.
    composeRule.onNodeWithContentDescription("46 di 50").assertExists()
    pager().performTouchInput { swipeLeft() }
    composeRule.waitForIdle()
    // Verso sinistra, l'indice 3: piu' recente, ma e' la posizione dopo nella vista (e a destra c'e' la 5, piu' vecchia, come prima).
    composeRule.onNodeWithContentDescription("47 di 50").assertExists()
    assertThat(fixture.anchor.revealed.last()).isEqualTo(fixture.idAt(3))
    pager().performTouchInput { swipeRight() }
    composeRule.waitForIdle()
    pager().performTouchInput { swipeRight() }
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("45 di 50").assertExists()
    assertThat(fixture.anchor.revealed.last()).isEqualTo(fixture.idAt(5))
  }

  @Test
  fun unaVistaSenzaVersoDelTempoSegueLOrdineDellaLista() {
    // La pertinenza di una ricerca: non c'e' un "piu' vecchio". La posizione 0 sta a sinistra, la dopo a destra, come la griglia dei risultati si legge.
    val relevance = IndexOrderedTimelineView(fixture.library.snapshot, ((0 until 25).flatMap { listOf(it, 49 - it) }).toIntArray())
    fixture.items.publish(relevance)
    show(request = fixture.request(startIndex = 0))
    composeRule.onNodeWithContentDescription("1 di 50").assertExists()
    pager().performTouchInput { swipeLeft() }
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("2 di 50").assertExists()
    assertThat(fixture.anchor.revealed.last()).isEqualTo(fixture.idAt(49))
  }
}
