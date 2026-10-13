package dev.pampa.mycloudgallery.core.metadata.format

// Un lettore minimo di TIFF/EXIF: la struttura a IFD che sta dentro un JPEG (APP1), un PNG (eXIf), un WebP (EXIF),
// un HEIC (voce Exif) e che e' il file stesso per i DNG. Legge solo cio' che serve a date, orientamento, GPS, luogo e
// pannello info, con tetti a ogni ciclo e a ogni lettura (un file ostile o rovinato non puo' farlo girare a lungo ne'
// allocare molto). I valori fuori dal file non sono un errore: la voce si legge come assente.

/** Una voce di un IFD: il campo valore (4 byte) e' gia' letto, i valori piu' lunghi si leggono su richiesta. */
internal class TiffEntry(
  val tag: Int,
  val type: Int,
  val count: Long,
  /** I 4 byte del campo valore, cosi' come stanno nel file (valore in linea o offset). */
  val valueField: ByteArray,
)

internal class Ifd(val entries: Map<Int, TiffEntry>) {
  operator fun get(tag: Int): TiffEntry? = entries[tag]
}

/** Un TIFF letto: l'IFD0 e quelli collegati. [ra] e [base] servono per leggere i valori lunghi. */
internal class TiffFile(
  val ra: RandomAccess,
  val base: Long,
  val bigEndian: Boolean,
  val ifd0: Ifd,
  val exif: Ifd?,
  val gps: Ifd?,
  val ifd1: Ifd?,
  val subIfds: List<Ifd>,
) {

  private fun u16(b: ByteArray, i: Int): Int = if (bigEndian) b.u16be(i) else b.u16le(i)

  private fun u32(b: ByteArray, i: Int): Long = if (bigEndian) b.u32be(i) else b.u32le(i)

  /** I byte del valore di [e], o null se il valore e' fuori dal file, troppo grande o la voce e' malformata. */
  suspend fun raw(e: TiffEntry, cap: Int = MAX_VALUE_BYTES): ByteArray? {
    val unit = typeSize(e.type)
    if (unit == 0 || e.count <= 0) return null
    val total = unit.toLong() * e.count
    if (total > cap) return null
    val n = total.toInt()
    if (n <= 4) return e.valueField.copyOf(n)
    val offset = u32(e.valueField, 0)
    val bytes = ra.read(base + offset, n)
    return if (bytes.size == n) bytes else null
  }

  /** Il testo ASCII di [e], senza il NUL finale e senza spazi ai bordi; vuoto = assente. */
  suspend fun ascii(e: TiffEntry?): String? {
    if (e == null || (e.type != TYPE_ASCII && e.type != TYPE_UNDEFINED && e.type != TYPE_BYTE)) return null
    val bytes = raw(e) ?: return null
    var end = bytes.indexOfFirst { it == 0.toByte() }
    if (end < 0) end = bytes.size
    val text = String(bytes, 0, end, Charsets.ISO_8859_1).trim()
    return text.ifEmpty { null }
  }

  /** I primi [max] valori interi di [e] (BYTE, SHORT, LONG e le versioni con segno). */
  suspend fun ints(e: TiffEntry?, max: Int = 16): List<Long> {
    if (e == null) return emptyList()
    val unit = typeSize(e.type)
    if (unit == 0 || e.type == TYPE_ASCII || e.type == TYPE_RATIONAL || e.type == TYPE_SRATIONAL || e.type == TYPE_FLOAT || e.type == TYPE_DOUBLE) return emptyList()
    val wanted = minOf(e.count, max.toLong()).toInt()
    val limited = if (wanted.toLong() == e.count) e else sliceOf(e, wanted)
    val bytes = raw(limited, wanted * unit + 8) ?: return emptyList()
    return List(wanted) { k ->
      when (e.type) {
        TYPE_BYTE, TYPE_UNDEFINED -> bytes.u8(k).toLong()
        TYPE_SBYTE -> bytes[k].toLong()
        TYPE_SHORT -> u16(bytes, k * 2).toLong()
        TYPE_SSHORT -> u16(bytes, k * 2).toShort().toLong()
        TYPE_SLONG -> u32(bytes, k * 4).toInt().toLong()
        else -> u32(bytes, k * 4)
      }
    }
  }

  /** Come [ints], ma ne chiede uno solo. */
  suspend fun int(e: TiffEntry?): Long? = ints(e, 1).firstOrNull()

  /** I primi [max] valori razionali (RATIONAL e SRATIONAL) come numeri; un denominatore zero da' `NaN`. */
  suspend fun rationals(e: TiffEntry?, max: Int = 3): List<Double> {
    if (e == null || (e.type != TYPE_RATIONAL && e.type != TYPE_SRATIONAL)) return emptyList()
    val wanted = minOf(e.count, max.toLong()).toInt()
    val limited = if (wanted.toLong() == e.count) e else sliceOf(e, wanted)
    val bytes = raw(limited) ?: return emptyList()
    return List(wanted) { k ->
      val num = u32(bytes, k * 8)
      val den = u32(bytes, k * 8 + 4)
      if (e.type == TYPE_SRATIONAL) {
        val d = den.toInt()
        if (d == 0) Double.NaN else num.toInt().toDouble() / d
      } else if (den == 0L) Double.NaN else num.toDouble() / den
    }
  }

  suspend fun rational(e: TiffEntry?): Double? = rationals(e, 1).firstOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }

  /** Una voce con meno valori (stesso offset): per non leggere un array enorme quando ne servono pochi. */
  private fun sliceOf(e: TiffEntry, count: Int): TiffEntry = TiffEntry(e.tag, e.type, count.toLong(), e.valueField)

  /** Il valore di [e] come offset nel file (per i puntatori agli IFD e ai dati). */
  fun offsetOf(e: TiffEntry): Long = u32(e.valueField, 0)

  /** I due lati dichiarati da un IFD d'immagine (`ImageWidth`, `ImageLength`), se ci sono e hanno senso. */
  suspend fun sizeOf(ifd: Ifd): Pair<Int, Int>? {
    val w = int(ifd[TAG_IMAGE_WIDTH])
    val h = int(ifd[TAG_IMAGE_LENGTH])
    if (w == null || h == null || w <= 0 || h <= 0 || w > MAX_SIDE || h > MAX_SIDE) return null
    return w.toInt() to h.toInt()
  }

  /** La fine piu' lontana dei dati di immagine (strisce e riquadri) dichiarati dagli IFD: oltre il file = troncato. */
  suspend fun declaredDataEnd(): Long {
    var end = 0L
    for (ifd in listOf(ifd0) + subIfds) {
      end = maxOf(end, dataEnd(ifd, TAG_STRIP_OFFSETS, TAG_STRIP_BYTE_COUNTS))
      end = maxOf(end, dataEnd(ifd, TAG_TILE_OFFSETS, TAG_TILE_BYTE_COUNTS))
    }
    return end
  }

  private suspend fun dataEnd(ifd: Ifd, offsetsTag: Int, countsTag: Int): Long {
    val offsetsEntry = ifd[offsetsTag] ?: return 0
    val countsEntry = ifd[countsTag] ?: return 0
    val offsets = ints(offsetsEntry, MAX_STRIPS)
    val counts = ints(countsEntry, MAX_STRIPS)
    var end = 0L
    for (k in 0 until minOf(offsets.size, counts.size)) end = maxOf(end, offsets[k] + counts[k])
    return end
  }

  companion object {
    const val TYPE_BYTE = 1
    const val TYPE_ASCII = 2
    const val TYPE_SHORT = 3
    const val TYPE_LONG = 4
    const val TYPE_RATIONAL = 5
    const val TYPE_SBYTE = 6
    const val TYPE_UNDEFINED = 7
    const val TYPE_SSHORT = 8
    const val TYPE_SLONG = 9
    const val TYPE_SRATIONAL = 10
    const val TYPE_FLOAT = 11
    const val TYPE_DOUBLE = 12
    const val TYPE_IFD = 13

    const val TAG_IMAGE_WIDTH = 0x0100
    const val TAG_IMAGE_LENGTH = 0x0101
    const val TAG_MAKE = 0x010F
    const val TAG_MODEL = 0x0110
    const val TAG_STRIP_OFFSETS = 0x0111
    const val TAG_ORIENTATION = 0x0112
    const val TAG_STRIP_BYTE_COUNTS = 0x0117
    const val TAG_DATE_TIME = 0x0132
    const val TAG_SUB_IFDS = 0x014A
    const val TAG_TILE_OFFSETS = 0x0144
    const val TAG_TILE_BYTE_COUNTS = 0x0145
    const val TAG_JPEG_OFFSET = 0x0201
    const val TAG_JPEG_LENGTH = 0x0202
    const val TAG_EXIF_IFD = 0x8769
    const val TAG_GPS_IFD = 0x8825
    const val TAG_DNG_VERSION = 0xC612
    const val TAG_DEFAULT_CROP_SIZE = 0xC620

    const val MAX_VALUE_BYTES = 1 shl 20
    const val MAX_SIDE = 1_000_000L
    private const val MAX_STRIPS = 16_384

    fun typeSize(type: Int): Int = when (type) {
      TYPE_BYTE, TYPE_ASCII, TYPE_SBYTE, TYPE_UNDEFINED -> 1
      TYPE_SHORT, TYPE_SSHORT -> 2
      TYPE_LONG, TYPE_SLONG, TYPE_FLOAT, TYPE_IFD -> 4
      TYPE_RATIONAL, TYPE_SRATIONAL, TYPE_DOUBLE -> 8
      else -> 0
    }
  }
}

internal object TiffParser {

  private const val MAX_ENTRIES = 1000
  private const val MAX_SUB_IFDS = 8

  /**
   * Legge un TIFF che comincia a [base] dentro [ra]. Null se l'intestazione non e' TIFF (BigTIFF e le varianti RAW
   * con intestazione propria non sono supportate) o se l'IFD0 non si legge.
   */
  suspend fun parse(ra: RandomAccess, base: Long = 0): TiffFile? {
    val header = ra.read(base, 8)
    if (header.size < 8) return null
    val big = when {
      header.u8(0) == 'M'.code && header.u8(1) == 'M'.code -> true
      header.u8(0) == 'I'.code && header.u8(1) == 'I'.code -> false
      else -> return null
    }
    val magic = if (big) header.u16be(2) else header.u16le(2)
    if (magic != 42) return null
    val ifd0Offset = if (big) header.u32be(4) else header.u32le(4)
    val reader = Reader(ra, base, big)
    val first = reader.readIfd(ifd0Offset) ?: return null
    val ifd0 = first.first
    val exif = ifd0[TiffFile.TAG_EXIF_IFD]?.let { reader.readIfd(reader.pointer(it))?.first }
    val gps = ifd0[TiffFile.TAG_GPS_IFD]?.let { reader.readIfd(reader.pointer(it))?.first }
    val ifd1 = if (first.second > 0) reader.readIfd(first.second)?.first else null
    val file = TiffFile(ra, base, big, ifd0, exif, gps, ifd1, emptyList())
    val subs = mutableListOf<Ifd>()
    ifd0[TiffFile.TAG_SUB_IFDS]?.let { entry ->
      for (offset in file.ints(entry, MAX_SUB_IFDS)) reader.readIfd(offset)?.first?.let { subs += it }
    }
    return if (subs.isEmpty()) file else TiffFile(ra, base, big, ifd0, exif, gps, ifd1, subs)
  }

  private class Reader(val ra: RandomAccess, val base: Long, val big: Boolean) {
    private val visited = HashSet<Long>()

    fun pointer(e: TiffEntry): Long = if (big) e.valueField.u32be(0) else e.valueField.u32le(0)

    /** L'IFD a [offset] e l'offset del successivo (0 = nessuno). */
    suspend fun readIfd(offset: Long): Pair<Ifd, Long>? {
      if (offset < 8 || !visited.add(offset)) return null
      val countBytes = ra.read(base + offset, 2)
      if (countBytes.size < 2) return null
      val count = if (big) countBytes.u16be(0) else countBytes.u16le(0)
      if (count == 0 || count > MAX_ENTRIES) return null
      val body = ra.read(base + offset + 2, count * 12 + 4)
      // Un IFD tagliato nelle sue voci non si legge a meta': meglio nessun dato che dati parziali (un file troncato).
      if (body.size < count * 12) return null
      val entries = LinkedHashMap<Int, TiffEntry>(count * 2)
      for (k in 0 until count) {
        val at = k * 12
        val tag = if (big) body.u16be(at) else body.u16le(at)
        val type = if (big) body.u16be(at + 2) else body.u16le(at + 2)
        val n = if (big) body.u32be(at + 4) else body.u32le(at + 4)
        if (TiffFile.typeSize(type) == 0 || n <= 0) continue
        entries.putIfAbsent(tag, TiffEntry(tag, type, n, body.copyOfRange(at + 8, at + 12)))
      }
      val next = if (body.size >= count * 12 + 4) (if (big) body.u32be(count * 12) else body.u32le(count * 12)) else 0L
      return Ifd(entries) to next
    }
  }
}
