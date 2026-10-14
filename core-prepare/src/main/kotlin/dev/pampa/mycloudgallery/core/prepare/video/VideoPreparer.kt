package dev.pampa.mycloudgallery.core.prepare.video

import android.graphics.Bitmap
import dev.pampa.mycloudgallery.core.index.contract.VideoFrame
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.QuickHashAlgorithm
import dev.pampa.mycloudgallery.core.prepare.codec.CodecAvailability
import dev.pampa.mycloudgallery.core.prepare.codec.VideoCodecs
import dev.pampa.mycloudgallery.core.prepare.contract.DecodedFacts
import dev.pampa.mycloudgallery.core.prepare.contract.PrepBudget
import dev.pampa.mycloudgallery.core.prepare.contract.PrepFailCode
import dev.pampa.mycloudgallery.core.prepare.contract.PrepOutput
import dev.pampa.mycloudgallery.core.prepare.contract.PrepResult
import dev.pampa.mycloudgallery.core.prepare.contract.PrepSpec
import dev.pampa.mycloudgallery.core.prepare.contract.PrepareSource
import dev.pampa.mycloudgallery.core.prepare.engine.PrepGate
import dev.pampa.mycloudgallery.core.prepare.photo.BitmapLedger
import dev.pampa.mycloudgallery.core.prepare.photo.Derived
import dev.pampa.mycloudgallery.core.prepare.photo.FileMediaBytes
import dev.pampa.mycloudgallery.core.prepare.photo.ImageDerivation
import dev.pampa.mycloudgallery.core.prepare.photo.ImageOps
import dev.pampa.mycloudgallery.core.prepare.photo.LocalIoException
import dev.pampa.mycloudgallery.core.prepare.photo.OriginUnreachableException
import dev.pampa.mycloudgallery.core.prepare.photo.OutputPlan
import dev.pampa.mycloudgallery.core.prepare.photo.OverBudgetException
import dev.pampa.mycloudgallery.core.prepare.photo.PrepAbort
import dev.pampa.mycloudgallery.core.prepare.photo.SourceChangedException
import dev.pampa.mycloudgallery.core.prepare.photo.abort
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * La preparazione di un VIDEO (§2.4, D12, E-M7): **mai scaricato**. L'indice del contenitore (spesso in coda) e i fotogrammi
 * chiave si leggono a intervalli attraverso la cache a blocchi; il sistema (`MediaMetadataRetriever`) decide quali byte
 * servono per ogni istante. Le uscite sono la copertina, da cui si ricavano miniature, analisi e HQ come per una foto, e
 * `frames`: la copertina e un fotogramma ogni 30 secondi (da 1 a 8), in JPEG con il lato lungo a 1024 px.
 *
 * Il codec si riconosce dall'indice **prima** di chiedere un fotogramma: un video APV su un telefono senza decoder APV e'
 * `DECODER_MISSING` (D14) senza aver letto altro che l'indice.
 */
internal class VideoPreparer(
  private val budget: PrepBudget,
  private val gate: PrepGate,
  private val codecs: CodecAvailability,
  private val decoderFamily: String,
  private val grabbers: VideoFrameGrabberFactory,
  private val cpu: CoroutineDispatcher,
) {
  /** Se il sistema ruota da solo i fotogrammi (visto l'ultima volta su un video che lo permetteva): vedi [FrameOrientation]. */
  private val platformRotates = AtomicReference<Boolean?>(null)

  suspend fun prepare(source: PrepareSource, spec: PrepSpec): PrepResult {
    return try {
      when (source) {
        is PrepareSource.Bytes -> run(source.bytes, null, spec)
        is PrepareSource.Local -> {
          if (!source.file.isFile) throw LocalIoException(null)
          run(FileMediaBytes(source.file), source.file, spec)
        }
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

  private suspend fun run(bytes: MediaBytes, local: File?, spec: PrepSpec): PrepResult {
    val size = bytes.size
    if (size <= 0L) throw abort(PrepFailCode.DECODE_FAILED)
    val cache = BlockCache(bytes, budget.videoBlockBytes, max(1, budget.videoCacheBytes / budget.videoBlockBytes))
    try {
      val facts = Mp4Scout.scout(cache, budget.maxIndexBytes)
      // Registrazione interrotta o file troncato: senza indice non c'e' niente da leggere (E-M10).
      if (facts.isoBmff && !facts.hasMoov) throw abort(PrepFailCode.DECODE_FAILED)
      // Il decoder che serve a questo flusso c'e' su questo dispositivo? Se no, passa di mano (D14, E-M13).
      facts.sampleEntry?.let { entry ->
        if (VideoCodecs.isMissing(entry, codecs)) throw abort(PrepFailCode.DECODER_MISSING, decoderFamily = decoderFamily)
      }
      val quick = quickHashOf(cache, size)
      val input: VideoInput = if (local != null) VideoInput.LocalFile(local) else VideoInput.Source(BlockCacheDataSource(cache))
      val storedPixels = (facts.width ?: 0).toLong() * (facts.height ?: 0)
      val heavy = storedPixels > budget.heavyPixelThreshold
      if (heavy) gate.heavy.acquire()
      try {
        return withContext(cpu) { watched(cache) { extract(input, facts, cache, spec, quick) } }
      } finally {
        if (heavy) gate.heavy.release()
      }
    } finally {
      cache.clear()
    }
  }

  /** Il sistema non sa di coroutine: se il lavoro e' annullato le sue prossime letture falliscono subito. */
  private suspend fun <T> watched(cache: BlockCache, block: suspend () -> T): T = coroutineScope {
    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
      try {
        awaitCancellation()
      } finally {
        cache.cancelled = true
      }
    }
    try {
      block()
    } finally {
      watcher.cancel()
    }
  }

  private suspend fun quickHashOf(cache: BlockCache, size: Long): QuickHash {
    val regions = QuickHashAlgorithm.regions(size)
    val chunk = QuickHashAlgorithm.CHUNK_BYTES
    if (regions.size <= 1) {
      val all = cache.read(0, size.toInt())
      if (all.size.toLong() != size) throw SourceChangedException()
      return QuickHashAlgorithm.compute(size, all, ByteArray(0))
    }
    val head = cache.read(0, chunk)
    val tail = cache.read(size - chunk, chunk)
    if (head.size != chunk || tail.size != chunk) throw SourceChangedException()
    return QuickHashAlgorithm.compute(size, head, tail)
  }

  // --- Fotogrammi --------------------------------------------------------------------------------------------------

  private suspend fun extract(input: VideoInput, facts: ContainerFacts, cache: BlockCache, spec: PrepSpec, quick: QuickHash): PrepResult {
    val grabber = try {
      grabbers.open(input)
    } catch (e: RuntimeException) {
      cache.failure?.let { throw it }
      throw undecodable(facts)
    }
    val ledger = BitmapLedger(budget.maxDecodedBytes * LIVE_FACTOR)
    try {
      grabber.use {
        val info = grabber.info
        val durationMs = info.durationMs ?: facts.durationMs ?: 0L
        // Per decidere se il fotogramma e' ruotato fanno fede le misure del `tkhd` (memorizzate, prima della matrice).
        val storedW = facts.width ?: info.width
        val storedH = facts.height ?: info.height
        val rotation = if (facts.sampleEntry != null) facts.rotation else info.rotation
        val wantFrames = SegmentClass.FRAMES in spec.classes
        val plan = FrameTimes.plan(durationMs, spec, withKeys = wantFrames)
        val coverSide = if (SegmentClass.HQ in spec.classes) spec.hq else spec.analysis

        fun grab(timeMs: Long, side: Int): Bitmap? {
          val raw = grabber.frameAt(timeMs, side) ?: return null
          ledger.add(raw)
          val decision = FrameOrientation.decide(storedW, storedH, rotation, raw.width, raw.height, platformRotates.get())
          decision.platformRotated?.let { platformRotates.set(it) }
          ImageOps.dropGainmap(raw)
          if (decision.degrees == 0) return raw
          val rotated = FrameOrientation.rotate(raw, decision.degrees)
          ledger.add(rotated)
          ledger.free(raw)
          return rotated
        }

        var cover: Bitmap? = null
        var coverAt = 0L
        for (t in listOf(plan.coverMs) + plan.keyMs) {
          currentCoroutineContext().ensureActive()
          cover = grab(t, coverSide)
          if (cover != null) {
            coverAt = t
            break
          }
        }
        if (cover == null) {
          cache.failure?.let { throw it }
          throw undecodable(facts)
        }

        val frames = ArrayList<VideoFrame>()
        if (wantFrames) {
          frameOf(cover, coverAt, spec, ledger)?.let { frames += it }
          for (t in plan.keyMs) {
            if (t == coverAt) continue
            currentCoroutineContext().ensureActive()
            val bitmap = grab(t, spec.analysis) ?: continue
            val frame = frameOf(bitmap, t, spec, ledger)
            ledger.free(bitmap)
            if (frame != null && frames.none { it.jpeg.contentEquals(frame.jpeg) }) frames += frame
          }
          frames.sortBy { it.atMs }
        }

        // Le miniature e le anteprime dalla copertina, che si consuma qui.
        val wanted = OutputPlan.imagesOf(spec)
        val sizes = wanted.associateWith { OutputPlan.sizeFor(it, cover.width, cover.height, spec) }
        val derived = if (wanted.isEmpty()) {
          Derived(emptyList(), ImageOps.thumbHashOf(cover, ledger))
        } else {
          ImageDerivation.derive(cover, wanted, sizes, ledger)
        }
        val outputs = ArrayList<PrepOutput>(derived.images)
        if (wantFrames && frames.isNotEmpty()) outputs += PrepOutput.Frames(frames.take(TrustLimits.MAX_FRAMES_PER_MEDIA))

        val displayW = if (rotation == 90 || rotation == 270) storedH else storedW
        val displayH = if (rotation == 90 || rotation == 270) storedW else storedH
        val decoded = if (displayW != null && displayH != null) DecodedFacts(displayW, displayH, durationMs) else null
        return PrepResult.Done(outputs, fullHash = null, facts = decoded, thumbHash = derived.thumbHash, quickHash = quick)
      }
    } finally {
      ledger.freeAll()
    }
  }

  /** Un fotogramma in JPEG con il lato lungo a [PrepSpec.analysis], entro la sua parte del tetto di `frames` (2 MiB per media). */
  private fun frameOf(bitmap: Bitmap, atMs: Long, spec: PrepSpec, ledger: BitmapLedger): VideoFrame? {
    val longest = max(bitmap.width, bitmap.height)
    val scale = if (longest > spec.analysis) spec.analysis.toDouble() / longest else 1.0
    val w = max(1, (bitmap.width * scale).roundToInt())
    val h = max(1, (bitmap.height * scale).roundToInt())
    val scaled = ImageOps.scaleTo(bitmap, w, h, ledger)
    val perFrame = (TrustLimits.MAX_FRAMES_BODY_BYTES - FRAMES_OVERHEAD_BYTES) / TrustLimits.MAX_FRAMES_PER_MEDIA
    val jpeg = ImageOps.encodeJpeg(scaled, FRAME_QUALITY, perFrame)
    if (scaled !== bitmap) ledger.free(scaled)
    return jpeg?.let { VideoFrame(atMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), it) }
  }

  /** Nessun fotogramma: il decoder del flusso non e' noto, quindi non si puo' dire che manchi: e' un file che non si legge. */
  private fun undecodable(facts: ContainerFacts): PrepAbort {
    val entry = facts.sampleEntry
    return if (entry != null && VideoCodecs.isMissing(entry, codecs)) {
      abort(PrepFailCode.DECODER_MISSING, decoderFamily = decoderFamily)
    } else {
      abort(PrepFailCode.DECODE_FAILED)
    }
  }

  private companion object {
    const val FRAME_QUALITY = 75
    const val LIVE_FACTOR = 3L

    /** Il corpo `frames` ha un byte di conteggio e, per fotogramma, istante (4) e lunghezza (4). */
    const val FRAMES_OVERHEAD_BYTES = 1 + 8 * TrustLimits.MAX_FRAMES_PER_MEDIA
  }
}
