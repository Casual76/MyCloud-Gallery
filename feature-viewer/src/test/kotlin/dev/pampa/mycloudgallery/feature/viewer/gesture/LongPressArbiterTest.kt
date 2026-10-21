package dev.pampa.mycloudgallery.feature.viewer.gesture

import androidx.compose.ui.geometry.Offset
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.feature.viewer.livetext.TextPosition
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * L'arbitro della pressione prolungata (Fase 6, P12, D45: tabella "Gesti" del §2.10). La decisione e' pura: dove cade il dito e che
 * cosa c'e' sotto. P13 si aggancia a `subject`.
 */
class LongPressArbiterTest {

  private val onWord = TextClaim { TextPosition(0, 1) }
  private val noText = TextClaim { null }
  private val point = Offset(0.4f, 0.5f)

  private fun decide(kind: MediaKind?, text: TextClaim?, subject: Boolean, at: Offset = point): LongPressOutcome =
    runBlocking { LongPressArbiter.decide(kind, at, text, subject) }

  @Test
  fun suUnaRigaDiTestoLettoEIlTestoDalVivo() {
    assertThat(decide(MediaKind.PHOTO, onWord, subject = true)).isEqualTo(LongPressOutcome.Text(TextPosition(0, 1)))
  }

  @Test
  fun altroveSuUnaFotoEIlRitaglioConIlPuntoNormalizzato() {
    assertThat(decide(MediaKind.PHOTO, noText, subject = true)).isEqualTo(LongPressOutcome.Subject(0.4f, 0.5f))
  }

  @Test
  fun e_V12_unaFotoInMovimentoSiComportaComeUnaFoto() {
    assertThat(decide(MediaKind.MOTION_PHOTO, onWord, subject = true)).isEqualTo(LongPressOutcome.Text(TextPosition(0, 1)))
    assertThat(decide(MediaKind.MOTION_PHOTO, noText, subject = true)).isEqualTo(LongPressOutcome.Subject(0.4f, 0.5f))
  }

  @Test
  fun suUnVideoNienteENemmenoIlTesto() {
    var asked = false
    assertThat(decide(MediaKind.VIDEO, TextClaim { asked = true; TextPosition(0, 0) }, subject = true)).isEqualTo(LongPressOutcome.None)
    assertThat(asked).isFalse()
  }

  @Test
  fun e_V9_senzaTestoELaPressioneVaAlRitaglio_eSenzaRitaglioNonFaNiente() {
    assertThat(decide(MediaKind.PHOTO, noText, subject = true)).isInstanceOf(LongPressOutcome.Subject::class.java)
    assertThat(decide(MediaKind.PHOTO, noText, subject = false)).isEqualTo(LongPressOutcome.None)
    assertThat(decide(MediaKind.PHOTO, text = null, subject = false)).isEqualTo(LongPressOutcome.None)
  }

  @Test
  fun fuoriDallImmagineNonSiDecideNiente() {
    var asked = false
    val claim = TextClaim { asked = true; null }
    for (outside in listOf(Offset(-0.01f, 0.5f), Offset(0.5f, 1.01f), Offset(2f, 2f))) {
      assertThat(decide(MediaKind.PHOTO, claim, subject = true, at = outside)).isEqualTo(LongPressOutcome.None)
    }
    assertThat(asked).isFalse()
  }

  @Test
  fun unMediaSconosciutoNonFaNiente() {
    assertThat(decide(null, onWord, subject = true)).isEqualTo(LongPressOutcome.None)
  }

  @Test
  fun gliAltriTipiDiImmagineSiComportanoComeUnaFoto() {
    for (kind in listOf(MediaKind.PHOTO, MediaKind.RAW_PHOTO)) {
      assertThat(decide(kind, onWord, subject = false)).isEqualTo(LongPressOutcome.Text(TextPosition(0, 1)))
    }
  }
}
