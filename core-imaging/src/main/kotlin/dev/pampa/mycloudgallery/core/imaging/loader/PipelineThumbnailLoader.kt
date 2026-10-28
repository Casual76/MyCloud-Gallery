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
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbContext
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbResult
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbStats
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailSource
import dev.pampa.mycloudgallery.core.imaging.scheduler.LoadResult
import dev.pampa.mycloudgallery.core.imaging.scheduler.RetryPolicy
import dev.pampa.mycloudgallery.core.imaging.scheduler.ThumbnailScheduler
import dev.pampa.mycloudgallery.core.imaging.source.withCancellationSignal
import dev.pampa.mycloudgallery.core.model.MediaRef
import java.util.concurrent.ConcurrentHashMap
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
 * **Fase 4 (P7), miniature del NAS.** La sorgente riceve il contesto della richiesta ([ThumbContext]: e' visibile
 * adesso?) e puo' rispondere "non adesso" ([ThumbResult.Deferred]: non e' un fallimento) o dare una miniatura
 * **provvisoria** (EXIF, o dall'originale). Le provvisorie si ricordano: [refreshNas] le sostituisce con quelle
 * preparate appena la replica le ha, **senza** far sparire quella che la cella mostra.
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

  /** Le chiavi visibili adesso: la sorgente le chiede (`ThumbContext.isVisible`) prima di un lavoro costoso. */
  @Volatile
  private var visibleKeys: Set<ThumbKey> = emptySet()

  /** Le miniature provvisorie in cache (chiave, riferimento), le piu' recenti: [refreshNas] controlla se c'e' di meglio. */
  private val provisional = object : LinkedHashMap<ThumbKey, MediaRef>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ThumbKey, MediaRef>?): Boolean = size > MAX_PROVISIONAL
  }

  /** Le provvisorie da ricaricare: restano in cache (la cella le mostra) ma per il pianificatore non lo sono piu'. */
  private val stale: MutableSet<ThumbKey> = ConcurrentHashMap.newKeySet()

  private val scheduler = ThumbnailScheduler(
    scope = scope,
    maxConcurrent = budget.thumbnailConcurrency,
    clockMs = clockMs,
    isCached = { key -> cache.touch(key) && key !in stale },
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
    val visibleSet = visible.mapTo(HashSet(visible.size * 2)) { it.key }
    visibleKeys = visibleSet
    cache.pin(visibleSet, ahead.mapTo(HashSet(ahead.size * 2)) { it.key })
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

  /**
   * L'app torna in primo piano (GA-3). Un `onTrimMemory` da `UI_HIDDEN` in su scarta anche le miniature visibili e, con l'app in
   * secondo piano, non le richiede (niente lavoro che nessuno vede): al ritorno la finestra e' la stessa, ma nessuno la rimanda, e le
   * celle restavano sul segnaposto finche' non si scorreva. Qui si riapplica la finestra che il pianificatore ricorda: parte solo cio'
   * che manca dalla cache (nessun costo se non e' stato scartato niente).
   */
  override fun resume() {
    if (closed) return
    scheduler.repump()
    publishStats()
  }

  /**
   * Fase 4 (P7): la replica ha ricevuto blob nuovi, oppure il NAS e' di nuovo raggiungibile. Ogni miniatura
   * **provvisoria** per cui adesso c'e' di meglio ([ThumbnailSource.canUpgrade]) si ricarica, e quella mostrata dalla
   * cella resta finche' non arriva la nuova (nessun lampeggio); i fallimenti e le attese si dimenticano, cosi' le celle
   * che non avevano niente (non preparate, NAS spento) ritentano. Si chiama dopo un giro di sincronizzazione che ha
   * scaricato segmenti e quando il NAS torna raggiungibile; costa un controllo in memoria per miniatura provvisoria.
   *
   * @return quante miniature provvisorie verranno sostituite.
   */
  fun refreshNas(): Int {
    if (closed) return 0
    val candidates = synchronized(provisional) { provisional.entries.map { it.key to it.value } }
    var upgraded = 0
    for ((key, ref) in candidates) {
      if (!source.canUpgrade(ref, key.bucket.px)) continue
      stale += key
      synchronized(provisional) { provisional.remove(key) }
      upgraded++
    }
    scheduler.clearFailures(restart = true)
    publishStats()
    return upgraded
  }

  override fun settled(key: ThumbKey): Boolean = cache.touch(key) || scheduler.answeredWithout(key)

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
    val key = request.key
    val result: ThumbResult? = try {
      withCancellationSignal { signal ->
        withContext(dispatchers.io) {
          val context = ThumbContext { key in visibleKeys }
          val loaded = source.loadResult(request.ref, key.bucket.px, signal, context)
          // Tutto sul thread di lavoro: se la richiesta e' uscita dalla finestra non si prepara ne' si salva.
          if (loaded is ThumbResult.Loaded) {
            ensureActive()
            preparer.prepare(loaded.bitmap)
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
    return when (result) {
      null -> LoadResult.FAILED
      ThumbResult.Deferred -> LoadResult.DEFERRED
      is ThumbResult.Loaded -> {
        cache.put(key, result.bitmap)
        stale -= key
        synchronized(provisional) { if (result.provisional) provisional[key] = request.ref else provisional.remove(key) }
        publishStats()
        LoadResult.LOADED
      }
    }
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
    const val MAX_PROVISIONAL = 2048
  }
}
