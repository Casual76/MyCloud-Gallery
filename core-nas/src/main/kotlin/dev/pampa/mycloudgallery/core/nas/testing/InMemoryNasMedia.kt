package dev.pampa.mycloudgallery.core.nas.testing

import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.contract.ListOutcome
import dev.pampa.mycloudgallery.core.nas.contract.NasAttributes
import dev.pampa.mycloudgallery.core.nas.contract.NasEntry
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaLimits
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasRandomFile
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.ServerCopy
import dev.pampa.mycloudgallery.core.nas.ops.CopyOutcome
import dev.pampa.mycloudgallery.core.nas.ops.DeleteOutcome
import dev.pampa.mycloudgallery.core.nas.ops.DestructiveNasAccess
import dev.pampa.mycloudgallery.core.nas.ops.NasFileOps
import dev.pampa.mycloudgallery.core.nas.ops.NasOpsPaths
import dev.pampa.mycloudgallery.core.nas.ops.NasSpaceInfo
import dev.pampa.mycloudgallery.core.nas.ops.NasTempWriter
import dev.pampa.mycloudgallery.core.nas.ops.PublishOutcome
import dev.pampa.mycloudgallery.core.nas.ops.RenameOutcome
import dev.pampa.mycloudgallery.core.nas.ops.RmdirOutcome
import dev.pampa.mycloudgallery.core.nas.ops.TempFinish
import dev.pampa.mycloudgallery.core.nas.ops.backup.AppendOnlyNasAccess
import dev.pampa.mycloudgallery.core.nas.ops.backup.BackupTempWriter
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupOps
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupPaths
import dev.pampa.mycloudgallery.core.nas.ops.backup.ReopenOutcome
import dev.pampa.mycloudgallery.core.nas.ops.backup.TempPoint
import dev.pampa.mycloudgallery.core.nas.ops.backup.VersionPaths
import dev.pampa.mycloudgallery.core.nas.session.SmbErrors
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.NasListing
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.nas.PublishResult
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

// Il NAS finto dei MEDIA della Fase 4 (P0): uno scheletro funzionante del §5.2 di docs/fase4-progetto.md, abbastanza per
// far lavorare P4, P5, P7 e P8 senza SMB. Il simulatore fedele (dietro SmbTransport, con FaultPlan "prima/dopo che il
// server l'abbia applicata", latenze a tempo virtuale, dischi in standby, quote) e' InMemorySmbBackend (P1), letto da
// `SmbNasSessionManager.mediaReader()`; gli stessi casi del contratto passano su entrambi (NasMediaReaderContract).
// Questo vive SOPRA il contratto NasMediaReader, quindi non prova nulla di SMB.

/**
 * Il contenuto di un file finto: byte veri (piccoli) oppure **virtuali** (dimensione + seme, generati a richiesta),
 * cosi' un video "da 9,4 GB" non occupa memoria e si legge comunque a intervalli (§5.3).
 */
interface NasContent {
  val size: Long

  /** Copia in [dst] i byte da [offset]; [len] e' gia' entro [size]. */
  fun copy(offset: Long, dst: ByteArray, dstOffset: Int, len: Int)

  companion object {
    fun of(bytes: ByteArray): NasContent = BytesContent(bytes.copyOf())

    /**
     * [size] byte pseudo-casuali dal [seed], sempre gli stessi per la stessa posizione (letture a intervalli
     * ripetibili). Due semi diversi danno contenuti diversi ovunque; [head], se c'e', sostituisce i primi byte (per
     * esempio un'intestazione JPEG o MP4 vera davanti a un corpo virtuale).
     */
    fun virtual(size: Long, seed: Long, head: ByteArray = ByteArray(0)): NasContent = VirtualContent(size, seed, head.copyOf())
  }
}

private class BytesContent(val bytes: ByteArray) : NasContent {
  override val size: Long get() = bytes.size.toLong()

  override fun copy(offset: Long, dst: ByteArray, dstOffset: Int, len: Int) {
    System.arraycopy(bytes, offset.toInt(), dst, dstOffset, len)
  }
}

private class VirtualContent(override val size: Long, private val seed: Long, private val head: ByteArray) : NasContent {
  init {
    require(size >= 0 && head.size <= size) { "Contenuto virtuale non valido." }
  }

  override fun copy(offset: Long, dst: ByteArray, dstOffset: Int, len: Int) {
    for (i in 0 until len) {
      val pos = offset + i
      dst[dstOffset + i] = if (pos < head.size) head[pos.toInt()] else byteAt(pos)
    }
  }

  /** Un byte per posizione: SplitMix64 su (seme, blocco da 8), poi il byte giusto del blocco. */
  private fun byteAt(pos: Long): Byte {
    var z = seed + (pos ushr 3) * -0x61c8864680b583ebL
    z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
    z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
    z = z xor (z ushr 31)
    return (z ushr (((pos and 7L).toInt()) * 8)).toByte()
  }
}

/** Le operazioni del NAS finto, per contare le chiamate e per i guasti programmati. */
enum class FakeNasOp {
  LIST, STAT, OPEN, READ, READ_ALL, SERVER_COPY,

  // Le primitive di scrittura (4B, NasFileOps): il fake delle operazioni le conta e le guasta come le letture.
  MKDIRS, RENAME, COPY_TO_TEMP, OPEN_TEMP, WRITE_TEMP, PUBLISH_TEMP, TRASH_MOVE, DELETE, RMDIR, SPACE,

  // Fase 5A (NasBackupOps): la riapertura di un temporaneo del backup con l'accorciamento al punto di ripresa (D5).
  REOPEN_TEMP,

  // Fase 5B (P0-B, NasBackupOps): le due rinomine delle versioni, verso e da `Versioni/` (D17, D18).
  ARCHIVE_VERSION, RESTORE_VERSION,
}

/** Come risponde la copia lato server di `NasFileOps.copyToTemp` (E-D12). */
enum class ServerCopyMode { OK, NOT_SUPPORTED, ACCESS_DENIED }

/**
 * Un guasto programmato: dato l'operazione, la share e il percorso (null per la radice), l'errore da restituire o
 * `null` per proseguire. Per un "guasto all'n-esima chiamata" basta un contatore nella lambda.
 */
fun interface FakeNasFault {
  fun before(op: FakeNasOp, share: String, path: SharePath?): NasError?
}

/**
 * Un NAS in memoria per i media: share, cartelle e file con **nomi senza distinzione di maiuscole** (forma scritta
 * conservata, come Samba), `fileId` stabili alla rinomina e nuovi per una copia, date dei file e delle cartelle in
 * ora del NAS ([nasNow]), attributi, file bloccati, guasti programmati, ordine degli elenchi casuale, contatori.
 *
 * I metodi di preparazione ([putFile], [rename], [modify], [delete]...) sono "il PC" o "un altro dispositivo": cambiano
 * il NAS da fuori. [reader] e [serverCopy] sono cio' che vede l'app.
 */
class InMemoryNasMedia(
  /** L'ora del NAS in ms (scarto e salti li decide il test: oggi il NAS e' ~58 s avanti). */
  var nasNow: () -> Long = { DEFAULT_NOW },
  /** Se non null, ogni elenco esce in ordine casuale con questo generatore (I3: l'ordine non e' garantito). */
  var shuffle: Random? = null,
) {

  private class Node(
    var displayName: String,
    val isDirectory: Boolean,
    var content: NasContent,
    var modifiedAt: Long,
    var changedAt: Long,
    val fileId: Long,
    var attributes: Int,
  )

  private class ShareState(val name: String) {
    /** Chiave: percorso in minuscolo ("" = radice). */
    val nodes = LinkedHashMap<String, Node>()
  }

  private val lock = Any()
  private val shares = HashMap<String, ShareState>()
  private val locked = HashSet<String>()
  private val nextFileId = AtomicLong(1000)

  /** Se non null, ogni operazione fallisce con questo errore. */
  @Volatile var failWith: NasError? = null

  /** Guasto programmato per operazione (piu' fine di [failWith]). */
  @Volatile var fault: FakeNasFault? = null

  // --- Contatori: per provare "verifica senza novita' = nessuna lettura di file", "video mai scaricati interi"... -----
  val listCalls = AtomicInteger(0)
  val statCalls = AtomicInteger(0)
  val opens = AtomicInteger(0)
  val bytesRead = AtomicLong(0)
  val serverCopies = AtomicInteger(0)

  // --- Il NAS visto "da fuori" -------------------------------------------------------------------------------------

  fun addShare(name: String) {
    synchronized(lock) {
      shares.getOrPut(key(name)) { ShareState(name).also { it.nodes[""] = dirNode(name) } }
    }
  }

  /** Toglie una share intera (E-R10): da qui in poi ogni operazione su di essa da' `ShareMissing`. */
  fun removeShare(name: String) {
    synchronized(lock) { shares.remove(key(name)) }
  }

  /** Crea (o sostituisce) un file, creando le cartelle che mancano. Restituisce il `fileId`. */
  fun putFile(
    share: String,
    path: String,
    content: NasContent,
    modifiedAt: Long = nasNow(),
    attributes: Int = NasAttributes.ARCHIVE,
  ): Long = synchronized(lock) {
    val state = shareState(share)
    val parts = split(path)
    ensureDirs(state, parts.dropLast(1), modifiedAt)
    val k = key(parts)
    val previous = state.nodes[k]
    require(previous == null || !previous.isDirectory) { "Al posto del file c'e' una cartella." }
    val node = Node(parts.last(), false, content, modifiedAt, modifiedAt, previous?.fileId ?: nextFileId.incrementAndGet(), attributes)
    state.nodes[k] = node
    touchParent(state, parts, modifiedAt)
    node.fileId
  }

  /** Crea una cartella (e quelle sopra). */
  fun putDir(share: String, path: String, attributes: Int = NasAttributes.DIRECTORY, modifiedAt: Long = nasNow()) {
    synchronized(lock) {
      val state = shareState(share)
      val parts = split(path)
      ensureDirs(state, parts, modifiedAt)
      state.nodes.getValue(key(parts)).attributes = attributes or NasAttributes.DIRECTORY
    }
  }

  /** Modifica sul posto dal PC (E-N2): byte nuovi, stesso `fileId`, data nuova. La data della cartella NON cambia. */
  fun modify(share: String, path: String, content: NasContent, modifiedAt: Long = nasNow()) {
    synchronized(lock) {
      val node = node(shareState(share), split(path)) ?: error("File assente.")
      node.content = content
      node.modifiedAt = modifiedAt
      node.changedAt = modifiedAt
    }
  }

  /**
   * Rinomina o sposta (anche una cartella intera, E-N1): i `fileId` restano, la data di modifica dei file no (come
   * SMB), quella delle due cartelle genitore si'. Non sostituisce mai: la destinazione occupata e' un errore.
   */
  fun rename(share: String, from: String, to: String) {
    synchronized(lock) {
      val state = shareState(share)
      val fromParts = split(from)
      val toParts = split(to)
      val fromKey = key(fromParts)
      require(state.nodes.containsKey(fromKey)) { "Origine assente." }
      require(!state.nodes.containsKey(key(toParts)) || key(toParts) == fromKey) { "Destinazione occupata." }
      val now = nasNow()
      ensureDirs(state, toParts.dropLast(1), now)
      val moved = state.nodes.keys.filter { it == fromKey || it.startsWith("$fromKey/") }
      val renamed = LinkedHashMap<String, Node>()
      for (old in moved) {
        val node = state.nodes.remove(old)!!
        val suffix = old.removePrefix(fromKey)
        renamed[key(toParts) + suffix] = node
        node.changedAt = now
      }
      renamed[key(toParts)]!!.displayName = toParts.last()
      state.nodes.putAll(renamed)
      touchParent(state, fromParts, now)
      touchParent(state, toParts, now)
    }
  }

  /** Cancellazione dal PC (E-N3). Una cartella sparisce con tutto il contenuto. */
  fun delete(share: String, path: String) {
    synchronized(lock) {
      val state = shareState(share)
      val parts = split(path)
      val k = key(parts)
      state.nodes.keys.removeAll { it == k || it.startsWith("$k/") }
      touchParent(state, parts, nasNow())
    }
  }

  /** File aperto dal PC (E-N13): l'apertura fallisce con `FileBusy`. */
  fun lock(share: String, path: String) {
    synchronized(lock) { locked += lockKey(share, path) }
  }

  fun unlock(share: String, path: String) {
    synchronized(lock) { locked -= lockKey(share, path) }
  }

  fun exists(share: String, path: String): Boolean = synchronized(lock) {
    shares[key(share)]?.nodes?.containsKey(key(split(path))) == true
  }

  /** I byte veri di un file (per confrontare un hash o una copia). */
  fun bytes(share: String, path: String): ByteArray? = synchronized(lock) {
    val node = shares[key(share)]?.let { node(it, split(path)) } ?: return null
    ByteArray(node.content.size.toInt()).also { node.content.copy(0, it, 0, it.size) }
  }

  fun fileCount(share: String): Int = synchronized(lock) { shareState(share).nodes.values.count { !it.isDirectory } }

  /** I percorsi (scritti come sul server) di tutti i file di una share, in ordine alfabetico: per i controlli dei test (4B). */
  fun filePaths(share: String): List<String> = synchronized(lock) {
    val state = shareState(share)
    state.nodes.entries.filter { (k, n) -> k.isNotEmpty() && !n.isDirectory }.map { (k, _) ->
      val parts = k.split('/')
      (1..parts.size).joinToString("/") { depth -> state.nodes.getValue(parts.subList(0, depth).joinToString("/")).displayName }
    }.sorted()
  }

  // --- Cio' che vede l'app -------------------------------------------------------------------------------------------

  /** Il lettore dei media sopra questo NAS. */
  val reader: NasMediaReader = Reader()

  /**
   * Fase 4B (P12): la share [share] vista da `core-trust` (`NasFileSystem`: elenco, lettura, pubblicazione esclusiva) sopra gli
   * **stessi** file: i record firmati del cestino scritti dall'app finiscono accanto ai file cestinati. [failWith] vale anche qui.
   */
  fun fileSystem(share: String): NasFileSystem = object : NasFileSystem {
    override val shareName: String = share

    private fun failIfRequested() {
      failWith?.let { throw NasIoException(it) }
    }

    override fun list(dir: SharePath, limit: Int): NasListing {
      failIfRequested()
      val names = synchronized(lock) {
        val state = shares[key(share)] ?: throw NasIoException(NasError.ShareMissing(share))
        val prefix = key(dir.components) + "/"
        state.nodes.entries.filter { (k, _) -> k.startsWith(prefix) && '/' !in k.removePrefix(prefix) }.map { it.value.displayName }
      }
      return NasListing(names.take(limit), truncated = names.size > limit)
    }

    override fun read(path: SharePath, maxBytes: Int): NasRead {
      failIfRequested()
      val node = synchronized(lock) {
        val state = shares[key(share)] ?: throw NasIoException(NasError.ShareMissing(share))
        node(state, path.components)
      } ?: return NasRead.NotFound
      if (node.isDirectory) return NasRead.NotFound
      if (node.content.size > maxBytes) return NasRead.TooLarge
      return NasRead.Data(ByteArray(node.content.size.toInt()).also { node.content.copy(0, it, 0, it.size) })
    }

    override fun publishExclusive(path: SharePath, bytes: ByteArray): PublishResult {
      failIfRequested()
      return synchronized(lock) {
        val state = shares[key(share)] ?: throw NasIoException(NasError.ShareMissing(share))
        if (state.nodes.containsKey(key(path.components))) return@synchronized PublishResult.ALREADY_EXISTS
        val now = nasNow()
        ensureDirs(state, path.components.dropLast(1), now)
        state.nodes[key(path.components)] =
          Node(path.name, false, NasContent.of(bytes), now, now, nextFileId.incrementAndGet(), NasAttributes.ARCHIVE)
        touchParent(state, path.components, now)
        PublishResult.CREATED
      }
    }
  }

  /** La copia lato server (fra share di questo NAS, nuovo `fileId`, mai sovrascrivendo). */
  val serverCopy: ServerCopy = object : ServerCopy {
    override suspend fun copy(fromShare: String, from: SharePath, toShare: String, to: SharePath): NasResult<Long> {
      serverCopies.incrementAndGet()
      fail(FakeNasOp.SERVER_COPY, fromShare, from)?.let { return NasResult.Err(it) }
      return synchronized(lock) {
        val source = shares[key(fromShare)] ?: return@synchronized NasResult.Err(NasError.ShareMissing(fromShare))
        val target = shares[key(toShare)] ?: return@synchronized NasResult.Err(NasError.ShareMissing(toShare))
        val node = node(source, from.components) ?: return@synchronized NasResult.Err(NasError.NotFound)
        if (node.isDirectory) return@synchronized NasResult.Err(NasError.Unexpected(null))
        if (target.nodes.containsKey(key(to.components))) return@synchronized NasResult.Err(NasError.Exists)
        val now = nasNow()
        ensureDirs(target, to.components.dropLast(1), now)
        val bytes = ByteArray(node.content.size.toInt()).also { node.content.copy(0, it, 0, it.size) }
        target.nodes[key(to.components)] =
          Node(to.components.last(), false, NasContent.of(bytes), now, now, nextFileId.incrementAndGet(), node.attributes)
        touchParent(target, to.components, now)
        NasResult.Ok(bytes.size.toLong())
      }
    }
  }

  // --- Le primitive di scrittura (4B, P0-B): il fake di NasFileOps ----------------------------------------------------------
  // Un fake funzionante delle primitive, con la semantica del contratto: mai sostituire, mai eliminare un media, rinomina con
  // controllo del timbro, copia lato server su un temporaneo, scrittore a offset, pubblicazione senza sostituire, cestino per
  // rinomina, eliminazione solo dentro `.mycloudgallery/` con la dimensione controllata nello stesso passo. I guasti sono il
  // `FaultPlan` del simulatore SMB ([opsFaults]): caduta PRIMA o DOPO che il NAS abbia applicato l'operazione (risposta persa),
  // sessione scaduta, stallo. P12 scrive l'implementazione vera dietro SmbTransport e estende il simulatore; il contratto non cambia.

  /** Il piano dei guasti delle operazioni di scrittura (stessa semantica di `InMemorySmbBackend.faults`). */
  @Volatile var opsFaults: FaultPlan? = null

  /**
   * Fase 4B (P12): il piano dei guasti delle **letture** (elenco, stato, apertura, ogni blocco letto), con la stessa semantica (prima o
   * dopo: per una lettura e' la risposta persa). Con lo stesso [FaultPlan] di [opsFaults] le chiamate si contano tutte insieme: un
   * guasto "alla chiamata n" colpisce qualunque chiamata, lettura o scrittura.
   */
  @Volatile var readFaults: FaultPlan? = null

  /** Una lettura con i guasti programmati di [readFaults] (prima e dopo). */
  private inline fun <T> readGuarded(sim: SimOp, share: String, block: () -> NasResult<T>): NasResult<T> {
    val plan = readFaults ?: return block()
    val indices = plan.begin(sim)
    plan.check(sim, indices, FaultPhase.BEFORE)?.let { return NasResult.Err(faultError(it, share)) }
    val result = block()
    plan.check(sim, indices, FaultPhase.AFTER)?.let { return NasResult.Err(faultError(it, share)) }
    return result
  }

  /** Come risponde la copia lato server (E-D12). */
  @Volatile var serverCopyMode: ServerCopyMode = ServerCopyMode.OK

  /** Lo spazio di ogni share (E-D5, E-N13): di default ampio. */
  @Volatile var spaceOf: (String) -> NasSpaceInfo = { NasSpaceInfo(totalBytes = 2L shl 40, freeBytes = 1L shl 40, callerFreeBytes = 1L shl 40) }

  private val readOnlyShares = HashSet<String>()
  private val opCounts = ConcurrentHashMap<FakeNasOp, AtomicInteger>()

  /** Share in sola lettura per questo account (E-N9): ogni scrittura risponde `PermissionDenied`. */
  fun setReadOnly(share: String, readOnly: Boolean) {
    synchronized(lock) { if (readOnly) readOnlyShares += key(share) else readOnlyShares -= key(share) }
  }

  /** Quante volte e' stata chiamata la primitiva [op] (anche se un guasto l'ha fatta fallire). */
  fun opCount(op: FakeNasOp): Int = opCounts[op]?.get() ?: 0

  /** Il fake delle primitive di scrittura per il dispositivo [ownerDeviceId] (un id valido, `TrustIds`). */
  @DestructiveNasAccess
  fun fileOps(ownerDeviceId: String): NasFileOps = Ops(ownerDeviceId)

  /** Esegue [apply] sotto il blocco del NAS (un'operazione e' atomica), con i guasti prima e dopo. */
  private fun <T> guarded(
    sim: SimOp,
    op: FakeNasOp,
    share: String,
    path: SharePath?,
    write: Boolean,
    apply: () -> NasResult<T>,
  ): NasResult<T> {
    opCounts.getOrPut(op) { AtomicInteger(0) }.incrementAndGet()
    fail(op, share, path)?.let { return NasResult.Err(it) }
    val plan = opsFaults
    val indices = plan?.begin(sim)
    if (plan != null && indices != null) plan.check(sim, indices, FaultPhase.BEFORE)?.let { return NasResult.Err(faultError(it, share)) }
    val result = synchronized(lock) {
      if (write && key(share) in readOnlyShares) NasResult.Err(NasError.PermissionDenied) else apply()
    }
    if (plan != null && indices != null) plan.check(sim, indices, FaultPhase.AFTER)?.let { return NasResult.Err(faultError(it, share)) }
    return result
  }

  private fun faultError(fault: SimFault, share: String): NasError = when (fault) {
    SimFault.DropConnection -> NasError.Timeout
    SimFault.ExpireSession -> NasError.SessionExpired
    SimFault.Stall -> NasError.Stalled
    is SimFault.Status -> SmbErrors.fromStatus(fault.ntStatus, share)
  }

  private fun stampOf(node: Node?): NasStamp? = node?.let { NasStamp(if (it.isDirectory) 0 else it.content.size, it.modifiedAt, it.fileId) }

  /** Il timbro attuale se il file non e' piu' quello atteso (le cartelle non si confrontano), altrimenti null. */
  private fun changedStamp(node: Node, expect: NasStamp?): NasStamp? {
    if (expect == null || node.isDirectory) return null
    val actual = NasStamp(node.content.size, node.modifiedAt, node.fileId)
    return if (expect.sameAs(actual)) null else actual
  }

  /** Un livello del percorso e' un file: creare cartelle sotto di esso e' impossibile. */
  private fun blockedByFile(state: ShareState, parts: List<String>): Boolean =
    (1..parts.size).any { depth -> state.nodes[key(parts.subList(0, depth))]?.isDirectory == false }

  private fun hasChildren(state: ShareState, k: String): Boolean = state.nodes.keys.any { it.startsWith("$k/") }

  /** Spazio per [bytes] sulla share: quota dell'account o disco pieno (E-D5, E-N13, E-R9). */
  private fun roomError(share: String, bytes: Long): NasError? {
    val space = spaceOf(share)
    if (bytes <= space.callerFreeBytes) return null
    return if (space.quotaLimited) NasError.QuotaExceeded else NasError.NasFull
  }

  /** Un buffer che cresce a ogni scrittura: il contenuto di un temporaneo mentre lo si scrive. */
  private class GrowableContent : NasContent {
    private var data = ByteArray(0)

    @Volatile private var length = 0

    override val size: Long get() = length.toLong()

    override fun copy(offset: Long, dst: ByteArray, dstOffset: Int, len: Int) {
      System.arraycopy(data, offset.toInt(), dst, dstOffset, len)
    }

    fun write(offset: Long, src: ByteArray, off: Int, len: Int) {
      val end = offset.toInt() + len
      if (end > data.size) data = data.copyOf(maxOf(end, data.size * 2))
      System.arraycopy(src, off, data, offset.toInt(), len)
      if (end > length) length = end
    }

    fun snapshot(): ByteArray = data.copyOf(length)
  }

  // --- Le primitive del backup (Fase 5A, P0): il fake di NasBackupOps -------------------------------------------------------------
  // La vista in sola aggiunta sopra le stesse primitive del fake della 4B: stessi guasti ([opsFaults], prima e dopo che il NAS abbia
  // applicato l'operazione), spazio e quota ([spaceOf]), sola lettura ([setReadOnly]), contatori ([opCount]). In piu': i temporanei
  // devono essere del backup del dispositivo (`<io>-bk-`), lo scrittore ha il punto di ripresa (`checkpoint`, con l'identita' del file) e
  // la riapertura accorcia il temporaneo al punto salvato (`reopenTemp`, D5) solo se e' ancora lui (stessa identita', R3 della revisione
  // 5A; il fake non ha collegamenti). Nessuna primitiva che tolga o sposti un file visibile.

  /** Quante riaperture hanno accorciato un temporaneo del backup (D5): per provare "al massimo 64 MiB ricaricati". */
  val backupTruncations = AtomicInteger(0)

  /** Byte scritti dagli scrittori del backup, riscritture comprese: il traffico del caricamento. */
  val backupBytesWritten = AtomicLong(0)

  private val knownContents = java.util.concurrent.CopyOnWriteArrayList<NasContent>()

  /**
   * Un contenuto che i test scriveranno (es. un video "da 3 GB" virtuale del telefono finto): una pagina di un temporaneo del backup
   * scritta con gli **stessi byte, allo stesso punto** di un contenuto noto si conserva come riferimento invece che come copia, cosi'
   * anche un file virtuale da GB si carica, si verifica e si pubblica senza occupare memoria. Non cambia la semantica: i byte letti
   * sono quelli scritti.
   */
  fun registerKnownContent(content: NasContent) {
    knownContents += content
  }

  /** Il fake delle primitive del backup per il dispositivo [ownerDeviceId] (un id valido, `TrustIds`). */
  @AppendOnlyNasAccess
  fun backupOps(ownerDeviceId: String): NasBackupOps = BackupView(Ops(ownerDeviceId))

  /**
   * Il contenuto di un temporaneo del backup mentre lo si scrive: pagine da 64 KiB, ognuna con i suoi byte o (se uguale) un
   * riferimento allo stesso tratto di un contenuto noto ([registerKnownContent]). Si accorcia alla riapertura (D5).
   */
  private inner class PagedContent : NasContent {
    private inner class Page(val bytes: ByteArray?, val source: NasContent?, val length: Int)

    private val pages = ArrayList<Page>()

    @Volatile private var length = 0L

    override val size: Long get() = length

    override fun copy(offset: Long, dst: ByteArray, dstOffset: Int, len: Int) {
      var done = 0
      while (done < len) {
        val pos = offset + done
        val index = (pos / PAGE).toInt()
        val inPage = (pos % PAGE).toInt()
        val page = pages[index]
        val n = minOf(len - done, page.length - inPage)
        if (page.bytes != null) {
          System.arraycopy(page.bytes, inPage, dst, dstOffset + done, n)
        } else {
          page.source!!.copy(index.toLong() * PAGE + inPage, dst, dstOffset + done, n)
        }
        done += n
      }
    }

    private fun materialize(index: Int): ByteArray {
      val page = pages[index]
      return page.bytes?.copyOf() ?: ByteArray(page.length).also { page.source!!.copy(index.toLong() * PAGE, it, 0, page.length) }
    }

    private fun store(index: Int, data: ByteArray, len: Int) {
      val start = index.toLong() * PAGE
      val head = data.copyOf(len)
      val match = knownContents.firstOrNull { known ->
        known.size >= start + len && ByteArray(len).also { known.copy(start, it, 0, len) }.contentEquals(head)
      }
      val page = if (match != null) Page(null, match, len) else Page(head, null, len)
      if (index == pages.size) pages += page else pages[index] = page
    }

    fun write(offset: Long, src: ByteArray, off: Int, len: Int) {
      require(offset in 0..length) { "Scrittura oltre la fine del temporaneo." }
      var done = 0
      while (done < len) {
        val pos = offset + done
        val index = (pos / PAGE).toInt()
        val inPage = (pos % PAGE).toInt()
        val n = minOf(len - done, PAGE - inPage)
        val current = if (index < pages.size) materialize(index) else ByteArray(0)
        val pageLen = maxOf(current.size, inPage + n)
        val data = current.copyOf(PAGE)
        System.arraycopy(src, off + done, data, inPage, n)
        store(index, data, pageLen)
        done += n
      }
      length = maxOf(length, offset + len)
    }

    /** Tiene i primi [keep] byte (fine file sullo stesso handle, D5). */
    fun truncate(keep: Long) {
      require(keep in 0..length) { "Accorciamento oltre la fine." }
      val full = (keep / PAGE).toInt()
      val rest = (keep % PAGE).toInt()
      val lastIndex = if (rest == 0) full else full + 1
      while (pages.size > lastIndex) pages.removeAt(pages.size - 1)
      if (rest != 0) store(full, materialize(full).copyOf(PAGE), rest)
      length = keep
    }

    /** I primi [keep] byte di [content] (un temporaneo che non nasce da questo fake: la ripresa lo riprende da qui). */
    fun loadFrom(content: NasContent, keep: Long) {
      var pos = 0L
      val buffer = ByteArray(PAGE)
      while (pos < keep) {
        val n = minOf(PAGE.toLong(), keep - pos).toInt()
        content.copy(pos, buffer, 0, n)
        write(pos, buffer, 0, n)
        pos += n
      }
    }
  }

  @AppendOnlyNasAccess
  private inner class BackupView(private val ops: Ops) : NasBackupOps {
    override val ownerDeviceId: String get() = ops.ownerDeviceId

    private fun requireBackupTemp(temp: SharePath) {
      require(NasBackupPaths.isBackupTempOf(temp, ownerDeviceId)) { "Un temporaneo del backup di questo dispositivo." }
    }

    override suspend fun mkdirs(share: String, dir: SharePath, purpose: Purpose): NasResult<Unit> = ops.mkdirs(share, dir, purpose)

    override suspend fun openTemp(share: String, temp: SharePath, purpose: Purpose): NasResult<BackupTempWriter> {
      requireBackupTemp(temp)
      return guarded(SimOp.CREATE, FakeNasOp.OPEN_TEMP, share, temp, write = true) {
        val state = shares[key(share)] ?: return@guarded NasResult.Err(NasError.ShareMissing(share))
        if (state.nodes.containsKey(key(temp.components))) return@guarded NasResult.Err(NasError.Exists)
        val now = nasNow()
        ensureDirs(state, temp.components.dropLast(1), now)
        val content = PagedContent()
        val node = Node(temp.name, false, content, now, now, nextFileId.incrementAndGet(), NasAttributes.ARCHIVE)
        state.nodes[key(temp.components)] = node
        touchParent(state, temp.components, now)
        NasResult.Ok(BackupWriter(share, temp, node, content))
      }
    }

    override suspend fun reopenTemp(share: String, temp: SharePath, keepBytes: Long, tempId: Long, purpose: Purpose): NasResult<ReopenOutcome> {
      requireBackupTemp(temp)
      require(keepBytes >= 0) { "Punto di ripresa negativo." }
      return guarded(SimOp.OPEN_CHANGE, FakeNasOp.REOPEN_TEMP, share, temp, write = true) {
        val state = shares[key(share)] ?: return@guarded NasResult.Err(NasError.ShareMissing(share))
        val node = node(state, temp.components) ?: return@guarded NasResult.Ok(ReopenOutcome.Missing)
        if (node.isDirectory) return@guarded NasResult.Err(NasError.NotFound)
        if (lockKey(share, temp.value) in locked) return@guarded NasResult.Ok(ReopenOutcome.Locked)
        // R3 (revisione 5A): un altro file al nome del temporaneo (o un punto di rianalisi) non si accorcia mai.
        if (node.fileId != tempId || (node.attributes and NasAttributes.REPARSE_POINT) != 0) return@guarded NasResult.Ok(ReopenOutcome.Foreign)
        if (node.content.size < keepBytes) return@guarded NasResult.Ok(ReopenOutcome.TooShort(node.content.size))
        val content = (node.content as? PagedContent) ?: PagedContent().also { it.loadFrom(node.content, keepBytes) }
        content.truncate(keepBytes)
        node.content = content
        node.modifiedAt = nasNow()
        backupTruncations.incrementAndGet()
        NasResult.Ok(ReopenOutcome.Reopened(BackupWriter(share, temp, node, content)))
      }
    }

    override suspend fun copyToTemp(
      fromShare: String,
      from: SharePath,
      toShare: String,
      temp: SharePath,
      expect: NasStamp?,
      purpose: Purpose,
    ): NasResult<CopyOutcome> {
      requireBackupTemp(temp)
      return ops.copyToTemp(fromShare, from, toShare, temp, expect, purpose)
    }

    override suspend fun publishTemp(share: String, temp: SharePath, final: SharePath, purpose: Purpose): NasResult<PublishOutcome> {
      requireBackupTemp(temp)
      return ops.publishTemp(share, temp, final, purpose)
    }

    override suspend fun deleteOwnTemp(share: String, temp: SharePath, purpose: Purpose): NasResult<DeleteOutcome> {
      requireBackupTemp(temp)
      return ops.deleteOwnTemp(share, temp, purpose)
    }

    /**
     * 5B (P0-B, D17): archivia [from] in [to] sotto `Versioni/`. Stessa semantica della rinomina della 4B (senza sostituire, la cartella di
     * [to] deve esistere, timbro controllato prima di muovere, guasti prima e dopo, share in sola lettura) piu' i limiti della vista: i
     * percorsi giusti ([VersionPaths]) e solo **file** (una cartella al posto del file e' `SourceChanged`: non si archiviano cartelle).
     */
    override suspend fun archiveVersion(share: String, from: SharePath, to: SharePath, expect: NasStamp, purpose: Purpose): NasResult<RenameOutcome> {
      VersionPaths.requireArchive(from, to)
      return moveVersion(FakeNasOp.ARCHIVE_VERSION, share, from, to, expect)
    }

    /** 5B (P0-B, D18): l'inversa di [archiveVersion]: da `Versioni/` al solito percorso, che deve essere libero. */
    override suspend fun restoreVersion(share: String, from: SharePath, to: SharePath, expect: NasStamp, purpose: Purpose): NasResult<RenameOutcome> {
      VersionPaths.requireRestore(from, to)
      return moveVersion(FakeNasOp.RESTORE_VERSION, share, from, to, expect)
    }

    private fun moveVersion(op: FakeNasOp, share: String, from: SharePath, to: SharePath, expect: NasStamp): NasResult<RenameOutcome> =
      guarded(SimOp.RENAME, op, share, from, write = true) {
        val state = shares[key(share)] ?: return@guarded NasResult.Err(NasError.ShareMissing(share))
        // Una cartella non si sposta con questa vista: per il chiamante e' "non e' il file che mi aspettavo".
        if (node(state, from.components)?.isDirectory == true) return@guarded NasResult.Ok(RenameOutcome.SourceChanged(null))
        ops.move(state, share, from, to, expect, createParents = false)
      }

    override suspend fun space(share: String, purpose: Purpose): NasResult<NasSpaceInfo> = ops.space(share, purpose)

    private inner class BackupWriter(
      private val share: String,
      override val temp: SharePath,
      private val node: Node,
      private val content: PagedContent,
    ) : BackupTempWriter {
      @Volatile private var closed = false

      override val length: Long get() = content.size

      private fun alive(state: ShareState?): Boolean = state != null && state.nodes[key(temp.components)] === node

      override suspend fun write(offset: Long, buf: ByteArray, off: Int, len: Int): NasResult<Unit> {
        check(!closed) { "Scrittore chiuso." }
        require(offset >= 0 && off >= 0 && len >= 0 && off + len <= buf.size) { "Intervallo non valido." }
        require(offset <= content.size) { "Scrittura oltre la fine del temporaneo." }
        return guarded(SimOp.WRITE, FakeNasOp.WRITE_TEMP, share, temp, write = true) {
          val state = shares[key(share)] ?: return@guarded NasResult.Err(NasError.ShareMissing(share))
          if (!alive(state)) return@guarded NasResult.Err(NasError.NotFound)
          roomError(share, offset + len)?.let { return@guarded NasResult.Err(it) }
          content.write(offset, buf, off, len)
          backupBytesWritten.addAndGet(len.toLong())
          node.modifiedAt = nasNow()
          NasResult.Ok(Unit)
        }
      }

      override suspend fun checkpoint(): NasResult<TempPoint> {
        check(!closed) { "Scrittore chiuso." }
        return guarded(SimOp.LENGTH, FakeNasOp.WRITE_TEMP, share, temp, write = true) {
          if (!alive(shares[key(share)])) return@guarded NasResult.Err(NasError.NotFound)
          NasResult.Ok(TempPoint(content.size, node.fileId))
        }
      }

      override suspend fun finish(expectedSize: Long): NasResult<TempFinish> {
        check(!closed) { "Scrittore chiuso." }
        return guarded(SimOp.FLUSH, FakeNasOp.WRITE_TEMP, share, temp, write = true) {
          if (!alive(shares[key(share)])) return@guarded NasResult.Err(NasError.NotFound)
          val actual = content.size
          if (actual != expectedSize) return@guarded NasResult.Ok(TempFinish.SizeMismatch(actual))
          NasResult.Ok(TempFinish.Complete(stampOf(node)!!))
        }
      }

      override fun close() {
        closed = true
      }
    }
  }

  @DestructiveNasAccess
  private inner class Ops(override val ownerDeviceId: String) : NasFileOps {

    init {
      require(TrustIds.isValid(ownerDeviceId)) { "Il dispositivo proprietario e' un id valido." }
    }

    override suspend fun mkdirs(share: String, dir: SharePath, purpose: Purpose): NasResult<Unit> {
      require(NasOpsPaths.isMediaPath(dir)) { "mkdirs vale solo fuori da .mycloudgallery/." }
      return guarded(SimOp.MKDIR, FakeNasOp.MKDIRS, share, dir, write = true) {
        val state = shares[key(share)] ?: return@guarded NasResult.Err(NasError.ShareMissing(share))
        val existing = node(state, dir.components)
        when {
          existing != null && existing.isDirectory -> NasResult.Ok(Unit)
          existing != null || blockedByFile(state, dir.components) -> NasResult.Err(NasError.Exists)
          else -> {
            ensureDirs(state, dir.components, nasNow())
            NasResult.Ok(Unit)
          }
        }
      }
    }

    override suspend fun rename(
      share: String,
      from: SharePath,
      to: SharePath,
      expect: NasStamp?,
      purpose: Purpose,
    ): NasResult<RenameOutcome> {
      require(
        (NasOpsPaths.isMediaPath(from) && NasOpsPaths.isMediaPath(to)) || (NasOpsPaths.isTrashItem(from) && NasOpsPaths.isMediaPath(to)),
      ) { "Rinomina non ammessa: solo fra percorsi di media, o dal cestino al posto d'origine." }
      return guarded(SimOp.RENAME, FakeNasOp.RENAME, share, from, write = true) {
        val state = shares[key(share)] ?: return@guarded NasResult.Err(NasError.ShareMissing(share))
        move(state, share, from, to, expect, createParents = false)
      }
    }

    /** Rinomina [from] in [to] (senza sostituire); le cartelle che mancano a [to] si creano solo con [createParents]. */
    fun move(
      state: ShareState,
      share: String,
      from: SharePath,
      to: SharePath,
      expect: NasStamp?,
      createParents: Boolean,
    ): NasResult<RenameOutcome> {
      val src = node(state, from.components) ?: return NasResult.Ok(RenameOutcome.SourceMissing)
      if (lockKey(share, from.value) in locked) return NasResult.Ok(RenameOutcome.Locked)
      changedStamp(src, expect)?.let { return NasResult.Ok(RenameOutcome.SourceChanged(it)) }
      val fromKey = key(from.components)
      val toKey = key(to.components)
      // Stessa destinazione, anche solo per le maiuscole (E-N7): il chiamante passa da un nome temporaneo.
      if (toKey == fromKey || state.nodes.containsKey(toKey)) return NasResult.Ok(RenameOutcome.Collision)
      if (toKey.startsWith("$fromKey/")) return NasResult.Err(NasError.Unexpected(STATUS_INVALID_PARAMETER))
      val parents = to.components.dropLast(1)
      if (createParents) {
        if (blockedByFile(state, parents)) return NasResult.Err(NasError.Exists)
      } else if (node(state, parents)?.isDirectory != true) {
        return NasResult.Err(NasError.NotFound)
      }
      this@InMemoryNasMedia.rename(share, from.value, to.value)
      return NasResult.Ok(RenameOutcome.Renamed(stampOf(node(state, to.components))))
    }

    override suspend fun copyToTemp(
      fromShare: String,
      from: SharePath,
      toShare: String,
      temp: SharePath,
      expect: NasStamp?,
      purpose: Purpose,
    ): NasResult<CopyOutcome> {
      require(NasOpsPaths.isMediaPath(from)) { "La sorgente di una copia e' un media." }
      require(NasOpsPaths.isTempOf(temp, ownerDeviceId)) { "La destinazione e' un temporaneo di questo dispositivo." }
      return guarded(SimOp.COPY_CHUNK, FakeNasOp.COPY_TO_TEMP, fromShare, from, write = false) {
        val source = shares[key(fromShare)] ?: return@guarded NasResult.Err(NasError.ShareMissing(fromShare))
        val target = shares[key(toShare)] ?: return@guarded NasResult.Err(NasError.ShareMissing(toShare))
        if (key(toShare) in readOnlyShares) return@guarded NasResult.Err(NasError.PermissionDenied)
        if (serverCopyMode != ServerCopyMode.OK) return@guarded NasResult.Ok(CopyOutcome.NotSupported)
        val node = node(source, from.components) ?: return@guarded NasResult.Ok(CopyOutcome.SourceMissing)
        if (node.isDirectory) return@guarded NasResult.Err(NasError.Unexpected(null))
        if (lockKey(fromShare, from.value) in locked) return@guarded NasResult.Ok(CopyOutcome.Locked)
        changedStamp(node, expect)?.let { return@guarded NasResult.Ok(CopyOutcome.SourceChanged(it)) }
        if (target.nodes.containsKey(key(temp.components))) return@guarded NasResult.Ok(CopyOutcome.TempExists)
        roomError(toShare, node.content.size)?.let { return@guarded NasResult.Err(it) }
        val now = nasNow()
        ensureDirs(target, temp.components.dropLast(1), now)
        // Il contenuto e' immutabile (modify lo sostituisce): si condivide, cosi' anche un video "da 9 GB" si copia.
        val copy = Node(temp.name, false, node.content, now, now, nextFileId.incrementAndGet(), node.attributes)
        target.nodes[key(temp.components)] = copy
        touchParent(target, temp.components, now)
        serverCopies.incrementAndGet()
        NasResult.Ok(CopyOutcome.Copied(node.content.size, stampOf(copy)!!))
      }
    }

    override suspend fun openTemp(share: String, temp: SharePath, purpose: Purpose): NasResult<NasTempWriter> {
      require(NasOpsPaths.isTempOf(temp, ownerDeviceId)) { "Un temporaneo di questo dispositivo." }
      return guarded(SimOp.CREATE, FakeNasOp.OPEN_TEMP, share, temp, write = true) {
        val state = shares[key(share)] ?: return@guarded NasResult.Err(NasError.ShareMissing(share))
        if (state.nodes.containsKey(key(temp.components))) return@guarded NasResult.Err(NasError.Exists)
        val now = nasNow()
        ensureDirs(state, temp.components.dropLast(1), now)
        val content = GrowableContent()
        val node = Node(temp.name, false, content, now, now, nextFileId.incrementAndGet(), NasAttributes.ARCHIVE)
        state.nodes[key(temp.components)] = node
        touchParent(state, temp.components, now)
        NasResult.Ok(TempWriter(share, temp, node, content))
      }
    }

    override suspend fun publishTemp(share: String, temp: SharePath, final: SharePath, purpose: Purpose): NasResult<PublishOutcome> {
      require(NasOpsPaths.isTempOf(temp, ownerDeviceId)) { "Un temporaneo di questo dispositivo." }
      require(NasOpsPaths.isMediaPath(final)) { "La destinazione e' un percorso di media." }
      return guarded(SimOp.RENAME, FakeNasOp.PUBLISH_TEMP, share, temp, write = true) {
        val state = shares[key(share)] ?: return@guarded NasResult.Err(NasError.ShareMissing(share))
        if (node(state, temp.components) == null) return@guarded NasResult.Ok(PublishOutcome.TempMissing)
        when (val moved = move(state, share, temp, final, expect = null, createParents = true)) {
          is NasResult.Err -> moved
          is NasResult.Ok -> NasResult.Ok(
            when (val outcome = moved.value) {
              is RenameOutcome.Renamed -> PublishOutcome.Published(outcome.stamp)
              RenameOutcome.Collision -> PublishOutcome.Collision
              RenameOutcome.Locked, RenameOutcome.FolderInUse -> PublishOutcome.Locked
              RenameOutcome.SourceMissing, is RenameOutcome.SourceChanged -> PublishOutcome.TempMissing
            },
          )
        }
      }
    }

    override suspend fun trashMove(
      share: String,
      path: SharePath,
      trashId: String,
      expect: NasStamp?,
      purpose: Purpose,
    ): NasResult<RenameOutcome> {
      require(NasOpsPaths.isMediaPath(path)) { "Nel cestino si mette un media, mai un file dell'app." }
      require(TrustIds.isValid(trashId)) { "Un id di cestino valido." }
      return guarded(SimOp.RENAME, FakeNasOp.TRASH_MOVE, share, path, write = true) {
        val state = shares[key(share)] ?: return@guarded NasResult.Err(NasError.ShareMissing(share))
        move(state, share, path, NasLayout.trashItemFile(ownerDeviceId, trashId), expect, createParents = true)
      }
    }

    override suspend fun deleteIfUnchanged(share: String, path: SharePath, size: Long, purpose: Purpose, expect: NasStamp?): NasResult<DeleteOutcome> {
      require(NasOpsPaths.isAppArea(path)) { "L'eliminazione definitiva vale solo dentro .mycloudgallery/: un media va nel cestino." }
      require(expect == null || expect.size == size) { "Il timbro atteso ha la sua dimensione." }
      return guarded(SimOp.DELETE_ON_CLOSE, FakeNasOp.DELETE, share, path, write = true) {
        val state = shares[key(share)] ?: return@guarded NasResult.Err(NasError.ShareMissing(share))
        val node = node(state, path.components) ?: return@guarded NasResult.Ok(DeleteOutcome.Missing)
        when {
          node.isDirectory || NasAttributes.has(node.attributes, NasAttributes.REPARSE_POINT) -> NasResult.Ok(DeleteOutcome.NotAFile)
          lockKey(share, path.value) in locked -> NasResult.Ok(DeleteOutcome.Locked)
          // La dimensione (e il timbro atteso) si controllano qui, sotto lo stesso blocco: controllo e cancellazione sono lo stesso passo.
          node.content.size != size -> NasResult.Ok(DeleteOutcome.SizeChanged(node.content.size))
          changedStamp(node, expect) != null -> NasResult.Ok(DeleteOutcome.SizeChanged(node.content.size))
          else -> {
            this@InMemoryNasMedia.delete(share, path.value)
            NasResult.Ok(DeleteOutcome.Deleted)
          }
        }
      }
    }

    override suspend fun deleteOwnTemp(share: String, temp: SharePath, purpose: Purpose): NasResult<DeleteOutcome> {
      require(NasOpsPaths.isTempOf(temp, ownerDeviceId)) { "Un temporaneo di questo dispositivo." }
      return guarded(SimOp.DELETE_ON_CLOSE, FakeNasOp.DELETE, share, temp, write = true) {
        val state = shares[key(share)] ?: return@guarded NasResult.Err(NasError.ShareMissing(share))
        val node = node(state, temp.components) ?: return@guarded NasResult.Ok(DeleteOutcome.Missing)
        if (lockKey(share, temp.value) in locked) return@guarded NasResult.Ok(DeleteOutcome.Locked)
        if (node.isDirectory) return@guarded NasResult.Ok(DeleteOutcome.NotAFile)
        this@InMemoryNasMedia.delete(share, temp.value)
        NasResult.Ok(DeleteOutcome.Deleted)
      }
    }

    override suspend fun rmdirIfEmpty(share: String, dir: SharePath, purpose: Purpose): NasResult<RmdirOutcome> {
      require(NasOpsPaths.isMediaPath(dir) || NasOpsPaths.isOwnTrashTree(dir, ownerDeviceId)) {
        "rmdirIfEmpty vale fuori da .mycloudgallery/ o nel proprio cestino."
      }
      return guarded(SimOp.RMDIR, FakeNasOp.RMDIR, share, dir, write = true) {
        val state = shares[key(share)] ?: return@guarded NasResult.Err(NasError.ShareMissing(share))
        val node = node(state, dir.components) ?: return@guarded NasResult.Ok(RmdirOutcome.Missing)
        when {
          !node.isDirectory -> NasResult.Err(NasError.NotFound)
          lockKey(share, dir.value) in locked -> NasResult.Ok(RmdirOutcome.Locked)
          // Un collegamento (punto di rianalisi) non si toglie mai (R3 della revisione 4B).
          NasAttributes.has(node.attributes, NasAttributes.REPARSE_POINT) -> NasResult.Ok(RmdirOutcome.NotEmpty)
          hasChildren(state, key(dir.components)) -> NasResult.Ok(RmdirOutcome.NotEmpty)
          else -> {
            this@InMemoryNasMedia.delete(share, dir.value)
            NasResult.Ok(RmdirOutcome.Removed)
          }
        }
      }
    }

    /** Il genitore sul disco di una cartella del proprio cestino: qui non ci sono collegamenti trasparenti, e' quello del percorso. */
    override suspend fun parentIdentity(share: String, dir: SharePath, purpose: Purpose): NasResult<Long?> {
      require(NasOpsPaths.isOwnTrashTree(dir, ownerDeviceId)) { "Solo nel proprio cestino." }
      return guarded(SimOp.OPEN_DIRECTORY, FakeNasOp.LIST, share, dir, write = false) {
        val state = shares[key(share)] ?: return@guarded NasResult.Err(NasError.ShareMissing(share))
        val node = node(state, dir.components) ?: return@guarded NasResult.Err(NasError.NotFound)
        if (!node.isDirectory) return@guarded NasResult.Err(NasError.NotFound)
        val parent = dir.components.dropLast(1)
        NasResult.Ok(if (parent.isEmpty()) null else node(state, parent)?.fileId)
      }
    }

    override suspend fun space(share: String, purpose: Purpose): NasResult<NasSpaceInfo> =
      guarded(SimOp.SPACE, FakeNasOp.SPACE, share, null, write = false) {
        if (shares[key(share)] == null) NasResult.Err(NasError.ShareMissing(share)) else NasResult.Ok(spaceOf(share))
      }

    private inner class TempWriter(
      private val share: String,
      override val temp: SharePath,
      private val node: Node,
      private val content: GrowableContent,
    ) : NasTempWriter {
      @Volatile private var closed = false

      override suspend fun write(offset: Long, buf: ByteArray, off: Int, len: Int): NasResult<Unit> {
        check(!closed) { "Scrittore chiuso." }
        require(offset >= 0 && off >= 0 && len >= 0 && off + len <= buf.size) { "Intervallo non valido." }
        return guarded(SimOp.WRITE, FakeNasOp.WRITE_TEMP, share, temp, write = true) {
          val state = shares[key(share)] ?: return@guarded NasResult.Err(NasError.ShareMissing(share))
          if (state.nodes[key(temp.components)] !== node) return@guarded NasResult.Err(NasError.NotFound)
          require(offset <= content.size) { "Scrittura oltre la fine del temporaneo." }
          roomError(share, offset + len)?.let { return@guarded NasResult.Err(it) }
          content.write(offset, buf, off, len)
          node.modifiedAt = nasNow()
          NasResult.Ok(Unit)
        }
      }

      override suspend fun finish(expectedSize: Long): NasResult<TempFinish> {
        check(!closed) { "Scrittore chiuso." }
        return guarded(SimOp.FLUSH, FakeNasOp.WRITE_TEMP, share, temp, write = true) {
          val state = shares[key(share)] ?: return@guarded NasResult.Err(NasError.ShareMissing(share))
          if (state.nodes[key(temp.components)] !== node) return@guarded NasResult.Err(NasError.NotFound)
          val actual = content.size
          if (actual != expectedSize) return@guarded NasResult.Ok(TempFinish.SizeMismatch(actual))
          node.content = NasContent.of(content.snapshot())
          NasResult.Ok(TempFinish.Complete(stampOf(node)!!))
        }
      }

      override fun close() {
        closed = true
      }
    }
  }

  private inner class Reader : NasMediaReader {

    override suspend fun list(
      share: String,
      dir: SharePath?,
      purpose: Purpose,
      pageSize: Int,
      onPage: suspend (List<NasEntry>) -> Unit,
    ): NasResult<ListOutcome> {
      require(pageSize > 0) { "Pagina vuota." }
      listCalls.incrementAndGet()
      fail(FakeNasOp.LIST, share, dir)?.let { return NasResult.Err(it) }
      var entries: List<NasEntry> = emptyList()
      val listed = readGuarded(SimOp.OPEN_DIRECTORY, share) {
        synchronized(lock) {
          val state = shares[key(share)] ?: return@synchronized NasResult.Err(NasError.ShareMissing(share))
          val parts = dir?.components.orEmpty()
          val self = node(state, parts) ?: return@synchronized NasResult.Err(NasError.NotFound)
          if (!self.isDirectory) return@synchronized NasResult.Err(NasError.NotFound)
          val prefix = if (parts.isEmpty()) "" else key(parts) + "/"
          entries = state.nodes.entries
            .filter { (k, _) -> k.isNotEmpty() && k.startsWith(prefix) && '/' !in k.removePrefix(prefix) }
            .map { (_, n) -> n.toEntry() }
          NasResult.Ok(Unit)
        }
      }
      if (listed is NasResult.Err) return listed
      val ordered = shuffle?.let { entries.shuffled(it) } ?: entries
      val capped = ordered.size > NasMediaLimits.MAX_DIR_ENTRIES
      val delivered = if (capped) ordered.subList(0, NasMediaLimits.MAX_DIR_ENTRIES) else ordered
      delivered.chunked(pageSize).forEach { onPage(it) }
      return NasResult.Ok(ListOutcome(delivered.size, if (capped) NasMediaLimits.MAX_DIR_ENTRIES else null))
    }

    override suspend fun stat(share: String, path: SharePath, purpose: Purpose): NasResult<NasEntry?> {
      statCalls.incrementAndGet()
      fail(FakeNasOp.STAT, share, path)?.let { return NasResult.Err(it) }
      return readGuarded(SimOp.STAT, share) {
        synchronized(lock) {
          val state = shares[key(share)] ?: return@synchronized NasResult.Err(NasError.ShareMissing(share))
          NasResult.Ok(node(state, path.components)?.toEntry())
        }
      }
    }

    override suspend fun open(share: String, path: SharePath, purpose: Purpose): NasResult<NasRandomFile> {
      opens.incrementAndGet()
      fail(FakeNasOp.OPEN, share, path)?.let { return NasResult.Err(it) }
      return readGuarded(SimOp.OPEN_READ, share) {
        synchronized(lock) {
          val state = shares[key(share)] ?: return@synchronized NasResult.Err(NasError.ShareMissing(share))
          val node = node(state, path.components) ?: return@synchronized NasResult.Err(NasError.NotFound)
          if (node.isDirectory) return@synchronized NasResult.Err(NasError.NotFound)
          if (lockKey(share, path.value) in locked) return@synchronized NasResult.Err(NasError.FileBusy)
          NasResult.Ok(OpenFile(share, path, node.content, NasStamp(node.content.size, node.modifiedAt, node.fileId)))
        }
      }
    }

    override suspend fun readAll(file: NasRandomFile, sink: (ByteArray, Int) -> Unit, progress: ((Long) -> Unit)?): NasResult<Sha256> {
      val open = file as? OpenFile ?: return NasResult.Err(NasError.Unexpected(null))
      fail(FakeNasOp.READ_ALL, open.share, open.path)?.let { return NasResult.Err(it) }
      val digest = MessageDigest.getInstance("SHA-256")
      val buffer = ByteArray(NasMediaLimits.READ_ALL_CHUNK_BYTES)
      var offset = 0L
      try {
        while (offset < file.size) {
          val n = file.read(offset, buffer, 0, buffer.size)
          if (n <= 0) break
          digest.update(buffer, 0, n)
          sink(buffer, n)
          offset += n
          progress?.invoke(offset)
        }
      } catch (e: NasIoException) {
        return NasResult.Err(e.error)
      }
      return NasResult.Ok(Sha256(digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }))
    }
  }

  private inner class OpenFile(
    val share: String,
    val path: SharePath,
    private val content: NasContent,
    override val stamp: NasStamp,
  ) : NasRandomFile {
    @Volatile private var closed = false

    override val size: Long get() = content.size

    override suspend fun read(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
      check(!closed) { "File chiuso." }
      require(offset >= 0 && off >= 0 && len >= 0 && off + len <= buf.size) { "Intervallo non valido." }
      fail(FakeNasOp.READ, share, path)?.let { throw NasIoException(it) }
      val result = readGuarded(SimOp.READ, share) {
        if (offset >= content.size) {
          NasResult.Ok(-1)
        } else {
          val n = minOf(len.toLong(), content.size - offset).toInt()
          content.copy(offset, buf, off, n)
          bytesRead.addAndGet(n.toLong())
          NasResult.Ok(n)
        }
      }
      return when (result) {
        is NasResult.Ok -> result.value
        is NasResult.Err -> throw NasIoException(result.error)
      }
    }

    override suspend fun readRange(range: LongRange): ByteArray {
      if (range.isEmpty()) return ByteArray(0)
      val start = range.first.coerceAtLeast(0)
      val endExclusive = minOf(range.last + 1, content.size)
      if (start >= endExclusive) return ByteArray(0)
      val out = ByteArray((endExclusive - start).toInt())
      var done = 0
      while (done < out.size) {
        val n = read(start + done, out, done, out.size - done)
        if (n <= 0) break
        done += n
      }
      return if (done == out.size) out else out.copyOf(done)
    }

    override fun close() {
      closed = true
    }
  }

  // --- Interni ----------------------------------------------------------------------------------------------------------

  private fun fail(op: FakeNasOp, share: String, path: SharePath?): NasError? = failWith ?: fault?.before(op, share, path)

  private fun Node.toEntry(): NasEntry =
    NasEntry(displayName, isDirectory, if (isDirectory) 0 else content.size, modifiedAt, changedAt, fileId, attributes)

  private fun dirNode(name: String, at: Long = nasNow()): Node =
    Node(name, true, NasContent.of(ByteArray(0)), at, at, nextFileId.incrementAndGet(), NasAttributes.DIRECTORY)

  private fun shareState(name: String): ShareState = shares[key(name)] ?: error("Share sconosciuta: aggiungerla con addShare.")

  private fun node(state: ShareState, parts: List<String>): Node? = state.nodes[key(parts)]

  private fun ensureDirs(state: ShareState, parts: List<String>, at: Long) {
    for (depth in 1..parts.size) {
      val k = key(parts.subList(0, depth))
      val existing = state.nodes[k]
      if (existing == null) {
        state.nodes[k] = dirNode(parts[depth - 1], at)
        touchParent(state, parts.subList(0, depth), at)
      } else {
        require(existing.isDirectory) { "Un file sta al posto di una cartella." }
      }
    }
  }

  /** La data della cartella cambia a ogni creazione, rinomina o cancellazione dentro di essa (§5.2). */
  private fun touchParent(state: ShareState, parts: List<String>, at: Long) {
    val parent = state.nodes[key(parts.dropLast(1))] ?: return
    parent.modifiedAt = at
    parent.changedAt = at
  }

  private fun lockKey(share: String, path: String) = key(share) + ":" + key(split(path))

  private companion object {
    /** 2026-10-14T00:00:00Z: un'ora del NAS plausibile per i test che non la impostano. */
    const val DEFAULT_NOW: Long = 1_791_936_000_000L

    /** `STATUS_INVALID_PARAMETER`: per esempio spostare una cartella dentro se stessa. */
    const val STATUS_INVALID_PARAMETER: Long = 0xC000000DL

    /** Le pagine dei temporanei del backup (Fase 5A). */
    const val PAGE: Int = 64 * 1024

    fun key(name: String): String = name.lowercase(Locale.ROOT)

    fun key(parts: List<String>): String = parts.joinToString("/") { it.lowercase(Locale.ROOT) }

    fun split(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }
  }
}
