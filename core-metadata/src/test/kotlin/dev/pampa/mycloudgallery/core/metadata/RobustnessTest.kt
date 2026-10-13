package dev.pampa.mycloudgallery.core.metadata

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.metadata.contract.ExtractCode
import dev.pampa.mycloudgallery.core.metadata.contract.ExtractHints
import dev.pampa.mycloudgallery.core.metadata.contract.Extraction
import dev.pampa.mycloudgallery.core.metadata.extract.VideoMetadataParsing
import dev.pampa.mycloudgallery.core.metadata.testing.ByteArrayMediaBytes
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.fixtures.Bytes
import dev.pampa.mycloudgallery.fixtures.ExifWriter
import dev.pampa.mycloudgallery.fixtures.FixtureCatalog
import dev.pampa.mycloudgallery.fixtures.ImageFixtures
import dev.pampa.mycloudgallery.fixtures.IsoFixtures
import dev.pampa.mycloudgallery.fixtures.MotionFixtures
import dev.pampa.mycloudgallery.fixtures.TiffFixtures
import dev.pampa.mycloudgallery.fixtures.XmpPackets
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** File troncati o distrutti in tutti i modi: mai un'eccezione (E-M10, E-M12), e 200 MP senza leggere il file intero (E-P4). */
class RobustnessTest {

  private val exif = ExifWriter.Spec().apply { dateOriginal = "2024:03:12 10:15:30"; offsetOriginal = "+01:00"; model = "SM-S931B"; latitude = 43.7696; longitude = 11.2558 }

  private fun hints(name: String, mime: String) = ExtractHints(mime, name, "DCIM/Camera/", null, null, null, null, null, ROME)

  /** I file ben formati su cui si prova il taglio, con il tipo MIME e se un taglio a meta' deve per forza dare "non letto". */
  private fun samples(): List<Triple<String, String, ByteArray>> = listOf(
    Triple("a.jpg", "image/jpeg", ImageFixtures.jpeg(ImageFixtures.noise(120, 90, 5), exif, null)),
    Triple("b.png", "image/png", ImageFixtures.png(ImageFixtures.noise(60, 60, 6), exif, XmpPackets.panorama())),
    Triple("c.webp", "image/webp", ImageFixtures.webp(64, 48, -0xBF7F40, exif, XmpPackets.panorama())),
    Triple("d.heic", "image/heic", IsoFixtures.heic(IsoFixtures.HeicSpec().apply { this.exif = this@RobustnessTest.exif; xmp = XmpPackets.ultraHdr() })),
    Triple("e.dng", "image/x-adobe-dng", TiffFixtures.dng(TiffFixtures.DngSpec())),
    Triple("f.mp4", "video/mp4", IsoFixtures.mp4(IsoFixtures.Mp4Spec().apply { iso6709 = "+43.7696+011.2558/" })),
    Triple("g.bmp", "image/bmp", ImageFixtures.bmp(30, 20, 0x336699)),
  )

  private fun allCuts(size: Int): List<Int> {
    val step = maxOf(1, size / 1200)
    return (0 until size step step).toList() + (maxOf(0, size - 40)..size).toList()
  }

  @Test
  fun ognunDeiTagliNonLanciaEUnTaglioABetaNonEUnaLetturaRiuscita() = runTest {
    val extractor = testExtractor()
    for ((name, mime, whole) in samples()) {
      for (n in allCuts(whole.size)) {
        val result = extractor.extract(ByteArrayMediaBytes(whole.copyOf(n)), hints(name, mime))
        if (n < whole.size / 2) {
          assertWithMessage("$name tagliato a $n di ${whole.size} byte").that(result).isNotInstanceOf(Extraction.Read::class.java)
        }
      }
      // Il file intero, invece, si legge.
      assertWithMessage(name).that(extractor.extract(ByteArrayMediaBytes(whole), hints(name, mime))).isInstanceOf(Extraction.Read::class.java)
    }
  }

  @Test
  fun unGifENelleMotionPhotoUnTaglioNonLanciaMai() = runTest {
    val extractor = testExtractor()
    val still = ImageFixtures.solid(64, 48, 0x336699)
    val files = listOf(
      "a.gif" to ImageFixtures.gif(30, 20, 0xAA5500),
      "b.jpg" to MotionFixtures.samsungSef(still, exif, MotionFixtures.video(800)),
      "c.jpg" to MotionFixtures.googleV1(still, exif, MotionFixtures.video(800)),
      "d.jpg" to MotionFixtures.microVideo(still, exif, MotionFixtures.video(800)),
      "e.webp" to ImageFixtures.webpAnimated(30, 20, intArrayOf(-1, -0x1000000), 100),
    )
    for ((name, whole) in files) {
      for (n in allCuts(whole.size)) {
        extractor.extract(ByteArrayMediaBytes(whole.copyOf(n)), hints(name, if (name.endsWith("gif")) "image/gif" else "image/jpeg"))
      }
    }
  }

  @Test
  fun ognunByteDistruttoUnoAllaVoltaNonLancia() = runTest {
    val extractor = testExtractor()
    for ((name, mime, whole) in samples()) {
      val step = maxOf(1, whole.size / 600)
      var i = 0
      while (i < whole.size) {
        val broken = whole.copyOf()
        broken[i] = (broken[i].toInt() xor 0xFF).toByte()
        extractor.extract(ByteArrayMediaBytes(broken), hints(name, mime))
        i += step
      }
    }
  }

  @Test
  fun byteCasualiInOgniFormatoNonLanciano() = runTest {
    val extractor = testExtractor()
    val random = java.util.Random(42)
    val magics = listOf(
      byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte()),
      byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
      "RIFF".toByteArray() + byteArrayOf(0x20, 0, 0, 0) + "WEBPVP8X".toByteArray(),
      byteArrayOf(0, 0, 0, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), 'h'.code.toByte(), 'e'.code.toByte(), 'i'.code.toByte(), 'c'.code.toByte()),
      "II*\u0000".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(8, 0, 0, 0),
      "MM\u0000*".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 0, 0, 8),
      byteArrayOf(0, 0, 0, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), 'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte()),
      "GIF89a".toByteArray(),
    )
    repeat(400) {
      val magic = magics[it % magics.size]
      val tail = ByteArray(random.nextInt(600)).also { b -> random.nextBytes(b) }
      val result = extractor.extract(ByteArrayMediaBytes(Bytes.concat(magic, tail)), hints("x.bin", "image/jpeg"))
      assertThat(result).isNotNull()
    }
  }

  @Test
  fun unaSerieDiTagliSuTuttoIlCatalogoNonLancia() = runTest {
    val extractor = testExtractor()
    for (f in FixtureCatalog.all()) {
      val whole = f.bytes()
      for (n in listOf(0, 1, 2, 3, 7, 11, 12, 13, 31, 64, whole.size / 3, whole.size / 2, whole.size - 1)) {
        if (n in 0..whole.size) extractor.extract(ByteArrayMediaBytes(whole.copyOf(n)), f.hints())
      }
    }
  }

  // ---- 200 MP ------------------------------------------------------------------------------------------------------------

  /** Un JPEG vero con la dimensione dichiarata portata a 16320 x 12240 (SOF0). */
  private fun with200Mp(jpeg: ByteArray): ByteArray {
    val out = jpeg.copyOf()
    var i = 2
    while (i < out.size - 9) {
      if ((out[i].toInt() and 0xFF) == 0xFF && (out[i + 1].toInt() and 0xFF) == 0xC0) {
        val height = 12240
        val width = 16320
        out[i + 5] = (height shr 8).toByte(); out[i + 6] = height.toByte()
        out[i + 7] = (width shr 8).toByte(); out[i + 8] = width.toByte()
        return out
      }
      i += 1
    }
    error("SOF0 non trovato")
  }

  @Test
  fun unJpegDa200MpSiLeggeSenzaLeggereIlFile() = runTest {
    val head = with200Mp(ImageFixtures.jpeg(64, 48, 0x4080C0, exif, null))
    val file = SparseJpegBytes(62_000_000L, head)
    val preview = FakePreviewDecoder()
    val result = testExtractor(preview = preview).extract(file, hints("grande.jpg", "image/jpeg")) as Extraction.Read
    assertThat(result.kind).isEqualTo(MediaKind.PHOTO)
    assertThat(result.metadata.width).isEqualTo(16320)
    assertThat(result.metadata.height).isEqualTo(12240)
    assertThat(result.metadata.captureTime!!.confidence.name).isEqualTo("EXACT")
    // Testa, intestazioni e coda: nemmeno un megabyte su 62.
    assertThat(file.bytesRead).isLessThan(300_000L)
    assertThat(file.reads).isAtMost(6)
    // Al massimo due pagine contigue da 64 KiB in una sola lettura (la coda cade a cavallo di due pagine).
    assertThat(file.maxSingleRead).isAtMost(128 * 1024)
    // Mai decodificato intero.
    assertThat(preview.lastSize).isAtMost(64 * 1024)
  }

  @Test
  fun unVideoDaVentiGigabyteSiLeggeConPocheLetture() = runTest {
    // Un MP4 finto di 20 GB: i riquadri di primo livello sono ftyp, mdat (che copre quasi tutto) e moov in fondo.
    val total = 20L * 1024 * 1024 * 1024
    val ftyp = IsoFixtures.box("ftyp", Bytes.ascii4("isom"), ByteArray(4), Bytes.ascii4("mp42"))
    val moov = IsoFixtures.box("moov", IsoFixtures.fullBox("mvhd", 0, 0, ByteArray(96)))
    val mdatSize = total - ftyp.size - moov.size
    val mdatHeader = Bytes().u32be(1).ascii("mdat").u64be(mdatSize).toBytes()
    val moovStart = total - moov.size
    val file = object : dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes {
      var reads = 0
      var bytes = 0L
      override val size: Long = total

      override suspend fun read(range: LongRange): ByteArray {
        val first = maxOf(0L, range.first)
        val last = minOf(size - 1, range.last)
        if (last < first) return ByteArray(0)
        reads++
        val out = ByteArray((last - first + 1).toInt())
        bytes += out.size
        for (i in out.indices) {
          val p = first + i
          out[i] = when {
            p < ftyp.size -> ftyp[p.toInt()]
            p < ftyp.size + mdatHeader.size -> mdatHeader[(p - ftyp.size).toInt()]
            p >= moovStart -> moov[(p - moovStart).toInt()]
            else -> 0
          }
        }
        return out
      }

      override fun dataSource(): android.media.MediaDataSource = error("non serve")
    }
    val result = testExtractor().extract(file, hints("lungo.mp4", "video/mp4"))
    assertThat(result).isInstanceOf(Extraction.Read::class.java)
    assertThat(file.reads).isAtMost(8)
    assertThat(file.bytes).isLessThan(300_000L)
  }

  // ---- stringhe del MediaMetadataRetriever --------------------------------------------------------------------------------

  @Test
  fun laDataDelContenitoreVideo() {
    assertThat(VideoMetadataParsing.parseCreationDate("20240312T091530.000Z")).isEqualTo(FixtureCatalog.ms("2024-03-12T09:15:30Z"))
    assertThat(VideoMetadataParsing.parseCreationDate("20240312T091530.5Z")).isEqualTo(FixtureCatalog.ms("2024-03-12T09:15:30.500Z"))
    assertThat(VideoMetadataParsing.parseCreationDate("20240312T091530Z")).isEqualTo(FixtureCatalog.ms("2024-03-12T09:15:30Z"))
    // Un contenitore a zero vale il 1904: nessuna data.
    assertThat(VideoMetadataParsing.parseCreationDate("19040101T000000.000Z")).isNull()
    assertThat(VideoMetadataParsing.parseCreationDate("19700101T000000.000Z")).isNull()
    for (bad in listOf("", "ieri", "20241399T091530.000Z", "2024-03-12", "20240312")) assertThat(VideoMetadataParsing.parseCreationDate(bad)).isNull()
    assertThat(VideoMetadataParsing.parseCreationDate(null)).isNull()
  }

  @Test
  fun laPosizioneIso6709() {
    val a = VideoMetadataParsing.parseIso6709("+43.7696+011.2558/")!!
    assertThat(a.latitude).isWithin(1e-6).of(43.7696)
    assertThat(a.longitude).isWithin(1e-6).of(11.2558)
    val b = VideoMetadataParsing.parseIso6709("-33.8688-070.6693+012.5/")!!
    assertThat(b.latitude).isWithin(1e-6).of(-33.8688)
    assertThat(b.longitude).isWithin(1e-6).of(-70.6693)
    assertThat(b.altitudeMeters).isWithin(1e-6).of(12.5)
    // Gradi e minuti, e gradi minuti e secondi.
    val c = VideoMetadataParsing.parseIso6709("+4346.176+01115.348/")!!
    assertThat(c.latitude).isWithin(1e-4).of(43.7696)
    assertThat(c.longitude).isWithin(1e-4).of(11.2558)
    val d = VideoMetadataParsing.parseIso6709("+434610.56+0111520.88/")!!
    assertThat(d.latitude).isWithin(1e-4).of(43.7696)
    assertThat(d.longitude).isWithin(1e-4).of(11.2558)
    for (bad in listOf("", "+0.0+0.0/", "+95.0+010.0/", "+43.0-190.0/", "qui", "+43.7696", "43.7696+011.2558/")) assertThat(VideoMetadataParsing.parseIso6709(bad)).isNull()
    assertThat(VideoMetadataParsing.parseIso6709(null)).isNull()
  }

  @Test
  fun laRotazioneDiUnVideoSiNormalizza() {
    assertThat(VideoMetadataParsing.normalizeRotation(0)).isEqualTo(0)
    assertThat(VideoMetadataParsing.normalizeRotation(90)).isEqualTo(90)
    assertThat(VideoMetadataParsing.normalizeRotation(-90)).isEqualTo(270)
    assertThat(VideoMetadataParsing.normalizeRotation(450)).isEqualTo(90)
    assertThat(VideoMetadataParsing.normalizeRotation(100)).isEqualTo(90)
    assertThat(VideoMetadataParsing.normalizeRotation(359)).isEqualTo(0)
  }

  @Test
  fun iCodiciDiEsitoRestanoQuelliDelContratto() {
    assertThat(ExtractCode.entries.map { it.name }).containsAtLeast("ZERO_BYTES", "NOT_A_MEDIA_FILE", "DECODE_FAILED", "TRUNCATED", "UNSUPPORTED_FORMAT")
  }
}
