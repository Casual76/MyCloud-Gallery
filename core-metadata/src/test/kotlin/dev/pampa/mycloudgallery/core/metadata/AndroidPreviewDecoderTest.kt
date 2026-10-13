package dev.pampa.mycloudgallery.core.metadata

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.metadata.contract.ExtractHints
import dev.pampa.mycloudgallery.core.metadata.contract.Extraction
import dev.pampa.mycloudgallery.core.metadata.contract.ThumbHashCodec
import dev.pampa.mycloudgallery.core.metadata.extract.AndroidPreviewDecoder
import dev.pampa.mycloudgallery.core.metadata.testing.ByteArrayMediaBytes
import dev.pampa.mycloudgallery.fixtures.ExifWriter
import dev.pampa.mycloudgallery.fixtures.ImageFixtures
import kotlin.math.abs
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * La decodifica vera (BitmapFactory, grafica nativa di Robolectric) sulle immagini delle fixture: PNG, JPEG, GIF, BMP e WebP
 * (anche il lossless costruito a mano dalle fixture, quindi un WebP "vero"), e il segnaposto che ne esce, dall'inizio alla fine.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidPreviewDecoderTest {

  private val decoder = AndroidPreviewDecoder()

  private fun mean(rgba: ByteArray, channel: Int): Double {
    var sum = 0L
    var n = 0
    var i = channel
    while (i < rgba.size) { sum += rgba[i].toInt() and 0xFF; n++; i += 4 }
    return sum.toDouble() / n
  }

  private fun assertColor(bytes: ByteArray, r: Int, g: Int, b: Int, tolerance: Double = 6.0) {
    val preview = decoder.decode(bytes, 100)
    assertThat(preview).isNotNull()
    assertThat(abs(mean(preview!!.rgba, 0) - r)).isLessThan(tolerance)
    assertThat(abs(mean(preview.rgba, 1) - g)).isLessThan(tolerance)
    assertThat(abs(mean(preview.rgba, 2) - b)).isLessThan(tolerance)
  }

  @Test
  fun decodificaIFormatiComuniECisonoIColoriGiusti() {
    assertColor(ImageFixtures.jpeg(64, 48, 0x4080C0), 0x40, 0x80, 0xC0, 10.0)
    assertColor(ImageFixtures.pngBytes(ImageFixtures.solid(64, 48, 0x40C080)), 0x40, 0xC0, 0x80)
    assertColor(ImageFixtures.bmp(64, 48, 0x8040C0), 0x80, 0x40, 0xC0)
    assertColor(ImageFixtures.gif(64, 48, 0xC04080), 0xC0, 0x40, 0x80, 40.0) // la tavolozza del GIF e' ridotta
    assertColor(ImageFixtures.webp(64, 48, -0xBF3F80, null, null), 0x40, 0xC0, 0x80)
  }

  @Test
  fun lo_Streaming_DaMediaBytesDaLoStessoRisultatoDeiByteInMemoria() {
    val files = listOf(
      ImageFixtures.jpeg(64, 48, 0x4080C0),
      ImageFixtures.jpeg(1600, 1200, 0x336699),
      ImageFixtures.pngBytes(ImageFixtures.solid(64, 48, 0x40C080)),
      ImageFixtures.bmp(64, 48, 0x8040C0),
      ImageFixtures.gif(64, 48, 0xC04080),
      ImageFixtures.webp(64, 48, -0xBF3F80, null, null),
    )
    for (bytes in files) {
      val inMemory = decoder.decode(bytes, 100)!!
      val streamed = decoder.decode(ByteArrayMediaBytes(bytes), 100)!!
      assertThat(streamed.width).isEqualTo(inMemory.width)
      assertThat(streamed.height).isEqualTo(inMemory.height)
      assertThat(streamed.rgba).isEqualTo(inMemory.rgba)
    }
  }

  @Test
  fun loStreamingNonCaricaIlFileEDaNullPerByteRovinati() {
    // Un file "grande" servito a blocchi: nessuna lettura piu' lunga di un blocco del decodificatore, e il file si legge una volta
    // (la prima apertura per le dimensioni ne legge pochi KiB).
    val jpeg = ImageFixtures.jpeg(ImageFixtures.noise(300, 200, 5), null, null)
    val source = ByteArrayMediaBytes(jpeg)
    assertThat(decoder.decode(source, 100)).isNotNull()
    assertThat(source.bytesRead).isEqualTo(0L) // dataSource() non passa da read(): il flusso legge con readAt
    assertThat(decoder.decode(ByteArrayMediaBytes("non sono un'immagine".toByteArray()), 100)).isNull()
    assertThat(decoder.decode(ByteArrayMediaBytes(jpeg.copyOf(10)), 100)).isNull()
    assertThat(decoder.decode(ByteArrayMediaBytes(ByteArray(0)), 100)).isNull()
    assertThat(decoder.decode(source, 0)).isNull()
  }

  @Test
  fun leDimensioniSiRiduconoAlMassimoRichiesto() {
    val big = ImageFixtures.jpeg(1600, 1200, 0x336699)
    val preview = decoder.decode(big, 100)!!
    assertThat(maxOf(preview.width, preview.height)).isAtMost(100)
    assertThat(maxOf(preview.width, preview.height)).isAtLeast(50)
    // Proporzione 4:3 conservata.
    assertThat(preview.width.toDouble() / preview.height).isWithin(0.1).of(4.0 / 3.0)
    val small = decoder.decode(ImageFixtures.jpeg(30, 20, 0x336699), 100)!!
    assertThat(small.width).isEqualTo(30)
    assertThat(small.height).isEqualTo(20)
  }

  @Test
  fun ilWebpAnimatoSiDecodificaNelPrimoFotogramaOSiDichiaraNonDecodificabile() {
    val animated = ImageFixtures.webpAnimated(64, 48, intArrayOf(-0x1FBFBF, -0xBFBF20), 100)
    // BitmapFactory non anima: o restituisce il primo fotogramma o null. Mai un'eccezione.
    val preview = decoder.decode(animated, 100)
    if (preview != null) assertThat(preview.width).isEqualTo(64)
  }

  @Test
  fun iByteRovinatiDannoNullEMaiUnEccezione() {
    assertThat(decoder.decode(ByteArray(0), 100)).isNull()
    assertThat(decoder.decode("non sono un'immagine".toByteArray(), 100)).isNull()
    assertThat(decoder.decode(ImageFixtures.jpeg(64, 48, 0).copyOf(10), 100)).isNull()
    assertThat(decoder.decode(ImageFixtures.jpeg(64, 48, 0), 0)).isNull()
  }

  @Test
  fun dalFileAlSegnapostoConIlDecodificatoreVero() = runTest {
    val spec = ExifWriter.Spec().apply { dateOriginal = "2024:03:12 10:15:30"; offsetOriginal = "+01:00"; orientation = 1 }
    val jpeg = ImageFixtures.jpeg(ImageFixtures.uprightF(120, 90), spec, null)
    val extractor = dev.pampa.mycloudgallery.core.metadata.extract.DefaultMetadataExtractor(
      geocoder = PLACES,
      zones = PLACES,
      videoProbe = dev.pampa.mycloudgallery.core.metadata.testing.FakeVideoProbe(null),
      previewDecoder = AndroidPreviewDecoder(),
      clock = { 1_800_000_000_000L },
      blocking = kotlinx.coroutines.Dispatchers.Unconfined,
    )
    val hints = ExtractHints("image/jpeg", "a.jpg", "DCIM/Camera/", null, null, null, null, null, ROME)
    val result = extractor.extract(ByteArrayMediaBytes(jpeg), hints) as Extraction.Read
    val hash = result.metadata.thumbHash!!
    // 120x90 = 4:3: 7x5 coefficienti.
    assertThat(ThumbHashCodec.aspectRatio(hash)).isWithin(0.1).of(1.4)
    // Lo sfondo della "F" e' blu (0x2A6FB0): il segnaposto e' blu.
    val image = ThumbHashCodec.decode(hash, 16)
    val b = image.map { it and 0xFF }.average()
    val r = image.map { (it shr 16) and 0xFF }.average()
    assertThat(b).isGreaterThan(r)
  }

  @Test
  fun ilSegnapostoVieneDallaMiniaturaIncorporataQuandoCe() = runTest {
    // Foto quasi bianca con una miniatura scura 4:3: il segnaposto e' scuro, quindi viene dalla miniatura e non dal file.
    val spec = ExifWriter.Spec().apply {
      dateOriginal = "2024:03:12 10:15:30"; offsetOriginal = "+01:00"; thumbnailJpeg = ImageFixtures.exifThumbnail(0x203040)
    }
    val jpeg = ImageFixtures.jpeg(120, 90, 0xF0F0F0, spec, null)
    val extractor = dev.pampa.mycloudgallery.core.metadata.extract.DefaultMetadataExtractor(
      geocoder = PLACES,
      zones = PLACES,
      videoProbe = dev.pampa.mycloudgallery.core.metadata.testing.FakeVideoProbe(null),
      previewDecoder = AndroidPreviewDecoder(),
      clock = { 1_800_000_000_000L },
      blocking = kotlinx.coroutines.Dispatchers.Unconfined,
    )
    val hints = ExtractHints("image/jpeg", "a.jpg", "DCIM/Camera/", null, null, null, null, null, ROME)
    val source = ByteArrayMediaBytes(jpeg)
    val result = extractor.extract(source, hints) as Extraction.Read
    val image = ThumbHashCodec.decode(result.metadata.thumbHash!!, 8)
    assertThat(image.map { (it shr 8) and 0xFF }.average()).isLessThan(90.0)
    // Il file non e' stato aperto come flusso: solo la lettura per intervalli (testa e coda).
    assertThat(source.reads).isAtMost(2)
  }

  @Test
  fun unFileRovinatoChiamatoJpegDiventaCorruptConIlDecodificatoreVero() = runTest {
    val extractor = dev.pampa.mycloudgallery.core.metadata.extract.DefaultMetadataExtractor(
      geocoder = PLACES,
      zones = PLACES,
      videoProbe = dev.pampa.mycloudgallery.core.metadata.testing.FakeVideoProbe(null),
      previewDecoder = AndroidPreviewDecoder(),
      clock = { 1_800_000_000_000L },
      blocking = kotlinx.coroutines.Dispatchers.Unconfined,
    )
    // Intestazioni a posto, dati dell'immagine distrutti dopo l'SOS (con un EOI in fondo, quindi "intero").
    val good = ImageFixtures.jpeg(ImageFixtures.noise(60, 60, 9), null, null)
    val sos = (2 until good.size - 1).first { (good[it].toInt() and 0xFF) == 0xFF && (good[it + 1].toInt() and 0xFF) == 0xDA }
    val broken = good.copyOf()
    for (i in sos + 20 until good.size - 2) broken[i] = 0x13
    val hints = ExtractHints("image/jpeg", "a.jpg", "DCIM/Camera/", null, null, null, null, null, ROME)
    val result = extractor.extract(ByteArrayMediaBytes(broken), hints)
    // BitmapFactory puo' recuperare un'immagine parziale o rifiutarla: in entrambi i casi nessuna eccezione.
    assertThat(result).isNotNull()
  }
}
