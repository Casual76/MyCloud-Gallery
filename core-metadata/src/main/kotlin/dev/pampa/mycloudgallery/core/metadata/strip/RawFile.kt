package dev.pampa.mycloudgallery.core.metadata.strip

import java.io.IOException
import java.io.RandomAccessFile

// Fase 5B, P11 (docs/fase5-progetto.md D26, E-X5): piccoli attrezzi per togliere la posizione da una COPIA in cache. Qui si lavora su file locali
// con letture e scritture a posizione, mai caricando un file intero: le foto sono poche decine di MB, i video qualche GB.

/**
 * Un file locale letto e scritto a posizione. Una lettura fuori dal file non lancia: si accorcia (un file troncato e' un caso normale, non
 * un errore del programma). Le scritture sono sempre dentro il file (le patch non cambiano mai la lunghezza).
 */
internal class RawFile(private val raf: RandomAccessFile) {

  val length: Long get() = raf.length()

  fun read(pos: Long, len: Int): ByteArray {
    if (len <= 0 || pos < 0) return EMPTY
    val size = length
    if (pos >= size) return EMPTY
    val n = minOf(len.toLong(), size - pos).toInt()
    val out = ByteArray(n)
    raf.seek(pos)
    raf.readFully(out)
    return out
  }

  /** Scrive [data] a [pos]; mai oltre la fine del file (la copia non cambia lunghezza). */
  fun write(pos: Long, data: ByteArray) {
    if (data.isEmpty()) return
    if (pos < 0 || pos + data.size > length) throw IOException("Scrittura fuori dal file.")
    raf.seek(pos)
    raf.write(data)
  }

  /** Azzera [len] byte a partire da [pos], a blocchi. */
  fun zero(pos: Long, len: Long) {
    if (len <= 0) return
    if (pos < 0 || pos + len > length) throw IOException("Scrittura fuori dal file.")
    val block = ByteArray(minOf(len, ZERO_BLOCK.toLong()).toInt())
    var done = 0L
    raf.seek(pos)
    while (done < len) {
      val n = minOf(block.size.toLong(), len - done).toInt()
      raf.write(block, 0, n)
      done += n
    }
  }

  fun u8(pos: Long): Int = read(pos, 1).let { if (it.isEmpty()) -1 else it[0].toInt() and 0xFF }

  companion object {
    private const val ZERO_BLOCK = 64 * 1024
    val EMPTY = ByteArray(0)
  }
}

internal fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF

internal fun ByteArray.u16be(i: Int): Int = (u8(i) shl 8) or u8(i + 1)

internal fun ByteArray.u32be(i: Int): Long = (u8(i).toLong() shl 24) or (u8(i + 1).toLong() shl 16) or (u8(i + 2).toLong() shl 8) or u8(i + 3).toLong()

internal fun ByteArray.u32le(i: Int): Long = (u8(i + 3).toLong() shl 24) or (u8(i + 2).toLong() shl 16) or (u8(i + 1).toLong() shl 8) or u8(i).toLong()

internal fun ByteArray.u64be(i: Int): Long = (u32be(i) shl 32) or u32be(i + 4)

/** Quattro byte come testo a un carattere per byte (il `©` di `©xyz` e' il byte 0xA9). */
internal fun ByteArray.fourcc(i: Int): String = String(this, i, 4, Charsets.ISO_8859_1)

internal fun ByteArray.startsWithAscii(at: Int, text: String): Boolean {
  if (at < 0 || at + text.length > size) return false
  for (k in text.indices) if (this[at + k].toInt() and 0xFF != text[k].code) return false
  return true
}
