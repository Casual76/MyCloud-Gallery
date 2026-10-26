package dev.pampa.mycloudgallery.core.models.source

import dev.pampa.mycloudgallery.core.models.contract.FetchRefusal
import dev.pampa.mycloudgallery.core.models.contract.FetchResult
import dev.pampa.mycloudgallery.core.models.contract.ModelFetcher
import dev.pampa.mycloudgallery.core.models.contract.ModelLimits
import dev.pampa.mycloudgallery.core.models.contract.ModelSource
import dev.pampa.mycloudgallery.core.models.contract.ModelSpec
import dev.pampa.mycloudgallery.core.models.contract.ModelTraffic
import dev.pampa.mycloudgallery.core.models.download.PartialFile
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaLimits
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasRandomFile
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

// Le sorgenti dal NAS (docs/fase6-progetto.md §2.1, D17, D18, E-A1, E-A2): la COPIA di un dispositivo della famiglia
// (`devices/<id>/models/<sha256>`) e la CARTELLA DAL PC (`MyCloud Gallery/Modelli/<nome del catalogo>`). Solo lettura, solo con il
// `NasMediaReader` (sessione, cancello di rete del NAS e recinzione D33 li applica core-nas a ogni operazione).
//
// Difese (il NAS e' una sorgente non fidata: un dispositivo compromesso o un file rovinato ci stanno):
//  - il percorso nasce da costanti, dall'impronta del catalogo e da un id di dispositivo che DEVE avere la forma di un id (E-S11);
//  - la voce deve essere un file normale (mai una cartella ne' un collegamento) lungo ESATTAMENTE la dimensione fissata, controllata
//    prima di leggere un solo byte; si legge al piu' quella dimensione, a blocchi da 1 MiB;
//  - l'impronta non la guarda questa classe: il gestore la verifica sul file finito (un byte cambiato = file scartato, sorgente dopo).
//
// Ripresa: dal confine di blocco sotto la lunghezza del parziale (`PartialFile`), con letture a intervalli. Fino a [readAhead]
// blocchi in volo (la stessa finestra di `readAll`: 4 flussi), consegnati in ordine; al primo errore si smette e si tiene tutto cio'
// che e' gia' stato scritto.

class NasModelFetcher(
  private val reader: NasMediaReader,
  private val traffic: ModelTraffic,
  private val purpose: Purpose = Purpose.BACKGROUND,
  private val readAhead: Int = NasMediaLimits.READ_ALL_STREAMS,
  private val io: CoroutineDispatcher = Dispatchers.IO,
  private val nanos: () -> Long = System::nanoTime,
) : ModelFetcher {

  init {
    require(readAhead in 1..16) { "Finestra di lettura non valida." }
  }

  private sealed interface Located {
    class At(val share: String, val path: SharePath) : Located

    class No(val reason: String) : Located
  }

  private sealed interface BlockResult {
    class Data(val buffer: ByteArray, val length: Int) : BlockResult

    class Failed(val error: NasError?) : BlockResult
  }

  private class Pending(val length: Int, val result: Deferred<BlockResult>)

  private val pool = ArrayDeque<ByteArray>()

  override suspend fun fetch(spec: ModelSpec, source: ModelSource, partial: File, budget: Duration): FetchResult {
    val located = locate(spec, source)
    val where = when (located) {
      is Located.No -> return FetchResult.Refused(located.reason)
      is Located.At -> located
    }
    val total = spec.sizeBytes
    val offset = PartialFile.resumePoint(partial, total)
    if (offset == total) return FetchResult.Done
    val start = nanos()
    val budgetNanos = if (budget.isInfinite()) Long.MAX_VALUE else budget.inWholeNanoseconds.coerceAtLeast(0)

    // Un file normale, della dimensione fissata, prima di leggere.
    val entry = when (val stat = reader.stat(where.share, where.path, purpose)) {
      is NasResult.Err -> return mapError(stat.error, partial, total)
      is NasResult.Ok -> stat.value
    }
    if (entry == null || entry.isDirectory) return FetchResult.NotFound
    if (entry.isReparsePoint) return FetchResult.Refused(FetchRefusal.REPARSE_POINT)
    // Un'altra dimensione e' un'altra sorgente sbagliata, ma niente e' stato letto: il parziale (di un'altra sorgente) resta (R13).
    if (entry.size != total) return FetchResult.SizeMismatch

    val file = when (val opened = reader.open(where.share, where.path, purpose)) {
      is NasResult.Err -> return mapError(opened.error, partial, total)
      is NasResult.Ok -> opened.value
    }
    return file.use { transfer(spec, it, partial, offset, start, budgetNanos) }
  }

  private fun locate(spec: ModelSpec, source: ModelSource): Located =
    when (source) {
      is ModelSource.NasCopy ->
        if (source.share.isBlank() || !TrustIds.isValid(source.device)) {
          Located.No(FetchRefusal.DEVICE_ID)
        } else {
          Located.At(source.share, NasLayout.modelCopy(source.device, spec.sha256.hex))
        }
      is ModelSource.PcFolder ->
        if (source.share.isBlank()) Located.No(FetchRefusal.SOURCE_KIND)
        else Located.At(source.share, SharePath.of(*ModelLimits.PC_FOLDER.toTypedArray(), spec.fileName))
      else -> Located.No(FetchRefusal.SOURCE_KIND)
    }

  private suspend fun transfer(spec: ModelSpec, file: NasRandomFile, partial: File, offset: Long, start: Long, budgetNanos: Long): FetchResult {
    val total = spec.sizeBytes
    // Il file aperto e' lungo quanto la voce vista un attimo fa? (cambiato in mezzo = un altro file)
    if (file.size != total) return FetchResult.SizeMismatch
    val out = try {
      PartialFile.open(partial, offset)
    } catch (e: IOException) {
      return if (PartialFile.isNoSpace(e)) FetchResult.NoSpace else FetchResult.NetworkError
    }
    return out.use {
      coroutineScope {
        val window = ArrayDeque<Pending>()
        var next = offset
        try {
          while (out.length < total) {
            coroutineContext.ensureActive()
            while (window.size < readAhead && next < total) {
              val from = next
              val length = minOf(ModelLimits.BLOCK_BYTES.toLong(), total - from).toInt()
              window.addLast(Pending(length, async { readBlock(file, from, length) }))
              next += length
            }
            val pending = window.removeFirst()
            when (val block = pending.result.await()) {
              is BlockResult.Failed -> {
                return@coroutineScope block.error?.let { mapStreamError(it, out.length) } ?: interrupted(out.length)
              }
              is BlockResult.Data -> {
                try {
                  withContext(io) { out.append(block.buffer, block.length) }
                } catch (e: IOException) {
                  return@coroutineScope if (PartialFile.isNoSpace(e)) FetchResult.NoSpace else interrupted(out.length)
                } finally {
                  recycle(block.buffer)
                }
                traffic.record(block.length.toLong())
                if (out.length < total && nanos() - start >= budgetNanos) return@coroutineScope FetchResult.Partial(out.length)
              }
            }
          }
          FetchResult.Done
        } finally {
          window.forEach { it.result.cancel() }
        }
      }
    }
  }

  /** Legge esattamente [length] byte da [from]: una lettura puo' tornare con meno, quindi si ripete fino al blocco intero. */
  private suspend fun readBlock(file: NasRandomFile, from: Long, length: Int): BlockResult {
    val buffer = take()
    try {
      var done = 0
      while (done < length) {
        val n = file.read(from + done, buffer, done, length - done)
        if (n <= 0) {
          recycle(buffer)
          return BlockResult.Failed(null)
        }
        done += n
      }
      return BlockResult.Data(buffer, length)
    } catch (e: IOException) {
      recycle(buffer)
      return BlockResult.Failed((e as? NasIoException)?.error)
    }
  }

  private fun take(): ByteArray = synchronized(pool) { pool.removeLastOrNull() } ?: ByteArray(ModelLimits.BLOCK_BYTES)

  private fun recycle(buffer: ByteArray) {
    synchronized(pool) { if (pool.size < POOL_MAX) pool.addLast(buffer) }
  }

  private fun interrupted(length: Long): FetchResult = if (length > 0) FetchResult.Partial(length) else FetchResult.NetworkError

  /** Un errore del NAS prima di leggere: assente = [FetchResult.NotFound], non permesso = rifiutato, il resto "non adesso". */
  private fun mapError(error: NasError, partial: File, total: Long): FetchResult =
    when (error) {
      NasError.NotFound, is NasError.ShareMissing -> FetchResult.NotFound
      NasError.PermissionDenied -> FetchResult.Refused(FetchRefusal.PERMISSION)
      else -> interrupted(PartialFile.resumePoint(partial, total))
    }

  /** Un errore del NAS a meta' lettura: si tiene il parziale e si riprende (un file sparito nel frattempo e' un errore come gli altri). */
  private fun mapStreamError(error: NasError, length: Long): FetchResult =
    when (error) {
      NasError.PermissionDenied -> FetchResult.Refused(FetchRefusal.PERMISSION)
      else -> interrupted(length)
    }

  private companion object {
    const val POOL_MAX = 8
  }
}
