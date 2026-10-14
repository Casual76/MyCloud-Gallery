package dev.pampa.mycloudgallery.core.nas.media

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
import dev.pampa.mycloudgallery.core.nas.fs.ABSENT
import dev.pampa.mycloudgallery.core.nas.fs.smb
import dev.pampa.mycloudgallery.core.nas.session.FailureKind
import dev.pampa.mycloudgallery.core.nas.session.NasOpException
import dev.pampa.mycloudgallery.core.nas.session.OpContext
import dev.pampa.mycloudgallery.core.nas.session.OpTicket
import dev.pampa.mycloudgallery.core.nas.session.SmbNasSessionManager
import dev.pampa.mycloudgallery.core.nas.smb.DirAccess
import dev.pampa.mycloudgallery.core.nas.smb.SmbDirEntry
import dev.pampa.mycloudgallery.core.nas.smb.SmbDirectory
import dev.pampa.mycloudgallery.core.nas.smb.SmbFileInfo
import dev.pampa.mycloudgallery.core.nas.smb.SmbReadFile
import dev.pampa.mycloudgallery.core.nas.smb.SmbStatusException
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock

/**
 * La lettura dei media del NAS sopra la sessione SMB (Fase 4, D2; `docs/fase4-progetto.md` §1.2 "core-nas (P1, 4A)").
 *
 * Ogni richiesta passa dal gestore della sessione come le altre: percorso, interruttore, limitatore (8 in volo),
 * recinzione D33, nuovi tentativi dopo una caduta, sorvegliante. Le operazioni sono **corte** (una pagina d'elenco, un
 * blocco letto): la scadenza vale per pagina o per blocco, mai per il totale (D3).
 *
 * - [list]: una cartella a pagine con lo **stesso handle** e lo stesso iteratore fra una pagina e l'altra; mai l'elenco
 *   intero in memoria (E-N5). Un guasto prima della prima pagina si riprova da capo; dopo, l'elenco si ferma con
 *   l'errore (rifarlo darebbe doppioni) e chi chiama riprova la cartella.
 * - [open]: un handle per file, con il timbro letto **sullo stesso handle**. Se la sessione cade, il file si riapre
 *   sulla nuova e si controlla che sia ancora lui (dimensione, data, `fileId`): se e' cambiato la lettura fallisce.
 * - [readAll]: 4 flussi paralleli a blocchi di 1 MiB, riordinati prima del `sink`, con lo SHA-256 calcolato in ordine;
 *   al massimo [WINDOW] blocchi in memoria; il ritmo (32 KiB/s per 20 s) lo sorveglia un biglietto del trasferimento.
 */
internal class SmbNasMediaReader(private val manager: SmbNasSessionManager) : NasMediaReader {

  override suspend fun list(
    share: String,
    dir: SharePath?,
    purpose: Purpose,
    pageSize: Int,
    onPage: suspend (List<NasEntry>) -> Unit,
  ): NasResult<ListOutcome> {
    require(pageSize > 0) { "Pagina vuota." }
    val cursor = DirCursor(dir?.smb().orEmpty())
    return manager.holding {
      try {
        var delivered = 0
        var outcome: NasResult<ListOutcome>? = null
        while (outcome == null) {
          val limit = minOf(pageSize, NasMediaLimits.MAX_DIR_ENTRIES - delivered)
          val page = manager.runOp(share, purpose, idempotent = true, touchesDisk = true, mayRetry = { delivered == 0 }) { ctx ->
            cursor.nextPage(ctx, limit)
          }
          outcome = when (page) {
            is NasResult.Err -> page
            is NasResult.Ok -> {
              val (entries, more) = page.value
              if (entries.isNotEmpty()) {
                onPage(entries)
                delivered += entries.size
              }
              when {
                !more -> NasResult.Ok(ListOutcome(delivered, null))
                delivered >= NasMediaLimits.MAX_DIR_ENTRIES -> NasResult.Ok(ListOutcome(delivered, NasMediaLimits.MAX_DIR_ENTRIES))
                else -> null
              }
            }
          }
        }
        checkNotNull(outcome)
      } finally {
        cursor.close(manager)
      }
    }
  }

  override suspend fun stat(share: String, path: SharePath, purpose: Purpose): NasResult<NasEntry?> =
    manager.runOp(share, purpose, idempotent = true, touchesDisk = true) { ctx ->
      try {
        ctx.share.stat(path.smb()).toEntry(path.name)
      } catch (e: SmbStatusException) {
        if (e.status in ABSENT) null else throw e
      }
    }

  override suspend fun open(share: String, path: SharePath, purpose: Purpose): NasResult<NasRandomFile> =
    manager.runOp(share, purpose, idempotent = true, touchesDisk = true) { ctx ->
      val file = openChecked(ctx, path)
      try {
        val stamp = stampOf(ctx, path, file)
        SmbRandomFile(manager, share, path, purpose, file, ctx.sessionKey, stamp)
      } catch (t: Throwable) {
        file.close()
        throw t
      }
    }

  override suspend fun readAll(file: NasRandomFile, sink: (ByteArray, Int) -> Unit, progress: ((Long) -> Unit)?): NasResult<Sha256> {
    val smb = file as? SmbRandomFile ?: return sequential(file, sink, progress)
    return manager.holding { parallel(smb, sink, progress) }
  }

  // --- readAll -------------------------------------------------------------------------------------------------

  private suspend fun parallel(file: SmbRandomFile, sink: (ByteArray, Int) -> Unit, progress: ((Long) -> Unit)?): NasResult<Sha256> {
    val size = file.size
    val chunk = NasMediaLimits.READ_ALL_CHUNK_BYTES
    val chunks = (size + chunk - 1) / chunk
    val digest = MessageDigest.getInstance("SHA-256")
    val transfer = manager.beginTransfer()
    try {
      val nextIndex = AtomicLong(0)
      val window = Semaphore(WINDOW)
      val lock = Mutex()
      val ready = HashMap<Long, Pair<ByteArray, Int>>()
      val pool = ArrayDeque<ByteArray>()
      var deliverNext = 0L
      var delivered = 0L
      coroutineScope {
        repeat(NasMediaLimits.READ_ALL_STREAMS) {
          launch {
            while (true) {
              window.acquire()
              val index = nextIndex.getAndIncrement()
              if (index >= chunks) {
                window.release()
                break
              }
              val buffer = lock.withLock { pool.removeFirstOrNull() } ?: ByteArray(chunk)
              val offset = index * chunk
              val want = minOf(chunk.toLong(), size - offset).toInt()
              val got = file.readFully(offset, buffer, want, transfer)
              if (got != want) throw NasIoException(NasError.Unexpected(null))
              lock.withLock {
                ready[index] = buffer to got
                while (true) {
                  val (bytes, length) = ready.remove(deliverNext) ?: break
                  digest.update(bytes, 0, length)
                  sink(bytes, length)
                  delivered += length
                  progress?.invoke(delivered)
                  deliverNext++
                  pool.addLast(bytes)
                  window.release()
                }
              }
            }
          }
        }
      }
      if (delivered != size) return NasResult.Err(NasError.Unexpected(null))
      return NasResult.Ok(Sha256(digest.digest().toHex()))
    } catch (e: NasIoException) {
      return NasResult.Err(transfer.abortedBy ?: e.error)
    } finally {
      manager.endTransfer(transfer)
    }
  }

  /** Un `NasRandomFile` che non e' nostro: un flusso solo, in ordine. */
  private suspend fun sequential(file: NasRandomFile, sink: (ByteArray, Int) -> Unit, progress: ((Long) -> Unit)?): NasResult<Sha256> {
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
    if (offset != file.size) return NasResult.Err(NasError.Unexpected(null))
    return NasResult.Ok(Sha256(digest.digest().toHex()))
  }

  companion object {
    /** Blocchi di `readAll` letti ma non ancora consegnati, al massimo (memoria: 8 MiB + i 4 in volo). */
    const val WINDOW: Int = 8
  }
}

// --- Elenco ------------------------------------------------------------------------------------------------------

/**
 * La cartella aperta di un elenco a pagine: lo stesso handle e lo stesso iteratore per tutte le pagine, finche' la
 * sessione e' la stessa. Usato da una pagina alla volta (mai in parallelo).
 */
private class DirCursor(private val path: String) {
  private var directory: SmbDirectory? = null
  private var iterator: Iterator<SmbDirEntry>? = null
  private var session: Any? = null
  private val stale = ArrayList<SmbDirectory>()

  /** Un tentativo e' fallito a meta' pagina: l'iteratore non e' piu' affidabile (voci gia' tolte e perse). */
  private var dirty = false

  /**
   * Fino a [limit] voci; `more` = ce ne sono altre dopo. Un nuovo tentativo dopo un guasto (che il chiamante concede
   * solo prima della prima pagina consegnata) riparte da capo con un handle nuovo.
   */
  fun nextPage(ctx: OpContext, limit: Int): Pair<List<NasEntry>, Boolean> {
    if (dirty) reset()
    dirty = true
    val entries = iteratorFor(ctx)
    val page = ArrayList<NasEntry>(minOf(limit, FIRST_PAGE_CAPACITY))
    while (page.size < limit && entries.hasNext()) {
      ctx.checkAborted()
      page += entries.next().toEntry()
    }
    val more = entries.hasNext()
    dirty = false
    return page to more
  }

  private fun iteratorFor(ctx: OpContext): Iterator<SmbDirEntry> {
    iterator?.let { current ->
      // Un handle vale solo sulla sessione che l'ha aperto: ricominciare dopo una pagina consegnata darebbe doppioni.
      if (session === ctx.sessionKey) return current
      throw NasOpException(NasError.SessionExpired, FailureKind.FINAL)
    }
    val opened = try {
      ctx.share.openDirectory(path, DirAccess.LIST)
    } catch (e: SmbStatusException) {
      if (e.status in ABSENT) throw NasOpException(NasError.NotFound)
      throw e
    }
    directory = opened
    session = ctx.sessionKey
    return opened.entries().also { iterator = it }
  }

  private fun reset() {
    directory?.let { stale += it }
    directory = null
    iterator = null
    session = null
  }

  fun close(manager: SmbNasSessionManager) {
    reset()
    stale.forEach { manager.closeLater(it) }
    stale.clear()
  }

  private companion object {
    const val FIRST_PAGE_CAPACITY = 1024
  }
}

// --- File aperti -------------------------------------------------------------------------------------------------

/**
 * Un file del NAS aperto per letture a intervalli. Ogni lettura e' un'operazione corta della sessione (un blocco, D3).
 * L'handle e' legato alla sessione: dopo una riconnessione il file si riapre e si confronta il timbro.
 */
internal class SmbRandomFile(
  private val manager: SmbNasSessionManager,
  private val share: String,
  private val path: SharePath,
  private val purpose: Purpose,
  first: SmbReadFile,
  firstSession: Any,
  override val stamp: NasStamp,
) : NasRandomFile {

  override val size: Long get() = stamp.size

  private val lock = Any()
  private var handle: SmbReadFile? = first
  private var handleSession: Any? = firstSession

  @Volatile
  private var closed = false

  override suspend fun read(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
    require(offset >= 0 && off >= 0 && len >= 0 && off + len <= buf.size) { "Intervallo non valido." }
    check(!closed) { "File chiuso." }
    if (offset >= size) return -1
    if (len == 0) return 0
    val want = minOf(len.toLong(), size - offset).toInt()
    return readOp(offset, buf, off, want, transfer = null)
  }

  override suspend fun readRange(range: LongRange): ByteArray {
    if (range.isEmpty()) return ByteArray(0)
    val start = range.first.coerceAtLeast(0)
    // I6 (revisione 4A): `range.last + 1` non deve traboccare, e un intervallo oltre 2 GiB non si tronca in silenzio.
    val endExclusive = if (range.last == Long.MAX_VALUE) size else minOf(range.last + 1, size)
    if (start >= endExclusive) return ByteArray(0)
    val length = endExclusive - start
    require(length <= MAX_RANGE_BYTES) { "Intervallo troppo grande per una lettura sola." }
    val out = ByteArray(length.toInt())
    var done = 0
    while (done < out.size) {
      val n = read(start + done, out, done, out.size - done)
      if (n <= 0) break
      done += n
    }
    return if (done == out.size) out else out.copyOf(done)
  }

  /** Legge esattamente [want] byte da [offset] in [buffer] (meno solo se il file finisce prima), per `readAll`. */
  suspend fun readFully(offset: Long, buffer: ByteArray, want: Int, transfer: OpTicket): Int {
    var done = 0
    while (done < want) {
      val n = readOp(offset + done, buffer, done, want - done, transfer)
      if (n <= 0) break
      done += n
    }
    return done
  }

  private suspend fun readOp(offset: Long, buf: ByteArray, off: Int, len: Int, transfer: OpTicket?): Int {
    val result = manager.runOp(
      share,
      purpose,
      idempotent = true,
      touchesDisk = true,
      mayRetry = { transfer?.abortedBy == null && !closed },
    ) { ctx ->
      transfer?.abortedBy?.let { throw NasOpException(it, FailureKind.SUSPECT) }
      val n = handleFor(ctx).read(buf, offset, off, len)
      if (n > 0 && transfer != null) manager.transferProgress(transfer, n)
      n
    }
    return when (result) {
      is NasResult.Ok -> result.value
      is NasResult.Err -> throw NasIoException(transfer?.abortedBy ?: result.error)
    }
  }

  private fun handleFor(ctx: OpContext): SmbReadFile = synchronized(lock) {
    check(!closed) { "File chiuso." }
    handle?.takeIf { handleSession === ctx.sessionKey }?.let { return it }
    // La sessione e' cambiata (caduta, riconnessione): l'handle vecchio e' morto con lei.
    handle?.let { manager.closeLater(it) }
    handle = null
    val reopened = openChecked(ctx, path)
    val now = try {
      stampOf(ctx, path, reopened)
    } catch (t: Throwable) {
      reopened.close()
      throw t
    }
    if (!now.sameAs(stamp)) {
      // Il file e' cambiato mentre lo leggevamo (modificato o sostituito dal PC): mai mescolare due versioni.
      reopened.close()
      throw NasOpException(NasError.Unexpected(null))
    }
    handle = reopened
    handleSession = ctx.sessionKey
    reopened
  }

  override fun close() {
    val toClose = synchronized(lock) {
      closed = true
      handle.also { handle = null }
    }
    toClose?.let { manager.closeLater(it) }
  }

  override fun toString(): String = "SmbRandomFile(size=$size)"
}

/** Apre [path] in lettura; assente o cartella = `NotFound`, in uso dal PC = `FileBusy` (tabella §3.4). */
private fun openChecked(ctx: OpContext, path: SharePath): SmbReadFile =
  try {
    ctx.share.openRead(path.smb())
  } catch (e: SmbStatusException) {
    if (e.status in ABSENT) throw NasOpException(NasError.NotFound)
    throw e
  }

/** Il timbro visto sull'handle; se il server non ha dato lo stato con l'apertura, una `stat` in piu'. */
private fun stampOf(ctx: OpContext, path: SharePath, file: SmbReadFile): NasStamp {
  if (file.declaredSize < 0) throw NasOpException(NasError.Unexpected(null))
  val info = file.info ?: ctx.share.stat(path.smb())
  return NasStamp(file.declaredSize, info.lastWriteMs, info.fileId)
}

private fun SmbDirEntry.toEntry(): NasEntry = NasEntry(
  name = name,
  isDirectory = isDirectory,
  size = if (isDirectory) 0 else size,
  modifiedAt = lastWriteMs,
  changedAt = changeMs,
  fileId = fileId,
  attributes = if (isDirectory) attributes or NasAttributes.DIRECTORY else attributes,
)

private fun SmbFileInfo.toEntry(requestedName: String): NasEntry = NasEntry(
  name = name ?: requestedName,
  isDirectory = isDirectory,
  size = if (isDirectory) 0 else size,
  modifiedAt = lastWriteMs,
  changedAt = changeMs,
  fileId = fileId,
  attributes = if (isDirectory) attributes or NasAttributes.DIRECTORY else attributes,
)

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 0xff) }

/** La lettura piu' lunga che `readRange` consegna in un array solo (I6): oltre si legge a blocchi con `read`. */
private const val MAX_RANGE_BYTES: Long = Int.MAX_VALUE.toLong() - 8
