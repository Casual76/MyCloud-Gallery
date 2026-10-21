package dev.pampa.mycloudgallery.core.ai

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.contract.AiBackend
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineError
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineException
import dev.pampa.mycloudgallery.core.ai.contract.CutoutResult
import dev.pampa.mycloudgallery.core.ai.contract.NormalizedPoint
import dev.pampa.mycloudgallery.core.ai.contract.OcrResult
import dev.pampa.mycloudgallery.core.ai.contract.WarmUpResult
import dev.pampa.mycloudgallery.core.ai.testing.AiTestInputs
import dev.pampa.mycloudgallery.core.ai.testing.EmbedderContract
import dev.pampa.mycloudgallery.core.ai.testing.FakeConcepts
import dev.pampa.mycloudgallery.core.ai.testing.FakeImageEmbedder
import dev.pampa.mycloudgallery.core.ai.testing.FakeSubjectCutter
import dev.pampa.mycloudgallery.core.ai.testing.FakeTextEmbedder
import dev.pampa.mycloudgallery.core.ai.testing.FakeTextReader
import dev.pampa.mycloudgallery.core.trust.data.OcrLine
import dev.pampa.mycloudgallery.core.trust.data.ShortBox
import dev.pampa.mycloudgallery.core.trust.data.WordSpan
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Fase 6 (P0, §5.2): i motori finti passano la stessa suite di contratto dei motori veri (`EmbedderContract`, che P4 fa girare
 * strumentata sul motore vero) e si comportano come promesso: sinonimi vicini, lettore che fallisce da spento, ritaglio dalle forme.
 */
class FakeEnginesTest {

  @Test
  fun ilMotoreDelleQueryFintoPassaLaSuiteDiContratto() = runTest {
    EmbedderContract.verifyText(FakeTextEmbedder(latencyMs = 5, coldStartMs = 50))
  }

  @Test
  fun ilMotoreDelleImmaginiFintoPassaLaSuiteDiContratto() = runTest {
    EmbedderContract.verifyImages(FakeImageEmbedder(), listOf(AiTestInputs.image("cane spiaggia"), AiTestInputs.image("scontrino")))
  }

  /** I sinonimi danno lo stesso concetto; concetti diversi stanno lontani; immagine e testo dello stesso concetto vicini. */
  @Test
  fun iConcettiSonoQuelliDellaTabella() = runTest {
    val text = FakeTextEmbedder()
    val images = FakeImageEmbedder()
    val cane = text.embed("cane").values
    val cagnolino = text.embed("il cagnolino").values
    val scontrino = text.embed("scontrino").values
    assertThat(EmbedderContract.cosine(cane, cagnolino)).isGreaterThan(0.99f)
    assertThat(EmbedderContract.cosine(cane, scontrino)).isLessThan(0.2f)
    val (dogBeach, receipt) = images.embed(listOf(AiTestInputs.image("cani al mare"), AiTestInputs.image("ricevuta")))
    val query = text.embed("cane in spiaggia").values
    assertThat(EmbedderContract.cosine(query, dogBeach)).isGreaterThan(EmbedderContract.cosine(query, receipt) + 0.5f)
    assertThat(FakeConcepts.conceptsOf("Già, l'Esselunga!")).containsExactly("w:gia", "w:l", "w:esselunga")
  }

  /** E-A12: la GPU che da' valori non finiti fa passare il motore su CPU per la sessione; un guasto e' un AiEngineException. */
  @Test
  fun ilRipiegoSuCpuEIGuastiSonoQuelliDelContratto() = runTest {
    val images = FakeImageEmbedder()
    images.gpuFailsNext = true
    images.embed(listOf(AiTestInputs.image("tramonto")))
    assertThat(images.backend.value).isEqualTo(AiBackend.CPU)
    assertThat(images.fallbacks).isEqualTo(1)

    val text = FakeTextEmbedder()
    text.failWarmUp = AiEngineError.INIT_FAILED
    assertThat(text.warmUp()).isEqualTo(WarmUpResult.Failed(AiEngineError.INIT_FAILED))
    val error = runCatching { text.embed("cane") }.exceptionOrNull()
    assertThat((error as AiEngineException).error).isEqualTo(AiEngineError.INIT_FAILED)
  }

  /** §4.3, D41: con la lettura del testo spenta il lettore finto fallisce se chiamato, e nessun catch(Exception) lo nasconde. */
  @Test
  fun ilLettoreFintoFallisceSeChiamatoDaSpento() = runTest {
    var on = true
    val reader = FakeTextReader(readingHere = { on })
    val line = OcrLine(ShortBox(0, 0, 30000, 4000), angle = 0, words = listOf(WordSpan(0, 30000), WordSpan(35000, 65535)), text = "Totale euro")
    reader.fixture("scontrino", OcrResult(listOf(line), dense = false))
    assertThat(reader.read(AiTestInputs.ocr("scontrino")).lines.single().text).isEqualTo("Totale euro")
    assertThat(reader.read(AiTestInputs.ocr("prato")).lines).isEmpty()
    on = false
    val thrown = runCatching {
      try {
        reader.read(AiTestInputs.ocr("scontrino"))
      } catch (e: Exception) {
        null
      }
    }.exceptionOrNull()
    assertThat(thrown).isInstanceOf(AssertionError::class.java)
  }

  /** D44, E-V10: il soggetto e' la forma toccata; il cielo uniforme non e' un soggetto chiaro. */
  @Test
  fun ilRitaglioFintoPrendeLaFormaToccata() = runTest {
    val w = 100
    val h = 80
    val sky = 0xFF87CEEB.toInt()
    val ball = 0xFFD2691E.toInt()
    val pixels = IntArray(w * h) { i ->
      val x = i % w
      val y = i / w
      if ((x - 50) * (x - 50) + (y - 40) * (y - 40) <= 15 * 15) ball else sky
    }
    val cutter = FakeSubjectCutter()
    val image = AiTestInputs.cutout(w, h, pixels)
    val subject = cutter.cut(image, NormalizedPoint(0.5f, 0.5f)) as CutoutResult.Subject
    assertThat(subject.mask.coverage).isWithin(0.02f).of(0.088f)
    assertThat(subject.bounds.left).isEqualTo(35)
    assertThat(cutter.cut(image, NormalizedPoint(0.02f, 0.02f))).isEqualTo(CutoutResult.NoClearSubject)
    assertThrows(IllegalArgumentException::class.java) { NormalizedPoint(1.2f, 0f) }
  }
}
