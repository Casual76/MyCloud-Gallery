package dev.pampa.mycloudgallery.core.faces.chaos

import dev.pampa.mycloudgallery.core.nas.smb.DirAccess
import dev.pampa.mycloudgallery.core.nas.smb.NtStatus
import dev.pampa.mycloudgallery.core.nas.smb.SmbConnection
import dev.pampa.mycloudgallery.core.nas.smb.SmbSession
import dev.pampa.mycloudgallery.core.nas.smb.SmbStatusException
import dev.pampa.mycloudgallery.core.nas.smb.SmbTransport
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.SocketBinding
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

// L'ORACOLO SUL NAS VERO: il "PC" accanto al NAS. Una connessione sua (un login sua, fatto una volta: se non riesce la prova si ferma) attraverso lo stesso
// trasporto recintato del dispositivo ma SENZA colpi: serve a mettere i file di partenza (media e file dell'app che il banco non deve mai toccare), a leggere
// i byte di ogni file e a elencarli (gli invarianti si controllano sul NAS vero, non su quello che il dispositivo dice di aver fatto), a togliere un file come
// farebbe l'utente dal PC e, alla fine, a togliere la cartella del giro. Percorsi relativi al mondo (`mcg-it/<giro>/r<N>`), con `/`. Tutto sincrono.
//
// Sull'area comune (`test-comune`) l'oracolo SOLO legge e elenca: non crea nemmeno la radice del giro, perche' li' non si scrive niente.

/** La connessione del banco: chi, dove, con quale socket. */
class FacesConnection(val user: String, val password: CharArray, val host: String, val guid: String, val binding: SocketBinding) {
  override fun toString(): String = "FacesConnection(nascosta)"
}

/** Cosa ha tolto e cosa e' rimasto la pulizia di un mondo. Solo numeri e, al piu', qualche percorso di prova (mai contenuti). */
class FacesCleanupReport(val files: Int, val folders: Int, val remaining: List<String>, val rootRemoved: Boolean) {
  val clean: Boolean get() = remaining.isEmpty() && rootRemoved

  fun text(): String =
    if (clean) {
      "tolti $files file e $folders cartelle; radice tolta, niente rimasto"
    } else {
      "tolti $files file e $folders cartelle; rimasto: ${remaining.size} voci${if (rootRemoved) "" else ", radice non tolta"} (${remaining.take(8).joinToString()})"
    }
}

class FacesAreaAdmin(
  private val transport: SmbTransport,
  private val connection: FacesConnection,
  private val latch: FacesAuthLatch,
  private val privateName: String = "test",
  private val commonName: String = "test-comune",
  /** Quante volte e per quanto si riprova a togliere un file che il NAS dice "in uso" (handle di una connessione morta che il NAS tiene ancora). */
  private val cleanupRetries: Int = 6,
  private val cleanupPauseMs: Long = 20_000,
  /** Cosa fare quando si chiude: la pulizia del mondo (di norma si). */
  private val cleanOnClose: Boolean = true,
  private val onCleaned: (FacesCleanupReport) -> Unit = {},
) : AutoCloseable {

  private var session: SmbSession? = null
  private var link: SmbConnection? = null
  private var shares: Map<String, FacesAreaShare> = emptyMap()

  /** Il login (una volta) e la share privata con la radice del mondo creata. Un login rifiutato ferma tutto: [FacesAreaAuthStop], nessun nuovo tentativo. */
  fun open(): FacesAreaAdmin {
    connect()
    shares.getValue(privateName).ensureRoot()
    return this
  }

  private fun connect() {
    if (latch.failed) throw FacesAreaAuthStop("login gia' rifiutato dal NAS: nessun nuovo tentativo")
    runCatching { link?.close() }
    val c = transport.connect(connection.binding, NasAddress(connection.host), 15.seconds)
    link = c
    val s = try {
      c.authenticate(connection.user, connection.password.copyOf())
    } catch (e: SmbStatusException) {
      runCatching { c.close() }
      throw FacesAreaAuthStop("login rifiutato dal NAS (codice 0x%08X): fermo, nessun nuovo tentativo".format(e.status))
    }
    session = s
    shares = listOf(privateName, commonName).associateWith { name -> s.connectShare(name) as FacesAreaShare }
  }

  /** Un'operazione sulla share; se la connessione e' caduta (un NAS che chiude le sessioni ferme) si riapre UNA volta e si riprova. */
  private fun <T> withShare(share: String, block: (FacesAreaShare) -> T): T {
    val key = listOf(privateName, commonName).firstOrNull { it.equals(share, ignoreCase = true) } ?: throw FacesAreaViolation("share fuori dall'area di prova")
    return try {
      block(shares.getValue(key))
    } catch (e: IOException) {
      if (e is SmbStatusException && e.status != NtStatus.USER_SESSION_DELETED && e.status != NtStatus.NETWORK_SESSION_EXPIRED) throw e
      connect()
      block(shares.getValue(key))
    }
  }

  /** Mette un file nell'area privata (mai nell'area comune). */
  fun put(path: String, bytes: ByteArray) = withShare(privateName) { s ->
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

  private fun mkdirs(s: FacesAreaShare, dir: String) {
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

  fun bytes(share: String, path: String): ByteArray? = withShare(share) { s ->
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

  fun files(share: String): List<String> = withShare(share) { s -> ArrayList<String>().also { listFiles(s, "", it) } }

  private fun listFiles(s: FacesAreaShare, dir: String, out: MutableList<String>) {
    for (entry in entriesOf(s, dir)) {
      val path = if (dir.isEmpty()) entry.name else "$dir/${entry.name}"
      if (entry.isDirectory) listFiles(s, path, out) else out += path
    }
  }

  private fun entriesOf(s: FacesAreaShare, dir: String) = try {
    s.openDirectory(dir, DirAccess.LIST).use { directory -> directory.entries().asSequence().toList() }
  } catch (e: SmbStatusException) {
    if (e.status in ABSENT) emptyList() else throw e
  }

  /** L'utente toglie un file dal PC. */
  fun delete(path: String) = withShare(privateName) { s ->
    try {
      s.deleteFile(path)
    } catch (e: SmbStatusException) {
      if (e.status !in ABSENT) throw e
    }
  }

  // --- La pulizia -------------------------------------------------------------------------------------------------------------------

  /**
   * Toglie tutto cio' che sta nel mondo di questo oracolo (la sua radice `mcg-it/<giro>/r<N>`, o `mcg-it/<giro>` per la pulizia finale), poi la radice se
   * e' vuota. Un file "in uso" (una connessione morta che il NAS non ha ancora chiuso) si riprova: [cleanupRetries] volte, [cleanupPauseMs] fra l'una e
   * l'altra. Solo nell'area privata: nell'area comune non si e' scritto, e non si toglie niente. Restituisce cosa e' rimasto (vuoto se niente).
   */
  fun clean(): FacesCleanupReport {
    var files = 0
    var folders = 0
    val remaining = ArrayList<String>()
    var rootRemoved = true
    val s = try {
      shares.getValue(privateName)
    } catch (e: NoSuchElementException) {
      return FacesCleanupReport(0, 0, emptyList(), true)
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
    remaining += left.map { "$privateName: $it" }
    if (left.isEmpty()) {
      try {
        s.removeRoot()
      } catch (e: IOException) {
        rootRemoved = false
        remaining += "$privateName: radice non tolta (${e.javaClass.simpleName})"
      }
    } else {
      rootRemoved = false
    }
    // L'area comune: se qualcosa c'e' sotto la radice del giro e' una violazione, non una pulizia (il banco lo dice nell'invariante 2).
    val leftover = runCatching { shares.getValue(commonName).let { c -> ArrayList<String>().also { listFiles(c, "", it) } } }.getOrDefault(emptyList())
    remaining += leftover.map { "$commonName: $it" }
    return FacesCleanupReport(files, folders, remaining, rootRemoved)
  }

  private class Tally {
    var files = 0
    var folders = 0
    val left = ArrayList<String>()
  }

  private fun deleteTree(s: FacesAreaShare, dir: String, tally: Tally) {
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
      onCleaned(FacesCleanupReport(0, 0, listOf("pulizia non riuscita (${e.javaClass.simpleName})"), false))
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
