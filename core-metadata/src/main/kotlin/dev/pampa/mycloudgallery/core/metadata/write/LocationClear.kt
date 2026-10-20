package dev.pampa.mycloudgallery.core.metadata.write

import dev.pampa.mycloudgallery.core.metadata.format.ContainerType
import dev.pampa.mycloudgallery.core.metadata.strip.EmbeddedIso
import dev.pampa.mycloudgallery.core.metadata.strip.IsoClean
import dev.pampa.mycloudgallery.core.metadata.strip.RawFile
import dev.pampa.mycloudgallery.core.metadata.strip.fourcc
import dev.pampa.mycloudgallery.core.metadata.strip.startsWithAscii
import dev.pampa.mycloudgallery.core.metadata.strip.u16be
import dev.pampa.mycloudgallery.core.metadata.strip.u32be
import dev.pampa.mycloudgallery.core.metadata.strip.u32le
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.CRC32

// Correzioni 5B (R12, docs/review/5b-fase5b.md): "togli il luogo" (D19, `GeoChange.Clear`) non toglie piu' solo l'EXIF. Dopo `ExifInterface`, che ha
// tolto tutto il blocco GPS e la nota del produttore, sulla copia corretta (mai sul file vero) si toglie il luogo **sul posto** anche da:
//  - l'XMP: nel pacchetto principale ogni proprieta' del luogo diventa spazi (`XmpLocationScrub`); i pezzi dell'XMP esteso e l'APP13 di Photoshop
//    (che porta copie dell'EXIF, dell'XMP e i nomi di luogo IPTC) si azzerano interi;
//  - i PNG: l'XMP in `iTXt`/`tEXt` si pulisce (con il CRC rifatto); un XMP compresso, i testi "Raw profile type" di ImageMagick e i testi con un nome
//    del luogo (`exif:GPSLatitude`) diventano un chunk privato azzerato;
//  - il WebP: il chunk `XMP `;
//  - la parte video di una motion photo: si ricostruisce come un video condiviso senza posizione (`IsoClean`: solo audio e video).
// Poi il controllo: lo stesso rilevatore indipendente delle condivisioni (`LocationTraces`) rilegge la copia. Se non si puo' pulire, o il
// rilevatore trova ancora qualcosa, la correzione non si scrive nel file (`WriteUnsupported.FORMAT`: resta nell'app, come per i formati della Q6).

internal object LocationClear {

  private const val MAX_SEGMENTS = 65_536
  private const val BLOCK = 1 shl 20
  private val XMP_MAIN = "http://ns.adobe.com/xap/1.0/\u0000"
  private val XMP_EXTENSION = "http://ns.adobe.com/xmp/extension/\u0000"
  private const val PNG_XMP = "XML:com.adobe.xmp"
  private const val RAW_PROFILE = "Raw profile type"

  /** Toglie il luogo sul posto da [file] di tipo [type]. Falso se qualcosa non si sa pulire (o il file non torna): chi chiama non scrive. */
  fun inPlace(file: File, type: ContainerType): Boolean = try {
    RandomAccessFile(file, "rw").use { raf ->
      val raw = RawFile(raf)
      when (type) {
        ContainerType.JPEG -> jpeg(raw)
        ContainerType.PNG -> png(raw)
        ContainerType.WEBP -> webp(raw)
        else -> false
      }
    }
  } catch (_: IOException) {
    false
  } catch (_: SecurityException) {
    false
  }

  // --- JPEG ----------------------------------------------------------------------------------------------------------------------

  private fun jpeg(file: RawFile): Boolean {
    var pos = 2L
    var segments = 0
    val size = file.length
    while (pos + 2 <= size) {
      if (++segments > MAX_SEGMENTS) return false
      if (file.u8(pos) != 0xFF) return false
      var marker = file.u8(pos + 1)
      var fill = 0
      while (marker == 0xFF && fill++ < 64) {
        pos++
        marker = file.u8(pos + 1)
      }
      when {
        marker < 0 -> return false
        marker == 0xD9 -> {
          // La parte video di una motion photo, in coda: solo audio e video.
          val video = EmbeddedIso.first(file, pos + 2, size) ?: return true
          return IsoClean.clean(file, video.start, video.end) == null
        }
        marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7 -> {
          pos += 2
          continue
        }
      }
      val length = file.read(pos + 2, 2).let { if (it.size < 2) return false else it.u16be(0) }
      if (length < 2 || pos + 2 + length > size) return false
      val payload = pos + 4
      val payloadLength = length - 2
      when (marker) {
        0xE1 -> {
          val head = file.read(payload, minOf(payloadLength, XMP_EXTENSION.length))
          when {
            head.startsWithAscii(0, XMP_MAIN) -> if (!scrub(file, payload + XMP_MAIN.length, payloadLength - XMP_MAIN.length)) return false
            head.startsWithAscii(0, XMP_EXTENSION) -> file.zero(payload, payloadLength.toLong())
          }
        }
        // Photoshop: copie dell'EXIF e dell'XMP, nomi di luogo IPTC.
        0xED -> file.zero(payload, payloadLength.toLong())
      }
      pos += 2 + length
      if (marker == 0xDA) pos = entropyEnd(file, pos) ?: return false
    }
    return false
  }

  private fun entropyEnd(file: RawFile, from: Long): Long? {
    var block = from
    while (block < file.length) {
      val data = file.read(block, BLOCK)
      if (data.size < 2) return null
      for (i in 0 until data.size - 1) {
        if (data[i] == 0xFF.toByte()) {
          val next = data[i + 1].toInt() and 0xFF
          if (next != 0x00 && next !in 0xD0..0xD7 && next != 0xFF) return block + i
        }
      }
      block += data.size - 1
    }
    return null
  }

  /** Un pacchetto XMP sul posto: stessa lunghezza. */
  private fun scrub(file: RawFile, at: Long, length: Int): Boolean {
    if (length <= 0) return true
    if (length > XmpLocationScrub.MAX_PACKET) return false
    val result = XmpLocationScrub.scrub(file.read(at, length))
    if (result.unsupported) return false
    if (result.removed > 0) file.write(at, result.bytes)
    return true
  }

  // --- PNG -----------------------------------------------------------------------------------------------------------------------

  private fun png(file: RawFile): Boolean {
    var pos = 8L
    var count = 0
    val size = file.length
    while (pos + 12 <= size) {
      if (++count > MAX_SEGMENTS) return false
      val head = file.read(pos, 8)
      val length = head.u32be(0)
      val type = head.fourcc(4)
      if (length > Int.MAX_VALUE || pos + 12 + length > size) return false
      val dataAt = pos + 8
      when (type) {
        "IEND" -> return true
        "tEXt", "zTXt", "iTXt" -> if (!pngText(file, type, pos, dataAt, length.toInt())) return false
      }
      pos += 12 + length
    }
    return false
  }

  private fun pngText(file: RawFile, type: String, chunkStart: Long, dataAt: Long, length: Int): Boolean {
    if (length <= 0) return true
    val data = file.read(dataAt, minOf(length, 80))
    val keywordEnd = data.indexOf(0.toByte())
    val keyword = if (keywordEnd < 0) "" else String(data, 0, keywordEnd, Charsets.ISO_8859_1)
    val textAt: Long? = when {
      keyword != PNG_XMP || keywordEnd < 0 -> null
      type == "tEXt" -> dataAt + keywordEnd + 1
      type == "iTXt" -> {
        // parola chiave\0, compressione (1), metodo (1), lingua\0, parola tradotta\0, testo. Compresso non si riscrive sul posto.
        val all = file.read(dataAt, length)
        val compressed = all.getOrElse(keywordEnd + 1) { 1 }.toInt() != 0
        val languageEnd = indexOfZero(all, keywordEnd + 3)
        val translatedEnd = if (languageEnd < 0) -1 else indexOfZero(all, languageEnd + 1)
        if (compressed || translatedEnd < 0) null else dataAt + translatedEnd + 1
      }
      else -> null
    }
    if (textAt != null) {
      if (!scrub(file, textAt, (dataAt + length - textAt).toInt())) return false
      refreshCrc(file, chunkStart, type, dataAt, length)
      return true
    }
    // Un XMP compresso, i profili "Raw profile type" e i testi con un nome del luogo: il chunk diventa privato e vuoto. Gli altri testi restano.
    val placeName = keyword.substringAfterLast(':').lowercase().let { n -> listOf("gps", "latitude", "longitude", "longtitude", "location").any { it in n } }
    if (keyword == PNG_XMP || keyword.startsWith(RAW_PROFILE) || placeName) {
      file.write(chunkStart + 4, "mcGx".toByteArray(Charsets.ISO_8859_1))
      file.zero(dataAt, length.toLong())
      refreshCrc(file, chunkStart, "mcGx", dataAt, length)
    }
    return true
  }

  private fun refreshCrc(file: RawFile, chunkStart: Long, type: String, dataAt: Long, length: Int) {
    val crc = CRC32()
    crc.update(type.toByteArray(Charsets.ISO_8859_1))
    crc.update(file.read(dataAt, length))
    val value = crc.value
    file.write(chunkStart + 8 + length, byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte()))
  }

  private fun indexOfZero(data: ByteArray, from: Int): Int {
    var i = maxOf(from, 0)
    while (i < data.size) {
      if (data[i] == 0.toByte()) return i
      i++
    }
    return -1
  }

  // --- WebP ----------------------------------------------------------------------------------------------------------------------

  private fun webp(file: RawFile): Boolean {
    val head = file.read(0, 12)
    if (head.size < 12 || !head.startsWithAscii(0, "RIFF") || !head.startsWithAscii(8, "WEBP")) return false
    val end = minOf(file.length, 8 + head.u32le(4))
    var pos = 12L
    var count = 0
    while (pos + 8 <= end) {
      if (++count > MAX_SEGMENTS) return false
      val h = file.read(pos, 8)
      val length = h.u32le(4)
      if (length > Int.MAX_VALUE || pos + 8 + length > file.length) return false
      if (h.fourcc(0) == "XMP " && !scrub(file, pos + 8, length.toInt())) return false
      pos += 8 + length + (length and 1)
    }
    return true
  }
}
