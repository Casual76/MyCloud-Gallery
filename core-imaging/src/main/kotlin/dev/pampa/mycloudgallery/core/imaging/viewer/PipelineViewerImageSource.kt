package dev.pampa.mycloudgallery.core.imaging.viewer

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import dev.pampa.mycloudgallery.core.common.coroutines.DefaultDispatcherProvider
import dev.pampa.mycloudgallery.core.common.coroutines.DispatcherProvider
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.imaging.budget.ImagingBudget
import dev.pampa.mycloudgallery.core.imaging.contract.AnimatedDrawableSource
import dev.pampa.mycloudgallery.core.imaging.contract.AnimatedSpec
import dev.pampa.mycloudgallery.core.imaging.contract.ImageFailure
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailSource
import dev.pampa.mycloudgallery.core.imaging.contract.ViewerImageSource
import dev.pampa.mycloudgallery.core.imaging.contract.ViewerImageState
import dev.pampa.mycloudgallery.core.imaging.contract.ZoomSpec
import dev.pampa.mycloudgallery.core.imaging.decode.ImageBytes
import dev.pampa.mycloudgallery.core.imaging.decode.ImageFacts
import dev.pampa.mycloudgallery.core.imaging.decode.ImageFailureException
import dev.pampa.mycloudgallery.core.imaging.decode.ImageProbe
import dev.pampa.mycloudgallery.core.imaging.decode.ScreenImageDecoder
import dev.pampa.mycloudgallery.core.imaging.decode.TilePolicy
import dev.pampa.mycloudgallery.core.imaging.loader.BitmapPreparer
import dev.pampa.mycloudgallery.core.imaging.source.withCancellationSignal
import dev.pampa.mycloudgallery.core.model.MediaRef
import java.io.FileNotFoundException
import java.util.Collections
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext

/**
 * I contatori del viewer, per i test di budget (E-V5) e la Diagnostica. Solo numeri.
 *
 * @property inFlight decodifiche in corso adesso (non possono superare `ImagingBudget.viewerConcurrency`).
 * @property maxInFlight il massimo di decodifiche in corso osservato.
 * @property waiting pagine che aspettano uno slot di decodifica.
 * @property cancelled pagine uscite prima di avere uno stato finale (il pager le ha lasciate: E-V5).
 */
@Immutable
data class ViewerStats(
  val started: Long = 0,
  val completed: Long = 0,
  val cancelled: Long = 0,
  val failed: Long = 0,
  val unavailable: Long = 0,
  val inFlight: Int = 0,
  val maxInFlight: Int = 0,
  val waiting: Int = 0,
)

/**
 * Le immagini del viewer (3.4, D6): dall'anteprima alla versione nitida alla taglia dello schermo, **senza salti**
 * (E-V1), con la sorgente dei byte dietro [ImageOrigin] (il telefono oggi, il NAS in Fase 4).
 *
 * La sequenza di [state] per una pagina:
 * 1. [ViewerImageState.Preview]: la miniatura (da [previews]), subito, alla stessa geometria della versione nitida;
 * 2. [ViewerImageState.Loading]: si aspetta uno slot di decodifica e si apre il media;
 * 3. uno stato finale: [ViewerImageState.Sharp] (dritta, spazio colore conservato, `hdr` se c'e' la gain map),
 *    [ViewerImageState.Unavailable] (sorgente irraggiungibile: E-V1, E-V2) o [ViewerImageState.Failed] (corrotto:
 *    E-M10, memoria finita, formato sconosciuto, mancante).
 *
 * **Concorrenza (E-V5).** Al massimo `budget.viewerConcurrency` (3) pagine aprono e decodificano insieme; le altre
 * aspettano e, se il pager le lascia, **non partono mai**: il flusso si annulla, il `CancellationSignal` scatta e lo
 * slot passa alla pagina che serve. Una sorgente lenta e' un caso normale, non un'eccezione.
 *
 * Tutto il lavoro (I/O, decodifica, `prepareToDraw`) gira su thread di lavoro. [zoomSpec] e [animated] non sospendono:
 * rispondono dalla cache riempita da [state] e, se manca, leggono la testa del file solo da una sorgente locale
 * ([ImageOrigin.openLocal]) — da chiamare fuori dal thread principale.
 */
class PipelineViewerImageSource(
  private val origin: ImageOrigin,
  private val previews: ThumbnailSource,
  private val budget: ImagingBudget,
  private val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
  private val preparer: BitmapPreparer = BitmapPreparer.Platform,
  private val tiles: TilePolicy = TilePolicy(),
  private val previewPx: Int = ThumbBucket.L.px,
) : ViewerImageSource, AnimatedDrawableSource {

  private val slots = Semaphore(budget.viewerConcurrency)
  private val statsFlow = MutableStateFlow(ViewerStats())
  private val facts: MutableMap<MediaRef, ImageFacts> = lruMap(FACTS_ENTRIES)
  private val zoomSpecs: MutableMap<MediaRef, ZoomSpec> = lruMap(FACTS_ENTRIES)

  val stats: StateFlow<ViewerStats> = statsFlow

  override fun state(ref: MediaRef, screen: IntSize): Flow<ViewerImageState> = flow {
    statsFlow.update { it.copy(started = it.started + 1) }
    var terminal = false
    try {
      val preview = loadPreview(ref)
      if (preview != null) emit(ViewerImageState.Preview(preview.asImageBitmap()))
      emit(ViewerImageState.Loading(progress = null))
      val result = withSlot { produce(ref, screen, preview) }
      emit(result)
      terminal = true
    } finally {
      statsFlow.update { if (terminal) it.copy(completed = it.completed + 1) else it.copy(cancelled = it.cancelled + 1) }
    }
  }

  override fun zoomSpec(ref: MediaRef): ZoomSpec? {
    zoomSpecs[ref]?.let { return it }
    val known = factsOf(ref) ?: return null
    val spec = ZoomSpec(
      width = known.uprightWidth,
      height = known.uprightHeight,
      tiled = tiles.supportsTiles(known) { origin.openLocal(ref) },
    )
    zoomSpecs[ref] = spec
    return spec
  }

  override fun animated(ref: MediaRef): AnimatedSpec? = factsOf(ref)?.animated

  override suspend fun drawable(ref: MediaRef, maxSide: Int): AnimatedImageDrawable? {
    val opened = withContext(dispatchers.io) { origin.open(ref) } as? OriginResult.Opened ?: return null
    return opened.bytes.use { bytes ->
      withContext(dispatchers.default) {
        try {
          val known = ImageProbe.probe(bytes)
          if (known.animated == null) return@withContext null
          decodeAnimated(bytes, maxSide) as? AnimatedImageDrawable
        } catch (e: CancellationException) {
          throw e
        } catch (e: ImageFailureException) {
          null
        } catch (e: OutOfMemoryError) {
          null
        } catch (e: Exception) {
          null
        }
      }
    }
  }

  // --- La sequenza ----------------------------------------------------------------------------------------

  /** L'anteprima: la miniatura. Un errore qui non e' un errore del viewer (si passa alla versione nitida senza). */
  private suspend fun loadPreview(ref: MediaRef): Bitmap? = try {
    withCancellationSignal { signal ->
      withContext(dispatchers.io) {
        previews.load(ref, previewPx, signal)?.also { preparer.prepare(it) }
      }
    }
  } catch (e: CancellationException) {
    throw e
  } catch (e: OutOfMemoryError) {
    null
  } catch (e: Exception) {
    null
  }

  /** Esegue [block] dentro uno degli slot di decodifica; aspettare lo slot si annulla con il flusso. */
  private suspend fun <T> withSlot(block: suspend () -> T): T {
    statsFlow.update { it.copy(waiting = it.waiting + 1) }
    try {
      slots.acquire()
    } finally {
      statsFlow.update { it.copy(waiting = it.waiting - 1) }
    }
    statsFlow.update { it.copy(inFlight = it.inFlight + 1, maxInFlight = max(it.maxInFlight, it.inFlight + 1)) }
    try {
      return block()
    } finally {
      statsFlow.update { it.copy(inFlight = it.inFlight - 1) }
      slots.release()
    }
  }

  private suspend fun produce(ref: MediaRef, screen: IntSize, preview: Bitmap?): ViewerImageState {
    if (isVideo(ref)) {
      // I video li riproduce il player: qui resta il fotogramma-miniatura come immagine finale.
      return if (preview != null) {
        ViewerImageState.Sharp(preview.asImageBitmap(), hdr = false, colorSpace = preview.colorSpace)
      } else {
        fail(ImageFailure.UNSUPPORTED_FORMAT)
      }
    }
    return when (val opened = withContext(dispatchers.io) { origin.open(ref) }) {
      is OriginResult.Unreachable -> {
        statsFlow.update { it.copy(unavailable = it.unavailable + 1) }
        ViewerImageState.Unavailable(opened.reason, opened.sizeBytes)
      }
      OriginResult.Missing -> fail(ImageFailure.NOT_FOUND)
      is OriginResult.Opened -> opened.bytes.use { decode(ref, it, screen) }
    }
  }

  private suspend fun decode(ref: MediaRef, bytes: ImageBytes, screen: IntSize): ViewerImageState =
    try {
      withCancellationSignal { signal ->
        withContext(dispatchers.default) {
          val known = ImageProbe.probe(bytes)
          facts[ref] = known
          val decoded = ScreenImageDecoder.decode(bytes, known, screen, budget, signal)
          // Se la pagina e' uscita mentre si decodificava, la bitmap non si prepara ne' si consegna.
          ensureActive()
          preparer.prepare(decoded.bitmap)
          ViewerImageState.Sharp(decoded.bitmap.asImageBitmap(), decoded.hdr, decoded.colorSpace)
        }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: ImageFailureException) {
      fail(e.code)
    } catch (e: OutOfMemoryError) {
      GalleryLog.w(TAG) { "memoria esaurita nel decodificare un'immagine" }
      fail(ImageFailure.OUT_OF_MEMORY)
    } catch (e: FileNotFoundException) {
      fail(ImageFailure.NOT_FOUND)
    } catch (e: Exception) {
      GalleryLog.d(TAG) { "decodifica non riuscita: ${e.javaClass.simpleName}" }
      fail(ImageFailure.DECODE_FAILED)
    }

  private fun fail(code: ImageFailure): ViewerImageState.Failed {
    statsFlow.update { it.copy(failed = it.failed + 1) }
    return ViewerImageState.Failed(code)
  }

  private fun isVideo(ref: MediaRef): Boolean = (ref as? MediaRef.Phone)?.mime?.startsWith("video/") == true

  /** La testa del file di [ref]: dalla cache, oppure (solo da una sorgente locale) leggendola adesso. */
  private fun factsOf(ref: MediaRef): ImageFacts? {
    facts[ref]?.let { return it }
    val local = origin.openLocal(ref) ?: return null
    return try {
      local.use { ImageProbe.probe(it) }.also { facts[ref] = it }
    } catch (_: ImageFailureException) {
      null
    } catch (_: Exception) {
      null
    }
  }

  /**
   * Il disegno animato di [bytes] con il lato lungo al massimo [maxSide]: un GIF tiene in memoria piu' fotogrammi,
   * quindi anche il tetto sui pixel e' un quarto di quello di una bitmap ferma.
   */
  private fun decodeAnimated(bytes: ImageBytes, maxSide: Int): Drawable =
    ImageDecoder.decodeDrawable(bytes.decoderSource()) { decoder, info, _ ->
      val w = info.size.width
      val h = info.size.height
      var scale = 1.0
      scale = min(scale, max(maxSide, 1).toDouble() / max(w, h))
      scale = min(scale, sqrt(budget.viewerMaxPixels.toDouble() / 4.0 / (w.toDouble() * h)))
      if (scale < 1.0) decoder.setTargetSize(max(1, (w * scale).roundToInt()), max(1, (h * scale).roundToInt()))
    }

  private companion object {
    const val TAG = "ViewerImages"
    const val FACTS_ENTRIES = 64

    fun <K, V> lruMap(max: Int): MutableMap<K, V> = Collections.synchronizedMap(
      object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > max
      },
    )
  }
}
