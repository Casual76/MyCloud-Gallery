package dev.pampa.mycloudgallery.core.metadata.strip

import dev.pampa.mycloudgallery.core.metadata.format.ContainerType
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

// Fase 5B, P11 (docs/fase5-progetto.md D26, E-X5): l'XMP di un'IMMAGINE (JPEG, PNG, WebP), dove stanno i pacchetti e come si puliscono sul
// posto. `ExifInterface` toglie l'EXIF; l'XMP lo lascia com'e' e puo' contenere `exif:GPSLatitude` e sorelle. Ogni formato ha il suo modo di
// ospitarlo:
//  - JPEG: segmenti APP1 con il prefisso `http://ns.adobe.com/xap/1.0/` (e quelli "extended" `.../xmp/extension/`), prima dei dati dell'immagine;
//  - PNG: chunk `iTXt` con la parola chiave `XML:com.adobe.xmp` (e, vecchio stile, i chunk di testo "Raw profile type ...", EXIF/XMP in esadecimale);
//    cambiare il contenuto di un chunk vuol dire rifare il suo CRC;
//  - WebP: chunk `XMP ` del contenitore RIFF.

internal object ImageXmp {

  private const val MAX_SEGMENTS = 4096
  private const val MAX_PACKET = 4 * 1024 * 1024
  private const val XMP_PREFIX = "http://ns.adobe.com/xap/1.0/\u0000"
  private const val EXTENDED_PREFIX = "http://ns.adobe.com/xmp/extension/\u0000"
  private const val EXTENDED_HEADER = 40 // GUID (32) + dimensione totale (4) + posizione (4)
  private const val PNG_XMP_KEYWORD = "XML:com.adobe.xmp"
  private const val PNG_RAW_PROFILE = "Raw profile type"
  private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

  /** Un chunk PNG: dove comincia (la lunghezza), il tipo, i dati. */
  private class Chunk(val start: Long, val type: String, val dataStart: Long, val dataLength: Int)

  /** Il risultato di una pulizia: quanti pacchetti ripuliti e se qualcosa non si sa togliere. */
  class Report(val scrubbed: Int, val blocked: Boolean)

  /** Pulisce sul posto l'XMP di [file] di tipo [type]. */
  fun scrub(file: RawFile, type: ContainerType): Report = when (type) {
    ContainerType.JPEG -> jpeg(file, scrub = true)
    ContainerType.PNG -> png(file, scrub = true)
    ContainerType.WEBP -> webp(file, scrub = true)
    else -> Report(0, false)
  }

  /** Vero se in [file] resta una proprieta' GPS nell'XMP (o un pacchetto che non si sa leggere con GPS dentro). */
  fun hasGps(file: RawFile, type: ContainerType): Boolean = when (type) {
    ContainerType.JPEG -> jpeg(file, scrub = false)
    ContainerType.PNG -> png(file, scrub = false)
    ContainerType.WEBP -> webp(file, scrub = false)
    else -> Report(0, false)
  }.blocked

  /**
   * In modalita' ricontrollo ([scrub] falso) `blocked` vale "c'e' ancora una proprieta' GPS"; in pulizia, "c'e' un pacchetto che non so
   * riscrivere".
   */
  private fun handle(file: RawFile, at: Long, length: Int, scrub: Boolean, tally: IntArray): Boolean {
    if (length <= 0) return false
    if (length > MAX_PACKET) return true // troppo grande per esaminarlo: nel dubbio "c'e'" (ricontrollo) e la pulizia si ferma
    val packet = file.read(at, length)
    if (!scrub) return XmpGpsScrub.hasGps(packet)
    val result = XmpGpsScrub.scrub(packet)
    if (result.unsupported) return true
    if (result.removed > 0) {
      file.write(at, result.bytes)
      tally[0] += result.removed
    }
    return false
  }

  // --- JPEG --------------------------------------------------------------------------------------------------------------------

  private fun jpeg(file: RawFile, scrub: Boolean): Report {
    var blocked = false
    val tally = IntArray(1)
    var pos = 2L
    var segments = 0
    while (segments++ < MAX_SEGMENTS) {
      var marker = file.u8(pos + 1)
      if (file.u8(pos) != 0xFF || marker < 0) break
      // Byte di riempimento 0xFF prima del marcatore.
      var skip = 0
      while (marker == 0xFF && skip++ < 16) {
        pos++
        marker = file.u8(pos + 1)
      }
      if (marker == 0xD9 || marker == 0xDA || marker < 0) break
      if (marker == 0x01 || marker in 0xD0..0xD7) {
        pos += 2
        continue
      }
      val head = file.read(pos + 2, 2)
      if (head.size < 2) break
      val length = head.u16be(0)
      if (length < 2) break
      val payloadStart = pos + 4
      val payloadLength = length - 2
      if (marker == 0xE1 && payloadLength > 0) {
        val probe = file.read(payloadStart, minOf(payloadLength, EXTENDED_PREFIX.length))
        when {
          probe.startsWithAscii(0, XMP_PREFIX) -> blocked = handle(file, payloadStart + XMP_PREFIX.length, payloadLength - XMP_PREFIX.length, scrub, tally) || blocked
          probe.startsWithAscii(0, EXTENDED_PREFIX) ->
            blocked = handle(file, payloadStart + EXTENDED_PREFIX.length + EXTENDED_HEADER, payloadLength - EXTENDED_PREFIX.length - EXTENDED_HEADER, scrub, tally) || blocked
        }
      }
      pos += 2 + length
    }
    return Report(tally[0], blocked)
  }

  // --- PNG ---------------------------------------------------------------------------------------------------------------------

  private fun chunks(file: RawFile): List<Chunk> {
    val out = ArrayList<Chunk>()
    if (!file.read(0, 8).contentEquals(PNG_SIGNATURE)) return out
    var pos = 8L
    val size = file.length
    while (pos + 12 <= size && out.size < MAX_SEGMENTS) {
      val head = file.read(pos, 8)
      if (head.size < 8) break
      val length = head.u32be(0)
      if (length > Int.MAX_VALUE || pos + 12 + length > size) break
      out += Chunk(pos, head.fourcc(4), pos + 8, length.toInt())
      pos += 12 + length
    }
    return out
  }

  private fun png(file: RawFile, scrub: Boolean): Report {
    var blocked = false
    val tally = IntArray(1)
    for (chunk in chunks(file)) {
      when (chunk.type) {
        "iTXt" -> blocked = pngText(file, chunk, scrub, tally, itxt = true) || blocked
        "tEXt", "zTXt" -> blocked = pngText(file, chunk, scrub, tally, itxt = false) || blocked
      }
    }
    return Report(tally[0], blocked)
  }

  private fun pngText(file: RawFile, chunk: Chunk, scrub: Boolean, tally: IntArray, itxt: Boolean): Boolean {
    if (chunk.dataLength > MAX_PACKET) return true
    val data = file.read(chunk.dataStart, chunk.dataLength)
    val keywordEnd = data.indexOfZero(0)
    if (keywordEnd < 0 || keywordEnd > 79) return false
    val keyword = String(data, 0, keywordEnd, Charsets.ISO_8859_1)
    // Il testo "Raw profile type ...": un EXIF o XMP in esadecimale (ImageMagick). Non si pulisce: il chunk intero si neutralizza.
    if (keyword.startsWith(PNG_RAW_PROFILE)) return pngNeutralize(file, chunk, scrub, tally)
    if (!itxt || keyword != PNG_XMP_KEYWORD) return false
    // iTXt: parola chiave\0, compressione (1), metodo (1), lingua\0, parola tradotta\0, testo.
    val compressed = data.getOrElse(keywordEnd + 1) { 0 }.toInt() != 0
    val languageEnd = data.indexOfZero(keywordEnd + 3)
    if (languageEnd < 0) return false
    val translatedEnd = data.indexOfZero(languageEnd + 1)
    if (translatedEnd < 0) return false
    val textStart = translatedEnd + 1
    val textLength = data.size - textStart
    if (textLength <= 0) return false
    if (compressed) {
      // Compresso non si riscrive sul posto: se non ha proprieta' GPS va bene cosi', altrimenti la condivisione si ferma.
      val text = inflate(data, textStart, textLength) ?: return true
      return XmpGpsScrub.hasGps(text)
    }
    if (!scrub) return XmpGpsScrub.hasGps(data.copyOfRange(textStart, data.size))
    val result = XmpGpsScrub.scrub(data.copyOfRange(textStart, data.size))
    if (result.unsupported) return true
    if (result.removed > 0) {
      file.write(chunk.dataStart + textStart, result.bytes)
      refreshCrc(file, chunk)
      tally[0] += result.removed
    }
    return false
  }

  /** Un chunk di testo da buttare: il tipo diventa uno privato (`mcGx`, che ogni lettore ignora), il contenuto zeri, il CRC rifatto. */
  private fun pngNeutralize(file: RawFile, chunk: Chunk, scrub: Boolean, tally: IntArray): Boolean {
    if (!scrub) return true
    file.write(chunk.start + 4, "mcGx".toByteArray(Charsets.ISO_8859_1))
    file.zero(chunk.dataStart, chunk.dataLength.toLong())
    refreshCrc(file, chunk, typeAt = "mcGx")
    tally[0] += 1
    return false
  }

  /** Il CRC di un chunk PNG copre il tipo e i dati: dopo una patch si rifa'. */
  private fun refreshCrc(file: RawFile, chunk: Chunk, typeAt: String? = null) {
    val crc = CRC32()
    crc.update((typeAt ?: chunk.type).toByteArray(Charsets.ISO_8859_1))
    crc.update(file.read(chunk.dataStart, chunk.dataLength))
    val value = crc.value
    file.write(
      chunk.dataStart + chunk.dataLength,
      byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte()),
    )
  }

  private fun inflate(data: ByteArray, from: Int, length: Int): ByteArray? {
    val inflater = Inflater()
    return try {
      inflater.setInput(data, from, length)
      val out = java.io.ByteArrayOutputStream()
      val buffer = ByteArray(8 * 1024)
      while (!inflater.finished() && out.size() <= MAX_PACKET) {
        val n = inflater.inflate(buffer)
        if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
        out.write(buffer, 0, n)
      }
      if (inflater.finished() && out.size() <= MAX_PACKET) out.toByteArray() else null
    } catch (_: DataFormatException) {
      null
    } finally {
      inflater.end()
    }
  }

  private fun ByteArray.indexOfZero(from: Int): Int {
    var i = maxOf(from, 0)
    while (i < size) {
      if (this[i] == 0.toByte()) return i
      i++
    }
    return -1
  }

  // --- WebP --------------------------------------------------------------------------------------------------------------------

  private fun webp(file: RawFile, scrub: Boolean): Report {
    val head = file.read(0, 12)
    if (head.size < 12 || !head.startsWithAscii(0, "RIFF") || !head.startsWithAscii(8, "WEBP")) return Report(0, false)
    var blocked = false
    val tally = IntArray(1)
    var pos = 12L
    val end = minOf(file.length, 8 + head.u32le(4))
    var count = 0
    while (pos + 8 <= end && count++ < MAX_SEGMENTS) {
      val h = file.read(pos, 8)
      if (h.size < 8) break
      val length = h.u32le(4)
      if (length > Int.MAX_VALUE || pos + 8 + length > file.length) break
      if (h.fourcc(0) == "XMP ") blocked = handle(file, pos + 8, length.toInt(), scrub, tally) || blocked
      pos += 8 + length + (length and 1) // i chunk RIFF sono allineati a due byte
    }
    return Report(tally[0], blocked)
  }
}

