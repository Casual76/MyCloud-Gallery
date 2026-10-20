package dev.pampa.mycloudgallery.feature.integrations.share

import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.metadata.contract.VideoFacts
import dev.pampa.mycloudgallery.core.metadata.contract.VideoProbe
import dev.pampa.mycloudgallery.core.metadata.extract.AndroidPreviewDecoder
import dev.pampa.mycloudgallery.core.metadata.extract.DefaultMetadataExtractor
import dev.pampa.mycloudgallery.core.metadata.extract.VideoMetadataParsing
import dev.pampa.mycloudgallery.core.metadata.strip.DefaultLocationStripper
import dev.pampa.mycloudgallery.core.metadata.strip.ExifInterfaceLocationRecheck
import dev.pampa.mycloudgallery.core.metadata.strip.ExtractorLocationRecheck
import dev.pampa.mycloudgallery.core.metadata.strip.LocationRecheck
import dev.pampa.mycloudgallery.core.metadata.strip.LocationVerdict
import dev.pampa.mycloudgallery.core.places.testing.FakePlaces
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

// Fase 5B (P11): i file con posizione per provare "Condividi senza posizione" **fino in fondo** con lo stripper vero di core-metadata (le fixture di
// core-metadata non sono visibili qui: si costruiscono con `Bitmap` e `ExifInterface`, che e' cio' che fa un telefono).

internal object ShareSamples {

  const val LAT = 45.4642
  const val LON = 9.19
  const val ISO6709 = "+45.4642+009.1900/"

  /** Un JPEG vero con una posizione e qualche altro dato (fotocamera, data di scatto). Va costruito dentro un test Robolectric con grafica nativa. */
  fun gpsJpeg(folder: File, name: String = "gps.jpg"): ByteArray {
    val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(40, 120, 200)) }
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
    val file = File(folder, name).apply { writeBytes(out.toByteArray()) }
    ExifInterface(file.path).apply {
      // 45,4642 N e 9,19 E come li scrive una fotocamera: gradi, primi e secondi in razionali.
      setAttribute(ExifInterface.TAG_GPS_LATITUDE, "45/1,27/1,5112/100")
      setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
      setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "9/1,11/1,24/1")
      setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
      setAttribute(ExifInterface.TAG_MAKE, "samsung")
      setAttribute(ExifInterface.TAG_MODEL, "SM-S931B")
      setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2024:03:12 10:15:30")
      saveAttributes()
    }
    return file.readBytes().also { file.delete() }
  }

  private fun box(type: String, vararg parts: ByteArray): ByteArray {
    val body = parts.fold(ByteArray(0)) { a, b -> a + b }
    val size = 8 + body.size
    return u32(size.toLong()) + type.toByteArray(Charsets.ISO_8859_1) + body
  }

  private fun fullBox(type: String, flags: Int, vararg parts: ByteArray): ByteArray = box(type, byteArrayOf(0, 0, 0, flags.toByte()), *parts)

  private fun u32(v: Long): ByteArray = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

  private fun u16(v: Int): ByteArray = byteArrayOf((v ushr 8).toByte(), v.toByte())

  /**
   * Un MP4 come le registrazioni di Android: una traccia video (un campione che occupa tutto `mdat`) e la posizione in `moov/udta/©xyz`.
   * Correzioni 5B: lo stripper tiene le sole tracce audio e video, quindi un video di prova deve averne una.
   */
  fun gpsMp4(mdatBytes: Int = 2_048): ByteArray {
    val text = ISO6709.toByteArray(Charsets.ISO_8859_1)
    val xyz = u32(12L + text.size) + byteArrayOf(0xA9.toByte(), 'x'.code.toByte(), 'y'.code.toByte(), 'z'.code.toByte()) + u16(text.size) + u16(0x15C7) + text
    val ftyp = box("ftyp", "isom".toByteArray(), byteArrayOf(0, 0, 2, 0), "isom".toByteArray())
    val mdat = box("mdat", ByteArray(mdatBytes) { 7 })
    val sampleAt = ftyp.size + 8L
    val tkhd = fullBox("tkhd", 3, ByteArray(80))
    val mdhd = fullBox("mdhd", 0, u32(0), u32(0), u32(1000), u32(3000), u16(0x55C4), u16(0))
    val hdlr = fullBox("hdlr", 0, u32(0), "vide".toByteArray(), ByteArray(12), byteArrayOf(0))
    val vmhd = fullBox("vmhd", 1, ByteArray(8))
    val dinf = box("dinf", fullBox("dref", 0, u32(1), fullBox("url ", 1)))
    val stsd = fullBox("stsd", 0, u32(1), box("avc1", ByteArray(6), u16(1), ByteArray(70)))
    val stts = fullBox("stts", 0, u32(1), u32(1), u32(3000))
    val stsc = fullBox("stsc", 0, u32(1), u32(1), u32(1), u32(1))
    val stsz = fullBox("stsz", 0, u32(0), u32(1), u32(mdatBytes.toLong()))
    val stco = fullBox("stco", 0, u32(1), u32(sampleAt))
    val trak = box("trak", tkhd, box("mdia", mdhd, hdlr, box("minf", vmhd, dinf, box("stbl", stsd, stts, stsc, stsz, stco))))
    val mvhd = box("mvhd", ByteArray(100))
    return ftyp + mdat + box("moov", mvhd, trak, box("udta", xyz))
  }

  /** La posizione di una foto, letta con `ExifInterface` del sistema (un lettore scritto da altri), o null. */
  fun latLong(file: File): FloatArray? {
    val out = FloatArray(2)
    return if (ExifInterface(file.path).getLatLong(out)) out else null
  }

  fun model(file: File): String? = ExifInterface(file.path).getAttribute(ExifInterface.TAG_MODEL)

  fun contains(bytes: ByteArray, text: String): Boolean {
    val needle = text.toByteArray(Charsets.ISO_8859_1)
    outer@ for (i in 0..bytes.size - needle.size) {
      for (k in needle.indices) if (bytes[i + k] != needle[k]) continue@outer
      return true
    }
    return false
  }

  /** Legge la posizione del video da `©xyz`, come farebbe il sistema (su JVM `MediaMetadataRetriever` non gira). */
  class BoxVideoProbe : VideoProbe {
    override fun probe(bytes: MediaBytes): VideoFacts? {
      val data = runBlocking { bytes.read(0 until bytes.size) }
      var at = -1
      for (i in 0..data.size - 4) if (data[i] == 0xA9.toByte() && data[i + 1] == 'x'.code.toByte() && data[i + 2] == 'y'.code.toByte() && data[i + 3] == 'z'.code.toByte()) { at = i; break }
      val point = if (at < 0) null else VideoMetadataParsing.parseIso6709(String(data, at + 8, ((data[at + 4].toInt() and 0xFF) shl 8) or (data[at + 5].toInt() and 0xFF), Charsets.ISO_8859_1))
      return VideoFacts(3000, 0, null, point, false, "video/avc")
    }
  }

  /** Un secondo lettore dei video per la JVM, al posto di `MediaMetadataRetriever`: dice "nessuna posizione" se non c'e' piu' nessuna `©xyz`. */
  class VideoBoxReader : LocationRecheck {
    override fun appliesTo(mime: String): Boolean = mime.startsWith("video/")

    override fun check(file: File, mime: String): LocationVerdict =
      if (contains(file.readBytes(), "©xyz")) LocationVerdict.HAS_LOCATION else LocationVerdict.CLEAR
  }

  /** Lo stripper vero con i lettori veri (l'estrattore dell'app e `ExifInterface`) e, per i video, il lettore di prova. */
  fun stripper(): DefaultLocationStripper {
    val places = FakePlaces()
    val extractor = DefaultMetadataExtractor(
      geocoder = places,
      zones = places,
      videoProbe = BoxVideoProbe(),
      previewDecoder = AndroidPreviewDecoder(),
      clock = { 1_800_000_000_000L },
      blocking = Dispatchers.Unconfined,
    )
    return DefaultLocationStripper(
      listOf(ExtractorLocationRecheck(extractor, AndroidPreviewDecoder(), ZoneId.of("Europe/Rome")), ExifInterfaceLocationRecheck(), VideoBoxReader()),
    )
  }
}
