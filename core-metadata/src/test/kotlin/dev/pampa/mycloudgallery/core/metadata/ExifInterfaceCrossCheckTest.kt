package dev.pampa.mycloudgallery.core.metadata

import androidx.exifinterface.media.ExifInterface
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.metadata.contract.ExtractHints
import dev.pampa.mycloudgallery.core.metadata.contract.Extraction
import dev.pampa.mycloudgallery.core.metadata.testing.ByteArrayMediaBytes
import dev.pampa.mycloudgallery.core.model.CaptureTimeConfidence
import dev.pampa.mycloudgallery.core.model.CaptureTimeSource
import dev.pampa.mycloudgallery.fixtures.ExifWriter
import dev.pampa.mycloudgallery.fixtures.FixtureCatalog
import dev.pampa.mycloudgallery.fixtures.ImageFixtures
import java.time.LocalDateTime
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * L'oracolo indipendente: ExifInterface di AndroidX. Se il nostro scrittore (le fixture) e il nostro lettore sbagliassero allo
 * stesso modo, fra loro andrebbero d'accordo e nessun test se ne accorgerebbe: qui ciascuno e' confrontato con ExifInterface.
 */
@RunWith(RobolectricTestRunner::class)
class ExifInterfaceCrossCheckTest {

  @get:Rule
  val tmp = TemporaryFolder()

  private fun hints(name: String) = ExtractHints("image/jpeg", name, "DCIM/Camera/", null, null, null, null, null, ROME)

  @Test
  fun ilNostroScrittoreProduceUnExifCheExifInterfaceLegge() {
    val spec = ExifWriter.Spec().apply {
      make = "samsung"; model = "SM-S931B"; lens = "Wide 1.8"; orientation = 6
      dateOriginal = "2024:03:12 10:15:30"; offsetOriginal = "+01:00"; subSecOriginal = "123"
      latitude = 43.7696; longitude = 11.2558; altitude = 123.5
      gpsDate = "2024:03:12"; gpsTime = intArrayOf(9, 15, 28)
      fNumber = 1.8; exposure = intArrayOf(1, 250); iso = 100; focalMm = 6.3; flash = 1
      thumbnailJpeg = ImageFixtures.exifThumbnail(0x203040)
    }
    for (big in listOf(false, true)) {
      spec.bigEndian = big
      val file = tmp.newFile("w$big.jpg")
      file.writeBytes(ImageFixtures.jpeg(64, 48, 0x4080C0, spec, null))
      val ei = ExifInterface(file.path)
      assertThat(ei.getAttribute(ExifInterface.TAG_MAKE)).isEqualTo("samsung")
      assertThat(ei.getAttribute(ExifInterface.TAG_MODEL)).isEqualTo("SM-S931B")
      assertThat(ei.getAttribute(ExifInterface.TAG_LENS_MODEL)).isEqualTo("Wide 1.8")
      assertThat(ei.getAttributeInt(ExifInterface.TAG_ORIENTATION, -1)).isEqualTo(6)
      assertThat(ei.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)).isEqualTo("2024:03:12 10:15:30")
      assertThat(ei.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)).isEqualTo("+01:00")
      assertThat(ei.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL)).isEqualTo("123")
      assertThat(ei.getAttribute(ExifInterface.TAG_GPS_DATESTAMP)).isEqualTo("2024:03:12")
      assertThat(ei.getAttribute(ExifInterface.TAG_GPS_TIMESTAMP)).isEqualTo("09:15:28")
      val latLong = ei.latLong!!
      assertThat(latLong[0]).isWithin(1e-4).of(43.7696)
      assertThat(latLong[1]).isWithin(1e-4).of(11.2558)
      assertThat(ei.getAltitude(0.0)).isWithin(0.01).of(123.5)
      assertThat(ei.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)).isWithin(0.001).of(1.8)
      assertThat(ei.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)).isWithin(1e-6).of(0.004)
      assertThat(ei.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0)).isEqualTo(100)
      assertThat(ei.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)).isWithin(0.001).of(6.3)
      assertThat(ei.getAttributeInt(ExifInterface.TAG_FLASH, -1)).isEqualTo(1)
      assertThat(ei.hasThumbnail()).isTrue()
    }
  }

  @Test
  fun ilNostroLettoreLeggeCioCheExifInterfaceScrive() = runTest {
    val file = tmp.newFile("r.jpg")
    file.writeBytes(ImageFixtures.jpeg(64, 48, 0x4080C0))
    ExifInterface(file.path).apply {
      setAttribute(ExifInterface.TAG_MAKE, "Acme")
      setAttribute(ExifInterface.TAG_MODEL, "X1")
      setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_270.toString())
      setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2023:12:25 08:30:15")
      setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "-05:00")
      setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, "456")
      setLatLong(40.7143, -74.0060)
      setAttribute(ExifInterface.TAG_F_NUMBER, "28/10")
      setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, "400")
      saveAttributes()
    }
    val bytes = file.readBytes()
    val result = testExtractor().extract(ByteArrayMediaBytes(bytes), hints("a.jpg")) as Extraction.Read
    val time = result.metadata.captureTime!!
    assertThat(time.source).isEqualTo(CaptureTimeSource.EXIF_WITH_OFFSET)
    assertThat(time.confidence).isEqualTo(CaptureTimeConfidence.EXACT)
    assertThat(time.offsetMinutes).isEqualTo(-300)
    assertThat(time.localDateTime).isEqualTo(LocalDateTime.of(2023, 12, 25, 8, 30, 15, 456_000_000))
    assertThat(result.metadata.orientation.exif).isEqualTo(8)
    assertThat(result.metadata.location!!.latitude).isWithin(1e-4).of(40.7143)
    assertThat(result.metadata.location!!.longitude).isWithin(1e-4).of(-74.0060)
    assertThat(PLACES.label(result.metadata.place!!)).isEqualTo("New York")
    assertThat(result.groupHint!!.cameraModel).isEqualTo("X1")
    assertThat(result.metadata.width).isEqualTo(64)
    assertThat(result.metadata.height).isEqualTo(48)
    val details = dev.pampa.mycloudgallery.core.metadata.extract.DefaultExifDetailsReader().exifDetails(ByteArrayMediaBytes(bytes))
    assertThat(details.make).isEqualTo("Acme")
    assertThat(details.fNumber).isEqualTo(2.8)
    assertThat(details.iso).isEqualTo(400)
  }

  @Test
  fun ilNostroLettoreLeggeAncheLaOraGpsDiExifInterface() = runTest {
    val file = tmp.newFile("g.jpg")
    file.writeBytes(ImageFixtures.jpeg(64, 48, 0x4080C0))
    ExifInterface(file.path).apply {
      setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2024:03:12 15:45:00")
      setLatLong(28.6139, 77.2090)
      setAttribute(ExifInterface.TAG_GPS_DATESTAMP, "2024:03:12")
      setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, "10:15:02")
      saveAttributes()
    }
    val result = testExtractor().extract(ByteArrayMediaBytes(file.readBytes()), hints("a.jpg")) as Extraction.Read
    assertThat(result.metadata.captureTime!!.offsetMinutes).isEqualTo(330)
    assertThat(result.metadata.captureTime!!.confidence).isEqualTo(CaptureTimeConfidence.EXACT)
  }

  @Test
  fun lePngEWebpDelleFixtureLeggeAncheExifInterface() {
    val spec = ExifWriter.Spec().apply { model = "SM-S931B"; dateOriginal = "2024:03:12 10:15:30"; offsetOriginal = "+01:00" }
    val png = tmp.newFile("a.png").also { it.writeBytes(ImageFixtures.png(ImageFixtures.solid(30, 20, 0x112233), spec, null)) }
    val webp = tmp.newFile("a.webp").also { it.writeBytes(ImageFixtures.webp(30, 20, -0xEEDDCD, spec, null)) }
    for (f in listOf(png, webp)) {
      val ei = ExifInterface(f.path)
      assertThat(ei.getAttribute(ExifInterface.TAG_MODEL)).isEqualTo("SM-S931B")
      assertThat(ei.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)).isEqualTo("2024:03:12 10:15:30")
    }
  }

  @Test
  fun tuttiIJpegDelCatalogoHannoLaStessaDataPerExifInterfaceEPerNoi() = runTest {
    for (f in FixtureCatalog.all()) {
      if (f.mime != "image/jpeg" || f.expect.result != "read" || f.expect.dateSource != "EXIF_WITH_OFFSET") continue
      if (f.name.startsWith("exif-04") || f.name.startsWith("exif-05") || f.name.startsWith("exif-06") || f.name.startsWith("exif-12")) continue
      val file = tmp.newFile("c-${f.name}")
      file.writeBytes(f.bytes())
      val ei = ExifInterface(file.path)
      val original = ei.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: continue
      val expected = f.expect.local!!.replace('T', ' ').replace('-', ':').take(19)
      assertThat(original.take(19)).isEqualTo(expected)
    }
  }
}
