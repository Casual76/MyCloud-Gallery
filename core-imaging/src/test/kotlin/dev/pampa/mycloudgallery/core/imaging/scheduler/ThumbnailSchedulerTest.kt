package dev.pampa.mycloudgallery.core.imaging.scheduler

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Il pianificatore con il **tempo virtuale** (D5, `docs/fase3-progetto.md` §4.3 P6): priorita', finestra, anticipo,
 * annullamento e tetto di concorrenza, senza un thread vero ne' un'attesa vera.
 */
class ThumbnailSchedulerTest {

  private fun request(index: Int, bucket: ThumbBucket = ThumbBucket.M): ThumbRequest = ThumbRequest(
    ThumbKey(MediaId("00000000-0000-4000-8000-%012d".format(index)), versionKey = 1, bucket = bucket),
    MediaRef.Synthetic(seed = index.toLong(), width = 400, height = 300),
  )

  private fun requests(range: IntRange): List<ThumbRequest> = range.map(::request)

  /** Un banco di prova: lavori che durano [workMs] di tempo virtuale e registrano cio' che succede. */
  private class Bench(
    val testScope: TestScope,
    cap: Int,
    val workMs: (ThumbRequest) -> Long = { 100 },
    val outcome: (ThumbRequest, Int) -> LoadResult = { _, _ -> LoadResult.LOADED },
    retry: RetryPolicy = RetryPolicy(),
  ) {
    // Non `backgroundScope`: `advanceUntilIdle` non aspetta il lavoro di sfondo, e qui serve proprio che aspetti.
    private val job = SupervisorJob()
    val scope: CoroutineScope = CoroutineScope(StandardTestDispatcher(testScope.testScheduler) + job)

    fun close() = job.cancel()

    val started = mutableListOf<ThumbKey>()
    val finished = mutableListOf<ThumbKey>()
    val cancelledKeys = mutableListOf<ThumbKey>()
    val cached = mutableSetOf<ThumbKey>()
    val attempts = HashMap<ThumbKey, Int>()
    var active = 0
    var peakActive = 0

    /** Le chiavi della finestra corrente, per verificare che nessun lavoro parta per qualcosa che non si vede piu'. */
    var windowNow: Set<ThumbKey> = emptySet()
    val startedOutsideWindow = mutableListOf<ThumbKey>()

    val scheduler = ThumbnailScheduler(
      scope = scope,
      maxConcurrent = cap,
      clockMs = { testScope.currentTime },
      isCached = { it in cached },
      execute = { req ->
        started += req.key
        if (req.key !in windowNow) startedOutsideWindow += req.key
        active++
        peakActive = maxOf(peakActive, active)
        try {
          delay(workMs(req))
          val attempt = (attempts[req.key] ?: 0) + 1
          attempts[req.key] = attempt
          val result = outcome(req, attempt)
          if (result == LoadResult.LOADED) cached += req.key
          finished += req.key
          result
        } catch (e: CancellationException) {
          cancelledKeys += req.key
          throw e
        } finally {
          active--
        }
      },
      retry = retry,
    )

    fun window(visible: List<ThumbRequest>, ahead: List<ThumbRequest> = emptyList()) {
      windowNow = (visible + ahead).map { it.key }.toSet()
      scheduler.update(visible, ahead)
    }
  }

  @Test
  fun leVisibiliPartonoPrimaDelleInArrivo() = runTest {
    val bench = Bench(this, cap = 1)
    bench.window(visible = requests(0..1), ahead = requests(2..3))
    runCurrent()
    // Con un solo slot parte la prima visibile; le altre aspettano il loro turno, nell'ordine ricevuto.
    assertThat(bench.started).containsExactly(request(0).key)
    advanceUntilIdle()
    assertThat(bench.started).containsExactly(request(0).key, request(1).key, request(2).key, request(3).key).inOrder()
    assertThat(bench.peakActive).isEqualTo(1)
  }

  @Test
  fun loSlotRiservatoTieneLibereLeCelleVisibili() = runTest {
    val bench = Bench(this, cap = 4, workMs = { 1_000 })
    // Solo lavoro in arrivo: ne parte al massimo cap - 1.
    bench.window(visible = emptyList(), ahead = requests(0..9))
    runCurrent()
    assertThat(bench.scheduler.counters().inFlight).isEqualTo(3)

    // Compare una cella visibile: usa subito lo slot riservato, senza aspettare che finisca il lavoro speculativo.
    bench.window(visible = listOf(request(100)), ahead = requests(0..9))
    runCurrent()
    assertThat(bench.scheduler.counters().inFlight).isEqualTo(4)
    assertThat(bench.started).contains(request(100).key)
    advanceUntilIdle()
    assertThat(bench.peakActive).isAtMost(4)
  }

  @Test
  fun ilTettoDiConcorrenzaNonSiSuperaMai() = runTest {
    for (cap in 1..4) {
      val bench = Bench(this, cap = cap, workMs = { 50 })
      bench.window(visible = requests(0..30), ahead = requests(31..60))
      advanceUntilIdle()
      assertThat(bench.peakActive).isAtMost(cap)
      assertThat(bench.scheduler.counters().peakInFlight).isAtMost(cap)
      assertThat(bench.finished).hasSize(61)
    }
  }

  @Test
  fun cioCheEsceDallaFinestraSiAnnulla() = runTest {
    val bench = Bench(this, cap = 2, workMs = { 1_000 })
    bench.window(visible = requests(0..3))
    runCurrent()
    assertThat(bench.started).containsExactly(request(0).key, request(1).key).inOrder()

    // L'utente scorre: restano visibili solo la 2 e la 3. Le due in volo si annullano, le altre partono.
    bench.window(visible = requests(2..3))
    runCurrent()
    assertThat(bench.cancelledKeys).containsExactly(request(0).key, request(1).key)
    assertThat(bench.scheduler.counters().cancelled).isEqualTo(2)
    assertThat(bench.started).containsAtLeast(request(2).key, request(3).key)
    advanceUntilIdle()
    // La 0 e la 1 non sono mai state caricate; la 2 e la 3 si.
    assertThat(bench.cached).containsExactly(request(2).key, request(3).key)
  }

  @Test
  fun unaRichiestaInCodaCheEsceNonPartePiu() = runTest {
    val bench = Bench(this, cap = 1, workMs = { 1_000 })
    bench.window(visible = requests(0..5))
    runCurrent()
    bench.window(visible = listOf(request(5)))
    advanceUntilIdle()
    // La 1..4 erano in coda e sono uscite: non sono mai partite (e non contano come annullate: non sono partite).
    assertThat(bench.started).containsExactly(request(0).key, request(5).key).inOrder()
    assertThat(bench.scheduler.counters().cancelled).isEqualTo(1)
  }

  @Test
  fun cioCheRestaInFinestraNonRiparte() = runTest {
    val bench = Bench(this, cap = 2, workMs = { 1_000 })
    bench.window(visible = requests(0..1))
    runCurrent()
    advanceTimeBy(300)
    // La stessa finestra piu' volte (e con un ordine diverso): nessun lavoro nuovo, nessun annullamento.
    bench.window(visible = requests(0..1))
    bench.window(visible = requests(0..1).reversed())
    runCurrent()
    assertThat(bench.started).hasSize(2)
    assertThat(bench.scheduler.counters().cancelled).isEqualTo(0)
    advanceUntilIdle()
    assertThat(bench.finished).hasSize(2)
  }

  @Test
  fun unaRichiestaInArrivoCheDiventaVisibileNonSiAnnulla() = runTest {
    val bench = Bench(this, cap = 2, workMs = { 1_000 })
    bench.window(visible = requests(0..0), ahead = requests(1..1))
    runCurrent()
    // Lo scorrimento la porta sullo schermo: stesso lavoro, ora visibile.
    bench.window(visible = requests(0..1))
    runCurrent()
    assertThat(bench.started).hasSize(2)
    assertThat(bench.scheduler.counters().cancelled).isEqualTo(0)
  }

  @Test
  fun leGiaInCacheNonPartonoMai() = runTest {
    val bench = Bench(this, cap = 4)
    bench.cached += request(0).key
    bench.cached += request(1).key
    bench.window(visible = requests(0..3))
    advanceUntilIdle()
    assertThat(bench.started).containsExactly(request(2).key, request(3).key)
  }

  @Test
  fun lAnticipoSegueLaDirezioneDelloScorrimento() = runTest {
    // La griglia calcola l'anticipo con LookAhead e lo passa nell'ordine: il pianificatore lo rispetta.
    val items = requests(0..99)
    val forward = LookAhead.indices(firstVisible = 40, lastVisible = 49, itemCount = 100, direction = ScrollDirection.FORWARD, count = 6)
    val benchForward = Bench(this, cap = 1, workMs = { 10 })
    benchForward.window(visible = emptyList(), ahead = forward.map { items[it] })
    advanceUntilIdle()
    assertThat(benchForward.started.map { items.indexOfFirst { r -> r.key == it } }).containsExactly(50, 51, 52, 53, 54, 55).inOrder()

    val backward = LookAhead.indices(40, 49, 100, ScrollDirection.BACKWARD, count = 6)
    val benchBackward = Bench(this, cap = 1, workMs = { 10 })
    benchBackward.window(visible = emptyList(), ahead = backward.map { items[it] })
    advanceUntilIdle()
    assertThat(benchBackward.started.map { items.indexOfFirst { r -> r.key == it } }).containsExactly(39, 38, 37, 36, 35, 34).inOrder()
  }

  @Test
  fun unFallimentoSiRiprovaConAttesaCrescenteEPoiSiArrende() = runTest {
    val bench = Bench(
      this, cap = 2, workMs = { 10 },
      outcome = { _, _ -> LoadResult.FAILED },
      retry = RetryPolicy(maxAttempts = 3, backoffMs = listOf(1_000, 15_000)),
    )
    val key = request(7).key
    bench.window(visible = listOf(request(7)))
    advanceTimeBy(100)
    assertThat(bench.attempts[key]).isEqualTo(1)
    // Prima dell'attesa non riprova, anche se la finestra si ripete (lo scorrimento non martella un file corrotto).
    bench.window(visible = listOf(request(7)))
    advanceTimeBy(500)
    assertThat(bench.attempts[key]).isEqualTo(1)
    // Passata l'attesa riprova da sola, senza che la finestra cambi.
    advanceTimeBy(1_000)
    assertThat(bench.attempts[key]).isEqualTo(2)
    advanceTimeBy(14_000)
    assertThat(bench.attempts[key]).isEqualTo(2)
    advanceTimeBy(2_000)
    assertThat(bench.attempts[key]).isEqualTo(3)
    // Esauriti i tentativi: basta, anche con finestre nuove.
    assertThat(bench.scheduler.hasGivenUp(key)).isTrue()
    advanceTimeBy(60_000)
    bench.window(visible = listOf(request(7)))
    advanceTimeBy(60_000)
    assertThat(bench.attempts[key]).isEqualTo(3)
    assertThat(bench.scheduler.counters().failed).isEqualTo(3)
    // Dopo clearFailures (un trim) c'e' un'altra occasione.
    bench.scheduler.clearFailures()
    advanceTimeBy(100)
    assertThat(bench.attempts[key]).isEqualTo(4)
  }

  @Test
  fun unaVersioneNuovaDelFileHaUnaChiaveNuovaEParteSubito() = runTest {
    val bench = Bench(this, cap = 2, workMs = { 10 }, outcome = { _, _ -> LoadResult.FAILED })
    bench.window(visible = listOf(request(1)))
    advanceTimeBy(100)
    val edited = ThumbRequest(request(1).key.copy(versionKey = 2), request(1).ref)
    bench.window(visible = listOf(edited))
    advanceTimeBy(100)
    assertThat(bench.attempts[edited.key]).isEqualTo(1)
  }

  @Test
  fun unaEccezioneNonFermaGliAltriLavori() = runTest {
    val seen = mutableListOf<ThumbKey>()
    val scheduler = ThumbnailScheduler(
      scope = backgroundScope,
      maxConcurrent = 2,
      clockMs = { currentTime },
      isCached = { false },
      execute = { req ->
        seen += req.key
        if (req.key == request(0).key) error("boom")
        delay(10)
        LoadResult.LOADED
      },
    )
    scheduler.update(requests(0..2), emptyList())
    advanceTimeBy(100)
    assertThat(seen).containsAtLeast(request(0).key, request(1).key, request(2).key)
    assertThat(scheduler.counters().failed).isEqualTo(1)
  }

  @Test
  fun fraLeFinestreDiUnFlingNessunLavoroPartePerCioCheNonSiVede() = runTest {
    // 200 cambi di finestra, uno ogni 16 ms, ognuno sposta di una riga (4 celle): il caso peggiore dello scorrimento.
    val items = requests(0..1_199)
    val bench = Bench(this, cap = 4, workMs = { 40 })
    for (step in 0 until 200) {
      val first = step * 4
      val last = first + 39
      // Come la griglia: le celle sul bordo d'ingresso per prime, poi l'anticipo nella direzione dello scorrimento.
      val visible = LookAhead.visibleIndices(first, last, ScrollDirection.FORWARD).map { items[it] }
      val ahead = LookAhead.indices(first, last, items.size, ScrollDirection.FORWARD, count = 24).map { items[it] }
      bench.window(visible, ahead)
      advanceTimeBy(16)
    }
    advanceUntilIdle()
    assertThat(bench.peakActive).isAtMost(4)
    assertThat(bench.scheduler.counters().peakInFlight).isAtMost(4)
    // Nessun lavoro e' partito per una chiave fuori dalla finestra di quel momento.
    assertThat(bench.startedOutsideWindow).isEmpty()
    // Ne sono partiti molti meno delle 1.200 celle sfiorate dal fling: il resto non e' mai stato richiesto.
    assertThat(bench.started.size).isLessThan(600)
    // Quando lo scorrimento si ferma la finestra finale e' completa.
    val last = 199 * 4
    assertThat(bench.cached).containsAtLeastElementsIn(items.subList(last, last + 40).map { it.key })
    assertThat(bench.scheduler.counters().inFlight).isEqualTo(0)
  }

  @Test
  fun cancelAllFermaTutto() = runTest {
    val bench = Bench(this, cap = 3, workMs = { 1_000 })
    bench.window(visible = requests(0..5))
    runCurrent()
    bench.scheduler.cancelAll()
    runCurrent()
    assertThat(bench.cancelledKeys).hasSize(3)
    assertThat(bench.scheduler.counters().inFlight).isEqualTo(0)
    // Dopo la chiusura non parte piu' niente.
    advanceTimeBy(10_000)
    assertThat(bench.started).hasSize(3)
  }

  @Test
  fun lAnticipoCalcolatoRispettaIConfini() {
    assertThat(LookAhead.indices(0, 9, 10, ScrollDirection.FORWARD, 5)).isEmpty()
    assertThat(LookAhead.indices(0, 9, 12, ScrollDirection.FORWARD, 5).toList()).containsExactly(10, 11).inOrder()
    assertThat(LookAhead.indices(2, 5, 100, ScrollDirection.BACKWARD, 5).toList()).containsExactly(1, 0).inOrder()
    assertThat(LookAhead.indices(10, 19, 100, ScrollDirection.NONE, 4).toList()).containsExactly(20, 9, 21, 8).inOrder()
    assertThat(LookAhead.indices(0, 9, 100, ScrollDirection.NONE, 4).toList()).containsExactly(10, 11, 12, 13).inOrder()
    assertThat(LookAhead.indices(5, 3, 100, ScrollDirection.FORWARD, 4)).isEmpty()
    assertThat(LookAhead.indices(0, 3, 100, ScrollDirection.FORWARD, 0)).isEmpty()
    assertThat(LookAhead.visibleIndices(4, 7, ScrollDirection.FORWARD).toList()).containsExactly(7, 6, 5, 4).inOrder()
    assertThat(LookAhead.visibleIndices(4, 7, ScrollDirection.BACKWARD).toList()).containsExactly(4, 5, 6, 7).inOrder()
    assertThat(LookAhead.visibleIndices(4, 7, ScrollDirection.NONE).toList()).containsExactly(4, 5, 6, 7).inOrder()
    assertThat(LookAhead.visibleIndices(8, 7, ScrollDirection.NONE)).isEmpty()
  }
}
