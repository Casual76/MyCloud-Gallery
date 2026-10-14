package dev.pampa.mycloudgallery.core.prepare.photo

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import androidx.compose.ui.unit.IntSize
import dev.pampa.mycloudgallery.core.imaging.budget.ImagingBudget
import dev.pampa.mycloudgallery.core.imaging.decode.DecodePlanner
import dev.pampa.mycloudgallery.core.imaging.decode.ByteArrayImageBytes
import dev.pampa.mycloudgallery.core.imaging.decode.ImageBytes
import dev.pampa.mycloudgallery.core.imaging.decode.ImageFacts
import dev.pampa.mycloudgallery.core.imaging.decode.ImageFailureException
import dev.pampa.mycloudgallery.core.imaging.decode.ImageFormat
import dev.pampa.mycloudgallery.core.imaging.decode.ImageProbe
import dev.pampa.mycloudgallery.core.imaging.decode.ImageSniffer
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.QuickHashAlgorithm
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.ThumbHash
import dev.pampa.mycloudgallery.core.prepare.codec.CodecAvailability
import dev.pampa.mycloudgallery.core.prepare.contract.DecodedFacts
import dev.pampa.mycloudgallery.core.prepare.contract.PrepBudget
import dev.pampa.mycloudgallery.core.prepare.contract.PrepFailCode
import dev.pampa.mycloudgallery.core.prepare.contract.PrepOutput
import dev.pampa.mycloudgallery.core.prepare.contract.PrepResult
import dev.pampa.mycloudgallery.core.prepare.contract.PrepSpec
import dev.pampa.mycloudgallery.core.prepare.contract.PrepareSource
import dev.pampa.mycloudgallery.core.prepare.engine.PrepGate
import dev.pampa.mycloudgallery.core.prepare.engine.PrepProbe
import dev.pampa.mycloudgallery.core.prepare.spill.TempSpill
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Un fallimento deciso in profondita' e consegnato al chiamante come [PrepResult.Failed]. */
internal class PrepAbort(val failed: PrepResult.Failed) : RuntimeException(null as String?, null as Throwable?, false, false)

internal fun abort(code: PrepFailCode, retryable: Boolean = false, decoderFamily: String? = null): PrepAbort =
  PrepAbort(PrepResult.Failed(code, retryable, decoderFamily))

/**
 * La preparazione di una FOTO (§2.4): l'originale si legge una volta (con SHA-256 e `qh1` gratis), si decodifica una volta
 * alla taglia piu' grande richiesta (orientamento, spazio colore e tetto di pixel dal `DecodePlanner` di core-imaging) e le
 * altre uscite si ricavano per riduzioni successive dalla bitmap gia' in memoria. Funziona anche per motion photo (la foto
 * ferma: il decodificatore si ferma all'EOI, la parte video resta nel file) e per GIF e WebP animati (primo fotogramma).
 */
internal class PhotoPreparer(
  private val budget: PrepBudget,
  private val gate: PrepGate,
  private val codecs: CodecAvailability,
  private val decoderFamily: String,
  private val spill: TempSpill,
  private val cpu: CoroutineDispatcher,
  private val probe: PrepProbe,
  /** Il formato e l'orientamento letti dalla testa del file ([ImageProbe]); i test lo sostituiscono per HEIC e DNG, che JVM non decodifica. */
  private val readFacts: (ImageBytes) -> ImageFacts = ImageProbe::probe,
  /** Come si decodifica un file locale (`ImageDecoder.createSource(File)`): Robolectric non lo sa fare ("Only supported on Android"), i test lo sostituiscono. */
  private val fileImages: (File) -> ImageBytes = ::FileImageBytes,
) {
  /** Il tetto di decodifica del `DecodePlanner`: la bitmap non supera quello di `PrepBudget` (che qui comanda). */
  private val imaging: ImagingBudget =
    ImagingBudget.forMemoryClass(budget.memoryClassMb, cores = budget.cores).copy(viewerBitmapBytes = budget.maxDecodedBytes)

  suspend fun prepare(source: PrepareSource, spec: PrepSpec): PrepResult {
    return try {
      when (source) {
        is PrepareSource.Bytes -> fromBytes(source, spec)
        is PrepareSource.Local -> fromFile(source, spec)
      }
    } catch (a: PrepAbort) {
      a.failed
    } catch (e: CancellationException) {
      throw e
    } catch (e: OriginUnreachableException) {
      PrepResult.Failed(PrepFailCode.NAS_UNREACHABLE, retryable = true)
    } catch (e: SourceChangedException) {
      PrepResult.Failed(PrepFailCode.SOURCE_CHANGED, retryable = true)
    } catch (e: LocalIoException) {
      PrepResult.Failed(PrepFailCode.INTERRUPTED, retryable = true)
    } catch (e: OverBudgetException) {
      PrepResult.Failed(PrepFailCode.TOO_LARGE, retryable = false)
    } catch (e: IOException) {
      // Una lettura interrotta dal sistema perche' il lavoro e' stato annullato arriva come IOException: se e' cosi', e' un annullamento.
      currentCoroutineContext().ensureActive()
      PrepResult.Failed(PrepFailCode.INTERRUPTED, retryable = true)
    } catch (e: OutOfMemoryError) {
      PrepResult.Failed(PrepFailCode.TOO_LARGE, retryable = false)
    }
  }

  // --- Origine ---------------------------------------------------------------------------------------------------

  private suspend fun fromBytes(source: PrepareSource.Bytes, spec: PrepSpec): PrepResult {
    val bytes = source.bytes
    val size = bytes.size
    if (size <= 0L) throw abort(PrepFailCode.DECODE_FAILED)
    if (size > budget.maxTempBytes) throw abort(PrepFailCode.TOO_LARGE)
    if (size <= budget.inMemoryMaxBytes) {
      val large = size > budget.largeFileBytes
      if (large) gate.large.acquire()
      try {
        val photo = PhotoIo.readAll(bytes, budget.readChunkBytes)
        val quick = QuickHashAlgorithm.ofContent(photo.bytes)
        return finish(ByteArrayImageBytes(photo.bytes), photo.sha256, quick, spec)
      } finally {
        if (large) gate.large.release()
      }
    }
    // Oltre 64 MiB: un temporaneo, con l'hash calcolato durante la copia (DNG, 200 MP, panorami).
    if (spill.usableBytes() < size + MIN_FREE_MARGIN_BYTES) throw abort(PrepFailCode.INTERRUPTED, retryable = true)
    val file = try {
      spill.create()
    } catch (e: IOException) {
      throw LocalIoException(e)
    }
    try {
      val sha = PhotoIo.spill(bytes, file, budget.readChunkBytes)
      return finish(fileImages(file), sha, PhotoIo.quickHashOf(file), spec)
    } finally {
      spill.release(file)
    }
  }

  private suspend fun fromFile(source: PrepareSource.Local, spec: PrepSpec): PrepResult {
    val file = source.file
    val size = file.length()
    if (!file.isFile) throw LocalIoException(null)
    if (size <= 0L) throw abort(PrepFailCode.DECODE_FAILED)
    if (size > budget.maxTempBytes) throw abort(PrepFailCode.TOO_LARGE)
    val (sha, quick) = PhotoIo.hashFile(file, budget.readChunkBytes)
    return finish(fileImages(file), sha, quick, spec)
  }

  private suspend fun finish(image: ImageBytes, sha: Sha256, quick: QuickHash, spec: PrepSpec): PrepResult {
    val wanted = OutputPlan.imagesOf(spec)
    // Una foto non ha fotogrammi chiave: se manca solo quella classe c'e' solo l'hash da restituire.
    if (wanted.isEmpty()) return PrepResult.Done(emptyList(), sha, facts = null, thumbHash = null, quickHash = quick)
    val produced = withContext(cpu) { produce(image, wanted, spec) }
    return PrepResult.Done(produced.images, sha, produced.facts, produced.thumbHash, quick)
  }

  // --- Decodifica e uscite ----------------------------------------------------------------------------------------

  private class Produced(val images: List<PrepOutput.Image>, val facts: DecodedFacts, val thumbHash: ThumbHash)

  private suspend fun produce(image: ImageBytes, wanted: List<SegmentClass>, spec: PrepSpec): Produced {
    val head = readHead(image)
    val facts = try {
      readFacts(image)
    } catch (e: ImageFailureException) {
      throw failureFor(head)
    } catch (e: RuntimeException) {
      throw failureFor(head)
    }
    val uprightW = facts.uprightWidth
    val uprightH = facts.uprightHeight
    val sourcePixels = uprightW.toLong() * uprightH
    if (sourcePixels <= 0 || sourcePixels > budget.maxSourcePixels) throw abort(PrepFailCode.TOO_LARGE)

    val sizes = wanted.associateWith { OutputPlan.sizeFor(it, uprightW, uprightH, spec) }
    val target = OutputPlan.largest(sizes.values)
    val heavy = sourcePixels > budget.heavyPixelThreshold
    val ledger = BitmapLedger(budget.maxDecodedBytes * LIVE_FACTOR)
    if (heavy) gate.heavy.acquire()
    try {
      return withLedger(ledger) {
        val decoded = decode(image, facts, target, ledger, head)
        val derived = ImageDerivation.derive(decoded, wanted, sizes, ledger)
        Produced(derived.images, DecodedFacts(uprightW, uprightH), derived.thumbHash)
      }
    } finally {
      ledger.freeAll()
      probe.photoPeakBytes(ledger.peakBytes)
      if (heavy) gate.heavy.release()
    }
  }

  private inline fun <T> withLedger(ledger: BitmapLedger, block: () -> T): T = try {
    block()
  } catch (e: OutOfMemoryError) {
    ledger.freeAll()
    throw abort(PrepFailCode.TOO_LARGE)
  }

  /** `ImageDecoder` alla taglia piu' grande richiesta, gia' dritta, in SDR e nello spazio colore dell'originale. */
  private fun decode(image: ImageBytes, facts: ImageFacts, target: Pair<Int, Int>, ledger: BitmapLedger, head: ByteArray): Bitmap {
    val raw = try {
      ImageDecoder.decodeBitmap(image.decoderSource()) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        // Due pixel di margine: il piano arrotonda per conto suo e potrebbe cadere un pixel sotto la taglia voluta; cosi' si decodifica
        // a quella o appena sopra (e [ImageDerivation] riduce di un pixel) e l'HQ esce sempre a 2048 sul lato lungo.
        val plan = DecodePlanner.plan(info.size.width, info.size.height, facts.manual, facts.format, IntSize(target.first + 2, target.second + 2), imaging)
        if (plan.resample) decoder.setTargetSize(plan.decoderWidth, plan.decoderHeight)
      }
    } catch (e: IOException) {
      throw failureFor(head)
    } catch (e: RuntimeException) {
      throw failureFor(head)
    }
    ledger.add(raw)
    val upright = facts.manual.applyTo(raw, recycleSource = false)
    if (upright !== raw) {
      ledger.add(upright)
      ledger.free(raw)
    }
    ImageOps.dropGainmap(upright)
    return upright
  }

  // --- Perche' non si decodifica ----------------------------------------------------------------------------------

  private fun readHead(image: ImageBytes): ByteArray = try {
    image.openStream().use { stream ->
      val buffer = ByteArray(HEAD_BYTES)
      var read = 0
      while (read < buffer.size) {
        val n = stream.read(buffer, read, buffer.size - read)
        if (n < 0) break
        read += n
      }
      buffer.copyOf(read)
    }
  } catch (e: IOException) {
    ByteArray(0)
  }

  /**
   * "Il file e' rovinato" o "qui manca il decoder" (D14)? Lo dice il formato vero, riconosciuto dai primi byte: un HEIC senza
   * decoder HEVC, un AVIF su un telefono senza AV1 e un JPEG XL sono `DECODER_MISSING` (un altro dispositivo puo' leggerli);
   * il resto e' `DECODE_FAILED` (E-M10).
   */
  private fun failureFor(head: ByteArray): PrepAbort {
    val format = ImageSniffer.sniff(head)
    val missing = when (format) {
      ImageFormat.HEIF -> !codecs.hasDecoder("video/hevc")
      ImageFormat.AVIF -> Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !codecs.hasDecoder("video/av01")
      ImageFormat.UNKNOWN -> isJpegXl(head)
      else -> false
    }
    return if (missing) abort(PrepFailCode.DECODER_MISSING, decoderFamily = decoderFamily) else abort(PrepFailCode.DECODE_FAILED)
  }

  private fun isJpegXl(head: ByteArray): Boolean {
    if (head.size >= 2 && head[0] == 0xFF.toByte() && head[1] == 0x0A.toByte()) return true
    val container = byteArrayOf(0, 0, 0, 0x0C, 'J'.code.toByte(), 'X'.code.toByte(), 'L'.code.toByte(), ' '.code.toByte())
    return head.size >= container.size && container.indices.all { head[it] == container[it] }
  }

  private companion object {
    const val HEAD_BYTES = 64

    /** Le bitmap vive insieme (decodificata + ridotta + ruotata): fino a due volte il tetto di una sola. */
    const val LIVE_FACTOR = 2L

    /** Spazio libero da lasciare oltre al temporaneo. */
    const val MIN_FREE_MARGIN_BYTES = 64L * 1024 * 1024
  }
}
