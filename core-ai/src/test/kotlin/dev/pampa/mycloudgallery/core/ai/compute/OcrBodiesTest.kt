package dev.pampa.mycloudgallery.core.ai.compute

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.contract.AiRecipe
import dev.pampa.mycloudgallery.core.model.OcrSource
import dev.pampa.mycloudgallery.core.trust.data.AiLimits
import dev.pampa.mycloudgallery.core.trust.data.AiSegments
import dev.pampa.mycloudgallery.core.trust.data.OcrLine
import dev.pampa.mycloudgallery.core.trust.data.ShortBox
import dev.pampa.mycloudgallery.core.trust.data.WordSpan
import org.junit.Test

/**
 * Il corpo `ocr` da cio' che il lettore ha trovato (§2.2 "Controlli"; Fase 6, P11b): lo costruiscono il calcolo e la lettura al volo del viewer,
 * cosi' salvano e pubblicano lo stesso corpo. Righe nei tetti, "letto, nessun testo" ammesso.
 */
class OcrBodiesTest {

  private fun line(text: String, top: Int = 0): OcrLine {
    val n = text.split(' ').size
    val step = 60_000 / n
    return OcrLine(ShortBox(0, top, 60_000, top + 1_000), 0, (0 until n).map { WordSpan(it * step, it * step + step - 1) }, text)
  }

  @Test
  fun unaRigaValidaEntraComeE() {
    val body = checkNotNull(OcrBodies.of(OcrSource.HQ, listOf(line("Pizzeria Mario"))))

    assertThat(body.lines.map { it.text }).containsExactly("Pizzeria Mario")
    assertThat(body.source).isEqualTo(OcrSource.HQ)
    assertThat(body.recipe).isEqualTo(AiRecipe.CURRENT.ocrVersion)
    assertThat(AiSegments.admits(body)).isTrue()
  }

  @Test
  fun senzaTestoIlCorpoEVuotoMaValido() {
    val body = checkNotNull(OcrBodies.of(OcrSource.ANALYSIS, emptyList()))

    assertThat(body.lines).isEmpty()
    assertThat(AiSegments.admits(body)).isTrue()
  }

  @Test
  fun leRigheOltreIlTettoSiTolgonoDallaFine() {
    val many = (0 until AiLimits.MAX_OCR_LINES + 25).map { line("riga$it", top = it) }

    val body = checkNotNull(OcrBodies.of(OcrSource.ANALYSIS, many))

    assertThat(body.lines.size).isAtMost(AiLimits.MAX_OCR_LINES)
    assertThat(body.lines.first().text).isEqualTo("riga0")
    assertThat(AiSegments.admits(body)).isTrue()
  }
}
