package dev.pampa.mycloudgallery.feature.viewer

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.adaptive.GalleryKey
import dev.pampa.mycloudgallery.feature.viewer.gesture.ViewerKeyCallbacks
import dev.pampa.mycloudgallery.feature.viewer.gesture.viewerKey
import dev.pampa.mycloudgallery.feature.viewer.gesture.wheelZoomFactor
import org.junit.Test

/**
 * Tastiera e mouse nel viewer (Fase 10, P4, D15, E-T10): le frecce sinistra e destra spostano di una foto, e nessun altro tasto fa qualcosa (Canc non elimina mai da solo; Invio e
 * Ctrl+A sono della griglia). La rotella ingrandisce: il fattore e' una funzione pura, provata qui; lo zoom vero lo prova la corsa sull'emulatore.
 */
@OptIn(ExperimentalTestApi::class)
class ViewerKeyboardTest : ViewerTestBase() {

  private fun press(key: Key) {
    composeRule.onRoot().performKeyInput { pressKey(key) }
    composeRule.waitForIdle()
  }

  @Test
  fun laFrecciaSinistraVaAllaFotoPiuVecchia_laDestraAQuellaPiuRecente() {
    // B5: nella Libreria la posizione 0 e' la piu' recente e le piu' vecchie stanno a sinistra: la freccia sinistra va a sinistra, cioe' alla posizione dopo.
    show()
    composeRule.onNodeWithContentDescription("5 di 50").assertExists()

    press(Key.DirectionLeft)
    composeRule.onNodeWithContentDescription("6 di 50").assertExists()
    press(Key.DirectionLeft)
    composeRule.onNodeWithContentDescription("7 di 50").assertExists()
    press(Key.DirectionRight)
    composeRule.onNodeWithContentDescription("6 di 50").assertExists()
  }

  @Test
  fun laRotellaDelMouseIngrandisceLaFoto() {
    show()
    composeRule.waitForIdle()
    assertThat(zoomText()).isNull()

    composeRule.onNodeWithContentDescription("5 di 50").performMouseInput {
      moveTo(center)
      scroll(-3f)
    }
    composeRule.waitForIdle()

    // La pagina dice "Ingrandita": da qui i gesti di un dito sono dello zoom, non del viewer.
    assertThat(zoomText()).isEqualTo("Ingrandita")
  }

  private fun zoomText(): String? =
    composeRule.onNodeWithContentDescription("5 di 50").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

  @Test
  fun iTastiDeiComandiDellaGrigliaNelViewerNonFannoNiente() {
    show()
    // Canc non elimina niente (il viewer ha il suo menu con il foglio di conferma), Invio e Ctrl+A non sono suoi.
    press(Key.Delete)
    press(Key.Enter)
    press(Key.A)
    composeRule.onNodeWithContentDescription("5 di 50").assertExists()
    // Nessun preferito, nessun nascosto, il viewer non si e' chiuso: i tasti della griglia nel viewer non fanno niente.
    assertThat(actions.favorites).isEmpty()
    assertThat(actions.hidden).isEmpty()
    assertThat(closed).isEqualTo(0)
  }

  @Test
  fun leFreccePerLaTabellaDeiTasti_soloPrimaEDopo() {
    var moved = 0
    val callbacks = ViewerKeyCallbacks(previous = { moved -= 1; true }, next = { moved += 1; true })

    assertThat(viewerKey(GalleryKey.NEXT, callbacks)).isTrue()
    assertThat(viewerKey(GalleryKey.NEXT, callbacks)).isTrue()
    assertThat(viewerKey(GalleryKey.PREVIOUS, callbacks)).isTrue()
    assertThat(moved).isEqualTo(1)
    // Gli altri non li consuma e non spostano niente.
    for (key in listOf(GalleryKey.OPEN, GalleryKey.SELECT_ALL, GalleryKey.DELETE)) {
      assertThat(viewerKey(key, callbacks)).isFalse()
    }
    assertThat(moved).isEqualTo(1)
  }

  @Test
  fun alleEstremitaLaFrecciaNonConsumaEILViewerRestaDovee() {
    val callbacks = ViewerKeyCallbacks(previous = { false }, next = { false })
    assertThat(viewerKey(GalleryKey.PREVIOUS, callbacks)).isFalse()
    assertThat(viewerKey(GalleryKey.NEXT, callbacks)).isFalse()
  }

  @Test
  fun laRotellaIngrandisceVersoLAltoERimpicciolisceVersoIlBasso() {
    assertThat(wheelZoomFactor(-1f)).isWithin(1e-4f).of(1.25f)
    assertThat(wheelZoomFactor(1f)).isWithin(1e-4f).of(0.8f)
    assertThat(wheelZoomFactor(-2f)).isWithin(1e-4f).of(1.5625f)
    // Ingrandire e rimpicciolire dello stesso passo si annullano.
    assertThat(wheelZoomFactor(-3f) * wheelZoomFactor(3f)).isWithin(1e-4f).of(1f)
  }

  @Test
  fun unaRotellaNullaONonFinitaNonFaNiente_eUnaImpennataSiLimita() {
    assertThat(wheelZoomFactor(0f)).isEqualTo(1f)
    assertThat(wheelZoomFactor(Float.NaN)).isEqualTo(1f)
    assertThat(wheelZoomFactor(Float.POSITIVE_INFINITY)).isEqualTo(1f)
    // Un touchpad che manda cento passi insieme vale al piu' quattro.
    assertThat(wheelZoomFactor(-100f)).isWithin(1e-3f).of(wheelZoomFactor(-4f))
    assertThat(wheelZoomFactor(100f)).isWithin(1e-3f).of(wheelZoomFactor(4f))
  }
}
