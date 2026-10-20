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
import dev.pampa.mycloudgallery.core.mediastore.contract.RewriteRecovery
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
import java.util.Properties
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * "Riscrivere un file del telefono" sul MediaStore vero (Fase 5B, P0-B; docs/fase5-progetto.md §1.2, D19, E-E10; correzioni R3, R9, R10 della
 * revisione di sicurezza 5B e VE-2 della verifica funzionale).
 *
 * **Una rete di salvataggio sotto ogni scrittura.** Riscrivere sul posto un file dell'utente e' l'unica azione dell'app che puo' lasciarne
 * uno a meta' se il telefono si spegne; per questo l'ordine e' fisso e il sistema non e' mai senza una copia buona:
 *  1. il file nuovo ha lo SHA-256 atteso (altrimenti [RewriteResult.SourceMismatch], niente e' toccato);
 *  2. i byte **attuali** della riga (sempre con `setRequireOriginal`, foto e video: con la posizione, mai oscurati, R10) si copiano nella
 *     cartella delle copie di sicurezza (in `noBackupFilesDir`, R9) e se ne calcola lo SHA-256: se la copia non riesce (disco pieno, riga
 *     sparita, permesso mancante, originale non leggibile) la riga non e' stata toccata;
 *  3. **R3: i byte di adesso devono avere l'impronta attesa**, quella della versione che si sostituisce e che e' al sicuro sul NAS: se no
 *     [RewriteResult.RowChanged] e niente si scrive (una modifica non ancora salvata, il catalogo in ritardo, un `_ID` riciclato); se la riga ha
 *     gia' i byte nuovi (una riscrittura finita con la risposta persa) e' fatto;
 *  4. **R9: il diario della scrittura** (URI, impronta di prima e nuova) si scrive e si sincronizza accanto alla copia, **prima** di toccare la
 *     riga: se il processo muore durante la scrittura, [recover] all'avvio rimette la riga com'era;
 *  5. si scrive sul posto (`rw`, senza `t`: mai troncata prima di avere scritto), si accorcia alla lunghezza nuova e si sincronizza;
 *  6. si **rilegge** (di nuovo `setRequireOriginal`) e si confronta con l'atteso;
 *  7. se qualcosa non torna si rimettono i byte di prima, si rileggono e si confrontano con lo SHA-256 di prima: [RewriteResult.Failed] dice
 *     se la riga e' stata ripristinata. Diario e copia si tolgono quando la riga e' a posto (nuova o com'era); se il ripristino non torna
 *     restano, e [recover] riprova.
 * Dal punto 2 in poi il lavoro non si annulla ([NonCancellable]): una riga a meta' sarebbe peggio di un lavoro finito in ritardo. Una
 * riscrittura alla volta in tutto il processo.
 *
 * **L'URI** (VE-2) e' quello della collezione del tipo della riga ([AndroidPhoneMediaIntents.requestUriOf]), lo stesso che chiede il dialogo
 * di sistema: il permesso di `createWriteRequest` vale proprio per l'URI che si apre. Senza permesso l'apertura in scrittura lancia
 * `SecurityException` ed e' [RewriteResult.NotGranted]. L'app non chiede `MANAGE_MEDIA`.
 *
 * Con D13 (build debug con gli account di prova) il chiamante limita le righe a `mcg-prova`: la recinzione e' di `:app` (P14).
 *
 * @param undoDir dove stanno copie di sicurezza e diario; di default `noBackupFilesDir/rewrite/` (sopravvive alla pulizia della cache e non e'
 *   servita da nessun `FileProvider`).
 */
class MediaStorePhoneMediaRewriter(
  context: Context,
  private val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
  undoDir: File? = null,
) : PhoneMediaRewriter {

  private val resolver: ContentResolver = context.applicationContext.contentResolver
  private val intents = AndroidPhoneMediaIntents(context)
  private val undoRoot: File = undoDir ?: File(context.applicationContext.noBackupFilesDir, UNDO_FOLDER)

  override suspend fun replace(ref: MediaRef.Phone, file: File, expectedSha256: Sha256, currentSha256: Sha256): RewriteResult = GATE.withLock {
    withContext(NonCancellable + dispatchers.io) {
      // R9: una riscrittura lasciata a meta' da un processo morto si sistema prima di cominciarne un'altra.
      recoverLocked()
      rewrite(ref, file, expectedSha256, currentSha256)
    }
  }

  override suspend fun recover(): RewriteRecovery = GATE.withLock { withContext(NonCancellable + dispatchers.io) { recoverLocked() } }

  private fun rewrite(ref: MediaRef.Phone, file: File, expected: Sha256, current: Sha256): RewriteResult {
    // 1. Il file nuovo e' quello atteso.
    val newSha = try {
      sha256Of(file)
    } catch (_: IOException) {
      return RewriteResult.SourceMismatch
    }
    if (newSha != expected) return RewriteResult.SourceMismatch
    val newSize = file.length()

    // VE-2: l'URI della collezione del tipo della riga, lo stesso del dialogo di sistema. Una riga che non c'e' piu' non ha un tipo.
    val uri = intents.requestUriOf(ref) ?: return RewriteResult.Gone
    // R10: foto e video si leggono con i byte originali (posizione compresa): una copia di sicurezza o una rilettura oscurate sarebbero un
    // file diverso, e rimetterle toglierebbe per sempre la posizione.
    val readUri = MediaStore.setRequireOriginal(uri)

    // 2. La copia di sicurezza dei byte attuali.
    if (!undoRoot.isDirectory && !undoRoot.mkdirs()) return RewriteResult.Failed(RewriteCodes.WRITE_ERROR, restored = true)
    val token = "${System.currentTimeMillis()}-${System.nanoTime()}"
    val undo = File(undoRoot, "$token$UNDO_SUFFIX")
    val journal = File(undoRoot, "$token$JOURNAL_SUFFIX")
    var keep = false
    try {
      val before = try {
        copyOut(readUri, undo)
      } catch (_: FileNotFoundException) {
        return RewriteResult.Gone
      } catch (_: SecurityException) {
        return RewriteResult.NotGranted
      } catch (_: UnsupportedOperationException) {
        // R10: senza `ACCESS_MEDIA_LOCATION` l'originale non si apre: niente copia oscurata, niente scrittura.
        return RewriteResult.Failed(RewriteCodes.ORIGINAL_UNREADABLE, restored = true)
      } catch (e: IOException) {
        return RewriteResult.Failed(if (MediaStorePhoneMediaWriter.isNoSpace(e)) RewriteCodes.PHONE_FULL else RewriteCodes.WRITE_ERROR, restored = true)
      } catch (e: RuntimeException) {
        GalleryLog.w(TAG) { "copia di sicurezza non riuscita: ${e.javaClass.simpleName}" }
        return RewriteResult.Failed(RewriteCodes.WRITE_ERROR, restored = true)
      }

      // 3. R3: la riga ha i byte nuovi (fatto) o quelli attesi; altro = una versione che non e' al sicuro, non si tocca.
      if (before == expected) return RewriteResult.Replaced(expected, newSize)
      if (before != current) {
        GalleryLog.w(TAG) { "la riga non ha l'impronta attesa: niente scritto" }
        return RewriteResult.RowChanged
      }

      // 4. R9: il diario prima di toccare la riga.
      try {
        writeJournal(journal, uri, before, expected)
      } catch (e: IOException) {
        return RewriteResult.Failed(if (MediaStorePhoneMediaWriter.isNoSpace(e)) RewriteCodes.PHONE_FULL else RewriteCodes.WRITE_ERROR, restored = true)
      }

      // 5. La scrittura sul posto.
      val written = try {
        writeInPlace(uri, file)
        null
      } catch (_: FileNotFoundException) {
        // La riga non si apre in scrittura: niente e' stato scritto.
        return RewriteResult.Gone
      } catch (_: SecurityException) {
        return RewriteResult.NotGranted
      } catch (e: IOException) {
        e
      } catch (e: RuntimeException) {
        IOException(e)
      }
      if (written != null) {
        val code = if (MediaStorePhoneMediaWriter.isNoSpace(written)) RewriteCodes.PHONE_FULL else RewriteCodes.WRITE_ERROR
        val restored = rollBack(uri, readUri, undo, before)
        keep = !restored
        return RewriteResult.Failed(code, restored)
      }

      // 6. La rilettura.
      val back = try {
        sha256Of(readUri)
      } catch (e: Exception) {
        GalleryLog.d(TAG) { "rilettura non riuscita: ${e.javaClass.simpleName}" }
        null
      }
      if (back == expected) return RewriteResult.Replaced(expected, newSize)

      // 7. Qualcosa non torna: si rimettono i byte di prima.
      val code = if (back == null) RewriteCodes.WRITE_ERROR else RewriteCodes.HASH_MISMATCH
      val restored = rollBack(uri, readUri, undo, before)
      keep = !restored
      return RewriteResult.Failed(code, restored)
    } finally {
      // Riga a posto (nuova, com'era, mai toccata): diario e copia si tolgono, nell'ordine. Non a posto: restano per il recupero (R9).
      if (!keep) {
        journal.delete()
        undo.delete()
      }
    }
  }

  /**
   * R9: i diari rimasti (un processo morto durante la scrittura, un ripristino che non tornava). Per ogni riga: con i byte nuovi o con quelli di
   * prima e' a posto; sparita, niente da fare; altrimenti la copia di sicurezza (con l'impronta di prima) si rimette e si rilegge. Un permesso
   * decaduto lascia diario e copia per la volta dopo. Le copie senza diario (morte prima del diario: la riga non e' stata toccata) si tolgono.
   */
  private fun recoverLocked(): RewriteRecovery {
    val entries = undoRoot.listFiles() ?: return RewriteRecovery.NONE
    var restored = 0
    var settled = 0
    var pending = 0
    val journals = entries.filter { it.name.endsWith(JOURNAL_SUFFIX) }
    val tokens = journals.map { it.name.removeSuffix(JOURNAL_SUFFIX) }.toSet()
    // Copie senza diario e diari a meta' (`.tmp`): niente e' stato scritto sulla riga.
    for (f in entries) {
      val orphanUndo = f.name.endsWith(UNDO_SUFFIX) && f.name.removeSuffix(UNDO_SUFFIX) !in tokens
      if (orphanUndo || f.name.endsWith(JOURNAL_SUFFIX + TMP_SUFFIX)) f.delete()
    }
    for (journal in journals) {
      val undo = File(undoRoot, journal.name.removeSuffix(JOURNAL_SUFFIX) + UNDO_SUFFIX)
      when (recoverOne(journal, undo)) {
        Recovered.RESTORED -> restored++
        Recovered.SETTLED -> settled++
        Recovered.PENDING -> {
          pending++
          continue
        }
      }
      journal.delete()
      undo.delete()
    }
    if (restored + pending > 0) GalleryLog.w(TAG) { "riscritture interrotte: $restored rimesse, $pending ancora da rimettere" }
    return RewriteRecovery(restored, settled, pending)
  }

  private enum class Recovered { RESTORED, SETTLED, PENDING }

  private fun recoverOne(journal: File, undo: File): Recovered {
    val entry = readJournal(journal) ?: return Recovered.SETTLED
    val readUri = MediaStore.setRequireOriginal(entry.uri)
    val now = try {
      sha256Of(readUri)
    } catch (_: FileNotFoundException) {
      // La riga non c'e' piu': niente da rimettere.
      return Recovered.SETTLED
    } catch (e: Exception) {
      GalleryLog.d(TAG) { "riga da recuperare non letta: ${e.javaClass.simpleName}" }
      return Recovered.PENDING
    }
    if (now == entry.after || now == entry.before) return Recovered.SETTLED
    // A meta': si rimette la copia di sicurezza, solo se e' davvero quella di prima.
    val undoSha = try {
      sha256Of(undo)
    } catch (_: IOException) {
      null
    }
    if (undoSha != entry.before) {
      GalleryLog.w(TAG) { "copia di sicurezza assente o diversa: la riga non si puo' rimettere" }
      return Recovered.SETTLED
    }
    return if (rollBack(entry.uri, readUri, undo, entry.before)) Recovered.RESTORED else Recovered.PENDING
  }

  private class JournalEntry(val uri: Uri, val before: Sha256, val after: Sha256)

  /** Il diario si scrive in un temporaneo sincronizzato e prende il suo nome con una rinomina: o c'e' intero, o non c'e'. */
  private fun writeJournal(journal: File, uri: Uri, before: Sha256, after: Sha256) {
    val props = Properties().apply {
      setProperty(KEY_URI, uri.toString())
      setProperty(KEY_BEFORE, before.hex)
      setProperty(KEY_AFTER, after.hex)
    }
    val temp = File(journal.parentFile, journal.name + TMP_SUFFIX)
    FileOutputStream(temp).use { out ->
      props.store(out, null)
      out.flush()
      try {
        out.fd.sync()
      } catch (_: SyncFailedException) {
        // La rinomina che segue resta atomica.
      }
    }
    if (!temp.renameTo(journal)) {
      temp.delete()
      throw IOException("Diario della riscrittura non scritto.")
    }
  }

  private fun readJournal(journal: File): JournalEntry? = try {
    val props = Properties().apply { FileInputStream(journal).use { load(it) } }
    val uri = props.getProperty(KEY_URI)?.let(Uri::parse)
    val before = props.getProperty(KEY_BEFORE)?.let(::Sha256)
    val after = props.getProperty(KEY_AFTER)?.let(::Sha256)
    if (uri == null || before == null || after == null || uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority != MediaStore.AUTHORITY) {
      null
    } else {
      JournalEntry(uri, before, after)
    }
  } catch (_: IOException) {
    null
  } catch (_: IllegalArgumentException) {
    null
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

  /** Copia i byte di [uri] in [target], la sincronizza e restituisce il loro SHA-256. */
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
        try {
          out.fd.sync()
        } catch (_: SyncFailedException) {
          // Come sopra.
        }
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
    /** Copie di sicurezza e diario stanno in `noBackupFilesDir/rewrite/`: mai serviti da un `FileProvider`, mai tolti con la cache (R9). */
    const val UNDO_FOLDER: String = "rewrite"

    /** Le estensioni dei file della cartella: la copia dei byte di prima e il diario della scrittura. */
    const val UNDO_SUFFIX: String = ".undo"
    const val JOURNAL_SUFFIX: String = ".journal"
    private const val TMP_SUFFIX = ".tmp"

    private const val KEY_URI = "uri"
    private const val KEY_BEFORE = "before"
    private const val KEY_AFTER = "after"

    private const val TAG = "PhoneRewriter"
    private const val BUFFER = 64 * 1024

    /** Una riscrittura (o un recupero) alla volta in tutto il processo, qualunque istanza la chieda. */
    private val GATE = Mutex()
  }
}
