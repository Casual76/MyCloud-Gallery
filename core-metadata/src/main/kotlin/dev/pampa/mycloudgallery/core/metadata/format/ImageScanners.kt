package dev.pampa.mycloudgallery.core.metadata.format

import dev.pampa.mycloudgallery.core.model.ExifOrientation

/**
 * Cio' che un'analisi del contenitore trova, senza decodificare l'immagine.
 *
 * @property exifTiff il blocco TIFF dell'EXIF (comincia con `II*` o `MM*`), se il formato lo porta in un riquadro;
 *   per i TIFF e i DNG e' il file stesso e questo campo resta nullo.
 * @property xmp il pacchetto XMP, come testo.
 * @property heifOrientation l'orientamento dato da `irot` e `imir` in un HEIC: ha la precedenza sull'EXIF.
 * @property truncated la struttura dice che il file finisce prima del previsto.
 * @property malformed il formato e' riconosciuto ma l'intestazione non si legge.
 */
internal class ImageScan(
  val width: Int? = null,
  val height: Int? = null,
  val exifTiff: ByteArray? = null,
  val xmp: String? = null,
  val heifOrientation: ExifOrientation? = null,
  val animated: Boolean = false,
  val truncated: Boolean = false,
  val malformed: Boolean = false,
  /** Solo JPEG: l'EOI (`FF D9`) si trova nella coda. */
  val jpegEndFound: Boolean = true,
  /**
   * Solo JPEG: nella coda c'e' qualcosa di aggiunto dopo l'immagine (video di una motion photo, trailer SEF, un secondo
   * JPEG), oppure non e' piu' il flusso compresso di un'immagine.
   */
  val trailerEvidence: Boolean = false,
)

private const val MAX_XMP_BYTES = 2 * 1024 * 1024
private const val MAX_EXIF_BYTES = 1024 * 1024

private val EXIF_HEADER = asciiBytes("Exif\u0000\u0000")

/** Toglie l'intestazione `Exif\0\0` da un blocco EXIF, se c'e': resta il TIFF (`II*` o `MM*`). */
internal fun stripExifHeader(block: ByteArray): ByteArray? {
  val start = if (block.hasAt(0, EXIF_HEADER)) EXIF_HEADER.size else 0
  if (block.size - start < 8) return null
  val a = block.u8(start)
  val b = block.u8(start + 1)
  val tiff = (a == 'I'.code && b == 'I'.code) || (a == 'M'.code && b == 'M'.code)
  return if (tiff) block.copyOfRange(start, block.size) else null
}

internal object JpegScanner {

  private const val MAX_SEGMENTS = 400
  private const val TAIL = 64 * 1024
  private val XMP_ID = asciiBytes("http://ns.adobe.com/xap/1.0/\u0000")
  private val TRAILER_FTYP = asciiBytes("ftyp")
  private val TRAILER_SEFT = asciiBytes("SEFT")
  private val TRAILER_SEFH = asciiBytes("SEFH")
  private val TRAILER_MPVD = asciiBytes("mpvd")

  suspend fun scan(ra: RandomAccess): ImageScan {
    var width: Int? = null
    var height: Int? = null
    var exifTiff: ByteArray? = null
    var xmp: String? = null
    var pos = 2L
    var reachedScan = false
    var scanDataStart = 0L
    var headerTruncated = false
    var segments = 0
    while (segments++ < MAX_SEGMENTS) {
      val marker = ra.read(pos, 4)
      if (marker.size < 2) { headerTruncated = true; break }
      if (marker.u8(0) != 0xFF) break // non e' piu' una sequenza di segmenti
      val code = marker.u8(1)
      if (code == 0xFF) { pos += 1; continue } // byte di riempimento
      if (code == 0x00) break
      if (code == 0xD9) break // EOI prima del SOS: niente immagine
      if (code == 0xD8 || code == 0x01 || code in 0xD0..0xD7) { pos += 2; continue }
      if (marker.size < 4) { headerTruncated = true; break }
      val length = marker.u16be(2)
      if (length < 2) break
      val payloadStart = pos + 4
      val payloadLength = length - 2
      if (payloadStart + payloadLength > ra.size) { headerTruncated = true; break }
      when {
        code == 0xDA -> { reachedScan = true; scanDataStart = payloadStart + payloadLength; break }
        code == 0xE1 && payloadLength >= 6 -> {
          val head = ra.read(payloadStart, minOf(payloadLength, XMP_ID.size))
          if (exifTiff == null && head.hasAt(0, EXIF_HEADER)) {
            if (payloadLength <= MAX_EXIF_BYTES) exifTiff = stripExifHeader(ra.read(payloadStart, payloadLength))
          } else if (xmp == null && head.hasAt(0, XMP_ID) && payloadLength <= MAX_XMP_BYTES) {
            val body = ra.read(payloadStart + XMP_ID.size, payloadLength - XMP_ID.size)
            xmp = String(body, Charsets.UTF_8)
          }
        }
        isStartOfFrame(code) && payloadLength >= 6 && width == null -> {
          val sof = ra.read(payloadStart, 6)
          val h = sof.u16be(1)
          val w = sof.u16be(3)
          if (w > 0 && h > 0) { width = w; height = h }
        }
      }
      pos = payloadStart + payloadLength
    }

    val tail = ra.tail(TAIL)
    val endFound = tail.lastIndexOf(byteArrayOf(0xFF.toByte(), 0xD9.toByte())) >= 0
    var evidence = tail.indexOf(TRAILER_FTYP) >= 0 || tail.indexOf(TRAILER_MPVD) >= 0 ||
      (tail.indexOf(TRAILER_SEFH) >= 0 && tail.lastIndexOf(TRAILER_SEFT) >= 0)
    if (!endFound && !evidence && reachedScan) {
      // Senza EOI: o il file e' tagliato in mezzo ai dati compressi, o dopo l'immagine c'e' altro (un video senza `ftyp` nella
      // coda, per esempio). I dati compressi di un JPEG non hanno mai `FF` seguito da altro che `00` o un RSTn: se nella coda
      // (dopo l'intestazione di scansione) c'e' altro, e' materiale aggiunto e il file non va dichiarato tagliato.
      val from = maxOf(scanDataStart, ra.size - TAIL)
      val window = ra.read(from, (ra.size - from).toInt())
      evidence = !looksLikeCompressedData(window)
    }
    val malformed = !headerTruncated && width == null
    return ImageScan(
      width = width,
      height = height,
      exifTiff = exifTiff,
      xmp = xmp,
      truncated = headerTruncated || (width != null && !reachedScan),
      malformed = malformed,
      jpegEndFound = endFound,
      trailerEvidence = evidence,
    )
  }

  private fun isStartOfFrame(code: Int): Boolean = code in 0xC0..0xCF && code != 0xC4 && code != 0xC8 && code != 0xCC

  /**
   * Vero se ogni `FF` e' seguito da `00` o da un marcatore di ripartenza (`D0..D7`): e' il flusso compresso di un JPEG. Una coda
   * quasi tutta di zeri e' un riempimento aggiunto dopo l'EOI, non dati compressi.
   */
  internal fun looksLikeCompressedData(data: ByteArray): Boolean {
    if (data.size >= 256 && data.count { it == 0.toByte() } > data.size * 9 / 10) return false
    var i = 0
    while (i < data.size - 1) {
      if (data.u8(i) == 0xFF) {
        val next = data.u8(i + 1)
        if (next != 0x00 && next !in 0xD0..0xD7 && next != 0xFF) return false
        // FF FF e' riempimento: si riparte dal secondo.
        i += if (next == 0xFF) 1 else 2
      } else {
        i++
      }
    }
    return true
  }
}

internal object PngScanner {

  private const val MAX_CHUNKS = 256
  private val IEND = asciiBytes("IEND")
  private val XMP_KEY = asciiBytes("XML:com.adobe.xmp\u0000")

  suspend fun scan(ra: RandomAccess): ImageScan {
    var width: Int? = null
    var height: Int? = null
    var exifTiff: ByteArray? = null
    var xmp: String? = null
    var pos = 8L
    var count = 0
    var reachedData = false
    var bad = false
    var cut = false
    while (count++ < MAX_CHUNKS) {
      val header = ra.read(pos, 8)
      if (header.size < 8) break
      val length = header.u32be(0)
      val type = header.fourcc(4)
      val dataStart = pos + 8
      if (count == 1 && type != "IHDR") { bad = true; break }
      if (length > Int.MAX_VALUE || dataStart + length + 4 > ra.size) {
        // Il riquadro esce dal file: il file e' troncato (o la lunghezza e' falsa; in ogni caso non si decodifica).
        cut = true
        break
      }
      when (type) {
        "IHDR" -> if (length >= 8) {
          val ihdr = ra.read(dataStart, 8)
          val w = ihdr.u32be(0)
          val h = ihdr.u32be(4)
          if (w in 1..Int.MAX_VALUE && h in 1..Int.MAX_VALUE) { width = w.toInt(); height = h.toInt() } else bad = true
        } else bad = true
        "eXIf" -> if (exifTiff == null && length <= MAX_EXIF_BYTES) exifTiff = stripExifHeader(ra.read(dataStart, length.toInt()))
        "iTXt" -> if (xmp == null && length in 1..MAX_XMP_BYTES) {
          val body = ra.read(dataStart, length.toInt())
          if (body.hasAt(0, XMP_KEY) && body.size > XMP_KEY.size + 4) {
            // compressione (1 byte), metodo (1), lingua e parola chiave tradotta (due stringhe con NUL)
            val compressed = body.u8(XMP_KEY.size) != 0
            var i = XMP_KEY.size + 2
            var nul = 0
            while (i < body.size && nul < 2) { if (body[i] == 0.toByte()) nul++; i++ }
            if (!compressed && nul == 2) xmp = String(body, i, body.size - i, Charsets.UTF_8)
          }
        }
        "IDAT" -> reachedData = true
      }
      if (bad || reachedData) break
      pos = dataStart + length + 4
    }
    val tail = ra.tail(64 * 1024)
    val ended = tail.indexOf(IEND) >= 0
    return ImageScan(
      width = width,
      height = height,
      exifTiff = exifTiff,
      xmp = xmp,
      truncated = !bad && (cut || (width != null && !ended)),
      malformed = bad || (width == null && !cut),
    )
  }
}

internal object WebpScanner {

  private const val MAX_CHUNKS = 4096

  suspend fun scan(ra: RandomAccess): ImageScan {
    val riff = ra.read(0, 12)
    if (riff.size < 12) return ImageScan(truncated = true)
    val declaredEnd = riff.u32le(4) + 8
    var truncated = declaredEnd > ra.size
    var width: Int? = null
    var height: Int? = null
    var exifTiff: ByteArray? = null
    var xmp: String? = null
    var animated = false
    var pos = 12L
    var count = 0
    val limit = minOf(ra.size, declaredEnd)
    while (count++ < MAX_CHUNKS && pos + 8 <= limit) {
      val header = ra.read(pos, 8)
      if (header.size < 8) break
      val type = header.fourcc(0)
      val length = header.u32le(4)
      val dataStart = pos + 8
      if (dataStart + length > ra.size) { truncated = true; break }
      when (type) {
        "VP8X" -> if (length >= 10) {
          val body = ra.read(dataStart, 10)
          if (body.u8(0) and 0x02 != 0) animated = true
          width = (body.u8(4) or (body.u8(5) shl 8) or (body.u8(6) shl 16)) + 1
          height = (body.u8(7) or (body.u8(8) shl 8) or (body.u8(9) shl 16)) + 1
        }
        "VP8 " -> if (width == null && length >= 10) {
          val body = ra.read(dataStart, 10)
          if (body.u8(3) == 0x9D && body.u8(4) == 0x01 && body.u8(5) == 0x2A) {
            width = body.u16le(6) and 0x3FFF
            height = body.u16le(8) and 0x3FFF
          }
        }
        "VP8L" -> if (width == null && length >= 5) {
          val body = ra.read(dataStart, 5)
          if (body.u8(0) == 0x2F) {
            val bits = body.u32le(1)
            width = (bits and 0x3FFF).toInt() + 1
            height = ((bits shr 14) and 0x3FFF).toInt() + 1
          }
        }
        "ANIM" -> animated = true
        "EXIF" -> if (exifTiff == null && length in 8..MAX_EXIF_BYTES) exifTiff = stripExifHeader(ra.read(dataStart, length.toInt()))
        "XMP " -> if (xmp == null && length in 1..MAX_XMP_BYTES) xmp = String(ra.read(dataStart, length.toInt()), Charsets.UTF_8)
      }
      pos = dataStart + length + (length and 1L)
    }
    return ImageScan(width = width, height = height, exifTiff = exifTiff, xmp = xmp, animated = animated, truncated = truncated, malformed = !truncated && width == null)
  }
}

internal object GifScanner {
  /** Larghezza e altezza dello schermo logico (6 byte di firma, poi due interi a 16 bit little-endian). */
  fun scan(head: ByteArray): ImageScan {
    if (head.size < 10) return ImageScan(truncated = true)
    val w = head.u16le(6)
    val h = head.u16le(8)
    if (w == 0 || h == 0) return ImageScan(malformed = true, animated = true)
    return ImageScan(width = w, height = h, animated = true)
  }
}

internal object BmpScanner {
  fun scan(head: ByteArray, size: Long): ImageScan {
    if (head.size < 26) return ImageScan(truncated = true)
    val declared = head.u32le(2)
    val dib = head.u32le(14)
    val w: Long
    val h: Long
    if (dib == 12L) {
      w = head.u16le(18).toLong()
      h = head.u16le(20).toLong()
    } else {
      w = head.u32le(18).toInt().toLong()
      h = kotlin.math.abs(head.u32le(22).toInt().toLong())
    }
    if (w <= 0 || h <= 0 || w > TiffFile.MAX_SIDE || h > TiffFile.MAX_SIDE) return ImageScan(malformed = true)
    return ImageScan(width = w.toInt(), height = h.toInt(), truncated = declared > size)
  }
}
