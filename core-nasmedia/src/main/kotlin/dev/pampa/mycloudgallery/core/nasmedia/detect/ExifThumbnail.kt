package dev.pampa.mycloudgallery.core.nasmedia.detect

// La MINIATURA EXIF incorporata in un JPEG (docs/fase4-progetto.md D17): la fotocamera ne scrive una da 160x120 circa
// dentro l'APP1 `Exif`, quasi sempre nei primi 64 KiB. Letta al rilevamento, e' la miniatura **provvisoria** della
// griglia finche' la preparazione non ne produce una vera; resta solo locale.
//
// Lettura difensiva (§4.3): il file e' un dato del NAS. Ogni indice e ogni lunghezza si controllano **prima** di usarli,
// nessuna allocazione parte da un valore dichiarato oltre i tetti, e un dato incoerente vale "nessuna miniatura".
// Nessuna decodifica qui: le dimensioni si leggono dall'intestazione SOF (controllo di proporzione) e la decodifica, con
// `ImageBounds`, la fa chi mostra la miniatura.

internal object ExifThumbnail {

  /** Una miniatura vera pesa 5-20 KB; oltre questa soglia non e' una miniatura da griglia. */
  const val MAX_BYTES: Int = 48 * 1024

  /** Lato massimo ammesso per una miniatura incorporata. */
  const val MAX_SIDE: Int = 1024

  private const val MAX_IFD_ENTRIES = 512
  private const val MAX_MARKERS = 64

  /** La miniatura JPEG dell'IFD1 nell'EXIF di [head] (i primi byte del file), o `null` se non c'e' o non e' attendibile. */
  fun find(head: ByteArray): ByteArray? {
    if (head.size < 12 || head.u8(0) != 0xFF || head.u8(1) != 0xD8) return null
    var pos = 2
    var guard = 0
    while (pos + 4 <= head.size && guard++ < MAX_MARKERS) {
      if (head.u8(pos) != 0xFF) return null
      val marker = head.u8(pos + 1)
      if (marker == 0xFF) {
        pos++
        continue
      }
      if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) {
        pos += 2
        continue
      }
      if (marker == 0xDA || marker == 0xD9) return null
      val length = head.u16(pos + 2, false)
      if (length < 2) return null
      val payload = pos + 4
      val end = pos + 2 + length
      if (marker == 0xE1 && payload + 6 <= head.size && head.matches(payload, EXIF_ID)) {
        return fromTiff(head, payload + 6, minOf(end, head.size))
      }
      if (end > head.size) return null
      pos = end
    }
    return null
  }

  private fun fromTiff(b: ByteArray, tiff: Int, limit: Int): ByteArray? {
    if (tiff + 8 > limit) return null
    val little = when {
      b.u8(tiff) == 'I'.code && b.u8(tiff + 1) == 'I'.code -> true
      b.u8(tiff) == 'M'.code && b.u8(tiff + 1) == 'M'.code -> false
      else -> return null
    }
    if (b.u16(tiff + 2, little) != 42) return null
    val ifd0 = b.u32(tiff + 4, little)
    val count0 = b.entryCount(tiff, ifd0, limit, little) ?: return null
    val next = tiff.toLong() + ifd0 + 2 + count0 * 12L
    if (next + 4 > limit) return null
    val ifd1 = b.u32(next.toInt(), little)
    if (ifd1 <= 0L) return null
    val count1 = b.entryCount(tiff, ifd1, limit, little) ?: return null

    var offset = -1L
    var length = -1L
    var compression = -1
    for (i in 0 until count1) {
      val entry = (tiff + ifd1 + 2 + i * 12L).toInt()
      if (entry + 12 > limit) return null
      when (b.u16(entry, little)) {
        TAG_COMPRESSION -> compression = b.entryValue(entry, little).toInt()
        TAG_JPEG_OFFSET -> offset = b.entryValue(entry, little)
        TAG_JPEG_LENGTH -> length = b.entryValue(entry, little)
      }
    }
    if (compression != -1 && compression != COMPRESSION_JPEG) return null
    if (offset <= 0 || length <= 0 || length > MAX_BYTES) return null
    val start = tiff.toLong() + offset
    if (start + length > limit) return null
    val jpeg = b.copyOfRange(start.toInt(), (start + length).toInt())
    if (jpeg.size < 4 || jpeg.u8(0) != 0xFF || jpeg.u8(1) != 0xD8 || jpeg.u8(2) != 0xFF) return null
    return jpeg
  }

  /** Larghezza e altezza dichiarate dal primo marcatore SOF, o `null` (dati incoerenti o fuori dai tetti). */
  fun jpegSize(jpeg: ByteArray): Pair<Int, Int>? {
    if (jpeg.size < 4 || jpeg.u8(0) != 0xFF || jpeg.u8(1) != 0xD8) return null
    var pos = 2
    var guard = 0
    while (pos + 4 <= jpeg.size && guard++ < MAX_MARKERS) {
      if (jpeg.u8(pos) != 0xFF) return null
      val marker = jpeg.u8(pos + 1)
      if (marker == 0xFF) {
        pos++
        continue
      }
      if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) {
        pos += 2
        continue
      }
      if (marker == 0xD9 || marker == 0xDA) return null
      val length = jpeg.u16(pos + 2, false)
      if (length < 2) return null
      if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
        if (pos + 9 > jpeg.size) return null
        val height = jpeg.u16(pos + 5, false)
        val width = jpeg.u16(pos + 7, false)
        return if (width in 1..MAX_SIDE && height in 1..MAX_SIDE) width to height else null
      }
      pos += 2 + length
    }
    return null
  }

  /**
   * La miniatura con il **suo** orientamento EXIF: la miniatura sta nell'orientamento del sensore, la foto si mostra
   * ruotata. Con un APP1 `Exif` di una sola voce (Orientation) dopo l'SOI, i decodificatori che rispettano l'EXIF (come
   * `ImageDecoder`) la mostrano dritta senza che chi la legge sappia nulla della foto. Orientamento 1 (o fuori norma)
   * o miniatura che ha gia' un APP1: restituita com'e'.
   */
  fun withOrientation(jpeg: ByteArray, exifOrientation: Int): ByteArray {
    if (exifOrientation !in 2..8 || jpeg.size < 4 || jpeg.u8(0) != 0xFF || jpeg.u8(1) != 0xD8) return jpeg
    if (hasExifSegment(jpeg)) return jpeg
    val app1 = byteArrayOf(
      0xFF.toByte(), 0xE1.toByte(), 0x00, 0x22, // marcatore APP1, lunghezza 34
      'E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0,
      'M'.code.toByte(), 'M'.code.toByte(), 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08, // intestazione TIFF, IFD0 a 8
      0x00, 0x01, // una voce
      0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, exifOrientation.toByte(), 0x00, 0x00, // Orientation (SHORT)
      0x00, 0x00, 0x00, 0x00, // nessun IFD1
    )
    val out = ByteArray(jpeg.size + app1.size)
    System.arraycopy(jpeg, 0, out, 0, 2)
    System.arraycopy(app1, 0, out, 2, app1.size)
    System.arraycopy(jpeg, 2, out, 2 + app1.size, jpeg.size - 2)
    return out
  }

  private fun hasExifSegment(jpeg: ByteArray): Boolean {
    var pos = 2
    var guard = 0
    while (pos + 4 <= jpeg.size && guard++ < MAX_MARKERS) {
      if (jpeg.u8(pos) != 0xFF) return false
      val marker = jpeg.u8(pos + 1)
      if (marker == 0xFF) {
        pos++
        continue
      }
      if (marker == 0xDA || marker == 0xD9 || marker in 0xC0..0xCF) return false
      val length = jpeg.u16(pos + 2, false)
      if (length < 2) return false
      if (marker == 0xE1 && pos + 10 <= jpeg.size && jpeg.matches(pos + 4, EXIF_ID)) return true
      pos += 2 + length
    }
    return false
  }

  private fun ByteArray.entryCount(tiff: Int, ifdOffset: Long, limit: Int, little: Boolean): Int? {
    if (ifdOffset < 8) return null
    val at = tiff.toLong() + ifdOffset
    if (at + 2 > limit) return null
    val count = u16(at.toInt(), little)
    if (count > MAX_IFD_ENTRIES) return null
    if (at + 2 + count * 12L > limit) return null
    return count
  }

  /** Il valore di una voce IFD di tipo SHORT (3) o LONG (4) con un solo elemento; -1 per ogni altra forma. */
  private fun ByteArray.entryValue(entry: Int, little: Boolean): Long {
    val type = u16(entry + 2, little)
    val count = u32(entry + 4, little)
    if (count != 1L) return -1
    return when (type) {
      3 -> u16(entry + 8, little).toLong()
      4 -> u32(entry + 8, little)
      else -> -1
    }
  }

  private val EXIF_ID = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)

  private const val TAG_COMPRESSION = 0x0103
  private const val TAG_JPEG_OFFSET = 0x0201
  private const val TAG_JPEG_LENGTH = 0x0202
  private const val COMPRESSION_JPEG = 6

  private fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF

  private fun ByteArray.u16(i: Int, little: Boolean): Int =
    if (little) u8(i) or (u8(i + 1) shl 8) else (u8(i) shl 8) or u8(i + 1)

  private fun ByteArray.u32(i: Int, little: Boolean): Long =
    if (little) {
      u8(i).toLong() or (u8(i + 1).toLong() shl 8) or (u8(i + 2).toLong() shl 16) or (u8(i + 3).toLong() shl 24)
    } else {
      (u8(i).toLong() shl 24) or (u8(i + 1).toLong() shl 16) or (u8(i + 2).toLong() shl 8) or u8(i + 3).toLong()
    }

  private fun ByteArray.matches(at: Int, prefix: ByteArray): Boolean {
    if (at < 0 || at + prefix.size > size) return false
    for (k in prefix.indices) if (this[at + k] != prefix[k]) return false
    return true
  }
}
