package dev.pampa.mycloudgallery.core.imaging.viewer.testing

import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.contract.ListOutcome
import dev.pampa.mycloudgallery.core.nas.contract.NasEntry
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasRandomFile
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay

/**
 * Un NAS **lento, fermo o irraggiungibile** per provare cio' che legge i media a intervalli (il viewer di Fase 4, E-V1,
 * E-V2, E-V5, E-V6, E-L4). Avvolge un [NasMediaReader] vero (di norma `InMemoryNasMedia.reader`) e ci aggiunge cio' che il
 * simulatore base non sa fare:
 *  - [openDelayMs] e [chunkDelayMs]: latenza di apertura e di ogni blocco (`delay`: tempo virtuale in `runTest`, tempo
 *    vero in `runBlocking`);
 *  - [openError]: l'apertura fallisce (NAS spento, rete non ammessa, file sparito...);
 *  - [stallAfterBytes]: dopo tanti byte consegnati per file aperto la lettura **non risponde piu'**; con [stallError] la
 *    lettura fallisce con quell'errore dopo [stallErrorAfterMs] (il sorvegliante a progresso di `core-nas` che dice
 *    `Stalled`), altrimenti resta sospesa finche' non la si annulla;
 *  - [failReadsAfterBytes] e [failReadsWith]: la lettura cade a meta' con un errore;
 *  - [shortBy]: il file dichiara una dimensione ma ne consegna meno (modificato dal PC mentre si legge).
 *
 * Conta le aperture, i file aperti adesso e i byte consegnati: "il lettore non e' rimasto aperto" e "i video non si
 * scaricano interi" si provano con i contatori. Vive in `testing`: nel dex di release non entra (`tools/check.ps1`).
 */
class ScriptedNasReader(private val delegate: NasMediaReader) : NasMediaReader {

  @Volatile var openDelayMs: Long = 0

  @Volatile var chunkDelayMs: Long = 0

  @Volatile var openError: NasError? = null

  @Volatile var stallAfterBytes: Long = Long.MAX_VALUE

  @Volatile var stallError: NasError? = null

  @Volatile var stallErrorAfterMs: Long = 0

  @Volatile var failReadsAfterBytes: Long = Long.MAX_VALUE

  @Volatile var failReadsWith: NasError = NasError.Timeout

  @Volatile var shortBy: Long = 0

  /** La dimensione dei blocchi di `readAll` (e quindi la granularita' dell'avanzamento). */
  @Volatile var readAllChunk: Int = 64 * 1024

  val opens = AtomicInteger(0)
  val openFiles = AtomicInteger(0)
  val bytesDelivered = AtomicLong(0)
  val reads = AtomicInteger(0)

  override suspend fun list(
    share: String,
    dir: SharePath?,
    purpose: Purpose,
    pageSize: Int,
    onPage: suspend (List<NasEntry>) -> Unit,
  ): NasResult<ListOutcome> = delegate.list(share, dir, purpose, pageSize, onPage)

  override suspend fun stat(share: String, path: SharePath, purpose: Purpose): NasResult<NasEntry?> = delegate.stat(share, path, purpose)

  override suspend fun open(share: String, path: SharePath, purpose: Purpose): NasResult<NasRandomFile> {
    opens.incrementAndGet()
    if (openDelayMs > 0) delay(openDelayMs)
    openError?.let { return NasResult.Err(it) }
    return when (val opened = delegate.open(share, path, purpose)) {
      is NasResult.Err -> opened
      is NasResult.Ok -> {
        openFiles.incrementAndGet()
        NasResult.Ok(ScriptedFile(opened.value))
      }
    }
  }

  override suspend fun readAll(file: NasRandomFile, sink: (ByteArray, Int) -> Unit, progress: ((Long) -> Unit)?): NasResult<Sha256> {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(readAllChunk)
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

  private inner class ScriptedFile(private val inner: NasRandomFile) : NasRandomFile {
    private var delivered = 0L
    private var closed = false

    override val size: Long get() = inner.size

    override val stamp: NasStamp get() = inner.stamp

    override suspend fun read(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
      reads.incrementAndGet()
      if (chunkDelayMs > 0) delay(chunkDelayMs)
      if (delivered >= stallAfterBytes) {
        val error = stallError ?: awaitCancellation()
        if (stallErrorAfterMs > 0) delay(stallErrorAfterMs)
        throw NasIoException(error)
      }
      if (delivered >= failReadsAfterBytes) throw NasIoException(failReadsWith)
      val limit = (inner.size - shortBy).coerceAtLeast(0)
      if (offset >= limit) return -1
      val room = minOf(len.toLong(), limit - offset).toInt()
      val n = inner.read(offset, buf, off, room)
      if (n > 0) {
        delivered += n
        bytesDelivered.addAndGet(n.toLong())
      }
      return n
    }

    override suspend fun readRange(range: LongRange): ByteArray {
      if (range.isEmpty()) return ByteArray(0)
      val start = range.first.coerceAtLeast(0)
      val endExclusive = minOf(range.last + 1, size)
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
      if (closed) return
      closed = true
      openFiles.decrementAndGet()
      inner.close()
    }
  }
}
