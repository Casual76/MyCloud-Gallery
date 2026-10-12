package dev.pampa.mycloudgallery.core.family.registry

import dev.pampa.mycloudgallery.core.family.trust.writeAtomically
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Quando l'app dell'admin ha visto la prima volta ogni richiesta d'ingresso (ora del NAS, revisione M2): la
 * finestra di 15 minuti parte da qui, non da `requestedAt` (lo scrive il richiedente). Si conserva in locale,
 * cosi' un riavvio dell'app non riapre la finestra di una richiesta vecchia.
 */
interface ApprovalSightings {
  /** L'ora della prima vista di [requestId]; se e' la prima volta, la annota a [nasNow]. */
  suspend fun observe(requestId: String, nasNow: Long): Long

  /** Tiene solo le richieste ancora presenti (le altre sono state approvate o sono sparite). */
  suspend fun retain(ids: Set<String>)

  suspend fun clear()
}

class InMemoryApprovalSightings : ApprovalSightings {
  private val seen = LinkedHashMap<String, Long>()

  override suspend fun observe(requestId: String, nasNow: Long): Long = synchronized(seen) { seen.getOrPut(requestId) { nasNow } }

  override suspend fun retain(ids: Set<String>) {
    synchronized(seen) { seen.keys.retainAll(ids) }
  }

  override suspend fun clear() = synchronized(seen) { seen.clear() }
}

/** [ApprovalSightings] in un file locale (`noBackupFilesDir`): righe `id;ora`, al massimo [MAX_ENTRIES]. */
class FileApprovalSightings(private val file: File, private val io: CoroutineDispatcher = Dispatchers.IO) : ApprovalSightings {
  private val mutex = Mutex()

  override suspend fun observe(requestId: String, nasNow: Long): Long = withContext(io) {
    mutex.withLock {
      val all = readAll()
      all[requestId]?.let { return@withLock it }
      if (!TrustIds.isValid(requestId)) return@withLock nasNow
      all[requestId] = nasNow
      writeAll(all)
      nasNow
    }
  }

  override suspend fun retain(ids: Set<String>) = withContext(io) {
    mutex.withLock {
      val all = readAll()
      if (all.keys.retainAll(ids)) writeAll(all)
    }
  }

  override suspend fun clear() = withContext(io) {
    mutex.withLock {
      file.delete()
      Unit
    }
  }

  private fun readAll(): LinkedHashMap<String, Long> {
    val out = LinkedHashMap<String, Long>()
    if (!file.exists()) return out
    val lines = try {
      file.readLines(Charsets.US_ASCII)
    } catch (e: IOException) {
      return out
    }
    for (line in lines.take(MAX_ENTRIES)) {
      val parts = line.split(';')
      val at = parts.getOrNull(1)?.toLongOrNull() ?: continue
      if (parts.size == 2 && TrustIds.isValid(parts[0])) out[parts[0]] = at
    }
    return out
  }

  private fun writeAll(all: Map<String, Long>) {
    val text = all.entries.toList().takeLast(MAX_ENTRIES).joinToString("\n") { "${it.key};${it.value}" }
    writeAtomically(file, text.toByteArray(Charsets.US_ASCII))
  }

  private companion object {
    const val MAX_ENTRIES = 256
  }
}
