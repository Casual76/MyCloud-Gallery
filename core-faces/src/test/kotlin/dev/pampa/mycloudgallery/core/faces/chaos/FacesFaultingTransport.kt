package dev.pampa.mycloudgallery.core.faces.chaos

import dev.pampa.mycloudgallery.core.nas.smb.DirAccess
import dev.pampa.mycloudgallery.core.nas.smb.Negotiation
import dev.pampa.mycloudgallery.core.nas.smb.NtStatus
import dev.pampa.mycloudgallery.core.nas.smb.SessionFacts
import dev.pampa.mycloudgallery.core.nas.smb.SmbChangeHandle
import dev.pampa.mycloudgallery.core.nas.smb.SmbConnection
import dev.pampa.mycloudgallery.core.nas.smb.SmbConnectionLostException
import dev.pampa.mycloudgallery.core.nas.smb.SmbCopyChunk
import dev.pampa.mycloudgallery.core.nas.smb.SmbDirEntry
import dev.pampa.mycloudgallery.core.nas.smb.SmbDirectory
import dev.pampa.mycloudgallery.core.nas.smb.SmbFileInfo
import dev.pampa.mycloudgallery.core.nas.smb.SmbOpenMode
import dev.pampa.mycloudgallery.core.nas.smb.SmbReadFile
import dev.pampa.mycloudgallery.core.nas.smb.SmbSession
import dev.pampa.mycloudgallery.core.nas.smb.SmbShare
import dev.pampa.mycloudgallery.core.nas.smb.SmbSpace
import dev.pampa.mycloudgallery.core.nas.smb.SmbStatusException
import dev.pampa.mycloudgallery.core.nas.smb.SmbTimeoutException
import dev.pampa.mycloudgallery.core.nas.smb.SmbTransport
import dev.pampa.mycloudgallery.core.nas.smb.SmbWriteFile
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.SocketBinding
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration

// I COLPI SUL NAS SMB: un trasporto che avvolge un altro (il simulatore, o smbj dietro il recinto del NAS vero) e conta le chiamate che UN dispositivo fa alla
// sessione, una per una, per farci cadere sopra i colpi della superficie [Surface.NAS]: connessione caduta, sessione scaduta, socket fermo, spazio finito,
// morte del processo, PRIMA o DOPO che la chiamata sia arrivata al NAS (risposta persa). Lo stesso meccanismo sul simulatore (600 giri) e sul NAS vero.
//
// Il login non si conta e non si colpisce. Una connessione tagliata dal colpo si chiude DAVVERO: come il sistema fa col processo ucciso o il Wi-Fi spento, e
// come vuole il NAS vero (docs/prova-500.md Parte A §6bis: niente handle del vecchio processo rimasti aperti). La morte del processo e' uguale, e in piu'
// l'iniettore segna il dispositivo morto: ogni chiamata dei vecchi oggetti si ferma (un processo morto non scrive piu' sul NAS).

internal class FacesFaultingTransport(
  private val inner: SmbTransport,
  private val inj: ChaosInjector,
  private val device: Int,
  private val online: () -> Boolean = { true },
) : SmbTransport {

  private val live = CopyOnWriteArrayList<SmbConnection>()

  /** Quante chiamate sono arrivate al trasporto sotto (per i rapporti: solo un numero). */
  @Volatile
  var calls: Long = 0
    private set

  internal fun isDead(): Boolean = inj.isDead(device) || !online()

  /** La morte del processo (o una connessione persa): chiude tutti i socket del dispositivo. */
  fun cutAll() {
    val all = live.toList()
    live.clear()
    all.forEach { runCatching { it.close() } }
  }

  override fun connect(binding: SocketBinding, address: NasAddress, connectTimeout: Duration): SmbConnection {
    if (isDead()) throw SmbConnectionLostException()
    val connection = inner.connect(binding, address, connectTimeout)
    live += connection
    return FaultingConnection(connection, this)
  }

  /**
   * Una chiamata del dispositivo al NAS: la conta, applica il colpo PRIMA e DOPO se c'e'. [allocates]: la chiamata occupa spazio sul disco (crea un file o
   * una cartella, scrive, copia): solo li' lo spazio puo' finire, mai cancellando o rinominando.
   */
  internal fun <T> call(connection: SmbConnection, allocates: Boolean, name: String, block: () -> T): T {
    if (isDead()) {
      cutAll()
      throw SmbConnectionLostException()
    }
    val n = inj.enter(device, Surface.NAS)
    calls++
    inj.due(device, Surface.NAS, n, Moment.BEFORE, allocates)?.let { apply(it, connection, name) }
    val value = block()
    inj.due(device, Surface.NAS, n, Moment.AFTER, allocates)?.let { apply(it, connection, name) }
    return value
  }

  private fun apply(blow: Blow, connection: SmbConnection, name: String): Nothing {
    when (blow.kind) {
      FaultKind.KILL -> {
        inj.kill(blow, name)
        cutAll()
        throw SmbConnectionLostException()
      }
      FaultKind.EXPIRE_SESSION -> {
        inj.markFired(blow, name)
        runCatching { connection.close() }
        throw SmbStatusException(NtStatus.USER_SESSION_DELETED)
      }
      FaultKind.STALL -> {
        inj.markFired(blow, name)
        inj.onStall()
        runCatching { connection.close() }
        throw SmbTimeoutException()
      }
      FaultKind.NO_SPACE -> {
        inj.markFired(blow, name)
        throw SmbStatusException(NtStatus.DISK_FULL)
      }
      else -> {
        inj.markFired(blow, name)
        runCatching { connection.close() }
        throw SmbConnectionLostException()
      }
    }
  }
}

private class FaultingConnection(private val inner: SmbConnection, private val owner: FacesFaultingTransport) : SmbConnection {
  override val negotiation: Negotiation get() = inner.negotiation

  override val isConnected: Boolean get() = inner.isConnected

  override fun authenticate(user: String, password: CharArray): SmbSession {
    if (owner.isDead()) throw SmbConnectionLostException()
    return FaultingSession(inner.authenticate(user, password), inner, owner)
  }

  override fun close() = inner.close()
}

private class FaultingSession(private val inner: SmbSession, private val connection: SmbConnection, private val owner: FacesFaultingTransport) : SmbSession {
  override val facts: SessionFacts get() = inner.facts

  override fun connectShare(name: String): SmbShare = owner.call(connection, allocates = false, "connectShare") {
    FaultingShare(inner.connectShare(name), connection, owner)
  }

  override fun close() = inner.close()
}

private class FaultingShare(private val inner: SmbShare, private val connection: SmbConnection, private val owner: FacesFaultingTransport) : SmbShare {
  override val name: String get() = inner.name

  override fun openRead(path: String): SmbReadFile = owner.call(connection, false, "openRead") { FaultingRead(inner.openRead(path), connection, owner) }

  override fun createExclusive(path: String, shareMode: SmbOpenMode): SmbWriteFile =
    owner.call(connection, true, "createExclusive") { FaultingWrite(inner.createExclusive(path, shareMode), connection, owner) }

  override fun mkdir(path: String) = owner.call(connection, true, "mkdir") { inner.mkdir(path) }

  override fun openDirectory(path: String, access: DirAccess): SmbDirectory = owner.call(connection, false, "openDirectory") { FaultingDirectory(inner.openDirectory(path, access)) }

  override fun stat(path: String): SmbFileInfo = owner.call(connection, false, "stat") { inner.stat(path) }

  override fun removeEmptyDirectory(path: String) = owner.call(connection, false, "removeEmptyDirectory") { inner.removeEmptyDirectory(path) }

  override fun deleteFile(path: String) = owner.call(connection, false, "deleteFile") { inner.deleteFile(path) }

  override fun space(): SmbSpace = owner.call(connection, false, "space") { inner.space() }

  override fun openForChange(path: String, exclusive: Boolean): SmbChangeHandle =
    owner.call(connection, false, "openForChange") { FaultingChange(inner.openForChange(path, exclusive), connection, owner) }

  override fun openExistingForWrite(path: String, shareMode: SmbOpenMode): SmbWriteFile =
    owner.call(connection, false, "openExistingForWrite") { FaultingWrite(inner.openExistingForWrite(path, shareMode), connection, owner) }

  override fun close() = inner.close()
}

private class FaultingRead(private val inner: SmbReadFile, private val connection: SmbConnection, private val owner: FacesFaultingTransport) : SmbReadFile {
  override val declaredSize: Long get() = inner.declaredSize

  override val info: SmbFileInfo? get() = inner.info

  override fun read(buffer: ByteArray, fileOffset: Long, bufferOffset: Int, length: Int): Int =
    owner.call(connection, false, "read") { inner.read(buffer, fileOffset, bufferOffset, length) }

  override fun resumeKey(): ByteArray = owner.call(connection, false, "resumeKey") { inner.resumeKey() }

  override fun close() = inner.close()
}

private class FaultingWrite(private val inner: SmbWriteFile, private val connection: SmbConnection, private val owner: FacesFaultingTransport) : SmbWriteFile {
  override fun write(buffer: ByteArray, fileOffset: Long, bufferOffset: Int, length: Int): Int =
    owner.call(connection, true, "write") { inner.write(buffer, fileOffset, bufferOffset, length) }

  override fun flush() = owner.call(connection, true, "flush") { inner.flush() }

  override fun length(): Long = owner.call(connection, false, "length") { inner.length() }

  override fun renameNoReplace(newPath: String) = owner.call(connection, false, "renameNoReplace") { inner.renameNoReplace(newPath) }

  override fun deleteOnClose() = owner.call(connection, false, "deleteOnClose") { inner.deleteOnClose() }

  override fun copyChunks(resumeKey: ByteArray, chunks: List<SmbCopyChunk>): Long = owner.call(connection, true, "copyChunks") { inner.copyChunks(resumeKey, chunks) }

  override val info: SmbFileInfo? get() = inner.info

  override fun setLength(length: Long) = owner.call(connection, true, "setLength") { inner.setLength(length) }

  override fun query(): SmbFileInfo = owner.call(connection, false, "query") { inner.query() }

  override fun close() = inner.close()
}

private class FaultingChange(private val inner: SmbChangeHandle, private val connection: SmbConnection, private val owner: FacesFaultingTransport) : SmbChangeHandle {
  override val info: SmbFileInfo get() = inner.info

  override fun renameNoReplace(newPath: String) = owner.call(connection, false, "renameNoReplace") { inner.renameNoReplace(newPath) }

  override fun deleteOnClose() = owner.call(connection, false, "deleteOnClose") { inner.deleteOnClose() }

  override fun close() = inner.close()
}

private class FaultingDirectory(private val inner: SmbDirectory) : SmbDirectory {
  override fun entries(): Iterator<SmbDirEntry> = inner.entries()

  override fun parentFileId(): Long? = inner.parentFileId()

  override fun close() = inner.close()
}
