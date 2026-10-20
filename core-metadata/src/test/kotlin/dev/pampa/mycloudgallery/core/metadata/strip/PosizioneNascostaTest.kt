package dev.pampa.mycloudgallery.core.metadata.strip

import android.media.MediaMetadataRetriever
import androidx.exifinterface.media.ExifInterface
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.metadata.contract.StripOutcome
import dev.pampa.mycloudgallery.core.metadata.contract.WriteFailure
import dev.pampa.mycloudgallery.core.metadata.extract.AndroidPreviewDecoder
import dev.pampa.mycloudgallery.fixtures.ImageFixtures
import java.io.File
import java.io.IOException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowMediaMetadataRetriever

/**
 * Correzioni 5B (R1, R2, docs/review/5b-fase5b.md): la posizione **dove lo stripper di prima non guardava**. Per ogni file di
 * [LocationFixtures] (DJI, `camm`, `gpmd`, Apple `mebx`, XMP esteso spezzato, APP13, PNG `tEXt`/`iTXt`, motion photo con la posizione nel video,
 * HEIC):
 *  1. il rilevatore indipendente ([LocationTraces]) trova la posizione nel file **non pulito** (altrimenti il test non proverebbe niente);
 *  2. la copia condivisa esce `Stripped` e il rilevatore non ci trova piu' niente; si decodifica (foto) o ha ancora il suo `moov` con le tracce
 *     video e audio, con i campioni identici byte per byte (video); le coordinate scritte in binario non ci sono piu' in nessun punto.
 * Con lo stripper di prima questi test erano rossi: i file uscivano `Stripped` con la posizione dentro, o la condivisione si fermava (vedi
 * `docs/decisioni.md`, "Correzioni 5B: posizione e Condividi").
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PosizioneNascostaTest {

  @get:Rule val folder = TemporaryFolder()

  private val stripper = DefaultLocationStripper(StripFixtures.rechecks(), heic = LeakyHeicToJpeg())

  private fun file(name: String, bytes: ByteArray): File = StripFixtures.write(folder.root, name, bytes)

  /** Il video si apre e dura 3 secondi per `MediaMetadataRetriever` (su JVM c'e' il suo shadow, che non legge il file). */
  private fun playable(file: File): File {
    ShadowMediaMetadataRetriever.addMetadata(file.path, MediaMetadataRetriever.METADATA_KEY_DURATION, "3000")
    return file
  }

  private fun scan(bytes: ByteArray): TraceScan = LocationTraces.scan(file("s-${System.nanoTime()}", bytes))

  // --- 1. Il rilevatore vede la posizione in ogni file non pulito ------------------------------------------------------------------

  @Test
  fun ilRilevatoreTrovaLaPosizioneInOgniFixtureNonPulita() {
    val dirty = mapOf(
      "dji video (djmd)" to LocationFixtures.djiVideo().bytes,
      "camm come seconda voce" to LocationFixtures.cammVideo().bytes,
      "gopro gpmd" to LocationFixtures.gpmdVideo().bytes,
      "apple mebx" to LocationFixtures.appleMebxVideo().bytes,
      "dji foto (drone-dji)" to LocationFixtures.djiPhoto(),
      "xmp esteso spezzato" to LocationFixtures.extendedXmpPhoto(),
      "app13" to LocationFixtures.app13Photo(),
      "png tEXt/iTXt" to LocationFixtures.textPng(),
      "motion photo, gps nel video" to LocationFixtures.motionPhotoWithVideoGps(),
      "heic" to LocationFixtures.heic(),
      // E quelle di prima.
      "jpeg exif+xmp" to StripFixtures.jpeg(),
      "png exif+xmp" to StripFixtures.png(),
      "webp exif+xmp" to StripFixtures.webp(),
      "mp4 ©xyz" to StripFixtures.mp4WithLocation(),
      "mov apple keys" to StripFixtures.movWithApplePosition(),
      "mp4 uuid xmp" to StripFixtures.mp4WithXmpUuid(),
      "motion photo ©xyz" to StripFixtures.motionPhoto(),
    )
    for ((name, bytes) in dirty) {
      val scan = scan(bytes)
      assertWithMessage("$name: $scan").that(scan.traces).isNotEmpty()
    }
    // Ogni tipo di traccia dove deve stare.
    assertThat(scan(LocationFixtures.djiVideo().bytes).traces).contains(LocationTrace.NON_AV_TRACK)
    assertThat(scan(LocationFixtures.cammVideo().bytes).traces).contains(LocationTrace.NON_AV_TRACK)
    assertThat(scan(LocationFixtures.gpmdVideo().bytes).traces).containsAtLeast(LocationTrace.NON_AV_TRACK, LocationTrace.GPS_BLOCK)
    assertThat(scan(LocationFixtures.appleMebxVideo().bytes).traces).containsAtLeast(LocationTrace.NON_AV_TRACK, LocationTrace.ISO6709)
    assertThat(scan(LocationFixtures.djiPhoto()).traces).contains(LocationTrace.XMP_NAME)
    assertThat(scan(LocationFixtures.extendedXmpPhoto()).traces).contains(LocationTrace.XMP_NAME)
    assertThat(scan(LocationFixtures.app13Photo()).traces).containsAtLeast(LocationTrace.XMP_NAME, LocationTrace.EXIF_GPS)
    assertThat(scan(LocationFixtures.textPng()).traces).contains(LocationTrace.XMP_NAME)
    assertThat(scan(LocationFixtures.motionPhotoWithVideoGps()).traces).containsAtLeast(LocationTrace.NON_AV_TRACK, LocationTrace.ISO6709)
    assertThat(scan(LocationFixtures.heic()).traces).containsAtLeast(LocationTrace.EXIF_GPS, LocationTrace.XMP_NAME)
    assertThat(scan(StripFixtures.jpeg(xmp = null)).traces).contains(LocationTrace.EXIF_GPS)
    assertThat(scan(StripFixtures.mp4WithLocation()).traces).containsAtLeast(LocationTrace.LOCATION_BOX, LocationTrace.ISO6709)
  }

  @Test
  fun suIFilePulitiIlRilevatoreNonTrovaNiente() {
    val clean = mapOf(
      "jpeg senza metadati" to ImageFixtures.jpeg(64, 48, 0x336699),
      "jpeg con exif senza gps" to StripFixtures.jpeg(exif = StripFixtures.exifWithoutGps(), xmp = null),
      "png" to ImageFixtures.png(ImageFixtures.noise(32, 24, 1), null, null),
      "webp" to ImageFixtures.webp(32, 24, -0xBF3F80, null, null),
      "mp4 senza posizione" to StripFixtures.mp4WithoutLocation(),
      "mp4 con video e audio" to LocationFixtures.mp4(listOf(LocationFixtures.video(), LocationFixtures.audio())).bytes,
    )
    for ((name, bytes) in clean) {
      val scan = scan(bytes)
      assertWithMessage("$name: $scan").that(scan.clean).isTrue()
    }
  }

  // --- 2. La copia condivisa non ha piu' la posizione ------------------------------------------------------------------------------

  /** Un video: `Stripped`, nessuna traccia, stessa dimensione, video e audio intatti, nessun resto della traccia dei metadati. */
  private fun assertCleanVideo(built: LocationFixtures.Built, mime: String, gone: List<ByteArray>) {
    val source = file("v.${if (mime == "video/quicktime") "mov" else "mp4"}", built.bytes)
    assertWithMessage("prima").that(LocationTraces.scan(source).traces).isNotEmpty()
    val out = playable(File(folder.root, "out-${System.nanoTime()}.bin"))

    val result = stripper.strip(source, out, mime)

    assertThat(result).isEqualTo(StripOutcome.Stripped(mime))
    val bytes = out.readBytes()
    assertWithMessage("dopo: ${LocationTraces.scan(out)}").that(LocationTraces.scan(out).clean).isTrue()
    assertThat(bytes.size).isEqualTo(built.bytes.size) // nessuna ricodifica: niente si sposta
    // Il `moov` c'e' ancora, con le tracce video e audio e i loro campioni identici, allo stesso posto.
    assertThat(LocationFixtures.handlers(bytes)).containsExactly("vide", "soun").inOrder()
    for (track in 0..1) {
      for ((k, range) in built.samples[track].withIndex()) {
        assertWithMessage("traccia $track campione $k").that(bytes.copyOfRange(range.first, range.last + 1)).isEqualTo(built.sample(track, k))
      }
    }
    // I campioni della traccia dei metadati non ci sono piu', in nessun punto del file.
    for ((k, range) in built.samples[2].withIndex()) {
      assertWithMessage("metadati $k").that(bytes.copyOfRange(range.first, range.last + 1).all { it == 0.toByte() }).isTrue()
    }
    for (needle in gone) assertWithMessage(String(needle, Charsets.ISO_8859_1)).that(StripFixtures.indexOf(bytes, needle)).isEqualTo(-1)
  }

  @Test
  fun djiTracciaDjmdTolta() = assertCleanVideo(LocationFixtures.djiVideo(), "video/mp4", listOf(LocationFixtures.BINARY_POSITION))

  @Test
  fun cammComeSecondaVoceTolta() = assertCleanVideo(LocationFixtures.cammVideo(), "video/mp4", listOf(LocationFixtures.BINARY_POSITION))

  @Test
  fun goproGpmdTolta() = assertCleanVideo(LocationFixtures.gpmdVideo(), "video/mp4", listOf("GPS5".toByteArray()))

  @Test
  fun appleMebxTolta() = assertCleanVideo(
    LocationFixtures.appleMebxVideo(),
    "video/quicktime",
    listOf(LocationFixtures.APPLE_ISO6709.toByteArray(), "location".toByteArray(), "mebx".toByteArray()),
  )

  /** Una foto: `Stripped`, nessuna traccia, si decodifica, orientamento e data restano. */
  private fun assertCleanPhoto(bytes: ByteArray, mime: String, ext: String, gone: List<String>): ByteArray {
    val source = file("f.$ext", bytes)
    assertWithMessage("prima").that(LocationTraces.scan(source).traces).isNotEmpty()
    val out = File(folder.root, "out-${System.nanoTime()}.$ext")

    val result = stripper.strip(source, out, mime)

    assertThat(result).isEqualTo(StripOutcome.Stripped(mime))
    val clean = out.readBytes()
    assertWithMessage("dopo: ${LocationTraces.scan(out)}").that(LocationTraces.scan(out).clean).isTrue()
    assertThat(AndroidPreviewDecoder().decode(clean, 64)).isNotNull()
    assertThat(ExifInterface(out).latLong).isNull()
    for (text in gone) assertWithMessage(text).that(StripFixtures.contains(clean, text)).isFalse()
    return clean
  }

  @Test
  fun droneDjiLXmpDroneDjiSparisce() {
    val out = assertCleanPhoto(LocationFixtures.djiPhoto(), "image/jpeg", "jpg", listOf("GpsLatitude", "GpsLongtitude", "AbsoluteAltitude", "+45.4642", "drone-dji"))
    // Orientamento e data restano (sono i soli metadati che si tengono).
    val exif = ExifInterface(File(folder.root, "chk.jpg").apply { writeBytes(out) })
    assertThat(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)).isEqualTo(6)
    assertThat(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)).isEqualTo("2024:03:12 10:15:30")
  }

  @Test
  fun xmpEstesoSpezzatoSparisce() {
    assertCleanPhoto(LocationFixtures.extendedXmpPhoto(), "image/jpeg", "jpg", listOf("GPSLat", "itude=", "xmp/extension", "45,27.852N"))
  }

  @Test
  fun app13DiPhotoshopSparisce() {
    assertCleanPhoto(LocationFixtures.app13Photo(), "image/jpeg", "jpg", listOf("Photoshop 3.0", "8BIM", "GPSLatitude"))
  }

  @Test
  fun pngConTestiEXmpSparisconoEIlPngResta() {
    val out = assertCleanPhoto(LocationFixtures.textPng(), "image/png", "png", listOf("exif:GPSLatitude", "GpsLatitude", "45/1, 27/1", "tEXt", "iTXt"))
    // Ogni chunk rimasto ha il suo CRC giusto.
    var pos = 8
    while (pos + 12 <= out.size) {
      val length = ((out[pos].toInt() and 0xFF) shl 24) or ((out[pos + 1].toInt() and 0xFF) shl 16) or ((out[pos + 2].toInt() and 0xFF) shl 8) or (out[pos + 3].toInt() and 0xFF)
      val crc = java.util.zip.CRC32().apply { update(out, pos + 4, 4 + length) }.value
      val stored = ((out[pos + 8 + length].toLong() and 0xFF) shl 24) or ((out[pos + 9 + length].toLong() and 0xFF) shl 16) or
        ((out[pos + 10 + length].toLong() and 0xFF) shl 8) or (out[pos + 11 + length].toLong() and 0xFF)
      assertWithMessage(String(out, pos + 4, 4, Charsets.ISO_8859_1)).that(stored).isEqualTo(crc)
      pos += 12 + length
    }
  }

  @Test
  fun motionPhotoConLaPosizioneNelVideo() {
    val video = LocationFixtures.motionVideoPart()
    val out = assertCleanPhoto(LocationFixtures.motionPhotoWithVideoGps(), "image/jpeg", "jpg", listOf(LocationFixtures.APPLE_ISO6709, "location"))
    // E' ancora una motion photo: il video c'e', con video e audio e i loro campioni intatti.
    val start = StripFixtures.indexOf(out, "ftyp".toByteArray()) - 4
    assertThat(start).isGreaterThan(0)
    assertThat(LocationFixtures.handlers(out, start)).containsExactly("vide", "soun").inOrder()
    for (track in 0..1) {
      for ((k, range) in video.samples[track].withIndex()) {
        assertThat(out.copyOfRange(start + range.first, start + range.last + 1)).isEqualTo(video.sample(track, k))
      }
    }
    // L'app la riconosce ancora come motion photo: l'XMP nuovo dichiara il video (e niente altro).
    assertThat(StripFixtures.contains(out, "MotionPhoto")).isTrue()
    assertThat(out.size - start).isEqualTo(video.bytes.size)
  }

  @Test
  fun heicConUnConvertitoreCheSiPortaDietroLXmp() {
    val source = file("h.heic", LocationFixtures.heic())
    assertThat(LocationTraces.scan(source).traces).isNotEmpty()
    val out = File(folder.root, "out-h.jpg")

    val result = stripper.strip(source, out, "image/heic")

    assertThat(result).isEqualTo(StripOutcome.Stripped("image/jpeg"))
    assertWithMessage("dopo: ${LocationTraces.scan(out)}").that(LocationTraces.scan(out).clean).isTrue()
    assertThat(StripFixtures.contains(out.readBytes(), "GpsLatitude")).isFalse()
    assertThat(AndroidPreviewDecoder().decode(out.readBytes(), 64)).isNotNull()
  }

  /**
   * Un convertitore HEIC → JPEG per la JVM che sbaglia: copia nel JPEG l'XMP del drone (un convertitore che "conserva i metadati"). La regola
   * di cio' che si tiene vale anche per il JPEG nato da un HEIC.
   */
  private class LeakyHeicToJpeg : HeicToJpeg {
    override fun convert(src: File, dst: File): HeicConversion {
      val bytes = StripFixtures.jpeg(exif = StripFixtures.exifWithoutGps(), xmp = LocationFixtures.djiXmp())
      try {
        dst.writeBytes(bytes)
      } catch (_: IOException) {
        return HeicConversion.Failed(WriteFailure.IO)
      }
      return HeicConversion.Done
    }
  }
}
