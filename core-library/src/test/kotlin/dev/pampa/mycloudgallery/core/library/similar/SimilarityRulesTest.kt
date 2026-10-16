package dev.pampa.mycloudgallery.core.library.similar

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.VisualSignature
import org.junit.Test

/** Le condizioni 2 e 3 del §2.12 (Fase 5A, P15) ai loro bordi, e il giudizio sulle distanze. */
class SimilarityRulesTest {

  @Test
  fun laProporzioneHaLoScartoDell1PerCento() {
    assertThat(SimilarityRules.aspectCompatible(1333, 1333)).isTrue()
    assertThat(SimilarityRules.aspectCompatible(1333, 1346)).isTrue() // 0,97%
    assertThat(SimilarityRules.aspectCompatible(1333, 1347)).isFalse() // 1,04%
    assertThat(SimilarityRules.aspectCompatible(1347, 1333)).isFalse()
    assertThat(SimilarityRules.aspectCompatible(1778, 1333)).isFalse() // 16:9 contro 4:3
    assertThat(SimilarityRules.aspectCompatible(750, 1333)).isFalse() // verticale contro orizzontale
    assertThat(SimilarityRules.aspectCompatible(0, 0)).isFalse()
    assertThat(SimilarityRules.aspectCompatible(-1, 1333)).isFalse()
  }

  @Test
  fun laDurataDeiVideoHaLoScartoDiMezzoSecondo() {
    assertThat(SimilarityRules.durationCompatible(8_000, 8_000)).isTrue()
    assertThat(SimilarityRules.durationCompatible(8_000, 8_500)).isTrue()
    assertThat(SimilarityRules.durationCompatible(8_500, 8_000)).isTrue()
    assertThat(SimilarityRules.durationCompatible(8_000, 8_501)).isFalse()
    assertThat(SimilarityRules.durationCompatible(8_000, null)).isFalse()
    assertThat(SimilarityRules.durationCompatible(null, null)).isFalse()
  }

  @Test
  fun iSottosecondiDevonoCoincidereSeCiSonoSuEntrambi() {
    val s = 1_760_000_000_000L
    assertThat(SimilarityRules.subSecondCompatible(s + 123, s + 123)).isTrue()
    assertThat(SimilarityRules.subSecondCompatible(s + 123, s + 124)).isFalse()
    assertThat(SimilarityRules.subSecondCompatible(s + 123, s + 900)).isFalse()
    // Uno senza (millisecondi a zero): decide la firma.
    assertThat(SimilarityRules.subSecondCompatible(s, s + 123)).isTrue()
    assertThat(SimilarityRules.subSecondCompatible(s + 123, s)).isTrue()
    assertThat(SimilarityRules.subSecondCompatible(s, s)).isTrue()
    // Date prima dell'epoca: il modulo non va in negativo.
    assertThat(SimilarityRules.subSecondCompatible(-1_000L + 250, -2_000L + 250)).isTrue()
  }

  @Test
  fun ilGiudizioSullaDistanzaAiBordi() {
    fun v(mean: Float, peak: Float, tail: Float, structure: Float = 0.1f) = SimilarityRules.verdict(SignatureDistance(mean, peak, tail, structure))
    assertThat(v(0.005f, 0.01f, 0.02f)).isEqualTo(VisualVerdict.SAME)
    assertThat(v(SimilarityRules.MEAN_MAX, SimilarityRules.PEAK_MAX, SimilarityRules.TAIL_MAX)).isEqualTo(VisualVerdict.SAME)
    assertThat(v(SimilarityRules.MEAN_MAX + 0.0001f, 0f, 0f)).isEqualTo(VisualVerdict.DIFFERENT)
    assertThat(v(0f, SimilarityRules.PEAK_MAX + 0.0001f, 0f)).isEqualTo(VisualVerdict.DIFFERENT)
    assertThat(v(0f, 0f, SimilarityRules.TAIL_MAX + 0.0001f)).isEqualTo(VisualVerdict.DIFFERENT)
    // Poca struttura: sotto le soglie non prova niente; sopra le soglie e' comunque diverso.
    assertThat(v(0.001f, 0.002f, 0.003f, structure = SimilarityRules.MIN_STRUCTURE - 0.001f)).isEqualTo(VisualVerdict.UNDECIDABLE)
    assertThat(v(0.1f, 0.2f, 0.3f, structure = 0.001f)).isEqualTo(VisualVerdict.DIFFERENT)
  }

  @Test
  fun firmeDiAlgoritmoOFormaDiversiNonSiConfrontano() {
    val photo = signatureOf(1)
    val video = VisualSignatures.of(listOf(pattern(1), pattern(1)))
    assertThat(SimilarityRules.visual(photo, video)).isEqualTo(VisualVerdict.UNDECIDABLE)
    val alien = VisualSignature.of(2, 1, ByteArray(VisualSignatures.FRAME_BYTES) { 7 })
    assertThat(SimilarityRules.visual(photo, alien)).isEqualTo(VisualVerdict.UNDECIDABLE)
    // Stesso algoritmo ma byte della forma sbagliata: non si confronta.
    val wrongSize = VisualSignature.of(VisualSignatures.ALGORITHM, 1, ByteArray(100) { 7 })
    assertThat(SignatureComparison.distance(photo, wrongSize)).isNull()
  }
}
