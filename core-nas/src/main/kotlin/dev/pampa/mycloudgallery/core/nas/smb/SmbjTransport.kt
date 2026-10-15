package dev.pampa.mycloudgallery.core.nas.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msdtyp.FileTime
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileAllInformation
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMB2PacketData
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMB3CompressedPacketData
import com.hierynomus.mssmb2.SMB3EncryptedPacketData
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.mssmb2.copy.CopyChunkRequest
import com.hierynomus.mssmb2.copy.CopyChunkResponse
import com.hierynomus.protocol.transport.PacketHandlers
import com.hierynomus.protocol.transport.PacketReceiver
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.protocol.transport.TransportLayer
import com.hierynomus.smb.SMBBuffer
import com.hierynomus.smb.SMBPacket
import com.hierynomus.smb.SMBPacketData
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.SMB2GuestSigningRequiredException
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.Directory
import com.hierynomus.smbj.share.DiskEntry
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import com.hierynomus.smbj.transport.TransportLayerFactory
import com.hierynomus.smbj.transport.tcp.direct.DirectTcpTransportFactory
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
import java.security.MessageDigest
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
 * **Due controlli che smbj non fa** (domanda aperta 1 della revisione 2, P1 della Fase 4), con [ResponseGuard]:
 * la firma della risposta finale di `SESSION_SETUP` e il rifiuto di ogni risposta in chiaro su una sessione cifrata.
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
    val guard = ResponseGuard()
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
      .withTransportLayerFactory(GuardedTransportFactory(guard))
      .build()
    val client = SMBClient(config)
    try {
      val connection = client.connect(address.ip, address.port)
      return SmbjConnection(client, connection, readNegotiation(connection), config, guard)
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
  private val config: SmbConfig,
  private val guard: ResponseGuard,
) : SmbConnection {

  override val isConnected: Boolean get() = connection.isConnected && guard.violation == null

  override fun authenticate(user: String, password: CharArray): SmbSession {
    val context = AuthenticationContext(user, password, "")
    try {
      val session = connection.authenticate(context)
      try {
        // Domanda aperta 1 della revisione 2: smbj non verifica la firma della risposta finale. La si verifica qui,
        // prima di mandare qualunque richiesta sulla sessione (un impostore senza la password non sa firmarla).
        if (!FinalResponseCheck.signed(guard.finalSessionSetup(), session, config)) {
          throw SmbInsecureSessionException(SessionDefect.UNSIGNED_FINAL_RESPONSE)
        }
        val facts = SessionFacts(
          encrypted = session.shouldEncryptData(),
          guest = session.isGuest,
          anonymous = session.isAnonymous,
          signingRequired = session.isSigningRequired,
        )
        // Da qui ogni risposta deve arrivare cifrata: smbj accetterebbe anche il chiaro (vedi ResponseGuard).
        if (facts.encrypted) guard.arm()
        return SmbjSession(session, facts, guard)
      } catch (t: Throwable) {
        if (t is SmbInsecureSessionException) {
          // Nessun LOGOFF verso un server che non ha provato chi e': si chiude il socket e basta.
          closeQuietly { connection.close(true) }
        } else {
          closeQuietly { session.close() }
        }
        throw t
      }
    } catch (t: Throwable) {
      throw guard.translate(t)
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

private class SmbjSession(
  private val session: Session,
  override val facts: SessionFacts,
  private val guard: ResponseGuard,
) : SmbSession {
  override fun connectShare(name: String): SmbShare = guard.io {
    val share = session.connectShare(name)
    share as? DiskShare ?: run {
      closeQuietly { share.close() }
      throw SmbStatusException(NtStatus.BAD_NETWORK_NAME)
    }
    SmbjShare(name, share, guard)
  }

  override fun close() {
    closeQuietly { session.close() }
  }
}

private class SmbjShare(override val name: String, private val share: DiskShare, private val guard: ResponseGuard) : SmbShare {

  // Le condivisioni di ogni apertura vengono da SmbOpenMode (le stesse che applica il simulatore). La lettura condivide anche la
  // cancellazione (prova F16 sul NAS vero): un nostro handle di lettura ancora aperto non impedisce la rinomina o il cestino del file.
  override fun openRead(path: String): SmbReadFile = guard.io {
    val file = share.openFile(
      path,
      EnumSet.of(AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES),
      EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
      SmbOpenMode.READ.shareAccess(),
      SMB2CreateDisposition.FILE_OPEN,
      EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
    )
    try {
      // Un round trip in piu' rispetto all'EndOfFile della CREATE, che smbj non espone sull'oggetto File: tutto lo
      // stato (dimensione, data, fileId, attributi) dallo stesso handle con una sola QUERY_INFO.
      val all = file.getFileInformation(FileAllInformation::class.java)
      SmbjReadFile(file, all.standardInformation.endOfFile, all.toInfo(), guard)
    } catch (t: Throwable) {
      closeQuietly { file.closeSilently() }
      throw t
    }
  }

  override fun stat(path: String): SmbFileInfo = guard.io { share.getFileInformation(path).toInfo() }

  override fun removeEmptyDirectory(path: String) = guard.io { share.rmdir(path, false) }

  override fun createExclusive(path: String): SmbWriteFile = guard.io {
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
      SmbOpenMode.CREATE_EXCLUSIVE.shareAccess(),
      SMB2CreateDisposition.FILE_CREATE,
      EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
    )
    SmbjWriteFile(file, guard)
  }

  override fun mkdir(path: String) = guard.io { share.mkdir(path) }

  override fun openDirectory(path: String, access: DirAccess): SmbDirectory = guard.io {
    val mask = when (access) {
      DirAccess.LIST -> EnumSet.of(AccessMask.FILE_LIST_DIRECTORY, AccessMask.FILE_READ_ATTRIBUTES)
      DirAccess.ADD_FILE -> EnumSet.of(AccessMask.FILE_ADD_FILE)
    }
    val directory = share.openDirectory(
      path,
      mask,
      EnumSet.of(FileAttributes.FILE_ATTRIBUTE_DIRECTORY),
      SmbOpenMode.directory(access).shareAccess(),
      SMB2CreateDisposition.FILE_OPEN,
      EnumSet.of(SMB2CreateOptions.FILE_DIRECTORY_FILE),
    )
    SmbjDirectory(directory, guard)
  }

  override fun deleteFile(path: String) = guard.io { share.rm(path) }

  override fun space(): SmbSpace = guard.io {
    val info = share.shareInformation
    SmbSpace(callerFreeBytes = info.callerFreeSpace, totalFreeBytes = info.freeSpace, totalBytes = info.totalSpace)
  }

  // Fase 4B (ops/): file o cartella esistenti, aperti con DELETE per rinominarli o cancellarli sullo stesso handle. Nessuna
  // opzione FILE_(NON_)DIRECTORY_FILE: smbj restituisce File o Directory secondo cio' che c'e'. Lo stato si legge subito sullo
  // stesso handle (una QUERY_INFO), cosi' il controllo del timbro e la modifica non sono su due aperture diverse.
  // R3 (revisione 4B): con FILE_OPEN_REPARSE_POINT un punto di rianalisi (collegamento, giunzione) che il server riconosce si apre
  // per quello che e' e non si segue: una rinomina o una cancellazione toccano il collegamento, mai la sua destinazione, e
  // l'attributo REPARSE_POINT arriva sullo stesso handle (le primitive rifiutano di cancellarlo).
  override fun openForChange(path: String, exclusive: Boolean): SmbChangeHandle = guard.io {
    val entry = share.open(
      path,
      EnumSet.of(AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES, AccessMask.SYNCHRONIZE),
      EnumSet.noneOf(FileAttributes::class.java),
      SmbOpenMode.change(exclusive).shareAccess(),
      SMB2CreateDisposition.FILE_OPEN,
      EnumSet.of(SMB2CreateOptions.FILE_OPEN_REPARSE_POINT),
    )
    try {
      SmbjChangeHandle(entry, entry.getFileInformation(FileAllInformation::class.java).toInfo(), guard)
    } catch (t: Throwable) {
      closeQuietly { entry.closeSilently() }
      throw t
    }
  }

  override fun close() {
    closeQuietly { share.close() }
  }
}

private class SmbjReadFile(
  private val file: File,
  override val declaredSize: Long,
  override val info: SmbFileInfo?,
  private val guard: ResponseGuard,
) : SmbReadFile {
  override fun read(buffer: ByteArray, fileOffset: Long, bufferOffset: Int, length: Int): Int =
    guard.io { file.read(buffer, fileOffset, bufferOffset, length) }

  // Come nello spike (SmbTests.copyAcrossShares), verificato sul NAS: le prime 24 byte della risposta.
  override fun resumeKey(): ByteArray =
    guard.io { file.ioctl(FSCTL_SRV_REQUEST_RESUME_KEY, true, ByteArray(0), 0, 0, RESUME_KEY_RESPONSE).copyOf(RESUME_KEY_BYTES) }

  override fun close() {
    closeQuietly { file.closeSilently() }
  }
}

private class SmbjWriteFile(private val file: File, private val guard: ResponseGuard) : SmbWriteFile {
  override fun write(buffer: ByteArray, fileOffset: Long, bufferOffset: Int, length: Int): Int =
    guard.io { file.write(buffer, fileOffset, bufferOffset, length).toInt() }

  override fun flush() = guard.io { file.flush() }

  override fun length(): Long = guard.io { file.getFileInformation(FileStandardInformation::class.java).endOfFile }

  override fun renameNoReplace(newPath: String) = guard.io { file.rename(newPath, false) }

  override fun deleteOnClose() = guard.io { file.deleteOnClose() }

  // smbj espone File.remoteCopyTo solo nella stessa share: tra share la richiesta si costruisce a mano (research §1.7).
  override fun copyChunks(resumeKey: ByteArray, chunks: List<SmbCopyChunk>): Long = guard.io {
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

private class SmbjChangeHandle(
  private val entry: DiskEntry,
  override val info: SmbFileInfo,
  private val guard: ResponseGuard,
) : SmbChangeHandle {
  override fun renameNoReplace(newPath: String) = guard.io { entry.rename(newPath, false) }

  override fun deleteOnClose() = guard.io { entry.deleteOnClose() }

  override fun close() {
    closeQuietly { entry.close() }
  }
}

private class SmbjDirectory(private val directory: Directory, private val guard: ResponseGuard) : SmbDirectory {
  // R3: il genitore SUL DISCO (la voce `..`): un elenco a parte, che si ferma appena la trova (di solito la seconda voce).
  override fun parentFileId(): Long? {
    val raw = guard.io { directory.iterator(FileIdBothDirectoryInformation::class.java) }
    while (guard.io { raw.hasNext() }) {
      val info = guard.io { raw.next() }
      if (info.fileName == "..") return info.fileId.takeIf { it != 0L }
    }
    return null
  }

  override fun entries(): Iterator<SmbDirEntry> {
    val raw = guard.io { directory.iterator(FileIdBothDirectoryInformation::class.java) }
    return object : Iterator<SmbDirEntry> {
      private var next: SmbDirEntry? = null

      private fun advance(): SmbDirEntry? {
        while (guard.io { raw.hasNext() }) {
          val info = guard.io { raw.next() }
          val name = info.fileName
          if (name == "." || name == "..") continue
          return SmbDirEntry(
            name = name,
            isDirectory = (info.fileAttributes and FILE_ATTRIBUTE_DIRECTORY) != 0L,
            size = info.endOfFile,
            lastWriteMs = info.lastWriteTime.toEpochMillis(),
            changeMs = info.changeTime.millisOrNull(),
            // Samba ricava il FileId dall'inode: 0 = non dato.
            fileId = info.fileId.takeIf { it != 0L },
            attributes = info.fileAttributes.toInt(),
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

/** Lo stato di `FileAllInformation` nella forma del trasporto. */
private fun FileAllInformation.toInfo(): SmbFileInfo {
  val basic = basicInformation
  val attributes = basic.fileAttributes.toInt()
  return SmbFileInfo(
    // FileNameInformation: il percorso dalla radice della share, con la barra rovesciata iniziale.
    name = nameInformation?.substringAfterLast('\\')?.takeIf { it.isNotEmpty() },
    isDirectory = standardInformation.isDirectory || (attributes and SmbDirEntry.ATTRIBUTE_DIRECTORY) != 0,
    size = standardInformation.endOfFile,
    lastWriteMs = basic.lastWriteTime.toEpochMillis(),
    changeMs = basic.changeTime.millisOrNull(),
    fileId = internalInformation.indexNumber.takeIf { it != 0L },
    attributes = attributes,
  )
}

private fun FileTime?.millisOrNull(): Long? = this?.takeIf { it.windowsTimeStamp != 0L }?.toEpochMillis()

/** Le condivisioni di [SmbOpenMode] per smbj. */
private fun SmbOpenMode.shareAccess(): EnumSet<SMB2ShareAccess> = EnumSet.noneOf(SMB2ShareAccess::class.java).apply {
  if (shareRead) add(SMB2ShareAccess.FILE_SHARE_READ)
  if (shareWrite) add(SMB2ShareAccess.FILE_SHARE_WRITE)
  if (shareDelete) add(SMB2ShareAccess.FILE_SHARE_DELETE)
}

// --- La guardia sulle risposte (domanda aperta 1 della revisione 2) ------------------------------------------

/**
 * Sorveglia **ogni pacchetto in arrivo** su una connessione, prima che smbj lo smisti (P1 della Fase 4; domanda aperta
 * 1 di `docs/review/2-fase2.md`). Letto nel bytecode di smbj 0.15.0 (`SMBSessionBuilder`,
 * `SMB2SignatureVerificationPacketHandler`, `SMB3DecryptingPacketHandler`):
 * - la risposta finale di `SESSION_SETUP` non viene mai verificata: un impostore che risponde `STATUS_SUCCESS` senza
 *   firma "completa" la sessione. Qui se ne tiene una copia, verificata dopo il login da [FinalResponseCheck];
 * - un pacchetto in chiaro e non firmato su una sessione cifrata passa se il server ha messo il flag `ENCRYPT_DATA`
 *   (smbj smette di pretendere la firma) o se dichiara la sessione 0 (nessuna verifica). Qui, dopo [arm], ogni
 *   pacchetto che non sia cifrato chiude la connessione: le richieste in corso falliscono e nessun byte arriva su.
 */
internal class ResponseGuard {
  @Volatile
  private var armed = false

  @Volatile
  private var lastSessionSetup: ByteArray? = null

  /** Perche' la connessione e' stata chiusa dalla guardia, se e' successo. */
  @Volatile
  var violation: SessionDefect? = null
    private set

  /** La copia dell'ultima risposta di `SESSION_SETUP` riuscita (null = mai vista). */
  fun finalSessionSetup(): ByteArray? = lastSessionSetup

  /** Da qui ogni pacchetto deve essere cifrato. */
  fun arm() {
    armed = true
  }

  /** Vero = si consegna a smbj; falso = violazione (la connessione si chiude). */
  fun accept(packet: SMBPacketData<*>): Boolean {
    if (armed) {
      if (packet is SMB3EncryptedPacketData) return true
      violation = SessionDefect.PLAINTEXT_RESPONSE
      return false
    }
    // R3 (revisione 4A): prima del login nessuna risposta legittima e' cifrata o compressa (non ci sono ancora chiavi, e la
    // compressione non si negozia). smbj non regge un messaggio cosi' prima della NEGOTIATE (`PacketEncryptor.canDecrypt`
    // senza dialetto: `NullPointerException` nel thread di lettura, cioe' la chiusura dell'app): si chiude e basta.
    if (packet is SMB3EncryptedPacketData || packet is SMB3CompressedPacketData) return false
    if (packet is SMB2PacketData) {
      val header = packet.header
      if (header.message == SMB2MessageCommandCode.SMB2_SESSION_SETUP && header.statusCode == STATUS_SUCCESS) {
        lastSessionSetup = packet.dataBuffer.array().copyOf()
      }
    }
    return true
  }

  /** Come la traduzione normale, ma un errore dopo una violazione e' la violazione (non una caduta da riprovare). */
  inline fun <T> io(block: () -> T): T =
    try {
      block()
    } catch (t: Throwable) {
      throw translate(t)
    }

  fun translate(t: Throwable): IOException {
    if (t is SmbInsecureSessionException) return t
    violation?.let { return SmbInsecureSessionException(it, t) }
    return translateSmbj(t)
  }

  private companion object {
    const val STATUS_SUCCESS = 0L
  }
}

/**
 * Il ricevitore di smbj avvolto dalla guardia: una violazione chiude la connessione invece di consegnare.
 *
 * R3 (revisione 4A): il thread "Packet Reader" di smbj cattura solo `TransportException`; qualunque altra eccezione nata
 * consegnando un pacchetto (un tag GCM falso iniettato dopo il login: `SMBRuntimeException`; un pacchetto che smbj non
 * regge) sarebbe un'eccezione non catturata, e su Android chiude il processo. Qui diventa un errore della connessione:
 * la connessione si chiude e le richieste in corso falliscono.
 */
internal class GuardedReceiver(
  private val delegate: PacketReceiver<SMBPacketData<*>>,
  private val guard: ResponseGuard,
) : PacketReceiver<SMBPacketData<*>> {
  override fun handle(packet: SMBPacketData<*>) {
    if (!guard.accept(packet)) {
      // Fallisce ogni richiesta in corso e chiude il socket (Connection.handleError).
      closeWith(TransportException("risposta non ammessa dalla guardia"))
      return
    }
    try {
      delegate.handle(packet)
    } catch (e: VirtualMachineError) {
      throw e
    } catch (t: Throwable) {
      closeWith(if (t is TransportException) t else TransportException(t))
    }
  }

  override fun handleError(t: Throwable) {
    try {
      delegate.handleError(t)
    } catch (e: VirtualMachineError) {
      throw e
    } catch (_: Throwable) {
      // La connessione si sta gia' chiudendo: niente deve uscire dal thread di lettura.
    }
  }

  private fun closeWith(error: TransportException) = handleError(error)
}

/** Il trasporto TCP diretto di smbj, con il ricevitore avvolto da [ResponseGuard]. */
private class GuardedTransportFactory(private val guard: ResponseGuard) : TransportLayerFactory<SMBPacketData<*>, SMBPacket<*, *>> {
  private val direct = DirectTcpTransportFactory<SMBPacketData<*>, SMBPacket<*, *>>()

  override fun createTransportLayer(
    handlers: PacketHandlers<SMBPacketData<*>, SMBPacket<*, *>>,
    config: SmbConfig,
  ): TransportLayer<SMBPacket<*, *>> =
    direct.createTransportLayer(PacketHandlers(handlers.serializer, GuardedReceiver(handlers.receiver, guard), handlers.packetFactory), config)
}

/**
 * La firma della risposta finale di `SESSION_SETUP` (MS-SMB2 3.2.5.3.1: con 3.1.1 e' obbligatoria e il client la deve
 * verificare): AES-CMAC con la chiave di firma della sessione sui byte del messaggio, con il campo della firma a zero.
 * Stesso calcolo di `PacketSignatory.verify` di smbj, che pero' per questa risposta non viene mai chiamato.
 */
internal object FinalResponseCheck {
  private const val FLAGS_OFFSET = 16
  private const val NEXT_COMMAND_OFFSET = 20
  private const val SESSION_ID_OFFSET = 40
  private const val SIGNATURE_OFFSET = 48
  private const val SIGNATURE_BYTES = 16
  private const val HEADER_BYTES = 64
  private const val FLAG_SIGNED = 0x8

  fun signed(raw: ByteArray?, session: Session, config: SmbConfig): Boolean {
    if (raw == null || raw.size < HEADER_BYTES) return false
    if (le32(raw, FLAGS_OFFSET) and FLAG_SIGNED == 0) return false
    if (le64(raw, SESSION_ID_OFFSET) != session.sessionId) return false
    val next = le32(raw, NEXT_COMMAND_OFFSET)
    val end = if (next in HEADER_BYTES..raw.size) next else raw.size
    val key = session.sessionContext.signingKey ?: return false
    return try {
      val mac = config.securityProvider.getMac(key.algorithm)
      mac.init(key.encoded)
      mac.update(raw, 0, SIGNATURE_OFFSET)
      mac.update(ByteArray(SIGNATURE_BYTES))
      mac.update(raw, HEADER_BYTES, end - HEADER_BYTES)
      val computed = mac.doFinal().copyOf(SIGNATURE_BYTES)
      MessageDigest.isEqual(computed, raw.copyOfRange(SIGNATURE_OFFSET, SIGNATURE_OFFSET + SIGNATURE_BYTES))
    } catch (_: Exception) {
      false
    }
  }

  private fun le32(b: ByteArray, at: Int): Int =
    (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or ((b[at + 2].toInt() and 0xff) shl 16) or
      ((b[at + 3].toInt() and 0xff) shl 24)

  private fun le64(b: ByteArray, at: Int): Long =
    (le32(b, at).toLong() and 0xffffffffL) or (le32(b, at + 4).toLong() shl 32)
}

// --- Traduzione degli errori di smbj ------------------------------------------------------------------------

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

/** Usata da [ResponseGuard]. */
private fun translateSmbj(t: Throwable): IOException = translate(t)
