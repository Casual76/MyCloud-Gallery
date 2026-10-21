package dev.pampa.mycloudgallery.feature.viewer.livetext

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.unit.IntSize
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.feature.viewer.page.doubleTapZoomFactor
import dev.pampa.mycloudgallery.feature.viewer.page.maxZoomFactor
import org.junit.Test

/**
 * Il testo dal vivo a ogni livello di zoom (Fase 6, P12, D45: "il punto passa per la trasformazione di telephoto"; E-V13: "le maniglie
 * seguono lo zoom"). Una parola sola al centro: ingrandendo intorno al centro resta sullo schermo e le maniglie si misurano.
 *
 * Il fattore del doppio tocco e' quello della pagina ([doubleTapZoomFactor]); le immagini di prova sono 1600x900 (`ScriptedImages`).
 */
class ViewerLiveTextZoomTest : LiveTextViewerBase() {

  private val centro = ViewerTextLayout(listOf(LiveTextTestLines.line("Centro", 0.5f, 0.5f, 0.20f, 0.04f, 0, aspect)))

  private fun zoomedMatcher() = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Ingrandita")

  private fun stage(): IntSize = composeRule.onRoot().fetchSemanticsNode().size

  private fun doubleTapFactor(): Float {
    val content = Size(1600f, 900f)
    return doubleTapZoomFactor(content, stage(), maxZoomFactor(content, stage()))
  }

  /** Chiude la selezione con un tocco in un punto vuoto della pagina (ne' sulle barre ne' sulla parola). */
  private fun dismiss() {
    pager().performTouchInput { click(Offset(40f, visibleSize.height * 0.3f)) }
    composeRule.mainClock.advanceTimeBy(1_000)
    composeRule.waitForIdle()
    assertThat(hasNode(START)).isFalse()
  }

  private fun copyCentro() {
    composeRule.onNodeWithContentDescription("Copia").performClick()
    composeRule.waitForIdle()
    assertThat(clipText()).isEqualTo("Centro")
  }

  @Test
  fun unoX_leManiglieStannoAgliEstremiDellaParola() {
    showText(textServices(centro))
    longPress(pageCenter())
    // La riga e' lunga 0,20 altezze dell'immagine: 0,20 x l'altezza a schermo dell'immagine intera.
    assertThat(handleSeparation()).isWithin(2f).of(0.20f * mapping().fit.height)
    copyCentro()
  }

  @Test
  fun alDoppioTocco_laPressioneSelezionaLaParolaSottoIlDitoEIlManiglieSeguonoLoZoom() {
    showText(textServices(centro))
    longPress(pageCenter())
    val oneX = handleSeparation()
    dismiss()

    pager().performTouchInput { doubleClick(pageCenter()) }
    composeRule.mainClock.advanceTimeBy(1_000)
    composeRule.waitForIdle()
    composeRule.onNode(zoomedMatcher()).assertExists()
    longPress(pageCenter())
    assertThat(handleSeparation() / oneX).isWithin(0.04f * doubleTapFactor()).of(doubleTapFactor())
    copyCentro()
  }

  @Test
  fun conLaSelezioneApertaUnDoppioTocco_nonCambiaLoZoom() {
    showText(textServices(centro))
    longPress(pageCenter())
    val oneX = handleSeparation()
    pager().performTouchInput { doubleClick(pageCenter() + Offset(0f, 200f)) }
    composeRule.mainClock.advanceTimeBy(1_000)
    composeRule.waitForIdle()
    assertThat(handleSeparation()).isWithin(0.5f).of(oneX)
    assertThat(composeRule.onAllNodes(zoomedMatcher()).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun ilPizzicoConLaSelezioneApertaIngrandisceELeManiglieLoSeguono() {
    showText(textServices(centro))
    longPress(pageCenter())
    val oneX = handleSeparation()
    val center = pageCenter()
    pager().performTouchInput {
      pinch(start0 = center - Offset(40f, 0f), end0 = center - Offset(160f, 0f), start1 = center + Offset(40f, 0f), end1 = center + Offset(160f, 0f))
    }
    composeRule.mainClock.advanceTimeBy(1_000)
    composeRule.waitForIdle()
    composeRule.onNode(zoomedMatcher()).assertExists()
    assertThat(handleSeparation()).isGreaterThan(oneX * 1.5f)
    assertThat(hasNode(START)).isTrue()
    copyCentro()
  }

  @Test
  fun alMassimoDelloZoomLaPressioneFunzionaAncora() {
    showText(textServices(centro))
    longPress(pageCenter())
    val oneX = handleSeparation()
    dismiss()
    val center = pageCenter()
    repeat(3) {
      pager().performTouchInput {
        pinch(start0 = center - Offset(40f, 0f), end0 = center - Offset(300f, 0f), start1 = center + Offset(40f, 0f), end1 = center + Offset(300f, 0f))
      }
      composeRule.mainClock.advanceTimeBy(500)
      composeRule.waitForIdle()
    }
    composeRule.onNode(zoomedMatcher()).assertExists()
    longPress(center)
    val max = maxZoomFactor(Size(1600f, 900f), stage())
    assertThat(handleSeparation() / oneX).isWithin(0.1f * max).of(max)
    copyCentro()
  }
}
