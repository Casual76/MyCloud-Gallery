package dev.pampa.mycloudgallery.core.metadata.write

import androidx.exifinterface.media.ExifInterface
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.metadata.contract.GeoChange
import dev.pampa.mycloudgallery.core.metadata.contract.MetadataChange
import dev.pampa.mycloudgallery.core.metadata.contract.WriteFailure
import dev.pampa.mycloudgallery.core.metadata.contract.WriteOutcome
import dev.pampa.mycloudgallery.core.metadata.contract.WriteUnsupported
import dev.pampa.mycloudgallery.core.metadata.extract.AndroidPreviewDecoder
import dev.pampa.mycloudgallery.core.metadata.extract.DefaultMetadataExtractor
import dev.pampa.mycloudgallery.core.metadata.testing.FakeVideoProbe
import dev.pampa.mycloudgallery.core.model.ExifOrientation
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.places.testing.FakePlaces
import dev.pampa.mycloudgallery.fixtures.ExifWriter
import dev.pampa.mycloudgallery.fixtures.ImageFixtures
import java.io.File
import java.security.MessageDigest
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Fase 5B, P8 (D19, Q6 (a), L40): lo scrittore vero delle correzioni ([ExifMetadataWriter], `ExifInterface`) e la rilettura che decide se
 * una copia corretta vale ([ExtractingFileCheck]: l'estrattore dell'app e il decodificatore del sistema, grafica nativa di Robolectric).
 * - **JPEG, PNG e WebP** corretti (data e fuso, luogo messo o tolto, rotazione) si rileggono con l'estrattore dell'app con i valori nuovi
 *   e si decodificano; `ExifInterface` (l'oracolo indipendente) legge gli stessi valori; il sorgente resta byte per byte com'era;
 * - HEIC, RAW, video (Q6 (a)) e un "JPEG" che dentro e' altro **non si scrivono** e niente si crea;
 * - una destinazione che c'e' gia' non si tocca; un guasto non lascia niente;
 * - una copia **rovinata** dopo la scrittura (troncata, pixel distrutti) non passa la rilettura: le correzioni la scartano.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExifMetadataWriterTest {

  @get:Rule val folder = TemporaryFolder()

  private val writer = ExifMetadataWriter()
  private val rome: ZoneId = ZoneId.of("Europe/Rome")
  private val places = FakePlaces()
  private val check = ExtractingFileCheck(
    extractor = DefaultMetadataExtractor(
      geocoder = places,
      zones = places,
      videoProbe = FakeVideoProbe(null),
      previewDecoder = AndroidPreviewDecoder(),
      clock = { 1_800_000_000_000L },
      blocking = Dispatchers.Unconfined,
    ),
    decoder = AndroidPreviewDecoder(),
    zone = rome,
  )

  private val oldSpec = ExifWriter.Spec().apply {
    make = "samsung"; model = "SM-S931B"; orientation = 1
    dateOriginal = "2024:03:12 10:15:30"; offsetOriginal = "+01:00"; subSecOriginal = "123"
    latitude = 45.4642; longitude = 9.19; altitude = 120.0
    gpsDate = "2024:03:12"; gpsTime = intArrayOf(9, 15, 30)
  }

  /** I tre formati che si scrivono (Q6 (a)), con EXIF di partenza: data, luogo e orientamento. */
  private fun sources(): Map<String, Pair<String, ByteArray>> = mapOf(
    "jpeg" to ("image/jpeg" to ImageFixtures.jpeg(ImageFixtures.noise(96, 64, 3), oldSpec, null)),
    "png" to ("image/png" to ImageFixtures.png(ImageFixtures.noise(96, 64, 4), oldSpec, null)),
    "webp" to ("image/webp" to ImageFixtures.webp(96, 64, -0xBF3F80, oldSpec, null)),
  )

  private fun file(name: String, bytes: ByteArray): File = folder.newFile(name).apply { writeBytes(bytes) }

  private fun absent(name: String): File = File(folder.root, name)

  private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toList()

  /** 15/10/2026 14.32.05,250 a Oslo (UTC+2). */
  private val newDate = MetadataChange(takenAtUtcMillis = 1_792_067_525_250L, offsetMinutes = 120)
  private val newPlace = MetadataChange(geo = GeoChange.Set(GeoPoint(59.9139, 10.7522)))
  private val noPlace = MetadataChange(geo = GeoChange.Clear)
  private val rotated = MetadataChange(orientation = ExifOrientation.ROTATE_90)
  private val everything = MetadataChange(
    takenAtUtcMillis = 1_792_067_525_250L,
    offsetMinutes = 120,
    geo = GeoChange.Set(GeoPoint(59.9139, 10.7522, 23.0)),
    orientation = ExifOrientation.ROTATE_270,
  )

  @Test
  fun jpegPngEWebpCorrettiSiRileggonoConIValoriNuoviESiDecodificano() = runTest {
    for ((format, source) in sources()) {
      val (mime, bytes) = source
      for ((name, change) in listOf("data" to newDate, "luogo" to newPlace, "senza luogo" to noPlace, "rotazione" to rotated, "tutto" to everything)) {
        val label = "$format, $name"
        val src = file("src-$format-${name.replace(' ', '_')}.$format", bytes)
        val dst = absent("dst-$format-${name.replace(' ', '_')}.$format")
        val outcome = writer.apply(src, dst, change)
        assertWithMessage(label).that(outcome).isInstanceOf(WriteOutcome.Written::class.java)
        assertWithMessage("$label: lunghezza dichiarata").that((outcome as WriteOutcome.Written).bytes).isEqualTo(dst.length())
        // Il sorgente non si tocca mai.
        assertWithMessage("$label: sorgente intatto").that(sha(src.readBytes())).isEqualTo(sha(bytes))
        // La rilettura dell'app: valori nuovi e immagine decodificabile.
        assertWithMessage("$label: rilettura").that(check.check(dst, mime, change)).isEqualTo(WrittenCheck.VALID)
        // L'oracolo indipendente legge le stesse cose.
        val exif = ExifInterface(dst)
        if (change.takenAtUtcMillis != null) {
          assertWithMessage("$label: data").that(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)).isEqualTo("2026:10:15 14:32:05")
          assertWithMessage("$label: fuso").that(exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)).isEqualTo("+02:00")
          assertWithMessage("$label: millisecondi").that(exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL)).isEqualTo("250")
          // L'ora GPS si sposta con la data (l'estrattore la usa per il fuso).
          assertWithMessage("$label: data GPS").that(exif.getAttribute(ExifInterface.TAG_GPS_DATESTAMP)).isEqualTo("2026:10:15")
        }
        when (val geo = change.geo) {
          GeoChange.Keep -> assertWithMessage("$label: luogo di prima").that(exif.latLong!![0]).isWithin(1e-4).of(45.4642)
          GeoChange.Clear -> {
            assertWithMessage("$label: nessun luogo").that(exif.latLong).isNull()
            assertWithMessage("$label: nessuna altitudine").that(exif.getAttribute(ExifInterface.TAG_GPS_ALTITUDE)).isNull()
          }
          is GeoChange.Set -> {
            assertWithMessage("$label: latitudine").that(exif.latLong!![0]).isWithin(1e-5).of(geo.point.latitude)
            assertWithMessage("$label: longitudine").that(exif.latLong!![1]).isWithin(1e-5).of(geo.point.longitude)
            // L'altitudine del luogo di prima non resta attaccata a quello nuovo.
            if (geo.point.altitudeMeters == null) assertWithMessage("$label: altitudine").that(exif.getAttribute(ExifInterface.TAG_GPS_ALTITUDE)).isNull()
          }
        }
        change.orientation?.let { assertWithMessage("$label: orientamento").that(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)).isEqualTo(it.exif) }
        // Il resto dell'EXIF resta: e' la stessa foto.
        assertWithMessage("$label: marca").that(exif.getAttribute(ExifInterface.TAG_MAKE)).isEqualTo("samsung")
      }
    }
  }

  @Test
  fun iFormatiFuoriDallaQ6NonSiScrivonoENienteSiCrea() {
    val notWritable = buildList {
      // Un "JPEG" che dentro e' testo: il tipo si legge dai byte, mai dal nome.
      add("finto.jpg" to "non sono un'immagine, solo un nome".toByteArray())
      // Un MP4 (video) e un TIFF/DNG (RAW): fuori dalla Q6.
      add("video.mp4" to byteArrayOf(0, 0, 0, 0x18) + "ftypisom".toByteArray() + ByteArray(16))
      add("raw.dng" to byteArrayOf(0x49, 0x49, 0x2A, 0x00, 0x08, 0, 0, 0) + ByteArray(32))
      // Un HEIC: la marca `heic` nel `ftyp`.
      add("foto.heic" to byteArrayOf(0, 0, 0, 0x18) + "ftypheic".toByteArray() + "mif1heic".toByteArray() + ByteArray(16))
    }
    for ((name, bytes) in notWritable) {
      val src = file(name, bytes)
      val dst = absent("out-$name")
      assertWithMessage(name).that(writer.apply(src, dst, newDate)).isEqualTo(WriteOutcome.Unsupported(WriteUnsupported.FORMAT))
      assertWithMessage("$name: niente creato").that(dst.exists()).isFalse()
      assertWithMessage("$name: sorgente intatto").that(src.readBytes().toList()).isEqualTo(bytes.toList())
    }
    assertThat(writer.canWrite("image/jpeg")).isTrue()
    assertThat(writer.canWrite("IMAGE/PNG")).isTrue()
    assertThat(writer.canWrite("image/webp")).isTrue()
    for (mime in listOf("image/heic", "image/heif", "image/x-adobe-dng", "video/mp4", "video/quicktime", "image/gif")) {
      assertWithMessage(mime).that(writer.canWrite(mime)).isFalse()
    }
  }

  @Test
  fun unaDestinazioneCheCeNonSiToccaEUnCambiamentoVuotoNonScriveNiente() {
    val (_, bytes) = sources().getValue("jpeg")
    val src = file("a.jpg", bytes)
    val dst = file("gia-li.jpg", byteArrayOf(1, 2, 3))
    assertThat(writer.apply(src, dst, newDate)).isEqualTo(WriteOutcome.Failed(WriteFailure.DESTINATION_EXISTS))
    assertThat(dst.readBytes().toList()).isEqualTo(listOf<Byte>(1, 2, 3))
    val other = absent("vuoto.jpg")
    assertThat(writer.apply(src, other, MetadataChange())).isEqualTo(WriteOutcome.Unsupported(WriteUnsupported.NOTHING_TO_DO))
    assertThat(other.exists()).isFalse()
    // Un sorgente che non c'e' non si legge.
    assertThat(writer.apply(absent("assente.jpg"), absent("o.jpg"), newDate)).isEqualTo(WriteOutcome.Failed(WriteFailure.SOURCE_UNREADABLE))
    assertThat(absent("o.jpg").exists()).isFalse()
  }

  @Test
  fun unJpegConLeIntestazioniRotteNonLasciaNiente() {
    // L'inizio di un JPEG (SOI) e poi spazzatura: il tipo e' giusto ma ExifInterface non lo legge, o la scrittura non torna.
    val broken = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0x7F, 0x7F) + ByteArray(200) { 0x13 }
    val src = file("rotto.jpg", broken)
    val dst = absent("rotto-out.jpg")
    val outcome = writer.apply(src, dst, newDate)
    assertThat(outcome).isNotInstanceOf(WriteOutcome.Written::class.java)
    assertThat(dst.exists()).isFalse()
    assertThat(src.readBytes().toList()).isEqualTo(broken.toList())
  }

  /** D19: una copia rovinata **dopo** la scrittura (un disco che tronca, un editor che sbaglia) non passa la rilettura e si scarta. */
  @Test
  fun unaCopiaRovinataNonPassaLaRilettura() = runTest {
    for ((format, source) in sources()) {
      val (mime, bytes) = source
      val src = file("buona-$format.$format", bytes)
      val dst = absent("corretta-$format.$format")
      assertThat(writer.apply(src, dst, newDate)).isInstanceOf(WriteOutcome.Written::class.java)
      assertThat(check.check(dst, mime, newDate)).isEqualTo(WrittenCheck.VALID)
      // Troncata a 10 byte: niente si legge.
      val truncated = file("troncata-$format.$format", dst.readBytes().copyOf(10))
      assertWithMessage("$format troncato").that(check.check(truncated, mime, newDate)).isNotEqualTo(WrittenCheck.VALID)
      // Una correzione che non e' arrivata nel file (si rilegge la copia scritta con un'altra data): non vale.
      assertWithMessage("$format con un'altra data").that(check.check(dst, mime, MetadataChange(takenAtUtcMillis = 1_000_000_000_000L)))
        .isEqualTo(WrittenCheck.CHANGE_MISSING)
    }
    // Un JPEG con le intestazioni a posto ma i dati dell'immagine tagliati via (resta solo la testa con l'EXIF corretto).
    val (_, bytes) = sources().getValue("jpeg")
    val dst = absent("corretta-tagliata.jpg")
    writer.apply(file("buona.jpg", bytes), dst, newDate)
    val written = dst.readBytes()
    val sof = (2 until written.size - 1).first { (written[it].toInt() and 0xFF) == 0xFF && (written[it + 1].toInt() and 0xFF) in setOf(0xC0, 0xC2) }
    val headOnly = file("solo-testa.jpg", written.copyOf(sof))
    assertThat(check.check(headOnly, "image/jpeg", newDate)).isNotEqualTo(WrittenCheck.VALID)
  }

  @Test
  fun ilFusoSiLeggeESiScriveNelFormatoDellExif() {
    assertThat(ExifMetadataWriter.parseOffset("+01:00")).isEqualTo(60)
    assertThat(ExifMetadataWriter.parseOffset("-05:30")).isEqualTo(-330)
    assertThat(ExifMetadataWriter.parseOffset("+15:00")).isNull()
    assertThat(ExifMetadataWriter.parseOffset("1:00")).isNull()
    assertThat(ExifMetadataWriter.parseOffset(null)).isNull()
    assertThat(ExifMetadataWriter.formatOffset(120)).isEqualTo("+02:00")
    assertThat(ExifMetadataWriter.formatOffset(-330)).isEqualTo("-05:30")
    assertThat(ExifMetadataWriter.formatOffset(0)).isEqualTo("+00:00")
  }
}
