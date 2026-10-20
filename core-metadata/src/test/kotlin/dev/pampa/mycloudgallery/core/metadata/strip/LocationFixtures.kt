package dev.pampa.mycloudgallery.core.metadata.strip

import dev.pampa.mycloudgallery.fixtures.Bytes
import dev.pampa.mycloudgallery.fixtures.ExifWriter
import dev.pampa.mycloudgallery.fixtures.ImageFixtures
import dev.pampa.mycloudgallery.fixtures.IsoFixtures
import dev.pampa.mycloudgallery.fixtures.MotionFixtures
import java.nio.ByteBuffer
import java.util.zip.CRC32

// Correzioni 5B (R1, R2, docs/review/5b-fase5b.md): i file dove la posizione sta **dove lo stripper di prima non guardava**, costruiti qui a mano,
// byte per byte (le fixture di `tools/fixtures` danno solo i pixel e l'EXIF di base). Ognuno porta la posizione in un posto vero:
//  - video con tracce: DJI (`djmd`, coordinate binarie), Google (`camm` come seconda voce di `stsd`), GoPro (`gpmd` con `GPS5`), Apple (`mebx`
//    con le chiavi di posizione e le stringhe ISO 6709 nei campioni);
//  - foto: XMP `drone-dji:GpsLatitude` (nome che non comincia per `GPS`), XMP esteso con la proprieta' spezzata fra due segmenti, APP13 di
//    Photoshop con l'XMP e l'EXIF dentro, PNG con `tEXt` di ImageMagick e `iTXt` XMP;
//  - una motion photo con la posizione solo nella parte video;
//  - un HEIC con EXIF GPS e XMP.

internal object LocationFixtures {

  const val ISO6709 = StripFixtures.ISO6709
  const val APPLE_ISO6709 = "+45.4642+009.1900+120.000/"

  // --- Video con tracce ------------------------------------------------------------------------------------------------------------

  /**
   * Una traccia: tipo (`hdlr`), formati di campione (`stsd`, il primo e gli altri), i campioni, quanti per chunk e se le dimensioni stanno in
   * `stz2` (8 bit) invece che in `stsz`.
   */
  class Track(
    val handler: String,
    val formats: List<String>,
    val samples: List<ByteArray>,
    val entryExtra: ByteArray = ByteArray(0),
    val perChunk: Int = 1,
    val compactSizes: Boolean = false,
  ) {
    val chunks: List<List<ByteArray>> get() = samples.chunked(perChunk)
  }

  /** Un video costruito: i byte e, per ogni traccia, dove stanno i suoi campioni nel file. */
  class Built(val bytes: ByteArray, val samples: List<List<IntRange>>) {
    /** I byte del campione [index] della traccia [track], come stanno nel file. */
    fun sample(track: Int, index: Int): ByteArray = bytes.copyOfRange(samples[track][index].first, samples[track][index].last + 1)
  }

  /** Un fotogramma "video" e un pezzo di "audio" riconoscibili: il pulito deve averli ancora, byte per byte. */
  fun videoSamples(count: Int = 3): List<ByteArray> = (0 until count).map { k -> ByteArray(96) { (0x40 + k).toByte() }.also { it[0] = 0x00; it[1] = 0x00; it[2] = 0x01 } }

  fun audioSamples(count: Int = 3): List<ByteArray> = (0 until count).map { k -> ByteArray(40) { (0x70 + k).toByte() } }

  fun video(): Track = Track("vide", listOf("avc1"), videoSamples(), ByteArray(70))

  fun audio(): Track = Track("soun", listOf("mp4a"), audioSamples(), ByteArray(20))

  /**
   * Un MP4 (o MOV con `brand = "qt  "`) con le tracce [tracks]: `ftyp`, `mdat` con i campioni alternati traccia per traccia, `moov` (dopo, come le
   * registrazioni di Android, o prima con [moovFirst]) con `mvhd`, una `trak` per traccia (`tkhd`, `mdia/mdhd`, `hdlr`, `minf` con `dinf` e le
   * tabelle `stsd`, `stts`, `stsc`, `stsz`, `stco`) e i riquadri in piu' [moovExtra]; in fondo [topExtra].
   */
  fun mp4(
    tracks: List<Track>,
    brand: String = "isom",
    moovExtra: List<ByteArray> = emptyList(),
    topExtra: List<ByteArray> = emptyList(),
    moovFirst: Boolean = false,
  ): Built {
    val ftyp = if (brand == "qt  ") {
      IsoFixtures.box("ftyp", ascii("qt  "), u32(0x200), ascii("qt  "))
    } else {
      IsoFixtures.box("ftyp", ascii(brand), u32(0x200), ascii("isom"), ascii("mp42"))
    }
    // I chunk nel `mdat`, alternati: il primo di ogni traccia, poi il secondo... Dentro un chunk i campioni sono di seguito.
    val payload = Bytes()
    val chunkAt = tracks.map { ArrayList<Int>() }
    val sampleAt = tracks.map { ArrayList<Int>() }
    val most = tracks.maxOfOrNull { it.chunks.size } ?: 0
    for (c in 0 until most) {
      for ((t, track) in tracks.withIndex()) {
        val chunk = track.chunks.getOrNull(c) ?: continue
        chunkAt[t] += payload.size()
        for (sample in chunk) {
          sampleAt[t] += payload.size()
          payload.raw(sample)
        }
      }
    }
    val mdat = IsoFixtures.box("mdat", payload.toBytes())
    val moovSize = moov(tracks, chunkAt, 0, moovExtra).size
    val payloadStart = ftyp.size + (if (moovFirst) moovSize else 0) + 8
    val moov = moov(tracks, chunkAt, payloadStart, moovExtra)
    check(moov.size == moovSize)
    val parts = ArrayList<ByteArray>()
    parts += ftyp
    if (moovFirst) parts += moov
    parts += mdat
    if (!moovFirst) parts += moov
    parts += topExtra
    val ranges = tracks.mapIndexed { t, track -> sampleAt[t].mapIndexed { k, at -> (payloadStart + at) until (payloadStart + at + track.samples[k].size) } }
    return Built(Bytes.concat(*parts.toTypedArray()), ranges)
  }

  private fun moov(tracks: List<Track>, relative: List<List<Int>>, base: Int, extra: List<ByteArray>): ByteArray {
    val traks = tracks.mapIndexed { t, track -> trak(t + 1, track, relative[t].map { base + it }) }
    return IsoFixtures.box("moov", *(listOf(StripFixtures.MVHD) + traks + extra).toTypedArray())
  }

  private fun trak(id: Int, track: Track, offsets: List<Int>): ByteArray {
    val tkhd = IsoFixtures.fullBox(
      "tkhd", 0, 3,
      Bytes().u32be(0).u32be(0).u32be(id.toLong()).u32be(0).u32be(3000).zeros(8).u16be(0).u16be(0)
        .u16be(if (track.handler == "soun") 0x0100 else 0).u16be(0)
        .u32be(0x00010000L).u32be(0).u32be(0).u32be(0).u32be(0x00010000L).u32be(0).u32be(0).u32be(0).u32be(0x40000000L)
        .u32be(if (track.handler == "vide") 64L shl 16 else 0).u32be(if (track.handler == "vide") 48L shl 16 else 0).toBytes(),
    )
    val mdhd = IsoFixtures.fullBox("mdhd", 0, 0, Bytes().u32be(0).u32be(0).u32be(1000).u32be(3000).u16be(0x55C4).u16be(0).toBytes())
    val hdlr = IsoFixtures.fullBox("hdlr", 0, 0, u32(0), ascii(track.handler), ByteArray(12), byteArrayOf(0))
    val header = when (track.handler) {
      "vide" -> IsoFixtures.fullBox("vmhd", 0, 1, ByteArray(8))
      "soun" -> IsoFixtures.fullBox("smhd", 0, 0, ByteArray(4))
      else -> IsoFixtures.fullBox("nmhd", 0, 0)
    }
    val dinf = IsoFixtures.box("dinf", IsoFixtures.fullBox("dref", 0, 0, u32(1), IsoFixtures.fullBox("url ", 0, 1)))
    val entries = track.formats.map { format -> IsoFixtures.box(format, ByteArray(6), Bytes().u16be(1).toBytes(), track.entryExtra) }
    val stsd = IsoFixtures.fullBox("stsd", 0, 0, u32(entries.size.toLong()), *entries.toTypedArray())
    val n = track.samples.size.toLong()
    val stts = IsoFixtures.fullBox("stts", 0, 0, u32(1), u32(n), u32(1000))
    // Un chunk pieno per volta; l'ultimo puo' averne meno (una seconda voce di `stsc`).
    val runs = Bytes()
    val last = track.samples.size % track.perChunk
    val full = track.chunks.size - if (last != 0) 1 else 0
    var runCount = 0
    if (full > 0) {
      runs.u32be(1).u32be(track.perChunk.toLong()).u32be(1)
      runCount++
    }
    if (last != 0) {
      runs.u32be(full + 1L).u32be(last.toLong()).u32be(1)
      runCount++
    }
    val stsc = IsoFixtures.fullBox("stsc", 0, 0, u32(runCount.toLong()), runs.toBytes())
    val stsz = if (track.compactSizes) {
      val sizes = Bytes().zeros(3).u8(8).u32be(n)
      track.samples.forEach { sizes.u8(it.size) }
      IsoFixtures.fullBox("stz2", 0, 0, sizes.toBytes())
    } else {
      val sizes = Bytes().u32be(0).u32be(n)
      track.samples.forEach { sizes.u32be(it.size.toLong()) }
      IsoFixtures.fullBox("stsz", 0, 0, sizes.toBytes())
    }
    val chunks = Bytes().u32be(offsets.size.toLong())
    offsets.forEach { chunks.u32be(it.toLong()) }
    val stco = IsoFixtures.fullBox("stco", 0, 0, chunks.toBytes())
    val stbl = IsoFixtures.box("stbl", stsd, stts, stsc, stsz, stco)
    val minf = IsoFixtures.box("minf", header, dinf, stbl)
    return IsoFixtures.box("trak", tkhd, IsoFixtures.box("mdia", mdhd, hdlr, minf))
  }

  /** Latitudine e longitudine come due `double` big-endian: come le scrivono DJI e `camm`. Un pulito non deve averle piu', in nessun punto. */
  val BINARY_POSITION: ByteArray = ByteBuffer.allocate(16).putDouble(45.4642).putDouble(9.19).array()

  /** DJI (Mavic, Mini...): una traccia di metadati `djmd` (protobuf) con la posizione in `double`, accanto a video e audio. */
  fun djiVideo(): Built {
    val samples = (0 until 3).map { k -> Bytes().raw(byteArrayOf(0x0A, 0x1A, 0x09)).raw(BINARY_POSITION).u8(0x12).u8(k).toBytes() }
    return mp4(listOf(video(), audio(), Track("meta", listOf("djmd"), samples)))
  }

  /** Google `camm` (Street View): la traccia dei sensori, con `camm` come **seconda** voce di `stsd`; tipo 5 = posizione (lat, lon, quota). */
  fun cammVideo(): Built {
    val samples = (0 until 3).map { Bytes().u16le(0).u16le(5).raw(BINARY_POSITION).raw(ByteBuffer.allocate(8).putDouble(120.0).array()).toBytes() }
    return mp4(listOf(video(), audio(), Track("meta", listOf("mett", "camm"), samples)))
  }

  /** GoPro: la traccia `gpmd` (GPMF) con i blocchi `GPS5` (lat, lon, quota, velocita'). */
  fun gpmdVideo(): Built {
    val gps5 = Bytes().ascii("GPS5").ascii("l").u8(20).u16be(1).u32be(454642000).u32be(91900000).u32be(120000).u32be(0).u32be(0).toBytes()
    val samples = (0 until 3).map { Bytes().ascii("DEVC").u8(0).u8(1).u16be(gps5.size + 8).ascii("STRM").u8(0).u8(1).u16be(gps5.size).raw(gps5).toBytes() }
    return mp4(listOf(video(), audio(), Track("meta", listOf("gpmd"), samples)))
  }

  /**
   * iPhone (MOV): una traccia `mebx` con le chiavi di posizione nella voce di `stsd` (`keys` → `keyd` `com.apple.quicktime.location.ISO6709`) e
   * le coordinate ISO 6709 nei campioni.
   */
  fun appleMebxVideo(): Built {
    val keyName = "com.apple.quicktime.location.ISO6709".toByteArray(Charsets.ISO_8859_1)
    val keyd = IsoFixtures.box("keyd", ascii("mdta"), keyName)
    val keys = IsoFixtures.box("keys", IsoFixtures.box(String(byteArrayOf(0, 0, 0, 1), Charsets.ISO_8859_1), keyd))
    val samples = (0 until 3).map { Bytes().u32be(8L + APPLE_ISO6709.length).u32be(1).ascii(APPLE_ISO6709).toBytes() }
    return mp4(listOf(video(), audio(), Track("meta", listOf("mebx"), samples, keys)), brand = "qt  ")
  }

  // --- Foto ------------------------------------------------------------------------------------------------------------------------

  /** L'XMP di un drone DJI: nessun nome comincia per `GPS` (e `GpsLongtitude` e' scritto cosi'). */
  fun djiXmp(): String = "<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n" +
    "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n" +
    " <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n" +
    "  <rdf:Description rdf:about=\"\" xmlns:drone-dji=\"http://www.dji.com/drone-dji/1.0/\" xmlns:tiff=\"http://ns.adobe.com/tiff/1.0/\"\n" +
    "    tiff:Make=\"DJI\" drone-dji:GpsStatus=\"RTK\" drone-dji:GpsLatitude=\"+45.46420000\" drone-dji:GpsLongtitude=\"+9.19000000\"\n" +
    "    drone-dji:AbsoluteAltitude=\"+120.00\" drone-dji:RelativeAltitude=\"+30.00\" drone-dji:FlightYawDegree=\"+12.30\"/>\n" +
    " </rdf:RDF>\n" +
    "</x:xmpmeta>\n" +
    "<?xpacket end=\"w\"?>"

  /** La foto di un drone DJI: EXIF senza GPS (come dopo una pulizia del solo EXIF), coordinate nell'XMP `drone-dji`. */
  fun djiPhoto(): ByteArray = StripFixtures.jpeg(exif = StripFixtures.exifWithoutGps(), xmp = djiXmp())

  /** Un GUID di XMP esteso (32 cifre esadecimali maiuscole). */
  private const val GUID = "2B8E10DB4F1E6B3A8C0F1D2E3A4B5C6D"

  /**
   * XMP esteso (Lightroom, Photoshop, Google con la mappa di profondita'): il pacchetto principale rimanda a un GUID, il resto sta in due segmenti
   * APP1 `http://ns.adobe.com/xmp/extension/`, e la proprieta' `exif:GPSLatitude` e' **spezzata** fra i due.
   */
  fun extendedXmpPhoto(): ByteArray {
    val main = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
      "<rdf:Description rdf:about=\"\" xmlns:xmpNote=\"http://ns.adobe.com/xmp/note/\" xmpNote:HasExtendedXMP=\"$GUID\"/></rdf:RDF></x:xmpmeta>"
    val extended = ("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
      "<rdf:Description rdf:about=\"\" xmlns:exif=\"http://ns.adobe.com/exif/1.0/\" exif:GPSLatitude=\"45,27.852N\" exif:GPSLongitude=\"9,11.400E\"/>" +
      "</rdf:RDF></x:xmpmeta>").toByteArray(Charsets.UTF_8)
    val cut = String(extended, Charsets.ISO_8859_1).indexOf("GPSLat") + "GPSLat".length
    fun part(offset: Int, data: ByteArray): ByteArray =
      Bytes().ascii("http://ns.adobe.com/xmp/extension/").u8(0).ascii(GUID).u32be(extended.size.toLong()).u32be(offset.toLong()).raw(data).toBytes()
    val base = StripFixtures.jpeg(exif = StripFixtures.exifWithoutGps(), xmp = main)
    return insertJpegSegments(base, app1(part(0, extended.copyOfRange(0, cut))), app1(part(cut, extended.copyOfRange(cut, extended.size))))
  }

  /**
   * APP13 di Photoshop ("Photoshop 3.0"): le risorse `8BIM` 0x0424 (una copia dell'XMP, con `exif:GPSLatitude`) e 0x0422 (una copia dell'EXIF,
   * con il blocco GPS). L'EXIF e l'XMP "veri" del file sono puliti.
   */
  fun app13Photo(): ByteArray {
    val xmp = StripFixtures.xmpWithGps().toByteArray(Charsets.UTF_8)
    val exif = ExifWriter.tiff(StripFixtures.exifWithGps())
    val irb = Bytes().ascii("Photoshop 3.0").u8(0).raw(resource(0x0424, xmp)).raw(resource(0x0422, exif)).toBytes()
    val base = StripFixtures.jpeg(exif = StripFixtures.exifWithoutGps(), xmp = null)
    return insertJpegSegments(base, segment(0xED, irb))
  }

  private fun resource(id: Int, data: ByteArray): ByteArray {
    val b = Bytes().ascii("8BIM").u16be(id).u8(0).u8(0).u32be(data.size.toLong()).raw(data)
    if (data.size % 2 == 1) b.u8(0)
    return b.toBytes()
  }

  /**
   * Un PNG passato da ImageMagick (l'EXIF diventa testo: `tEXt` `exif:GPSLatitude` = "45/1, 27/1, 5112/100") con in piu' un `iTXt` XMP con le
   * coordinate di un drone. Nessun chunk `eXIf`.
   */
  fun textPng(): ByteArray {
    val base = ImageFixtures.png(ImageFixtures.noise(96, 64, 4), null, null)
    val text1 = pngChunk("tEXt", Bytes().ascii("exif:GPSLatitude").u8(0).ascii("45/1, 27/1, 5112/100").toBytes())
    val text2 = pngChunk("tEXt", Bytes().ascii("exif:GPSLongitude").u8(0).ascii("9/1, 11/1, 24/1").toBytes())
    val itxt = pngChunk("iTXt", Bytes().ascii("XML:com.adobe.xmp").u8(0).u8(0).u8(0).u8(0).u8(0).utf8(djiXmp()).toBytes())
    val afterIhdr = 8 + 12 + 13
    return Bytes.concat(base.copyOfRange(0, afterIhdr), text1, text2, itxt, base.copyOfRange(afterIhdr, base.size))
  }

  /**
   * Una motion photo di Google con la posizione **solo nella parte video**: il JPEG ha EXIF e XMP puliti, il video ha una traccia `mebx` con le
   * coordinate ISO 6709 (come la registra un iPhone, o un telefono che scrive la traccia della posizione).
   */
  fun motionPhotoWithVideoGps(): ByteArray =
    MotionFixtures.googleV1(ImageFixtures.noise(96, 64, 5), StripFixtures.exifWithoutGps(), appleMebxVideo().bytes)

  /** La parte video di [motionPhotoWithVideoGps], per confrontarla dopo la pulizia. */
  fun motionVideoPart(): Built = appleMebxVideo()

  /** Un HEIC con EXIF GPS e XMP `drone-dji`. */
  fun heic(): ByteArray = StripFixtures.heic(exif = StripFixtures.exifWithGps(), xmp = djiXmp())

  // --- Attrezzi --------------------------------------------------------------------------------------------------------------------

  fun segment(marker: Int, payload: ByteArray): ByteArray = Bytes().u8(0xFF).u8(marker).u16be(payload.size + 2).raw(payload).toBytes()

  fun app1(payload: ByteArray): ByteArray = segment(0xE1, payload)

  /** [segments] subito dopo l'SOI e i segmenti APP gia' presenti (prima delle tabelle). */
  fun insertJpegSegments(jpeg: ByteArray, vararg segments: ByteArray): ByteArray {
    var p = 2
    while (p + 4 <= jpeg.size && (jpeg[p].toInt() and 0xFF) == 0xFF && (jpeg[p + 1].toInt() and 0xFF) in 0xE0..0xEF) {
      p += 2 + (((jpeg[p + 2].toInt() and 0xFF) shl 8) or (jpeg[p + 3].toInt() and 0xFF))
    }
    return Bytes.concat(jpeg.copyOfRange(0, p), *segments, jpeg.copyOfRange(p, jpeg.size))
  }

  fun pngChunk(type: String, data: ByteArray): ByteArray {
    val crc = CRC32().apply { update(type.toByteArray(Charsets.ISO_8859_1)); update(data) }
    return Bytes().u32be(data.size.toLong()).ascii(type).raw(data).u32be(crc.value).toBytes()
  }

  private fun u32(v: Long): ByteArray = Bytes().u32be(v).toBytes()

  private fun ascii(text: String): ByteArray = Bytes.ascii4(text)

  // --- Lettura dei video, solo per i test -------------------------------------------------------------------------------------------

  /** I tipi delle tracce (`hdlr`) del `moov` di un MP4 che comincia a [from]: per dire che il pulito ha ancora video e audio. */
  fun handlers(bytes: ByteArray, from: Int = 0): List<String> {
    val moov = childBox(bytes, from, bytes.size, "moov") ?: return emptyList()
    val out = ArrayList<String>()
    var p = moov.first
    while (p + 8 <= moov.last + 1) {
      val size = u32At(bytes, p).toInt()
      if (size < 8) break
      if (String(bytes, p + 4, 4, Charsets.ISO_8859_1) == "trak") {
        val mdia = childBox(bytes, p + 8, p + size, "mdia")
        val hdlr = mdia?.let { childBox(bytes, it.first, it.last + 1, "hdlr") }
        if (hdlr != null) out += String(bytes, hdlr.first + 8, 4, Charsets.ISO_8859_1)
      }
      p += size
    }
    return out
  }

  /** Il contenuto del primo riquadro [type] fra `[from, to)`, o null. */
  fun childBox(bytes: ByteArray, from: Int, to: Int, type: String): IntRange? {
    var p = from
    while (p + 8 <= to) {
      val size = u32At(bytes, p).toInt()
      if (size < 8 || p + size > to) return null
      if (String(bytes, p + 4, 4, Charsets.ISO_8859_1) == type) return (p + 8) until (p + size)
      p += size
    }
    return null
  }

  private fun u32At(b: ByteArray, i: Int): Long =
    ((b[i].toLong() and 0xFF) shl 24) or ((b[i + 1].toLong() and 0xFF) shl 16) or ((b[i + 2].toLong() and 0xFF) shl 8) or (b[i + 3].toLong() and 0xFF)
}
