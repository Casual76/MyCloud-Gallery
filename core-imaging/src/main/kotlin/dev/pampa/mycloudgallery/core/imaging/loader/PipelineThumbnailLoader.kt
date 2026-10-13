package dev.pampa.mycloudgallery.core.imaging.loader

import android.graphics.Bitmap
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.ImageBitmap
import dev.pampa.mycloudgallery.core.common.coroutines.DefaultDispatcherProvider
import dev.pampa.mycloudgallery.core.common.coroutines.DispatcherProvider
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.imaging.budget.ImagingBudget
import dev.pampa.mycloudgallery.core.imaging.cache.ThumbnailCache
import dev.pampa.mycloudgallery.core.imaging.cache.TrimLevels
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbStats
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailSource
import dev.pampa.mycloudgallery.core.imaging.scheduler.LoadResult
import dev.pampa.mycloudgallery.core.imaging.scheduler.RetryPolicy
import dev.pampa.mycloudgallery.core.imaging.scheduler.ThumbnailScheduler
import dev.pampa.mycloudgallery.core.imaging.source.withCancellationSignal
import dev.pampa.mycloudgallery.core.model.MediaRef
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Quello che si fa a una bitmap appena decodificata prima di darla alla cella. Di default `prepareToDraw()`: carica la
 * bitmap software nella cache delle texture della GPU **adesso**, su un thread di lavoro, e non al primo `draw` sul
 * thread di rendering (a 120 Hz un fotogramma ha 8,3 ms: il primo disegno di una bitmap non preparata e' un salto).
 */
fun interface BitmapPreparer {
  fun prepare(bitmap: Bitmap)

  companion object {
    val Platform: BitmapPreparer = BitmapPreparer { it.prepareToDraw() }
  }
}

/**
 * Il caricatore delle miniature (D5): pianificatore + cache a budget + sorgente. Implementa il contratto
 * [ThumbnailLoader]: **le celle osservano soltanto** ([image] non avvia niente), la griglia fa partire il lavoro con
 * [window] una volta per cambio di finestra, e cio' che esce dalla finestra si annulla.
 *
 * Il percorso di una miniatura:
 * 1. [window] mette la richiesta nel pianificatore (visibile prima, poi in arrivo; tetto di concorrenza);
 * 2. un lavoro chiama `ThumbnailSource.load` su un thread di I/O, con un `CancellationSignal` che scatta nel
 *    momento in cui la richiesta esce dalla finestra;
 * 3. la bitmap passa da [BitmapPreparer] (`prepareToDraw`) **sullo stesso thread di lavoro**;
 * 4. entra in cache e lo `State` della cella si aggiorna: si ricompone solo quella cella.
 *
 * Statistiche ([stats]): si ripubblicano a ogni cambio di finestra, a ogni lavoro finito e a ogni `trim`, non a ogni
 * `image` (sarebbe una scrittura per cella per ricomposizione). `memoryHits` e `misses` contano le letture della
 * cache fatte da [image] e [peek].
 *
 * @param scope dove girano i lavori. Di default un ambito proprio con `SupervisorJob` sui dispatcher di
 *   [dispatchers]; [close] lo annulla.
 */
class PipelineThumbnailLoader(
  private val source: ThumbnailSource,
  budget: ImagingBudget,
  private val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.default),
  private val preparer: BitmapPreparer = BitmapPreparer.Platform,
  clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
  retry: RetryPolicy = RetryPolicy(),
) : ThumbnailLoader, AutoCloseable {

  private val cache = ThumbnailCache(budget.thumbnailCacheBytes)
  private val statsFlow = MutableStateFlow(ThumbStats(budgetBytes = budget.thumbnailCacheBytes))
  private val windowLock = Any()
  private var lastVisible: List<ThumbRequest> = emptyList()
  private var lastAhead: List<ThumbRequest> = emptyList()
  @Volatile
  private var closed = false

  private val scheduler = ThumbnailScheduler(
    scope = scope,
    maxConcurrent = budget.thumbnailConcurrency,
    clockMs = clockMs,
    isCached = cache::touch,
    hasRoom = cache::hasRoom,
    execute = { request -> load(request) },
    retry = retry,
    onChanged = { publishStats() },
  )

  override val stats: StateFlow<ThumbStats> = statsFlow

  override fun image(key: ThumbKey, ref: MediaRef): State<ImageBitmap?> =
    cache.stateFor(key)

  override fun peek(key: ThumbKey): ImageBitmap? = cache.peek(key)

  override fun window(visible: List<ThumbRequest>, ahead: List<ThumbRequest>) {
    if (closed) return
    synchronized(windowLock) {
      lastVisible = visible
      lastAhead = ahead
    }
    cache.pin(visible.mapTo(HashSet(visible.size * 2)) { it.key }, ahead.mapTo(HashSet(ahead.size * 2)) { it.key })
    scheduler.update(visible, ahead)
  }

  override fun trim(level: Int) {
    val touchedVisible = cache.trim(level)
    // Una pressione di memoria sul serio: ai fallimenti si da' un'altra occasione quando la memoria torna.
    if (level >= TrimLevels.RUNNING_MODERATE) scheduler.clearFailures(restart = false)
    // Con l'app in primo piano le celle che hanno perso la miniatura la richiedono di nuovo; in secondo piano no:
    // la griglia rimanda la finestra quando torna visibile.
    if (touchedVisible && level < TrimLevels.UI_HIDDEN) scheduler.repump()
    publishStats()
  }

  /** Vero se la miniatura di [key] non e' arrivata dopo tutti i tentativi (la cella resta sul segnaposto). */
  fun hasGivenUp(key: ThumbKey): Boolean = scheduler.hasGivenUp(key)

  /** Annulla il lavoro in volo e svuota la cache. Dopo [close] il caricatore non fa piu' niente. */
  override fun close() {
    closed = true
    scheduler.cancelAll()
    scope.cancel()
    cache.clear()
    publishStats()
  }

  // --- Il lavoro di una miniatura --------------------------------------------------------------------------

  private suspend fun load(request: ThumbRequest): LoadResult {
    val bitmap: Bitmap? = try {
      withCancellationSignal { signal ->
        withContext(dispatchers.io) {
          val loaded = source.load(request.ref, request.key.bucket.px, signal)
          // Tutto sul thread di lavoro: se la richiesta e' uscita dalla finestra non si prepara ne' si salva.
          if (loaded != null) {
            ensureActive()
            preparer.prepare(loaded)
          }
          loaded
        }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: OutOfMemoryError) {
      // La memoria e' finita: si libera meta' della cache e la miniatura si riprova piu' tardi.
      GalleryLog.w(TAG) { "memoria esaurita nel caricare una miniatura" }
      cache.shrinkForMemoryPressure()
      return LoadResult.FAILED
    } catch (e: Exception) {
      GalleryLog.d(TAG) { "miniatura non caricata: ${e.javaClass.simpleName}" }
      return LoadResult.FAILED
    }
    currentCoroutineContext().ensureActive()
    if (bitmap == null) return LoadResult.FAILED
    cache.put(request.key, bitmap)
    publishStats()
    return LoadResult.LOADED
  }

  private fun publishStats() {
    val counters = scheduler.counters()
    val cacheCounters = cache.counters()
    statsFlow.value = ThumbStats(
      memoryHits = cacheCounters.hits,
      misses = cacheCounters.misses,
      started = counters.started,
      cancelled = counters.cancelled,
      failed = counters.failed,
      inFlight = counters.inFlight,
      cachedBytes = cacheCounters.usedBytes,
      budgetBytes = cache.budgetBytes,
    )
  }

  private companion object {
    const val TAG = "ThumbnailLoader"
  }
}
