package dev.pampa.mycloudgallery.core.imaging.decode

import androidx.compose.ui.unit.IntSize
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.imaging.TestImages
import dev.pampa.mycloudgallery.core.imaging.budget.ImagingBudget
import dev.pampa.mycloudgallery.core.imaging.contract.AnimatedKind
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Le parti pure della decodifica: riconoscimento del formato dai primi byte, animazione, `irot` e il piano di
 * decodifica (taglia, tetti, orientamento). Girano su Robolectric solo perche' le fixture usano Bitmap.
 */
@RunWith(RobolectricTestRunner::class)
class ImageSnifferAndPlannerTest {

  private val budget = ImagingBudget.forMemoryClass(256)

  // --- Formato ------------------------------------------------------------------------------------------

  @Test
  fun riconoscePerIPrimiByteIlFormatoVero() {
    assertThat(ImageSniffer.sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()))).isEqualTo(ImageFormat.JPEG)
    assertThat(ImageSniffer.sniff(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))).isEqualTo(ImageFormat.PNG)
    assertThat(ImageSniffer.sniff("GIF89a....".toByteArray())).isEqualTo(ImageFormat.GIF)
    assertThat(ImageSniffer.sniff("GIF87a....".toByteArray())).isEqualTo(ImageFormat.GIF)
    assertThat(ImageSniffer.sniff("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1))).isEqualTo(ImageFormat.WEBP)
    assertThat(ImageSniffer.sniff(TestImages.heicHeader(irot = false))).isEqualTo(ImageFormat.HEIF)
    assertThat(ImageSniffer.sniff(byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 0x2A, 0x00, 8, 0, 0, 0))).isEqualTo(ImageFormat.TIFF_RAW)
    assertThat(ImageSniffer.sniff(byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 0x00, 0x2A, 0, 0, 0, 8))).isEqualTo(ImageFormat.TIFF_RAW)
    assertThat(ImageSniffer.sniff("BM......".toByteArray())).isEqualTo(ImageFormat.BMP)
    // Il nome non conta: un testo con estensione .jpg resta sconosciuto.
    assertThat(ImageSniffer.sniff("<html>non e' un'immagine".toByteArray())).isEqualTo(ImageFormat.UNKNOWN)
    assertThat(ImageSniffer.sniff(ByteArray(0))).isEqualTo(ImageFormat.UNKNOWN)
    assertThat(ImageSniffer.sniff(ByteArray(3))).isEqualTo(ImageFormat.UNKNOWN)
  }

  @Test
  fun distingueAvifDaHeifDaiMarchi() {
    fun ftyp(major: String, compatible: List<String>): ByteArray {
      val size = 16 + compatible.size * 4
      return byteArrayOf(0, 0, 0, size.toByte()) + "ftyp".toByteArray() + major.toByteArray() + byteArrayOf(0, 0, 0, 0) +
        compatible.joinToString("").toByteArray()
    }
    assertThat(ImageSniffer.sniff(ftyp("avif", listOf("mif1", "avif")))).isEqualTo(ImageFormat.AVIF)
    assertThat(ImageSniffer.sniff(ftyp("heic", listOf("mif1", "heic")))).isEqualTo(ImageFormat.HEIF)
    assertThat(ImageSniffer.sniff(ftyp("mif1", listOf("heic")))).isEqualTo(ImageFormat.HEIF)
    // Un video MP4 non e' un'immagine.
    assertThat(ImageSniffer.sniff(ftyp("isom", listOf("iso2", "mp41")))).isEqualTo(ImageFormat.UNKNOWN)
  }

  @Test
  fun unHeicConIrotLoRuotaGiaIlSistemaUnoSenzaNo() {
    assertThat(ImageSniffer.hasContainerTransform(TestImages.heicHeader(irot = true))).isTrue()
    assertThat(ImageSniffer.hasContainerTransform(TestImages.heicHeader(irot = false))).isFalse()
    assertThat(ImageSniffer.hasContainerTransform("..imir..".toByteArray())).isTrue()
    assertThat(ImageSniffer.hasContainerTransform(ByteArray(0))).isFalse()
  }

  // --- Chi applica l'orientamento --------------------------------------------------------------------------

  @Test
  fun laPoliticaDiOrientamentoPerFormato() {
    val rotated = ExifOrientation.ROTATE_90
    // JPEG, PNG, WebP, AVIF, GIF, BMP: lo applica ImageDecoder, non si tocca.
    for (format in listOf(ImageFormat.JPEG, ImageFormat.PNG, ImageFormat.WEBP, ImageFormat.AVIF, ImageFormat.GIF, ImageFormat.BMP)) {
      assertThat(OrientationPolicy.manual(format, rotated, hasContainerTransform = false)).isEqualTo(ExifOrientation.NORMAL)
      assertThat(OrientationPolicy.needsExif(format, false)).isFalse()
    }
    // DNG e RAW: lo applichiamo noi.
    assertThat(OrientationPolicy.manual(ImageFormat.TIFF_RAW, rotated, false)).isEqualTo(rotated)
    assertThat(OrientationPolicy.manual(ImageFormat.TIFF_RAW, rotated, true)).isEqualTo(rotated)
    // HEIC: noi solo se il contenitore non dichiara irot/imir (altrimenti due rotazioni).
    assertThat(OrientationPolicy.manual(ImageFormat.HEIF, rotated, hasContainerTransform = false)).isEqualTo(rotated)
    assertThat(OrientationPolicy.manual(ImageFormat.HEIF, rotated, hasContainerTransform = true)).isEqualTo(ExifOrientation.NORMAL)
  }

  // --- Animati --------------------------------------------------------------------------------------------

  @Test
  fun riconoscePerLaTestaGifEWebpAnimati() {
    val gif = TestImages.animatedGif()
    val spec = ImageSniffer.animatedSpec(gif)!!
    assertThat(spec.kind).isEqualTo(AnimatedKind.GIF)
    assertThat(spec.width).isEqualTo(4)
    assertThat(spec.height).isEqualTo(2)

    val webp = ImageSniffer.animatedSpec(TestImages.animatedWebpHeader(640, 480))!!
    assertThat(webp.kind).isEqualTo(AnimatedKind.WEBP)
    assertThat(webp.width).isEqualTo(640)
    assertThat(webp.height).isEqualTo(480)
  }

  @Test
  fun unGifSenzaRipetizioneMaConDueFotogrammiSiAnimaUnoSoloNo() {
    val two = TestImages.gif(4, 2, listOf(IntArray(8), IntArray(8) { 1 }), loop = false)
    assertThat(ImageSniffer.animatedSpec(two)?.kind).isEqualTo(AnimatedKind.GIF)
    val one = TestImages.gif(4, 2, listOf(IntArray(8)), loop = false)
    assertThat(ImageSniffer.animatedSpec(one)).isNull()
  }

  @Test
  fun unWebpFermoOUnJpegNonSonoAnimati() {
    val still = TestImages.animatedWebpHeader(10, 10).also { it[20] = 0x10 } // solo alpha, niente animazione
    assertThat(ImageSniffer.animatedSpec(still)).isNull()
    assertThat(ImageSniffer.animatedSpec(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0))).isNull()
    assertThat(ImageSniffer.animatedSpec(ByteArray(0))).isNull()
    // Una testa troncata non solleva: semplicemente non lo riconosce.
    assertThat(ImageSniffer.animatedSpec(TestImages.animatedGif().copyOf(8))).isNull()
  }

  // --- Piano di decodifica ---------------------------------------------------------------------------------

  private fun plan(w: Int, h: Int, screen: IntSize, manual: ExifOrientation = ExifOrientation.NORMAL, format: ImageFormat = ImageFormat.JPEG) =
    DecodePlanner.plan(w, h, manual, format, screen, budget)

  @Test
  fun unaFotoPiuGrandeDelloSchermoSiContieneSenzaRitaglio() {
    val landscape = plan(4000, 3000, IntSize(1080, 2400))
    assertThat(landscape.resample).isTrue()
    assertThat(landscape.uprightWidth).isEqualTo(1080)
    assertThat(landscape.uprightHeight).isEqualTo(810)
    assertThat(landscape.decoderWidth).isEqualTo(1080)

    val portrait = plan(3000, 4000, IntSize(1080, 2400))
    // 1080/3000 = 0.36, 2400/4000 = 0.6: vince la larghezza.
    assertThat(portrait.uprightWidth).isEqualTo(1080)
    assertThat(portrait.uprightHeight).isEqualTo(1440)
  }

  @Test
  fun nonIngrandisceMaiOltreLOriginale() {
    val small = plan(100, 50, IntSize(1080, 2400))
    assertThat(small.resample).isFalse()
    assertThat(small.uprightWidth).isEqualTo(100)
    assertThat(small.decoderWidth).isEqualTo(100)
  }

  @Test
  fun unaFotoDa200MegapixelNonDiventaMaiUnaBitmapDaUnGiga() {
    // 200 MP: 16000 x 12500. Alla taglia dello schermo sono ~1 MP.
    val huge = plan(16_000, 12_500, IntSize(1080, 2400))
    assertThat(huge.resample).isTrue()
    val bytes = huge.decoderWidth.toLong() * huge.decoderHeight * 4
    assertThat(bytes).isAtMost(10L * 1024 * 1024)
    // Anche per lo zoom campionato (schermo enorme) il tetto da memoryClass vale: mai piu' del budget del viewer.
    val zoomed = plan(16_000, 12_500, IntSize(16_000, 16_000))
    assertThat(zoomed.decoderWidth.toLong() * zoomed.decoderHeight * 4).isAtMost(budget.viewerBitmapBytes)
    assertThat(zoomed.decoderWidth.toLong() * zoomed.decoderHeight).isGreaterThan(1_000_000)
  }

  @Test
  fun unRawNonSuperaQuattromilaNovantaseiPixelSulLatoLungo() {
    val big = ImagingBudget.forMemoryClass(512)
    val dng = DecodePlanner.plan(8000, 6000, ExifOrientation.NORMAL, ImageFormat.TIFF_RAW, IntSize(20_000, 20_000), big)
    assertThat(maxOf(dng.uprightWidth, dng.uprightHeight)).isAtMost(4096)
    assertThat(dng.uprightWidth).isEqualTo(4096)
    // Lo stesso file JPEG con lo stesso schermo non ha il tetto dei 4096.
    val jpeg = DecodePlanner.plan(8000, 6000, ExifOrientation.NORMAL, ImageFormat.JPEG, IntSize(20_000, 20_000), big)
    assertThat(jpeg.uprightWidth).isGreaterThan(4096)
  }

  @Test
  fun ilTettoDaMemoryClassSiRestringeSuUnTelefonoModesto() {
    val modest = ImagingBudget.forMemoryClass(128)
    val dng = DecodePlanner.plan(8000, 6000, ExifOrientation.NORMAL, ImageFormat.TIFF_RAW, IntSize(20_000, 20_000), modest)
    assertThat(dng.decoderWidth.toLong() * dng.decoderHeight * 4).isAtMost(modest.viewerBitmapBytes)
    assertThat(dng.uprightWidth).isLessThan(4096)
  }

  @Test
  fun conUnOrientamentoManualeLaTagliaDelDecodificatoreEQuellaNonRuotata() {
    // Un DNG di sensore 4000x3000 con orientamento 6: l'utente lo vede 3000x4000 (verticale).
    val p = plan(4000, 3000, IntSize(1000, 2000), manual = ExifOrientation.ROTATE_90, format = ImageFormat.TIFF_RAW)
    assertThat(p.uprightWidth).isEqualTo(1000)
    assertThat(p.uprightHeight).isEqualTo(1333)
    // Il decodificatore produce l'immagine ancora sdraiata: lati scambiati rispetto alla taglia finale.
    assertThat(p.decoderWidth).isEqualTo(1333)
    assertThat(p.decoderHeight).isEqualTo(1000)
  }

  @Test
  fun unPanoramaEstremoEUnoSchermoPiccoloNonDannoDimensioniNulle() {
    val pano = plan(30_000, 1_000, IntSize(1080, 2400))
    assertThat(pano.decoderWidth).isEqualTo(1080)
    assertThat(pano.decoderHeight).isAtLeast(1)
    val thin = plan(10, 10_000, IntSize(1, 1))
    assertThat(thin.decoderWidth).isAtLeast(1)
    assertThat(thin.decoderHeight).isAtLeast(1)
    val zero = plan(500, 400, IntSize(0, 0))
    assertThat(zero.decoderWidth).isAtLeast(1)
  }
}
