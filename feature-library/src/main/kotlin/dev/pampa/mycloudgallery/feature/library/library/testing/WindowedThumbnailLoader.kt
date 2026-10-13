package dev.pampa.mycloudgallery.feature.library.library.testing

import android.os.CancellationSignal
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.pampa.mycloudgallery.core.imaging.contract.PlaceholderCache
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbStats
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailSource
import dev.pampa.mycloudgallery.core.imaging.testing.SyntheticPixels
import dev.pampa.mycloudgallery.core.imaging.testing.SyntheticThumbnailSource
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.ThumbHash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// I doppi di prova di M1 (docs/bench/M1.md): in src/main, package `testing`, mai nella release (tools/check.ps1 lo
// verifica). Servono a far girare la griglia vera su 100 mila elementi in debug PRIMA che il caricatore vero di P6 sia
// collegato: il `FakeThumbnailLoader` di core-imaging produce le bitmap in composizione e non le libera mai (a 100 mila
// elementi sono gigabyte), questo invece si comporta come il contratto chiede.

/**
 * Un caricatore di miniature **guidato dalla finestra** sopra una [ThumbnailSource] (di default la sintetica), con lo
 * stesso comportamento che `ThumbnailLoader` chiede a P6 (D5):
 *  - le celle **osservano soltanto** ([image] non avvia niente); il lavoro parte da [window], prima le visibili;
 *  - al piu' [workers] decodifiche in volo, fuori dal thread principale, con `prepareToDraw` prima di consegnare;
 *  - cio' che esce dalla finestra e non e' ancora arrivato si annulla (`CancellationSignal`);
 *  - una cache LRU con un tetto di byte, che non butta mai una miniatura della finestra corrente.
 *
 * Tutta la contabilita' gira sul thread principale (come `window`): nessun lock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WindowedThumbnailLoader(
  private val source: ThumbnailSource = SyntheticThumbnailSource(),
  private val budgetBytes: Long = DefaultBudgetBytes,
  private val workers: Int = DefaultWorkers,
  workDispatcher: CoroutineDispatcher = Dispatchers.Default,
  mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ThumbnailLoader {

  private val work = workDispatcher.limitedParallelism(workers)
  private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
  private val cache = LinkedHashMap<ThumbKey, ImageBitmap>(InitialCapacity, LoadFactor, true)
  private val states = HashMap<ThumbKey, MutableState<ImageBitmap?>>()
  private val inFlight = HashMap<ThumbKey, Job>()
  private var pending = ArrayDeque<ThumbRequest>()
  private var windowKeys: Set<ThumbKey> = emptySet()
  private var cachedBytes = 0L
  private val statsFlow = MutableStateFlow(ThumbStats(budgetBytes = budgetBytes))

  override val stats: StateFlow<ThumbStats> = statsFlow

  override fun image(key: ThumbKey, ref: MediaRef): State<ImageBitmap?> {
    val cached = cache[key]
    if (cached != null) count { copy(memoryHits = memoryHits + 1) }
    return states.getOrPut(key) { mutableStateOf(cached) }
  }

  override fun peek(key: ThumbKey): ImageBitmap? = cache[key]

  override fun window(visible: List<ThumbRequest>, ahead: List<ThumbRequest>) {
    val wanted = LinkedHashSet<ThumbKey>(visible.size + ahead.size)
    visible.forEach { wanted += it.key }
    ahead.forEach { wanted += it.key }
    windowKeys = wanted

    // Cio' che e' uscito dalla finestra e non e' ancora arrivato: si annulla.
    val iterator = inFlight.entries.iterator()
    var dropped = 0
    while (iterator.hasNext()) {
      val (key, job) = iterator.next()
      if (key !in wanted) {
        job.cancel()
        iterator.remove()
        dropped++
      }
    }
    // La finestra corrente e' la piu' recente nella LRU.
    visible.forEach { cache[it.key] }
    pending = ArrayDeque((visible + ahead).filter { it.key !in cache && it.key !in inFlight }.distinctBy { it.key })
    // Gli stati che nessuna cella puo' piu' leggere (fuori finestra e senza bitmap) si lasciano andare.
    states.keys.retainAll { it in cache || it in wanted }
    count { copy(cancelled = cancelled + dropped, misses = misses + pending.size) }
    pump()
  }

  override fun trim(level: Int) {
    val iterator = cache.entries.iterator()
    while (iterator.hasNext()) {
      val (key, image) = iterator.next()
      if (key in windowKeys) continue
      cachedBytes -= image.bytes()
      iterator.remove()
      states.remove(key)
    }
    count { copy(cachedBytes = this@WindowedThumbnailLoader.cachedBytes) }
  }

  /** Quante decodifiche sono in volo adesso (per i test). */
  val inFlightCount: Int get() = inFlight.size

  private fun pump() {
    while (inFlight.size < workers && pending.isNotEmpty()) start(pending.removeFirst())
    count { copy(inFlight = this@WindowedThumbnailLoader.inFlight.size) }
  }

  private fun start(request: ThumbRequest) {
    val signal = CancellationSignal()
    val key = request.key
    val job = scope.launch {
      val bitmap = try {
        withContext(work) {
          source.load(request.ref, key.bucket.px, signal)?.also { it.prepareToDraw() }
        }
      } catch (e: CancellationException) {
        signal.cancel()
        throw e
      }
      inFlight.remove(key)
      if (bitmap != null) put(key, bitmap.asImageBitmap()) else count { copy(failed = failed + 1) }
      pump()
    }
    inFlight[key] = job
    count { copy(started = started + 1) }
  }

  private fun put(key: ThumbKey, image: ImageBitmap) {
    cache[key]?.let { cachedBytes -= it.bytes() }
    cache[key] = image
    cachedBytes += image.bytes()
    states[key]?.value = image
    evict()
  }

  private fun evict() {
    if (cachedBytes <= budgetBytes) return
    val iterator = cache.entries.iterator()
    while (cachedBytes > budgetBytes && iterator.hasNext()) {
      val (key, image) = iterator.next()
      if (key in windowKeys) continue
      cachedBytes -= image.bytes()
      iterator.remove()
      states.remove(key)
    }
    count { copy(cachedBytes = this@WindowedThumbnailLoader.cachedBytes) }
  }

  private inline fun count(update: ThumbStats.() -> ThumbStats) {
    statsFlow.value = statsFlow.value.update().copy(cachedBytes = cachedBytes, budgetBytes = budgetBytes)
  }

  private fun ImageBitmap.bytes(): Long = asAndroidBitmap().allocationByteCount.toLong()

  companion object {
    /** Il tetto della cache delle prove: le bitmap sintetiche sono piccole (96 px), quarantotto mega sono migliaia. */
    const val DefaultBudgetBytes: Long = 48L * 1024 * 1024

    /** Le decodifiche in volo insieme (E-V5 chiede al massimo 3 al viewer; la griglia ne usa altrettante). */
    const val DefaultWorkers: Int = 3

    private const val InitialCapacity = 512
    private const val LoadFactor = 0.75f
  }
}

/**
 * I segnaposto della Libreria sintetica, con una cache **a tetto** (LRU): lo stesso gradiente 32x32 del
 * `FakePlaceholderCache` di core-imaging, che pero' non dimentica niente (a 100 mila elementi, 400 MB).
 */
class BoundedPlaceholderCache(private val maxEntries: Int = DefaultEntries) : PlaceholderCache {

  private val cache = object : LinkedHashMap<ThumbHash, ImageBitmap>(maxEntries, LoadFactor, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ThumbHash, ImageBitmap>?): Boolean = size > maxEntries
  }

  override fun image(h: ThumbHash): ImageBitmap =
    cache.getOrPut(h) { SyntheticPixels.bitmap(h.base64.hashCode().toLong(), Side, Side).asImageBitmap() }

  private companion object {
    const val DefaultEntries = 768
    const val Side = 32
    const val LoadFactor = 0.75f
  }
}
