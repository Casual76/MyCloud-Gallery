package dev.pampa.mycloudgallery.core.nas.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.mssmb2.copy.CopyChunkRequest
import com.hierynomus.mssmb2.copy.CopyChunkResponse
import com.hierynomus.smb.SMBBuffer
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.SMB2GuestSigningRequiredException
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.Directory
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import dev.pampa.mycloudgallery.core.nas.contract.SmbDialect
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.SocketBinding
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.SocketFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * L'adattatore sottile su smbj 0.15.0 (docs/fase2-progetto.md §3.1, riga "Client"; research/smb-vpn.md §1).
 *
 * Configurazione fissa: solo SMB 3.1.1 (smbj non valida la negoziazione dei 3.0.x), firma obbligatoria,
 * cifratura annunciata (la si verifica DOPO, sopra di qui: smbj ripiega in silenzio sulla firma), niente DFS, niente
 * negoziazione multiprotocollo, niente directory lease, `SO_TIMEOUT` 0 (smbj non manda keepalive: i blocchi li
 * gestiscono i timeout per richiesta e il watchdog del gestore).
 *
 * **Un `SMBClient` per connessione**, non uno per processo come nel documento: il pool interno di smbj riusa le
 * connessioni per `host:porta` e potrebbe restituire una connessione legata alla rete di prima (D1). La fabbrica
 * di socket e' quella del percorso consentito ([SocketBinding]): socket legato, connect a tempo, IP letterale.
 *
 * Nient'altro: niente logica, niente nuovi tentativi. Ogni eccezione di smbj diventa una delle eccezioni di
 * `SmbTransport.kt`.
 */
class SmbjTransport(
  private val bufferSize: Int = DEFAULT_BUFFER,
  /** Il tetto di smbj per ogni richiesta (lettura, scrittura, transazione): rete di sicurezza sotto i timeout del gestore. */
  private val libraryTimeout: Duration = 60.seconds,
) : SmbTransport {

  override fun connect(binding: SocketBinding, address: NasAddress, connectTimeout: Duration): SmbConnection {
    val factory = BoundSocketFactory(binding, address.toInetAddress(), connectTimeout)
    val config = SmbConfig.builder()
      .withDialects(SMB2Dialect.SMB_3_1_1)
      .withSigningRequired(true)
      .withEncryptData(true)
      .withDfsEnabled(false)
      .withMultiProtocolNegotiate(false)
      .withDirectoryLeasingEnabled(false)
      .withSoTimeout(0)
      .withBufferSize(bufferSize)
      .withTimeout(libraryTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
      .withSocketFactory(factory)
      .build()
    val client = SMBClient(config)
    try {
      val connection = client.connect(address.ip, address.port)
      return SmbjConnection(client, connection, readNegotiation(connection))
    } catch (t: Throwable) {
      closeQuietly { client.close() }
      throw translateConnect(t)
    }
  }

  private fun readNegotiation(connection: Connection): Negotiation {
    val context = connection.connectionContext
    val dialect = when (connection.negotiatedProtocol.dialect) {
      SMB2Dialect.SMB_3_1_1 -> SmbDialect.SMB_3_1_1
      SMB2Dialect.SMB_3_0_2 -> SmbDialect.SMB_3_0_2
      SMB2Dialect.SMB_3_0 -> SmbDialect.SMB_3_0
      SMB2Dialect.SMB_2_1 -> SmbDialect.SMB_2_1
      SMB2Dialect.SMB_2_0_2 -> SmbDialect.SMB_2_0_2
      else -> SmbDialect.UNKNOWN
    }
    // smbj: getTimeOffsetMillis() = orologio del dispositivo - SystemTime della risposta NEGOTIATE.
    val localMinusNas: Long? = context.timeOffsetMillis
    val wallNow = System.currentTimeMillis()
    return Negotiation(
      dialect = dialect,
      // Con 3.1.1 smbj la dice vera solo se il server ha scelto un cifrario nel contesto di negoziazione.
      encryptionOffered = context.supportsEncryption(),
      cipher = context.cipherId?.name,
      serverGuid = context.serverGuid.toString().lowercase(),
      serverRequiresSigning = context.isServerRequiresSigning,
      nasTimeOffsetMs = localMinusNas?.let { -it },
      nasTimeAtNegotiateMs = localMinusNas?.let { wallNow - it },
    )
  }

  companion object {
    const val DEFAULT_BUFFER: Int = 1024 * 1024
  }
}

/**
 * La fabbrica di socket di smbj, legata al percorso: ignora il nome che smbj le passa e si connette all'IP
 * letterale gia' convertito in byte (nessun DNS), attraverso il [SocketBinding] e con il timeout.
 */
internal class BoundSocketFactory(
  private val binding: SocketBinding,
  private val address: InetAddress,
  private val timeout: Duration,
) : SocketFactory() {
  override fun createSocket(): Socket = throw UnsupportedOperationException("serve un socket gia' connesso")

  override fun createSocket(host: String?, port: Int): Socket = binding.connect(address, port, timeout)

  override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
    binding.connect(address, port, timeout)

  override fun createSocket(host: InetAddress?, port: Int): Socket = binding.connect(address, port, timeout)

  override fun createSocket(host: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
    binding.connect(address, port, timeout)
}

private class SmbjConnection(
  private val client: SMBClient,
  private val connection: Connection,
  override val negotiation: Negotiation,
) : SmbConnection {

  override val isConnected: Boolean get() = connection.isConnected

  override fun authenticate(user: String, password: CharArray): SmbSession {
    val context = AuthenticationContext(user, password, "")
    try {
      val session = connection.authenticate(context)
      try {
        val facts = SessionFacts(
          encrypted = session.shouldEncryptData(),
          guest = session.isGuest,
          anonymous = session.isAnonymous,
          signingRequired = session.isSigningRequired,
        )
        return SmbjSession(session, facts)
      } catch (t: Throwable) {
        closeQuietly { session.close() }
        throw t
      }
    } catch (t: Throwable) {
      throw translate(t)
    } finally {
      // smbj copia la password in AuthenticationContext e la terrebbe per tutta la sessione (sicurezza.md L4):
      // getPassword() restituisce l'array interno, lo si azzera appena l'autenticazione e' finita (DFS e
      // sessioni annidate, che la riuserebbero, sono spenti).
      context.password.fill('\u0000')
    }
  }

  override fun close() {
    closeQuietly { connection.close(true) }
    closeQuietly { client.close() }
  }
}

private class SmbjSession(private val session: Session, override val facts: SessionFacts) : SmbSession {
  override fun connectShare(name: String): SmbShare = io {
    val share = session.connectShare(name)
    share as? DiskShare ?: run {
      closeQuietly { share.close() }
      throw SmbStatusException(NtStatus.BAD_NETWORK_NAME)
    }
    SmbjShare(name, share)
  }

  override fun close() {
    closeQuietly { session.close() }
  }
}

private class SmbjShare(override val name: String, private val share: DiskShare) : SmbShare {

  override fun openRead(path: String): SmbReadFile = io {
    val file = share.openFile(
      path,
      EnumSet.of(AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES),
      EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
      EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE),
      SMB2CreateDisposition.FILE_OPEN,
      EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
    )
    try {
      // Un round trip in piu' rispetto all'EndOfFile della CREATE, che smbj non espone sull'oggetto File.
      val size = file.getFileInformation(FileStandardInformation::class.java).endOfFile
      SmbjReadFile(file, size)
    } catch (t: Throwable) {
      closeQuietly { file.closeSilently() }
      throw t
    }
  }

  override fun createExclusive(path: String): SmbWriteFile = io {
    val file = share.openFile(
      path,
      EnumSet.of(
        AccessMask.FILE_WRITE_DATA,
        AccessMask.FILE_READ_DATA,
        AccessMask.FILE_READ_ATTRIBUTES,
        AccessMask.FILE_WRITE_ATTRIBUTES,
        AccessMask.DELETE,
      ),
      EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
      EnumSet.noneOf(SMB2ShareAccess::class.java),
      SMB2CreateDisposition.FILE_CREATE,
      EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
    )
    SmbjWriteFile(file)
  }

  override fun mkdir(path: String) = io { share.mkdir(path) }

  override fun openDirectory(path: String, access: DirAccess): SmbDirectory = io {
    val mask = when (access) {
      DirAccess.LIST -> EnumSet.of(AccessMask.FILE_LIST_DIRECTORY, AccessMask.FILE_READ_ATTRIBUTES)
      DirAccess.ADD_FILE -> EnumSet.of(AccessMask.FILE_ADD_FILE)
    }
    val directory = share.openDirectory(
      path,
      mask,
      EnumSet.of(FileAttributes.FILE_ATTRIBUTE_DIRECTORY),
      EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE, SMB2ShareAccess.FILE_SHARE_DELETE),
      SMB2CreateDisposition.FILE_OPEN,
      EnumSet.of(SMB2CreateOptions.FILE_DIRECTORY_FILE),
    )
    SmbjDirectory(directory)
  }

  override fun deleteFile(path: String) = io { share.rm(path) }

  override fun space(): SmbSpace = io {
    val info = share.shareInformation
    SmbSpace(callerFreeBytes = info.callerFreeSpace, totalFreeBytes = info.freeSpace)
  }

  override fun close() {
    closeQuietly { share.close() }
  }
}

private class SmbjReadFile(private val file: File, override val declaredSize: Long) : SmbReadFile {
  override fun read(buffer: ByteArray, fileOffset: Long, bufferOffset: Int, length: Int): Int =
    io { file.read(buffer, fileOffset, bufferOffset, length) }

  // Come nello spike (SmbTests.copyAcrossShares), verificato sul NAS: le prime 24 byte della risposta.
  override fun resumeKey(): ByteArray =
    io { file.ioctl(FSCTL_SRV_REQUEST_RESUME_KEY, true, ByteArray(0), 0, 0, RESUME_KEY_RESPONSE).copyOf(RESUME_KEY_BYTES) }

  override fun close() {
    closeQuietly { file.closeSilently() }
  }
}

private class SmbjWriteFile(private val file: File) : SmbWriteFile {
  override fun write(buffer: ByteArray, fileOffset: Long, bufferOffset: Int, length: Int): Int =
    io { file.write(buffer, fileOffset, bufferOffset, length).toInt() }

  override fun flush() = io { file.flush() }

  override fun length(): Long = io { file.getFileInformation(FileStandardInformation::class.java).endOfFile }

  override fun renameNoReplace(newPath: String) = io { file.rename(newPath, false) }

  override fun deleteOnClose() = io { file.deleteOnClose() }

  // smbj espone File.remoteCopyTo solo nella stessa share: tra share la richiesta si costruisce a mano (research §1.7).
  override fun copyChunks(resumeKey: ByteArray, chunks: List<SmbCopyChunk>): Long = io {
    val request = SMBBuffer()
    CopyChunkRequest(resumeKey, chunks.map { CopyChunkRequest.Chunk(it.sourceOffset, it.targetOffset, it.length) }).write(request)
    val bytes = request.compactData
    val out = file.ioctl(CopyChunkRequest.getCtlCode().toInt(), true, bytes, 0, bytes.size, COPY_CHUNK_RESPONSE)
    CopyChunkResponse().apply { read(SMBBuffer(out)) }.totalBytesWritten
  }

  override fun close() {
    closeQuietly { file.closeSilently() }
  }
}

private class SmbjDirectory(private val directory: Directory) : SmbDirectory {
  override fun entries(): Iterator<SmbDirEntry> {
    val raw = io { directory.iterator(FileIdBothDirectoryInformation::class.java) }
    return object : Iterator<SmbDirEntry> {
      private var next: SmbDirEntry? = null

      private fun advance(): SmbDirEntry? {
        while (io { raw.hasNext() }) {
          val info = io { raw.next() }
          val name = info.fileName
          if (name == "." || name == "..") continue
          return SmbDirEntry(
            name = name,
            isDirectory = (info.fileAttributes and FILE_ATTRIBUTE_DIRECTORY) != 0L,
            size = info.endOfFile,
            lastWriteMs = info.lastWriteTime.toEpochMillis(),
          )
        }
        return null
      }

      override fun hasNext(): Boolean {
        if (next == null) next = advance()
        return next != null
      }

      override fun next(): SmbDirEntry {
        val value = next ?: advance() ?: throw NoSuchElementException()
        next = null
        return value
      }
    }
  }

  override fun close() {
    closeQuietly { directory.closeSilently() }
  }

  private companion object {
    const val FILE_ATTRIBUTE_DIRECTORY = 0x10L
  }
}

// --- Traduzione degli errori di smbj ------------------------------------------------------------------------

/** Esegue una chiamata smbj traducendo ogni eccezione in una di `SmbTransport.kt`. */
private inline fun <T> io(block: () -> T): T =
  try {
    block()
  } catch (t: Throwable) {
    throw translate(t)
  }

private inline fun closeQuietly(block: () -> Unit) {
  try {
    block()
  } catch (_: Throwable) {
    // chiusura: nessun errore da riportare
  }
}

private fun Throwable.hasCause(type: Class<out Throwable>): Boolean {
  var current: Throwable? = this
  var depth = 0
  while (current != null && depth < MAX_CAUSE_DEPTH) {
    if (type.isInstance(current)) return true
    current = current.cause
    depth++
  }
  return false
}

private const val MAX_CAUSE_DEPTH = 8
private const val FSCTL_SRV_REQUEST_RESUME_KEY = 0x00140078
private const val RESUME_KEY_RESPONSE = 32
private const val RESUME_KEY_BYTES = 24
private const val COPY_CHUNK_RESPONSE = 12

/** Durante la connessione: rifiuto e timeout TCP restano tali (ServiceDown / Timeout). */
private fun translateConnect(t: Throwable): IOException = when (t) {
  is ConnectException, is SocketTimeoutException, is NoRouteToHostException -> t as IOException
  is SMBApiException -> SmbNegotiationException(t)
  is SMBRuntimeException -> if (t.hasCause(TransportException::class.java) || t.hasCause(SocketException::class.java)) {
    translate(t)
  } else {
    SmbNegotiationException(t)
  }
  else -> translate(t)
}

internal fun translate(t: Throwable): IOException = when {
  t is SmbException -> t
  t is SMB2GuestSigningRequiredException -> SmbGuestRejectedException(t)
  t is SMBApiException -> SmbStatusException(t.statusCode, t)
  t is InterruptedException -> InterruptedIOException("interrotto").apply { initCause(t) }
  t is InterruptedIOException && t !is SocketTimeoutException -> t
  t.hasCause(TimeoutException::class.java) || t is SocketTimeoutException -> SmbTimeoutException(t)
  t is SMBRuntimeException && (t.message?.contains("Not enough credits") == true) -> SmbCreditsExhaustedException(t)
  t.hasCause(InterruptedException::class.java) -> InterruptedIOException("interrotto").apply { initCause(t) }
  // TransportException, SocketException, socket chiuso, share gia' chiusa: la connessione non e' piu' buona.
  t is TransportException || t is IOException || t is SMBRuntimeException -> SmbConnectionLostException(t)
  else -> SmbException("errore SMB imprevisto", t)
}
