package dev.pampa.mycloudgallery.feature.viewer.livetext

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.feature.viewer.testing.FakeViewerTextReader
import org.junit.Test

/**
 * La lettura del testo e i punti d'ingresso del testo dal vivo sul viewer vero (Fase 6, P12; D41, D42, D46; E-V9, E-H1): con la lettura
 * spenta nessuna chiamata all'OCR (il fake fallisce se chiamato), il testo letto da un altro dispositivo vale anche li', la lettura al
 * volo solo con la lettura attiva; il pulsante "Testo", la voce ⋮ e l'azione di TalkBack aprono il foglio "Testo nella foto".
 */
class ViewerLiveTextReadTest : LiveTextViewerBase() {

  private val text = LiveTextTestLines.multiline
  private val centro = ViewerTextLayout(listOf(LiveTextTestLines.line("Centro", 0.5f, 0.5f, 0.20f, 0.04f, 0, aspect)))

  private val sheetText = "Prova di lettura\nsu piu' righe di testo\nfine"

  private fun talkBackAction(): SemanticsMatcher =
    SemanticsMatcher("ha l'azione \"Testo nella foto\"") { node ->
      node.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == "Testo nella foto" } == true
    }

  private fun hasTalkBackAction(): Boolean = composeRule.onAllNodes(talkBackAction()).fetchSemanticsNodes().isNotEmpty()

  private fun runTalkBackAction() {
    val action = composeRule.onAllNodes(talkBackAction()).fetchSemanticsNodes().first()
      .config[SemanticsActions.CustomActions].first { it.label == "Testo nella foto" }
    composeRule.runOnIdle { action.action() }
    composeRule.waitForIdle()
  }

  // --- La lettura (D41, D42, E-V9) -----------------------------------------------------------------------------------------------

  @Test
  fun lettura_spenta_nessunaChiamataAllOcr_eLaPressioneVaAlRitaglio() {
    val reader = FakeViewerTextReader(available = false, result = text)
    val pressed = mutableListOf<Offset>()
    showText(textServices(layout = null, reader = reader, subject = { _, x, y -> pressed += Offset(x, y) }))
    longPress(pageCenter())
    assertThat(reader.reads).isEqualTo(0)
    assertThat(hasNode(START)).isFalse()
    // E-V9: senza testo letto e con la lettura spenta, la pressione prolungata va al ritaglio, col punto normalizzato.
    assertThat(pressed).hasSize(1)
    assertThat(pressed.single().x).isWithin(0.01f).of(0.5f)
    assertThat(pressed.single().y).isWithin(0.01f).of(0.5f)
  }

  @Test
  fun lettura_spenta_ilTestoLettoDaUnAltroDispositivoVale() {
    val reader = FakeViewerTextReader(available = false)
    showText(textServices(text, reader = reader))
    longPress(at(text, 1, 2))
    assertThat(hasNode(START)).isTrue()
    composeRule.onNodeWithContentDescription("Copia").performClick()
    assertThat(clipText()).isEqualTo("righe")
    assertThat(reader.reads).isEqualTo(0)
  }

  @Test
  fun lettura_attiva_alVoloSoloSuUnMediaNonAncoraLetto() {
    val reader = FakeViewerTextReader(available = true, result = centro)
    showText(textServices(layout = null, reader = reader))
    longPress(pageCenter())
    assertThat(reader.reads).isEqualTo(1)
    assertThat(hasNode(START)).isTrue()
    composeRule.onNodeWithContentDescription("Copia").performClick()
    assertThat(clipText()).isEqualTo("Centro")
  }

  @Test
  fun lettura_attiva_unMediaGiaLettoNonSiRilegge() {
    val reader = FakeViewerTextReader(available = true, result = centro)
    showText(textServices(text, reader = reader))
    longPress(at(text, 0, 0))
    assertThat(hasNode(START)).isTrue()
    assertThat(reader.reads).isEqualTo(0)
  }

  @Test
  fun lettura_attiva_senzaTestoNelRisultatoLaPressioneVaAlRitaglio() {
    val reader = FakeViewerTextReader(available = true, result = ViewerTextLayout(emptyList()))
    val pressed = mutableListOf<Offset>()
    showText(textServices(layout = null, reader = reader, subject = { _, x, y -> pressed += Offset(x, y) }))
    longPress(pageCenter())
    assertThat(reader.reads).isEqualTo(1)
    assertThat(hasNode(START)).isFalse()
    assertThat(pressed).hasSize(1)
  }

  @Test
  fun senzaNessunServizioDelTestoLaPressioneNonFaNiente() {
    showText(textServices(withSource = false))
    longPress(pageCenter())
    assertThat(hasNode(START)).isFalse()
    assertThat(hasNode("Copia")).isFalse()
  }

  // --- Il pulsante "Testo", la voce ⋮, TalkBack (D43, D46) ------------------------------------------------------------------------

  @Test
  fun ilPulsanteTestoCeSoloSeLaFotoHaTestoLetto() {
    showText(textServices(layout = null))
    assertThat(hasNode("Testo nella foto")).isFalse()
    assertThat(hasTalkBackAction()).isFalse()
  }

  @Test
  fun ilPulsanteTestoEvidenziaPoiApreIlFoglio() {
    showText()
    composeRule.onNodeWithContentDescription("Testo nella foto").performClick()
    composeRule.waitForIdle()
    assertThat(composeRule.onAllNodesWithText("Copia tutto").fetchSemanticsNodes()).isEmpty()
    composeRule.onNodeWithContentDescription("Testo nella foto").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Copia tutto").assertExists()
    composeRule.onNodeWithText(sheetText).assertExists()
  }

  @Test
  fun laVoceDelMenuApreIlFoglio() {
    showText()
    composeRule.onNodeWithContentDescription("Altre azioni").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Testo nella foto").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText(sheetText).assertExists()
  }

  @Test
  fun laVoceDelMenuNonCeSenzaTesto() {
    showText(textServices(layout = null))
    composeRule.onNodeWithContentDescription("Altre azioni").performClick()
    composeRule.waitForIdle()
    assertThat(composeRule.onAllNodesWithText("Testo nella foto").fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun talkBack_l_azioneApreIlFoglioLeggibileEdIlBottoneHa48dp() {
    showText()
    assertThat(hasTalkBackAction()).isTrue()
    runTalkBackAction()
    // Il testo letto come testo normale, riga per riga: TalkBack lo legge e la selezione di sistema funziona.
    composeRule.onNodeWithText(sheetText).assertExists()
    composeRule.onNodeWithText("Copia tutto").assertHeightIsAtLeast(48.dp)
    composeRule.onNodeWithText("Chiudi").assertHeightIsAtLeast(48.dp)
  }

  @Test
  fun copiaTuttoDelFoglioMetteTuttoNegliAppuntiSensibili() {
    showText()
    runTalkBackAction()
    composeRule.onNodeWithText("Copia tutto").performClick()
    composeRule.waitForIdle()
    assertThat(clipText()).isEqualTo(sheetText)
    assertThat(clipSensitive()).isTrue()
    assertThat(composeRule.onAllNodesWithText("Copia tutto").fetchSemanticsNodes()).isEmpty()
    composeRule.onNodeWithText("Testo copiato").assertExists()
  }

  @Test
  fun talkBack_conLaLetturaAttivaSuUnMediaNonLettoLAzioneLeggeEApreIlFoglio() {
    val reader = FakeViewerTextReader(available = true, result = text)
    showText(textServices(layout = null, reader = reader))
    assertThat(hasNode("Testo nella foto")).isFalse()
    assertThat(hasTalkBackAction()).isTrue()
    runTalkBackAction()
    assertThat(reader.reads).isEqualTo(1)
    composeRule.onNodeWithText(sheetText).assertExists()
  }

  @Test
  fun talkBack_conLaLetturaSpentaSenzaTestoLAzioneNonCeEDiceNienteDaLeggere() {
    val reader = FakeViewerTextReader(available = false, result = text)
    showText(textServices(layout = null, reader = reader))
    assertThat(hasTalkBackAction()).isFalse()
    assertThat(reader.reads).isEqualTo(0)
  }

  // --- E-H1: mai il testo di un altro media -------------------------------------------------------------------------------------------

  @Test
  fun e_H1_ilTestoNonCompareSuUnAltroMedia_eTornandoLaSelezioneNonCe() {
    showText()
    longPress(at(text, 0, 1))
    composeRule.onNodeWithContentDescription("Copia").performClick()
    composeRule.waitForIdle()
    pager().performTouchInput { swipeLeft() }
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("${startIndex + 2} di ${fixture.library.count}").assertExists()
    assertThat(hasNode("Testo nella foto")).isFalse()
    assertThat(hasTalkBackAction()).isFalse()
    pager().performTouchInput { swipeRight() }
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription(pageLabel).assertExists()
    assertThat(hasNode("Testo nella foto")).isTrue()
    assertThat(hasNode(START)).isFalse()
  }

  @Test
  fun e_H1_daiNascostiLaCopiaEAncoraSensibile() {
    showText(secure = true)
    longPress(at(text, 2, 0))
    composeRule.onNodeWithContentDescription("Copia").performClick()
    assertThat(clipText()).isEqualTo("fine")
    assertThat(clipSensitive()).isTrue()
  }
}
