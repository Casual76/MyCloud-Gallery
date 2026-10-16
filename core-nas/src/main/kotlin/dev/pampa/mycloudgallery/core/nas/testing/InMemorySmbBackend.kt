package dev.pampa.mycloudgallery.core.nas.testing

import dev.pampa.mycloudgallery.core.nas.breaker.BreakerRecord
import dev.pampa.mycloudgallery.core.nas.breaker.BreakerStore
import dev.pampa.mycloudgallery.core.nas.contract.NasAttributes
import dev.pampa.mycloudgallery.core.nas.contract.SmbDialect
import dev.pampa.mycloudgallery.core.nas.session.MonotonicClock
import dev.pampa.mycloudgallery.core.nas.smb.DirAccess
import dev.pampa.mycloudgallery.core.nas.smb.Negotiation
import dev.pampa.mycloudgallery.core.nas.smb.NtStatus
import dev.pampa.mycloudgallery.core.nas.smb.SessionFacts
import dev.pampa.mycloudgallery.core.nas.smb.SmbChangeHandle
import dev.pampa.mycloudgallery.core.nas.smb.SmbConnection
import dev.pampa.mycloudgallery.core.nas.smb.SmbConnectionLostException
import dev.pampa.mycloudgallery.core.nas.smb.SmbCopyChunk
import dev.pampa.mycloudgallery.core.nas.smb.SmbCreditsExhaustedException
import dev.pampa.mycloudgallery.core.nas.smb.SmbDirEntry
import dev.pampa.mycloudgallery.core.nas.smb.SmbDirectory
import dev.pampa.mycloudgallery.core.nas.smb.SmbFileInfo
import dev.pampa.mycloudgallery.core.nas.smb.SmbOpenMode
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
import kotlin.random.Random
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

// Il simulatore SMB (docs/fase2-progetto.md §5.1, esteso dalla Fase 4 P1 secondo docs/fase4-progetto.md §5.2): un NAS
// in memoria con la semantica di Samba che conta per la sessione e per la lettura dei media. Sta in src/main/.../testing
// (D6) per i test dei moduli a valle; mai nella release (tools/check.ps1).

/** Le operazioni del simulatore, per i ganci dei test ([InMemorySmbBackend.beforeOp]) e per il [FaultPlan]. */
enum class SimOp {
  CONNECT, AUTHENTICATE, TREE_CONNECT, OPEN_READ, READ, CREATE, WRITE, FLUSH, LENGTH, RENAME, DELETE_ON_CLOSE, MKDIR,
  OPEN_DIRECTORY, LIST_ENTRY, DELETE, SPACE, RESUME_KEY, COPY_CHUNK, STAT, RMDIR,

  /** Fase 4B: apertura di un file o di una cartella esistenti per rinominarli o cancellarli sullo stesso handle. */
  OPEN_CHANGE,

  /** Fase 5A (D5): riapertura in scrittura, senza crearlo, del proprio temporaneo del backup. */
  OPEN_WRITE,

  /** Fase 5A (D5): `SET_INFO` di fine file sullo stesso handle (accorciamento al punto di ripresa). */
  SET_END_OF_FILE,
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
 * Dalla Fase 4 (§5.2):
 * - **`fileId`** stabile per file, conservato da ogni rinomina (anche di una cartella intera), nuovo per una copia;
 * - **date**: del file all'ultima scrittura; della cartella a ogni creazione, rinomina o cancellazione dentro;
 * - **file bloccati** ([lock]): scrittura, rinomina e cancellazione → `SHARING_VIOLATION` (con `denyRead` anche la
 *   lettura, come un file aperto in esclusiva dal PC);
 * - **contenuti virtuali** ([putContent] con `NasContent.virtual`): un video "da 9,4 GB" senza occupare memoria;
 * - **latenze** per tipo di operazione e **dischi in standby** a **tempo virtuale** ([time], [latencies],
 *   [standbyAfterMs]);
 * - **piano dei guasti** ([faults]): caduta prima o dopo che il server abbia applicato l'operazione, sessione scaduta,
 *   blocco, codici;
 * - ordine degli elenchi casuale ([listOrder]), elenchi troncati ([truncateListsAfter]);
 * - copia lato server con `NOT_SUPPORTED` o `ACCESS_DENIED` programmabili ([copyChunkStatus]);
 * - contenuti ostili: nomi non validi, punti di rianalisi e cicli ([putLink]), cartelle enormi.
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

  /** Dimensione del volume (Spazio NAS, 4B). */
  @Volatile var volumeTotalBytes: Long = 3_840_000_000_000L

  /** Le prossime N operazioni sulle share falliscono con "Not enough credits". */
  val creditFailures = AtomicInteger(0)

  /** Le letture non rispondono finche' la connessione non viene chiusa (blocco silenzioso, E-R14). */
  @Volatile var stallReads: Boolean = false

  /** Al massimo questi byte per chiamata di lettura (per simulare un trasferimento lento). */
  @Volatile var maxBytesPerRead: Int = Int.MAX_VALUE

  /** Gancio prima di ogni operazione (fuori dal lucchetto del simulatore: puo' lanciare o aspettare). */
  @Volatile var beforeOp: ((SimOp, String) -> Unit)? = null

  /** Il piano dei guasti (§5.2): null = nessuno. */
  @Volatile var faults: FaultPlan? = null

  /** Il tempo virtuale che latenze e risvegli fanno avanzare; null = latenze e standby spenti. */
  @Volatile var time: ManualMonotonicClock? = null

  /** Le latenze per tipo di operazione, in tempo virtuale ([time]). */
  @Volatile var latencies: SimLatencies = SimLatencies.NONE

  /** Dopo questa inattivita' ([time]) i dischi dormono e la prossima operazione su di loro tarda [wakeUpMs] (E-R1). */
  @Volatile var standbyAfterMs: Long? = null
  @Volatile var wakeUpMs: Long = DEFAULT_WAKE_UP_MS

  /** Se non null, ogni elenco esce in un ordine casuale (l'ordine di Samba non e' garantito). */
  @Volatile var listOrder: Random? = null

  /** Se non null, ogni elenco si ferma in silenzio dopo queste voci (un elenco troncato dal server). */
  @Volatile var truncateListsAfter: Int? = null

  /** Se non null, la copia lato server risponde con questo codice (`NOT_SUPPORTED`, `ACCESS_DENIED`: E-D12). */
  @Volatile var copyChunkStatus: Long? = null

  /**
   * P16, prova F16 sul NAS vero: gli **handle dei client** contano come su Samba. Ogni apertura (lettura, temporaneo, cartella, handle
   * per cambiare) resta viva sul server con i suoi diritti e le sue condivisioni ([SmbOpenMode], le stesse di `SmbjTransport`) finche'
   * il client non la chiude o finche' la connessione non cade **per il server**; chi apre lo stesso file con un diritto che un handle
   * vivo non condivide riceve `SHARING_VIOLATION`, anche se l'handle e' dello stesso telefono. Una cancellazione con altri handle aperti
   * avviene alla chiusura dell'ultimo (`DELETE_PENDING` nel frattempo). Di default acceso, come il NAS vero.
   */
  @Volatile var shareModes: Boolean = true

  /**
   * Rinominare (o mettere nel cestino) una cartella con un file aperto **sotto**, dalla stessa connessione: Samba risponde
   * `ACCESS_DENIED` (controlla gli handle dello stesso processo `smbd`, cioe' dello stesso client; `strict rename = no`). Gli handle di
   * un'altra connessione (il PC) non contano.
   */
  @Volatile var openBelowDeniesDirRename: Boolean = true

  /**
   * Gancio chiamato quando la chiusura di un handle sta per arrivare al server (fuori dal lucchetto: puo' aspettare). Serve a provare
   * l'ordine fra una chiusura "in volo" e la richiesta che segue.
   */
  @Volatile var beforeClose: (() -> Unit)? = null

  // --- Contatori ---------------------------------------------------------------------------------------------
  val tcpAttempts = AtomicInteger(0)
  val negotiations = AtomicInteger(0)
  val authenticateCalls = AtomicInteger(0)
  val treeConnects = AtomicInteger(0)
  val readCalls = AtomicInteger(0)
  val bytesServed = AtomicLong(0)
  val connectedNetworks: MutableList<String> = CopyOnWriteArrayList()

  /** Tutte le richieste arrivate alle share (per i test "zero richieste"). */
  val shareOps = AtomicInteger(0)

  /** Le voci d'elenco consegnate (per provare che chi elenca non tiene tutto in memoria). */
  val listEntriesServed = AtomicLong(0)

  /** Le letture in corso adesso e il massimo visto (per i flussi paralleli di `readAll`). */
  val readsInFlight = AtomicInteger(0)
  val maxReadsInFlight = AtomicInteger(0)

  /** I risvegli dei dischi simulati. */
  val diskWakeUps = AtomicInteger(0)

  // --- Stato ---------------------------------------------------------------------------------------------------
  private val lock = Any()
  private val users = HashMap<String, SimUser>()
  private val shares = HashMap<String, SimShareState>()
  private val connections = CopyOnWriteArrayList<SimConnection>()
  private val resumeKeys = java.util.concurrent.ConcurrentHashMap<List<Byte>, Pair<Any, Node>>()
  private val locks = HashMap<String, Boolean>()
  private val nextFileId = AtomicLong(FIRST_FILE_ID)

  /** Gli handle vivi sul server, per nodo ([shareModes]). Sotto [lock]. */
  private val handles = HashMap<Node, MutableList<SimHandle>>()

  /** Un handle vivo sul server: di quale sessione (e connessione), con quali diritti e condivisioni. */
  private inner class SimHandle(val node: Node, val state: SimShareState, val session: SimSession, val mode: SmbOpenMode) {
    val connection: SimConnection get() = session.connection

    /** Il "cancella alla chiusura": Samba lo esegue quando l'handle si chiude, anche per la caduta della connessione. */
    @Volatile var deleteOnClose = false
  }

  @Volatile private var lastDiskOpAt: Long? = null
  private val listMicros = AtomicLong(0)

  private class SimUser(val password: String, val loginStatus: Long?)

  private class Node(var displayPath: String, val isDirectory: Boolean, val fileId: Long, var lastWriteMs: Long) {
    var bytes: ByteArray = EMPTY
    var virtual: NasContent? = null
    var declaredSize: Long? = null
    var changeMs: Long = lastWriteMs
    var attributes: Int = if (isDirectory) NasAttributes.DIRECTORY else NasAttributes.ARCHIVE

    /** Un punto di rianalisi (collegamento) verso un'altra cartella: la chiave della destinazione. */
    var linkTarget: String? = null

    /** Cancellato con altri handle ancora aperti: sparisce alla chiusura dell'ultimo; intanto aprirlo da' `DELETE_PENDING`. */
    var deletePending: Boolean = false

    val size: Long get() = virtual?.size ?: bytes.size.toLong()

    fun copyTo(offset: Long, dst: ByteArray, dstOffset: Int, len: Int) {
      val v = virtual
      if (v != null) v.copy(offset, dst, dstOffset, len) else System.arraycopy(bytes, offset.toInt(), dst, dstOffset, len)
    }

    /** I byte veri (un contenuto virtuale si materializza: solo per file piccoli). */
    fun materialize(): ByteArray {
      val v = virtual ?: return bytes
      require(v.size <= MAX_MATERIALIZE) { "Contenuto virtuale troppo grande per una scrittura." }
      return ByteArray(v.size.toInt()).also { v.copy(0, it, 0, it.size) }.also {
        bytes = it
        virtual = null
      }
    }
  }

  private inner class SimShareState(val name: String, val access: Map<String, SimAccess>, val quotaBytes: Long?) {
    val nodes = HashMap<String, Node>()
    val kids = HashMap<String, LinkedHashMap<String, Node>>()
    val root = Node("", true, nextFileId.incrementAndGet(), nasTime())

    fun usedBytes(): Long = nodes.values.sumOf { if (it.virtual == null) it.bytes.size.toLong() else 0L }

    fun add(key: String, node: Node) {
      nodes[key] = node
      kids.getOrPut(parentKey(key)) { LinkedHashMap() }[key] = node
    }

    fun remove(key: String): Node? {
      val node = nodes.remove(key) ?: return null
      kids[parentKey(key)]?.remove(key)
      return node
    }

    fun node(key: String): Node? = if (key.isEmpty()) root else nodes[key]

    fun children(key: String): Collection<Node> = kids[key]?.values.orEmpty()

    /** La data della cartella che contiene [key] cambia (creazione, rinomina, cancellazione dentro, §5.2). */
    fun touchParent(key: String, at: Long) {
      val parent = node(parentKey(key)) ?: return
      parent.lastWriteMs = at
      parent.changeMs = at
    }
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

  /** Scrive un file (crea le cartelle), con l'ora di modifica [lastWriteMs]. Un file gia' presente tiene il `fileId`. */
  fun put(share: String, path: String, bytes: ByteArray, lastWriteMs: Long = nasTime()) {
    putNode(share, path, lastWriteMs, NasAttributes.ARCHIVE) { it.bytes = bytes.copyOf() }
  }

  /** Come [put], con un contenuto anche virtuale e gli attributi. Restituisce il `fileId`. */
  fun putContent(
    share: String,
    path: String,
    content: NasContent,
    lastWriteMs: Long = nasTime(),
    attributes: Int = NasAttributes.ARCHIVE,
  ): Long = putNode(share, path, lastWriteMs, attributes) { it.virtual = content }

  private fun putNode(share: String, path: String, lastWriteMs: Long, attributes: Int, fill: (Node) -> Unit): Long =
    synchronized(lock) {
      val state = shareState(share)
      val parts = split(path)
      ensureDirs(state, parts.dropLast(1), lastWriteMs)
      val k = key(parts)
      val previous = state.nodes[k]
      require(previous == null || !previous.isDirectory) { "Al posto del file c'e' una cartella." }
      val node = Node(parts.joinToString("/"), false, previous?.fileId ?: nextFileId.incrementAndGet(), lastWriteMs)
      node.attributes = attributes
      fill(node)
      state.remove(k)
      state.add(k, node)
      if (previous == null) state.touchParent(k, lastWriteMs)
      node.fileId
    }

  /** Una cartella (e quelle sopra) con i suoi attributi (nascosta, di sistema...). */
  fun putDir(share: String, path: String, attributes: Int = NasAttributes.DIRECTORY, lastWriteMs: Long = nasTime()) {
    synchronized(lock) {
      val state = shareState(share)
      val parts = split(path)
      ensureDirs(state, parts, lastWriteMs)
      state.nodes.getValue(key(parts)).attributes = attributes or NasAttributes.DIRECTORY
    }
  }

  /**
   * Un punto di rianalisi (E-N10): una "cartella" in [path] che porta a [target] (anche un suo antenato: un ciclo).
   * Ha l'attributo `REPARSE_POINT`; aprirla elenca la destinazione, come farebbe un server che segue i collegamenti.
   */
  fun putLink(share: String, path: String, target: String) {
    synchronized(lock) {
      val state = shareState(share)
      val parts = split(path)
      ensureDirs(state, parts.dropLast(1), nasTime())
      val node = Node(parts.joinToString("/"), true, nextFileId.incrementAndGet(), nasTime())
      node.attributes = NasAttributes.DIRECTORY or NasAttributes.REPARSE_POINT
      node.linkTarget = key(split(target))
      state.add(key(parts), node)
    }
  }

  /** Modifica sul posto dal PC (E-N2): contenuto nuovo, stesso `fileId`, data nuova; la cartella non cambia data. */
  fun modify(share: String, path: String, content: NasContent, lastWriteMs: Long = nasTime()) {
    synchronized(lock) {
      val node = checkNotNull(shareState(share).nodes[key(split(path))]) { "File assente." }
      node.virtual = content
      node.bytes = EMPTY
      node.lastWriteMs = lastWriteMs
      node.changeMs = lastWriteMs
    }
  }

  /**
   * Rinomina o sposta dal PC (anche una cartella intera, E-N1): i `fileId` restano, le date dei file no, quelle delle
   * due cartelle genitore si'. Non sostituisce mai: una destinazione occupata e' un errore del test.
   */
  fun rename(share: String, from: String, to: String) {
    synchronized(lock) { moveLocked(shareState(share), key(split(from)), split(to), nasTime(), outside = true) }
  }

  /** Cancellazione dal PC (E-N3): una cartella sparisce con tutto il contenuto. */
  fun delete(share: String, path: String) {
    synchronized(lock) {
      val state = shareState(share)
      val k = key(split(path))
      state.nodes.keys.filter { it == k || it.startsWith("$k/") }.forEach { state.remove(it) }
      state.touchParent(k, nasTime())
    }
  }

  /**
   * Il file e' aperto dal PC (E-N13): scrittura, rinomina e cancellazione rispondono `SHARING_VIOLATION`; con
   * [denyRead] anche la lettura (un'apertura esclusiva, come Office).
   */
  fun lock(share: String, path: String, denyRead: Boolean = true) {
    synchronized(lock) { locks[lockKey(share, path)] = denyRead }
  }

  fun unlock(share: String, path: String) {
    synchronized(lock) { locks.remove(lockKey(share, path)) }
  }

  /** La dimensione che la CREATE dichiarera' per il file (un server che mente). */
  fun declareSize(share: String, path: String, size: Long) {
    synchronized(lock) { checkNotNull(shareState(share).nodes[key(split(path))]).declaredSize = size }
  }

  fun bytes(share: String, path: String): ByteArray? = synchronized(lock) {
    val node = shareState(share).nodes[key(split(path))]?.takeIf { !it.isDirectory } ?: return null
    ByteArray(node.size.toInt()).also { node.copyTo(0, it, 0, it.size) }
  }

  fun exists(share: String, path: String): Boolean = synchronized(lock) { shareState(share).nodes.containsKey(key(split(path))) }

  /** Il `fileId` di un file o di una cartella, o null. */
  fun fileId(share: String, path: String): Long? = synchronized(lock) { shareState(share).node(key(split(path)))?.fileId }

  /** La data di modifica (ora del NAS) di un file o di una cartella, o null. */
  fun lastWriteMs(share: String, path: String): Long? = synchronized(lock) { shareState(share).node(key(split(path)))?.lastWriteMs }

  /** I nomi (come scritti) dei figli di [dir]. */
  fun children(share: String, dir: String): List<String> = synchronized(lock) {
    shareState(share).children(key(split(dir))).map { split(it.displayPath).last() }
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
   * Il telefono perde la rete senza che il NAS se ne accorga (Wi-Fi caduto, cambio di rete): per il client le connessioni sono morte,
   * ma il server tiene le sessioni e **i loro handle** finche' non si accorge che dall'altra parte non c'e' piu' nessuno ([reapAbandoned]).
   */
  fun abandonAllConnections() {
    connections.forEach { it.abandon() }
  }

  /** Il NAS si accorge delle connessioni abbandonate (keepalive) e chiude i loro handle. */
  fun reapAbandoned() {
    synchronized(lock) {
      handles.values.flatten().filter { it.connection.abandoned }.forEach { releaseLocked(it) }
    }
  }

  /** Gli handle dei client ancora aperti sul server (tutte le connessioni). */
  val openHandles: Int get() = synchronized(lock) { handles.values.sumOf { it.size } }

  /** Gli handle ancora aperti sul server su [path] (per i test). */
  fun openHandlesOn(share: String, path: String): Int = synchronized(lock) {
    val node = shareState(share).nodes[key(split(path))] ?: return 0
    handles[node]?.size ?: 0
  }

  // --- Handle dei client (condivisioni di Samba) -------------------------------------------------------------

  /** Un'apertura di [node]: `DELETE_PENDING` se sta per sparire, `SHARING_VIOLATION` se un handle vivo non la lascia passare. Sotto il lucchetto. */
  private fun openLocked(node: Node, state: SimShareState, session: SimSession, mode: SmbOpenMode): SimHandle {
    checkOpenLocked(node, mode)
    return SimHandle(node, state, session, mode).also { handles.getOrPut(node) { ArrayList() } += it }
  }

  /** Il controllo di un'apertura che non resta (cancellazione per nome). Sotto il lucchetto. */
  private fun checkOpenLocked(node: Node, mode: SmbOpenMode) {
    if (node.deletePending) throw SmbStatusException(NtStatus.DELETE_PENDING)
    if (shareModes && handles[node].orEmpty().any { it.mode.conflictsWith(mode) }) throw SmbStatusException(NtStatus.SHARING_VIOLATION)
  }

  /** L'handle si chiude sul server: se il nodo aspettava di sparire e questo era l'ultimo handle, sparisce adesso. Sotto il lucchetto. */
  private fun releaseLocked(handle: SimHandle) {
    val list = handles[handle.node] ?: return
    if (!list.remove(handle)) return
    if (list.isEmpty()) handles.remove(handle.node)
    val node = handle.node
    if (handle.deleteOnClose) {
      // Una cartella riempita nel frattempo resta (Samba ricontrolla alla chiusura).
      val k = handle.state.nodes.entries.firstOrNull { it.value === node }?.key
      if (k != null && !(node.isDirectory && handle.state.children(k).isNotEmpty())) deleteLocked(handle.state, node)
    } else if (list.isEmpty() && node.deletePending) {
      removeNodeLocked(handle.state, node)
    }
  }

  /** Cancella [node] adesso, o alla chiusura dell'ultimo handle se ce ne sono ancora (Samba: cancella alla chiusura). Sotto il lucchetto. */
  private fun deleteLocked(state: SimShareState, node: Node) {
    if (handles[node].isNullOrEmpty()) removeNodeLocked(state, node) else node.deletePending = true
  }

  private fun removeNodeLocked(state: SimShareState, node: Node) {
    val k = state.nodes.entries.firstOrNull { it.value === node }?.key ?: return
    state.remove(k)
    state.touchParent(k, nasTime())
  }

  /** La chiusura di un handle da parte del client: il gancio [beforeClose], poi il server lo dimentica (se la connessione c'e' ancora). */
  private fun closeHandle(handle: SimHandle?) {
    handle ?: return
    beforeClose?.invoke()
    synchronized(lock) {
      // Una connessione abbandonata non porta la chiusura al server: l'handle resta finche' il NAS non se ne accorge.
      if (!handle.connection.abandoned) releaseLocked(handle)
    }
  }

  /** La connessione si e' chiusa o e' caduta per il server: i suoi handle si chiudono. */
  private fun releaseConnection(connection: SimConnection) {
    synchronized(lock) { handles.values.flatten().filter { it.connection === connection }.forEach { releaseLocked(it) } }
  }

  /** La sessione e' scaduta o chiusa: i suoi handle si chiudono. */
  private fun releaseSession(session: SimSession) {
    synchronized(lock) { handles.values.flatten().filter { it.session === session }.forEach { releaseLocked(it) } }
  }

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

  /** Il piano dei guasti per un'operazione: conta, applica la fase [FaultPhase.BEFORE], restituisce l'indice. */
  private fun faultBefore(op: SimOp, connection: SimConnection, session: SimSession?): Pair<Int, Int>? {
    val plan = faults ?: return null
    val indices = plan.begin(op)
    plan.check(op, indices, FaultPhase.BEFORE)?.let { apply(it, connection, session) }
    return indices
  }

  private fun faultAfter(op: SimOp, indices: Pair<Int, Int>?, connection: SimConnection, session: SimSession?) {
    val plan = faults ?: return
    indices ?: return
    plan.check(op, indices, FaultPhase.AFTER)?.let { apply(it, connection, session) }
  }

  private fun apply(fault: SimFault, connection: SimConnection, session: SimSession?): Nothing {
    when (fault) {
      SimFault.DropConnection -> {
        connection.serverDrop()
        throw SmbConnectionLostException()
      }
      SimFault.ExpireSession -> {
        session?.expire()
        throw SmbStatusException(NtStatus.USER_SESSION_DELETED)
      }
      SimFault.Stall -> {
        connection.awaitClosed()
        throw SmbConnectionLostException()
      }
      is SimFault.Status -> throw SmbStatusException(fault.ntStatus)
    }
  }

  /** Le latenze in tempo virtuale e il risveglio dei dischi (§5.2). */
  private fun simulateTime(op: SimOp, bytes: Long) {
    val clock = time ?: return
    val l = latencies
    var ms = l.roundTripMs
    when (op) {
      SimOp.READ -> ms += bytes * l.readPerMiBMs / MIB
      SimOp.WRITE -> ms += bytes * l.writePerMiBMs / MIB
      SimOp.COPY_CHUNK -> ms += bytes * l.readPerMiBMs / MIB / COPY_SPEEDUP
      SimOp.LIST_ENTRY -> {
        // Le voci arrivano a pacchi: niente giro di rete per voce, solo il costo per voce (in microsecondi).
        ms = 0
        val total = listMicros.addAndGet(l.perListEntryUs)
        if (total >= MICROS_PER_MILLI) {
          val whole = total / MICROS_PER_MILLI
          listMicros.addAndGet(-whole * MICROS_PER_MILLI)
          ms = whole
        }
      }
      else -> Unit
    }
    if (op in DISK_OPS) {
      val now = clock.nowMillis()
      val idle = standbyAfterMs
      val last = lastDiskOpAt
      if (idle != null && (last == null || now - last >= idle)) {
        diskWakeUps.incrementAndGet()
        ms += wakeUpMs
      }
    }
    if (ms > 0) clock.advance(ms)
    if (op in DISK_OPS) lastDiskOpAt = clock.nowMillis()
  }

  private inner class SimConnection(override val negotiation: Negotiation) : SmbConnection {
    private val closedLatch = CountDownLatch(1)

    @Volatile private var dropped = false

    @Volatile private var closed = false

    /** Morta per il client, viva per il server (con i suoi handle) finche' non la si raccoglie: [abandonAllConnections]. */
    @Volatile var abandoned = false
      private set
    private val sessions = CopyOnWriteArrayList<SimSession>()

    override val isConnected: Boolean get() = !dropped && !closed && !abandoned

    fun serverDrop() {
      dropped = true
      closedLatch.countDown()
      // Il server chiude la connessione (anche una che il telefono aveva gia' perso): i suoi handle si chiudono con lei.
      releaseConnection(this)
    }

    fun abandon() {
      abandoned = true
      closedLatch.countDown()
    }

    fun expireSessions() = sessions.forEach { it.expire() }

    fun ensureAlive() {
      if (!isConnected) throw SmbConnectionLostException()
    }

    /**
     * Aspetta che la connessione venga chiusa (letture bloccate). Ha un tetto in tempo REALE ([BLOCK_MAX_SECONDS]): un
     * test che dimentica di chiuderla (o che blocca il solo thread che dovrebbe farlo) fallisce con un messaggio invece
     * di restare appeso per sempre.
     */
    fun awaitClosed() {
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BLOCK_MAX_SECONDS)
      while (isConnected) {
        check(System.nanoTime() < deadline) { "simulatore: richiesta ferma e connessione mai chiusa entro $BLOCK_MAX_SECONDS s" }
        closedLatch.await(BLOCK_POLL_MS, TimeUnit.MILLISECONDS)
      }
    }

    override fun authenticate(user: String, password: CharArray): SmbSession {
      ensureAlive()
      authenticateCalls.incrementAndGet()
      beforeOp?.invoke(SimOp.AUTHENTICATE, "")
      ensureAlive()
      val indices = faultBefore(SimOp.AUTHENTICATE, this, null)
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
      val session = SimSession(this, user.lowercase(Locale.ROOT), facts).also { sessions += it }
      faultAfter(SimOp.AUTHENTICATE, indices, this, session)
      return session
    }

    override fun close() {
      closed = true
      closedLatch.countDown()
      // Il client chiude il socket: il server chiude i suoi handle (non se la rete era gia' persa: allora non lo sa).
      if (!abandoned) releaseConnection(this)
    }
  }

  private inner class SimSession(val connection: SimConnection, val user: String, override val facts: SessionFacts) : SmbSession {
    @Volatile var expired = false

    /** La sessione scade sul server (`USER_SESSION_DELETED`): i suoi handle si chiudono. */
    fun expire() {
      expired = true
      releaseSession(this)
    }

    fun ensureUsable() {
      connection.ensureAlive()
      if (expired) throw SmbStatusException(NtStatus.USER_SESSION_DELETED)
    }

    override fun connectShare(name: String): SmbShare {
      ensureUsable()
      treeConnects.incrementAndGet()
      beforeOp?.invoke(SimOp.TREE_CONNECT, name)
      ensureUsable()
      val indices = faultBefore(SimOp.TREE_CONNECT, connection, this)
      val access = synchronized(lock) {
        val state = shares[name.lowercase(Locale.ROOT)] ?: throw SmbStatusException(NtStatus.BAD_NETWORK_NAME)
        state.access[user] ?: SimAccess.NONE
      }
      if (access == SimAccess.NONE) throw SmbStatusException(NtStatus.ACCESS_DENIED)
      return SimShare(this, name, access).also { faultAfter(SimOp.TREE_CONNECT, indices, connection, this) }
    }

    override fun close() = Unit
  }

  private inner class SimShare(val session: SimSession, override val name: String, val access: SimAccess) : SmbShare {

    /**
     * Una richiesta: guasti "prima", gancio, crediti, latenze; poi [body]; poi guasti "dopo" (risposta persa). Per un'apertura, [lost]
     * chiude sul server l'handle che il client non ricevera' mai (un codice d'errore al posto della risposta; con la connessione caduta o
     * la sessione scaduta gli handle si chiudono comunque).
     */
    private inline fun <T> perform(op: SimOp, path: String, bytes: Long = 0, noinline lost: ((T) -> Unit)? = null, body: () -> T): T {
      session.ensureUsable()
      shareOps.incrementAndGet()
      beforeOp?.invoke(op, path)
      session.ensureUsable()
      val indices = faultBefore(op, session.connection, session)
      if (creditFailures.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) throw SmbCreditsExhaustedException()
      simulateTime(op, bytes)
      val result = body()
      try {
        faultAfter(op, indices, session.connection, session)
      } catch (t: Throwable) {
        lost?.invoke(result)
        throw t
      }
      return result
    }

    /** Il nodo aperto con un handle che resta vivo sul server ([shareModes]). Sotto il lucchetto. */
    private fun open(state: SimShareState, node: Node, mode: SmbOpenMode): SimHandle = openLocked(node, state, session, mode)

    private fun requireWrite() {
      if (access != SimAccess.READ_WRITE) throw SmbStatusException(NtStatus.ACCESS_DENIED)
    }

    private fun state() = shareState(name)

    /** Il nodo di [parts], con gli errori di Samba per cartella o nome mancanti. */
    private fun lookup(state: SimShareState, parts: List<String>): Node {
      if (parts.isEmpty()) return state.root
      requireParent(state, parts)
      return state.nodes[key(parts)] ?: throw SmbStatusException(NtStatus.OBJECT_NAME_NOT_FOUND)
    }

    private fun requireParent(state: SimShareState, parts: List<String>) {
      if (parts.size > 1 && state.nodes[key(parts.dropLast(1))]?.isDirectory != true) {
        throw SmbStatusException(NtStatus.OBJECT_PATH_NOT_FOUND)
      }
    }

    private fun checkUnlocked(path: String, forRead: Boolean) {
      val denyRead = locks[lockKey(name, path)] ?: return
      if (!forRead || denyRead) throw SmbStatusException(NtStatus.SHARING_VIOLATION)
    }

    override fun openRead(path: String): SmbReadFile = perform(SimOp.OPEN_READ, path, lost = { (it as SimOpen).lost() }) {
      synchronized(lock) {
        val state = state()
        val found = lookup(state, split(path))
        if (found.isDirectory) throw SmbStatusException(NtStatus.FILE_IS_A_DIRECTORY)
        checkUnlocked(path, forRead = true)
        SimReadFile(this, found, open(state, found, SmbOpenMode.READ))
      }
    }

    override fun stat(path: String): SmbFileInfo = perform(SimOp.STAT, path) {
      synchronized(lock) {
        val node = lookup(state(), split(path))
        if (node.deletePending) throw SmbStatusException(NtStatus.DELETE_PENDING)
        node.info()
      }
    }

    override fun createExclusive(path: String): SmbWriteFile = perform(SimOp.CREATE, path, lost = { (it as SimOpen).lost() }) {
      requireWrite()
      synchronized(lock) {
        val state = state()
        val parts = split(path)
        requireParent(state, parts)
        state.nodes[key(parts)]?.let { existing ->
          throw SmbStatusException(if (existing.deletePending) NtStatus.DELETE_PENDING else NtStatus.OBJECT_NAME_COLLISION)
        }
        val now = nasTime()
        val node = Node(parts.joinToString("/"), false, nextFileId.incrementAndGet(), now).also {
          state.add(key(parts), it)
          state.touchParent(key(parts), now)
        }
        SimWriteFile(this, node, open(state, node, SmbOpenMode.CREATE_EXCLUSIVE))
      }
    }

    override fun mkdir(path: String) = perform(SimOp.MKDIR, path) {
      requireWrite()
      synchronized(lock) {
        val state = state()
        val parts = split(path)
        requireParent(state, parts)
        if (state.nodes.containsKey(key(parts))) throw SmbStatusException(NtStatus.OBJECT_NAME_COLLISION)
        val now = nasTime()
        state.add(key(parts), Node(parts.joinToString("/"), true, nextFileId.incrementAndGet(), now))
        state.touchParent(key(parts), now)
      }
    }

    override fun removeEmptyDirectory(path: String) = perform(SimOp.RMDIR, path) {
      requireWrite()
      synchronized(lock) {
        val state = state()
        val parts = split(path)
        if (parts.isEmpty()) throw SmbStatusException(NtStatus.ACCESS_DENIED)
        val node = lookup(state, parts)
        if (!node.isDirectory) throw SmbStatusException(NtStatus.NOT_A_DIRECTORY)
        if (state.children(key(parts)).isNotEmpty()) throw SmbStatusException(NtStatus.DIRECTORY_NOT_EMPTY)
        checkOpenLocked(node, SmbOpenMode.DELETE_BY_NAME)
        deleteLocked(state, node)
      }
    }

    override fun openDirectory(path: String, access: DirAccess): SmbDirectory = perform(SimOp.OPEN_DIRECTORY, path, lost = { (it as SimOpen).lost() }) {
      if (access == DirAccess.ADD_FILE) requireWrite()
      synchronized(lock) {
        val state = state()
        val parts = split(path)
        val node = lookup(state, parts)
        if (!node.isDirectory) throw SmbStatusException(NtStatus.NOT_A_DIRECTORY)
        val handle = open(state, node, SmbOpenMode.directory(access))
        val listed = node.linkTarget ?: key(parts)
        // La voce `..` come la da' Samba: il genitore sul disco (per un collegamento, quello della destinazione). Radice: niente.
        val parentId = if (listed.isEmpty()) null else state.node(parentKey(listed))?.fileId
        // Un'istantanea dei nodi (riferimenti): le voci si costruiscono una alla volta, mentre si scorre.
        val snapshot = state.children(listed).toList()
        val entries = listOrder?.let { random -> snapshot.shuffled(random) } ?: snapshot
        SimDirectory(this, entries, truncateListsAfter, parentId, handle)
      }
    }

    override fun deleteFile(path: String) = perform(SimOp.DELETE, path) {
      requireWrite()
      synchronized(lock) {
        val state = state()
        val parts = split(path)
        val node = lookup(state, parts)
        if (node.isDirectory) throw SmbStatusException(NtStatus.FILE_IS_A_DIRECTORY)
        checkUnlocked(path, forRead = false)
        checkOpenLocked(node, SmbOpenMode.DELETE_BY_NAME)
        deleteLocked(state, node)
      }
    }

    override fun space(): SmbSpace = perform(SimOp.SPACE, "") {
      synchronized(lock) {
        val state = state()
        val quotaLeft = state.quotaBytes?.let { (it - state.usedBytes()).coerceAtLeast(0) } ?: volumeFreeBytes
        SmbSpace(callerFreeBytes = minOf(quotaLeft, volumeFreeBytes), totalFreeBytes = volumeFreeBytes, totalBytes = volumeTotalBytes)
      }
    }

    // Fase 4B: un file o una cartella esistenti aperti con DELETE. Un file aperto dal PC (lock) risponde SHARING_VIOLATION
    // all'apertura, come Samba quando chi l'ha aperto non condivide la cancellazione; per una cartella lo dice la rinomina o la
    // cancellazione se dentro c'e' un file aperto.
    override fun openForChange(path: String, exclusive: Boolean): SmbChangeHandle = perform(SimOp.OPEN_CHANGE, path, lost = { (it as SimOpen).lost() }) {
      requireWrite()
      synchronized(lock) {
        val parts = split(path)
        if (parts.isEmpty()) throw SmbStatusException(NtStatus.ACCESS_DENIED)
        val state = state()
        val found = lookup(state, parts)
        if (locks.containsKey(lockKey(name, path))) throw SmbStatusException(NtStatus.SHARING_VIOLATION)
        SimChangeHandle(this, found, open(state, found, SmbOpenMode.change(exclusive)))
      }
    }

    // Fase 5A (D5): il proprio temporaneo del backup riaperto per riprenderlo. Mai creato (FILE_OPEN: nome assente = 0xC0000034), in
    // esclusiva come il temporaneo appena creato, senza seguire un collegamento (FILE_OPEN_REPARSE_POINT): lo stato del nodo stesso
    // arriva sullo stesso handle, attributi compresi. Un file aperto dal PC risponde SHARING_VIOLATION.
    override fun openExistingForWrite(path: String): SmbWriteFile = perform(SimOp.OPEN_WRITE, path, lost = { (it as SimOpen).lost() }) {
      requireWrite()
      synchronized(lock) {
        val parts = split(path)
        if (parts.isEmpty()) throw SmbStatusException(NtStatus.ACCESS_DENIED)
        val state = state()
        val found = lookup(state, parts)
        if (found.isDirectory) throw SmbStatusException(NtStatus.FILE_IS_A_DIRECTORY)
        checkUnlocked(path, forRead = false)
        SimWriteFile(this, found, open(state, found, SmbOpenMode.CREATE_EXCLUSIVE), found.info())
      }
    }

    override fun close() = Unit

    /** La chiave attuale di [node] (dopo eventuali rinomine), o null se non c'e' piu'. Sotto il lucchetto. */
    private fun keyOf(state: SimShareState, node: Node): String? = state.nodes.entries.firstOrNull { it.value === node }?.key

    /** Un file aperto dal PC dentro la cartella [key] (o la cartella stessa). Sotto il lucchetto. */
    private fun lockedUnder(key: String): Boolean {
      val prefix = name.lowercase(Locale.ROOT) + ":"
      return locks.keys.any { it.startsWith(prefix) && (it.removePrefix(prefix) == key || it.removePrefix(prefix).startsWith("$key/")) }
    }

    /** Un handle di questa connessione e' aperto su qualcosa SOTTO la cartella [dirKey] (Samba lo controlla nello stesso processo). Sotto il lucchetto. */
    private fun ownHandleBelow(state: SimShareState, dirKey: String): Boolean = handles.values.any { list ->
      list.any { it.state === state && it.connection === session.connection && key(split(it.node.displayPath)).startsWith("$dirKey/") }
    }

    fun renameByHandle(node: Node, newPath: String) = perform(SimOp.RENAME, newPath) {
      synchronized(lock) {
        val state = state()
        val oldKey = keyOf(state, node) ?: throw SmbStatusException(NtStatus.OBJECT_NAME_NOT_FOUND)
        if (lockedUnder(oldKey)) throw SmbStatusException(NtStatus.SHARING_VIOLATION)
        // Samba (`have_file_open_below`): una cartella con un file aperto sotto dallo stesso client non si rinomina.
        if (node.isDirectory && shareModes && openBelowDeniesDirRename && ownHandleBelow(state, oldKey)) {
          throw SmbStatusException(NtStatus.ACCESS_DENIED)
        }
        val toParts = split(newPath)
        val toKey = key(toParts)
        if (toKey == oldKey || toKey.startsWith("$oldKey/")) {
          // Stesso nome (anche solo per le maiuscole: il chiamante non lo chiede mai) o dentro se stessa.
          if (toKey == oldKey) throw SmbStatusException(NtStatus.OBJECT_NAME_COLLISION)
          throw SmbStatusException(NtStatus.INVALID_PARAMETER)
        }
        moveLocked(state, oldKey, toParts, nasTime(), outside = false)
      }
    }

    fun markDeleteByHandle(node: Node) = perform(SimOp.DELETE_ON_CLOSE, node.displayPath) {
      synchronized(lock) {
        val state = state()
        val k = keyOf(state, node) ?: throw SmbStatusException(NtStatus.OBJECT_NAME_NOT_FOUND)
        if (lockedUnder(k)) throw SmbStatusException(NtStatus.SHARING_VIOLATION)
        if (node.isDirectory && state.children(k).isNotEmpty()) throw SmbStatusException(NtStatus.DIRECTORY_NOT_EMPTY)
      }
    }

    /** La chiusura di [handle] da parte di questo client (la cancellazione alla chiusura, se chiesta, la fa il server: [releaseLocked]). */
    fun closeOf(handle: SimHandle) = closeHandle(handle)

    fun readInto(node: Node, buffer: ByteArray, fileOffset: Long, bufferOffset: Int, length: Int): Int {
      val inFlight = readsInFlight.incrementAndGet()
      maxReadsInFlight.accumulateAndGet(inFlight, ::maxOf)
      try {
        return perform(SimOp.READ, node.displayPath, bytes = length.toLong()) {
          readCalls.incrementAndGet()
          if (stallReads) {
            session.connection.awaitClosed()
            throw SmbConnectionLostException()
          }
          val count = synchronized(lock) {
            val size = node.size
            if (fileOffset >= size) return@perform -1
            val count = minOf(length.toLong(), size - fileOffset, maxBytesPerRead.toLong()).toInt()
            node.copyTo(fileOffset, buffer, bufferOffset, count)
            count
          }
          bytesServed.addAndGet(count.toLong())
          count
        }
      } finally {
        readsInFlight.decrementAndGet()
      }
    }

    fun writeInto(node: Node, buffer: ByteArray, fileOffset: Long, bufferOffset: Int, length: Int): Int =
      perform(SimOp.WRITE, node.displayPath, bytes = length.toLong()) {
        synchronized(lock) {
          checkUnlocked(node.displayPath, forRead = false)
          writeLocked(node, buffer, fileOffset, bufferOffset, length)
        }
        length
      }

    private fun writeLocked(node: Node, buffer: ByteArray, fileOffset: Long, bufferOffset: Int, length: Int) {
      val state = state()
      val current = node.materialize()
      val end = fileOffset + length
      val growth = (end - current.size).coerceAtLeast(0)
      val quotaFull = state.quotaBytes != null && state.usedBytes() + growth > state.quotaBytes
      if (quotaFull || growth > volumeFreeBytes) throw SmbStatusException(NtStatus.DISK_FULL)
      if (end > current.size) node.bytes = current.copyOf(end.toInt())
      System.arraycopy(buffer, bufferOffset, node.bytes, fileOffset.toInt(), length)
      node.lastWriteMs = nasTime()
      node.changeMs = node.lastWriteMs
    }

    fun resumeKeyOf(node: Node): ByteArray = perform(SimOp.RESUME_KEY, node.displayPath) {
      val key = ByteArray(RESUME_KEY_BYTES).also { java.security.SecureRandom().nextBytes(it) }
      resumeKeys[key.toList()] = session to node
      key
    }

    /** Copia lato server: la chiave vale nella stessa sessione, anche da un'altra share (come Samba). */
    fun copyChunksInto(node: Node, resumeKey: ByteArray, chunks: List<SmbCopyChunk>): Long =
      perform(SimOp.COPY_CHUNK, node.displayPath, bytes = chunks.sumOf { it.length }) {
        copyChunkStatus?.let { throw SmbStatusException(it) }
        val (owner, source) = resumeKeys[resumeKey.toList()] ?: throw SmbStatusException(NtStatus.OBJECT_NAME_NOT_FOUND)
        if (owner !== session) throw SmbStatusException(NtStatus.ACCESS_DENIED)
        var total = 0L
        synchronized(lock) {
          for (chunk in chunks) {
            val available = (source.size - chunk.sourceOffset).coerceAtLeast(0)
            val length = minOf(chunk.length, available).toInt()
            if (length <= 0) break
            val piece = ByteArray(length).also { source.copyTo(chunk.sourceOffset, it, 0, length) }
            writeLocked(node, piece, chunk.targetOffset, 0, length)
            total += length
          }
        }
        total
      }

    fun rename(node: Node, newPath: String) = perform(SimOp.RENAME, newPath) {
      synchronized(lock) {
        val state = state()
        val oldKey = state.nodes.entries.firstOrNull { it.value === node }?.key ?: throw SmbStatusException(NtStatus.OBJECT_NAME_NOT_FOUND)
        if (locks.containsKey(lockKeyOf(name, oldKey))) throw SmbStatusException(NtStatus.SHARING_VIOLATION)
        moveLocked(state, oldKey, split(newPath), nasTime(), outside = false)
      }
    }

    /** `SET_INFO` di fine file sullo stesso handle (D5): il file tiene i primi [length] byte (o si allunga con zeri, come Samba). */
    fun setLengthOf(node: Node, length: Long) = perform(SimOp.SET_END_OF_FILE, node.displayPath) {
      require(length >= 0) { "Lunghezza negativa." }
      synchronized(lock) {
        checkUnlocked(node.displayPath, forRead = false)
        val current = node.materialize()
        val growth = (length - current.size).coerceAtLeast(0)
        if (growth > volumeFreeBytes) throw SmbStatusException(NtStatus.DISK_FULL)
        node.bytes = current.copyOf(length.toInt())
        node.lastWriteMs = nasTime()
        node.changeMs = node.lastWriteMs
      }
    }

    fun checkAlive() = session.ensureUsable()

    fun flushOrLength(op: SimOp, node: Node) = perform(op, node.displayPath) { Unit }

    fun markDeleteOnClose(node: Node) = perform(SimOp.DELETE_ON_CLOSE, node.displayPath) { Unit }

    fun nextEntry(node: Node): SmbDirEntry = perform(SimOp.LIST_ENTRY, "") {
      listEntriesServed.incrementAndGet()
      synchronized(lock) { node.entry() }
    }
  }

  /** Un handle aperto dal client: chiuderlo lo chiude sul server (una volta sola). */
  private abstract inner class SimOpen(private val share: SimShare, private val handle: SimHandle) {
    @Volatile private var closed = false

    /** Il client non ha mai ricevuto la risposta dell'apertura: per il server l'handle non c'e'. */
    fun lost() = synchronized(lock) { releaseLocked(handle) }

    protected fun markDeleteOnClose() {
      handle.deleteOnClose = true
    }

    fun closeOnServer() {
      if (closed) return
      closed = true
      share.closeOf(handle)
    }
  }

  private inner class SimReadFile(private val share: SimShare, private val node: Node, handle: SimHandle) : SimOpen(share, handle), SmbReadFile {
    override val declaredSize: Long = synchronized(lock) { node.declaredSize ?: node.size }

    override val info: SmbFileInfo = synchronized(lock) { node.info() }

    override fun read(buffer: ByteArray, fileOffset: Long, bufferOffset: Int, length: Int): Int =
      share.readInto(node, buffer, fileOffset, bufferOffset, length)

    override fun resumeKey(): ByteArray = share.resumeKeyOf(node)

    override fun close() = closeOnServer()
  }

  private inner class SimWriteFile(
    private val share: SimShare,
    private val node: Node,
    handle: SimHandle,
    override val info: SmbFileInfo? = null,
  ) : SimOpen(share, handle), SmbWriteFile {
    override fun write(buffer: ByteArray, fileOffset: Long, bufferOffset: Int, length: Int): Int =
      share.writeInto(node, buffer, fileOffset, bufferOffset, length)

    override fun flush() = share.flushOrLength(SimOp.FLUSH, node)

    override fun length(): Long {
      share.flushOrLength(SimOp.LENGTH, node)
      return synchronized(lock) { node.size }
    }

    override fun renameNoReplace(newPath: String) = share.rename(node, newPath)

    override fun copyChunks(resumeKey: ByteArray, chunks: List<SmbCopyChunk>): Long = share.copyChunksInto(node, resumeKey, chunks)

    override fun setLength(length: Long) = share.setLengthOf(node, length)

    override fun deleteOnClose() {
      share.markDeleteOnClose(node)
      markDeleteOnClose()
    }

    // Samba esegue il "cancella alla chiusura" anche quando l'handle si chiude per la caduta della sessione ([releaseLocked]).
    override fun close() = closeOnServer()
  }

  private inner class SimChangeHandle(private val share: SimShare, private val node: Node, handle: SimHandle) :
    SimOpen(share, handle), SmbChangeHandle {
    override val info: SmbFileInfo = synchronized(lock) { node.info() }

    override fun renameNoReplace(newPath: String) = share.renameByHandle(node, newPath)

    override fun deleteOnClose() {
      share.markDeleteByHandle(node)
      markDeleteOnClose()
    }

    // Come Samba: la cancellazione avviene alla chiusura, anche quando l'handle si chiude per la caduta della sessione.
    override fun close() = closeOnServer()
  }

  private inner class SimDirectory(
    private val share: SimShare,
    private val entries: List<Node>,
    private val truncate: Int?,
    private val parent: Long?,
    handle: SimHandle,
  ) : SimOpen(share, handle), SmbDirectory {
    override fun parentFileId(): Long? {
      share.checkAlive()
      return parent
    }

    override fun entries(): Iterator<SmbDirEntry> {
      val inner = entries.iterator()
      var served = 0
      return object : Iterator<SmbDirEntry> {
        override fun hasNext(): Boolean = inner.hasNext() && (truncate == null || served < truncate)

        override fun next(): SmbDirEntry {
          share.checkAlive()
          if (!hasNext()) throw NoSuchElementException()
          served++
          return share.nextEntry(inner.next())
        }
      }
    }

    override fun close() = closeOnServer()
  }

  private fun Node.info(): SmbFileInfo = SmbFileInfo(
    name = displayPath.substringAfterLast('/').takeIf { it.isNotEmpty() },
    isDirectory = isDirectory,
    size = if (isDirectory) 0 else size,
    lastWriteMs = lastWriteMs,
    changeMs = changeMs,
    fileId = fileId,
    attributes = attributes,
  )

  private fun Node.entry(): SmbDirEntry = SmbDirEntry(
    name = displayPath.substringAfterLast('/'),
    isDirectory = isDirectory,
    size = if (isDirectory) 0 else size,
    lastWriteMs = lastWriteMs,
    changeMs = changeMs,
    fileId = fileId,
    attributes = attributes,
  )

  /** Sposta [fromKey] (e tutto cio' che contiene) in [toParts]; mai sopra un nome esistente. Sotto il lucchetto. */
  private fun moveLocked(state: SimShareState, fromKey: String, toParts: List<String>, now: Long, outside: Boolean) {
    val toKey = key(toParts)
    val moving = state.nodes[fromKey] ?: if (outside) error("Origine assente.") else throw SmbStatusException(NtStatus.OBJECT_NAME_NOT_FOUND)
    if (outside) {
      ensureDirs(state, toParts.dropLast(1), now)
    } else if (toParts.size > 1 && state.nodes[key(toParts.dropLast(1))]?.isDirectory != true) {
      throw SmbStatusException(NtStatus.OBJECT_PATH_NOT_FOUND)
    }
    val target = state.nodes[toKey]
    if (target != null && target !== moving) {
      if (outside) error("Destinazione occupata.") else throw SmbStatusException(NtStatus.OBJECT_NAME_COLLISION)
    }
    val movedKeys = state.nodes.keys.filter { it == fromKey || it.startsWith("$fromKey/") }
    val moved = movedKeys.map { it to state.remove(it)!! }
    val depth = split(moving.displayPath).size
    for ((oldKey, node) in moved) {
      node.displayPath = (toParts + split(node.displayPath).drop(depth)).joinToString("/")
      state.add(toKey + oldKey.removePrefix(fromKey), node)
    }
    moving.changeMs = now
    state.touchParent(fromKey, now)
    state.touchParent(toKey, now)
  }

  private fun ensureDirs(state: SimShareState, parts: List<String>, at: Long) {
    for (depth in 1..parts.size) {
      val dir = parts.subList(0, depth)
      val k = key(dir)
      val existing = state.nodes[k]
      if (existing == null) {
        state.add(k, Node(dir.joinToString("/"), true, nextFileId.incrementAndGet(), at))
        state.touchParent(k, at)
      } else {
        require(existing.isDirectory) { "Un file sta al posto di una cartella." }
      }
    }
  }

  private fun shareState(name: String): SimShareState =
    shares[name.lowercase(Locale.ROOT)] ?: throw SmbStatusException(NtStatus.BAD_NETWORK_NAME)

  private fun lockKey(share: String, path: String): String = lockKeyOf(share, key(split(path)))

  private fun lockKeyOf(share: String, nodeKey: String): String = share.lowercase(Locale.ROOT) + ":" + nodeKey

  private companion object {
    const val DEFAULT_GUID = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"

    /** 2026-10-12T00:00:00Z. */
    const val DEFAULT_NAS_TIME = 1_791_763_200_000L
    const val BLOCK_POLL_MS = 50L
    const val BLOCK_MAX_SECONDS = 120L
    const val RESUME_KEY_BYTES = 24
    const val FIRST_FILE_ID = 1000L
    const val DEFAULT_WAKE_UP_MS = 8_000L
    const val MIB = 1024L * 1024
    const val COPY_SPEEDUP = 30
    const val MICROS_PER_MILLI = 1000L
    const val MAX_MATERIALIZE = 256L * 1024 * 1024
    val EMPTY = ByteArray(0)

    /** Le operazioni che toccano i dischi (il risveglio dallo standby, E-R1). */
    val DISK_OPS: Set<SimOp> = setOf(
      SimOp.OPEN_READ, SimOp.READ, SimOp.CREATE, SimOp.WRITE, SimOp.RENAME, SimOp.MKDIR, SimOp.OPEN_DIRECTORY,
      SimOp.DELETE, SimOp.COPY_CHUNK, SimOp.STAT, SimOp.RMDIR, SimOp.OPEN_CHANGE, SimOp.OPEN_WRITE, SimOp.SET_END_OF_FILE,
    )

    fun split(path: String): List<String> = path.split('\\', '/').filter { it.isNotEmpty() }

    fun key(parts: List<String>): String = parts.joinToString("/") { it.lowercase(Locale.ROOT) }

    fun parentKey(key: String): String = key.substringBeforeLast('/', "")
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
