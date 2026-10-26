package dev.pampa.mycloudgallery.core.albums.chaos

import dev.pampa.mycloudgallery.core.nas.smb.DirAccess
import dev.pampa.mycloudgallery.core.nas.smb.NtStatus
import dev.pampa.mycloudgallery.core.nas.smb.SmbConnection
import dev.pampa.mycloudgallery.core.nas.smb.SmbSession
import dev.pampa.mycloudgallery.core.nas.smb.SmbStatusException
import dev.pampa.mycloudgallery.core.nas.smb.SmbTransport
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

// L'ORACOLO SUL NAS VERO: il "PC" accanto al NAS. Una connessione sua (un login sua, fatto una volta: se non riesce la prova si ferma) attraverso lo stesso
// trasporto recintato del motore ma SENZA colpi: serve a mettere i file di partenza, a leggere i byte di ogni file e a elencarli (gli invarianti si
// controllano sul NAS vero, non su quello che il motore dice di aver fatto), a cambiare un file dal "PC" e, alla fine, a togliere la cartella del giro.
// Percorsi relativi al mondo (`mcg-it/<giro>/r<N>`), con `/`. Tutto sincrono: il banco gira su un thread solo.

/** Cosa ha tolto e cosa e' rimasto la pulizia di un mondo. Solo numeri e, al piu', qualche percorso di prova (mai contenuti). */
class CleanupReport(val files: Int, val folders: Int, val remaining: List<String>, val rootRemoved: Boolean) {
  val clean: Boolean get() = remaining.isEmpty() && rootRemoved

  fun text(): String =
    if (clean) {
      "tolti $files file e $folders cartelle; radice tolta, niente rimasto"
    } else {
      "tolti $files file e $folders cartelle; rimasto: ${remaining.size} voci${if (rootRemoved) "" else ", radice non tolta"} (${remaining.take(8).joinToString()})"
    }
}

class AreaAdmin(
  private val transport: SmbTransport,
  private val connection: ChaosConnection,
  private val latch: AreaAuthLatch,
  private val shareNames: List<String> = listOf(ShareNasTargetNames.PRIVATE, ShareNasTargetNames.COMMON),
  /** Quante volte e per quanto si riprova a togliere un file che il NAS dice "in uso" (handle di una connessione morta che il NAS tiene ancora). */
  private val cleanupRetries: Int = 6,
  private val cleanupPauseMs: Long = 20_000,
  /** Cosa fare quando si chiude: la pulizia del mondo (di norma si). */
  private val cleanOnClose: Boolean = true,
  private val onCleaned: (CleanupReport) -> Unit = {},
) : ChaosAdmin {

  private var session: SmbSession? = null
  private var link: SmbConnection? = null
  private var shares: Map<String, AreaShare> = emptyMap()

  /** Il login (una volta) e le due share con la radice del mondo creata. Un login rifiutato ferma tutto: [AreaAuthStop], nessun nuovo tentativo. */
  fun open(): AreaAdmin {
    connect()
    shares.values.forEach { it.ensureRoot() }
    return this
  }

  private fun connect() {
    if (latch.failed) throw AreaAuthStop("login gia' rifiutato dal NAS: nessun nuovo tentativo")
    runCatching { link?.close() }
    val c = transport.connect(connection.binding, NasAddress(connection.host), 15.seconds)
    link = c
    val s = try {
      c.authenticate(connection.user, connection.password.copyOf())
    } catch (e: SmbStatusException) {
      runCatching { c.close() }
      throw AreaAuthStop("login rifiutato dal NAS (codice 0x%08X): fermo, nessun nuovo tentativo".format(e.status))
    }
    session = s
    shares = shareNames.associateWith { name -> s.connectShare(name) as AreaShare }
  }

  /** Un'operazione sulla share; se la connessione e' caduta (un NAS che chiude le sessioni ferme) si riapre UNA volta e si riprova. */
  private fun <T> withShare(share: String, block: (AreaShare) -> T): T {
    val key = shareNames.firstOrNull { it.equals(share, ignoreCase = true) } ?: throw AreaViolation("share fuori dall'area di prova")
    return try {
      block(shares.getValue(key))
    } catch (e: IOException) {
      if (e is SmbStatusException && e.status != NtStatus.USER_SESSION_DELETED && e.status != NtStatus.NETWORK_SESSION_EXPIRED) throw e
      connect()
      block(shares.getValue(key))
    }
  }

  override fun put(share: String, path: String, bytes: ByteArray) = withShare(share) { s ->
    mkdirs(s, path.substringBeforeLast('/', ""))
    val file = try {
      s.createExclusive(path)
    } catch (e: SmbStatusException) {
      if (e.status != NtStatus.OBJECT_NAME_COLLISION) throw e
      s.deleteFile(path)
      s.createExclusive(path)
    }
    file.use {
      var offset = 0
      while (offset < bytes.size) {
        val n = minOf(CHUNK, bytes.size - offset)
        val written = it.write(bytes, offset.toLong(), offset, n)
        check(written > 0) { "scrittura ferma" }
        offset += written
      }
      it.flush()
    }
  }

  private fun mkdirs(s: AreaShare, dir: String) {
    if (dir.isEmpty()) return
    var prefix = ""
    for (part in dir.split('/')) {
      prefix = if (prefix.isEmpty()) part else "$prefix/$part"
      try {
        s.mkdir(prefix)
      } catch (e: SmbStatusException) {
        if (e.status != NtStatus.OBJECT_NAME_COLLISION) throw e
      }
    }
  }

  override fun bytes(share: String, path: String): ByteArray? = withShare(share) { s ->
    try {
      s.openRead(path).use { file ->
        val size = file.declaredSize.toInt()
        val out = ByteArray(size)
        var offset = 0
        while (offset < size) {
          val n = file.read(out, offset.toLong(), offset, size - offset)
          if (n <= 0) break
          offset += n
        }
        if (offset == size) out else out.copyOf(offset)
      }
    } catch (e: SmbStatusException) {
      if (e.status in ABSENT || e.status == NtStatus.FILE_IS_A_DIRECTORY) null else throw e
    }
  }

  override fun files(share: String): List<String> = withShare(share) { s -> ArrayList<String>().also { listFiles(s, "", it) } }

  private fun listFiles(s: AreaShare, dir: String, out: MutableList<String>) {
    for (entry in entriesOf(s, dir)) {
      val path = if (dir.isEmpty()) entry.name else "$dir/${entry.name}"
      if (entry.isDirectory) listFiles(s, path, out) else out += path
    }
  }

  private fun entriesOf(s: AreaShare, dir: String) = try {
    s.openDirectory(dir, DirAccess.LIST).use { directory -> directory.entries().asSequence().toList() }
  } catch (e: SmbStatusException) {
    if (e.status in ABSENT) emptyList() else throw e
  }

  /** Il "PC" cambia il file sul posto: lo toglie e lo riscrive con altri byte (un file nuovo per il NAS, con un'altra identita'). */
  override fun modify(share: String, path: String, bytes: ByteArray) {
    delete(share, path)
    put(share, path, bytes)
  }

  override fun delete(share: String, path: String) = withShare(share) { s ->
    try {
      s.deleteFile(path)
    } catch (e: SmbStatusException) {
      if (e.status !in ABSENT) throw e
    }
  }

  // Un file "aperto dal PC" sul NAS vero lascerebbe un handle in mano a una connessione che il banco puo' tagliare (docs/prova-500.md Parte A §6bis):
  // non lo si fa (`Capabilities.REAL.lockedFiles = false`), e lo spazio libero dell'account non si cambia.
  override fun lock(share: String, path: String) = throw UnsupportedOperationException("nessun file tenuto aperto sul NAS vero")

  override fun unlock(share: String, path: String) = throw UnsupportedOperationException("nessun file tenuto aperto sul NAS vero")

  override fun setFreeBytes(bytes: Long?) = throw UnsupportedOperationException("lo spazio del NAS vero non si cambia")

  // --- La pulizia -------------------------------------------------------------------------------------------------------------------

  /**
   * Toglie tutto cio' che sta nel mondo di questo oracolo (la sua radice `mcg-it/<giro>/r<N>`, o `mcg-it/<giro>` per la pulizia finale), poi la radice se
   * e' vuota. Un file "in uso" (una connessione morta che il NAS non ha ancora chiuso) si riprova: [cleanupRetries] volte, [cleanupPauseMs] fra l'una e
   * l'altra. Restituisce cosa e' rimasto (vuoto se niente).
   */
  fun clean(): CleanupReport {
    var files = 0
    var folders = 0
    val remaining = ArrayList<String>()
    var rootRemoved = true
    for (name in shareNames) {
      val s = try {
        shares.getValue(name)
      } catch (e: NoSuchElementException) {
        continue
      }
      var left: List<String> = emptyList()
      for (attempt in 0..cleanupRetries) {
        val pass = Tally()
        try {
          deleteTree(s, "", pass)
        } catch (e: IOException) {
          pass.left += "errore di rete durante la pulizia (${e.javaClass.simpleName})"
          runCatching { connect() }
        }
        files += pass.files
        folders += pass.folders
        left = pass.left
        if (left.isEmpty()) break
        if (attempt < cleanupRetries && cleanupPauseMs > 0) Thread.sleep(cleanupPauseMs)
      }
      remaining += left.map { "$name: $it" }
      if (left.isEmpty()) {
        try {
          s.removeRoot()
        } catch (e: IOException) {
          rootRemoved = false
          remaining += "$name: radice non tolta (${e.javaClass.simpleName})"
        }
      } else {
        rootRemoved = false
      }
    }
    return CleanupReport(files, folders, remaining, rootRemoved)
  }

  private class Tally {
    var files = 0
    var folders = 0
    val left = ArrayList<String>()
  }

  private fun deleteTree(s: AreaShare, dir: String, tally: Tally) {
    for (entry in entriesOf(s, dir)) {
      val path = if (dir.isEmpty()) entry.name else "$dir/${entry.name}"
      if (entry.isDirectory) {
        deleteTree(s, path, tally)
        try {
          s.removeEmptyDirectory(path)
          tally.folders++
        } catch (e: SmbStatusException) {
          if (e.status !in ABSENT) tally.left += "cartella $path (0x%08X)".format(e.status)
        }
      } else {
        try {
          s.deleteFile(path)
          tally.files++
        } catch (e: SmbStatusException) {
          if (e.status !in ABSENT) tally.left += "file $path (0x%08X)".format(e.status)
        }
      }
    }
  }

  override fun close() {
    try {
      if (cleanOnClose && session != null) onCleaned(clean())
    } catch (e: Exception) {
      onCleaned(CleanupReport(0, 0, listOf("pulizia non riuscita (${e.javaClass.simpleName})"), false))
    } finally {
      runCatching { session?.close() }
      runCatching { link?.close() }
      session = null
      link = null
    }
  }

  private companion object {
    const val CHUNK = 64 * 1024
    val ABSENT = setOf(NtStatus.OBJECT_NAME_NOT_FOUND, NtStatus.OBJECT_PATH_NOT_FOUND, NtStatus.NO_SUCH_FILE)
  }
}

/** I nomi delle due share di prova (gli stessi di `ShareNasTarget`, qui per non dipendere da P4 nei tipi dell'oracolo). */
object ShareNasTargetNames {
  const val PRIVATE = "test"
  const val COMMON = "test-comune"
}
