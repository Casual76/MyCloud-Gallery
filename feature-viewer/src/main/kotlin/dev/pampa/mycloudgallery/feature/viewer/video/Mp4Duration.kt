package dev.pampa.mycloudgallery.feature.viewer.video

/**
 * La durata di un MP4 dal box `mvhd` dentro `moov` (ISO 14496-12), letta **a intervalli**: poche letture da 16 a 32 byte, mai il
 * video. Serve all'istante della foto di una motion photo quando il file non lo dichiara (E-V16: meta' del video).
 *
 * Funzione pura sopra una lettura `(posizione, lunghezza)`: si prova con file sintetici. Un file che non e' un MP4, un box
 * troncato o con misure assurde danno null, mai un'eccezione.
 */
internal object Mp4Duration {

  private const val HEADER = 8
  private const val MAX_BOXES = 64
  private const val MVHD_BYTES = 32
  private const val MICROS = 1_000_000L

  /** La durata in microsecondi di un MP4 lungo [length] byte, o null. [read] torna meno byte di quelli chiesti oltre la fine. */
  suspend fun read(length: Long, read: suspend (offset: Long, length: Int) -> ByteArray): Long? {
    val moov = findBox(0L, length, "moov", read) ?: return null
    val mvhd = findBox(moov.contentStart, moov.end, "mvhd", read) ?: return null
    val body = read(mvhd.contentStart, MVHD_BYTES)
    if (body.size < MVHD_BYTES) return null
    val timescale: Long
    val duration: Long
    when (body[0].toInt() and 0xFF) {
      0 -> {
        timescale = u32(body, 12)
        duration = u32(body, 16)
        // 0xFFFFFFFF vale "sconosciuta" nei file scritti in diretta.
        if (duration == 0xFFFF_FFFFL) return null
      }
      1 -> {
        timescale = u32(body, 20)
        duration = u64(body, 24)
        if (duration < 0L) return null
      }
      else -> return null
    }
    if (timescale <= 0L || duration <= 0L) return null
    // duration * 1e6 non deve uscire dai Long: una durata assurda e' un file rovinato.
    if (duration > Long.MAX_VALUE / MICROS) return null
    return duration * MICROS / timescale
  }

  private class Box(val contentStart: Long, val end: Long)

  /** Il primo box di tipo [type] fra [from] e [to]; null se non c'e' o la struttura non torna. */
  private suspend fun findBox(from: Long, to: Long, type: String, read: suspend (Long, Int) -> ByteArray): Box? {
    var position = from
    var seen = 0
    while (position + HEADER <= to && seen++ < MAX_BOXES) {
      val header = read(position, 16)
      if (header.size < HEADER) return null
      var size = u32(header, 0)
      var headerSize = HEADER
      if (size == 1L) {
        if (header.size < 16) return null
        size = u64(header, 8)
        headerSize = 16
      } else if (size == 0L) {
        size = to - position
      }
      if (size < headerSize || position + size > to) return null
      if (String(header, 4, 4, Charsets.ISO_8859_1) == type) return Box(position + headerSize, position + size)
      position += size
    }
    return null
  }

  private fun u32(b: ByteArray, at: Int): Long =
    ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

  private fun u64(b: ByteArray, at: Int): Long = (u32(b, at) shl 32) or u32(b, at + 4)
}
