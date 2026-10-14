package dev.pampa.mycloudgallery.core.metadata.hash

import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.QuickHashAlgorithm
import dev.pampa.mycloudgallery.core.model.Sha256
import java.io.IOException
import java.security.MessageDigest

/**
 * Le impronte di un file letto **a intervalli** (`docs/modello-dati.md` §4): testa e coda da 64 KiB per l'hash rapido
 * `qh1`, l'intero file a pezzi solo per l'hash completo e solo quando serve. Generiche su [MediaBytes]: le stesse per i
 * byte del telefono (`ContentResolver`, Fase 3: `PhoneFingerprints` di core-mediastore fa la stessa cosa sulla riga del
 * MediaStore) e per quelli del NAS (`NasMediaBytes` sopra `NasRandomFile`, Fase 4: rilevamento e `FULL_HASH`).
 *
 * Chi apre i byte (e decide cosa fare se non si aprono, es. `ACCESS_MEDIA_LOCATION` mancante sul telefono) resta il
 * chiamante: qui si legge e basta. Nessuna eccezione d'I/O esce: un file che non si legge e' un esito.
 */
object Fingerprints {

  /** Pezzi da 1 MiB per l'hash completo: memoria costante anche su un video da 9 GB. */
  const val FULL_CHUNK_BYTES: Long = 1L shl 20

  /** L'esito della lettura dell'impronta rapida. */
  sealed interface Quick {
    data class Ok(val fingerprint: Fingerprint) : Quick

    /** Il file e' vuoto (E-M12). */
    data object ZeroBytes : Quick

    /** I primi byte non sono di un media (E-M12), secondo il controllo di chi chiama. */
    data object NotMedia : Quick

    /** Non si legge adesso (errore di I/O, file che si accorcia mentre lo leggiamo): si riprova al prossimo giro. */
    data object Unreadable : Quick
  }

  /**
   * L'impronta rapida (`qh1`) di [bytes]: due letture (o una fino a 128 KiB). [looksLikeMedia] guarda la testa gia' letta,
   * senza letture in piu' (il telefono usa `MediaSniffer`, il NAS il suo controllo E-M12); di default accetta tutto.
   */
  suspend fun quick(bytes: MediaBytes, looksLikeMedia: (head: ByteArray) -> Boolean = { true }): Quick {
    return try {
      val size = bytes.size
      if (size <= 0L) return Quick.ZeroBytes
      val regions = QuickHashAlgorithm.regions(size)
      val head = bytes.read(regions[0])
      val tail = if (regions.size > 1) bytes.read(regions[1]) else ByteArray(0)
      // Un file che si accorcia mentre lo leggiamo: si riprova al prossimo giro.
      if (head.size.toLong() != regions[0].length() || (regions.size > 1 && tail.size.toLong() != regions[1].length())) {
        return Quick.Unreadable
      }
      if (!looksLikeMedia(head)) return Quick.NotMedia
      Quick.Ok(Fingerprint(size, QuickHashAlgorithm.compute(size, head, tail)))
    } catch (e: IOException) {
      Quick.Unreadable
    }
  }

  /**
   * L'impronta di [bytes] con l'hash completo, letta per intero a pezzi da [chunkBytes]. `null` se il file non si legge
   * o non e' piu' quello di [expected] (dimensione o hash rapido diversi: e' cambiato nel frattempo). L'hash rapido si
   * ricalcola dagli stessi byte, cosi' testa, coda e intero vengono da una sola lettura coerente.
   */
  suspend fun full(bytes: MediaBytes, expected: Fingerprint, chunkBytes: Long = FULL_CHUNK_BYTES): Fingerprint? {
    require(chunkBytes > 0) { "Pezzi positivi." }
    return try {
      val size = bytes.size
      if (size != expected.size || size <= 0L) return null
      val digest = MessageDigest.getInstance("SHA-256")
      val split = size > 2L * QuickHashAlgorithm.CHUNK_BYTES
      val head = ByteArray(if (split) QuickHashAlgorithm.CHUNK_BYTES else size.toInt())
      val tail = ByteArray(if (split) QuickHashAlgorithm.CHUNK_BYTES else 0)
      val tailStart = size - tail.size
      var position = 0L
      while (position < size) {
        val end = minOf(position + chunkBytes, size)
        val chunk = bytes.read(position until end)
        if (chunk.size.toLong() != end - position) return null
        digest.update(chunk)
        copyOverlap(chunk, position, head, 0L)
        if (tail.isNotEmpty()) copyOverlap(chunk, position, tail, tailStart)
        position = end
      }
      val quick = QuickHashAlgorithm.compute(size, head, tail)
      if (quick != expected.quickHash) return null
      Fingerprint(size, quick, Sha256(digest.digest().toHex()))
    } catch (e: IOException) {
      null
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
