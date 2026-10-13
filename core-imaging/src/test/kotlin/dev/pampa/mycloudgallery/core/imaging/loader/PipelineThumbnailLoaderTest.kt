package dev.pampa.mycloudgallery.core.imaging.loader

import android.graphics.Bitmap
import android.os.CancellationSignal
import android.os.Looper
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.coroutines.DefaultDispatcherProvider
import dev.pampa.mycloudgallery.core.common.coroutines.DispatcherProvider
import dev.pampa.mycloudgallery.core.imaging.budget.ImagingBudget
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailSource
import dev.pampa.mycloudgallery.core.imaging.scheduler.RetryPolicy
import dev.pampa.mycloudgallery.core.imaging.testing.SyntheticPixels
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Il caricatore intero (pianificatore + cache + sorgente) con il tempo virtuale: quello che la griglia vede (D5).
 * E un test con thread veri per `prepareToDraw` fuori dal thread principale.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PipelineThumbnailLoaderTest {

  private fun request(index: Int, bucket: ThumbBucket = ThumbBucket.M, version: Long = 1): ThumbRequest = ThumbRequest(
    ThumbKey(MediaId("00000000-0000-4000-8000-%012d".format(index)), versionKey = version, bucket = bucket),
    MediaRef.Synthetic(seed = index.toLong(), width = 400, height = 300),
  )

  private val budget = ImagingBudget.forMemoryClass(256).copy(thumbnailConcurrency = 3)

  private fun TestScope.dispatchers(): DispatcherProvider {
    val dispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler)
    return object : DispatcherProvider {
      override val main = dispatcher
      override val io = dispatcher
      override val default = dispatcher
    }
  }

  /** Una sorgente che produce una bitmap dopo [latencyMs] e registra cosa le chiedono. */
  private class FakeSource(var latencyMs: Long = 100) : ThumbnailSource {
    val calls = CopyOnWriteArrayList<Pair<MediaRef, Int>>()
    val signals = CopyOnWriteArrayList<CancellationSignal>()
    val inFlight = AtomicInteger()
    val peak = AtomicInteger()
    val completed = AtomicInteger()

    override suspend fun load(ref: MediaRef, px: Int, cancel: CancellationSignal): Bitmap? {
      calls += ref to px
      signals += cancel
      val now = inFlight.incrementAndGet()
      peak.updateAndGet { maxOf(it, now) }
      try {
        delay(latencyMs)
        completed.incrementAndGet()
        return SyntheticPixels.bitmap((ref as MediaRef.Synthetic).seed, 40, 30)
      } finally {
        inFlight.decrementAndGet()
      }
    }
  }

  private fun TestScope.loader(
    source: ThumbnailSource,
    preparer: BitmapPreparer = BitmapPreparer { },
    retry: RetryPolicy = RetryPolicy(),
    withBudget: ImagingBudget = budget,
  ): PipelineThumbnailLoader {
    val dispatchers = dispatchers()
    return PipelineThumbnailLoader(
      source = source,
      budget = withBudget,
      dispatchers = dispatchers,
      scope = CoroutineScope(SupervisorJob() + dispatchers.default),
      preparer = preparer,
      clockMs = { currentTime },
      retry = retry,
    )
  }

  @Test
  fun laFinestraFaArrivareLeMiniatureEleCelleOsservanoSoltanto() = runTest {
    val source = FakeSource()
    val loader = loader(source)
    val req = request(1)
    val state = loader.image(req.key, req.ref)
    // `image` non avvia niente.
    advanceUntilIdle()
    assertThat(source.calls).isEmpty()
    assertThat(state.value).isNull()
    assertThat(loader.peek(req.key)).isNull()

    loader.window(visible = listOf(req), ahead = emptyList())
    runCurrent()
    assertThat(source.calls).hasSize(1)
    assertThat(source.calls.single().second).isEqualTo(ThumbBucket.M.px)
    advanceUntilIdle()
    // Lo stesso State che la cella teneva si e' riempito, e `peek` risponde subito.
    assertThat(state.value).isNotNull()
    assertThat(loader.peek(req.key)).isSameInstanceAs(state.value)
    assertThat(loader.stats.value.started).isEqualTo(1)
    assertThat(loader.stats.value.inFlight).isEqualTo(0)
    assertThat(loader.stats.value.cachedBytes).isGreaterThan(0)
    assertThat(loader.stats.value.budgetBytes).isEqualTo(budget.thumbnailCacheBytes)
    loader.close()
  }

  @Test
  fun laTagliaDellaChiaveDeterminaIPixelChiesti() = runTest {
    val source = FakeSource(latencyMs = 1)
    val loader = loader(source)
    loader.window(visible = ThumbBucket.entries.mapIndexed { i, b -> request(i, bucket = b) }, ahead = emptyList())
    advanceUntilIdle()
    assertThat(source.calls.map { it.second }).containsExactly(160, 320, 400)
    loader.close()
  }

  @Test
  fun unaMiniaturaGiaInCacheNonSiRicarica() = runTest {
    val source = FakeSource(latencyMs = 10)
    val loader = loader(source)
    loader.window(listOf(request(1)), emptyList())
    advanceUntilIdle()
    loader.window(listOf(request(1)), emptyList())
    loader.window(listOf(request(1), request(2)), emptyList())
    advanceUntilIdle()
    assertThat(source.calls).hasSize(2)
    loader.close()
  }

  @Test
  fun ilTettoDiConcorrenzaVaAllaSorgente() = runTest {
    val source = FakeSource(latencyMs = 50)
    val loader = loader(source)
    loader.window((0 until 40).map { request(it) }, (40 until 60).map { request(it) })
    advanceUntilIdle()
    assertThat(source.peak.get()).isAtMost(budget.thumbnailConcurrency)
    assertThat(source.completed.get()).isEqualTo(60)
    loader.close()
  }

  @Test
  fun cioCheEsceDallaFinestraFaScattareIlSegnaleDiAnnullamento() = runTest {
    val source = FakeSource(latencyMs = 10_000)
    val loader = loader(source)
    loader.window(visible = (0 until 3).map { request(it) }, ahead = emptyList())
    runCurrent()
    assertThat(source.signals).hasSize(3)
    assertThat(source.signals.none { it.isCanceled }).isTrue()

    // La finestra si sposta: le tre in volo escono e il loro CancellationSignal scatta subito.
    loader.window(visible = (10 until 13).map { request(it) }, ahead = emptyList())
    runCurrent()
    assertThat(source.signals.take(3).all { it.isCanceled }).isTrue()
    assertThat(loader.stats.value.cancelled).isEqualTo(3)
    // Una miniatura annullata non entra in cache.
    assertThat(loader.peek(request(0).key)).isNull()
    // Le nuove sono partite al posto delle vecchie.
    assertThat(source.calls.map { (it.first as MediaRef.Synthetic).seed }).containsAtLeast(10L, 11L, 12L)
    loader.close()
  }

  @Test
  fun unaSorgenteCheNonDaNienteLasciaLaCellaSulSegnapostoERiprova() = runTest {
    var attempts = 0
    val failing = object : ThumbnailSource {
      override suspend fun load(ref: MediaRef, px: Int, cancel: CancellationSignal): Bitmap? {
        attempts++
        return if (attempts < 3) null else SyntheticPixels.bitmap(1, 8, 8)
      }
    }
    val loader = loader(failing, retry = RetryPolicy(maxAttempts = 3, backoffMs = listOf(1_000, 5_000)))
    val req = request(1)
    val state = loader.image(req.key, req.ref)
    loader.window(listOf(req), emptyList())
    advanceTimeBy(100)
    assertThat(attempts).isEqualTo(1)
    assertThat(state.value).isNull()
    assertThat(loader.stats.value.failed).isEqualTo(1)
    // Senza nessun nuovo scorrimento riprova da sola.
    advanceTimeBy(1_200)
    assertThat(attempts).isEqualTo(2)
    advanceTimeBy(5_500)
    assertThat(attempts).isEqualTo(3)
    assertThat(state.value).isNotNull()
    loader.close()
  }

  @Test
  fun unFileCorrottoSiArrendeENonBloccaLeAltreCelle() = runTest {
    var corruptCalls = 0
    val source = object : ThumbnailSource {
      override suspend fun load(ref: MediaRef, px: Int, cancel: CancellationSignal): Bitmap? {
        val seed = (ref as MediaRef.Synthetic).seed
        if (seed == 9L) {
          corruptCalls++
          return null
        }
        return SyntheticPixels.bitmap(seed, 8, 8)
      }
    }
    val loader = loader(source, retry = RetryPolicy(maxAttempts = 2, backoffMs = listOf(500)))
    val corrupt = request(9)
    val ok = request(10)
    loader.window(listOf(corrupt, ok), emptyList())
    advanceTimeBy(60_000)
    loader.window(listOf(corrupt, ok), emptyList())
    advanceTimeBy(60_000)
    assertThat(corruptCalls).isEqualTo(2)
    assertThat(loader.hasGivenUp(corrupt.key)).isTrue()
    assertThat(loader.hasGivenUp(ok.key)).isFalse()
    // La cella buona e' arrivata subito, senza aspettare quella corrotta.
    assertThat(loader.peek(ok.key)).isNotNull()
    loader.close()
  }

  @Test
  fun unaEccezioneOUnaMemoriaEsauritaNonFermanoIlCaricatore() = runTest {
    var mode = "exception"
    val source = object : ThumbnailSource {
      override suspend fun load(ref: MediaRef, px: Int, cancel: CancellationSignal): Bitmap? {
        val seed = (ref as MediaRef.Synthetic).seed
        if (seed == 1L) {
          if (mode == "exception") throw IllegalStateException("boom") else throw OutOfMemoryError("finta")
        }
        return SyntheticPixels.bitmap(seed, 8, 8)
      }
    }
    val loader = loader(source)
    loader.window(listOf(request(1), request(2)), emptyList())
    advanceTimeBy(100)
    assertThat(loader.peek(request(2).key)).isNotNull()
    assertThat(loader.peek(request(1).key)).isNull()
    assertThat(loader.stats.value.failed).isEqualTo(1)

    mode = "oom"
    loader.window(listOf(request(1), request(3)), emptyList())
    advanceTimeBy(1_500)
    assertThat(loader.peek(request(3).key)).isNotNull()
    loader.close()
  }

  @Test
  fun ilTrimSvuotaAlloSfondoERichiedeCioCheServeInPrimoPiano() = runTest {
    val source = FakeSource(latencyMs = 5)
    // Un budget da quattro miniature (40 x 30 x 4 byte l'una): il trim critico (un quarto) ne tiene una sola.
    val loader = loader(source, withBudget = budget.copy(thumbnailCacheBytes = 4L * 40 * 30 * 4))
    val visible = (0 until 4).map { request(it) }
    loader.window(visible, emptyList())
    advanceUntilIdle()
    assertThat(visible.all { loader.peek(it.key) != null }).isTrue()
    assertThat(source.calls).hasSize(4)

    // Pressione critica con l'app in primo piano: le visibili perse si richiedono da sole.
    loader.trim(15)
    runCurrent()
    advanceUntilIdle()
    assertThat(source.calls.size).isGreaterThan(4)
    assertThat(visible.all { loader.peek(it.key) != null }).isTrue()

    // In secondo piano (background) la cache si svuota e NON si ricarica niente.
    val callsBefore = source.calls.size
    loader.trim(40)
    advanceUntilIdle()
    assertThat(visible.none { loader.peek(it.key) != null }).isTrue()
    assertThat(source.calls.size).isEqualTo(callsBefore)
    assertThat(loader.stats.value.cachedBytes).isEqualTo(0)

    // Al ritorno la griglia rimanda la finestra e tutto riparte.
    loader.window(visible, emptyList())
    advanceUntilIdle()
    assertThat(visible.all { loader.peek(it.key) != null }).isTrue()
    loader.close()
  }

  @Test
  fun dopoLaChiusuraNonPartePiuNiente() = runTest {
    val source = FakeSource(latencyMs = 5)
    val loader = loader(source)
    loader.close()
    loader.window(listOf(request(1)), emptyList())
    advanceUntilIdle()
    assertThat(source.calls).isEmpty()
  }

  @Test
  fun leLettureDellaCacheSiContanoComeColpiEMancati() = runTest {
    val source = FakeSource(latencyMs = 5)
    val loader = loader(source)
    val req = request(1)
    loader.image(req.key, req.ref)
    loader.window(listOf(req), emptyList())
    advanceUntilIdle()
    loader.peek(req.key)
    loader.image(req.key, req.ref)
    val stats = loader.stats.value
    assertThat(stats.misses).isAtLeast(1)
    // I colpi dopo l'ultimo evento si leggono dai contatori della prossima ripubblicazione.
    loader.window(listOf(req), emptyList())
    assertThat(loader.stats.value.memoryHits).isAtLeast(2)
    loader.close()
  }

  /** Thread veri: la preparazione della bitmap (`prepareToDraw`) non deve mai girare sul thread principale. */
  @Test
  fun prepareToDrawGiraFuoriDalThreadPrincipale() {
    val mainThread = Looper.getMainLooper().thread
    val preparedOn = CopyOnWriteArrayList<Thread>()
    val source = object : ThumbnailSource {
      override suspend fun load(ref: MediaRef, px: Int, cancel: CancellationSignal): Bitmap =
        SyntheticPixels.bitmap((ref as MediaRef.Synthetic).seed, 16, 12)
    }
    val loader = PipelineThumbnailLoader(
      source = source,
      budget = budget,
      dispatchers = DefaultDispatcherProvider,
      preparer = BitmapPreparer { preparedOn += Thread.currentThread() },
    )
    try {
      val requests = (0 until 6).map { request(it) }
      loader.window(requests, emptyList())
      runBlocking {
        withTimeout(10_000) {
          while (requests.any { loader.peek(it.key) == null }) delay(10)
        }
      }
      assertThat(preparedOn).hasSize(6)
      assertThat(preparedOn.none { it === mainThread }).isTrue()
      assertThat(preparedOn.none { it === Thread.currentThread() }).isTrue()
    } finally {
      loader.close()
    }
    // Il preparatore di piattaforma e' proprio prepareToDraw: su una bitmap software non solleva.
    BitmapPreparer.Platform.prepare(SyntheticPixels.bitmap(1, 8, 8))
  }

  /**
   * Thread veri e una chiamata che **blocca** come `loadThumbnail`: esce solo quando scatta il `CancellationSignal`.
   * Una coroutine annullata non interrompe una chiamata bloccante: il guardiano deve far scattare il segnale subito.
   */
  @Test
  fun ilSegnaleScattaAncheConUnaChiamataBloccanteDelSistema() {
    val entered = java.util.concurrent.CountDownLatch(1)
    val exited = java.util.concurrent.CountDownLatch(1)
    val canceledInside = java.util.concurrent.atomic.AtomicBoolean(false)
    val blocking = object : ThumbnailSource {
      override suspend fun load(ref: MediaRef, px: Int, cancel: CancellationSignal): Bitmap? {
        entered.countDown()
        val deadline = System.nanoTime() + 10_000_000_000L
        // Come loadThumbnail dentro il MediaProvider: nessun punto di sospensione, solo il segnale lo ferma.
        while (!cancel.isCanceled && System.nanoTime() < deadline) Thread.sleep(2)
        canceledInside.set(cancel.isCanceled)
        exited.countDown()
        return null
      }
    }
    val loader = PipelineThumbnailLoader(blocking, budget, DefaultDispatcherProvider)
    try {
      loader.window(listOf(request(1)), emptyList())
      assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue()
      assertThat(loader.stats.value.inFlight).isEqualTo(1)
      // La finestra si sposta: la richiesta esce e il segnale deve far uscire la chiamata bloccata in fretta.
      loader.window(listOf(request(2)), emptyList())
      assertThat(exited.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue()
      assertThat(canceledInside.get()).isTrue()
      assertThat(loader.stats.value.cancelled).isAtLeast(1)
    } finally {
      loader.close()
    }
  }

  @Test
  fun unaFinestraPiuGrandeDelBudgetSiFermaEnonGiraAVuoto() = runTest {
    // Cinque miniature di budget e cinquanta visibili: ne arrivano cinque (piu' al massimo quelle gia' in volo) e basta.
    val unit = 40L * 30 * 4
    val small = budget.copy(thumbnailCacheBytes = unit * 5)
    val source = FakeSource(latencyMs = 1)
    val loader = loader(source, withBudget = small)
    loader.window((0 until 50).map { request(it) }, emptyList())
    advanceUntilIdle()
    assertThat(loader.stats.value.cachedBytes).isAtMost(unit * (5 + small.thumbnailConcurrency))
    assertThat(source.completed.get()).isAtMost(5 + small.thumbnailConcurrency)
    assertThat(loader.stats.value.inFlight).isEqualTo(0)

    // Scorrere libera il posto: la nuova finestra arriva e la cache resta nel budget.
    loader.window((100 until 104).map { request(it) }, emptyList())
    advanceUntilIdle()
    assertThat((100 until 104).all { loader.peek(request(it).key) != null }).isTrue()
    assertThat(loader.stats.value.cachedBytes).isAtMost(small.thumbnailCacheBytes + unit * small.thumbnailConcurrency)
    loader.close()
  }
}
