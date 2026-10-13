package dev.pampa.mycloudgallery.core.metadata

import android.media.MediaDataSource
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.metadata.contract.ExtractCode
import dev.pampa.mycloudgallery.core.metadata.contract.ExtractHints
import dev.pampa.mycloudgallery.core.metadata.contract.Extraction
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.metadata.contract.NeighborOffsets
import dev.pampa.mycloudgallery.core.metadata.contract.ThumbHashCodec
import dev.pampa.mycloudgallery.core.metadata.contract.VideoFacts
import dev.pampa.mycloudgallery.core.metadata.contract.VideoProbe
import dev.pampa.mycloudgallery.core.metadata.extract.DefaultExifDetailsReader
import dev.pampa.mycloudgallery.core.metadata.extract.DefaultMetadataExtractor
import dev.pampa.mycloudgallery.core.metadata.extract.NeighborOffsetsProvider
import dev.pampa.mycloudgallery.core.metadata.extract.oriented
import dev.pampa.mycloudgallery.core.metadata.extract.Preview
import dev.pampa.mycloudgallery.core.metadata.testing.ByteArrayMediaBytes
import dev.pampa.mycloudgallery.core.metadata.testing.FakeVideoProbe
import dev.pampa.mycloudgallery.core.model.CaptureTimeConfidence
import dev.pampa.mycloudgallery.core.model.CaptureTimeSource
import dev.pampa.mycloudgallery.core.model.ExifOrientation
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaTrait
import dev.pampa.mycloudgallery.core.places.noop.NoReverseGeocoder
import dev.pampa.mycloudgallery.core.places.noop.NoTimeZoneLookup
import dev.pampa.mycloudgallery.fixtures.ExifWriter
import dev.pampa.mycloudgallery.fixtures.FixtureCatalog
import dev.pampa.mycloudgallery.fixtures.ImageFixtures
import dev.pampa.mycloudgallery.fixtures.Img
import dev.pampa.mycloudgallery.fixtures.IsoFixtures
import dev.pampa.mycloudgallery.fixtures.TiffFixtures
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Il comportamento dell'estrattore oltre il catalogo: eccezioni, anteprime, vicini, luoghi, video, EXIF a richiesta. */
class ExtractorBehaviorTest {

  private fun exif(block: ExifWriter.Spec.() -> Unit) = ExifWriter.Spec().apply { dateOriginal = "2024:03:12 10:15:30"; block() }

  private fun hints(name: String, mime: String = "image/jpeg", path: String = "DCIM/Camera/", taken: Long? = null, modified: Long? = null) =
    ExtractHints(mime, name, path, taken, null, null, null, null, ROME, modified)

  private suspend fun read(bytes: ByteArray, hints: ExtractHints, extractor: DefaultMetadataExtractor = testExtractor()): Extraction.Read =
    extractor.extract(ByteArrayMediaBytes(bytes), hints) as Extraction.Read

  // ---- mai un'eccezione ----------------------------------------------------------------------------------------------

  private class FailingBytes(override val size: Long, private val failAfterReads: Int) : MediaBytes {
    var reads = 0
    private val real = ImageFixtures.jpeg(64, 48, 0x4080C0)

    override suspend fun read(range: LongRange): ByteArray {
      if (++reads > failAfterReads) throw IOException("lettura fallita")
      return real.copyOfRange(range.first.toInt().coerceAtMost(real.size), (range.last.toInt() + 1).coerceAtMost(real.size))
    }

    override fun dataSource(): MediaDataSource = error("non serve")
  }

  @Test
  fun unErroreDiLetturaDiventaFileRovinatoNonUnEccezione() = runTest {
    val extractor = testExtractor()
    // Un file da 200 KB: la testa e la coda sono due letture. Se una delle due cade, il file e' rovinato.
    for (failAfter in 0..1) {
      val result = extractor.extract(FailingBytes(200_000, failAfter), hints("a.jpg"))
      assertThat(result).isInstanceOf(Extraction.Corrupt::class.java)
      assertThat((result as Extraction.Corrupt).code).isEqualTo(ExtractCode.DECODE_FAILED)
    }
  }

  private class ThrowingProbe : VideoProbe {
    override fun probe(bytes: MediaBytes): VideoFacts = throw IllegalStateException("sonda rotta")
  }

  @Test
  fun unaSondaVideoCheLanciaNonFermaLaLettura() = runTest {
    val mp4 = IsoFixtures.mp4(IsoFixtures.Mp4Spec())
    val result = read(mp4, hints("VID_20240312_101534.mp4", "video/mp4"), testExtractor(probe = ThrowingProbe()))
    assertThat(result.kind).isEqualTo(MediaKind.VIDEO)
    assertThat(result.metadata.captureTime!!.source).isEqualTo(CaptureTimeSource.FILE_NAME)
  }

  @Test
  fun zeroByteENonMediaNonChiamanoNessunDecodificatore() = runTest {
    val preview = FakePreviewDecoder()
    val probe = FakeVideoProbe(null)
    val extractor = testExtractor(probe = probe, preview = preview)
    assertThat(extractor.extract(ByteArrayMediaBytes(ByteArray(0)), hints("a.jpg"))).isEqualTo(Extraction.NotMedia(ExtractCode.ZERO_BYTES))
    assertThat(extractor.extract(ByteArrayMediaBytes("ciao".toByteArray()), hints("a.jpg"))).isEqualTo(Extraction.NotMedia(ExtractCode.NOT_A_MEDIA_FILE))
    assertThat(preview.calls).isEqualTo(0)
  }

  @Test
  fun unFormatoSenzaMimeNoto_NonSupportatoSoloSeDichiaratoMultimediale() = runTest {
    val junk = ByteArray(100) { 0x55 }
    val extractor = testExtractor()
    assertThat(extractor.extract(ByteArrayMediaBytes(junk), hints("a.jxl", "image/jxl"))).isEqualTo(Extraction.Unsupported(ExtractCode.UNSUPPORTED_FORMAT))
    assertThat(extractor.extract(ByteArrayMediaBytes(junk), hints("a.wmv", "video/x-ms-wmv"))).isEqualTo(Extraction.Unsupported(ExtractCode.UNSUPPORTED_FORMAT))
    assertThat(extractor.extract(ByteArrayMediaBytes(junk), hints("a.txt", "text/plain"))).isEqualTo(Extraction.NotMedia(ExtractCode.NOT_A_MEDIA_FILE))
    assertThat(extractor.extract(ByteArrayMediaBytes(junk), hints("a.bin", "application/octet-stream"))).isEqualTo(Extraction.NotMedia(ExtractCode.NOT_A_MEDIA_FILE))
    assertThat(extractor.extract(ByteArrayMediaBytes(junk), hints("a.jpg", "image/jpeg"))).isEqualTo(Extraction.NotMedia(ExtractCode.NOT_A_MEDIA_FILE))
    assertThat(extractor.extract(ByteArrayMediaBytes(junk), hints("a", ""))).isEqualTo(Extraction.NotMedia(ExtractCode.NOT_A_MEDIA_FILE))
  }

  // ---- anteprima sfocata -------------------------------------------------------------------------------------------------

  @Test
  fun ilSegnapostoSfocatoSiCalcolaDalleAnteprimeEHaLaProporzioneGiusta() = runTest {
    val jpeg = ImageFixtures.jpeg(64, 48, 0x4080C0, exif { }, null)
    val result = read(jpeg, hints("a.jpg"))
    val hash = result.metadata.thumbHash!!
    // L'anteprima finta e' 8x6 (4:3): 7x5 coefficienti -> circa 1,4.
    assertThat(ThumbHashCodec.aspectRatio(hash)).isWithin(0.1).of(1.4)
  }

  @Test
  fun conOrientamentoLeAnteprimeSiRaddrizzano() = runTest {
    val jpeg = ImageFixtures.jpeg(64, 48, 0x4080C0, exif { orientation = 6 }, null)
    val hash = read(jpeg, hints("a.jpg")).metadata.thumbHash!!
    // 8x6 ruotata di 90 gradi = 6x8: ritratto.
    assertThat(ThumbHashCodec.aspectRatio(hash)).isLessThan(1.0)
  }

  @Test
  fun leAnteprimeSiOrientanoComeGliOttoValoriExif() {
    val w = 3
    val h = 2
    val rgba = ByteArray(w * h * 4)
    val labels = Array(h) { y -> IntArray(w) { x -> y * w + x + 1 } }
    for (y in 0 until h) for (x in 0 until w) rgba[(y * w + x) * 4] = labels[y][x].toByte()
    val upright = Img.create(w, h)
    for (y in 0 until h) for (x in 0 until w) upright.setRgb(x, y, labels[y][x] shl 16)
    for (o in ExifOrientation.entries) {
      val shown = ImageFixtures.displayed(upright, o.exif)
      val got = Preview(w, h, rgba).oriented(o)
      assertThat(got.width).isEqualTo(shown.width())
      assertThat(got.height).isEqualTo(shown.height())
      for (y in 0 until got.height) for (x in 0 until got.width) {
        assertThat(got.rgba[(y * got.width + x) * 4].toInt() and 0xFF).isEqualTo((shown.rgb(x, y) shr 16) and 0xFF)
      }
    }
  }

  // ---- il segnaposto: prima la miniatura incorporata, poi (solo se manca) la decodifica in streaming -------------------------

  private val thumb = ImageFixtures.exifThumbnail(0x203040) // 40x30, 4:3

  private fun extractorWith(preview: FakePreviewDecoder, maxFullDecodeBytes: Long = DefaultMetadataExtractor.MAX_FULL_DECODE_BYTES) =
    DefaultMetadataExtractor(
      PLACES, PLACES, FakeVideoProbe(null), preview, clock = { FixtureCatalog.NOW_MS }, blocking = Dispatchers.Unconfined,
      maxFullDecodeBytes = maxFullDecodeBytes,
    )

  @Test
  fun unaMiniaturaConLaProporzioneGiustaBastaENonSiLeggeIlFile() = runTest {
    val small = ImageFixtures.jpeg(64, 48, 0x4080C0, exif { thumbnailJpeg = thumb }, null) // 4:3 contro 4:3
    val preview = FakePreviewDecoder()
    val result = read(small, hints("a.jpg"), extractorWith(preview))
    assertThat(preview.byteCalls).isEqualTo(1)
    assertThat(preview.lastSize).isEqualTo(thumb.size)
    assertThat(preview.streamCalls).isEqualTo(0)
    assertThat(result.metadata.thumbHash).isNotNull()
    // Anche per un file enorme: la miniatura e' gia' in memoria con l'EXIF.
    val big = SparseJpegBytes(DefaultMetadataExtractor.MAX_FULL_DECODE_BYTES * 4, small)
    val other = FakePreviewDecoder()
    val bigResult = extractorWith(other).extract(big, hints("b.jpg")) as Extraction.Read
    assertThat(other.byteCalls).isEqualTo(1)
    assertThat(other.streamCalls).isEqualTo(0)
    assertThat(bigResult.metadata.thumbHash).isNotNull()
    assertThat(big.maxSingleRead).isAtMost(128 * 1024)
  }

  @Test
  fun unaMiniaturaDiUnaltraProporzionePassaAlRipiegoInStreaming() = runTest {
    // Foto 16:9 con la solita miniatura 4:3 delle fotocamere.
    val wide = ImageFixtures.jpeg(64, 36, 0x4080C0, exif { thumbnailJpeg = thumb }, null)
    val preview = FakePreviewDecoder()
    val result = read(wide, hints("a.jpg"), extractorWith(preview))
    assertThat(preview.byteCalls).isEqualTo(1) // la miniatura e' stata provata
    assertThat(preview.streamCalls).isEqualTo(1) // e scartata: ripiego sul file
    assertThat(preview.lastStreamSize).isEqualTo(wide.size.toLong())
    assertThat(result.metadata.thumbHash).isNotNull()
    // Con il ripiego spento (limite 0) non resta nessun segnaposto, e il file non si apre.
    val none = FakePreviewDecoder()
    val noHash = read(wide, hints("a.jpg"), extractorWith(none, maxFullDecodeBytes = 0))
    assertThat(none.streamCalls).isEqualTo(0)
    assertThat(noHash.metadata.thumbHash).isNull()
  }

  @Test
  fun laToleranzaDiProporzioneEIlQuattroPerCento() = runTest {
    // Una foto 4:3; l'anteprima decodificata e' larga 100: l'altezza attesa e' 75, tollerata entro 3 pixel.
    val jpeg = ImageFixtures.jpeg(400, 300, 0x4080C0, exif { thumbnailJpeg = thumb }, null)
    suspend fun streamed(height: Int): Int {
      val preview = FakePreviewDecoder(previewWidth = 100, previewHeight = height)
      read(jpeg, hints("a.jpg"), extractorWith(preview))
      return preview.streamCalls
    }
    assertThat(streamed(75)).isEqualTo(0)
    assertThat(streamed(73)).isEqualTo(0)
    assertThat(streamed(78)).isEqualTo(0)
    assertThat(streamed(70)).isEqualTo(1)
    assertThat(streamed(56)).isEqualTo(1) // 16:9
    assertThat(streamed(100)).isEqualTo(1) // quadrata
    // Una miniatura 160x120 di una foto 4:3 a 100 px arrotonda di un pixel al piu': sempre accettata.
    assertThat(DefaultMetadataExtractor.ASPECT_TOLERANCE).isEqualTo(0.04)
  }

  @Test
  fun unaMiniaturaCheNonSiDecodificaPassaAlRipiego() = runTest {
    val small = ImageFixtures.jpeg(64, 48, 0x4080C0, exif { thumbnailJpeg = thumb }, null)
    val preview = FakePreviewDecoder(failBytes = true)
    val result = read(small, hints("a.jpg"), extractorWith(preview))
    assertThat(preview.byteCalls).isEqualTo(1)
    assertThat(preview.streamCalls).isEqualTo(1)
    assertThat(result.metadata.thumbHash).isNotNull()
  }

  @Test
  fun senzaMiniaturaSiVaDirettoAlRipiegoEPngWebpGifBmpNonHannoAltraStrada() = runTest {
    val preview = FakePreviewDecoder()
    val extractor = extractorWith(preview)
    read(ImageFixtures.jpeg(64, 48, 0x4080C0, exif { }, null), hints("a.jpg"), extractor)
    read(ImageFixtures.pngBytes(ImageFixtures.solid(64, 48, 0x40C080)), hints("a.png", "image/png"), extractor)
    read(ImageFixtures.webp(64, 48, -0xBF3F80, null, null), hints("a.webp", "image/webp"), extractor)
    read(ImageFixtures.gif(64, 48, 0xC04080), hints("a.gif", "image/gif"), extractor)
    read(ImageFixtures.bmp(64, 48, 0x8040C0), hints("a.bmp", "image/bmp"), extractor)
    assertThat(preview.byteCalls).isEqualTo(0)
    assertThat(preview.streamCalls).isEqualTo(5)
  }

  @Test
  fun unDngUsaLAnteprimaJpegDelSuoIfd0() = runTest {
    val previewJpeg = ImageFixtures.jpeg(64, 48, 0x336699) // 4:3 come il DNG (3984x2988)
    val dng = TiffFixtures.dng(TiffFixtures.DngSpec().apply { this.previewJpeg = previewJpeg })
    val preview = FakePreviewDecoder()
    val result = read(dng, hints("a.dng", "image/x-adobe-dng"), extractorWith(preview))
    assertThat(result.kind).isEqualTo(MediaKind.RAW_PHOTO)
    assertThat(preview.byteCalls).isEqualTo(1)
    assertThat(preview.lastSize).isEqualTo(previewJpeg.size)
    assertThat(preview.streamCalls).isEqualTo(0)
    assertThat(result.metadata.thumbHash).isNotNull()
    // Un DNG con l'anteprima di zeri (non e' un JPEG) o senza compressione: ripiego, e se non si decodifica non e' colpa del file.
    val plain = TiffFixtures.dng(TiffFixtures.DngSpec())
    val second = FakePreviewDecoder(fail = true)
    val noHash = read(plain, hints("a.dng", "image/x-adobe-dng"), extractorWith(second))
    assertThat(second.byteCalls).isEqualTo(0)
    assertThat(second.streamCalls).isEqualTo(1)
    assertThat(noHash.metadata.thumbHash).isNull()
  }

  @Test
  fun unaFotoComuneCostaPocheCentinaiaDiKiBLettiEZeroDecodificheDelFile() = runTest {
    // Una foto da 5 MB con la miniatura EXIF, come quelle della fotocamera: due letture da pagina (testa e coda).
    val head = ImageFixtures.jpeg(64, 48, 0x4080C0, exif { thumbnailJpeg = thumb; model = "SM-S931B" }, null)
    for (size in listOf(3_000_000L, 5_000_000L, 8_000_000L, 12_000_000L)) {
      val file = SparseJpegBytes(size, head)
      val preview = FakePreviewDecoder()
      extractorWith(preview).extract(file, hints("a.jpg"))
      println("COSTO foto da $size byte: letti ${file.bytesRead} in ${file.reads} letture, decodifiche: miniatura ${preview.byteCalls}, file ${preview.streamCalls}")
      assertThat(file.bytesRead).isAtMost(200L * 1024)
      assertThat(file.reads).isAtMost(3)
      assertThat(preview.streamCalls).isEqualTo(0)
    }
  }

  @Test
  fun heicOrientamentoSoloDaIrotEImirMaiDallExif() = runTest {
    // L'EXIF dice 6 ma non c'e' nessuna proprieta' di rotazione: i decodificatori mostrano la foto cosi' com'e', e cosi' la dichiariamo.
    val plain = IsoFixtures.heic(IsoFixtures.HeicSpec().apply { this.exif = exif { orientation = 6 } })
    assertThat(read(plain, hints("a.heic", "image/heic")).metadata.orientation).isEqualTo(ExifOrientation.NORMAL)
    // Con irot = 1 (90 antiorari) vale quello, anche se l'EXIF dice altro.
    val rotated = IsoFixtures.heic(IsoFixtures.HeicSpec().apply { this.exif = exif { orientation = 6 }; irot = 1 })
    assertThat(read(rotated, hints("a.heic", "image/heic")).metadata.orientation).isEqualTo(ExifOrientation.ROTATE_270)
    // Il MediaStore non cambia niente per un HEIC.
    val h = ExtractHints("image/heic", "a.heic", "DCIM/Camera/", null, null, null, 90, null, ROME)
    assertThat(read(plain, h).metadata.orientation).isEqualTo(ExifOrientation.NORMAL)
  }

  @Test
  fun unFileGrandeSenzaMiniaturaNonHaSegnapostoMaRestaLeggibile() = runTest {
    val small = ImageFixtures.jpeg(64, 48, 0x4080C0, exif { }, null)
    val preview = FakePreviewDecoder()
    val big = SparseJpegBytes(DefaultMetadataExtractor.MAX_FULL_DECODE_BYTES + 1, small)
    val result = testExtractor(preview = preview).extract(big, hints("b.jpg")) as Extraction.Read
    assertThat(result.metadata.thumbHash).isNull()
    assertThat(preview.calls).isEqualTo(0)
  }

  @Test
  fun seIlDecodificatoreFallisceUnJpegPiccoloEFileRovinato_ManonUnHeic() = runTest {
    val preview = FakePreviewDecoder(fail = true)
    val extractor = testExtractor(preview = preview)
    val jpeg = ImageFixtures.jpeg(64, 48, 0x4080C0, exif { }, null)
    assertThat(extractor.extract(ByteArrayMediaBytes(jpeg), hints("a.jpg"))).isEqualTo(Extraction.Corrupt(ExtractCode.DECODE_FAILED))
    val png = ImageFixtures.pngBytes(ImageFixtures.solid(8, 8, 0))
    assertThat(extractor.extract(ByteArrayMediaBytes(png), hints("a.png", "image/png"))).isEqualTo(Extraction.Corrupt(ExtractCode.DECODE_FAILED))
    // Un HEIC o un DNG che il telefono non decodifica puo' mancare di un codec: resta in Libreria, senza segnaposto.
    val heic = IsoFixtures.heic(IsoFixtures.HeicSpec().apply { this.exif = exif { } })
    val result = extractor.extract(ByteArrayMediaBytes(heic), hints("a.heic", "image/heic")) as Extraction.Read
    assertThat(result.metadata.thumbHash).isNull()
  }

  @Test
  fun senzaDecodificatoreNienteSegnapostoENessunControllo() = runTest {
    val jpeg = ImageFixtures.jpeg(64, 48, 0x4080C0, exif { }, null)
    val result = read(jpeg, hints("a.jpg"), testExtractor(preview = null))
    assertThat(result.metadata.thumbHash).isNull()
  }

  // ---- date: vicini e fusi ------------------------------------------------------------------------------------------------

  @Test
  fun iViciniSiChiedonoSoloSeIlFileNonHaUnFusoProprio() = runTest {
    var asked = 0
    var lastModel: String? = null
    val provider = NeighborOffsetsProvider { _, model, _ -> asked++; lastModel = model; NeighborOffsets(-300) }
    val extractor = testExtractor(neighbors = provider)
    // Con il fuso scritto, con l'ora GPS, o con un GPS risolvibile in un luogo: non si chiede.
    read(ImageFixtures.jpeg(64, 48, 0, exif { offsetOriginal = "+01:00" }, null), hints("a.jpg"), extractor)
    read(ImageFixtures.jpeg(64, 48, 0, exif { gpsDate = "2024:03:12"; gpsTime = intArrayOf(9, 15, 30); latitude = 59.9; longitude = 10.7 }, null), hints("a.jpg"), extractor)
    read(ImageFixtures.jpeg(64, 48, 0, exif { latitude = 35.6895; longitude = 139.6917 }, null), hints("a.jpg"), extractor)
    assertThat(asked).isEqualTo(0)
    // Senza niente si chiede, con il modello della fotocamera, e il fuso dei vicini vince su quello del telefono.
    val result = read(ImageFixtures.jpeg(64, 48, 0, exif { model = "SM-S931B" }, null), hints("a.jpg"), extractor)
    assertThat(asked).isEqualTo(1)
    assertThat(lastModel).isEqualTo("SM-S931B")
    assertThat(result.metadata.captureTime!!.offsetMinutes).isEqualTo(-300)
    assertThat(result.metadata.captureTime!!.confidence).isEqualTo(CaptureTimeConfidence.OFFSET_ASSUMED)
  }

  @Test
  fun unProviderDeiViciniCheLanciaNonFermaLaLettura() = runTest {
    val extractor = testExtractor(neighbors = NeighborOffsetsProvider { _, _, _ -> error("database chiuso") })
    val result = read(ImageFixtures.jpeg(64, 48, 0, exif { }, null), hints("a.jpg"), extractor)
    assertThat(result.metadata.captureTime!!.offsetMinutes).isEqualTo(60)
  }

  @Test
  fun lOrologioDiRiferimentoGiudicaLeDateFuture() = runTest {
    val jpeg = ImageFixtures.jpeg(64, 48, 0, exif { dateOriginal = "2026:10:20 10:00:00"; offsetOriginal = "+02:00" }, null)
    val h = hints("IMG_20240312_101530.jpg")
    // Adesso e' il 13/10/2026: il 20/10 e' nel futuro -> si scarta, vale il nome.
    assertThat(read(jpeg, h).metadata.captureTime!!.source).isEqualTo(CaptureTimeSource.FILE_NAME)
    // Con l'orologio al 1/11/2026 e' una data buona.
    val later = testExtractor(nowMs = FixtureCatalog.ms("2026-11-01T00:00:00Z"))
    assertThat(read(jpeg, h, later).metadata.captureTime!!.source).isEqualTo(CaptureTimeSource.EXIF_WITH_OFFSET)
  }

  // ---- luoghi --------------------------------------------------------------------------------------------------------------

  @Test
  fun ilLuogoSiScriveSoloSeVicinoEIlGpsResta() = runTest {
    // A Firenze: il luogo c'e', con il dataset 1 del fake.
    val florence = read(ImageFixtures.jpeg(64, 48, 0, exif { latitude = 43.7696; longitude = 11.2558 }, null), hints("a.jpg"))
    assertThat(florence.metadata.place!!.geonameId).isEqualTo(3176959)
    assertThat(florence.metadata.place!!.dataset).isEqualTo(1)
    assertThat(florence.metadata.location!!.latitude).isWithin(1e-4).of(43.7696)
    // A 100 km dal luogo piu' vicino il fake lo trova ma e' troppo lontano per dirne il nome: nessun luogo, il GPS resta.
    val far = read(ImageFixtures.jpeg(64, 48, 0, exif { latitude = 44.7; longitude = 11.2558 }, null), hints("a.jpg"))
    assertThat(far.metadata.place).isNull()
    assertThat(far.metadata.location).isNotNull()
    // Senza dataset (null-object) nessun luogo e nessun fuso, ma il GPS si salva.
    val noPlaces = DefaultMetadataExtractor(NoReverseGeocoder, NoTimeZoneLookup, FakeVideoProbe(null), FakePreviewDecoder(), clock = { FixtureCatalog.NOW_MS }, blocking = Dispatchers.Unconfined)
    val none = read(ImageFixtures.jpeg(64, 48, 0, exif { latitude = 43.7696; longitude = 11.2558 }, null), hints("a.jpg"), noPlaces)
    assertThat(none.metadata.place).isNull()
    assertThat(none.metadata.location).isNotNull()
    assertThat(none.metadata.captureTime!!.offsetMinutes).isEqualTo(60)
  }

  @Test
  fun ilGpsNonSiScriveNelleStringheDiDiagnostica() = runTest {
    val result = read(ImageFixtures.jpeg(64, 48, 0, exif { latitude = 43.7696; longitude = 11.2558 }, null), hints("Foto privata.jpg"))
    val text = result.toString() + result.metadata.toString() + (result.groupHint?.toString() ?: "")
    assertThat(text).doesNotContain("43.76")
    assertThat(text).doesNotContain("11.25")
    assertThat(result.metadata.place.toString()).doesNotContain("3176959")
    assertThat(GeoPoint(1.0, 2.0).toString()).doesNotContain("1.0")
  }

  // ---- video ---------------------------------------------------------------------------------------------------------------

  private fun videoFacts(utcMs: Long? = null, gps: GeoPoint? = null, rotation: Int = 0, hdr: Boolean = false, durationMs: Long = 4000) =
    VideoFacts(durationMs, rotation, utcMs, gps, hdr, "video/mp4")

  @Test
  fun unVideoPrendeDurataRotazioneHdrDataEDLuogoDallaSonda() = runTest {
    val mp4 = IsoFixtures.mp4(IsoFixtures.Mp4Spec())
    val facts = videoFacts(FixtureCatalog.ms("2024-07-04T12:00:00Z"), GeoPoint(35.6895, 139.6917), rotation = 90, hdr = true)
    val result = read(mp4, hints("MOV_0001.mp4", "video/mp4"), testExtractor(probe = FakeVideoProbe(facts)))
    assertThat(result.kind).isEqualTo(MediaKind.VIDEO)
    assertThat(result.metadata.durationMillis).isEqualTo(4000L)
    assertThat(result.metadata.orientation).isEqualTo(ExifOrientation.ROTATE_90)
    assertThat(result.metadata.traits).containsExactly(MediaTrait.HDR_VIDEO)
    val time = result.metadata.captureTime!!
    assertThat(time.source).isEqualTo(CaptureTimeSource.VIDEO_UTC)
    assertThat(time.offsetMinutes).isEqualTo(540) // il fuso del luogo del video (E-M14)
    assertThat(PLACES.label(result.metadata.place!!)).isEqualTo("Tokyo")
    assertThat(result.motion).isNull()
    assertThat(result.groupHint).isNull()
    assertThat(result.metadata.thumbHash).isNull()
  }

  @Test
  fun senzaSondaLaDurataEL_OrientamentoVengonoDalMediaStore() = runTest {
    val mp4 = IsoFixtures.mp4(IsoFixtures.Mp4Spec())
    val h = ExtractHints("video/mp4", "VID_20240312_101534.mp4", "DCIM/Camera/", null, 1920, 1080, 270, 7500, ROME)
    val result = read(mp4, h)
    assertThat(result.metadata.durationMillis).isEqualTo(7500L)
    assertThat(result.metadata.orientation).isEqualTo(ExifOrientation.ROTATE_270)
    assertThat(result.metadata.width).isEqualTo(1920)
    assertThat(result.metadata.height).isEqualTo(1080)
  }

  @Test
  fun lAltroFormatoDelContenitoreEMovEMkv() = runTest {
    val mov = IsoFixtures.mp4(IsoFixtures.Mp4Spec().apply { brand = "qt  " })
    assertThat(read(mov, hints("a.mov", "video/quicktime")).metadata.mimeType).isEqualTo("video/quicktime")
    val mkv = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0x9F.toByte()) + ByteArray(200)
    val result = read(mkv, hints("a.mkv", "video/x-matroska"))
    assertThat(result.kind).isEqualTo(MediaKind.VIDEO)
    assertThat(result.metadata.mimeType).isEqualTo("video/x-matroska")
  }

  @Test
  fun unVideoTroncatoNonSiFidaDellaSonda() = runTest {
    val broken = IsoFixtures.mp4(IsoFixtures.Mp4Spec().apply { includeMoov = false })
    val probe = FakeVideoProbe(videoFacts())
    val result = testExtractor(probe = probe).extract(ByteArrayMediaBytes(broken), hints("a.mp4", "video/mp4"))
    assertThat(result).isEqualTo(Extraction.Corrupt(ExtractCode.TRUNCATED))
  }

  // ---- dettagli EXIF a richiesta --------------------------------------------------------------------------------------------

  @Test
  fun idettagliExifSiLeggonoARichiestaEUnFileSenzaExifDaUnOggettoVuoto() = runTest {
    val reader = DefaultExifDetailsReader()
    val full = ImageFixtures.jpeg(
      64, 48, 0,
      exif { make = "samsung"; model = "SM-S931B"; lens = "Wide"; fNumber = 1.8; exposure = intArrayOf(1, 250); iso = 100; focalMm = 6.3; flash = 0 },
      null,
    )
    val d = reader.exifDetails(ByteArrayMediaBytes(full))
    assertThat(d.make).isEqualTo("samsung")
    assertThat(d.model).isEqualTo("SM-S931B")
    assertThat(d.lens).isEqualTo("Wide")
    assertThat(d.fNumber).isEqualTo(1.8)
    assertThat(d.exposureTime).isEqualTo("1/250")
    assertThat(d.iso).isEqualTo(100)
    assertThat(d.focalLengthMm).isEqualTo(6.3)
    assertThat(d.flashFired).isFalse()
    // HEIC, PNG, WebP e DNG leggono lo stesso blocco.
    val heic = IsoFixtures.heic(IsoFixtures.HeicSpec().apply { this.exif = exif { model = "H" } })
    assertThat(reader.exifDetails(ByteArrayMediaBytes(heic)).model).isEqualTo("H")
    val png = ImageFixtures.png(ImageFixtures.solid(8, 8, 0), exif { model = "P" }, null)
    assertThat(reader.exifDetails(ByteArrayMediaBytes(png)).model).isEqualTo("P")
    val webp = ImageFixtures.webp(8, 8, -1, exif { model = "W" }, null)
    assertThat(reader.exifDetails(ByteArrayMediaBytes(webp)).model).isEqualTo("W")
    // Nessun EXIF, niente file, file rovinato: vuoto, senza errori.
    assertThat(reader.exifDetails(ByteArrayMediaBytes(ImageFixtures.jpeg(8, 8, 0))).model).isNull()
    assertThat(reader.exifDetails(ByteArrayMediaBytes(ByteArray(0))).model).isNull()
    assertThat(reader.exifDetails(ByteArrayMediaBytes("non un'immagine".toByteArray())).make).isNull()
    assertThat(reader.exifDetails(ByteArrayMediaBytes(full.copyOf(40))).make).isNull()
  }
}
