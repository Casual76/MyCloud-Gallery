package dev.pampa.mycloudgallery.core.mediastore.write

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import dev.pampa.mycloudgallery.core.common.coroutines.DefaultDispatcherProvider
import dev.pampa.mycloudgallery.core.common.coroutines.DispatcherProvider
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.mediastore.android.AndroidPhoneMediaIntents
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaRewriter
import dev.pampa.mycloudgallery.core.mediastore.contract.RewriteCodes
import dev.pampa.mycloudgallery.core.mediastore.contract.RewriteResult
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.Sha256
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.SyncFailedException
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * "Riscrivere un file del telefono" sul MediaStore vero (Fase 5B, P0-B; docs/fase5-progetto.md §1.2, D19, E-E10).
 *
 * **Una rete di salvataggio sotto ogni scrittura.** Riscrivere sul posto un file dell'utente e' l'unica azione dell'app che puo' lasciarne
 * uno a meta' se il telefono si spegne; per questo l'ordine e' fisso e il sistema non e' mai senza una copia buona:
 *  1. il file nuovo ha lo SHA-256 atteso (altrimenti [RewriteResult.SourceMismatch], niente e' toccato);
 *  2. i byte **attuali** della riga (per le foto con `setRequireOriginal`: con la posizione, mai oscurati) si copiano in `cacheDir/rewrite/`
 *     e se ne calcola lo SHA-256: se la copia non riesce (disco pieno, riga sparita, permesso mancante) la riga non e' stata toccata;
 *  3. si scrive sul posto (`rw`, senza `t`: mai troncata prima di avere scritto), si accorcia alla lunghezza nuova e si sincronizza;
 *  4. si **rilegge** (di nuovo `setRequireOriginal`) e si confronta con l'atteso;
 *  5. se qualcosa non torna si rimettono i byte di prima, si rileggono e si confrontano con lo SHA-256 di prima: [RewriteResult.Failed]
 *     dice se la riga e' stata ripristinata. La copia di sicurezza si toglie sempre.
 * Dal punto 2 in poi il lavoro non si annulla ([NonCancellable]): una riga a meta' sarebbe peggio di un lavoro finito in ritardo.
 *
 * **Il permesso** e' quello di [dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaIntents.writeRequest] per questa riga: senza,
 * l'apertura in scrittura lancia `SecurityException` ed e' [RewriteResult.NotGranted]. L'app non chiede `MANAGE_MEDIA`.
 *
 * Con D13 (build debug con gli account di prova) il chiamante limita le righe a `mcg-prova`: la recinzione e' di `:app` (P14).
 *
 * @param undoDir dove stanno le copie di sicurezza; di default `cacheDir/rewrite/` (non quelle servite da un `FileProvider`).
 */
class MediaStorePhoneMediaRewriter(
  context: Context,
  private val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
  undoDir: File? = null,
) : PhoneMediaRewriter {

  private val resolver: ContentResolver = context.applicationContext.contentResolver
  private val intents = AndroidPhoneMediaIntents(context)
  private val undoRoot: File = undoDir ?: File(context.applicationContext.cacheDir, UNDO_FOLDER)

  /** Una riscrittura alla volta: due non si accavallano mai sulla stessa riga ne' sulla stessa cartella delle copie di sicurezza. */
  private val gate = Mutex()

  override suspend fun replace(ref: MediaRef.Phone, file: File, expectedSha256: Sha256): RewriteResult = gate.withLock {
    withContext(NonCancellable + dispatchers.io) { rewrite(ref, file, expectedSha256) }
  }

  private fun rewrite(ref: MediaRef.Phone, file: File, expected: Sha256): RewriteResult {
    // 1. Il file nuovo e' quello atteso.
    val newSha = try {
      sha256Of(file)
    } catch (_: IOException) {
      return RewriteResult.SourceMismatch
    }
    if (newSha != expected) return RewriteResult.SourceMismatch
    val newSize = file.length()

    val uri = intents.uriOf(ref)
    // Le foto si leggono con i byte originali (posizione compresa): una copia di sicurezza o una rilettura oscurate sarebbero un file diverso.
    val readUri = if (ref.mime?.lowercase(Locale.ROOT)?.startsWith("image/") == true) MediaStore.setRequireOriginal(uri) else uri

    // 2. La copia di sicurezza dei byte attuali.
    undoRoot.mkdirs()
    val undo = File(undoRoot, "undo-${System.nanoTime()}.bin")
    try {
      val before = try {
        copyOut(readUri, undo)
      } catch (_: FileNotFoundException) {
        return RewriteResult.Gone
      } catch (_: SecurityException) {
        return RewriteResult.NotGranted
      } catch (e: IOException) {
        return RewriteResult.Failed(if (MediaStorePhoneMediaWriter.isNoSpace(e)) RewriteCodes.PHONE_FULL else RewriteCodes.WRITE_ERROR, restored = true)
      }

      // 3. La scrittura sul posto.
      val written = try {
        writeInPlace(uri, file)
        null
      } catch (_: FileNotFoundException) {
        return RewriteResult.Gone
      } catch (_: SecurityException) {
        return RewriteResult.NotGranted
      } catch (e: IOException) {
        e
      }
      if (written != null) {
        val code = if (MediaStorePhoneMediaWriter.isNoSpace(written)) RewriteCodes.PHONE_FULL else RewriteCodes.WRITE_ERROR
        return RewriteResult.Failed(code, restored = rollBack(uri, readUri, undo, before))
      }

      // 4. La rilettura.
      val back = try {
        sha256Of(readUri)
      } catch (e: IOException) {
        GalleryLog.d(TAG) { "rilettura non riuscita: ${e.javaClass.simpleName}" }
        null
      }
      if (back == expected) return RewriteResult.Replaced(expected, newSize)

      // 5. Qualcosa non torna: si rimettono i byte di prima.
      val code = if (back == null) RewriteCodes.WRITE_ERROR else RewriteCodes.HASH_MISMATCH
      return RewriteResult.Failed(code, restored = rollBack(uri, readUri, undo, before))
    } finally {
      undo.delete()
    }
  }

  /** Rimette sulla riga i byte della copia di sicurezza e li rilegge: vero solo se tornano quelli di prima. */
  private fun rollBack(uri: Uri, readUri: Uri, undo: File, before: Sha256): Boolean = try {
    writeInPlace(uri, undo)
    sha256Of(readUri) == before
  } catch (e: Exception) {
    GalleryLog.d(TAG) { "ripristino non riuscito: ${e.javaClass.simpleName}" }
    false
  }

  /** Scrive [source] dall'inizio sulla riga, accorcia alla sua lunghezza e sincronizza. `rw` e non `rwt`: mai troncata prima di scrivere. */
  private fun writeInPlace(uri: Uri, source: File) {
    val descriptor: ParcelFileDescriptor = resolver.openFileDescriptor(uri, "rw") ?: throw FileNotFoundException("Nessun descrittore.")
    // Il flusso e' l'unico proprietario del descrittore: chiudendolo si chiude anche quello.
    ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { out ->
      FileInputStream(source).use { input ->
        val buffer = ByteArray(BUFFER)
        while (true) {
          val n = input.read(buffer)
          if (n < 0) break
          out.write(buffer, 0, n)
        }
      }
      out.channel.truncate(source.length())
      out.flush()
      try {
        out.fd.sync()
      } catch (_: SyncFailedException) {
        // Non tutti i descrittori si sincronizzano: la chiusura che segue basta.
      }
    }
  }

  /** Copia i byte di [uri] in [target] e restituisce il loro SHA-256. */
  private fun copyOut(uri: Uri, target: File): Sha256 {
    val digest = MessageDigest.getInstance("SHA-256")
    val input = resolver.openInputStream(uri) ?: throw FileNotFoundException("Nessun flusso.")
    input.use { stream ->
      FileOutputStream(target).use { out ->
        val buffer = ByteArray(BUFFER)
        while (true) {
          val n = stream.read(buffer)
          if (n < 0) break
          out.write(buffer, 0, n)
          digest.update(buffer, 0, n)
        }
        out.flush()
      }
    }
    return hex(digest)
  }

  private fun sha256Of(uri: Uri): Sha256 {
    val input = resolver.openInputStream(uri) ?: throw FileNotFoundException("Nessun flusso.")
    return input.use { digestOf(it) }
  }

  private fun sha256Of(file: File): Sha256 = FileInputStream(file).use { digestOf(it) }

  private fun digestOf(stream: InputStream): Sha256 {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(BUFFER)
    while (true) {
      val n = stream.read(buffer)
      if (n < 0) break
      digest.update(buffer, 0, n)
    }
    return hex(digest)
  }

  private fun hex(digest: MessageDigest): Sha256 = Sha256(digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })

  companion object {
    /** Le copie di sicurezza stanno in `cacheDir/rewrite/`: fuori da `cacheDir/share/` e `cacheDir/edit/`, le sole che un `FileProvider` serve. */
    const val UNDO_FOLDER: String = "rewrite"

    private const val TAG = "PhoneRewriter"
    private const val BUFFER = 64 * 1024
  }
}
