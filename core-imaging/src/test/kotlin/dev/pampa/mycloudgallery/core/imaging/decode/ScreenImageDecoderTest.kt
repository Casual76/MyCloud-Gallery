package dev.pampa.mycloudgallery.core.imaging.decode

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Gainmap
import android.os.CancellationSignal
import android.os.OperationCanceledException
import androidx.compose.ui.unit.IntSize
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.imaging.TestImages
import dev.pampa.mycloudgallery.core.imaging.budget.ImagingBudget
import dev.pampa.mycloudgallery.core.imaging.contract.ImageFailure
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * La decodifica per lo schermo con Skia vero (`GraphicsMode.NATIVE`): gli otto orientamenti sempre dritti (E-V3,
 * E-M15), lo spazio colore, la gain map, la taglia, i file corrotti (E-M10). Le immagini sono costruite dai test.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenImageDecoderTest {

  private val budget = ImagingBudget.forMemoryClass(256)
  private val screen = IntSize(1080, 2400)

  private fun decode(bytes: ByteArray, size: IntSize = screen): Pair<ImageFacts, DecodedImage> {
    val image = ByteArrayImageBytes(bytes)
    val facts = ImageProbe.probe(image)
    return facts to ScreenImageDecoder.decode(image, facts, size, budget, CancellationSignal())
  }

  // --- Otto orientamenti ---------------------------------------------------------------------------------

  @Test
  fun gliOttoOrientamentiExifSonoSempreDritti() {
    for (orientation in 1..8) {
      val (facts, decoded) = decode(TestImages.jpegWithOrientation(orientation))
      // JPEG: lo applica ImageDecoder, quindi niente da fare a mano e dimensioni gia' dritte.
      assertThat(facts.manual).isEqualTo(ExifOrientation.NORMAL)
      val expectedSwap = ExifOrientation.fromExif(orientation).swapsSides
      assertThat(decoded.bitmap.width).isEqualTo(if (expectedSwap) 16 else 32)
      assertThat(decoded.bitmap.height).isEqualTo(if (expectedSwap) 32 else 16)
      assertThat(facts.uprightWidth).isEqualTo(decoded.bitmap.width)
      assertThat(facts.uprightHeight).isEqualTo(decoded.bitmap.height)
      assertThat(TestImages.checkOrientation(decoded.bitmap, orientation)).isNull()
    }
  }

  @Test
  fun laTrasformazioneAMano_RawEHeicSenzaIrot_DaLoStessoRisultatoPerTuttiGliOtto() {
    // Per DNG e HEIC senza irot l'orientamento lo applichiamo noi: si prova con un JPEG senza EXIF come "vettore".
    for (orientation in 1..8) {
      val source = TestImages.jpeg(TestImages.quadrants())
      val image = ByteArrayImageBytes(source)
      val probed = ImageProbe.probe(image)
      val facts = ImageFacts(
        format = ImageFormat.TIFF_RAW,
        decoderWidth = probed.decoderWidth,
        decoderHeight = probed.decoderHeight,
        manual = ExifOrientation.fromExif(orientation),
        animated = null,
        sizeBytes = null,
      )
      val decoded = ScreenImageDecoder.decode(image, facts, screen, budget, CancellationSignal())
      assertThat(TestImages.checkOrientation(decoded.bitmap, orientation)).isNull()
      assertThat(decoded.bitmap.width).isEqualTo(facts.uprightWidth)
      assertThat(decoded.bitmap.height).isEqualTo(facts.uprightHeight)
    }
  }

  @Test
  fun lAreaDiMatriceDelleBitmapEquivaleAllOracoloSuiPixel() {
    // La rotazione di Bitmap (Matrix, Skia vero) e la definizione pixel per pixel sono la stessa cosa, per tutti e otto.
    val source = TestImages.quadrants(6, 4)
    for (orientation in ExifOrientation.entries) {
      val pixels = IntArray(6 * 4)
      source.getPixels(pixels, 0, 6, 0, 0, 6, 4)
      // Un pixel diverso per ogni posizione, per riconoscere ogni spostamento.
      val unique = IntArray(pixels.size) { 0xFF000000.toInt() or (it * 10) }
      source.setPixels(unique, 0, 6, 0, 0, 6, 4)
      val expected = orientation.transformPixels(unique, 6, 4)
      val actual = orientation.applyTo(source)
      val (w, h) = orientation.uprightSize(6, 4)
      assertThat(actual.width).isEqualTo(w)
      assertThat(actual.height).isEqualTo(h)
      val actualPixels = IntArray(w * h)
      actual.getPixels(actualPixels, 0, w, 0, 0, w, h)
      assertThat(actualPixels).isEqualTo(expected)
    }
  }

  @Test
  fun laTabellaCanonicaEIlCodiceConcordanoSuQuattroQuadranti() {
    // Controlla l'oracolo contro la tabella scritta a mano in TestImages (indipendente dal codice sotto prova).
    for (orientation in ExifOrientation.entries) {
      val rotated = orientation.applyTo(TestImages.quadrants())
      assertThat(TestImages.checkOrientation(rotated, orientation.exif)).isNull()
    }
  }

  @Test
  fun unValoreExifFuoriScalaVale_NessunaRotazione() {
    assertThat(ExifOrientation.fromExif(0)).isEqualTo(ExifOrientation.NORMAL)
    assertThat(ExifOrientation.fromExif(9)).isEqualTo(ExifOrientation.NORMAL)
    assertThat(ExifOrientation.fromExif(-1)).isEqualTo(ExifOrientation.NORMAL)
    for (i in 1..8) assertThat(ExifOrientation.fromExif(i).exif).isEqualTo(i)
  }

  // --- Taglia ------------------------------------------------------------------------------------------

  @Test
  fun unaFotoGrandeSiDecodificaAllaTagliaDelloSchermoSenzaRitaglio() {
    val big = TestImages.jpeg(TestImages.quadrants(2000, 1500))
    val (_, decoded) = decode(big, IntSize(400, 800))
    assertThat(decoded.bitmap.width).isEqualTo(400)
    assertThat(decoded.bitmap.height).isEqualTo(300)
    assertThat(decoded.bitmap.config).isNotEqualTo(Bitmap.Config.HARDWARE)
  }

  @Test
  fun unaFotoPiccolaNonSiIngrandisce() {
    val (_, decoded) = decode(TestImages.jpeg(TestImages.quadrants(64, 32)), IntSize(1080, 2400))
    assertThat(decoded.bitmap.width).isEqualTo(64)
    assertThat(decoded.bitmap.height).isEqualTo(32)
  }

  @Test
  fun lAnteprimaELaVersioneNitidaHannoLaStessaProporzioneConOgniOrientamento() {
    // E-V1: la sostituzione non sposta niente se le due geometrie hanno la stessa proporzione.
    for (orientation in 1..8) {
      val (_, decoded) = decode(TestImages.jpegWithOrientation(orientation, 320, 200), IntSize(160, 160))
      val ratio = decoded.bitmap.width.toFloat() / decoded.bitmap.height
      val expected = if (ExifOrientation.fromExif(orientation).swapsSides) 200f / 320f else 320f / 200f
      assertThat(ratio).isWithin(0.03f).of(expected)
    }
  }

  // --- Spazio colore e gain map ---------------------------------------------------------------------------

  @Test
  fun loSpazioColoreDisplayP3SiConserva() {
    val p3 = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888, true, ColorSpace.get(ColorSpace.Named.DISPLAY_P3))
    p3.eraseColor(android.graphics.Color.GREEN)
    val (_, decoded) = decode(TestImages.png(p3))
    assertThat(decoded.colorSpace).isEqualTo(ColorSpace.get(ColorSpace.Named.DISPLAY_P3))
    assertThat(decoded.hdr).isFalse()
    // Un sRGB resta sRGB.
    val (_, srgb) = decode(TestImages.jpeg(TestImages.quadrants()))
    assertThat(srgb.colorSpace).isEqualTo(ColorSpace.get(ColorSpace.Named.SRGB))
  }

  @Test
  fun laGainMapSiRiconoscePerLHdr() {
    val plain = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
    assertThat(ScreenImageDecoder.hasActiveGainmap(plain)).isFalse()
    val withMap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
    withMap.gainmap = Gainmap(Bitmap.createBitmap(2, 2, Bitmap.Config.ALPHA_8))
    assertThat(ScreenImageDecoder.hasActiveGainmap(withMap)).isTrue()
    // Sotto l'API 34 (e sulle bitmap senza gain map) l'HDR e' sempre falso: lo coprono i test sul dispositivo (T-30).
  }

  // --- Testa del file, file rotti --------------------------------------------------------------------------

  @Test
  fun laTestaDiUnJpegDaFormatoDimensioniEOrientamento() {
    val facts = ImageProbe.probe(ByteArrayImageBytes(TestImages.jpegWithOrientation(6, 64, 32)))
    assertThat(facts.format).isEqualTo(ImageFormat.JPEG)
    // ImageDecoder gia' ruota: dimensioni del decodificatore = dritte.
    assertThat(facts.decoderWidth).isEqualTo(32)
    assertThat(facts.decoderHeight).isEqualTo(64)
    assertThat(facts.uprightWidth).isEqualTo(32)
    assertThat(facts.animated).isNull()
  }

  @Test
  fun unFileVuotoOMalformatoDaUnErroreStabileSenzaEccezioniStrane() {
    fun failureOf(bytes: ByteArray): ImageFailure =
      assertThrows(ImageFailureException::class.java) { ImageProbe.probe(ByteArrayImageBytes(bytes)) }.code

    // Zero byte (E-M12): non e' un'immagine che si possa leggere.
    assertThat(failureOf(ByteArray(0))).isEqualTo(ImageFailure.DECODE_FAILED)
    // Formato che non si riconosce.
    assertThat(failureOf("non sono un'immagine".toByteArray())).isEqualTo(ImageFailure.UNSUPPORTED_FORMAT)
    // Un JPEG con la testa buona ma troncato a 10 byte.
    assertThat(failureOf(TestImages.jpeg(TestImages.quadrants()).copyOf(10))).isEqualTo(ImageFailure.DECODE_FAILED)
  }

  @Test
  fun unJpegTroncatoNonRestituisceMaiUnaImmagineAMeta() {
    val whole = TestImages.jpeg(TestImages.quadrants(256, 256), quality = 100)
    val truncated = ByteArrayImageBytes(whole.copyOf(whole.size / 3))
    // Fallisce nella testa o nella decodifica (dipende da dove cade il taglio), ma sempre con un errore leggibile e mai
    // con una bitmap a meta': il viewer lo mostra come Failed(DECODE_FAILED).
    val error = runCatching {
      val facts = ImageProbe.probe(truncated)
      ScreenImageDecoder.decode(truncated, facts, screen, budget, CancellationSignal())
    }.exceptionOrNull()
    assertThat(error).isNotNull()
    assertThat(error).isNotInstanceOf(OutOfMemoryError::class.java)
  }

  @Test
  fun unSegnaleGiaScattatoFermaLaDecodificaPrimaDiSpendereMemoria() {
    val image = ByteArrayImageBytes(TestImages.jpeg(TestImages.quadrants(512, 512)))
    val facts = ImageProbe.probe(image)
    val cancelled = CancellationSignal().also { it.cancel() }
    assertThrows(OperationCanceledException::class.java) {
      ScreenImageDecoder.decode(image, facts, screen, budget, cancelled)
    }
  }

  // --- Tasselli ------------------------------------------------------------------------------------------

  private fun facts(format: ImageFormat, animated: Boolean = false) = ImageFacts(
    format = format,
    decoderWidth = 100,
    decoderHeight = 100,
    manual = ExifOrientation.NORMAL,
    animated = if (animated) dev.pampa.mycloudgallery.core.imaging.contract.AnimatedSpec(
      dev.pampa.mycloudgallery.core.imaging.contract.AnimatedKind.GIF, 100, 100,
    ) else null,
    sizeBytes = null,
  )

  @Test
  fun iTasselliSiUsanoDoveIlSistemaLiRegge() {
    var probes = 0
    val policy = TilePolicy { probes++; true }
    val none = { null as ImageBytes? }
    assertThat(policy.supportsTiles(facts(ImageFormat.JPEG), none)).isTrue()
    assertThat(policy.supportsTiles(facts(ImageFormat.PNG), none)).isTrue()
    assertThat(policy.supportsTiles(facts(ImageFormat.WEBP), none)).isTrue()
    // I RAW mai (decodifica campionata), gli animati nemmeno.
    assertThat(policy.supportsTiles(facts(ImageFormat.TIFF_RAW), none)).isFalse()
    assertThat(policy.supportsTiles(facts(ImageFormat.GIF, animated = true), none)).isFalse()
    assertThat(policy.supportsTiles(facts(ImageFormat.BMP), none)).isFalse()
    assertThat(probes).isEqualTo(0)
    // HEIC e AVIF: si prova davvero un decodificatore di regioni, e senza byte non si puo'.
    assertThat(policy.supportsTiles(facts(ImageFormat.HEIF)) { ByteArrayImageBytes(ByteArray(4)) }).isTrue()
    assertThat(policy.supportsTiles(facts(ImageFormat.AVIF)) { ByteArrayImageBytes(ByteArray(4)) }).isTrue()
    assertThat(probes).isEqualTo(2)
    assertThat(policy.supportsTiles(facts(ImageFormat.HEIF), none)).isFalse()
    // Se il sistema non regge le regioni su HEIC, lo zoom resta sulla decodifica campionata.
    val strict = TilePolicy { false }
    assertThat(strict.supportsTiles(facts(ImageFormat.HEIF)) { ByteArrayImageBytes(ByteArray(4)) }).isFalse()
  }
}
