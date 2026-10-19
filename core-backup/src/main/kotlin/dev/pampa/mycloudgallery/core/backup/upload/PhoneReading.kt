package dev.pampa.mycloudgallery.core.backup.upload

import android.media.MediaDataSource
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.QuickHashAlgorithm
import dev.pampa.mycloudgallery.core.model.Sha256
import java.io.IOException
import java.security.MessageDigest

/**
 * Una lettura completa dei byte del telefono (§2.2 `HASHED`, D10): SHA-256 e `qh1` **degli stessi byte**, in ordine, a pezzi da
 * [chunkBytes] (mai un video intero in memoria); con [keepInMemory] il file resta anche in un array, cosi' la preparazione lavora sugli
 * stessi byte senza rileggere il telefono. [onBytes] riceve i byte letti (avanzamento). Una lettura corta (il file si e' accorciato)
 * da' null; un errore di I/O risale (il motore guarda la riga e decide, E-B4, E-B15).
 *
 * **La fine del file** (R6 della revisione 5A): `size` e' quella del MediaStore, che puo' essere rimasta indietro (un file che cresce in
 * piu' sessioni, la memoria condivisa di Android 10). Dopo `size` byte si guarda dove finisce il file: la dimensione del descrittore
 * aperto ([MediaBytes.size]) deve essere `size` e un byte in piu' deve dare la fine del file. Altrimenti [Result.endsAtSize] e' falso: i
 * byte letti sono un **prefisso**, non la versione, e non devono mai diventare un caricamento "al sicuro".
 */
internal object PhoneReading {

  class Result(val sha256: Sha256, val quickHash: QuickHash, val memory: ByteArray?, val endsAtSize: Boolean = true)

  @Throws(IOException::class)
  suspend fun read(bytes: MediaBytes, size: Long, chunkBytes: Int, keepInMemory: Boolean, onBytes: (Long) -> Unit = {}): Result? {
    require(size >= 0 && chunkBytes > 0) { "Lettura non valida." }
    require(!keepInMemory || size <= Int.MAX_VALUE) { "Troppo grande per la memoria." }
    val digest = MessageDigest.getInstance("SHA-256")
    val split = size > 2L * QuickHashAlgorithm.CHUNK_BYTES
    val head = ByteArray(if (split) QuickHashAlgorithm.CHUNK_BYTES else size.toInt())
    val tail = ByteArray(if (split) QuickHashAlgorithm.CHUNK_BYTES else 0)
    val tailStart = size - tail.size
    val memory = if (keepInMemory) ByteArray(size.toInt()) else null
    var position = 0L
    while (position < size) {
      val end = minOf(position + chunkBytes, size)
      val chunk = bytes.read(position until end)
      if (chunk.size.toLong() != end - position) return null
      digest.update(chunk)
      copyOverlap(chunk, position, head, 0L)
      if (tail.isNotEmpty()) copyOverlap(chunk, position, tail, tailStart)
      memory?.let { System.arraycopy(chunk, 0, it, position.toInt(), chunk.size) }
      onBytes(chunk.size.toLong())
      position = end
    }
    return Result(Sha256(hex(digest.digest())), QuickHashAlgorithm.compute(size, head, tail), memory, endsAtSize = endsAt(bytes, size))
  }

  /** Il file finisce dove dice il MediaStore: stessa dimensione sul descrittore aperto e nessun byte dopo `size` (R6). */
  private suspend fun endsAt(bytes: MediaBytes, size: Long): Boolean = bytes.size == size && bytes.read(size until size + 1).isEmpty()

  /** Copia in [target] (che inizia all'offset [targetStart] del file) la parte di [chunk] (all'offset [chunkStart]). */
  private fun copyOverlap(chunk: ByteArray, chunkStart: Long, target: ByteArray, targetStart: Long) {
    val from = maxOf(chunkStart, targetStart)
    val to = minOf(chunkStart + chunk.size, targetStart + target.size)
    if (from >= to) return
    System.arraycopy(chunk, (from - chunkStart).toInt(), target, (from - targetStart).toInt(), (to - from).toInt())
  }

  fun hex(bytes: ByteArray): String {
    val digits = "0123456789abcdef"
    val out = CharArray(bytes.size * 2)
    bytes.forEachIndexed { i, b ->
      val v = b.toInt() and 0xFF
      out[i * 2] = digits[v ushr 4]
      out[i * 2 + 1] = digits[v and 0x0F]
    }
    return String(out)
  }
}

/** I byte di un file gia' letti in memoria (D10: la preparazione lavora sugli stessi byte dell'hash, senza rileggere il telefono). */
internal class InMemoryMediaBytes(private val data: ByteArray) : MediaBytes {
  override val size: Long get() = data.size.toLong()

  override suspend fun read(range: LongRange): ByteArray {
    if (range.isEmpty() || range.first >= data.size || range.last < 0) return ByteArray(0)
    val from = maxOf(range.first, 0L).toInt()
    val to = minOf(range.last, data.size - 1L).toInt()
    return data.copyOfRange(from, to + 1)
  }

  override fun dataSource(): MediaDataSource = object : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
      if (position >= data.size) return -1
      val n = minOf(size.toLong(), data.size - position).toInt()
      System.arraycopy(data, position.toInt(), buffer, offset, n)
      return n
    }

    override fun getSize(): Long = data.size.toLong()

    override fun close() = Unit
  }
}
