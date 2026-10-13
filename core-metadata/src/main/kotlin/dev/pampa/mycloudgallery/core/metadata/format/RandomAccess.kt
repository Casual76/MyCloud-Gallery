package dev.pampa.mycloudgallery.core.metadata.format

import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes

// Accesso casuale ai byte di un file, a pagine, e piccole funzioni per leggere interi da un ByteArray. Tutto il
// modulo legge i file in questo modo: poche letture da 64 KiB (testa, intestazioni, coda) e mai il file intero
// (docs/fase3-progetto.md §2.1, "Nessuna lettura a file interi durante lo scorrimento").

/**
 * Un file letto a intervalli. [read] non lancia mai per un intervallo fuori dal file: lo accorcia, e se e' tutto fuori
 * da' un array vuoto. Chi interpreta i byte controlla sempre la lunghezza ricevuta: un file troncato non e' un
 * errore del programma.
 */
internal interface RandomAccess {
  val size: Long

  suspend fun read(pos: Long, len: Int): ByteArray
}

/** Un `ByteArray` (o una sua parte) come [RandomAccess]: per i blocchi gia' estratti (Exif di un JPEG, XMP...). */
internal class ArrayAccess(private val data: ByteArray) : RandomAccess {
  override val size: Long get() = data.size.toLong()

  override suspend fun read(pos: Long, len: Int): ByteArray {
    if (len <= 0 || pos < 0 || pos >= data.size) return EMPTY
    val end = minOf(data.size.toLong(), pos + len).toInt()
    return data.copyOfRange(pos.toInt(), end)
  }
}

/**
 * Un [MediaBytes] a pagine di [pageSize] byte con una piccola cache LRU: un'intestazione JPEG, una sequenza di
 * riquadri ISOBMFF o le voci di un IFD cadono quasi sempre nella stessa pagina e costano una lettura sola. Le letture
 * grandi (piu' di 4 pagine) vanno dirette, senza passare dalla cache.
 */
internal class PagedAccess(
  private val source: MediaBytes,
  private val pageSize: Int = PAGE_SIZE,
  maxPages: Int = 24,
) : RandomAccess {

  override val size: Long = source.size

  private val cache = object : LinkedHashMap<Long, ByteArray>(32, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean = this.size > maxPages
  }

  override suspend fun read(pos: Long, len: Int): ByteArray {
    if (len <= 0 || pos < 0 || pos >= size) return EMPTY
    val end = minOf(size, pos + len)
    val wanted = (end - pos).toInt()
    if (wanted > pageSize * DIRECT_READ_PAGES) return source.read(pos until end)

    val first = pos / pageSize
    val last = (end - 1) / pageSize
    val local = arrayOfNulls<ByteArray>((last - first + 1).toInt())
    var missingFrom = -1L
    for (page in first..last) {
      val cached = cache[page]
      if (cached != null) {
        local[(page - first).toInt()] = cached
        if (missingFrom >= 0) {
          fetch(missingFrom, page - 1, first, local)
          missingFrom = -1
        }
      } else if (missingFrom < 0) {
        missingFrom = page
      }
    }
    if (missingFrom >= 0) fetch(missingFrom, last, first, local)

    val out = ByteArray(wanted)
    var written = 0
    var cursor = pos
    while (cursor < end) {
      val page = local[(cursor / pageSize - first).toInt()] ?: break
      val inPage = (cursor % pageSize).toInt()
      if (inPage >= page.size) break
      val take = minOf(page.size - inPage, (end - cursor).toInt())
      System.arraycopy(page, inPage, out, written, take)
      written += take
      cursor += take
    }
    return if (written == wanted) out else out.copyOf(written)
  }

  /** Legge in una volta sola le pagine `[fromPage, toPage]` e le mette in cache e in [local]. */
  private suspend fun fetch(fromPage: Long, toPage: Long, first: Long, local: Array<ByteArray?>) {
    val from = fromPage * pageSize
    val to = minOf(size, (toPage + 1) * pageSize)
    val data = source.read(from until to)
    var offset = 0
    var page = fromPage
    while (page <= toPage && offset < data.size) {
      val chunk = data.copyOfRange(offset, minOf(data.size, offset + pageSize))
      cache[page] = chunk
      local[(page - first).toInt()] = chunk
      offset += chunk.size
      page++
    }
  }

  companion object {
    const val PAGE_SIZE: Int = 64 * 1024
    private const val DIRECT_READ_PAGES = 4
  }
}

internal val EMPTY: ByteArray = ByteArray(0)

/** Gli ultimi [len] byte (o tutto il file, se e' piu' corto). */
internal suspend fun RandomAccess.tail(len: Int): ByteArray {
  val start = maxOf(0L, size - len)
  return read(start, (size - start).toInt())
}

internal fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF

internal fun ByteArray.u16be(i: Int): Int = (u8(i) shl 8) or u8(i + 1)

internal fun ByteArray.u16le(i: Int): Int = u8(i) or (u8(i + 1) shl 8)

internal fun ByteArray.u32be(i: Int): Long =
  (u8(i).toLong() shl 24) or (u8(i + 1).toLong() shl 16) or (u8(i + 2).toLong() shl 8) or u8(i + 3).toLong()

internal fun ByteArray.u32le(i: Int): Long =
  u8(i).toLong() or (u8(i + 1).toLong() shl 8) or (u8(i + 2).toLong() shl 16) or (u8(i + 3).toLong() shl 24)

internal fun ByteArray.u64be(i: Int): Long = (u32be(i) shl 32) or u32be(i + 4)

/** Quattro caratteri ASCII (il tipo di un riquadro o di un chunk), con `?` per i byte non stampabili. */
internal fun ByteArray.fourcc(i: Int): String {
  val sb = StringBuilder(4)
  for (k in 0 until 4) {
    val c = u8(i + k)
    sb.append(if (c in 0x20..0x7E) c.toChar() else '?')
  }
  return sb.toString()
}

/** Se a partire da [at] ci sono gli stessi byte di [prefix] (falso, senza eccezioni, se il file finisce prima). */
internal fun ByteArray.hasAt(at: Int, prefix: ByteArray): Boolean {
  if (at < 0 || at + prefix.size > size) return false
  for (k in prefix.indices) if (this[at + k] != prefix[k]) return false
  return true
}

internal fun ByteArray.hasAscii(at: Int, text: String): Boolean {
  if (at < 0 || at + text.length > size) return false
  for (k in text.indices) if (u8(at + k) != text[k].code) return false
  return true
}

/** La prima posizione di [needle] in questo array a partire da [from], o -1. */
internal fun ByteArray.indexOf(needle: ByteArray, from: Int = 0): Int {
  if (needle.isEmpty()) return from
  var i = maxOf(0, from)
  val last = size - needle.size
  while (i <= last) {
    if (hasAt(i, needle)) return i
    i++
  }
  return -1
}

/** L'ultima posizione di [needle], o -1. */
internal fun ByteArray.lastIndexOf(needle: ByteArray): Int {
  var i = size - needle.size
  while (i >= 0) {
    if (hasAt(i, needle)) return i
    i--
  }
  return -1
}

internal fun asciiBytes(text: String): ByteArray = ByteArray(text.length) { text[it].code.toByte() }
