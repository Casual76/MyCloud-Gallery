package dev.pampa.mycloudgallery.core.faces.chaos

import dev.pampa.mycloudgallery.core.nas.smb.DirAccess
import dev.pampa.mycloudgallery.core.nas.smb.Negotiation
import dev.pampa.mycloudgallery.core.nas.smb.NtStatus
import dev.pampa.mycloudgallery.core.nas.smb.SessionFacts
import dev.pampa.mycloudgallery.core.nas.smb.SmbChangeHandle
import dev.pampa.mycloudgallery.core.nas.smb.SmbConnection
import dev.pampa.mycloudgallery.core.nas.smb.SmbConnectionLostException
import dev.pampa.mycloudgallery.core.nas.smb.SmbCopyChunk
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
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration

// IL TRASPORTO RECINTATO (docs/prova-500.md Parte F, §F8; come quello della condivisione, Parte E §E4): un trasporto SMB che avvolge smbj (o il simulatore) e fa quattro cose, tutte PRIMA di
// chiamare il vero:
//   1. controlla la share ([FacesAreaGuard.checkShare]): solo `test` e `test-comune`, mai altro (`test2`, `NAS`, `Public`, `alessio`, `pampa`);
//   2. radica OGNI percorso in `mcg-it/<giro>/r<N>`: la cartella dell'app (`.mycloudgallery/`, temporanei e cestino) compresa. Un percorso con `..`,
//      assoluto, con un'unita' (`C:`) o che esce dalla radice in qualunque modo lancia [FacesAreaViolation] e non arriva al NAS;
//   3. non lascia ritentare un login rifiutato: al primo errore di autenticazione ([SmbStatusException] dal login) il [FacesAuthLatch] scatta e nessuna
//      `authenticate` arriva piu' al NAS (il NAS blocca l'account dopo pochi tentativi sbagliati);
//   4. conta le chiamate che arrivano al vero ([FacesDelegateCounter]): l'autotest vuole zero per ogni rifiuto.
// E' il livello piu' basso: il motore vero di core-nasops costruisce le sue primitive di scrittura sopra la sessione che passa di qui (`NasOpsWiring`),
// senza che questo banco nomini mai le primitive. Le sole cartelle fuori dalla radice che si possono creare sono i suoi antenati (`mcg-it`,
// `mcg-it/<giro>`), con [FacesAreaShare.ensureRoot]; la sola cartella che si puo' togliere e' la radice stessa, con [FacesAreaShare.removeRoot], quando e' vuota.

class FacesAreaTransport(
  private val inner: SmbTransport,
  private val guard: FacesAreaGuard,
  /** La radice del mondo di questo trasporto: `mcg-it/<giro>/r<N>` (o `mcg-it/<giro>` per la pulizia finale). */
  val root: SharePath,
  private val latch: FacesAuthLatch,
  private val counter: FacesDelegateCounter = FacesDelegateCounter(),
) : SmbTransport {

  init {
    require(root.components.size >= 2 && root.components[0] == FacesAreaGuard.ROOT && root.components[1] == guard.runId) { "radice fuori dal giro" }
  }

  override fun connect(binding: SocketBinding, address: NasAddress, connectTimeout: Duration): SmbConnection {
    counter.calls.incrementAndGet()
    return FacesAreaConnection(inner.connect(binding, address, connectTimeout), guard, root, latch, counter)
  }
}

private class FacesAreaConnection(
  private val inner: SmbConnection,
  private val guard: FacesAreaGuard,
  private val root: SharePath,
  private val latch: FacesAuthLatch,
  private val counter: FacesDelegateCounter,
) : SmbConnection {
  override val negotiation: Negotiation get() = inner.negotiation

  override val isConnected: Boolean get() = inner.isConnected

  override fun authenticate(user: String, password: CharArray): SmbSession {
    guard.checkAccount(user)
    // Un login gia' rifiutato non si ripete: nessuna chiamata di autenticazione arriva piu' al NAS (come l'interruttore dell'app, ma per tutto il banco).
    if (latch.failed) throw SmbConnectionLostException()
    counter.calls.incrementAndGet()
    return try {
      FacesAreaSession(inner.authenticate(user, password), guard, root, counter)
    } catch (e: SmbStatusException) {
      latch.trip()
      throw e
    }
  }

  override fun close() = inner.close()
}

private class FacesAreaSession(private val inner: SmbSession, private val guard: FacesAreaGuard, private val root: SharePath, private val counter: FacesDelegateCounter) : SmbSession {
  override val facts: SessionFacts get() = inner.facts

  override fun connectShare(name: String): SmbShare {
    guard.checkShare(name)
    counter.calls.incrementAndGet()
    return FacesAreaShare(inner.connectShare(name), root, counter)
  }

  override fun close() = inner.close()
}

/** Una share di prova con ogni percorso radicato. Pubblica perche' l'oracolo ([AreaAdmin]) ne usa [ensureRoot] e [removeRoot]. */
class FacesAreaShare internal constructor(private val inner: SmbShare, private val root: SharePath, private val counter: FacesDelegateCounter) : SmbShare {
  override val name: String get() = inner.name

  private val rootNative: String = root.components.joinToString("\\")

  /** Il percorso della share (con `\` o `/`, relativo) radicato; `""` e' la radice del mondo. Lancia [FacesAreaViolation] se uscirebbe dalla radice. */
  fun at(path: String): String {
    val clean = path.replace('/', '\\')
    val parts = if (clean.isEmpty()) emptyList() else clean.split('\\')
    if (clean.startsWith("\\") || ':' in clean || parts.any { it.isEmpty() || it == ".." || it == "." }) throw FacesAreaViolation("percorso che esce dall'area di prova")
    return if (parts.isEmpty()) rootNative else "$rootNative\\$clean"
  }

  private fun <T> reach(block: () -> T): T {
    counter.calls.incrementAndGet()
    return block()
  }

  override fun openRead(path: String): SmbReadFile = at(path).let { p -> reach { inner.openRead(p) } }

  override fun createExclusive(path: String, shareMode: SmbOpenMode): SmbWriteFile = at(path).let { p -> reach { FacesAreaWrite(inner.createExclusive(p, shareMode), this) } }

  override fun mkdir(path: String) = at(path).let { p -> reach { inner.mkdir(p) } }

  override fun openDirectory(path: String, access: DirAccess): SmbDirectory = at(path).let { p -> reach { inner.openDirectory(p, access) } }

  override fun stat(path: String): SmbFileInfo = at(path).let { p -> reach { inner.stat(p) } }

  override fun removeEmptyDirectory(path: String) = at(path).let { p -> reach { inner.removeEmptyDirectory(p) } }

  override fun deleteFile(path: String) = at(path).let { p -> reach { inner.deleteFile(p) } }

  override fun space(): SmbSpace = reach { inner.space() }

  override fun openForChange(path: String, exclusive: Boolean): SmbChangeHandle =
    at(path).let { p -> reach { FacesAreaChange(inner.openForChange(p, exclusive), this) } }

  override fun openExistingForWrite(path: String, shareMode: SmbOpenMode): SmbWriteFile =
    at(path).let { p -> reach { FacesAreaWrite(inner.openExistingForWrite(p, shareMode), this) } }

  /**
   * Crea la radice del mondo e i suoi antenati (`mcg-it`, `mcg-it/<giro>`, `mcg-it/<giro>/r<N>`): le sole cartelle fuori dal mondo che si toccano, e solo
   * come prefissi della radice. "Esiste gia'" non e' un errore.
   */
  fun ensureRoot() {
    var prefix = ""
    for (component in root.components) {
      prefix = if (prefix.isEmpty()) component else "$prefix\\$component"
      try {
        reach { inner.mkdir(prefix) }
      } catch (e: SmbStatusException) {
        if (e.status != NtStatus.OBJECT_NAME_COLLISION) throw e
      }
    }
  }

  /** Toglie la radice del mondo se e' vuota (a fine prova): la sola cartella fuori dal contenuto che si toglie. `mcg-it` resta. */
  fun removeRoot() {
    try {
      reach { inner.removeEmptyDirectory(rootNative) }
    } catch (e: SmbStatusException) {
      if (e.status != NtStatus.OBJECT_NAME_NOT_FOUND && e.status != NtStatus.OBJECT_PATH_NOT_FOUND) throw e
    }
  }

  override fun close() = inner.close()
}

private class FacesAreaWrite(private val inner: SmbWriteFile, private val share: FacesAreaShare) : SmbWriteFile {
  override fun write(buffer: ByteArray, fileOffset: Long, bufferOffset: Int, length: Int): Int = inner.write(buffer, fileOffset, bufferOffset, length)

  override fun flush() = inner.flush()

  override fun length(): Long = inner.length()

  override fun renameNoReplace(newPath: String) = inner.renameNoReplace(share.at(newPath))

  override fun deleteOnClose() = inner.deleteOnClose()

  override fun copyChunks(resumeKey: ByteArray, chunks: List<SmbCopyChunk>): Long = inner.copyChunks(resumeKey, chunks)

  override val info: SmbFileInfo? get() = inner.info

  override fun setLength(length: Long) = inner.setLength(length)

  override fun query(): SmbFileInfo = inner.query()

  override fun close() = inner.close()
}

private class FacesAreaChange(private val inner: SmbChangeHandle, private val share: FacesAreaShare) : SmbChangeHandle {
  override val info: SmbFileInfo get() = inner.info

  override fun renameNoReplace(newPath: String) = inner.renameNoReplace(share.at(newPath))

  override fun deleteOnClose() = inner.deleteOnClose()

  override fun close() = inner.close()
}
