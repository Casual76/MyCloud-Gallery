package dev.pampa.mycloudgallery.feature.viewer.gesture

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Test

/**
 * H1: lo stato unico della chiusura. Il contenuto segue il dito **senza molla nel mezzo** (il punto sotto il dito resta sotto il dito mentre il
 * contenuto si rimpicciolisce), il pizzico usa la stessa macchina, l'Indietro predittivo pure; al rilascio torna con la molla o vola alla cella.
 * Le animazioni girano su un orologio dei fotogrammi finto (60 al secondo), senza composizione.
 */
class ViewerDismissStateTest {

  private val stage = Size(1000f, 2000f)

  /** Un fotogramma ogni 16,7 ms, subito (cedendo il passo alle altre coroutine): le molle arrivano in fondo senza aspettare il tempo vero. */
  private class StepClock : MonotonicFrameClock {
    var frames = 0
    private var time = 0L

    override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
      yield()
      frames++
      time += FRAME_NANOS
      return onFrame(time)
    }
  }

  @Test
  fun ilPuntoSottoIlDitoRestaSottoIlDitoMentreLaFotoSiRimpicciolisce() {
    val state = ViewerDismissState()
    state.begin()
    val touched = Offset(300f, 800f)
    var finger = touched
    repeat(4) {
      val step = Offset(15f, 100f)
      finger += step
      state.move(step, 1f, finger, stage.height)
    }
    val layer = state.layer()
    assertOffset(layer.map(touched), finger)
    assertThat(layer.scale).isWithin(EPS).of(ViewerDismissSpec.dragShrink(400f, stage.height))
    assertThat(state.progress).isWithin(EPS).of(0.4f)
    assertThat(state.backdropAlpha).isWithin(EPS).of(0.6f)
    assertThat(state.phase).isEqualTo(ViewerDismissState.Phase.Gesture)
  }

  @Test
  fun ilPizzicoUsaLaStessaMacchina_ilPuntoMedioResta_eLoSfondoSfuma() {
    val state = ViewerDismissState()
    state.begin()
    val middle = Offset(500f, 1000f)
    state.move(Offset.Zero, 0.8f, middle, stage.height)
    state.move(Offset.Zero, 0.75f, middle, stage.height)
    val layer = state.layer()
    assertThat(layer.scale).isWithin(EPS).of(0.6f)
    assertOffset(layer.map(middle), middle)
    assertThat(state.progress).isWithin(EPS).of(ViewerDismissSpec.pinchProgress(0.6f))
    // E le dita che si spostano insieme portano il contenuto con se'.
    state.move(Offset(40f, 120f), 1f, middle + Offset(40f, 120f), stage.height)
    assertOffset(state.layer().map(middle), middle + Offset(40f, 120f))
  }

  @Test
  fun unTrascinamentoBreveTornaAlSuoPostoConLaMolla() = runBlocking<Unit>(StepClock()) {
    val state = ViewerDismissState()
    state.begin()
    state.move(Offset(0f, 200f), 1f, Offset(500f, 1200f), stage.height)
    assertThat(state.releaseDismisses(velocityYDp = 100f, pinchRate = 0f, stageHeight = stage.height)).isFalse()

    state.settleBack(reducedMotion = false)

    assertThat(state.phase).isEqualTo(ViewerDismissState.Phase.Idle)
    assertThat(state.layer().isIdentity).isTrue()
    assertThat(state.progress).isEqualTo(0f)
  }

  @Test
  fun unDitoCheRiprendeIlContenutoMentreTornaLoFermaDovE() = runBlocking<Unit>(StepClock()) {
    val state = ViewerDismissState()
    state.begin()
    state.move(Offset(0f, 300f), 1f, Offset(500f, 1300f), stage.height)
    val returning = launch { state.settleBack(reducedMotion = false) }
    // Qualche fotogramma di ritorno, poi il dito lo riprende.
    repeat(3) { yield() }
    state.begin()
    returning.cancel()
    val caught = state.layer()
    assertThat(state.phase).isEqualTo(ViewerDismissState.Phase.Gesture)
    // Ripreso a meta' strada: ne' al posto di partenza ne' dov'era il dito.
    assertThat(caught.scale).isLessThan(1f)
    // E da li' segue il dito, senza salti.
    state.move(Offset(0f, 50f), 1f, Offset(500f, 1350f), stage.height)
    assertThat(state.layer().scale).isLessThan(caught.scale)
  }

  @Test
  fun oltreLaSogliaVolaAllaCella_eLoSfondoFinisceDiSfumare() = runBlocking<Unit>(StepClock()) {
    val state = ViewerDismissState()
    state.begin()
    state.move(Offset(0f, 700f), 1f, Offset(500f, 1700f), stage.height)
    assertThat(state.releaseDismisses(velocityYDp = 0f, pinchRate = 0f, stageHeight = stage.height)).isTrue()
    val content = Rect(0f, 625f, 1000f, 1375f)
    val cell = Rect(100f, 300f, 300f, 500f)
    val plan = ViewerDismissState.planFor(state.screenRect(content), content, cell, stage)

    state.fly(plan, initialVelocity = 2f, reducedMotion = false)

    assertThat(state.phase).isEqualTo(ViewerDismissState.Phase.Flying)
    assertThat(state.flight.value).isEqualTo(1f)
    assertThat(state.progress).isEqualTo(1f)
    val layer = state.layer()
    assertRect(layer.map(layer.clip!!), cell)
  }

  @Test
  fun conIlMovimentoRidottoIlVoloArrivaSubito() = runBlocking<Unit>(StepClock()) {
    val state = ViewerDismissState()
    val content = Rect(0f, 625f, 1000f, 1375f)
    val plan = ViewerDismissState.planFor(state.screenRect(content), content, null, stage)
    state.fly(plan, initialVelocity = 0f, reducedMotion = true)
    assertThat(state.flight.value).isEqualTo(1f)
    assertThat(state.layer().alpha).isEqualTo(0f)
  }

  @Test
  fun lIndietroPredittivoRimpicciolisceVersoIlLatoDelGesto_eSfumaAMeta() {
    val state = ViewerDismissState()
    state.back(1f, fromLeft = true, stage = stage)
    val layer = state.layer()
    assertThat(layer.scale).isWithin(EPS).of(ViewerDismissSpec.BackMinScale)
    assertOffset(layer.map(Offset(500f, 1000f)), Offset(500f + 1000f * ViewerDismissSpec.BackShiftFraction, 1000f + 2000f * ViewerDismissSpec.BackLiftFraction))
    assertThat(state.backdropAlpha).isWithin(EPS).of(1f - ViewerDismissSpec.BackFade)
    // Dal bordo destro va a sinistra.
    state.back(0.5f, fromLeft = false, stage = stage)
    assertThat(state.layer().map(Offset(500f, 1000f)).x).isLessThan(500f)
  }

  @Test
  fun fermoIlContenutoNonSiTrasforma() {
    val state = ViewerDismissState()
    assertThat(state.active).isFalse()
    assertThat(state.layer().isIdentity).isTrue()
    // Un passo senza un gesto preso non sposta niente.
    state.move(Offset(0f, 300f), 1f, Offset(500f, 1300f), stage.height)
    assertThat(state.layer().isIdentity).isTrue()
  }

  private fun assertOffset(actual: Offset, expected: Offset) {
    assertThat(actual.x).isWithin(0.5f).of(expected.x)
    assertThat(actual.y).isWithin(0.5f).of(expected.y)
  }

  private companion object {
    const val EPS = 1e-4f
    const val FRAME_NANOS = 16_666_667L
  }
}
