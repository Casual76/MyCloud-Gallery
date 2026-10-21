package dev.pampa.mycloudgallery.feature.viewer.livetext

import androidx.compose.ui.geometry.Offset
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.feature.viewer.testing.LiveTextFixtures
import org.junit.Test

/**
 * La selezione del testo dal vivo come funzioni pure (Fase 6, P12, D43): ordine di lettura, estremi inclusi, testo copiato, "Seleziona
 * tutto", e le maniglie che passano da una riga all'altra.
 */
class TextSelectionsTest {

  private val aspect = LiveTextTestLines.ASPECT
  private val layout = LiveTextTestLines.multiline

  @Test
  fun unaParolaSolaEUnaSelezioneConGliEstremiUguali() {
    val selection = TextSelections.word(TextPosition(0, 1))
    assertThat(selection.from).isEqualTo(selection.to)
    assertThat(TextSelections.text(layout, selection)).isEqualTo("di")
  }

  @Test
  fun leDuePosizioniSiMettonoNellOrdineDiLettura() {
    val a = TextPosition(1, 3)
    val b = TextPosition(0, 2)
    assertThat(TextSelections.between(a, b)).isEqualTo(TextSelection(b, a))
    assertThat(TextSelections.between(b, a)).isEqualTo(TextSelection(b, a))
    assertThat(TextSelections.compare(TextPosition(1, 0), TextPosition(1, 1))).isLessThan(0)
    assertThat(TextSelections.compare(TextPosition(2, 0), TextPosition(1, 9))).isGreaterThan(0)
  }

  @Test
  fun unaSelezioneSuUnaRigaDaLeParoleIntraEdEstremiInclusi() {
    val selection = TextSelection(TextPosition(1, 1), TextPosition(1, 3))
    assertThat(TextSelections.text(layout, selection)).isEqualTo("piu' righe di")
  }

  @Test
  fun unaSelezioneSuPiuRigheTagliaLeEstremitaEPrendeIlMezzoInteroSeparatoDaUnACapo() {
    val selection = TextSelection(TextPosition(0, 2), TextPosition(2, 0))
    assertThat(TextSelections.text(layout, selection)).isEqualTo("lettura\nsu piu' righe di testo\nfine")
  }

  @Test
  fun selezionaTuttoDallaPrimaParolaAllUltima() {
    val geometry = LiveTextGeometry(layout, aspect)
    val all = TextSelections.all(geometry)!!
    assertThat(all).isEqualTo(TextSelection(TextPosition(0, 0), TextPosition(2, 0)))
    assertThat(TextSelections.isAll(geometry, all)).isTrue()
    assertThat(TextSelections.text(layout, all)).isEqualTo(TextSelections.allText(layout))
    assertThat(TextSelections.allText(layout)).isEqualTo("Prova di lettura\nsu piu' righe di testo\nfine")
    assertThat(TextSelections.all(LiveTextGeometry(ViewerTextLayout(emptyList()), aspect))).isNull()
  }

  @Test
  fun leRighePerLeFixtureDiP0Tornano() {
    assertThat(TextSelections.allText(LiveTextFixtures.straight)).isEqualTo("Via delle Rose 12")
    val geometry = LiveTextGeometry(LiveTextFixtures.multiline, aspect)
    assertThat(TextSelections.text(LiveTextFixtures.multiline, TextSelections.all(geometry)!!))
      .isEqualTo("Prova di lettura\nsu piu' righe di testo\nfine")
  }

  @Test
  fun lePosizioniDentroLaSelezioneSonoQuelleFraGliEstremi() {
    val selection = TextSelection(TextPosition(0, 2), TextPosition(1, 1))
    assertThat(TextSelections.contains(selection, TextPosition(0, 2))).isTrue()
    assertThat(TextSelections.contains(selection, TextPosition(0, 5))).isTrue()
    assertThat(TextSelections.contains(selection, TextPosition(1, 1))).isTrue()
    assertThat(TextSelections.contains(selection, TextPosition(0, 1))).isFalse()
    assertThat(TextSelections.contains(selection, TextPosition(1, 2))).isFalse()
  }

  @Test
  fun leManiglieSiAgganciaAlleParoleEPassanoDaUnaRigaAlLAltraNellOrdineDiLettura() {
    val geometry = LiveTextGeometry(layout, aspect)
    // La maniglia di fine, tirata dalla prima riga alla terza: la selezione cresce a cavallo di tutte le righe di mezzo.
    val fixed = TextPosition(0, 0)
    val moving = geometry.nearestWord(Offset(0.22f, 0.55f))!!
    assertThat(moving).isEqualTo(TextPosition(2, 0))
    val selection = TextSelections.between(fixed, moving)
    assertThat(TextSelections.spans(geometry, selection)).containsExactly(Triple(0, 0, 2), Triple(1, 0, 4), Triple(2, 0, 0)).inOrder()
    // Tirata oltre l'altra maniglia, le due si scambiano i ruoli: la selezione resta ordinata.
    val crossed = TextSelections.between(TextPosition(1, 2), geometry.nearestWord(Offset(0.20f, 0.36f))!!)
    assertThat(TextSelections.compare(crossed.from, crossed.to)).isAtMost(0)
  }

  @Test
  fun unaSelezioneFuoriMisuraNonRompeLaGeometria() {
    val geometry = LiveTextGeometry(layout, aspect)
    val wild = TextSelection(TextPosition(0, 99), TextPosition(9, 99))
    assertThat(TextSelections.spans(geometry, wild)).isNotEmpty()
    assertThat(TextSelections.text(layout, wild)).isNotEmpty()
  }
}
