package dev.pampa.mycloudgallery.core.nas.testing

import dev.pampa.mycloudgallery.core.nas.breaker.BreakerRecord
import dev.pampa.mycloudgallery.core.nas.breaker.BreakerStore
import dev.pampa.mycloudgallery.core.nas.contract.SmbDialect
import dev.pampa.mycloudgallery.core.nas.session.MonotonicClock
import dev.pampa.mycloudgallery.core.nas.smb.DirAccess
import dev.pampa.mycloudgallery.core.nas.smb.Negotiation
import dev.pampa.mycloudgallery.core.nas.smb.NtStatus
import dev.pampa.mycloudgallery.core.nas.smb.SessionFacts
import dev.pampa.mycloudgallery.core.nas.smb.SmbConnection
import dev.pampa.mycloudgallery.core.nas.smb.SmbConnectionLostException
import dev.pampa.mycloudgallery.core.nas.smb.SmbCopyChunk
import dev.pampa.mycloudgallery.core.nas.smb.SmbCreditsExhaustedException
import dev.pampa.mycloudgallery.core.nas.smb.SmbDirEntry
import dev.pampa.mycloudgallery.core.nas.smb.SmbDirectory
import dev.pampa.mycloudgallery.core.nas.smb.SmbGuestRejectedException
import dev.pampa.mycloudgallery.core.nas.smb.SmbNegotiationException
import dev.pampa.mycloudgallery.core.nas.smb.SmbReadFile
import dev.pampa.mycloudgallery.core.nas.smb.SmbSession
import dev.pampa.mycloudgallery.core.nas.smb.SmbShare
import dev.pampa.mycloudgallery.core.nas.smb.SmbSpace
import dev.pampa.mycloudgallery.core.nas.smb.SmbStatusException
import dev.pampa.mycloudgallery.core.nas.smb.SmbTransport
import dev.pampa.mycloudgallery.core.nas.smb.SmbWriteFile
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.SocketBinding
import java.net.ConnectException
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

// Il simulatore SMB di P1 (docs/fase2-progetto.md §5.1): un NAS in memoria con la semantica di Samba che conta per
// la sessione. Sta in src/main/.../testing (D6) per i test dei moduli a valle; mai nella release.

/** Le operazioni del simulatore, per i ganci dei test ([InMemorySmbBackend.beforeOp]). */
enum class SimOp {
  CONNECT, AUTHENTICATE, TREE_CONNECT, OPEN_READ, READ, CREATE, WRITE, FLUSH, LENGTH, RENAME, DELETE_ON_CLOSE, MKDIR,
  OPEN_DIRECTORY, LIST_ENTRY, DELETE, SPACE, RESUME_KEY, COPY_CHUNK,
}

/** Che cosa fa la TCP verso il simulatore. */
enum class TcpBehaviour { ACCEPT, REFUSE, TIMEOUT, NO_ROUTE }

/** Come il simulatore tratta un login riuscito "da ospite". */
enum class GuestBehaviour {
  NONE,

  /** Come smbj con la firma obbligatoria: `SMB2GuestSigningRequiredException` durante il login. */
  REJECTED_BY_LIBRARY,

  /** La sessione arriva con il flag guest acceso (controllo 7 del §3.1). */
  SESSION_FLAG,
}

/** Che cosa puo' fare un utente su una share. */
enum class SimAccess { NONE, READ, READ_WRITE }

/**
 * Un NAS Samba in memoria dietro `SmbTransport`:
 * - nomi **senza distinzione di maiuscole** (come Samba per i client Windows), con il nome scritto conservato;
 * - `FILE_CREATE` esclusivo, `mkdir` che dice "esiste gia'", `rename` che non sovrascrive mai (`0xC0000035`),
 *   cartella mancante = `0xC000003A`;
 * - permessi per utente e share (`0xC0000022`), share mancante (`0xC00000CC`), quota e disco pieno (`0xC000007F`)
 *   con lo spazio libero per l'utente e per il volume;
 * - GUID, dialetto, cifratura offerta e concessa, guest e anonimo, scarto dell'orologio;
 * - stati dell'account (bloccato, disattivato, scaduto...) come codici del login;
 * - cadute della connessione, sessioni scadute, crediti esauriti, TCP rifiutata o muta, letture bloccate.
 *
 * Non apre socket: del `SocketBinding` registra solo la rete ([connectedNetworks]). Thread-safe.
 */
class InMemorySmbBackend(
  /** Il GUID del server (minuscolo). */
  @Volatile var serverGuid: String = DEFAULT_GUID,
  /** L'ora del NAS (ms dall'epoca) quando risponde. */
  @Volatile var nasTime: () -> Long = { DEFAULT_NAS_TIME },
) : SmbTransport {

  // --- Comportamento del server ------------------------------------------------------------------------------
  @Volatile var tcp: TcpBehaviour = TcpBehaviour.ACCEPT
  @Volatile var dialect: SmbDialect = SmbDialect.SMB_3_1_1
  @Volatile var negotiationFails: Boolean = false
  @Volatile var encryptionOffered: Boolean = true
  @Volatile var cipher: String? = "AES_128_GCM"

  /** Cio' che `Session.shouldEncryptData()` direbbe dopo il login. */
  @Volatile var sessionEncrypted: Boolean = true
  @Volatile var signingRequired: Boolean = true
  @Volatile var guest: GuestBehaviour = GuestBehaviour.NONE
  @Volatile var anonymous: Boolean = false

  /** L'ora del NAS meno quella del dispositivo, come la riporterebbe l'adattatore. */
  @Volatile var timeOffsetMs: Long? = 0L

  /** Spazio libero del volume (tutto il NAS). */
  @Volatile var volumeFreeBytes: Long = 2_420_000_000_000L

  /** Le prossime N operazioni sulle share falliscono con "Not enough credits". */
  val creditFailures = AtomicInteger(0)

  /** Le letture non rispondono finche' la connessione non viene chiusa (blocco silenzioso, E-R14). */
  @Volatile var stallReads: Boolean = false

  /** Al massimo questi byte per chiamata di lettura (per simulare un trasferimento lento). */
  @Volatile var maxBytesPerRead: Int = Int.MAX_VALUE

  /** Gancio prima di ogni operazione (fuori dal lucchetto del simulatore: puo' lanciare o aspettare). */
  @Volatile var beforeOp: ((SimOp, String) -> Unit)? = null

  // --- Contatori ---------------------------------------------------------------------------------------------
  val tcpAttempts = AtomicInteger(0)
  val negotiations = AtomicInteger(0)
  val authenticateCalls = AtomicInteger(0)
  val treeConnects = AtomicInteger(0)
  val readCalls = AtomicInteger(0)
  val bytesServed = AtomicLong(0)
  val connectedNetworks: MutableList<String> = CopyOnWriteArrayList()

  // --- Stato ---------------------------------------------------------------------------------------------------
  private val lock = Any()
  private val users = HashMap<String, SimUser>()
  private val shares = HashMap<String, SimShareState>()
  private val connections = CopyOnWriteArrayList<SimConnection>()
  private val resumeKeys = java.util.concurrent.ConcurrentHashMap<List<Byte>, Pair<Any, Node>>()

  private class SimUser(val password: String, val loginStatus: Long?)

  private class Node(var displayPath: String, val isDirectory: Boolean, var bytes: ByteArray, var lastWriteMs: Long) {
    var declaredSize: Long? = null
  }

  private class SimShareState(val name: String, val access: Map<String, SimAccess>, val quotaBytes: Long?) {
    val nodes = LinkedHashMap<String, Node>()

    fun usedBytes(): Long = nodes.values.sumOf { it.bytes.size.toLong() }
  }

  /** Un utente del NAS. [loginStatus] non null = il login risponde sempre con quel codice (bloccato, scaduto...). */
  fun addUser(name: String, password: String, loginStatus: Long? = null) {
    synchronized(lock) { users[name.lowercase(Locale.ROOT)] = SimUser(password, loginStatus) }
  }

  fun setLoginStatus(name: String, loginStatus: Long?) {
    synchronized(lock) {
      val user = checkNotNull(users[name.lowercase(Locale.ROOT)])
      users[name.lowercase(Locale.ROOT)] = SimUser(user.password, loginStatus)
    }
  }

  fun changePassword(name: String, password: String) {
    synchronized(lock) {
      val user = checkNotNull(users[name.lowercase(Locale.ROOT)])
      users[name.lowercase(Locale.ROOT)] = SimUser(password, user.loginStatus)
    }
  }

  /** Una share con i permessi per utente e, se serve, una quota (byte). */
  fun addShare(name: String, access: Map<String, SimAccess>, quotaBytes: Long? = null) {
    synchronized(lock) {
      shares[name.lowercase(Locale.ROOT)] = SimShareState(name, access.mapKeys { it.key.lowercase(Locale.ROOT) }, quotaBytes)
    }
  }

  // --- Accesso diretto ai file (come un PC sul NAS) -----------------------------------------------------------

  /** Scrive un file (crea le cartelle), con l'ora di modifica [lastWriteMs]. */
  fun put(share: String, path: String, bytes: ByteArray, lastWriteMs: Long = nasTime()) {
    synchronized(lock) {
      val state = shareState(share)
      val parts = split(path)
      for (depth in 1 until parts.size) {
        val dir = parts.subList(0, depth)
        state.nodes.getOrPut(key(dir)) { Node(dir.joinToString("/"), true, ByteArray(0), lastWriteMs) }
      }
      state.nodes[key(parts)] = Node(parts.joinToString("/"), false, bytes.copyOf(), lastWriteMs)
    }
  }

  /** La dimensione che la CREATE dichiarera' per il file (un server che mente). */
  fun declareSize(share: String, path: String, size: Long) {
    synchronized(lock) { checkNotNull(shareState(share).nodes[key(split(path))]).declaredSize = size }
  }

  fun bytes(share: String, path: String): ByteArray? =
    synchronized(lock) { shareState(share).nodes[key(split(path))]?.takeIf { !it.isDirectory }?.bytes?.copyOf() }

  fun exists(share: String, path: String): Boolean = synchronized(lock) { shareState(share).nodes.containsKey(key(split(path))) }

  /** I nomi (come scritti) dei figli di [dir]. */
  fun children(share: String, dir: String): List<String> = synchronized(lock) {
    val parent = split(dir)
    shareState(share).nodes.values
      .map { split(it.displayPath) }
      .filter { it.size == parent.size + 1 && key(it.subList(0, parent.size)) == key(parent) }
      .map { it.last() }
  }

  fun fileCount(share: String): Int = synchronized(lock) { shareState(share).nodes.values.count { !it.isDirectory } }

  // --- Guasti ------------------------------------------------------------------------------------------------

  /** Il NAS chiude tutte le connessioni (riavvio di Samba, Wi-Fi caduto): ogni chiamata in corso fallisce. */
  fun dropAllConnections() {
    connections.forEach { it.serverDrop() }
  }

  /** Le sessioni aperte scadono: la prossima operazione risponde `USER_SESSION_DELETED`. */
  fun expireSessions() {
    connections.forEach { it.expireSessions() }
  }

  val openConnections: Int get() = connections.count { it.isConnected }

  /**
   * Un binding "verso questo NAS" per chi fa la TCP da se' (la fase TCP del test di connessione): rispetta [tcp],
   * conta i tentativi in [tcpAttempts] e restituisce un socket mai connesso (nessuna rete vera).
   */
  fun binding(networkId: String): SocketBinding = object : SocketBinding {
    override val networkId: String = networkId

    override fun connect(addr: InetAddress, port: Int, timeout: Duration): Socket {
      tcpAttempts.incrementAndGet()
      connectedNetworks += networkId
      when (tcp) {
        TcpBehaviour.ACCEPT -> return Socket()
        TcpBehaviour.REFUSE -> throw ConnectException("rifiutata")
        TcpBehaviour.TIMEOUT -> throw SocketTimeoutException("connect")
        TcpBehaviour.NO_ROUTE -> throw NoRouteToHostException("nessuna rotta")
      }
    }
  }

  // --- SmbTransport --------------------------------------------------------------------------------------------

  override fun connect(binding: SocketBinding, address: NasAddress, connectTimeout: Duration): SmbConnection {
    tcpAttempts.incrementAndGet()
    connectedNetworks += binding.networkId
    beforeOp?.invoke(SimOp.CONNECT, "")
    when (tcp) {
      TcpBehaviour.ACCEPT -> Unit
      TcpBehaviour.REFUSE -> throw ConnectException("rifiutata")
      TcpBehaviour.TIMEOUT -> throw SocketTimeoutException("connect")
      TcpBehaviour.NO_ROUTE -> throw NoRouteToHostException("nessuna rotta")
    }
    negotiations.incrementAndGet()
    if (negotiationFails) throw SmbNegotiationException()
    val now = nasTime()
    val negotiation = Negotiation(
      dialect = dialect,
      encryptionOffered = encryptionOffered && dialect == SmbDialect.SMB_3_1_1,
      cipher = if (encryptionOffered) cipher else null,
      serverGuid = serverGuid.lowercase(Locale.ROOT),
      serverRequiresSigning = false,
      nasTimeOffsetMs = timeOffsetMs,
      nasTimeAtNegotiateMs = now,
    )
    return SimConnection(negotiation).also { connections += it }
  }

  private inner class SimConnection(override val negotiation: Negotiation) : SmbConnection {
    private val closedLatch = CountDownLatch(1)

    @Volatile private var dropped = false

    @Volatile private var closed = false
    private val sessions = CopyOnWriteArrayList<SimSession>()

    override val isConnected: Boolean get() = !dropped && !closed

    fun serverDrop() {
      dropped = true
      closedLatch.countDown()
    }

    fun expireSessions() = sessions.forEach { it.expired = true }

    fun ensureAlive() {
      if (!isConnected) throw SmbConnectionLostException()
    }

    /** Aspetta che la connessione venga chiusa (letture bloccate). */
    fun awaitClosed() {
      while (isConnected) closedLatch.await(BLOCK_POLL_MS, TimeUnit.MILLISECONDS)
    }

    override fun authenticate(user: String, password: CharArray): SmbSession {
      ensureAlive()
      authenticateCalls.incrementAndGet()
      beforeOp?.invoke(SimOp.AUTHENTICATE, "")
      ensureAlive()
      val account = synchronized(lock) { users[user.lowercase(Locale.ROOT)] }
      when {
        account == null -> throw SmbStatusException(NtStatus.LOGON_FAILURE)
        account.loginStatus != null -> throw SmbStatusException(account.loginStatus)
        !account.password.toCharArray().contentEquals(password) -> throw SmbStatusException(NtStatus.LOGON_FAILURE)
      }
      if (guest == GuestBehaviour.REJECTED_BY_LIBRARY) throw SmbGuestRejectedException()
      val facts = SessionFacts(
        encrypted = sessionEncrypted && negotiation.encryptionOffered,
        guest = guest == GuestBehaviour.SESSION_FLAG,
        anonymous = anonymous,
        signingRequired = signingRequired,
      )
      return SimSession(this, user.lowercase(Locale.ROOT), facts).also { sessions += it }
    }

    override fun close() {
      closed = true
      closedLatch.countDown()
    }
  }

  private inner class SimSession(val connection: SimConnection, val user: String, override val facts: SessionFacts) : SmbSession {
    @Volatile var expired = false

    fun ensureUsable() {
      connection.ensureAlive()
      if (expired) throw SmbStatusException(NtStatus.USER_SESSION_DELETED)
    }

    override fun connectShare(name: String): SmbShare {
      ensureUsable()
      treeConnects.incrementAndGet()
      beforeOp?.invoke(SimOp.TREE_CONNECT, name)
      ensureUsable()
      val access = synchronized(lock) {
        val state = shares[name.lowercase(Locale.ROOT)] ?: throw SmbStatusException(NtStatus.BAD_NETWORK_NAME)
        state.access[user] ?: SimAccess.NONE
      }
      if (access == SimAccess.NONE) throw SmbStatusException(NtStatus.ACCESS_DENIED)
      return SimShare(this, name, access)
    }

    override fun close() = Unit
  }

  private inner class SimShare(val session: SimSession, override val name: String, val access: SimAccess) : SmbShare {

    private fun enter(op: SimOp, path: String) {
      session.ensureUsable()
      beforeOp?.invoke(op, path)
      session.ensureUsable()
      if (creditFailures.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) throw SmbCreditsExhaustedException()
    }

    private fun requireWrite() {
      if (access != SimAccess.READ_WRITE) throw SmbStatusException(NtStatus.ACCESS_DENIED)
    }

    private fun state() = shareState(name)

    /** Il nodo di [parts], con gli errori di Samba per cartella o nome mancanti. */
    private fun lookup(state: SimShareState, parts: List<String>): Node {
      if (parts.isEmpty()) return ROOT
      if (parts.size > 1 && state.nodes[key(parts.dropLast(1))]?.isDirectory != true) {
        throw SmbStatusException(NtStatus.OBJECT_PATH_NOT_FOUND)
      }
      return state.nodes[key(parts)] ?: throw SmbStatusException(NtStatus.OBJECT_NAME_NOT_FOUND)
    }

    private fun requireParent(state: SimShareState, parts: List<String>) {
      if (parts.size > 1 && state.nodes[key(parts.dropLast(1))]?.isDirectory != true) {
        throw SmbStatusException(NtStatus.OBJECT_PATH_NOT_FOUND)
      }
    }

    override fun openRead(path: String): SmbReadFile {
      enter(SimOp.OPEN_READ, path)
      val node = synchronized(lock) {
        val found = lookup(state(), split(path))
        if (found.isDirectory) throw SmbStatusException(NtStatus.FILE_IS_A_DIRECTORY)
        found
      }
      return SimReadFile(this, node)
    }

    override fun createExclusive(path: String): SmbWriteFile {
      enter(SimOp.CREATE, path)
      requireWrite()
      val node = synchronized(lock) {
        val state = state()
        val parts = split(path)
        requireParent(state, parts)
        if (state.nodes.containsKey(key(parts))) throw SmbStatusException(NtStatus.OBJECT_NAME_COLLISION)
        Node(parts.joinToString("/"), false, ByteArray(0), nasTime()).also { state.nodes[key(parts)] = it }
      }
      return SimWriteFile(this, node)
    }

    override fun mkdir(path: String) {
      enter(SimOp.MKDIR, path)
      requireWrite()
      synchronized(lock) {
        val state = state()
        val parts = split(path)
        requireParent(state, parts)
        if (state.nodes.containsKey(key(parts))) throw SmbStatusException(NtStatus.OBJECT_NAME_COLLISION)
        state.nodes[key(parts)] = Node(parts.joinToString("/"), true, ByteArray(0), nasTime())
      }
    }

    override fun openDirectory(path: String, access: DirAccess): SmbDirectory {
      enter(SimOp.OPEN_DIRECTORY, path)
      if (access == DirAccess.ADD_FILE) requireWrite()
      val entries = synchronized(lock) {
        val state = state()
        val parts = split(path)
        val node = lookup(state, parts)
        if (!node.isDirectory) throw SmbStatusException(NtStatus.NOT_A_DIRECTORY)
        state.nodes.values
          .filter { child ->
            val childParts = split(child.displayPath)
            childParts.size == parts.size + 1 && key(childParts.subList(0, parts.size)) == key(parts)
          }
          .map { SmbDirEntry(split(it.displayPath).last(), it.isDirectory, it.bytes.size.toLong(), it.lastWriteMs) }
      }
      return SimDirectory(this, entries)
    }

    override fun deleteFile(path: String) {
      enter(SimOp.DELETE, path)
      requireWrite()
      synchronized(lock) {
        val state = state()
        val parts = split(path)
        val node = lookup(state, parts)
        if (node.isDirectory) throw SmbStatusException(NtStatus.FILE_IS_A_DIRECTORY)
        state.nodes.remove(key(parts))
      }
    }

    override fun space(): SmbSpace {
      enter(SimOp.SPACE, "")
      return synchronized(lock) {
        val state = state()
        val quotaLeft = state.quotaBytes?.let { (it - state.usedBytes()).coerceAtLeast(0) } ?: volumeFreeBytes
        SmbSpace(callerFreeBytes = minOf(quotaLeft, volumeFreeBytes), totalFreeBytes = volumeFreeBytes)
      }
    }

    override fun close() = Unit

    fun readInto(node: Node, buffer: ByteArray, fileOffset: Long, bufferOffset: Int, length: Int): Int {
      enter(SimOp.READ, node.displayPath)
      readCalls.incrementAndGet()
      if (stallReads) {
        session.connection.awaitClosed()
        throw SmbConnectionLostException()
      }
      val count = synchronized(lock) {
        val bytes = node.bytes
        if (fileOffset >= bytes.size) return -1
        val count = minOf(length.toLong(), bytes.size - fileOffset, maxBytesPerRead.toLong()).toInt()
        System.arraycopy(bytes, fileOffset.toInt(), buffer, bufferOffset, count)
        count
      }
      bytesServed.addAndGet(count.toLong())
      return count
    }

    fun writeInto(node: Node, buffer: ByteArray, fileOffset: Long, bufferOffset: Int, length: Int): Int {
      enter(SimOp.WRITE, node.displayPath)
      synchronized(lock) { writeLocked(node, buffer, fileOffset, bufferOffset, length) }
      return length
    }

    private fun writeLocked(node: Node, buffer: ByteArray, fileOffset: Long, bufferOffset: Int, length: Int) {
      val state = state()
      val end = fileOffset + length
      val growth = (end - node.bytes.size).coerceAtLeast(0)
      val quotaFull = state.quotaBytes != null && state.usedBytes() + growth > state.quotaBytes
      if (quotaFull || growth > volumeFreeBytes) throw SmbStatusException(NtStatus.DISK_FULL)
      if (end > node.bytes.size) node.bytes = node.bytes.copyOf(end.toInt())
      System.arraycopy(buffer, bufferOffset, node.bytes, fileOffset.toInt(), length)
      node.lastWriteMs = nasTime()
    }

    fun resumeKeyOf(node: Node): ByteArray {
      enter(SimOp.RESUME_KEY, node.displayPath)
      val key = ByteArray(RESUME_KEY_BYTES).also { java.security.SecureRandom().nextBytes(it) }
      resumeKeys[key.toList()] = session to node
      return key
    }

    /** Copia lato server: la chiave vale nella stessa sessione, anche da un'altra share (come Samba). */
    fun copyChunksInto(node: Node, resumeKey: ByteArray, chunks: List<SmbCopyChunk>): Long {
      enter(SimOp.COPY_CHUNK, node.displayPath)
      val (owner, source) = resumeKeys[resumeKey.toList()] ?: throw SmbStatusException(NtStatus.OBJECT_NAME_NOT_FOUND)
      if (owner !== session) throw SmbStatusException(NtStatus.ACCESS_DENIED)
      var total = 0L
      synchronized(lock) {
        for (chunk in chunks) {
          val available = (source.bytes.size - chunk.sourceOffset).coerceAtLeast(0)
          val length = minOf(chunk.length, available).toInt()
          if (length <= 0) break
          writeLocked(node, source.bytes, chunk.targetOffset, chunk.sourceOffset.toInt(), length)
          total += length
        }
      }
      return total
    }

    fun rename(node: Node, newPath: String) {
      enter(SimOp.RENAME, newPath)
      synchronized(lock) {
        val state = state()
        val parts = split(newPath)
        requireParent(state, parts)
        val target = state.nodes[key(parts)]
        if (target != null && target !== node) throw SmbStatusException(NtStatus.OBJECT_NAME_COLLISION)
        val oldKey = state.nodes.entries.firstOrNull { it.value === node }?.key ?: throw SmbStatusException(NtStatus.OBJECT_NAME_NOT_FOUND)
        state.nodes.remove(oldKey)
        node.displayPath = parts.joinToString("/")
        state.nodes[key(parts)] = node
      }
    }

    fun deleteNode(node: Node) {
      synchronized(lock) {
        val state = state()
        state.nodes.entries.firstOrNull { it.value === node }?.let { state.nodes.remove(it.key) }
      }
    }

    fun checkAlive() = session.ensureUsable()
  }

  private inner class SimReadFile(private val share: SimShare, private val node: Node) : SmbReadFile {
    override val declaredSize: Long = synchronized(lock) { node.declaredSize ?: node.bytes.size.toLong() }

    override fun read(buffer: ByteArray, fileOffset: Long, bufferOffset: Int, length: Int): Int =
      share.readInto(node, buffer, fileOffset, bufferOffset, length)

    override fun resumeKey(): ByteArray = share.resumeKeyOf(node)

    override fun close() = Unit
  }

  private inner class SimWriteFile(private val share: SimShare, private val node: Node) : SmbWriteFile {
    @Volatile private var deleteOnClose = false

    override fun write(buffer: ByteArray, fileOffset: Long, bufferOffset: Int, length: Int): Int =
      share.writeInto(node, buffer, fileOffset, bufferOffset, length)

    override fun flush() {
      share.checkAlive()
      beforeOp?.invoke(SimOp.FLUSH, node.displayPath)
      share.checkAlive()
    }

    override fun length(): Long {
      share.checkAlive()
      beforeOp?.invoke(SimOp.LENGTH, node.displayPath)
      share.checkAlive()
      return synchronized(lock) { node.bytes.size.toLong() }
    }

    override fun renameNoReplace(newPath: String) = share.rename(node, newPath)

    override fun copyChunks(resumeKey: ByteArray, chunks: List<SmbCopyChunk>): Long = share.copyChunksInto(node, resumeKey, chunks)

    override fun deleteOnClose() {
      share.checkAlive()
      beforeOp?.invoke(SimOp.DELETE_ON_CLOSE, node.displayPath)
      share.checkAlive()
      deleteOnClose = true
    }

    override fun close() {
      // Samba esegue il "cancella alla chiusura" anche quando l'handle si chiude per la caduta della sessione.
      if (deleteOnClose) share.deleteNode(node)
    }
  }

  private inner class SimDirectory(private val share: SimShare, private val entries: List<SmbDirEntry>) : SmbDirectory {
    override fun entries(): Iterator<SmbDirEntry> {
      val inner = entries.iterator()
      return object : Iterator<SmbDirEntry> {
        override fun hasNext(): Boolean = inner.hasNext()

        override fun next(): SmbDirEntry {
          share.checkAlive()
          beforeOp?.invoke(SimOp.LIST_ENTRY, "")
          return inner.next()
        }
      }
    }

    override fun close() = Unit
  }

  private fun shareState(name: String): SimShareState =
    shares[name.lowercase(Locale.ROOT)] ?: throw SmbStatusException(NtStatus.BAD_NETWORK_NAME)

  private companion object {
    const val DEFAULT_GUID = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"

    /** 2026-10-12T00:00:00Z. */
    const val DEFAULT_NAS_TIME = 1_791_763_200_000L
    const val BLOCK_POLL_MS = 50L
    const val RESUME_KEY_BYTES = 24
    val ROOT = Node("", true, ByteArray(0), 0L)

    fun split(path: String): List<String> = path.split('\\', '/').filter { it.isNotEmpty() }

    fun key(parts: List<String>): String = parts.joinToString("/") { it.lowercase(Locale.ROOT) }
  }
}

/** L'interruttore persistente in memoria: il "file" sopravvive a nuove istanze dell'interruttore (riavvio finto). */
class InMemoryBreakerStore : BreakerStore {
  private val records = MutableStateFlow<Map<String, BreakerRecord>>(emptyMap())

  /** Quante scritture (per verificare che l'apertura sia stata scritta). */
  val writes = AtomicInteger(0)

  override suspend fun read(key: String): BreakerRecord = records.value[key] ?: BreakerRecord()

  override suspend fun update(key: String, transform: (BreakerRecord) -> BreakerRecord): BreakerRecord {
    var result: BreakerRecord? = null
    synchronized(this) {
      val next = transform(records.value[key] ?: BreakerRecord())
      records.value = records.value + (key to next)
      result = next
    }
    writes.incrementAndGet()
    return checkNotNull(result)
  }

  override fun observe(key: String): Flow<BreakerRecord> = records.map { it[key] ?: BreakerRecord() }.distinctUntilChanged()
}

/** Un orologio monotono comandato dal test. */
class ManualMonotonicClock(start: Long = 0L) : MonotonicClock {
  private val now = AtomicLong(start)

  override fun nowMillis(): Long = now.get()

  fun advance(millis: Long) {
    now.addAndGet(millis)
  }
}
