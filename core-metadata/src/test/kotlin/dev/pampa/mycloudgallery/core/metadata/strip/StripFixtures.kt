package dev.pampa.mycloudgallery.core.metadata.strip

import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.metadata.contract.VideoFacts
import dev.pampa.mycloudgallery.core.metadata.contract.VideoProbe
import dev.pampa.mycloudgallery.core.metadata.extract.AndroidPreviewDecoder
import dev.pampa.mycloudgallery.core.metadata.extract.DefaultMetadataExtractor
import dev.pampa.mycloudgallery.core.metadata.extract.VideoMetadataParsing
import dev.pampa.mycloudgallery.core.places.testing.FakePlaces
import dev.pampa.mycloudgallery.fixtures.Bytes
import dev.pampa.mycloudgallery.fixtures.ExifWriter
import dev.pampa.mycloudgallery.fixtures.ImageFixtures
import dev.pampa.mycloudgallery.fixtures.IsoFixtures
import dev.pampa.mycloudgallery.fixtures.MotionFixtures
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

// Fase 5B, P11: i file di prova per togliere la posizione. Tutti costruiti con le fixture di `tools/fixtures` (JPEG, PNG, WebP, HEIC, MP4) e
// con qualche scatola fatta a mano (QuickTime di Apple, `uuid` XMP, tracce `camm`): sono i posti dove una posizione sta davvero.

internal object StripFixtures {

  const val LAT = 45.4642
  const val LON = 9.19
  const val ISO6709 = "+45.4642+009.1900/"

  val ROME: ZoneId = ZoneId.of("Europe/Rome")

  /** Un EXIF con tutto: fotocamera, data, orientamento e un blocco GPS completo (coordinate, quota, data e ora GPS). */
  fun exifWithGps(): ExifWriter.Spec = ExifWriter.Spec().apply {
    make = "samsung"; model = "SM-S931B"; orientation = 6
    dateOriginal = "2024:03:12 10:15:30"; offsetOriginal = "+01:00"; subSecOriginal = "123"
    latitude = LAT; longitude = LON; altitude = 120.0
    gpsDate = "2024:03:12"; gpsTime = intArrayOf(9, 15, 30)
  }

  /** Lo stesso EXIF senza GPS. */
  fun exifWithoutGps(): ExifWriter.Spec = exifWithGps().apply { latitude = null; longitude = null; altitude = null; gpsDate = null; gpsTime = null }

  /** Un pacchetto XMP con le proprieta' GPS in tutti e due i modi (attributo ed elemento), piu' una proprieta' innocua da non toccare. */
  fun xmpWithGps(): String = "<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n" +
    "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n" +
    " <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n" +
    "  <rdf:Description rdf:about=\"\" xmlns:exif=\"http://ns.adobe.com/exif/1.0/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\"\n" +
    "     exif:GPSLatitude=\"45,27.852N\" exif:GPSLongitude='9,11.400E' dc:creator=\"Mario\">\n" +
    "   <exif:GPSAltitude>120/1</exif:GPSAltitude>\n" +
    "   <exif:GPSProcessingMethod>fused</exif:GPSProcessingMethod>\n" +
    "   <exif:GPSVersionID/>\n" +
    "  </rdf:Description>\n" +
    " </rdf:RDF>\n" +
    "</x:xmpmeta>\n" +
    "<?xpacket end=\"w\"?>"

  fun jpeg(exif: ExifWriter.Spec? = exifWithGps(), xmp: String? = xmpWithGps()): ByteArray =
    ImageFixtures.jpeg(ImageFixtures.noise(96, 64, 3), exif, xmp)

  fun png(exif: ExifWriter.Spec? = exifWithGps(), xmp: String? = xmpWithGps()): ByteArray =
    ImageFixtures.png(ImageFixtures.noise(96, 64, 4), exif, xmp)

  fun webp(exif: ExifWriter.Spec? = exifWithGps(), xmp: String? = xmpWithGps()): ByteArray =
    ImageFixtures.webp(96, 64, -0xBF3F80, exif, xmp)

  fun heic(exif: ExifWriter.Spec? = exifWithGps(), xmp: String? = xmpWithGps()): ByteArray =
    IsoFixtures.heic(IsoFixtures.HeicSpec().apply { this.exif = exif; this.xmp = xmp })

  // --- Video ---------------------------------------------------------------------------------------------------------------------

  /** Un MP4 (come le registrazioni di Android) con la posizione in `udta/©xyz`. */
  fun mp4WithLocation(brand: String = "isom", mdatBytes: Int = 2048): ByteArray = IsoFixtures.mp4(
    IsoFixtures.Mp4Spec().apply { this.brand = brand; this.mdatBytes = mdatBytes; creationSeconds = IsoFixtures.mp4Seconds(1_710_238_530_000L); iso6709 = ISO6709 },
  )

  fun mp4WithoutLocation(): ByteArray = IsoFixtures.mp4(IsoFixtures.Mp4Spec().apply { creationSeconds = IsoFixtures.mp4Seconds(1_710_238_530_000L) })

  private fun u32(v: Long): ByteArray = Bytes().u32be(v).toBytes()

  private fun ascii(text: String): ByteArray = Bytes.ascii4(text)

  private val MVHD: ByteArray = IsoFixtures.fullBox(
    "mvhd", 0, 0,
    Bytes().u32be(0).u32be(0).u32be(1000).u32be(3000).u32be(0x00010000L).u16be(0x0100).u16be(0).u32be(0).u32be(0)
      .u32be(0x00010000L).u32be(0).u32be(0).u32be(0).u32be(0x00010000L).u32be(0).u32be(0).u32be(0).u32be(0x40000000L)
      .zeros(24).u32be(2).toBytes(),
  )

  /**
   * Un MOV di Apple: `moov/meta` in stile QuickTime (senza versione) con `hdlr mdta`, la tabella `keys` e `ilst`. Chiavi, in ordine:
   * `com.apple.quicktime.make` (1), `com.apple.quicktime.location.ISO6709` (2), `com.apple.quicktime.model` (3).
   */
  fun movWithApplePosition(): ByteArray {
    val keyNames = listOf("com.apple.quicktime.make", "com.apple.quicktime.location.ISO6709", "com.apple.quicktime.model")
    val keyEntries = Bytes()
    for (name in keyNames) keyEntries.u32be(8L + name.length).ascii("mdta").ascii(name)
    val keys = IsoFixtures.fullBox("keys", 0, 0, u32(keyNames.size.toLong()), keyEntries.toBytes())
    fun item(index: Int, value: String): ByteArray {
      val data = IsoFixtures.box("data", u32(1), u32(0), ascii(value))
      return IsoFixtures.box(String(byteArrayOf(0, 0, 0, index.toByte()), Charsets.ISO_8859_1), data)
    }
    val ilst = IsoFixtures.box("ilst", item(1, "Apple"), item(2, ISO6709), item(3, "iPhone 15"))
    val hdlr = IsoFixtures.fullBox("hdlr", 0, 0, u32(0), ascii("mdta"), ByteArray(12), byteArrayOf(0))
    val meta = IsoFixtures.box("meta", hdlr, keys, ilst)
    val moov = IsoFixtures.box("moov", MVHD, meta)
    return Bytes.concat(IsoFixtures.box("ftyp", ascii("qt  "), u32(0), ascii("qt  ")), IsoFixtures.box("mdat", ByteArray(512)), moov)
  }

  /** Un MP4 con l'XMP in un riquadro `uuid` (come lo scrivono alcuni editor) e le coordinate dentro. */
  fun mp4WithXmpUuid(): ByteArray {
    val uuid = byteArrayOf(
      0xBE.toByte(), 0x7A, 0xCF.toByte(), 0xCB.toByte(), 0x97.toByte(), 0xA9.toByte(), 0x42, 0xE8.toByte(),
      0x9C.toByte(), 0x71, 0x99.toByte(), 0x94.toByte(), 0x91.toByte(), 0xE3.toByte(), 0xAF.toByte(), 0xAC.toByte(),
    )
    val xmp = IsoFixtures.box("uuid", uuid, xmpWithGps().toByteArray(Charsets.UTF_8))
    return Bytes.concat(IsoFixtures.box("ftyp", ascii("isom"), u32(0x200), ascii("isom")), IsoFixtures.box("moov", MVHD), IsoFixtures.box("mdat", ByteArray(256)), xmp)
  }

  /** Un MP4 con una traccia di metadati a tempo del formato [entry] (`camm` di Google, `gpmd` di GoPro): punti GPS che non si possono togliere. */
  fun mp4WithTimedTrack(entry: String): ByteArray {
    val stsd = IsoFixtures.fullBox("stsd", 0, 0, u32(1), Bytes().u32be(16).ascii(entry).zeros(8).toBytes())
    val stbl = IsoFixtures.box("stbl", stsd)
    val trak = IsoFixtures.box("trak", IsoFixtures.box("mdia", IsoFixtures.box("minf", stbl)))
    return Bytes.concat(IsoFixtures.box("ftyp", ascii("isom"), u32(0x200), ascii("isom")), IsoFixtures.box("moov", MVHD, trak), IsoFixtures.box("mdat", ByteArray(256)))
  }

  /** Una motion photo di Google: un JPEG con EXIF e XMP con GPS e, in coda, un MP4 che porta la sua `©xyz`. */
  fun motionPhoto(): ByteArray {
    val video = mp4WithLocation(mdatBytes = 512)
    return MotionFixtures.googleV1(ImageFixtures.noise(96, 64, 5), exifWithGps(), video)
  }

  // --- Verifiche -----------------------------------------------------------------------------------------------------------------

  /** Il file contiene [text] come byte ASCII? */
  fun contains(bytes: ByteArray, text: String): Boolean = indexOf(bytes, text.toByteArray(Charsets.ISO_8859_1)) >= 0

  fun indexOf(bytes: ByteArray, needle: ByteArray): Int {
    outer@ for (i in 0..bytes.size - needle.size) {
      for (k in needle.indices) if (bytes[i + k] != needle[k]) continue@outer
      return i
    }
    return -1
  }

  /**
   * Una sonda video per i test JVM (il vero `MediaMetadataRetriever` non gira qui): legge la posizione da `©xyz` nei byte del file, come la
   * leggerebbe il sistema. Un `©xyz` rinominato in `free` non si vede piu'.
   */
  class BoxVideoProbe : VideoProbe {
    override fun probe(bytes: MediaBytes): VideoFacts? {
      val data = runBlocking { bytes.read(0 until bytes.size) }
      val at = indexOf(data, byteArrayOf(0xA9.toByte(), 'x'.code.toByte(), 'y'.code.toByte(), 'z'.code.toByte()))
      val point = if (at < 0) null else {
        val length = ((data[at + 4].toInt() and 0xFF) shl 8) or (data[at + 5].toInt() and 0xFF)
        VideoMetadataParsing.parseIso6709(String(data, at + 8, length, Charsets.ISO_8859_1))
      }
      return VideoFacts(durationMs = 3000, rotation = 0, utcMs = null, iso6709 = point, hdr = false, codecMime = "video/avc")
    }
  }

  /** L'estrattore dell'app come lo lega `:app`, con la sonda video di prova. */
  fun extractor(probe: VideoProbe = BoxVideoProbe()): DefaultMetadataExtractor {
    val places = FakePlaces()
    return DefaultMetadataExtractor(
      geocoder = places,
      zones = places,
      videoProbe = probe,
      previewDecoder = AndroidPreviewDecoder(),
      clock = { 1_800_000_000_000L },
      blocking = Dispatchers.Unconfined,
    )
  }

  /** I lettori di serie per i test JVM: l'estrattore, `ExifInterface` e `MediaMetadataRetriever` (quest'ultimo con il suo shadow di Robolectric). */
  fun rechecks(): List<LocationRecheck> = LocationRechecks.standard(extractor(), AndroidPreviewDecoder(), ROME)

  fun write(folder: File, name: String, bytes: ByteArray): File = File(folder, name).apply { writeBytes(bytes) }
}
