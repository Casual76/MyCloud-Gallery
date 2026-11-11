package dev.pampa.mycloudgallery.feature.library.grid

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Fase H, D10: il tiro oltre il fondo della griglia ([GridPullState]). Le regole senza comporre: la gomma, la soglia con l'aptica una volta, il
 * ritorno del dito prima dello scorrimento, i resti di un lancio ignorati, l'aggiornamento che parte una sola volta e tiene aperto lo spazio.
 * Il movimento ridotto rende i ritorni istantanei (niente orologio dei fotogrammi): le regole sono le stesse.
 */
class GridPullRefreshTest {

  private val height = 2_000
  private val trigger = 200f
  private val hold = 150f

  private fun TestScope.pullState(work: suspend () -> Unit = {}): Pair<GridPullState, MutableList<String>> {
    val events = mutableListOf<String>()
    val state = GridPullState(this, reducedMotion = true).apply {
      updateViewport(height)
      triggerPx = trigger
      holdPx = hold
      onArmed = { events += "aptica" }
      onRefresh = {
        events += "aggiorna"
        work()
      }
    }
    return state to events
  }

  /** Un passo del dito che sale oltre il fondo: la lista non ha strada, tutto e' resto. */
  private fun GridPullState.finger(dy: Float, source: NestedScrollSource = NestedScrollSource.UserInput): Offset =
    onPostScroll(consumed = Offset.Zero, available = Offset(0f, dy), source = source)

  @Test
  fun laGommaCresceSempreMenoENonSuperaLAsintoto() {
    val d = 480f
    var previous = 0f
    var previousStep = Float.MAX_VALUE
    for (raw in 50..5_000 step 50) {
      val v = GridPullState.rubberBand(raw.toFloat(), d)
      assertThat(v).isGreaterThan(previous)
      assertThat(v - previous).isAtMost(previousStep + 0.001f)
      assertThat(v).isLessThan(d)
      previousStep = v - previous
      previous = v
      assertThat(GridPullState.inverseRubberBand(v, d)).isWithin(0.5f).of(raw.toFloat())
    }
  }

  @Test
  fun ilDitoCheSaleOltreIlFondoSpostaLaListaESuperataLaSogliaVibraUnaVolta() = runTest(StandardTestDispatcher()) {
    val (state, events) = pullState()
    repeat(60) { state.finger(-20f) }
    assertThat(state.offsetPx).isGreaterThan(trigger)
    assertThat(events).containsExactly("aptica")
    // Il dito torna in giu': prima si richiude lo spostamento, poi scorrerebbe la lista.
    val back = state.onPreScroll(Offset(0f, 30f), NestedScrollSource.UserInput)
    assertThat(back.y).isEqualTo(30f)
    // Sotto la soglia e di nuovo sopra: si riarma, un secondo colpo.
    state.onPreScroll(Offset(0f, state.offsetPx - trigger + 20f), NestedScrollSource.UserInput)
    assertThat(state.offsetPx).isLessThan(trigger)
    repeat(20) { state.finger(-20f) }
    assertThat(events).containsExactly("aptica", "aptica")
  }

  @Test
  fun iRestiDiUnLancioNonTiranoEUnDitoCheScendeNonTira() = runTest(StandardTestDispatcher()) {
    val (state, _) = pullState()
    assertThat(state.finger(-200f, NestedScrollSource.SideEffect)).isEqualTo(Offset.Zero)
    assertThat(state.finger(200f)).isEqualTo(Offset.Zero)
    assertThat(state.offsetPx).isEqualTo(0f)
    // A riposo un lancio passa intatto.
    assertThat(state.onPreFling(Velocity(0f, -3_000f))).isEqualTo(Velocity.Zero)
  }

  @Test
  fun rilasciatoSottoLaSogliaTornaAPostoSenzaAggiornare() = runTest(StandardTestDispatcher()) {
    val (state, events) = pullState()
    repeat(3) { state.finger(-20f) }
    assertThat(state.offsetPx).isGreaterThan(0f)
    assertThat(state.offsetPx).isLessThan(trigger)
    // Il rilascio consuma la velocita' (la lista non si lancia) e il bordo torna a zero.
    assertThat(state.onPreFling(Velocity(0f, -800f))).isEqualTo(Velocity(0f, -800f))
    advanceUntilIdle()
    assertThat(state.offsetPx).isEqualTo(0f)
    assertThat(events).isEmpty()
  }

  @Test
  fun rilasciatoOltreLaSogliaAggiornaUnaVoltaETieneApertoLoSpazio() = runTest(StandardTestDispatcher()) {
    val done = CompletableDeferred<Unit>()
    val (state, events) = pullState { done.await() }
    repeat(60) { state.finger(-20f) }
    state.onPreFling(Velocity.Zero)
    runCurrent()
    assertThat(state.refreshing).isTrue()
    assertThat(state.offsetPx).isEqualTo(hold)
    // Mentre aggiorna, un altro tiro non fa partire niente e lo spazio non si muove.
    repeat(60) { state.finger(-20f) }
    assertThat(state.offsetPx).isEqualTo(hold)
    assertThat(state.onPreFling(Velocity.Zero)).isEqualTo(Velocity.Zero)
    done.complete(Unit)
    advanceUntilIdle()
    assertThat(state.refreshing).isFalse()
    assertThat(state.offsetPx).isEqualTo(0f)
    assertThat(events).containsExactly("aptica", "aggiorna").inOrder()
  }

  @Test
  fun unAggiornamentoCheNonFiniscePiuChiudeLoSpazioDopoIlTetto() = runTest(StandardTestDispatcher()) {
    val (state, _) = pullState { CompletableDeferred<Unit>().await() }
    repeat(60) { state.finger(-20f) }
    state.onPreFling(Velocity.Zero)
    advanceUntilIdle()
    assertThat(state.refreshing).isFalse()
    assertThat(state.offsetPx).isEqualTo(0f)
  }

  @Test
  fun senzaIlGestoAttivoNienteSiSposta() = runTest(StandardTestDispatcher()) {
    val (state, events) = pullState()
    state.enabled = { false }
    repeat(60) { state.finger(-20f) }
    assertThat(state.offsetPx).isEqualTo(0f)
    assertThat(events).isEmpty()
  }
}
