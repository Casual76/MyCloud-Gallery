package dev.pampa.mycloudgallery.core.ai.compute

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.contract.AiRecipe
import dev.pampa.mycloudgallery.core.ai.contract.AiScope
import dev.pampa.mycloudgallery.core.ai.contract.AiStop
import dev.pampa.mycloudgallery.core.ai.contract.OcrResult
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.OcrSource
import dev.pampa.mycloudgallery.core.model.VecSource
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.OcrLine
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.ShortBox
import dev.pampa.mycloudgallery.core.trust.data.WordSpan
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Lettura del testo (D9, D11, D12, D41, D42, E-A18) e ricetta nuova graduale (D21, E-A10). Ogni test che tocca l'OCR ha la variante
 * "lettura spenta" con il lettore finto che fallisce se chiamato (§4.3): `FakeTextReader` lancia un `AssertionError`.
 */
class TextAndRecipeTest {
  private val forever = Duration.INFINITE
  private val common = TestFamily.COMMON

  private fun line(text: String): OcrLine {
    val n = text.split(' ').size
    val step = 60_000 / n
    return OcrLine(ShortBox(0, 0, 60_000, 4_000), 0, (0 until n).map { WordSpan(it * step, it * step + step - 1) }, text)
  }

  @Test
  fun conLaLetturaSpentaNessunaChiamataAlMotoreOcr() = runTest {
    val f = TestFamily()
    f.nas.add(common, concepts = "scontrino")
    f.nas.add(common, source = MediaSource.SCREENSHOT, concepts = "chat")
    f.nas.add(common, kind = MediaKind.VIDEO, concepts = "cartello")
    val phone = f.anna(reading = false)
    phone.engine.runAll(forever)
    assertThat(phone.reader.reads).isEmpty()
    // Il codice OCR non si raggiunge nemmeno: il provider del lettore non e' mai stato chiamato (regola di P0, D41).
    assertThat(phone.readerAsked).isEqualTo(0)
    assertThat(f.published(common, phone.id, AiClass.OCR)).isEmpty()
    assertThat(f.published(common, phone.id, AiClass.VEC)).hasSize(3)
  }

  @Test
  fun conLaLetturaAttiva_analisiHqPerScreenshotEFitto_fotogrammiSenzaDoppioni() = runTest {
    val f = TestFamily()
    val photo = f.nas.add(common, concepts = "scontrino")
    val shot = f.nas.add(common, source = MediaSource.SCREENSHOT, concepts = "chat")
    val dense = f.nas.add(common, concepts = "contratto")
    val video = f.nas.add(common, kind = MediaKind.VIDEO, concepts = "cartello")
    f.nas.of(common).previews[shot to SegmentClass.HQ] = "FAKE:chat-hq".toByteArray()
    f.nas.of(common).previews[dense to SegmentClass.HQ] = "FAKE:contratto-hq".toByteArray()
    val phone = f.anna(reading = true)
    phone.reader.fixture("scontrino ${photo.hex.takeLast(4)}", OcrResult(listOf(line("Totale 12 euro")), dense = false))
    phone.reader.fixture("chat-hq", OcrResult(listOf(line("Ciao come stai")), dense = false))
    phone.reader.fixture("contratto ${dense.hex.takeLast(4)}", OcrResult(listOf(line("fitto")), dense = true))
    phone.reader.fixture("contratto-hq", OcrResult(listOf(line("Articolo 1"), line("Articolo 2")), dense = false))
    val tag = "cartello ${video.hex.takeLast(4)}"
    phone.reader.fixture("$tag f0", OcrResult(listOf(line("Vietato"), line("sosta")), dense = false))
    phone.reader.fixture("$tag f2", OcrResult(listOf(line("sosta"), line("Rimozione")), dense = false))

    phone.engine.runAll(forever)

    val texts = phone.results.texts
    assertThat(texts[photo]!!.source).isEqualTo(OcrSource.ANALYSIS)
    assertThat(texts[shot]!!.source).isEqualTo(OcrSource.HQ)
    assertThat(texts[shot]!!.lines.single().text).isEqualTo("Ciao come stai")
    assertThat(texts[dense]!!.source).isEqualTo(OcrSource.HQ)
    assertThat(texts[dense]!!.lines.map { it.text }).containsExactly("Articolo 1", "Articolo 2")
    assertThat(texts[video]!!.source).isEqualTo(OcrSource.FRAMES)
    assertThat(texts[video]!!.lines.map { it.text }).containsExactly("Vietato", "sosta", "Rimozione").inOrder()
    // Video: copertina e fotogramma centrale (4 fotogrammi: 0 e 2), un vettore per fotogramma (D11).
    assertThat(phone.results.vectors[video]!!.vectors).hasSize(4)
    assertThat(phone.results.vectors[video]!!.source).isEqualTo(VecSource.FRAMES)
    assertThat(f.published(common, phone.id, AiClass.OCR)).containsExactly(photo, shot, dense, video)
  }

  /** D9: "fatto" per classe. Chi ha la lettura attiva fa solo il testo che manca; chi l'ha spenta non ha lavoro per un `ocr` mancante. */
  @Test
  fun fattoPerClasse() = runTest {
    val f = TestFamily()
    val both = f.nas.add(common, concepts = "a")
    val onlyVec = f.nas.add(common, concepts = "b")
    val first = f.anna("telefono-anna", reading = true)
    first.engine.runAll(forever)
    // Il primo indicizzatore ha poi spento la lettura: per "b" togliamo il testo pubblicato (come se non l'avesse letto).
    f.nas.of(common).heads[first.id]!!.remove(onlyVec to AiClass.OCR)

    val noReading = f.bruno(reading = false)
    noReading.engine.runAll(forever)
    assertThat(noReading.embedded).isEqualTo(0)
    assertThat(noReading.units.claims).isEmpty()

    val reading = f.bruno("tablet-bruno", reading = true)
    reading.engine.runAll(forever)
    assertThat(reading.embedded).isEqualTo(0) // il vettore c'e' gia'
    assertThat(f.published(common, reading.id, AiClass.OCR)).containsExactly(onlyVec)
    assertThat(f.published(common, reading.id, AiClass.VEC)).isEmpty()
    assertThat(both).isNotNull()
  }

  /** E-A18: la lettura si spegne a meta' tappa: da li' in poi nessuna chiamata al motore OCR (il fake fallirebbe). */
  @Test
  fun letturaSpentaAMetaTappa() = runTest {
    val f = TestFamily()
    (1..6).forEach { f.nas.add(common, concepts = "foglio $it") }
    val phone = f.anna(reading = true)
    phone.restart(AiComputeConfig(batch = 2, reducedBatch = 1))
    var n = 0
    val off = object : AiRunControl {
      override suspend fun check(): AiPace {
        if (++n == 2) phone.roles.textReadingHere.value = false
        return AiPace.GO
      }
    }
    phone.engine.run(AiScope.Share(common), forever, off)
    assertThat(phone.reader.reads).hasSize(2)
    assertThat(f.published(common, phone.id, AiClass.VEC)).hasSize(6)
    assertThat(f.published(common, phone.id, AiClass.OCR)).hasSize(2)
  }

  /** E-A10, D21: ricetta nuova; si ricalcola dal piu' recente, il vettore vecchio vale finche' non arriva il nuovo. */
  @Test
  fun ricettaNuovaGraduale() = runTest {
    val f = TestFamily()
    val byMonth = (1..4).associate { m -> "2024-0$m" to (1..3).map { f.nas.add(common, month = "2024-0$m", concepts = "m$m $it") } }
    val phone = f.anna()
    phone.engine.runAll(forever)
    assertThat(phone.results.vectors.values.map { it.recipe }.toSet()).containsExactly(1)

    phone.recipe = AiRecipe(version = 2, ocrVersion = 1)
    phone.restart()
    val partial = phone.engine.run(AiScope.Share(common), 3.seconds) // 1 s per immagine nell'orologio finto
    assertThat(partial.stoppedBy).isEqualTo(AiStop.BUDGET)
    val v = phone.results.vectors
    assertThat(byMonth.getValue("2024-04").map { v[it]!!.recipe }).containsExactly(2, 2, 2)
    for (old in listOf("2024-01", "2024-02", "2024-03")) {
      assertThat(byMonth.getValue(old).map { v[it]!!.recipe }.toSet()).containsExactly(1) // il vecchio vale ancora
    }
    // Le voci pubblicate con la ricetta 1 non bastano per la 2: alla fine tutto e' alla 2.
    phone.engine.run(AiScope.Share(common), forever)
    assertThat(v.values.map { it.recipe }.toSet()).containsExactly(2)
    assertThat(f.nas.of(common).heads[phone.id]!!.values.map { it.recipe }.toSet()).containsExactly(2)
  }

  /** E-A12: ingresso non decodificabile = "non supportato" (segnato, non riprovato a ogni giro); GPU che cade = CPU, vettore valido. */
  @Test
  fun ingressoRifiutatoEGpuCheCade() = runTest {
    val f = TestFamily()
    val bad = f.nas.add(common, preview = false)
    f.nas.of(common).previews[bad to SegmentClass.ANALYSIS] = SimBytes.BAD
    val good = f.nas.add(common, concepts = "cane")
    val phone = f.anna()
    phone.images.fake.gpuFailsNext = true
    val r = phone.engine.run(AiScope.Share(common), forever)
    assertThat(r.failed).isEqualTo(1)
    assertThat(phone.unsupported).contains(bad to AiClass.VEC)
    assertThat(f.published(common, phone.id)).containsExactly(good)
    assertThat(phone.engine.counters.gpuFallbacks.get()).isEqualTo(1)
    assertThat(phone.engine.run(AiScope.Share(common), forever).failed).isEqualTo(0) // non si riprova
  }
}
