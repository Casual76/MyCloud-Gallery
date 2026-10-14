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
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

// Il NAS finto dei MEDIA della Fase 4 (P0): uno scheletro funzionante del §5.2 di docs/fase4-progetto.md, abbastanza per
// far lavorare P4, P5, P7 e P8 senza P1. Il simulatore fedele (dietro SmbTransport, con FaultPlan "prima/dopo che il
// server l'abbia applicata", latenze a tempo virtuale, dischi in standby, quote) e' di P1: estendera'
// InMemorySmbBackend. Questo vive SOPRA il contratto NasMediaReader, quindi non prova nulla di SMB.

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
enum class FakeNasOp { LIST, STAT, OPEN, READ, READ_ALL, SERVER_COPY }

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

  // --- Cio' che vede l'app -------------------------------------------------------------------------------------------

  /** Il lettore dei media sopra questo NAS. */
  val reader: NasMediaReader = Reader()

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
      val entries = synchronized(lock) {
        val state = shares[key(share)] ?: return NasResult.Err(NasError.ShareMissing(share))
        val parts = dir?.components.orEmpty()
        val self = node(state, parts) ?: return NasResult.Err(NasError.NotFound)
        if (!self.isDirectory) return NasResult.Err(NasError.NotFound)
        val prefix = if (parts.isEmpty()) "" else key(parts) + "/"
        state.nodes.entries
          .filter { (k, _) -> k.isNotEmpty() && k.startsWith(prefix) && '/' !in k.removePrefix(prefix) }
          .map { (_, n) -> n.toEntry() }
      }
      val ordered = shuffle?.let { entries.shuffled(it) } ?: entries
      val capped = ordered.size > NasMediaLimits.MAX_DIR_ENTRIES
      val delivered = if (capped) ordered.subList(0, NasMediaLimits.MAX_DIR_ENTRIES) else ordered
      delivered.chunked(pageSize).forEach { onPage(it) }
      return NasResult.Ok(ListOutcome(delivered.size, if (capped) NasMediaLimits.MAX_DIR_ENTRIES else null))
    }

    override suspend fun stat(share: String, path: SharePath, purpose: Purpose): NasResult<NasEntry?> {
      statCalls.incrementAndGet()
      fail(FakeNasOp.STAT, share, path)?.let { return NasResult.Err(it) }
      return synchronized(lock) {
        val state = shares[key(share)] ?: return NasResult.Err(NasError.ShareMissing(share))
        NasResult.Ok(node(state, path.components)?.toEntry())
      }
    }

    override suspend fun open(share: String, path: SharePath, purpose: Purpose): NasResult<NasRandomFile> {
      opens.incrementAndGet()
      fail(FakeNasOp.OPEN, share, path)?.let { return NasResult.Err(it) }
      return synchronized(lock) {
        val state = shares[key(share)] ?: return NasResult.Err(NasError.ShareMissing(share))
        val node = node(state, path.components) ?: return NasResult.Err(NasError.NotFound)
        if (node.isDirectory) return NasResult.Err(NasError.NotFound)
        if (lockKey(share, path.value) in locked) return NasResult.Err(NasError.FileBusy)
        NasResult.Ok(OpenFile(share, path, node.content, NasStamp(node.content.size, node.modifiedAt, node.fileId)))
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
      if (offset >= content.size) return -1
      val n = minOf(len.toLong(), content.size - offset).toInt()
      content.copy(offset, buf, off, n)
      bytesRead.addAndGet(n.toLong())
      return n
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

    fun key(name: String): String = name.lowercase(Locale.ROOT)

    fun key(parts: List<String>): String = parts.joinToString("/") { it.lowercase(Locale.ROOT) }

    fun split(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }
  }
}
