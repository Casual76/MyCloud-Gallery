package dev.pampa.mycloudgallery.core.prepare

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.imaging.decode.ExifOrientation
import dev.pampa.mycloudgallery.core.imaging.decode.ImageFacts
import dev.pampa.mycloudgallery.core.imaging.decode.ImageFormat
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.QuickHashAlgorithm
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.prepare.codec.CodecAvailability
import dev.pampa.mycloudgallery.core.prepare.contract.PrepBudget
import dev.pampa.mycloudgallery.core.prepare.contract.PrepFailCode
import dev.pampa.mycloudgallery.core.prepare.contract.PrepOutput
import dev.pampa.mycloudgallery.core.prepare.contract.PrepResult
import dev.pampa.mycloudgallery.core.prepare.contract.PrepSpec
import dev.pampa.mycloudgallery.core.prepare.contract.PrepareSource
import dev.pampa.mycloudgallery.core.prepare.engine.DefaultPreparer
import dev.pampa.mycloudgallery.core.prepare.engine.PrepGate
import dev.pampa.mycloudgallery.core.prepare.engine.PrepProbe
import dev.pampa.mycloudgallery.core.prepare.photo.PhotoPreparer
import dev.pampa.mycloudgallery.core.prepare.spill.TempSpill
import dev.pampa.mycloudgallery.core.prepare.testing.FakeCodecs
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.fixtures.ExifWriter
import dev.pampa.mycloudgallery.fixtures.ImageFixtures
import dev.pampa.mycloudgallery.fixtures.MotionFixtures
import java.io.File
import java.security.MessageDigest
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * La preparazione delle FOTO su Skia vero (§2.4, P6): uscite giuste per ogni formato che la JVM sa decodificare (dimensioni,
 * orientamento, spazio colore), hash completo uguale a quello calcolato a parte, lettura una volta sola. HEIC e DNG non si
 * decodificano su Robolectric ("unimplemented"): il loro orientamento e' provato con i fatti del formato iniettati su un
 * JPEG, il resto sul telefono (F6).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoPreparerTest {

  @get:Rule
  val tmp = TemporaryFolder()

  private val tempDir: File get() = File(tmp.root, "prep")

  private fun preparer(
    budget: PrepBudget = PrepBudget(memoryClassMb = 256, cores = 8),
    codecs: CodecAvailability = FakeCodecs.everything(),
    probe: PrepProbe = PrepProbe.NONE,
  ) = testPreparer(budget, tempDir, codecs, probe)

  private fun bytesSource(bytes: ByteArray, kind: MediaKind = MediaKind.PHOTO) = PrepareSource.Bytes(CountingBytes(bytes), kind, null)

  private fun PrepResult.done(): PrepResult.Done {
    assertThat(this).isInstanceOf(PrepResult.Done::class.java)
    return this as PrepResult.Done
  }

  private fun PrepResult.Done.image(cls: SegmentClass): PrepOutput.Image =
    outputs.filterIsInstance<PrepOutput.Image>().firstOrNull { it.cls == cls } ?: error("manca $cls")

  private fun assertWebp(bytes: ByteArray) {
    assertThat(String(bytes, 0, 4, Charsets.US_ASCII)).isEqualTo("RIFF")
    assertThat(String(bytes, 8, 4, Charsets.US_ASCII)).isEqualTo("WEBP")
    // WebP con perdita (VP8 / VP8X), come promesso dal formato.
    assertThat(String(bytes, 12, 3, Charsets.US_ASCII)).isEqualTo("VP8")
  }

  private fun assertJpeg(bytes: ByteArray) {
    assertThat(bytes[0]).isEqualTo(0xFF.toByte())
    assertThat(bytes[1]).isEqualTo(0xD8.toByte())
    assertThat(bytes[2]).isEqualTo(0xFF.toByte())
  }

  // --- Uscite e dimensioni ----------------------------------------------------------------------------------------

  @Test
  fun unaFotoLandscapeDaTreMilaPixelEsceInTuttiIFormati() = runTest(timeout = 120.seconds) {
    val original = TestMedia.jpeg(TestMedia.noisy(3000, 2000), quality = 88)
    val source = CountingBytes(original)
    val done = preparer().prepare(PrepareSource.Bytes(source, MediaKind.PHOTO, "image/jpeg"), PrepSpec()).done()

    // D12: S e M sul lato corto (200 e 400), analisi e HQ sul lato lungo (1024 e 2048).
    assertThat(done.image(SegmentClass.THUMB_S).let { it.width to it.height }).isEqualTo(300 to 200)
    assertThat(done.image(SegmentClass.THUMB_M).let { it.width to it.height }).isEqualTo(600 to 400)
    assertThat(done.image(SegmentClass.ANALYSIS).let { it.width to it.height }).isEqualTo(1024 to 683)
    assertThat(done.image(SegmentClass.HQ).let { it.width to it.height }).isEqualTo(2048 to 1365)

    // Formati: WebP con perdita per S, M e HQ; JPEG per l'analisi (q90).
    assertWebp(done.image(SegmentClass.THUMB_S).bytes)
    assertWebp(done.image(SegmentClass.THUMB_M).bytes)
    assertWebp(done.image(SegmentClass.HQ).bytes)
    assertJpeg(done.image(SegmentClass.ANALYSIS).bytes)

    // Le uscite si decodificano davvero e hanno le dimensioni dichiarate; nessuna supera il tetto del blob.
    for (cls in listOf(SegmentClass.THUMB_S, SegmentClass.THUMB_M, SegmentClass.ANALYSIS, SegmentClass.HQ)) {
      val out = done.image(cls)
      val bitmap = TestMedia.decode(out.bytes)
      assertThat(bitmap.width to bitmap.height).isEqualTo(out.width to out.height)
      assertThat(out.bytes.size).isAtMost(TrustLimits.MAX_BLOB_BYTES)
    }
    assertThat(done.facts!!.width to done.facts!!.height).isEqualTo(3000 to 2000)
    assertThat(done.thumbHash).isNotNull()
    assertThat(done.outputs.map { it.cls }).containsExactly(SegmentClass.THUMB_S, SegmentClass.THUMB_M, SegmentClass.ANALYSIS, SegmentClass.HQ).inOrder()
  }

  @Test
  fun lHashCompletoEUgualeAQuelloCalcolatoAParteELOriginaleSiLeggeUnaVoltaSola() = runTest(timeout = 120.seconds) {
    val original = TestMedia.jpeg(TestMedia.noisy(1600, 1200), quality = 90)
    val counting = CountingBytes(original)
    val done = preparer().prepare(PrepareSource.Bytes(counting, MediaKind.PHOTO, null), PrepSpec()).done()

    val independent = MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it) }
    assertThat(done.fullHash).isEqualTo(Sha256(independent))
    assertThat(done.fullHash).isEqualTo(Sha256.of(original))
    assertThat(done.quickHash).isEqualTo(QuickHashAlgorithm.ofContent(original))
    // "Letto una sola volta": ogni byte una volta, nessun altro accesso.
    assertThat(counting.bytesRead).isEqualTo(original.size.toLong())
    val covered = counting.reads.sumOf { it.last - it.first + 1 }
    assertThat(covered).isEqualTo(original.size.toLong())
  }

  @Test
  fun gliOttoOrientamentiExifEscono_Dritti_InTutteLeClassi() = runTest(timeout = 180.seconds) {
    for (orientation in 1..8) {
      val done = preparer().prepare(bytesSource(TestMedia.jpegWithOrientation(orientation, 640, 320)), PrepSpec()).done()
      val swaps = ExifOrientation.fromExif(orientation).swapsSides
      for (cls in listOf(SegmentClass.THUMB_S, SegmentClass.THUMB_M, SegmentClass.ANALYSIS, SegmentClass.HQ)) {
        val out = done.image(cls)
        // 640x320 su un lato: non si ingrandisce mai, quindi M, analisi e HQ restano grandi come l'originale.
        assertThat(out.width > out.height).isEqualTo(!swaps)
        val bitmap = TestMedia.decode(out.bytes)
        assertThat(TestMedia.checkOrientation(bitmap, orientation)).isNull()
      }
      val facts = done.facts!!
      assertThat(facts.width to facts.height).isEqualTo(if (swaps) 320 to 640 else 640 to 320)
    }
  }

  @Test
  fun unaFotoPiccolaNonSiIngrandisceInNessunaClasse() = runTest {
    val png = TestMedia.png(TestMedia.quadrants(64, 48))
    val done = preparer().prepare(bytesSource(png), PrepSpec()).done()
    for (cls in listOf(SegmentClass.THUMB_S, SegmentClass.THUMB_M, SegmentClass.ANALYSIS, SegmentClass.HQ)) {
      assertThat(done.image(cls).let { it.width to it.height }).isEqualTo(64 to 48)
    }
    assertThat(TestMedia.checkOrientation(TestMedia.decode(done.image(SegmentClass.HQ).bytes), 1)).isNull()
  }

  @Test
  fun unPanoramaNonDiventaUnNastroDiMigliaiaDiPixel() = runTest {
    val png = TestMedia.png(TestMedia.quadrants(8000, 400))
    val done = preparer().prepare(bytesSource(png), PrepSpec()).done()
    // Lato corto 200 vorrebbe 4000 px di lungo: il tetto e' quello dell'HQ.
    assertThat(done.image(SegmentClass.THUMB_S).width).isEqualTo(2048)
    assertThat(done.image(SegmentClass.THUMB_S).height).isEqualTo(102)
    assertThat(done.image(SegmentClass.HQ).width).isEqualTo(2048)
    assertThat(done.image(SegmentClass.ANALYSIS).width).isEqualTo(1024)
  }

  @Test
  fun siProduconoSoloLeClassiCheMancano() = runTest {
    val jpeg = TestMedia.jpeg(TestMedia.quadrants(1200, 800))
    val done = preparer().prepare(bytesSource(jpeg), PrepSpec(classes = setOf(SegmentClass.THUMB_S, SegmentClass.THUMB_M))).done()
    assertThat(done.outputs.map { it.cls }).containsExactly(SegmentClass.THUMB_S, SegmentClass.THUMB_M).inOrder()
    assertThat(done.fullHash).isEqualTo(Sha256.of(jpeg))
    assertThat(done.thumbHash).isNotNull()
    // Una foto non ha fotogrammi: chiederli non produce niente e non fa errore.
    val onlyFrames = preparer().prepare(bytesSource(jpeg), PrepSpec(classes = setOf(SegmentClass.FRAMES))).done()
    assertThat(onlyFrames.outputs).isEmpty()
    assertThat(onlyFrames.fullHash).isEqualTo(Sha256.of(jpeg))
  }

  // --- Formati ----------------------------------------------------------------------------------------------------

  @Test
  fun pngWebpGifBmpEWebpAnimatoSiPreparano() = runTest {
    val png = TestMedia.png(TestMedia.quadrants(200, 100))
    val webp = TestMedia.webp(TestMedia.quadrants(200, 100))
    val gif = ImageFixtures.gif(64, 48, 0xC04080)
    val bmp = ImageFixtures.bmp(64, 48, 0x8040C0)
    val animated = ImageFixtures.webpAnimated(64, 48, intArrayOf(0xFFE04040.toInt(), 0xFF4040E0.toInt()), 200)
    for ((name, bytes, kind) in listOf(
      Triple("png", png, MediaKind.PHOTO),
      Triple("webp", webp, MediaKind.PHOTO),
      Triple("gif", gif, MediaKind.ANIMATED_IMAGE),
      Triple("bmp", bmp, MediaKind.PHOTO),
      Triple("webp animato", animated, MediaKind.ANIMATED_IMAGE),
    )) {
      val done = preparer().prepare(bytesSource(bytes, kind), PrepSpec()).done()
      assertWithName(name, done)
    }
    // Le immagini animate escono dal primo fotogramma (E-M11): il WebP animato comincia rosso.
    val first = preparer().prepare(bytesSource(animated, MediaKind.ANIMATED_IMAGE), PrepSpec()).done()
    val pixel = TestMedia.decode(first.image(SegmentClass.HQ).bytes).getPixel(10, 10)
    assertThat(Color.red(pixel)).isGreaterThan(Color.blue(pixel))
  }

  private fun assertWithName(name: String, done: PrepResult.Done) {
    assertWithMessage(name).that(done.outputs.map { it.cls }).containsExactly(SegmentClass.THUMB_S, SegmentClass.THUMB_M, SegmentClass.ANALYSIS, SegmentClass.HQ)
    assertWithMessage(name).that(done.fullHash).isNotNull()
    assertWithMessage(name).that(done.thumbHash).isNotNull()
  }

  @Test
  fun unaMotionPhotoSiPreparaComeFotoFermaEL_HashCopreIlFileIntero() = runTest {
    val still = ImageFixtures.solid(64, 48, 0xB07040)
    val exif = ExifWriter.Spec().apply {
      dateOriginal = "2024:03:12 10:15:30"
      offsetOriginal = "+01:00"
    }
    val file = MotionFixtures.googleV1(still, exif, MotionFixtures.video(3000))
    val done = preparer().prepare(bytesSource(file, MediaKind.MOTION_PHOTO), PrepSpec()).done()
    assertThat(done.image(SegmentClass.HQ).let { it.width to it.height }).isEqualTo(64 to 48)
    // L'hash e' del file intero, parte video compresa: e' cio' che il NAS contiene.
    assertThat(done.fullHash).isEqualTo(Sha256.of(file))
    assertThat(done.facts!!.durationMs).isNull()
  }

  @Test
  fun laTrasparenzaSiComponeSulBiancoSoloNelJpegDiAnalisi() = runTest {
    val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(Color.TRANSPARENT)
    for (y in 0 until 100) for (x in 100 until 200) bitmap.setPixel(x, y, Color.BLACK)
    val done = preparer().prepare(bytesSource(TestMedia.png(bitmap)), PrepSpec()).done()
    val analysis = TestMedia.decode(done.image(SegmentClass.ANALYSIS).bytes)
    // A sinistra era trasparente: nel JPEG e' bianco, mai nero (l'OCR leggerebbe testo scuro su fondo scuro).
    assertThat(Color.red(analysis.getPixel(20, 50))).isAtLeast(240)
    assertThat(Color.red(analysis.getPixel(180, 50))).isAtMost(20)
    // Il WebP conserva la trasparenza.
    val thumb = TestMedia.decode(done.image(SegmentClass.THUMB_S).bytes)
    assertThat(Color.alpha(thumb.getPixel(20, 50))).isAtMost(10)
  }

  // --- Spazio colore ------------------------------------------------------------------------------------------------

  @Test
  fun loSpazioColoreDisplayP3SiConservaInTutteLeUscite() = runTest {
    val p3 = ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
    for ((label, bytes) in listOf(
      "png" to TestMedia.png(TestMedia.quadrants(400, 300, p3)),
      "jpeg" to TestMedia.jpeg(TestMedia.quadrants(400, 300, p3)),
    )) {
      val done = preparer().prepare(bytesSource(bytes), PrepSpec()).done()
      for (cls in listOf(SegmentClass.THUMB_S, SegmentClass.THUMB_M, SegmentClass.ANALYSIS, SegmentClass.HQ)) {
        assertWithMessage("$label $cls").that(TestMedia.decode(done.image(cls).bytes).colorSpace).isEqualTo(p3)
      }
    }
    // Una sorgente sRGB resta sRGB.
    val srgb = preparer().prepare(bytesSource(TestMedia.jpeg(TestMedia.quadrants(400, 300))), PrepSpec()).done()
    assertThat(TestMedia.decode(srgb.image(SegmentClass.HQ).bytes).colorSpace).isEqualTo(ColorSpace.get(ColorSpace.Named.SRGB))
  }

  // --- HEIC e DNG: l'orientamento lo applichiamo noi (E-V3, E-M15) ------------------------------------------------------

  /** Il preparatore con i fatti del formato imposti: un JPEG senza EXIF fa da vettore, come in core-imaging. */
  private fun withFacts(format: ImageFormat, manual: ExifOrientation, width: Int, height: Int): PhotoPreparer {
    val budget = PrepBudget(256, 8)
    return PhotoPreparer(
      budget, PrepGate(budget), FakeCodecs.everything(), "c2_test", TempSpill(tempDir), Dispatchers.Default, PrepProbe.NONE,
      readFacts = { ImageFacts(format, width, height, manual, animated = null, sizeBytes = null) },
    )
  }

  @Test
  fun unDngEUnHeicSenzaIrotSiRuotanoAMano_UnaVoltaSola() = runTest(timeout = 120.seconds) {
    val vector = TestMedia.jpeg(TestMedia.quadrants(640, 320))
    for ((format, label) in listOf(ImageFormat.TIFF_RAW to "dng", ImageFormat.HEIF to "heic senza irot")) {
      for (orientation in 1..8) {
        val manual = ExifOrientation.fromExif(orientation)
        val done = withFacts(format, manual, 640, 320).prepare(bytesSource(vector), PrepSpec()).done()
        for (cls in listOf(SegmentClass.THUMB_S, SegmentClass.HQ)) {
          val bitmap = TestMedia.decode(done.image(cls).bytes)
          assertWithMessage("$label orientamento $orientation $cls").that(TestMedia.checkOrientation(bitmap, orientation)).isNull()
        }
        val swaps = manual.swapsSides
        assertThat(done.facts!!.width to done.facts!!.height).isEqualTo(if (swaps) 320 to 640 else 640 to 320)
      }
    }
  }

  @Test
  fun unHeicConIrotLoRuotaGiaIlSistema_Qui_NienteDiPiu() = runTest {
    // Con irot nel contenitore `manual` e' NORMAL: il decodificatore ha gia' ruotato, e non si ruota due volte.
    val vector = TestMedia.jpeg(TestMedia.quadrants(640, 320))
    val done = withFacts(ImageFormat.HEIF, ExifOrientation.NORMAL, 640, 320).prepare(bytesSource(vector), PrepSpec()).done()
    assertThat(TestMedia.checkOrientation(TestMedia.decode(done.image(SegmentClass.HQ).bytes), 1)).isNull()
    assertThat(done.facts!!.width to done.facts!!.height).isEqualTo(640 to 320)
  }

  // --- Sorgenti e temporanei -------------------------------------------------------------------------------------------

  @Test
  fun unFileLocaleDaLeStesseUsciteELoStessoHash() = runTest(timeout = 120.seconds) {
    val original = TestMedia.jpeg(TestMedia.noisy(1200, 900), quality = 90)
    val file = File(tmp.root, "locale.jpg").also { it.writeBytes(original) }
    val local = preparer().prepare(PrepareSource.Local(file, MediaKind.PHOTO, null), PrepSpec()).done()
    val memory = preparer().prepare(bytesSource(original), PrepSpec()).done()
    assertThat(local.fullHash).isEqualTo(Sha256.of(original))
    assertThat(local.quickHash).isEqualTo(QuickHashAlgorithm.ofContent(original))
    assertThat(local.outputs.map { (it as PrepOutput.Image).let { i -> Triple(i.cls, i.width, i.height) } })
      .isEqualTo(memory.outputs.map { (it as PrepOutput.Image).let { i -> Triple(i.cls, i.width, i.height) } })
    // Il file di chi chiama non si tocca.
    assertThat(file.exists()).isTrue()
  }

  @Test
  fun unaFotoOltreIlTettoInMemoriaPassaDaUnTemporaneoCheSparisce() = runTest(timeout = 120.seconds) {
    val original = TestMedia.jpeg(TestMedia.noisy(1500, 1000), quality = 90)
    // Tetto in memoria ridotto: il file "grande" e' questo.
    val budget = PrepBudget(256, 8, inMemoryMaxBytes = 100_000, maxTempBytes = 50_000_000)
    assertThat(original.size).isGreaterThan(100_000)
    val counting = CountingBytes(original)
    val done = preparer(budget).prepare(PrepareSource.Bytes(counting, MediaKind.PHOTO, null), PrepSpec()).done()
    assertThat(done.fullHash).isEqualTo(Sha256.of(original))
    assertThat(done.quickHash).isEqualTo(QuickHashAlgorithm.ofContent(original))
    assertThat(done.image(SegmentClass.HQ).width).isEqualTo(1500)
    assertThat(counting.bytesRead).isEqualTo(original.size.toLong())
    // Il temporaneo non resta (nome da LocalNames, in cacheDir/prep/).
    assertThat(tempDir.listFiles().orEmpty().toList()).isEmpty()
  }

  @Test
  fun oltreIlTettoDeiTemporaneiELaFotoESegnaposto_TooLarge() = runTest {
    val original = TestMedia.jpeg(TestMedia.noisy(800, 600))
    val budget = PrepBudget(256, 8, inMemoryMaxBytes = 10_000, maxTempBytes = 20_000)
    val result = preparer(budget).prepare(bytesSource(original), PrepSpec())
    assertThat(result).isEqualTo(PrepResult.Failed(PrepFailCode.TOO_LARGE, retryable = false))
    assertThat(tempDir.listFiles().orEmpty().toList()).isEmpty()
  }

  @Test
  fun nessunaClasseDiTemporaneiSenzaBisogno() = runTest {
    val done = preparer().prepare(bytesSource(TestMedia.jpeg(TestMedia.quadrants(200, 100))), PrepSpec()).done()
    assertThat(done.outputs).isNotEmpty()
    assertThat(tempDir.exists() && tempDir.listFiles().orEmpty().isNotEmpty()).isFalse()
  }
}
