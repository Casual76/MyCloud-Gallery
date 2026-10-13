package dev.pampa.mycloudgallery.core.mediastore.sync

import java.util.Locale

/**
 * Se i primi byte di un file sono quelli di un media (E-M12: "byte iniziali non da media"). Guarda solo la testa che
 * l'impronta legge comunque: nessuna lettura in piu'.
 *
 * **Prudente nei due sensi.** Un falso media in Libreria e' un errore; una foto vera nascosta perche' il suo formato
 * non e' in questo elenco e' peggio. Quindi:
 * 1. una firma di immagine o video conosciuta: media;
 * 2. byte tutti uguali (un file azzerato), testo (HTML di un download fallito, JSON, XML, SVG) o la firma di un
 *    formato che non e' un media (PDF, ZIP, eseguibili): **non** media;
 * 3. un MIME dichiarato che ha una firma precisa (JPEG, PNG, HEIC, MP4...) e byte che non la rispettano: non media;
 * 4. tutto il resto: media. Lo decidera' la fase META, che conosce i formati meglio (`Extraction.NotMedia`).
 */
object MediaSniffer {

  fun looksLikeMedia(head: ByteArray, mime: String?): Boolean {
    if (head.isEmpty()) return false
    if (hasMediaSignature(head)) return true
    if (isUniform(head) || hasNonMediaSignature(head) || looksLikeText(head)) return false
    return !hasStrictSignature(mime)
  }

  /** Le firme delle immagini e dei video che il telefono puo' contenere. */
  fun hasMediaSignature(h: ByteArray): Boolean =
    // JPEG (anche MPO e motion photo)
    h.startsWith(0xFF, 0xD8, 0xFF) ||
      // PNG
      h.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) ||
      // GIF87a / GIF89a
      h.startsWithAscii("GIF87a") || h.startsWithAscii("GIF89a") ||
      // RIFF: WebP e AVI
      (h.startsWithAscii("RIFF") && (h.asciiAt(8, "WEBP") || h.asciiAt(8, "AVI "))) ||
      // BMP
      h.startsWithAscii("BM") && h.size >= 14 ||
      // TIFF e i RAW che ne derivano (DNG, CR2, NEF, ARW...), anche BigTIFF
      h.startsWith(0x49, 0x49, 0x2A, 0x00) || h.startsWith(0x4D, 0x4D, 0x00, 0x2A) ||
      h.startsWith(0x49, 0x49, 0x2B, 0x00) || h.startsWith(0x4D, 0x4D, 0x00, 0x2B) ||
      // Olympus ORF, Panasonic RW2, Fujifilm RAF, Sigma X3F
      h.startsWithAscii("IIRO") || h.startsWithAscii("IIRS") || h.startsWithAscii("MMOR") ||
      h.startsWith(0x49, 0x49, 0x55, 0x00) || h.startsWithAscii("FUJIFILMCCD-RAW") || h.startsWithAscii("FOVb") ||
      // ISO BMFF: HEIC, HEIF, AVIF, MP4, MOV, 3GP, CR3...
      h.asciiAt(4, "ftyp") ||
      // QuickTime con altri atomi in testa
      QUICKTIME_ATOMS.any { h.asciiAt(4, it) } ||
      // Matroska / WebM
      h.startsWith(0x1A, 0x45, 0xDF, 0xA3) ||
      // MPEG-TS (pacchetti da 188 byte) e M2TS/AVCHD (192 byte)
      (h.byteAt(0) == 0x47 && h.byteAt(188) == 0x47 && (h.size <= 376 || h.byteAt(376) == 0x47)) ||
      (h.byteAt(4) == 0x47 && h.byteAt(196) == 0x47 && (h.size <= 388 || h.byteAt(388) == 0x47)) ||
      // MPEG-PS e MPEG-1/2 elementare
      h.startsWith(0x00, 0x00, 0x01, 0xBA) || h.startsWith(0x00, 0x00, 0x01, 0xB3) ||
      // FLV, ASF/WMV, Ogg
      h.startsWithAscii("FLV") || h.startsWith(0x30, 0x26, 0xB2, 0x75, 0x8E, 0x66, 0xCF, 0x11) || h.startsWithAscii("OggS") ||
      // JPEG XL (codestream e contenitore), JPEG 2000 (contenitore e codestream)
      h.startsWith(0xFF, 0x0A) || h.startsWith(0x00, 0x00, 0x00, 0x0C, 0x4A, 0x58, 0x4C, 0x20) ||
      h.startsWith(0x00, 0x00, 0x00, 0x0C, 0x6A, 0x50, 0x20, 0x20) || h.startsWith(0xFF, 0x4F, 0xFF, 0x51) ||
      // ICO, CUR, PSD
      h.startsWith(0x00, 0x00, 0x01, 0x00) || h.startsWith(0x00, 0x00, 0x02, 0x00) || h.startsWithAscii("8BPS")

  private fun hasNonMediaSignature(h: ByteArray): Boolean =
    h.startsWithAscii("%PDF") || h.startsWith(0x50, 0x4B, 0x03, 0x04) || h.startsWith(0x7F, 0x45, 0x4C, 0x46) ||
      h.startsWithAscii("Rar!") || h.startsWith(0x37, 0x7A, 0xBC, 0xAF) || h.startsWith(0x1F, 0x8B) ||
      h.startsWithAscii("dex\n")

  /** I primi byte sono testo (controlla al piu' 512 byte): nessun formato di immagine o video che mostriamo lo e'. */
  private fun looksLikeText(h: ByteArray): Boolean {
    val n = minOf(h.size, TEXT_PROBE)
    var start = 0
    // BOM UTF-8
    if (h.startsWith(0xEF, 0xBB, 0xBF)) start = 3
    if (n <= start) return false
    for (i in start until n) {
      val b = h[i].toInt() and 0xFF
      val printable = b == 0x09 || b == 0x0A || b == 0x0D || b in 0x20..0x7E || b >= 0x80
      if (!printable) return false
    }
    return true
  }

  private fun isUniform(h: ByteArray): Boolean {
    val first = h[0]
    for (i in 1 until h.size) if (h[i] != first) return false
    return true
  }

  /** I MIME dei formati con una firma precisa: se i byte non la rispettano, il file non e' quello che dice di essere. */
  private fun hasStrictSignature(mime: String?): Boolean {
    val m = mime?.lowercase(Locale.ROOT) ?: return false
    return m in STRICT_MIMES
  }

  private val QUICKTIME_ATOMS = listOf("moov", "mdat", "wide", "free", "skip", "pnot", "uuid")

  private val STRICT_MIMES = setOf(
    "image/jpeg", "image/jpg", "image/png", "image/gif", "image/webp", "image/heic", "image/heif", "image/avif",
    "image/bmp", "image/tiff", "image/x-adobe-dng", "image/dng",
    "video/mp4", "video/quicktime", "video/3gpp", "video/3gpp2", "video/webm", "video/x-matroska",
  )

  private const val TEXT_PROBE = 512

  private fun ByteArray.byteAt(i: Int): Int = if (i < size) this[i].toInt() and 0xFF else -1

  private fun ByteArray.startsWith(vararg bytes: Int): Boolean {
    if (size < bytes.size) return false
    for (i in bytes.indices) if ((this[i].toInt() and 0xFF) != bytes[i]) return false
    return true
  }

  private fun ByteArray.asciiAt(offset: Int, text: String): Boolean {
    if (size < offset + text.length) return false
    for (i in text.indices) if ((this[offset + i].toInt() and 0xFF) != text[i].code) return false
    return true
  }

  private fun ByteArray.startsWithAscii(text: String): Boolean = asciiAt(0, text)
}
