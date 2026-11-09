package dev.pampa.mycloudgallery.feature.viewer

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.imaging.contract.ImageFailure
import dev.pampa.mycloudgallery.core.imaging.contract.UnavailableReason
import dev.pampa.mycloudgallery.core.imaging.contract.ViewerImageState
import dev.pampa.mycloudgallery.core.model.MediaRef
import org.junit.Test

/**
 * Gli stati della pagina, con sorgenti finte (Fatto quando della 3.4): una sorgente lenta (E-V1), irraggiungibile o da
 * confermare (E-V2, pronti per la Fase 4), un file che non si decodifica (E-M10). Il viewer non resta mai muto e la
 * miniatura non sparisce.
 */
class ViewerStatesTest : ViewerTestBase() {

  private fun script(vararg steps: (MediaRef) -> Step): ScriptedImages =
    ScriptedImages { ref -> steps.map { it(ref) } }

  private val preview: (MediaRef) -> Step = { ScriptedImages.preview(it) }
  private val sharp: (MediaRef) -> Step = { ScriptedImages.sharp(it) }
  private fun wait(ms: Long): (MediaRef) -> Step = { Step.Wait(ms) }
  private fun emit(state: ViewerImageState): (MediaRef) -> Step = { Step.Emit(state) }

  private fun pager() = composeRule.onNodeWithTag(ViewerTags.Pager)

  @Test
  fun e_V1_unaSorgenteLentaMostraSubitoLAnteprimaEDicePerche() {
    val source = script(preview, emit(ViewerImageState.Loading(progress = null)), wait(2_000), sharp)
    show(services = services(imageSource = source))
    composeRule.mainClock.autoAdvance = false
    composeRule.mainClock.advanceTimeBy(300)
    // L'originale non c'e' ancora: la nota lo dice, e l'anteprima e' gia' li'.
    composeRule.onNodeWithText("Carico l’originale").assertExists()
    composeRule.mainClock.advanceTimeBy(2_500)
    composeRule.waitForIdle()
    composeRule.onAllNodesWithText("Carico l’originale").assertCountEquals(0)
  }

  @Test
  fun e_V1_conLAvanzamentoLaNotaMostraLaBarraDeterminata() {
    val source = script(preview, emit(ViewerImageState.Loading(progress = 0.4f)), wait(60_000))
    show(services = services(imageSource = source))
    composeRule.onNodeWithText("Carico l’originale").assertExists()
  }

  @Test
  fun e_V2_conLaDimensioneEIlTastoPerIDatiMobili() {
    var confirmed: MediaRef? = null
    val source = script(
      preview,
      emit(ViewerImageState.Unavailable(UnavailableReason.NEEDS_MOBILE_DATA_CONFIRMATION, sizeBytes = 12_400_000L)),
    )
    show(services = services(imageSource = source, confirmMobileData = { confirmed = it }))
    composeRule.onNodeWithText("Servono i dati mobili").assertExists()
    composeRule.onNode(hasText("L’originale pesa", substring = true)).assertExists()
    composeRule.onNodeWithText("Scarica con i dati mobili").performClick()
    assertThat(confirmed).isEqualTo(fixture.items.ref(fixture.idAt(4)))
  }

  @Test
  fun e_V2_senzaIlCollegamentoDellaFase4NonProponeIlTasto() {
    val source = script(preview, emit(ViewerImageState.Unavailable(UnavailableReason.NEEDS_MOBILE_DATA_CONFIRMATION, 5_000_000L)))
    show(services = services(imageSource = source, confirmMobileData = null))
    composeRule.onNodeWithText("Servono i dati mobili").assertExists()
    composeRule.onAllNodesWithText("Scarica con i dati mobili").assertCountEquals(0)
  }

  @Test
  fun ilNasIrraggiungibileVieneSpiegato() {
    val source = script(preview, emit(ViewerImageState.Unavailable(UnavailableReason.NAS_UNREACHABLE)))
    show(services = services(imageSource = source))
    composeRule.onNodeWithText("Il NAS non risponde").assertExists()
  }

  @Test
  fun laReteNonConsentitaHaLaSuaFrase() {
    show(services = services(imageSource = script(preview, emit(ViewerImageState.Unavailable(UnavailableReason.NOT_ALLOWED_HERE)))))
    composeRule.onNodeWithText("Qui non si aprono i file del NAS").assertExists()
  }

  @Test
  fun laMicroSdToltaHaLaSuaFrase() {
    show(services = services(imageSource = script(preview, emit(ViewerImageState.Unavailable(UnavailableReason.VOLUME_UNMOUNTED)))))
    composeRule.onNodeWithText("La scheda microSD non c’è").assertExists()
  }

  @Test
  fun unFileCheNonSiDecodificaDaUnaFrasePerOgniCodice() {
    for (code in ImageFailure.entries) {
      val noticeKind = PageNoticeKind.of(PageImage().reduce(ViewerImageState.Failed(code)), videoProblem = null)
      assertThat(noticeKind).isEqualTo(PageNoticeKind.ImageFailed(code))
    }
    show(services = services(imageSource = script(preview, emit(ViewerImageState.Failed(ImageFailure.DECODE_FAILED)))))
    composeRule.onNodeWithText("Non riesco ad aprire questo file").assertExists()
    composeRule.onNodeWithText("Il file potrebbe essere rovinato.").assertExists()
  }

  @Test
  fun e_V5_unaScorsaSuMoltiElementiLentiTienePiuDiTreDecodificheInVoloELeAltreLeAnnulla() {
    val source = ScriptedImages { ref -> listOf(ScriptedImages.preview(ref), Step.Wait(5_000), ScriptedImages.sharp(ref)) }
    show(services = services(imageSource = source))
    composeRule.mainClock.autoAdvance = false
    repeat(14) {
      pager().performTouchInput { swipeToNext(durationMillis = 60) }
      composeRule.mainClock.advanceTimeBy(120)
    }
    composeRule.mainClock.advanceTimeBy(400)
    assertThat(source.maxInFlight).isAtMost(3)
    // Le pagine che il pager ha lasciato indietro hanno annullato la loro decodifica.
    assertThat(source.cancelled).isAtLeast(5)
    assertThat(source.started).isAtLeast(10)
  }

  @Test
  fun e_V6_lElementoCheSpariscePassaAlVicinoConUnAvviso() {
    show()
    composeRule.onNodeWithContentDescription("5 di 50").assertExists()
    fixture.items.publish(fixture.without(setOf(fixture.idAt(4))))
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("5 di 49").assertExists()
    composeRule.onNodeWithText("Questo elemento non c’è più").assertExists()
    assertThat(closed).isEqualTo(0)
  }

  @Test
  fun e_V6_lUltimoElementoCheSpariscePassaAlPrecedente() {
    val last = fixture.library.count - 1
    show(request = fixture.request(startIndex = last))
    fixture.items.publish(fixture.without(setOf(fixture.idAt(last))))
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("49 di 49").assertExists()
    composeRule.onNodeWithText("Questo elemento non c’è più").assertExists()
  }

  @Test
  fun e_V6_conLaListaVuotaIlViewerSiChiude() {
    show()
    fixture.items.publish(fixture.without((0 until fixture.library.count).map { fixture.idAt(it) }.toSet()))
    composeRule.waitUntil(10_000L) { closed >= 1 }
    assertThat(closed).isAtLeast(1)
  }
}
