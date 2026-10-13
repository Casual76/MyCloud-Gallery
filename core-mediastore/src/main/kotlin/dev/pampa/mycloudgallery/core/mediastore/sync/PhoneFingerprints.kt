package dev.pampa.mycloudgallery.core.mediastore.sync

import dev.pampa.mycloudgallery.core.mediastore.contract.MediaStoreRow
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaStoreSource
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.QuickHashAlgorithm
import dev.pampa.mycloudgallery.core.model.Sha256
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest

/**
 * I byte originali di una foto non si possono leggere adesso: manca `ACCESS_MEDIA_LOCATION` (o e' stato ritirato a
 * meta' giro) e senza quel permesso il sistema oscura il GPS dentro i byte (docs/android.md, scoperta 9). **Mai**
 * un'impronta su byte oscurati (D9, E-P21): chi apre i byte lancia questa eccezione invece di restituirli, e la
 * sincronizzazione mette la foto "in attesa".
 */
class OriginalBytesUnavailableException : SecurityException("Byte originali non disponibili senza ACCESS_MEDIA_LOCATION.")

/** L'esito della lettura dell'impronta rapida di una riga. */
internal sealed interface QuickRead {
  data class Ok(val fingerprint: Fingerprint) : QuickRead

  /** Il file e' vuoto (E-M12). */
  data object ZeroBytes : QuickRead

  /** I primi byte non sono di un media (E-M12). */
  data object NotMedia : QuickRead

  /** Non si apre adesso (cancellato nel frattempo, in scrittura, errore di I/O): si riprova al prossimo giro. */
  data object Unreadable : QuickRead

  /** Foto senza `ACCESS_MEDIA_LOCATION`: in attesa (E-P21). */
  data object OriginalUnavailable : QuickRead
}

/**
 * Le impronte dei file del telefono (`qh1`, `docs/modello-dati.md`): testa e coda da 64 KiB per l'hash rapido, l'intero
 * file solo per l'hash completo e solo quando le regole lo chiedono. Le eccezioni di sicurezza diverse da
 * [OriginalBytesUnavailableException] (permessi ritirati) risalgono: fermano il giro.
 */
internal object PhoneFingerprints {

  /** Pezzi da 1 MiB per l'hash completo: memoria costante anche su un video da 60 MB. */
  private const val FULL_CHUNK = 1L shl 20

  suspend fun quick(source: MediaStoreSource, row: MediaStoreRow): QuickRead {
    val bytes = try {
      source.open(row)
    } catch (e: OriginalBytesUnavailableException) {
      return QuickRead.OriginalUnavailable
    } catch (e: FileNotFoundException) {
      return QuickRead.Unreadable
    } catch (e: IOException) {
      return QuickRead.Unreadable
    }
    try {
      val size = bytes.size
      if (size <= 0L) return QuickRead.ZeroBytes
      val regions = QuickHashAlgorithm.regions(size)
      val head = bytes.read(regions[0])
      val tail = if (regions.size > 1) bytes.read(regions[1]) else ByteArray(0)
      // Un file che si accorcia mentre lo leggiamo: si riprova al prossimo giro.
      if (head.size.toLong() != regions[0].length() || (regions.size > 1 && tail.size.toLong() != regions[1].length())) {
        return QuickRead.Unreadable
      }
      if (!MediaSniffer.looksLikeMedia(head, row.mime)) return QuickRead.NotMedia
      return QuickRead.Ok(Fingerprint(size, QuickHashAlgorithm.compute(size, head, tail)))
    } catch (e: IOException) {
      return QuickRead.Unreadable
    } finally {
      bytes.closeQuietly()
    }
  }

  /**
   * L'impronta di [row] con l'hash completo, letta per intero a pezzi. Null se il file non si legge o non e' piu'
   * quello di [expected] (dimensione o hash rapido diversi: e' cambiato nel frattempo).
   */
  suspend fun full(source: MediaStoreSource, row: MediaStoreRow, expected: Fingerprint): Fingerprint? {
    val bytes = try {
      source.open(row)
    } catch (e: OriginalBytesUnavailableException) {
      return null
    } catch (e: IOException) {
      return null
    }
    try {
      val size = bytes.size
      if (size != expected.size || size <= 0L) return null
      val digest = MessageDigest.getInstance("SHA-256")
      val split = size > 2L * QuickHashAlgorithm.CHUNK_BYTES
      val headLength = if (split) QuickHashAlgorithm.CHUNK_BYTES else size.toInt()
      val head = ByteArray(headLength)
      val tail = ByteArray(if (split) QuickHashAlgorithm.CHUNK_BYTES else 0)
      val tailStart = size - tail.size
      var position = 0L
      while (position < size) {
        val end = minOf(position + FULL_CHUNK, size)
        val chunk = bytes.read(position until end)
        if (chunk.size.toLong() != end - position) return null
        digest.update(chunk)
        copyOverlap(chunk, position, head, 0L)
        if (tail.isNotEmpty()) copyOverlap(chunk, position, tail, tailStart)
        position = end
      }
      val quick = QuickHashAlgorithm.compute(size, head, tail)
      if (quick != expected.quickHash) return null
      return Fingerprint(size, quick, Sha256(digest.digest().toHex()))
    } catch (e: IOException) {
      return null
    } finally {
      bytes.closeQuietly()
    }
  }

  /** Copia in [target] (che inizia all'offset [targetStart] del file) la parte di [chunk] (all'offset [chunkStart]). */
  private fun copyOverlap(chunk: ByteArray, chunkStart: Long, target: ByteArray, targetStart: Long) {
    val from = maxOf(chunkStart, targetStart)
    val to = minOf(chunkStart + chunk.size, targetStart + target.size)
    if (from >= to) return
    System.arraycopy(chunk, (from - chunkStart).toInt(), target, (from - targetStart).toInt(), (to - from).toInt())
  }

  private fun LongRange.length(): Long = if (isEmpty()) 0L else last - first + 1

  private fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    val out = CharArray(size * 2)
    forEachIndexed { i, b ->
      val v = b.toInt() and 0xFF
      out[i * 2] = digits[v ushr 4]
      out[i * 2 + 1] = digits[v and 0x0F]
    }
    return String(out)
  }
}

/** Chiude i byte se l'implementazione lo permette ([MediaBytes] non e' `Closeable` di contratto). */
internal fun MediaBytes.closeQuietly() {
  try {
    (this as? AutoCloseable)?.close()
  } catch (_: Exception) {
    // ignorata di proposito: la lettura e' gia' finita
  }
}
