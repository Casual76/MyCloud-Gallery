package dev.pampa.mycloudgallery.core.faces.chaos

import dev.pampa.mycloudgallery.core.nas.smb.DirAccess
import dev.pampa.mycloudgallery.core.nas.smb.Negotiation
import dev.pampa.mycloudgallery.core.nas.smb.NtStatus
import dev.pampa.mycloudgallery.core.nas.smb.SessionFacts
import dev.pampa.mycloudgallery.core.nas.smb.SmbChangeHandle
import dev.pampa.mycloudgallery.core.nas.smb.SmbConnection
import dev.pampa.mycloudgallery.core.nas.smb.SmbConnectionLostException
import dev.pampa.mycloudgallery.core.nas.smb.SmbDirectory
import dev.pampa.mycloudgallery.core.nas.smb.SmbFileInfo
import dev.pampa.mycloudgallery.core.nas.smb.SmbOpenMode
import dev.pampa.mycloudgallery.core.nas.smb.SmbReadFile
import dev.pampa.mycloudgallery.core.nas.smb.SmbSession
import dev.pampa.mycloudgallery.core.nas.smb.SmbShare
import dev.pampa.mycloudgallery.core.nas.smb.SmbSpace
import dev.pampa.mycloudgallery.core.nas.smb.SmbStatusException
import dev.pampa.mycloudgallery.core.nas.smb.SmbTransport
import dev.pampa.mycloudgallery.core.nas.smb.SmbWriteFile
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.SocketBinding
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// IL LETTORE DELL'AREA DEL TELEFONO (docs/prova-500.md Parte F, §F9): per verificare dal PC i dati dei volti scritti dal telefono (`s25.ps1 -Mode faces`) il
// banco deve leggere le cartelle che l'app usa sulle due share di prova (le copie private del backup, le cartelle degli album, il cestino e i temporanei
// dell'app), non il mondo `mcg-it/<giro>/` del banco. Per questo il lettore e' un altro trasporto recintato, e per costruzione di SOLA LETTURA: ogni
// chiamata che scrive o cancella lancia [FacesAreaViolation] prima di arrivare al NAS; le sole cartelle leggibili sono quelle del manifesto (un elenco chiuso di
// prefissi per share). Stessa guardia delle altre prove: solo `test` e `test-comune`, solo l'account `test`, un login rifiutato non si ripete.

class FacesReadOnlyTransport(
  private val inner: SmbTransport,
  private val guard: FacesAreaGuard,
  /** Per share (minuscola): le cartelle leggibili, relative alla radice della share, con `/`. Le altre non si aprono. */
  private val readable: Map<String, List<String>>,
  private val latch: FacesAuthLatch,
  private val counter: FacesDelegateCounter = FacesDelegateCounter(),
) : SmbTransport {
  override fun connect(binding: SocketBinding, address: NasAddress, connectTimeout: Duration): SmbConnection {
    counter.calls.incrementAndGet()
    return FacesReadOnlyConnection(inner.connect(binding, address, connectTimeout), guard, readable, latch, counter)
  }
}

private class FacesReadOnlyConnection(
  private val inner: SmbConnection,
  private val guard: FacesAreaGuard,
  private val readable: Map<String, List<String>>,
  private val latch: FacesAuthLatch,
  private val counter: FacesDelegateCounter,
) : SmbConnection {
  override val negotiation: Negotiation get() = inner.negotiation

  override val isConnected: Boolean get() = inner.isConnected

  override fun authenticate(user: String, password: CharArray): SmbSession {
    guard.checkAccount(user)
    if (latch.failed) throw SmbConnectionLostException()
    counter.calls.incrementAndGet()
    return try {
      FacesReadOnlySession(inner.authenticate(user, password), guard, readable, counter)
    } catch (e: SmbStatusException) {
      latch.trip()
      throw e
    }
  }

  override fun close() = inner.close()
}

private class FacesReadOnlySession(
  private val inner: SmbSession,
  private val guard: FacesAreaGuard,
  private val readable: Map<String, List<String>>,
  private val counter: FacesDelegateCounter,
) : SmbSession {
  override val facts: SessionFacts get() = inner.facts

  override fun connectShare(name: String): SmbShare {
    guard.checkShare(name)
    counter.calls.incrementAndGet()
    return FacesReadOnlyShare(inner.connectShare(name), readable[name.lowercase(Locale.ROOT)].orEmpty(), counter)
  }

  override fun close() = inner.close()
}

private class FacesReadOnlyShare(private val inner: SmbShare, private val prefixes: List<String>, private val counter: FacesDelegateCounter) : SmbShare {
  override val name: String get() = inner.name

  /** Il percorso (con `\`) se sta dentro una delle cartelle leggibili; altrimenti [FacesAreaViolation]. Mai la radice della share, mai `..`, mai un assoluto. */
  private fun readablePath(path: String): String {
    val clean = path.replace('/', '\\')
    val parts = clean.split('\\')
    if (clean.isEmpty() || clean.startsWith("\\") || ':' in clean || parts.any { it.isEmpty() || it == ".." || it == "." }) throw FacesAreaViolation("percorso non leggibile")
    val lower = clean.lowercase(Locale.ROOT)
    if (prefixes.none { lower == it || lower.startsWith("$it\\") }) throw FacesAreaViolation("percorso fuori dalle cartelle del manifesto")
    return clean
  }

  private fun <T> reach(block: () -> T): T {
    counter.calls.incrementAndGet()
    return block()
  }

  override fun openRead(path: String): SmbReadFile = readablePath(path).let { p -> reach { inner.openRead(p) } }

  override fun openDirectory(path: String, access: DirAccess): SmbDirectory {
    if (access != DirAccess.LIST) throw FacesAreaViolation("sola lettura: non si prova a scrivere")
    return readablePath(path).let { p -> reach { inner.openDirectory(p, access) } }
  }

  override fun stat(path: String): SmbFileInfo = readablePath(path).let { p -> reach { inner.stat(p) } }

  override fun space(): SmbSpace = reach { inner.space() }

  override fun createExclusive(path: String, shareMode: SmbOpenMode): SmbWriteFile = throw FacesAreaViolation("sola lettura")

  override fun mkdir(path: String) = throw FacesAreaViolation("sola lettura")

  override fun removeEmptyDirectory(path: String) = throw FacesAreaViolation("sola lettura")

  override fun deleteFile(path: String) = throw FacesAreaViolation("sola lettura")

  override fun openForChange(path: String, exclusive: Boolean): SmbChangeHandle = throw FacesAreaViolation("sola lettura")

  override fun openExistingForWrite(path: String, shareMode: SmbOpenMode): SmbWriteFile = throw FacesAreaViolation("sola lettura")

  override fun close() = inner.close()
}

/** Una voce di una cartella letta. */
class FacesReadEntry(val name: String, val isDirectory: Boolean)

/** L'area aperta in sola lettura: una connessione, un login (una volta), le due share. */
class FacesReadOnlyArea(
  private val transport: SmbTransport,
  private val connection: FacesConnection,
  private val latch: FacesAuthLatch,
) : AutoCloseable {
  private var link: SmbConnection? = null
  private var session: SmbSession? = null
  private var shares: Map<String, SmbShare> = emptyMap()

  fun open(): FacesReadOnlyArea {
    if (latch.failed) throw FacesAreaAuthStop("login gia' rifiutato dal NAS: nessun nuovo tentativo")
    val c = transport.connect(connection.binding, NasAddress(connection.host), 15.seconds)
    link = c
    val s = try {
      c.authenticate(connection.user, connection.password.copyOf())
    } catch (e: SmbStatusException) {
      runCatching { c.close() }
      throw FacesAreaAuthStop("login rifiutato dal NAS (codice 0x%08X): fermo, nessun nuovo tentativo".format(e.status))
    }
    session = s
    shares = FacesAreaGuard.SHARES.associateWith { s.connectShare(it) }
    return this
  }

  private fun shareOf(share: String): SmbShare = shares[share.lowercase(Locale.ROOT)] ?: throw FacesAreaViolation("share fuori dall'area di prova")

  /** Le voci di [dir] (`/`); una cartella che non c'e' e' vuota. */
  fun entries(share: String, dir: String): List<FacesReadEntry> = try {
    shareOf(share).openDirectory(dir.replace('/', '\\'), DirAccess.LIST).use { d -> d.entries().asSequence().map { FacesReadEntry(it.name, it.isDirectory) }.toList() }
  } catch (e: SmbStatusException) {
    if (e.status in ABSENT) emptyList() else throw e
  }

  /** I byte di [path] (`/`), o null se non c'e'. */
  fun read(share: String, path: String): ByteArray? = try {
    shareOf(share).openRead(path.replace('/', '\\')).use { file ->
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

  override fun close() {
    runCatching { session?.close() }
    runCatching { link?.close() }
    session = null
    link = null
  }

  private companion object {
    val ABSENT = setOf(NtStatus.OBJECT_NAME_NOT_FOUND, NtStatus.OBJECT_PATH_NOT_FOUND, NtStatus.NO_SUCH_FILE)
  }
}
