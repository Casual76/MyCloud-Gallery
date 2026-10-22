package dev.pampa.mycloudgallery.feature.viewer.cutout

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.feature.viewer.livetext.LiveTextTestLines
import dev.pampa.mycloudgallery.feature.viewer.testing.FakeCutoutSaver
import org.junit.Test

/**
 * Il ritaglio del soggetto sul viewer vero (Fase 6, P13; D44, D45, D47; E-V10, E-V11, E-V13): la pressione prolungata sul soggetto lo stacca e
 * porta la barra "Copia · Condividi · Salva come nuova foto" con l'interruttore "Contorno"; fuori dal soggetto non succede niente; con il
 * soggetto staccato il pager e il trascinamento verso il basso sono fermi, il doppio tocco e' ignorato, il tocco fuori e Indietro lo chiudono;
 * dai Nascosti la foto salvata nasce nascosta.
 */
class ViewerCutoutTest : CutoutViewerBase() {

  @Test
  fun unaPressioneProlungataSulSoggettoLoStaccaEPortaLaBarra() {
    val cutter = CutoutTestImage.cutter()
    showCutout(cutoutServices(cutter))
    pressRobot()
    for (label in listOf(COPY, SHARE, SAVE, OUTLINE)) assertThat(hasNode(label)).isTrue()
    // Il ritagliatore e' stato chiamato una volta, sul punto toccato (normalizzato sull'immagine).
    assertThat(cutter.calls).hasSize(1)
    assertThat(cutter.calls.single().first).isWithin(0.02f).of(CutoutTestImage.BODY_X)
    assertThat(cutter.calls.single().second).isWithin(0.02f).of(CutoutTestImage.BODY_Y)
    assertThat(cutter.ids).containsExactly(shown)
    // I controlli sono quelli del soggetto: il chrome del viewer si toglie (niente barre una sopra l'altra).
    assertThat(hasNode("Chiudi")).isFalse()
  }

  @Test
  fun leAzioniEL_interruttoreHannoBersagliDa48dp() {
    showCutout()
    pressRobot()
    for (label in listOf(COPY, SHARE, SAVE, OUTLINE)) {
      composeRule.onNodeWithContentDescription(label).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }
  }

  @Test
  fun fuoriDalSoggettoNonSuccedeNienteESenzaMessaggioDErrore_E_V10() {
    val cutter = CutoutTestImage.cutter()
    showCutout(cutoutServices(cutter))
    pressGrass()
    assertThat(cutter.calls).hasSize(1)
    for (label in listOf(SAVE, OUTLINE)) assertThat(hasNode(label)).isFalse()
    // Nessun avviso (con il dito basta una vibrazione leggera): la pagina e le barre restano com'erano.
    assertThat(hasText("Nessun soggetto chiaro")).isFalse()
    assertThat(hasText("Ritaglio non riuscito")).isFalse()
    composeRule.onNodeWithContentDescription("Chiudi").assertExists()
  }

  @Test
  fun unGuastoDelRitagliatoreDiceCheIlRitaglioNonERiuscito() {
    val cutter = CutoutTestImage.cutter().apply { failWith = IllegalStateException("motore rotto") }
    showCutout(cutoutServices(cutter))
    pressRobot()
    assertThat(hasText("Ritaglio non riuscito")).isTrue()
    assertThat(hasNode(COPY)).isFalse()
  }

  // --- Le azioni ---------------------------------------------------------------------------------------------------------------------

  @Test
  fun copiaManda_IlSoggettoAlSalvataggioEChiude() {
    val saver = FakeCutoutSaver()
    showCutout(cutoutServices(saver = saver))
    pressRobot()
    tap(COPY)
    assertThat(saver.copies).containsExactly(shown)
    assertThat(saver.outlines).containsExactly(false)
    assertThat(hasText("Soggetto ritagliato e copiato")).isTrue()
    assertThat(hasNode(COPY)).isFalse()
    // Chiuso il ritaglio tornano le barre del viewer.
    composeRule.onNodeWithContentDescription("Chiudi").assertExists()
  }

  @Test
  fun copiaCheNonRiesceLoDiceEIlSoggettoResta() {
    val saver = FakeCutoutSaver().apply { copyFails = true }
    showCutout(cutoutServices(saver = saver))
    pressRobot()
    tap(COPY)
    assertThat(saver.copies).isEmpty()
    assertThat(hasText("Non è andata a buon fine")).isTrue()
    assertThat(hasNode(COPY)).isTrue()
  }

  @Test
  fun condividiLasciaIlSoggettoStaccatoEPoiSiPuoSalvare() {
    val saver = FakeCutoutSaver()
    showCutout(cutoutServices(saver = saver))
    pressRobot()
    tap(SHARE)
    assertThat(saver.shares).containsExactly(shown)
    assertThat(hasNode(SAVE)).isTrue()
  }

  @Test
  fun salvaComeNuovaFotoESalvaConIlContornoCheSiVede() {
    val saver = FakeCutoutSaver()
    showCutout(cutoutServices(saver = saver))
    pressRobot()
    composeRule.onNodeWithContentDescription(OUTLINE).assertIsOff()
    tap(OUTLINE)
    composeRule.onNodeWithContentDescription(OUTLINE).assertIsOn()
    tap(SAVE)
    assertThat(saver.saved).containsExactly(shown to false)
    // Quello che si vede e' quello che si salva: il Contorno acceso.
    assertThat(saver.outlines).containsExactly(true)
    assertThat(hasText("Salvata come nuova foto")).isTrue()
  }

  @Test
  fun daiNascostiLaFotoNuovaNasceNascosta_E_V11() {
    val saver = FakeCutoutSaver()
    showCutout(cutoutServices(saver = saver), secure = true)
    pressRobot()
    tap(SAVE)
    // I6: dai Nascosti il salvataggio chiede prima conferma (il ritaglio sara' una foto pubblica del telefono, con il nome generico) e parte dal tocco su "Salva".
    assertThat(hasText("Salvare il ritaglio nel telefono?")).isTrue()
    assertThat(saver.saved).isEmpty()
    composeRule.onNodeWithText("Salva").performClick()
    composeRule.waitForIdle()
    assertThat(saver.saved).containsExactly(shown to true)
  }

  @Test
  fun daiNascostiAnnullareLaConfermaNonSalvaNiente_I6() {
    val saver = FakeCutoutSaver()
    showCutout(cutoutServices(saver = saver), secure = true)
    pressRobot()
    tap(SAVE)
    composeRule.onNodeWithText("Annulla").performClick()
    composeRule.waitForIdle()
    assertThat(saver.saved).isEmpty()
    assertThat(hasText("Salvare il ritaglio nel telefono?")).isFalse()
    // Il soggetto resta staccato: si puo' ancora salvare.
    assertThat(hasNode(SAVE)).isTrue()
  }

  @Test
  fun unSalvataggioCheNonRiesceRestaAperto_CUTOUT_SAVE_FAILED() {
    val saver = FakeCutoutSaver().apply { saveFails = true }
    showCutout(cutoutServices(saver = saver))
    pressRobot()
    tap(SAVE)
    assertThat(saver.saved).isEmpty()
    assertThat(hasText("Ritaglio non salvato")).isTrue()
    // Il soggetto resta staccato: si puo' riprovare.
    assertThat(hasNode(SAVE)).isTrue()
    saver.saveFails = false
    tap(SAVE)
    assertThat(saver.saved).containsExactly(shown to false)
  }

  @Test
  fun senzaSalvataggioIlSoggettoSiStaccaMaLaBarraNonHaLeAzioniChePosso() {
    showCutout(cutoutServices(saver = null))
    pressRobot()
    // Il saver manca (D13): le azioni non fanno niente ma il viewer non si rompe.
    tap(COPY)
    assertThat(hasNode(COPY)).isTrue()
  }

  // --- I gesti (E-V13) -------------------------------------------------------------------------------------------------------------------

  @Test
  fun conIlSoggettoStaccatoIlPagerEIlTrascinamentoVersoIlBassoSonoFermi() {
    showCutout()
    pressRobot()
    pager().performTouchInput { swipeLeft() }
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription(pageLabel).assertExists()
    pager().performTouchInput { swipeDown() }
    composeRule.mainClock.advanceTimeBy(1_000)
    composeRule.waitForIdle()
    assertThat(closed).isEqualTo(0)
    assertThat(hasNode(COPY)).isTrue()
  }

  @Test
  fun ilDoppioTocco_Ignorato() {
    showCutout()
    pressRobot()
    pager().performTouchInput { doubleClick(center) }
    composeRule.mainClock.advanceTimeBy(1_000)
    composeRule.waitForIdle()
    assertThat(hasNode(COPY)).isTrue()
  }

  @Test
  fun ilToccoFuoriChiudeIlRitaglioEIlPagerScorreDiNuovo() {
    showCutout()
    pressRobot()
    pager().performTouchInput { click(androidx.compose.ui.geometry.Offset(40f, visibleSize.height * 0.2f)) }
    composeRule.mainClock.advanceTimeBy(1_000)
    composeRule.waitForIdle()
    assertThat(hasNode(COPY)).isFalse()
    composeRule.onNodeWithContentDescription("Chiudi").assertExists()
    pager().performTouchInput { swipeLeft() }
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("${startIndex + 2} di ${fixture.library.count}").assertExists()
  }

  @Test
  fun indietroChiudePrimaIlRitaglioEPoiIlViewer() {
    showCutout()
    pressRobot()
    composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
    composeRule.waitForIdle()
    assertThat(hasNode(COPY)).isFalse()
    composeRule.mainClock.advanceTimeBy(1_000)
    assertThat(closed).isEqualTo(0)
    composeRule.onNodeWithContentDescription("Chiudi").assertExists()
  }

  @Test
  fun conIlSoggettoStaccatoUnaNuovaPressioneProlungataNonFaNiente() {
    val cutter = CutoutTestImage.cutter()
    showCutout(cutoutServices(cutter))
    pressRobot()
    longPress(atImage(CutoutTestImage.BODY_X + 0.05f, CutoutTestImage.BODY_Y))
    // Ferma: i tasti della barra, premuti a lungo, non devono rifare il ritaglio.
    assertThat(cutter.calls).hasSize(1)
    assertThat(hasNode(COPY)).isTrue()
  }

  @Test
  fun unToccoNegliSpaziDellaBarraNonChiudeIlRitaglio() {
    showCutout()
    pressRobot()
    // Il tocco sul tasto "Contorno" resta nella barra e non chiude.
    tap(OUTLINE)
    assertThat(hasNode(SAVE)).isTrue()
  }

  // --- Cosa c'e' e cosa no (D13) -----------------------------------------------------------------------------------------------------

  @Test
  fun senzaRitagliatoreLaPressioneNonFaNiente_D13() {
    showCutout(cutoutServices(cutter = null))
    pressRobot()
    // "Condividi" e "Copia" esistono anche nel viewer (barra e menu): le etichette solo del ritaglio sono le altre due.
    for (label in listOf(SAVE, OUTLINE)) assertThat(hasNode(label)).isFalse()
    composeRule.onNodeWithContentDescription("Chiudi").assertExists()
  }

  @Test
  fun laVoceDelMenuRitagliaIlSoggettoPiuGrandeFraCinquePunti() {
    val cutter = CutoutTestImage.cutter()
    showCutout(cutoutServices(cutter))
    composeRule.onNodeWithContentDescription("Altre azioni").performClick()
    composeRule.waitUntil(TIMEOUT) { composeRule.onAllNodesWithText(CUTOUT_ACTION).fetchSemanticsNodes().isNotEmpty() }
    composeRule.onNodeWithText(CUTOUT_ACTION).performClick()
    composeRule.waitForIdle()
    assertThat(hasNode(COPY)).isTrue()
    // Il centro e' sul robot; i punti a un terzo che cadono sul robot gia' trovato non si provano.
    assertThat(cutter.calls.first().first).isWithin(1e-3f).of(0.5f)
    assertThat(cutter.calls.size).isIn(1..5)
  }

  @Test
  fun laVoceDelMenuNonCeSenzaRitagliatore() {
    showCutout(cutoutServices(cutter = null))
    composeRule.onNodeWithContentDescription("Altre azioni").performClick()
    composeRule.waitUntil(TIMEOUT) { composeRule.onAllNodesWithText("Informazioni").fetchSemanticsNodes().isNotEmpty() }
    assertThat(composeRule.onAllNodesWithText(CUTOUT_ACTION).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun laVoceDelMenuSenzaSoggettoLoDice_E_V10() {
    val cutter = CutoutTestImage.cutter().apply { result = { _, _ -> ViewerCutout.NoSubject } }
    showCutout(cutoutServices(cutter))
    composeRule.onNodeWithContentDescription("Altre azioni").performClick()
    composeRule.waitUntil(TIMEOUT) { composeRule.onAllNodesWithText(CUTOUT_ACTION).fetchSemanticsNodes().isNotEmpty() }
    composeRule.onNodeWithText(CUTOUT_ACTION).performClick()
    composeRule.waitForIdle()
    // Chiesto dal menu un "niente" si dice (non c'e' una vibrazione che lo faccia capire).
    assertThat(hasText("Nessun soggetto chiaro")).isTrue()
    assertThat(cutter.calls).hasSize(5)
  }

  @Test
  fun unVideoNonHaIlRitaglio() {
    val video = fixture.indicesOf(dev.pampa.mycloudgallery.core.model.MediaKind.VIDEO).first()
    val cutter = CutoutTestImage.cutter()
    val request = fixture.request(startIndex = video)
    val services = cutoutServices(cutter)
    composeRule.setContent {
      dev.pampa.mycloudgallery.feature.viewer.ViewerTestHost(dark = true) {
        dev.pampa.mycloudgallery.feature.viewer.ViewerOverlay(request = request, onClosed = { closed++ }, services = services)
      }
    }
    composeRule.waitForIdle()
    // Il menu di un video non ha la voce...
    composeRule.onNodeWithContentDescription("Altre azioni").performClick()
    composeRule.waitUntil(TIMEOUT) { composeRule.onAllNodesWithText("Informazioni").fetchSemanticsNodes().isNotEmpty() }
    assertThat(composeRule.onAllNodesWithText(CUTOUT_ACTION).fetchSemanticsNodes()).isEmpty()
    // ... e la pressione prolungata non chiama il ritagliatore.
    composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
    composeRule.waitForIdle()
    pressRobot()
    assertThat(cutter.calls).isEmpty()
  }

  // --- Accessibilita' (D46) ------------------------------------------------------------------------------------------------------------

  @Test
  fun TalkBackHaLAzioneRitagliaIlSoggetto() {
    showCutout()
    val matcher = SemanticsMatcher("azione Ritaglia il soggetto") { node ->
      node.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == CUTOUT_ACTION } == true
    }
    val action = composeRule.onAllNodes(matcher).fetchSemanticsNodes().first().config[SemanticsActions.CustomActions].first { it.label == CUTOUT_ACTION }
    composeRule.runOnIdle { action.action() }
    composeRule.waitForIdle()
    assertThat(hasNode(COPY)).isTrue()
  }

  // --- Con il testo dal vivo ---------------------------------------------------------------------------------------------------------

  @Test
  fun suUnaParolaDiTestoVinceIlTestoDalVivo_AltroveIlRitaglio() {
    val layout = LiveTextTestLines.multiline
    val cutter = CutoutTestImage.cutter()
    showCutout(cutoutServices(cutter, text = layout))
    val quad = dev.pampa.mycloudgallery.feature.viewer.livetext.LiveTextGeometry(layout, aspect).quad(0, 1, 1)
    longPress(mapping().imageToScreen(androidx.compose.ui.geometry.Offset(quad.map { it.x }.average().toFloat(), quad.map { it.y }.average().toFloat())))
    assertThat(hasNode("Seleziona tutto")).isTrue()
    assertThat(cutter.calls).isEmpty()
    assertThat(hasNode(COPY) && hasNode(SAVE)).isFalse()
    // Un tocco fuori chiude la selezione; la pressione sul robot, lontano dal testo, e' il ritaglio.
    pager().performTouchInput { click(androidx.compose.ui.geometry.Offset(40f, visibleSize.height * 0.9f)) }
    composeRule.waitForIdle()
    pressRobot()
    assertThat(cutter.calls).hasSize(1)
    assertThat(hasNode(SAVE)).isTrue()
  }

  private companion object {
    const val TIMEOUT = 10_000L
  }
}
