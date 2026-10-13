package dev.pampa.mycloudgallery.core.metadata.format

import dev.pampa.mycloudgallery.core.metadata.contract.ByteRange
import dev.pampa.mycloudgallery.core.metadata.contract.MotionFormat
import dev.pampa.mycloudgallery.core.metadata.contract.MotionPart

/** Una voce della directory del trailer SEF di Samsung (12 byte nel file). */
internal class SefEntry(val type: Int, val offsetBack: Long, val size: Long)

/**
 * La directory del trailer SEF ("Samsung Extended Format") in fondo a un JPEG. Il formato e' quello ricostruito da
 * ExifTool (modulo Samsung): in fondo al file c'e' un piede di 8 byte, `lunghezza (u32 little-endian)` + `SEFT`;
 * la `lunghezza` e' quella del blocco-directory che lo precede, `SEFH` + versione + numero di voci + voci da 12 byte
 * (`0 0`, tipo u16, distanza all'indietro u32, dimensione u32). Ogni voce punta a un blocco **prima** della directory:
 * `directoryStart - distanza`, lungo `dimensione`, fatto di 4 byte (come l'inizio della voce), la lunghezza del nome
 * (u32), il nome e poi i dati. Il tipo `0x0A30` col nome `MotionPhoto_Data` porta il video MP4.
 */
internal class SefDirectory(val directoryStart: Long, val entries: List<SefEntry>) {

  fun blockStart(e: SefEntry): Long = directoryStart - e.offsetBack
}

internal object SefTrailer {

  const val TYPE_MOTION_VIDEO: Int = 0x0A30
  const val MOTION_NAME: String = "MotionPhoto_Data"

  private const val FOOTER = 8
  private const val MAX_DIRECTORY = 0x10000
  private const val MAX_ENTRIES = 256

  /** Legge la directory dalla coda del file ([tail] sono gli ultimi byte, [size] la dimensione del file). Null se non c'e' o non torna. */
  fun parse(tail: ByteArray, size: Long): SefDirectory? {
    if (tail.size < FOOTER + 12 || !tail.hasAscii(tail.size - 4, "SEFT")) return null
    val length = tail.u32le(tail.size - FOOTER)
    if (length < 12 || length >= MAX_DIRECTORY) return null
    val start = tail.size - FOOTER - length
    if (start < 0 || !tail.hasAscii(start.toInt(), "SEFH")) return null
    val at = start.toInt()
    val count = tail.u32le(at + 8)
    if (count < 0 || count > MAX_ENTRIES || 12 + 12 * count > length) return null
    val directoryStart = size - FOOTER - length
    val entries = ArrayList<SefEntry>(count.toInt())
    for (k in 0 until count.toInt()) {
      val e = at + 12 + 12 * k
      val type = tail.u16le(e + 2)
      val back = tail.u32le(e + 4)
      val entrySize = tail.u32le(e + 8)
      // Come ExifTool: il blocco sta prima della directory e lo contiene tutto.
      if (back > directoryStart || entrySize > back || entrySize < 8) continue
      entries += SefEntry(type, back, entrySize)
    }
    return SefDirectory(directoryStart, entries)
  }
}

/** Un candidato per la parte video, con, per SEF, l'inizio del blocco (serve a verificare il nome e correggere la partenza). */
internal class MotionCandidate(val part: MotionPart, val sefBlockStart: Long? = null)

internal object MotionCandidates {

  /** Il nome presunto per il tipo `0x0A30`, finche' non si e' letto il blocco vero. */
  private const val ASSUMED_NAME_LENGTH = 16

  /**
   * I modi in cui il file dichiara di avere un video, nell'ordine di preferenza: la directory del contenitore di Google,
   * `MicroVideoOffset`, il trailer SEF. Nessuno e' una prova: chi li usa deve controllare che nel punto indicato ci sia un
   * MP4 (`ftyp`), perche' un'XMP puo' restare dopo che il video e' stato tolto.
   */
  fun of(xmp: XmpFacts, tail: ByteArray, size: Long): List<MotionCandidate> {
    val out = ArrayList<MotionCandidate>(3)
    val ts = xmp.presentationTimestampUs

    val length = xmp.motionItem?.length
    if (length != null && length in 8 until size) {
      out += MotionCandidate(MotionPart(ByteRange(size - length, size), MotionFormat.GOOGLE_V1, ts))
    }
    val offset = xmp.microVideoOffset
    if (xmp.microVideoFlag && offset != null && offset in 8 until size) {
      out += MotionCandidate(MotionPart(ByteRange(size - offset, size), MotionFormat.MICRO_VIDEO, ts))
    }
    SefTrailer.parse(tail, size)?.let { dir ->
      for (entry in dir.entries) {
        if (entry.type != SefTrailer.TYPE_MOTION_VIDEO) continue
        val block = dir.blockStart(entry)
        val end = block + entry.size
        val data = readValue(tail, size, block, entry)
        val range = data ?: ByteRange(block + 8 + ASSUMED_NAME_LENGTH, end)
        if (range.length > 0 && range.endExclusive <= size) {
          out += MotionCandidate(MotionPart(range, MotionFormat.SAMSUNG_SEF, ts), sefBlockStart = block)
        }
      }
    }
    return out
  }

  /** Se il blocco sta tutto nella coda se ne legge il nome vero; il valore da 12 byte `mpv2` e' un puntatore (offset e dimensione assoluti). */
  private fun readValue(tail: ByteArray, size: Long, block: Long, entry: SefEntry): ByteRange? {
    val inTail = block - (size - tail.size)
    if (inTail < 0 || inTail + 8 > tail.size) return null
    val at = inTail.toInt()
    val nameLength = tail.u32le(at + 4).toInt()
    if (nameLength < 0 || nameLength + 8 > entry.size) return null
    val valueStart = block + 8 + nameLength
    val valueEnd = block + entry.size
    if (valueEnd - valueStart == 12L && at + 8 + nameLength + 12 <= tail.size && tail.hasAscii(at + 8 + nameLength, "mpv2")) {
      val offset = tail.u32le(at + 8 + nameLength + 4)
      val length = tail.u32le(at + 8 + nameLength + 8)
      if (offset > 0 && length > 0 && offset + length <= size) return ByteRange(offset, offset + length)
      return null
    }
    return if (valueEnd > valueStart) ByteRange(valueStart, valueEnd) else null
  }
}
