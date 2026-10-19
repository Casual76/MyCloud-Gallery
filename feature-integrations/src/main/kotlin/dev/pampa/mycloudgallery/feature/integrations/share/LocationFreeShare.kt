package dev.pampa.mycloudgallery.feature.integrations.share

import dev.pampa.mycloudgallery.core.metadata.contract.LocationStripper
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.metadata.contract.StripOutcome
import dev.pampa.mycloudgallery.core.model.MediaId
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

// Fase 5B (P11, docs/fase5-progetto.md §2.7 "Condividere senza posizione", D26, E-X5, E-X6, E-X1, E-V7, L41; contratto `LocationStripper` di
// P0-B, `StripOutcome`): preparare cio' che "Condividi" consegna a un'altra app.
//
//  - **Rimuovi la posizione acceso**: ogni media si copia in `cacheDir/share/<token>/<n>/` (tetti: 100 elementi, 2 GiB, almeno 1 GiB libero
//    dopo la copia), la copia si passa allo `LocationStripper` (che la ricontrolla con piu' lettori) e solo la copia verificata si consegna.
//    **Si fallisce chiusi**: formato non trattabile, posizione rimasta, guasto → la condivisione si ferma con il motivo, tutte le copie fatte
//    si tolgono e **nessun file con la posizione parte**. Un file mai viene consegnato "com'e'" per ripiego.
//  - **Spento**: un media del telefono si condivide com'e' (l'indirizzo del MediaStore, nessuna copia); un solo-NAS si scarica in cache e si
//    consegna (E-V7).
//  - L'indirizzo ha sempre `FLAG_GRANT_READ_URI_PERMISSION` e `ClipData` (`ShareIntents`); il permesso e le copie si tolgono con
//    [ShareLease.release] dopo l'uso.
// Un NAS che non risponde ferma subito (E-X1): `ShareSource.open` risponde `NasUnreachable`, mai un'attesa infinita. Una cancellazione della
// coroutine (E-X4: l'utente annulla mentre si scarica) toglie le copie fatte.

class LocationFreeShare(
  private val cache: ShareCache,
  private val source: ShareSource,
  private val stripper: LocationStripper,
  private val uris: CacheUris,
  private val revoker: UriRevoker,
  private val io: CoroutineContext = Dispatchers.IO,
) {

  /**
   * Prepara i file di [items] per la condivisione. [onProgress] riceve quanti sono fatti e quanti sono in tutto (a ogni media). Non lancia per
   * un guasto del file system o del NAS: lo dice con [SharePreparation.Stopped].
   */
  suspend fun prepare(items: List<MediaId>, removeLocation: Boolean, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): SharePreparation {
    if (items.isEmpty()) return stopped(ShareStop.NOTHING, 0)
    if (items.size > ShareLimits.MAX_ITEMS) return stopped(ShareStop.TOO_MANY, ShareLimits.MAX_ITEMS)
    val dir = try {
      withContext(io) { cache.newShareDir() }
    } catch (_: IOException) {
      return stopped(ShareStop.FAILED, 0)
    }
    val files = ArrayList<SharedFile>(items.size)
    var copiedBytes = 0L
    var stripped = 0
    var done = false
    try {
      for ((index, id) in items.withIndex()) {
        onProgress(index, items.size)
        val opened = when (val result = source.open(id)) {
          ShareOpen.Gone -> return stopped(ShareStop.UNAVAILABLE, index)
          ShareOpen.NasUnreachable -> return stopped(ShareStop.NAS_UNREACHABLE, index)
          is ShareOpen.Opened -> result.media
        }
        val outcome = opened.use { media -> shareOne(media, File(dir, index.toString()), index, removeLocation, copiedBytes) }
        when (outcome) {
          is Item.Stop -> return stopped(outcome.stop, index)
          is Item.Direct -> {
            files += outcome.file
            FlowCounters.share.add(ShareCounter.ITEMS_DIRECT)
          }
          is Item.Copy -> {
            files += outcome.file
            copiedBytes += outcome.file.size
            if (removeLocation) {
              stripped++
              FlowCounters.share.add(ShareCounter.ITEMS_STRIPPED)
            }
          }
        }
      }
      onProgress(items.size, items.size)
      done = true
      FlowCounters.share.add(ShareCounter.PREPARED)
      return SharePreparation.Ready(files, ShareLease(files.map { it.uri }, listOf(dir), revoker, cache), stripped)
    } finally {
      // Una fermata, un guasto o una cancellazione non lasciano niente in cache e nessun permesso: i file con la posizione non esistono piu'.
      if (!done) withContext(NonCancellable + io) { cache.deleteTree(dir) }
    }
  }

  // --- Un media -------------------------------------------------------------------------------------------------------------------

  private sealed interface Item {
    class Stop(val stop: ShareStop) : Item

    class Direct(val file: SharedFile) : Item

    class Copy(val file: SharedFile) : Item
  }

  private suspend fun shareOne(media: OpenedForShare, itemDir: File, index: Int, removeLocation: Boolean, copiedSoFar: Long): Item {
    // Impostazione spenta e media del telefono: si condivide com'e', senza copiare (nessuna posizione da togliere, nessun ripiego).
    media.phoneUri?.let { phone ->
      if (!removeLocation) return Item.Direct(SharedFile(phone, media.mime, media.bytes.size, file = null))
    }
    val size = media.bytes.size
    if (size <= 0) return Item.Stop(ShareStop.UNAVAILABLE)
    if (copiedSoFar + size > ShareLimits.MAX_TOTAL_BYTES) return Item.Stop(ShareStop.TOO_BIG)
    // Per togliere la posizione servono due copie insieme (quella letta e quella pulita); senza, una.
    val needed = if (removeLocation) size * 2 else size
    if (cache.usableBytes() - needed < ShareLimits.MIN_FREE_AFTER_BYTES) return Item.Stop(ShareStop.NO_SPACE)
    return withContext(io) {
      if (!itemDir.mkdirs() && !itemDir.isDirectory) return@withContext Item.Stop(ShareStop.FAILED)
      val work = File(itemDir, WORK)
      try {
        copy(media.bytes, work)
      } catch (_: IOException) {
        return@withContext Item.Stop(ShareStop.UNAVAILABLE)
      } catch (_: SecurityException) {
        return@withContext Item.Stop(ShareStop.UNAVAILABLE)
      }
      try {
        if (!removeLocation) {
          finish(work, itemDir, media, media.mime, size)
        } else {
          val clean = File(itemDir, CLEAN)
          when (val result = stripper.strip(work, clean, media.mime)) {
            is StripOutcome.Stripped -> finish(clean, itemDir, media, result.mime, clean.length())
            // Un formato che non si sa trattare, o nessuno strumento legato: la condivisione senza posizione non si fa (L41).
            is StripOutcome.Unsupported -> Item.Stop(ShareStop.CANNOT_REMOVE_LOCATION)
            StripOutcome.StillHasLocation -> Item.Stop(ShareStop.LOCATION_REMAINS)
            is StripOutcome.Failed -> Item.Stop(ShareStop.FAILED)
          }
        }
      } finally {
        // La copia letta (con la posizione) non resta in cache un istante piu' del necessario.
        work.delete()
      }
    }
  }

  /** Rinomina il file finito con il nome vero e ne fa l'indirizzo. */
  private fun finish(ready: File, itemDir: File, media: OpenedForShare, mime: String, size: Long): Item {
    val named = File(itemDir, ShareNames.of(media.displayName, mime))
    if (!ready.renameTo(named)) return Item.Stop(ShareStop.FAILED)
    val uri = try {
      uris.uriFor(named)
    } catch (_: IllegalArgumentException) {
      named.delete()
      return Item.Stop(ShareStop.FAILED)
    }
    return Item.Copy(SharedFile(uri, mime, size, named))
  }

  /** Copia [bytes] in [target] a blocchi. Lancia `IOException` se la lettura si accorcia o il disco finisce. */
  private suspend fun copy(bytes: MediaBytes, target: File) {
    val size = bytes.size
    target.outputStream().use { out ->
      var at = 0L
      while (at < size) {
        currentCoroutineContext().ensureActive()
        val n = minOf(CHUNK.toLong(), size - at)
        val chunk = bytes.read(at until at + n)
        if (chunk.isEmpty()) throw IOException("Lettura accorciata.")
        out.write(chunk)
        at += chunk.size
      }
    }
  }

  private fun stopped(stop: ShareStop, index: Int): SharePreparation.Stopped {
    FlowCounters.share.add(
      when (stop) {
        ShareStop.CANNOT_REMOVE_LOCATION -> ShareCounter.STOPPED_FORMAT
        ShareStop.LOCATION_REMAINS -> ShareCounter.STOPPED_LOCATION_REMAINS
        ShareStop.UNAVAILABLE, ShareStop.NAS_UNREACHABLE -> ShareCounter.STOPPED_UNAVAILABLE
        ShareStop.TOO_MANY, ShareStop.TOO_BIG, ShareStop.NO_SPACE, ShareStop.NOTHING -> ShareCounter.STOPPED_LIMITS
        ShareStop.FAILED -> ShareCounter.STOPPED_FAILED
      },
    )
    return SharePreparation.Stopped(stop, index)
  }

  private companion object {
    const val CHUNK = 1 shl 20
    const val WORK = "w"
    const val CLEAN = "o"
  }
}
