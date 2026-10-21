package dev.pampa.mycloudgallery.core.trust

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.Float16
import dev.pampa.mycloudgallery.core.model.OcrEngineCode
import dev.pampa.mycloudgallery.core.model.OcrSource
import dev.pampa.mycloudgallery.core.model.SpaceCode
import dev.pampa.mycloudgallery.core.model.VecSource
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.AiLimits
import dev.pampa.mycloudgallery.core.trust.data.OcrBody
import dev.pampa.mycloudgallery.core.trust.data.OcrLine
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.ShortBox
import dev.pampa.mycloudgallery.core.trust.data.VecBody
import dev.pampa.mycloudgallery.core.trust.data.VecItem
import dev.pampa.mycloudgallery.core.trust.data.WordSpan
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Fase 6 (P0, §2.3): la FORMA dei corpi `vec` e `ocr` (conteggi, intervalli, tetti). I controlli ostili sui byte (valori non finiti,
 * norma, UTF-8, controllo e direzione, E-I20) sono del codec di P2.
 */
class AiBodiesShapeTest {

  private fun vector(frameMs: Int? = null) = VecItem(frameMs, Float16.encode(FloatArray(768) { if (it == 0) 1f else 0f }))

  @Test
  fun leClassiAiHannoICodiciDelFormato() {
    assertThat(AiClass.entries.associate { it.wire to it.code }).containsExactly("vec", 7, "ocr", 8)
    // I codici 7 e 8 non possono essere un'altra classe dell'indice (P2 puo' aggiungere `vec` e `ocr` a SegmentClass, con gli stessi).
    for (cls in SegmentClass.entries.filter { it.code == 7 || it.code == 8 }) {
      assertThat(cls.wire).isEqualTo(AiClass.fromCode(cls.code)!!.wire)
    }
  }

  @Test
  fun unCorpoVecHaLaFormaDelProgetto() {
    val photo = VecBody(SpaceCode.CURRENT, recipe = 1, source = VecSource.ANALYSIS, vectors = listOf(vector()))
    assertThat(photo.dimension).isEqualTo(768)
    val video = VecBody(SpaceCode.CURRENT, 1, VecSource.FRAMES, List(9) { vector(it * 30_000) })
    assertThat(video.vectors).hasSize(AiLimits.MAX_VECTORS)
    // Una foto ha un vettore solo; un video al piu' 9 e ognuno col suo istante; ricetta 1..255.
    assertThrows(IllegalArgumentException::class.java) { VecBody(SpaceCode.CURRENT, 1, VecSource.ANALYSIS, listOf(vector(), vector())) }
    assertThrows(IllegalArgumentException::class.java) { VecBody(SpaceCode.CURRENT, 1, VecSource.FRAMES, List(10) { vector(it) }) }
    assertThrows(IllegalArgumentException::class.java) { VecBody(SpaceCode.CURRENT, 1, VecSource.FRAMES, listOf(vector(null))) }
    assertThrows(IllegalArgumentException::class.java) { VecBody(SpaceCode.CURRENT, 0, VecSource.ANALYSIS, listOf(vector())) }
    assertThrows(IllegalArgumentException::class.java) {
      VecBody(SpaceCode.CURRENT, 1, VecSource.ANALYSIS, listOf(VecItem(null, ShortArray(256))))
    }
  }

  @Test
  fun unCorpoOcrHaLaFormaDelProgetto() {
    val line = OcrLine(ShortBox(100, 200, 30_000, 4_000), angle = -5, words = listOf(WordSpan(0, 20_000), WordSpan(25_000, 65_535)), text = "Via Roma")
    val body = OcrBody(OcrEngineCode.MLKIT_V2_LATIN, recipe = 1, source = OcrSource.HQ, lines = listOf(line))
    assertThat(body.noText).isFalse()
    assertThat(OcrBody(OcrEngineCode.MLKIT_V2_LATIN, 1, OcrSource.ANALYSIS, emptyList()).noText).isTrue()
    // Il testo non va mai in un toString (D40).
    assertThat(line.toString()).doesNotContain("Roma")
    assertThrows(IllegalArgumentException::class.java) { OcrLine(ShortBox(0, 0, 1, 1), 91, listOf(WordSpan(0, 1)), "x") }
    assertThrows(IllegalArgumentException::class.java) { OcrLine(ShortBox(0, 0, 1, 1), 0, listOf(WordSpan(0, 1)), "due parole") }
    assertThrows(IllegalArgumentException::class.java) { OcrLine(ShortBox(0, 0, 1, 1), 0, listOf(WordSpan(5, 9), WordSpan(1, 2)), "a b") }
    assertThrows(IllegalArgumentException::class.java) { ShortBox(10, 0, 5, 0) }
    assertThrows(IllegalArgumentException::class.java) { OcrLine(ShortBox(0, 0, 1, 1), 0, listOf(WordSpan(0, 1)), "x".repeat(1_025)) }
    assertThrows(IllegalArgumentException::class.java) {
      OcrBody(OcrEngineCode.MLKIT_V2_LATIN, 1, OcrSource.ANALYSIS, List(401) { line })
    }
  }
}
