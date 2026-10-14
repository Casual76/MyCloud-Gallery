package dev.pampa.mycloudgallery.core.mediastore.write

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.system.ErrnoException
import android.system.OsConstants
import dev.pampa.mycloudgallery.core.common.coroutines.DefaultDispatcherProvider
import dev.pampa.mycloudgallery.core.common.coroutines.DispatcherProvider
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.mediastore.contract.PendingCopy
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaWriter
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneSaveSource
import dev.pampa.mycloudgallery.core.mediastore.contract.SaveRequest
import dev.pampa.mycloudgallery.core.mediastore.contract.SaveResult
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.Sha256
import java.io.FileOutputStream
import java.io.IOException
import java.io.SyncFailedException
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * "Scarica sul telefono" sul MediaStore vero (Fase 4, P8; docs/fase4-progetto.md §2.6, D20, E-L2).
 *
 * **Quello che si vede non e' mai a meta'.** Il file nasce come riga con `IS_PENDING = 1`: per le altre app, per la
 * Galleria di sistema e per la nostra lettura del telefono (che esclude le righe in attesa) non esiste. I byte arrivano
 * in quella riga, con lo SHA-256 calcolato mentre scorrono; solo alla fine, con la copia completa, controllata
 * (dimensione, hash atteso) e **gia' collegata al catalogo** da `beforePublish`, la riga passa a `IS_PENDING = 0`. Se
 * qualcosa va storto prima (sorgente interrotta, disco pieno, annullamento, hash diverso, collegamento fallito) la
 * riga si toglie; se il processo muore, la riga in attesa resta invisibile e il giro dopo la ripulisce
 * ([purgeOrphans], chiamata anche all'inizio di ogni salvataggio).
 *
 * **Il collegamento viene prima di rendere visibile il file.** `beforePublish` e' il punto in cui :app fa `attachCopy`
 * sul catalogo con la posizione ([PendingCopy.location]) e l'impronta: quando la lettura del telefono vede la riga
 * nuova trova una posizione gia' nota con gli stessi byte, `SameFile`, e non nasce un secondo media (E-L2). Per questo
 * la posizione e' quella che il MediaStore ha davvero scritto (nome compreso: se il provider ha rinominato un conflitto
 * si legge la riga e non si fidano le intenzioni). Se il collegamento non riesce il file non si pubblica
 * (`LINK_FAILED`): visibile implica collegato. Dal collegamento in poi la pubblicazione non e' piu' annullabile: una
 * posizione nota nel catalogo senza il file sarebbe peggio di un file in piu'.
 *
 * **Nomi.** `Pictures/MyCloud Gallery/`, nome del NAS; se esiste gia' (anche in attesa o nel cestino, senza distinguere
 * maiuscole) diventa "nome (2).ext", "nome (3).ext"... Nessun permesso nuovo: sono file creati dall'app.
 *
 * Codici di [SaveResult.Failed], stabili: `UNSUPPORTED_TYPE` (solo immagini e video), `MEDIASTORE_ERROR`, `SOURCE_FAILED`
 * (la sorgente si e' interrotta), `PHONE_FULL` (`ENOSPC` scrivendo), `SIZE_MISMATCH`, `HASH_MISMATCH`, `LINK_FAILED`.
 * L'annullamento non e' un risultato: la `CancellationException` risale dopo la pulizia.
 *
 * @param versionOf la `MediaStore.getVersion` del volume, per la cache dell'`_ID` della posizione; null = non nota.
 */
class MediaStorePhoneMediaWriter(
  context: Context,
  private val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
  private val relativePath: String = PICTURES_FOLDER,
  private val volumeName: String = MediaStore.VOLUME_EXTERNAL_PRIMARY,
  private val versionOf: () -> String? = { defaultVersion(context.applicationContext, volumeName) },
) : PhoneMediaWriter {

  private val resolver: ContentResolver = context.applicationContext.contentResolver

  /** Serializza "scegli il nome e inserisci" e la ripulitura, cosi' questa non toglie la riga di un salvataggio in corso. */
  private val gate = Mutex()

  /** Le righe di questo processo che si stanno scrivendo adesso (protette da [gate] in scrittura). */
  private val active = HashSet<Long>()

  private class Pending(val id: Long, val uri: Uri, val displayName: String)

  /** Come e' finita la scrittura dei byte: la copia in attesa, oppure il motivo per cui non c'e'. */
  private sealed interface Written {
    class Done(val sha256: Sha256, val size: Long) : Written

    class Failed(val code: String) : Written
  }

  override suspend fun saveToPictures(
    request: SaveRequest,
    source: PhoneSaveSource,
    expectedSha256: Sha256?,
    progress: (Long) -> Unit,
    beforePublish: suspend (PendingCopy) -> Unit,
  ): SaveResult {
    val collection = collectionFor(request.mime) ?: return SaveResult.Failed(UNSUPPORTED_TYPE)
    // Il giro dopo una morte del processo: le righe in attesa lasciate a meta' si tolgono prima di cominciarne un'altra.
    try {
      purgeOrphans()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.d(TAG) { "ripulitura delle righe in attesa non riuscita: ${e.javaClass.simpleName}" }
    }
    val pending = try {
      createPending(request, collection)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.d(TAG) { "riga in attesa non creata: ${e.javaClass.simpleName}" }
      null
    } ?: return SaveResult.Failed(MEDIASTORE_ERROR)

    var published = false
    try {
      val written = writeBytes(pending, source, progress)
      if (written is Written.Failed) return SaveResult.Failed(written.code)
      written as Written.Done
      if (written.size != request.size) return SaveResult.Failed(SIZE_MISMATCH)
      if (expectedSha256 != null && expectedSha256 != written.sha256) return SaveResult.Failed(HASH_MISMATCH)

      val copy = PendingCopy(
        location = Location.Phone(
          volume = volumeName,
          relativePath = relativePath,
          displayName = pending.displayName,
          mediaStoreId = pending.id,
          mediaStoreVersion = versionOf(),
        ),
        sha256 = written.sha256,
        size = written.size,
      )
      try {
        beforePublish(copy)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        GalleryLog.d(TAG) { "collegamento della copia non riuscito: ${e.javaClass.simpleName}" }
        return SaveResult.Failed(LINK_FAILED)
      }
      // Collegata: da qui la pubblicazione non si annulla (vedi la classe).
      val visible = withContext(NonCancellable + dispatchers.io) { publish(pending) }
      if (!visible) return SaveResult.Failed(MEDIASTORE_ERROR)
      published = true
      return SaveResult.Saved(copy)
    } finally {
      withContext(NonCancellable + dispatchers.io) {
        if (!published) discard(pending)
        gate.withLock { active -= pending.id }
      }
    }
  }

  /**
   * Toglie le righe in attesa di `Pictures/MyCloud Gallery/` che nessun salvataggio di questo processo sta scrivendo: i
   * resti di un processo morto a meta'. Restituisce quante ne ha tolte. Pubblica per :app, che puo' chiamarla anche
   * all'avvio; un MediaStore che non risponde lascia le righe dove sono (restano invisibili).
   */
  suspend fun purgeOrphans(): Int = gate.withLock {
    withContext(dispatchers.io) {
      var removed = 0
      for (id in pendingIds()) {
        if (id in active) continue
        if (deleteRow(rowUri(id))) removed++
      }
      removed
    }
  }

  // --- Passi ---------------------------------------------------------------------------------------------------------

  private fun collectionFor(mime: String): Uri? = when {
    mime.startsWith("image/") -> MediaStore.Images.Media.getContentUri(volumeName)
    mime.startsWith("video/") -> MediaStore.Video.Media.getContentUri(volumeName)
    else -> null
  }

  private suspend fun createPending(request: SaveRequest, collection: Uri): Pending? = gate.withLock {
    withContext(dispatchers.io) {
      val name = freeName(request.displayName)
      val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, request.mime)
        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
      }
      val uri = resolver.insert(collection, values) ?: return@withContext null
      val id = ContentUris.parseId(uri)
      active += id
      // Il provider puo' aver cambiato il nome (un conflitto nato nel frattempo): la posizione e' quella scritta.
      Pending(id, rowUri(id), readDisplayName(rowUri(id)) ?: name)
    }
  }

  private suspend fun writeBytes(pending: Pending, source: PhoneSaveSource, progress: (Long) -> Unit): Written =
    withContext(dispatchers.io) {
      val job: Job? = currentCoroutineContext()[Job]
      val digest = MessageDigest.getInstance("SHA-256")
      var total = 0L
      var sinkError: IOException? = null
      val opened = try {
        resolver.openOutputStream(pending.uri, "w")
      } catch (e: IOException) {
        return@withContext Written.Failed(if (isNoSpace(e)) PHONE_FULL else MEDIASTORE_ERROR)
      } catch (e: SecurityException) {
        return@withContext Written.Failed(MEDIASTORE_ERROR)
      }
      val stream = opened ?: return@withContext Written.Failed(MEDIASTORE_ERROR)
      try {
        stream.use {
          source.copyTo { buffer, length ->
            job?.ensureActive()
            try {
              stream.write(buffer, 0, length)
            } catch (e: IOException) {
              sinkError = e
              throw e
            }
            digest.update(buffer, 0, length)
            total += length
            progress(total)
          }
          stream.flush()
          // Su disco davvero prima di rendere visibile il file: una caduta di corrente non lascia un file corto.
          try {
            (stream as? FileOutputStream)?.fd?.sync()
          } catch (_: SyncFailedException) {
            // Non tutti i descrittori si sincronizzano: la chiusura che segue basta.
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: IOException) {
        val write = sinkError
        return@withContext Written.Failed(
          when {
            write != null -> if (isNoSpace(write)) PHONE_FULL else MEDIASTORE_ERROR
            isNoSpace(e) -> PHONE_FULL
            else -> SOURCE_FAILED
          },
        )
      }
      Written.Done(Sha256(digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }), total)
    }

  private fun publish(pending: Pending): Boolean = try {
    val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
    resolver.update(pending.uri, values, null, null) > 0
  } catch (e: Exception) {
    GalleryLog.d(TAG) { "pubblicazione non riuscita: ${e.javaClass.simpleName}" }
    false
  }

  /** Toglie la riga (e con lei il file). Un errore la lascia in attesa: invisibile, la tolgono i giri dopo. */
  private fun discard(pending: Pending) {
    deleteRow(pending.uri)
  }

  private fun deleteRow(uri: Uri): Boolean = try {
    resolver.delete(uri, null, null) > 0
  } catch (e: Exception) {
    GalleryLog.d(TAG) { "riga in attesa non tolta: ${e.javaClass.simpleName}" }
    false
  }

  // --- Interrogazioni -------------------------------------------------------------------------------------------------

  private fun filesUri(): Uri = MediaStore.Files.getContentUri(volumeName)

  private fun rowUri(id: Long): Uri = ContentUris.withAppendedId(filesUri(), id)

  /**
   * Il nome libero: quello chiesto, poi "nome (2).ext"... Il confronto non distingue maiuscole (FUSE e MediaProvider) ed e'
   * fatto qui sull'elenco dei nomi della cartella, non in SQL: il MediaProvider vero accetta solo un sottoinsieme della
   * grammatica nelle selezioni delle app e un elenco di nomi non ha bisogno di altro che di `relative_path = ?`.
   */
  private fun freeName(wanted: String): String {
    val taken = namesInFolder()
    fun free(name: String) = name.lowercase(Locale.ROOT) !in taken
    if (free(wanted)) return wanted
    val dot = wanted.lastIndexOf('.')
    val base = if (dot > 0) wanted.substring(0, dot) else wanted
    val ext = if (dot > 0) wanted.substring(dot) else ""
    for (n in 2..MAX_SUFFIX) {
      val suffix = " ($n)$ext"
      val candidate = base.take(MAX_NAME - suffix.length) + suffix
      if (free(candidate)) return candidate
    }
    // Mille conflitti: improbabile; un nome che non si ripete.
    return base.take(MAX_NAME - ext.length - UNIQUE_TAIL) + " (" + System.nanoTime().toString(36) + ")" + ext
  }

  /** I nomi (in minuscolo) delle righe della cartella dell'app, anche in attesa o nel cestino. */
  private fun namesInFolder(): Set<String> {
    val names = HashSet<String>()
    val columns = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
    val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
    val args = arrayOf(relativePath)
    // Il MediaStore vero risponde null se non riesce: e' un errore, non "nessuna riga" (un nome libero per sbaglio).
    val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      resolver.query(filesUri(), columns, pendingBundle(selection, args, Match.INCLUDE), null)
    } else {
      @Suppress("DEPRECATION")
      val uri = MediaStore.setIncludePending(filesUri())
      resolver.query(uri, columns, selection, args, null)
    } ?: throw IOException("Il MediaStore non ha risposto.")
    cursor.use {
      while (it.moveToNext()) it.getString(0)?.let { name -> names += name.lowercase(Locale.ROOT) }
    }
    return names
  }

  private fun pendingIds(): List<Long> =
    queryIds("${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf(relativePath), pending = Match.ONLY)

  private fun readDisplayName(uri: Uri): String? = try {
    resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), pendingBundle(null, null, Match.INCLUDE), null)?.use {
      if (it.moveToFirst()) it.getString(0) else null
    }
  } catch (e: Exception) {
    null
  }

  private enum class Match { INCLUDE, ONLY }

  /** Gli `_ID` delle righe della cartella dell'app (le nostre: le altre non si vedono senza permessi). */
  private fun queryIds(selection: String, args: Array<String>, pending: Match): List<Long> {
    val ids = ArrayList<Long>()
    val columns = arrayOf(MediaStore.MediaColumns._ID)
    // Il MediaStore vero risponde null se non riesce: e' un errore, non "nessuna riga" (un nome libero per sbaglio).
    val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      resolver.query(filesUri(), columns, pendingBundle(selection, args, pending), null)
    } else {
      @Suppress("DEPRECATION")
      val uri = MediaStore.setIncludePending(filesUri())
      val extra = if (pending == Match.ONLY) " AND ${MediaStore.MediaColumns.IS_PENDING} = 1" else ""
      resolver.query(uri, columns, selection + extra, args, null)
    } ?: throw IOException("Il MediaStore non ha risposto.")
    cursor.use {
      while (it.moveToNext()) ids += it.getLong(0)
    }
    return ids
  }

  private fun pendingBundle(selection: String?, args: Array<String>?, pending: Match): Bundle = Bundle().apply {
    if (selection != null) putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
    if (args != null) putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      putInt(MediaStore.QUERY_ARG_MATCH_PENDING, if (pending == Match.ONLY) MediaStore.MATCH_ONLY else MediaStore.MATCH_INCLUDE)
      putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
    }
  }

  companion object {
    const val PICTURES_FOLDER: String = "Pictures/MyCloud Gallery/"

    const val UNSUPPORTED_TYPE: String = "UNSUPPORTED_TYPE"
    const val MEDIASTORE_ERROR: String = "MEDIASTORE_ERROR"
    const val SOURCE_FAILED: String = "SOURCE_FAILED"
    const val PHONE_FULL: String = "PHONE_FULL"
    const val SIZE_MISMATCH: String = "SIZE_MISMATCH"
    const val HASH_MISMATCH: String = "HASH_MISMATCH"
    const val LINK_FAILED: String = "LINK_FAILED"

    private const val TAG = "PhoneWriter"
    private const val MAX_NAME = 255
    private const val MAX_SUFFIX = 1_000
    private const val UNIQUE_TAIL = 16

    /** Vero se [e] (o una sua causa) e' "spazio esaurito" (`ENOSPC`, anche come testo del provider). */
    internal fun isNoSpace(e: Throwable?): Boolean {
      var current = e
      var depth = 0
      while (current != null && depth++ < 6) {
        if (current is ErrnoException && current.errno == OsConstants.ENOSPC) return true
        if (current.message?.lowercase(Locale.ROOT)?.let { "enospc" in it || "no space left" in it } == true) return true
        current = current.cause
      }
      return false
    }

    private fun defaultVersion(context: Context, volume: String): String? = try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) MediaStore.getVersion(context, volume) else MediaStore.getVersion(context)
    } catch (_: Exception) {
      null
    }
  }
}
