package dev.pampa.mycloudgallery.core.metadata.write

import androidx.exifinterface.media.ExifInterface
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.metadata.contract.GeoChange
import dev.pampa.mycloudgallery.core.metadata.contract.MetadataChange
import dev.pampa.mycloudgallery.core.metadata.contract.WriteOutcome
import dev.pampa.mycloudgallery.core.metadata.contract.WriteUnsupported
import dev.pampa.mycloudgallery.core.metadata.extract.AndroidPreviewDecoder
import dev.pampa.mycloudgallery.core.metadata.strip.LocationFixtures
import dev.pampa.mycloudgallery.core.metadata.strip.LocationTraces
import dev.pampa.mycloudgallery.core.metadata.strip.StripFixtures
import dev.pampa.mycloudgallery.fixtures.Bytes
import dev.pampa.mycloudgallery.fixtures.ImageFixtures
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Correzioni 5B (R12): "togli il luogo" ([ExifMetadataWriter] con `GeoChange.Clear`) toglie la posizione **anche** dall'XMP (in qualunque spazio dei
 * nomi), dai testi dei PNG, dall'APP13 e dalla parte video di una motion photo, e la copia si ricontrolla con lo stesso rilevatore indipendente delle
 * condivisioni. Prima restavano l'XMP `exif:GPS*`, la nota del produttore e il `©xyz` del video. Se il luogo non si puo' togliere, la correzione
 * non si scrive nel file e niente resta.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TogliIlLuogoTest {

  @get:Rule val folder = TemporaryFolder()

  private val writer = ExifMetadataWriter()
  private val clear = MetadataChange(geo = GeoChange.Clear)

  private fun file(name: String, bytes: ByteArray): File = File(folder.root, name).apply { writeBytes(bytes) }

  private fun clean(name: String, bytes: ByteArray): File {
    val src = file("src-$name", bytes)
    assertWithMessage("$name: prima").that(LocationTraces.scan(src).traces).isNotEmpty()
    val dst = File(folder.root, "dst-$name")

    val outcome = writer.apply(src, dst, clear)

    assertWithMessage("$name: $outcome").that(outcome).isInstanceOf(WriteOutcome.Written::class.java)
    assertWithMessage("$name: dopo ${LocationTraces.scan(dst)}").that(LocationTraces.scan(dst).clean).isTrue()
    assertThat(ExifInterface(dst).latLong).isNull()
    assertThat(AndroidPreviewDecoder().decode(dst.readBytes(), 64)).isNotNull()
    return dst
  }

  @Test
  fun jpegLXmpPerdeIlLuogoEIlRestoResta() {
    val dst = clean("a.jpg", StripFixtures.jpeg())
    val bytes = dst.readBytes()
    assertThat(StripFixtures.contains(bytes, "GPSLatitude")).isFalse()
    // Una correzione non e' una condivisione: l'XMP resta (senza il luogo), e cosi' la fotocamera.
    assertThat(StripFixtures.contains(bytes, "dc:creator")).isTrue()
    assertThat(ExifInterface(dst).getAttribute(ExifInterface.TAG_MODEL)).isEqualTo("SM-S931B")
    // Tutto il blocco GPS se ne va, anche l'ora GPS.
    assertThat(ExifInterface(dst).getAttribute(ExifInterface.TAG_GPS_TIMESTAMP)).isNull()
    assertThat(ExifInterface(dst).getAttribute(ExifInterface.TAG_GPS_DATESTAMP)).isNull()
  }

  @Test
  fun lXmpDiUnDroneEQuelloEstesoEDiPhotoshop() {
    clean("dji.jpg", StripFixtures.jpeg(exif = StripFixtures.exifWithGps(), xmp = LocationFixtures.djiXmp())).let {
      assertThat(StripFixtures.contains(it.readBytes(), "GpsLatitude")).isFalse()
      assertThat(StripFixtures.contains(it.readBytes(), "tiff:Make")).isTrue()
    }
    clean("ext.jpg", LocationFixtures.extendedXmpPhoto())
    clean("ps.jpg", LocationFixtures.app13Photo())
  }

  @Test
  fun pngWebpEMotionPhoto() {
    clean("t.png", LocationFixtures.textPng())
    clean("a.png", StripFixtures.png())
    clean("a.webp", StripFixtures.webp())
    val motion = clean("m.jpg", LocationFixtures.motionPhotoWithVideoGps())
    // La motion photo resta una motion photo: il video c'e', con video e audio, senza la traccia della posizione.
    val out = motion.readBytes()
    val start = StripFixtures.indexOf(out, "ftyp".toByteArray()) - 4
    assertThat(LocationFixtures.handlers(out, start)).containsExactly("vide", "soun").inOrder()
    assertThat(StripFixtures.contains(out, LocationFixtures.APPLE_ISO6709)).isFalse()
  }

  @Test
  fun seIlLuogoNonSiPuoTogliereLaCorrezioneNonSiScriveNelFile() {
    // Un XMP in UTF-16 con il luogo: non si riscrive sul posto.
    val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "<x:xmpmeta><rdf:Description exif:GPSLatitude=\"45,27N\"/></x:xmpmeta>".toByteArray(Charsets.UTF_16LE)
    val base = ImageFixtures.jpeg(ImageFixtures.noise(64, 48, 2), StripFixtures.exifWithGps(), null)
    val jpeg = LocationFixtures.insertJpegSegments(base, LocationFixtures.app1(Bytes().ascii("http://ns.adobe.com/xap/1.0/").u8(0).raw(utf16).toBytes()))
    val src = file("u.jpg", jpeg)
    val dst = File(folder.root, "out-u.jpg")

    assertThat(writer.apply(src, dst, clear)).isEqualTo(WriteOutcome.Unsupported(WriteUnsupported.FORMAT))

    assertThat(dst.exists()).isFalse()
  }
}
