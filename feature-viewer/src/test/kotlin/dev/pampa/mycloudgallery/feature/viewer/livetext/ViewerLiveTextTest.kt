package dev.pampa.mycloudgallery.feature.viewer.livetext

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Il testo dal vivo sul viewer vero (Fase 6, P12; D43, D45; E-V13): la pressione prolungata seleziona la parola, le maniglie e la barra
 * compaiono, la copia e' sensibile (`ClipData`), e con la selezione aperta il pager e il trascinamento verso il basso sono fermi, il
 * doppio tocco e' ignorato, il pizzico funziona e il tocco fuori chiude.
 */
class ViewerLiveTextTest : LiveTextViewerBase() {

  private val text = LiveTextTestLines.multiline

  private fun selectWord(line: Int, word: Int) {
    showText()
    longPress(at(text, line, word))
  }

  @Test
  fun unaPressioneProlungataSuUnaParolaLaSelezionaConDueManiglieELaBarra() {
    selectWord(0, 1)
    for (label in listOf(START, END, "Copia", "Seleziona tutto", "Condividi testo")) assertThat(hasNode(label)).isTrue()
    // Il rilascio non e' un tocco sulla foto: le barre del viewer restano.
    composeRule.onNodeWithContentDescription("Chiudi").assertExists()
  }

  @Test
  fun leManigliaHannoBersagliDa48dp() {
    selectWord(0, 1)
    for (label in listOf(START, END)) {
      composeRule.onNodeWithContentDescription(label).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }
    for (label in listOf("Copia", "Seleziona tutto", "Condividi testo")) {
      composeRule.onNodeWithContentDescription(label).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }
  }

  @Test
  fun leManiglieStannoAgliEstremiDellaParolaSuUnaRigaDritta() {
    val layout = LiveTextTestLines.centered
    showText(textServices(layout))
    longPress(at(layout, 0, 1))
    val geometry = LiveTextGeometry(layout, aspect)
    val start = mapping().imageToScreen(geometry.handle(TextPosition(0, 1), atEnd = false).tip)
    val end = mapping().imageToScreen(geometry.handle(TextPosition(0, 1), atEnd = true).tip)
    assertThat(handleCenter(HandleSide.Start).x).isWithin(2f).of(start.x)
    assertThat(handleCenter(HandleSide.End).x).isWithin(2f).of(end.x)
    // Il bersaglio sta sotto il bordo del testo.
    assertThat(handleCenter(HandleSide.Start).y).isGreaterThan(start.y)
  }

  @Test
  fun suUnaRigaInclinataLeManiglieSeguonoLInclinazione() {
    val layout = LiveTextTestLines.tilted
    showText(textServices(layout))
    longPress(at(layout, 0, 1))
    val start = handleCenter(HandleSide.Start)
    val end = handleCenter(HandleSide.End)
    // "euro" sta al centro di una riga che scende verso destra: la fine della parola e' piu' bassa del suo inizio.
    assertThat(end.y).isGreaterThan(start.y)
    composeRule.onNodeWithContentDescription("Copia").performClick()
    assertThat(clipText()).isEqualTo("euro")
  }

  // --- Copia e condividi ---------------------------------------------------------------------------------------------------------

  @Test
  fun copiaMetteLaParolaNegliAppuntiSegnataSensibileELaSelezioneSiChiude() {
    selectWord(0, 1)
    composeRule.onNodeWithContentDescription("Copia").performClick()
    composeRule.waitForIdle()
    assertThat(clipText()).isEqualTo("di")
    assertThat(clipSensitive()).isTrue()
    assertThat(hasNode(START)).isFalse()
    // L'annuncio per TalkBack.
    composeRule.onNodeWithText("Testo copiato").assertExists()
  }

  @Test
  fun selezionaTuttoECopiaTuttoIlTestoSuPiuRighe() {
    selectWord(1, 2)
    composeRule.onNodeWithContentDescription("Seleziona tutto").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("Copia").performClick()
    assertThat(clipText()).isEqualTo("Prova di lettura\nsu piu' righe di testo\nfine")
    assertThat(clipSensitive()).isTrue()
  }

  @Test
  fun trascinandoLaManigliaDiFineLaSelezioneSiAgganciaAlleParoleSuUnAltraRiga() {
    selectWord(0, 0)
    val end = handleCenter(HandleSide.End)
    val anchor = mapping().imageToScreen(LiveTextGeometry(text, aspect).handle(TextPosition(0, 0), atEnd = true).anchor)
    val target = at(text, 2, 0)
    pager().performTouchInput {
      // Il primo passo supera la soglia di movimento (la parte entro la soglia non conta come trascinamento): il resto e' esatto.
      val slop = viewConfiguration.touchSlop
      down(end)
      moveBy(Offset(0f, slop * 2f), delayMillis = 50)
      moveBy(target - anchor - Offset(0f, slop), delayMillis = 100)
      up()
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("Copia").performClick()
    assertThat(clipText()).isEqualTo("Prova di lettura\nsu piu' righe di testo\nfine")
  }

  @Test
  fun condividiTestoApreIlSelettoreDiTestoSemplice() {
    selectWord(0, 2)
    composeRule.onNodeWithContentDescription("Condividi testo").performClick()
    composeRule.waitForIdle()
    val started = org.robolectric.Shadows.shadowOf(composeRule.activity.application).nextStartedActivity
    assertThat(started.action).isEqualTo(android.content.Intent.ACTION_CHOOSER)
    val send = started.getParcelableExtra<android.content.Intent>(android.content.Intent.EXTRA_INTENT)!!
    assertThat(send.action).isEqualTo(android.content.Intent.ACTION_SEND)
    assertThat(send.getStringExtra(android.content.Intent.EXTRA_TEXT)).isEqualTo("lettura")
  }

  // --- E-V13: la selezione aperta e i gesti del viewer --------------------------------------------------------------------------

  @Test
  fun e_V13_ilPagerNonScorreConLaSelezioneAperta() {
    selectWord(0, 1)
    pager().performTouchInput { swipeLeft() }
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription(pageLabel).assertExists()
    assertThat(hasNode(START)).isTrue()
  }

  @Test
  fun e_V13_ilTrascinamentoVersoIlBassoNonChiudeIlViewer() {
    selectWord(0, 1)
    val before = handleCenter(HandleSide.End)
    pager().performTouchInput {
      down(Offset(40f, visibleSize.height * 0.3f))
      moveBy(Offset(0f, 500f), delayMillis = 80)
      up()
    }
    composeRule.mainClock.advanceTimeBy(1_000)
    composeRule.waitForIdle()
    assertThat(closed).isEqualTo(0)
    assertThat(handleCenter(HandleSide.End)).isEqualTo(before)
  }

  @Test
  fun e_V13_ilDoppioTocco_siIgnora_eIlPizzicoFunziona() {
    // Una sola parola al centro: a ogni livello di zoom resta sullo schermo e le maniglie si misurano.
    val centro = ViewerTextLayout(listOf(LiveTextTestLines.line("Centro", 0.5f, 0.5f, 0.20f, 0.04f, 0, aspect)))
    showText(textServices(centro))
    longPress(at(centro, 0, 0))
    val before = handleSeparation()
    pager().performTouchInput { doubleClick(atImage(0.9f, 0.9f)) }
    composeRule.mainClock.advanceTimeBy(1_000)
    composeRule.waitForIdle()
    // Il doppio tocco e' ignorato: stesse maniglie, selezione ancora aperta.
    assertThat(handleSeparation()).isWithin(0.5f).of(before)

    val center = pageCenter()
    pager().performTouchInput { pinch(start0 = center - Offset(60f, 0f), end0 = center - Offset(240f, 0f), start1 = center + Offset(60f, 0f), end1 = center + Offset(240f, 0f)) }
    composeRule.waitForIdle()
    // Il pizzico ingrandisce e le maniglie lo seguono.
    assertThat(handleSeparation()).isGreaterThan(before * 1.3f)
    assertThat(hasNode(START)).isTrue()
  }

  @Test
  fun e_V13_ilTocco_fuori_chiudeLaSelezioneSenzaNascondereLeBarre() {
    selectWord(0, 1)
    pager().performTouchInput { click(Offset(40f, visibleSize.height * 0.3f)) }
    composeRule.mainClock.advanceTimeBy(1_000)
    composeRule.waitForIdle()
    assertThat(hasNode(START)).isFalse()
    assertThat(hasNode("Copia")).isFalse()
    composeRule.onNodeWithContentDescription("Chiudi").assertExists()
  }

  @Test
  fun e_V13_chiusaLaSelezioneIlPagerScorreDinuovo() {
    selectWord(0, 1)
    pager().performTouchInput { click(Offset(40f, visibleSize.height * 0.3f)) }
    composeRule.mainClock.advanceTimeBy(1_000)
    composeRule.waitForIdle()
    assertThat(hasNode(START)).isFalse()
    pager().performTouchInput { swipeLeft() }
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("${startIndex + 2} di ${fixture.library.count}").assertExists()
  }

  @Test
  fun indietroChiudePrimaLaSelezioneEPoiIlViewer() {
    selectWord(0, 1)
    composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
    composeRule.waitForIdle()
    assertThat(hasNode(START)).isFalse()
    composeRule.mainClock.advanceTimeBy(1_000)
    assertThat(closed).isEqualTo(0)
    composeRule.onNodeWithContentDescription("Chiudi").assertExists()
  }

  @Test
  fun unaNuovaPressioneSuUnAltraParolaCambiaLaSelezione() {
    selectWord(0, 0)
    longPress(at(text, 1, 4))
    composeRule.onNodeWithContentDescription("Copia").performClick()
    assertThat(clipText()).isEqualTo("testo")
  }
}
