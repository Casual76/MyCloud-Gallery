package dev.pampa.mycloudgallery.core.nas.session

import dev.pampa.mycloudgallery.core.nas.smb.DirAccess
import dev.pampa.mycloudgallery.core.nas.smb.SmbChangeHandle
import dev.pampa.mycloudgallery.core.nas.smb.SmbDirEntry
import dev.pampa.mycloudgallery.core.nas.smb.SmbDirectory
import dev.pampa.mycloudgallery.core.nas.smb.SmbFileInfo
import dev.pampa.mycloudgallery.core.nas.smb.SmbReadFile
import dev.pampa.mycloudgallery.core.nas.smb.SmbShare
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

// Il "permesso negato" che non lo e' (decisione dell'utente, 15/10): Samba non rinomina una cartella con un file aperto SOTTO dallo stesso
// client (`have_file_open_below`, `strict rename = no`) e risponde `ACCESS_DENIED`, uguale a un vero permesso negato. Per distinguere i due
// casi serve una prova certa, e la sola e' sapere quali handle abbiamo noi aperti sul NAS: questo registro.

/**
 * Gli handle che QUESTA sessione tiene aperti sul NAS (letture di media, elenchi di cartelle), per share e percorso, dall'apertura alla chiusura
 * **vera** (la `CLOSE` arrivata al server, non la richiesta di chiudere: una chiusura in volo conta ancora). Un registro per sessione: Samba
 * controlla gli handle della stessa connessione, e quelli di una sessione chiusa non esistono piu' (li chiude il server con la connessione).
 *
 * E' la **prova** che una rinomina rifiutata dipende da noi ([countBelow] > 0): senza un handle nostro sotto la cartella, `ACCESS_DENIED` resta
 * un permesso negato vero (share in sola lettura, permessi dell'account) e chi chiama lo tratta come tale. Un handle non registrato (un tipo di
 * apertura che qui non si segue) fa solo tornare al comportamento di prima: mai il contrario.
 *
 * Gli handle di lettura dei media possono anche essere **rilasciati** ([releaseBelow]): il proprietario ([OwnHandles.Entry.release]) chiude
 * quello sul NAS e alla lettura dopo lo riapre da solo; un elenco a meta' non si puo' rilasciare, si aspetta che finisca.
 */
internal class OwnHandles {

  /** Che cosa e' stato aperto. */
  enum class Kind { FILE, DIRECTORY }

  /**
   * Un handle aperto: [share] e [path] sono normalizzati (minuscole, separatore `/`), perche' Samba non distingue le maiuscole.
   * [release] lo imposta chi lo possiede e sa riaprirlo (la lettura dei media).
   */
  class Entry internal constructor(val kind: Kind, val share: String, val path: String) {
    @Volatile
    var release: (() -> Unit)? = null
  }

  /** Un handle seguito dal registro: lo implementano i decoratori di [TrackingShare]. */
  interface Tracked {
    val entry: Entry
  }

  private val live: MutableSet<Entry> = ConcurrentHashMap.newKeySet()

  /** Un handle e' stato aperto sul server. */
  fun opened(kind: Kind, share: String, rawPath: String): Entry = Entry(kind, share.lowercase(Locale.ROOT), normalize(rawPath)).also { live += it }

  /** La chiusura e' arrivata al server (o la connessione e' morta: lo stesso). */
  fun closed(entry: Entry) {
    live -= entry
  }

  /** Quanti handle nostri sono aperti SOTTO la cartella [dirPath] di [share] (a qualunque profondita'; la cartella stessa no). */
  fun countBelow(share: String, dirPath: String): Int {
    val (shareKey, prefix) = share.lowercase(Locale.ROOT) to prefixOf(dirPath)
    return live.count { it.share == shareKey && it.path.startsWith(prefix) }
  }

  /**
   * Chiede il rilascio degli handle sotto [dirPath] che sanno rilasciarsi. Restituisce quanti ne ha chiesti; la chiusura vera e' asincrona
   * (`closeLater`): chi deve riprovare subito aspetta le chiusure in volo ([SmbNasSessionManager.awaitCloses]).
   */
  fun releaseBelow(share: String, dirPath: String): Int {
    val (shareKey, prefix) = share.lowercase(Locale.ROOT) to prefixOf(dirPath)
    var asked = 0
    for (entry in live.filter { it.share == shareKey && it.path.startsWith(prefix) }) {
      val release = entry.release ?: continue
      release()
      asked++
    }
    return asked
  }

  /** La sessione e' chiusa: il server ha chiuso i suoi handle. */
  fun clear() {
    live.clear()
  }

  val size: Int get() = live.size

  private companion object {
    fun normalize(raw: String): String = raw.replace('\\', '/').trim('/').lowercase(Locale.ROOT)

    /** Il prefisso dei percorsi che stanno sotto [dirPath]: `dir/`. La radice della share ("") ha tutto sotto. */
    fun prefixOf(dirPath: String): String = normalize(dirPath).let { if (it.isEmpty()) "" else "$it/" }
  }
}

/**
 * Una share vista dal registro degli handle propri: ogni lettura e ogni elenco che apre passa da [OwnHandles]. Il resto va com'e'. Per la
 * sessione e' la share vera ([LiveSession.share]): nessun altro codice tocca la `SmbShare` del trasporto.
 */
internal class TrackingShare(private val inner: SmbShare, private val handles: OwnHandles) : SmbShare by inner {

  override fun openRead(path: String): SmbReadFile {
    val file = inner.openRead(path)
    return TrackedReadFile(file, handles, handles.opened(OwnHandles.Kind.FILE, inner.name, path))
  }

  override fun openDirectory(path: String, access: DirAccess): SmbDirectory {
    val directory = inner.openDirectory(path, access)
    return TrackedDirectory(directory, handles, handles.opened(OwnHandles.Kind.DIRECTORY, inner.name, path))
  }

  // L'implementazione predefinita dell'interfaccia: la si inoltra al trasporto vero esplicitamente.
  override fun openForChange(path: String, exclusive: Boolean): SmbChangeHandle = inner.openForChange(path, exclusive)

  override fun close() = inner.close()
}

private class TrackedReadFile(
  private val inner: SmbReadFile,
  private val handles: OwnHandles,
  override val entry: OwnHandles.Entry,
) : SmbReadFile, OwnHandles.Tracked {

  override val declaredSize: Long get() = inner.declaredSize

  override val info: SmbFileInfo? get() = inner.info

  @Throws(IOException::class)
  override fun read(buffer: ByteArray, fileOffset: Long, bufferOffset: Int, length: Int): Int = inner.read(buffer, fileOffset, bufferOffset, length)

  @Throws(IOException::class)
  override fun resumeKey(): ByteArray = inner.resumeKey()

  override fun close() {
    try {
      inner.close()
    } finally {
      handles.closed(entry)
    }
  }
}

private class TrackedDirectory(
  private val inner: SmbDirectory,
  private val handles: OwnHandles,
  override val entry: OwnHandles.Entry,
) : SmbDirectory, OwnHandles.Tracked {

  @Throws(IOException::class)
  override fun entries(): Iterator<SmbDirEntry> = inner.entries()

  @Throws(IOException::class)
  override fun parentFileId(): Long? = inner.parentFileId()

  override fun close() {
    try {
      inner.close()
    } finally {
      handles.closed(entry)
    }
  }
}
