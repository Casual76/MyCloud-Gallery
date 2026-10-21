package dev.pampa.mycloudgallery.core.models.testing

import dev.pampa.mycloudgallery.core.models.contract.ShelfDelete
import dev.pampa.mycloudgallery.core.models.contract.ShelfSource
import dev.pampa.mycloudgallery.core.models.contract.ShelfWrite
import dev.pampa.mycloudgallery.core.models.contract.ShelfWriter
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasMedia
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.PublishResult
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

/**
 * Lo scrittore dello scaffale sopra il NAS finto dei media (§5.2): le stesse regole che l'adattatore vero di core-nas deve avere.
 * Scrive e toglie SOLO in `devices/<ownerDeviceId>/models/<sha256>` (ogni altro percorso e' [ShelfWrite.Refused]); non sostituisce
 * mai; la scrittura e' tutta-o-niente (un fallimento a meta' non lascia niente di visibile, come il temporaneo + rinomina del vero);
 * toglie solo se la dimensione e' ancora quella vista.
 *
 * I guasti: [failNext] (un esito programmato per la prossima scrittura, consumato), [failAfterBytes] (la scrittura legge fino a quel
 * punto dalla sorgente e poi cade: nessun file), e quelli del NAS finto (`failWith`, `readFaults`, `opsFaults`).
 */
class InMemoryShelfWriter(
  private val nas: InMemoryNasMedia,
  override val ownerDeviceId: String,
) : ShelfWriter {

  /** I percorsi scritti con successo, in ordine (per provare "solo nella propria cartella"). */
  val written: MutableList<SharePath> = mutableListOf()

  /** I percorsi tolti. */
  val deleted: MutableList<SharePath> = mutableListOf()

  /** Tutti i percorsi per cui e' arrivata una richiesta (anche rifiutata). */
  val requested: MutableList<SharePath> = mutableListOf()

  @Volatile var failNext: ShelfWrite? = null

  /** Se non null, la prossima scrittura legge questi byte e poi cade con [ShelfWrite.NetworkError] senza lasciare niente. */
  @Volatile var failAfterBytes: Long? = null

  private val ownDir: SharePath = NasLayout.ROOT.child("devices").child(ownerDeviceId).child("models")

  private fun isOwnCopy(path: SharePath): Boolean = path.parent() == ownDir && HEX64.matches(path.name)

  override suspend fun publishExclusive(share: String, path: SharePath, source: ShelfSource): ShelfWrite {
    requested += path
    if (!isOwnCopy(path)) return ShelfWrite.Refused("outside_own_folder")
    failNext?.let {
      failNext = null
      return it
    }
    // Si legge tutto prima di toccare il NAS: un fallimento a meta' non lascia niente (il vero scrive in un temporaneo).
    val size = source.size
    if (size !in 0..Int.MAX_VALUE) return ShelfWrite.Refused("too_large")
    val bytes = ByteArray(size.toInt())
    var done = 0
    val limit = failAfterBytes
    while (done < bytes.size) {
      if (limit != null && done >= limit) {
        failAfterBytes = null
        return ShelfWrite.NetworkError
      }
      val n = source.read(done.toLong(), bytes, done, minOf(bytes.size - done, 64 * 1024))
      if (n <= 0) return ShelfWrite.NetworkError
      done += n
    }
    return try {
      when (nas.fileSystem(share).publishExclusive(path, bytes)) {
        PublishResult.CREATED -> {
          written += path
          ShelfWrite.Written
        }
        PublishResult.ALREADY_EXISTS -> ShelfWrite.Exists
      }
    } catch (e: NasIoException) {
      ShelfWrite.NetworkError
    }
  }

  override suspend fun deleteOwn(share: String, path: SharePath, size: Long): ShelfDelete {
    requested += path
    if (!isOwnCopy(path)) return ShelfDelete.Failed
    val stat = nas.reader.stat(share, path, Purpose.BACKGROUND)
    val entry = when (stat) {
      is NasResult.Err -> return ShelfDelete.Failed
      is NasResult.Ok -> stat.value ?: return ShelfDelete.Missing
    }
    if (entry.isDirectory) return ShelfDelete.Failed
    if (entry.size != size) return ShelfDelete.Changed
    nas.delete(share, path.value)
    deleted += path
    return ShelfDelete.Deleted
  }

  private companion object {
    val HEX64 = Regex("^[0-9a-f]{64}$")
  }
}
