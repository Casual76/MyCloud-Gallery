package dev.pampa.mycloudgallery.core.ai.cutout

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineError
import dev.pampa.mycloudgallery.core.ai.contract.CutoutImage
import dev.pampa.mycloudgallery.core.ai.contract.CutoutResult
import dev.pampa.mycloudgallery.core.ai.testing.CutoutFixtures
import dev.pampa.mycloudgallery.core.ai.testing.CutoutQuality
import dev.pampa.mycloudgallery.core.ai.testing.FakeSubjectCutter
import dev.pampa.mycloudgallery.core.ai.testing.SubjectCutterContract
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/** Fase 6 (P13): il quadro del modello, il pacchetto `.task`, le fixture e il ritagliatore finto che passa la stessa suite di contratto. */
class FramingBundleAndFixturesTest {

  // --- Il quadro --------------------------------------------------------------------------------------------------------------------

  @Test
  fun unaProporzioneNormaleSiDeformaUnaEstremaHaIBordi() {
    val tuning = MagicTouchTuning.DEFAULT
    assertThat(Framing.useLetterbox(4000, 3000, tuning)).isFalse()
    assertThat(Framing.useLetterbox(1920, 1080, tuning)).isFalse()
    assertThat(Framing.useLetterbox(4000, 2000, tuning)).isTrue()
    assertThat(Framing.useLetterbox(3000, 1000, tuning)).isTrue()
    assertThat(Framing.useLetterbox(1000, 4000, tuning)).isTrue()
    assertThat(Framing.useLetterbox(4000, 3000, tuning.copy(fit = MagicTouchTuning.Fit.LETTERBOX))).isTrue()
    assertThat(Framing.useLetterbox(3000, 1000, tuning.copy(fit = MagicTouchTuning.Fit.STRETCH))).isFalse()
  }

  @Test
  fun unPanoramaSiGuardaAFinestreIntornoAlDitoConProporzioniNormali() {
    val tuning = MagicTouchTuning.DEFAULT
    // 4096x572 (7:1): la finestra e' alta quanto l'immagine, larga 1,5 volte, centrata sul punto e tenuta dentro l'immagine.
    val w = Framing.window(4096, 572, 0.5f, 0.5f, tuning)!!
    assertThat(w.height).isEqualTo(572)
    assertThat(w.width).isEqualTo(858)
    assertThat(w.left).isEqualTo(2048 - 429)
    val atLeft = Framing.window(4096, 572, 0.01f, 0.5f, tuning)!!
    assertThat(atLeft.left).isEqualTo(0)
    val atRight = Framing.window(4096, 572, 0.99f, 0.5f, tuning)!!
    assertThat(atRight.left + atRight.width).isEqualTo(4096)
    // Una torre (572x4096): la finestra e' alta 1,5 volte la larghezza.
    val tall = Framing.window(572, 4096, 0.5f, 0.9f, tuning)!!
    assertThat(tall.width).isEqualTo(572)
    assertThat(tall.height).isEqualTo(858)
    assertThat(tall.top + tall.height).isEqualTo(4096)
    // Proporzioni normali, e adattamenti scelti a mano: niente finestra.
    assertThat(Framing.window(4000, 3000, 0.5f, 0.5f, tuning)).isNull()
    assertThat(Framing.window(1920, 1080, 0.5f, 0.5f, tuning)).isNull()
    assertThat(Framing.window(4096, 572, 0.5f, 0.5f, tuning.copy(fit = MagicTouchTuning.Fit.LETTERBOX))).isNull()
  }

  @Test
  fun ilQuadroConBordiMettePadAlCentroELaMappaDelPunto() {
    val pixels = IntArray(200 * 100) { 0xFFFF0000.toInt() }
    val frame = Framing.frame(pixels, 200, 100, 64, letterbox = true)
    assertThat(frame.contentWidth).isEqualTo(64)
    assertThat(frame.contentHeight).isEqualTo(32)
    assertThat(frame.contentTop).isEqualTo(16)
    // Rosso dentro, grigio fuori.
    assertThat(frame.rgb[(32 * 64 + 10) * 3]).isWithin(1e-3f).of(255f)
    assertThat(frame.rgb[(2 * 64 + 10) * 3]).isWithin(1e-3f).of(ModelFrame.PAD)
    // Il centro dell'immagine e' il centro del quadro; l'angolo in alto a sinistra e' dove comincia il contenuto.
    assertThat(frame.frameX(0.5f)).isWithin(1e-4f).of(0.5f)
    assertThat(frame.frameY(0.5f)).isWithin(1e-4f).of(0.5f)
    assertThat(frame.frameY(0f)).isWithin(1e-4f).of(0.25f)
    assertThat(frame.frameY(1f)).isWithin(1e-4f).of(0.75f)
  }

  @Test
  fun senzaBordiIlQuadroEOgniPixel() {
    val pixels = IntArray(10 * 20) { 0xFF00FF00.toInt() }
    val frame = Framing.frame(pixels, 10, 20, 32, letterbox = false)
    assertThat(frame.contentWidth).isEqualTo(32)
    assertThat(frame.frameX(0.25f)).isWithin(1e-4f).of(0.25f)
  }

  @Test
  fun leImpostazioniDellePROVESiLeggonoEUnaChiaveStranaSiIgnora() {
    val t = MagicTouchTuning.parse("fit=stretch,norm=signed;shape=gauss,r=0.04,peak=2,v2=all,boh=1")
    assertThat(t.fit).isEqualTo(MagicTouchTuning.Fit.STRETCH)
    assertThat(t.norm).isEqualTo(MagicTouchTuning.Norm.SIGNED)
    assertThat(t.shape).isEqualTo(MagicTouchTuning.Shape.GAUSS)
    assertThat(t.radius).isEqualTo(0.04f)
    assertThat(t.peak).isEqualTo(2f)
    assertThat(t.v2Prompt).isEqualTo(MagicTouchTuning.V2Prompt.ALL)
    assertThat(MagicTouchTuning.parse("")).isEqualTo(MagicTouchTuning.DEFAULT)
    assertThat(MagicTouchTuning.DEFAULT.scale(255f)).isEqualTo(1f)
  }

  @Test
  fun laMappaDelPuntoHaIlPiccoSulPuntoEZeroLontano() {
    val side = 64
    val into = FloatArray(side * side * 4)
    PointMap.fill(side, 0.5f, 0.25f, MagicTouchTuning.DEFAULT.copy(radius = 0.1f), into, 4, 3)
    assertThat(into[(16 * side + 32) * 4 + 3]).isEqualTo(1f)
    assertThat(into[(50 * side + 5) * 4 + 3]).isEqualTo(0f)
    // Gli altri canali non si toccano.
    assertThat(into[(16 * side + 32) * 4]).isEqualTo(0f)
    val gauss = FloatArray(side * side)
    PointMap.fill(side, 0.5f, 0.5f, MagicTouchTuning.DEFAULT.copy(shape = MagicTouchTuning.Shape.GAUSS, radius = 0.1f), gauss, 1, 0)
    assertThat(gauss[32 * side + 32]).isGreaterThan(0.95f)
    assertThat(gauss[32 * side + 38]).isLessThan(gauss[32 * side + 34])
  }

  @Test
  fun unUscitaFuoriDaZeroAUnoELUnLogit() {
    assertThat(Probabilities.of(floatArrayOf(0f, 0.3f, 1f))).isEqualTo(floatArrayOf(0f, 0.3f, 1f))
    val logits = Probabilities.of(floatArrayOf(-20f, 0f, 20f))
    assertThat(logits[0]).isLessThan(0.001f)
    assertThat(logits[1]).isWithin(1e-4f).of(0.5f)
    assertThat(logits[2]).isGreaterThan(0.999f)
  }

  // --- Il pacchetto .task ---------------------------------------------------------------------------------------------------------

  private fun zip(vararg entries: Pair<String, ByteArray>, stored: Boolean = true): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
      for ((name, data) in entries) {
        val entry = ZipEntry(name)
        if (stored) {
          entry.method = ZipEntry.STORED
          entry.size = data.size.toLong()
          entry.compressedSize = data.size.toLong()
          entry.crc = CRC32().apply { update(data) }.value
        }
        zip.putNextEntry(entry)
        zip.write(data)
        zip.closeEntry()
      }
    }
    return out.toByteArray()
  }

  private fun bytesOf(slice: ByteBuffer): ByteArray = ByteArray(slice.remaining()).also { slice.duplicate().get(it) }

  @Test
  fun ilPacchettoDaIDueModelliCome_fetteSenzaCopiare() {
    val encoder = ByteArray(1000) { (it % 251).toByte() }
    val decoder = ByteArray(333) { (it % 7).toByte() }
    val bundle = zip(
      "interactive_segmentation_decoder.int8.tflite" to decoder,
      "interactive_segmentation_encoder.int8.tflite" to encoder,
      "manifest.pb" to ByteArray(50) { 1 },
      "metadata.pb" to ByteArray(12) { 2 },
    )
    val parts = TaskBundle.parts(ByteBuffer.wrap(bundle))
    assertThat(bytesOf(parts.encoder)).isEqualTo(encoder)
    assertThat(bytesOf(parts.decoder)).isEqualTo(decoder)
    assertThat(TaskBundle.entries(ByteBuffer.wrap(bundle)).keys).containsExactly(
      "interactive_segmentation_decoder.int8.tflite",
      "interactive_segmentation_encoder.int8.tflite",
      "manifest.pb",
      "metadata.pb",
    )
  }

  @Test
  fun unPacchettoCompressoOMalFattoSiRifiuta() {
    val compressed = zip("a_encoder.tflite" to ByteArray(500), "a_decoder.tflite" to ByteArray(500), stored = false)
    assertThrows(IllegalArgumentException::class.java) { TaskBundle.parts(ByteBuffer.wrap(compressed)) }
    val missing = zip("solo.pb" to ByteArray(10))
    assertThrows(IllegalArgumentException::class.java) { TaskBundle.parts(ByteBuffer.wrap(missing)) }
    assertThrows(IllegalArgumentException::class.java) { TaskBundle.entries(ByteBuffer.wrap(ByteArray(100))) }
    val good = zip("a_encoder.tflite" to ByteArray(100), "a_decoder.tflite" to ByteArray(100))
    // Troncato: la fine dell'archivio non c'e'.
    assertThrows(IllegalArgumentException::class.java) { TaskBundle.entries(ByteBuffer.wrap(good.copyOf(good.size - 30))) }
  }

  @Test
  fun unNomeConIlPuntoPuntoNonEsceDalPacchetto() {
    val bad = zip("../evil_encoder.tflite" to ByteArray(10), "a_decoder.tflite" to ByteArray(10))
    assertThrows(IllegalArgumentException::class.java) { TaskBundle.entries(ByteBuffer.wrap(bad)) }
  }

  // --- La taglia della sorgente -------------------------------------------------------------------------------------------------------

  @Test
  fun unaFotoDa200MegapixelSiRiduceA4096ENonSiAlloca() {
    // 16320 x 12240 = 199,8 MP (Samsung 200 MP).
    assertThat(CutoutImages.targetSize(16_320, 12_240)).isEqualTo(4096 to 3072)
    // Una panoramica lunga: il lato lungo e' il tetto.
    assertThat(CutoutImages.targetSize(30_000, 2_000)).isEqualTo(4096 to 273)
    // Una foto piu' piccola resta com'e'.
    assertThat(CutoutImages.targetSize(1600, 1200)).isEqualTo(1600 to 1200)
    // Quadrata da 4096: i pixel (16,8 MP) superano il tetto di 12,6 MP: si riduce ancora.
    val square = CutoutImages.targetSize(4096, 4096)!!
    assertThat(square.first.toLong() * square.second).isAtMost(CutoutImages.MAX_RESULT_PIXELS)
    // Un'intestazione che mente (dimensioni mostruose) non si apre.
    assertThat(CutoutImages.targetSize(60_000, 60_000)).isNull()
    assertThat(CutoutImages.targetSize(70_000, 100)).isNull()
    assertThat(CutoutImages.targetSize(0, 100)).isNull()
  }

  // --- Le fixture e il ritagliatore finto ----------------------------------------------------------------------------------------------

  @Test
  fun leFixtureSonoComeDichiarate() {
    val all = CutoutFixtures.all
    assertThat(all.map { it.name }).containsNoDuplicates()
    assertThat(CutoutFixtures.withSubject.size).isEqualTo(11)
    assertThat(CutoutFixtures.empty.size).isEqualTo(6)
    for (f in CutoutFixtures.withSubject) {
      val truth = f.truth!!
      val coverage = truth.count { it }.toFloat() / truth.size
      assertThat(coverage).isIn(com.google.common.collect.Range.closed(CutoutResult.MIN_COVERAGE, CutoutResult.MAX_COVERAGE))
      assertThat(truth[(f.point.y * (f.height - 1)).toInt() * f.width + (f.point.x * (f.width - 1)).toInt()]).isTrue()
    }
    for (f in CutoutFixtures.empty) assertThat(f.truth).isNull()
    // Le fixture sono scene vere (non un colore solo): ognuna ha molti colori diversi.
    for (f in all) assertThat(f.pixels().toSet().size).isGreaterThan(50)
  }

  @Test
  fun ilRitagliatoreFintoPassaLaStessaSuiteDiContrattoDiMagicTouch() = runTest {
    val fake = FakeSubjectCutter()
    val report = SubjectCutterContract.run(fake)
    assertThat(report.problems()).isEmpty()
    assertThat(report.meanIou).isWithin(1e-4f).of(1f)
    assertThat(report.emptyRejected).isEqualTo(1f)
    assertThat(fake.cuts).isEqualTo(CutoutFixtures.all.size)
  }

  @Test
  fun laSuiteBocciaUnRitagliatoreSbagliato() = runTest {
    // Uno che non trova mai niente: IoU 0 e nessun errore.
    val never = object : dev.pampa.mycloudgallery.core.ai.contract.SubjectCutter {
      override suspend fun cut(image: CutoutImage, point: dev.pampa.mycloudgallery.core.ai.contract.NormalizedPoint) = CutoutResult.NoClearSubject

      override suspend fun release() = Unit
    }
    val report = SubjectCutterContract.run(never)
    assertThat(report.meanIou).isEqualTo(0f)
    assertThat(report.problems()).isNotEmpty()
    // Uno che da' sempre l'immagine intera: i vuoti non si respingono.
    val always = object : dev.pampa.mycloudgallery.core.ai.contract.SubjectCutter {
      override suspend fun cut(image: CutoutImage, point: dev.pampa.mycloudgallery.core.ai.contract.NormalizedPoint): CutoutResult {
        val mask = dev.pampa.mycloudgallery.core.ai.contract.AlphaMask(image.width, image.height, ByteArray(image.width * image.height) { 0xff.toByte() })
        return CutoutResult.Subject(mask, dev.pampa.mycloudgallery.core.ai.contract.PixelBox(0, 0, image.width, image.height))
      }

      override suspend fun release() = Unit
    }
    val tooGreedy = SubjectCutterContract.run(always)
    assertThat(tooGreedy.emptyRejected).isEqualTo(0f)
    assertThat(tooGreedy.problems().any { it.contains("Vuoti respinti") }).isTrue()
    assertThat(CutoutQuality.MIN_IOU_EACH).isEqualTo(0.70f)
  }

  @Test
  fun ilRitagliatoreFintoRiportaIGuastiEIlRilascio() = runTest {
    val fake = FakeSubjectCutter()
    fake.failNext = AiEngineError.OUT_OF_MEMORY
    val fixture = CutoutFixtures.named("casa")
    assertThat(fake.cut(fixture.image(), fixture.point)).isEqualTo(CutoutResult.Failed(AiEngineError.OUT_OF_MEMORY))
    assertThat(fake.cut(fixture.image(), fixture.point)).isInstanceOf(CutoutResult.Subject::class.java)
    fake.release()
    assertThat(fake.releases).isEqualTo(1)
  }
}
