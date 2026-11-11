package dev.pampa.mycloudgallery.backup

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlin.time.Duration
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SimilarRunPacingTest {
  @Test fun attendeIlDisegnoEPoiDieciSecondiECedeInPrimoPiano() = runTest {
    val draw = CompletableDeferred<Unit>()
    val budgets = mutableListOf<Duration>()
    var foreground = true
    val start = SimilarRunStart { draw.await() }
    backgroundScope.launch { runSimilarSlices({ foreground }, start::await) { budgets += it; budgets.size < 3 } }
    advanceTimeBy(20_000); runCurrent()
    assertThat(budgets).isEmpty()
    draw.complete(Unit); runCurrent()
    advanceTimeBy(9_999); runCurrent(); assertThat(budgets).isEmpty()
    advanceTimeBy(1); runCurrent(); assertThat(budgets.single().inWholeSeconds).isEqualTo(1)
    foreground = false
    advanceTimeBy(1_000); runCurrent()
    assertThat(budgets.map { it.inWholeSeconds }).containsExactly(1L, 30L, 30L).inOrder()
  }
  @Test fun ilConfrontoSuccessivoNonAspettaAltriDieciSecondi() = runTest {
    val start = SimilarRunStart {}
    var calls = 0
    backgroundScope.launch { runSimilarSlices({ false }, start::await) { calls++; false } }
    advanceTimeBy(10_000); runCurrent()
    assertThat(calls).isEqualTo(1)
    val before = testScheduler.currentTime
    backgroundScope.launch { runSimilarSlices({ false }, start::await) { calls++; false } }
    runCurrent()
    assertThat(calls).isEqualTo(2)
    assertThat(testScheduler.currentTime).isEqualTo(before)
  }

}
