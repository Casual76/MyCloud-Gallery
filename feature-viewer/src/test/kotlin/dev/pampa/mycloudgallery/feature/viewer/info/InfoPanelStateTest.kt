package dev.pampa.mycloudgallery.feature.viewer.info

import androidx.compose.animation.core.exponentialDecay
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * H2: il pannello informazioni guidato dal dito, senza composizione. Le soglie di apertura e chiusura, un pannello lungo che cresce oltre meta'
 * schermo (portando su il contenuto) e poi scorre dentro, il ritorno in cima prima di chiudere, e la chiusura che lo rimette in cima.
 * Telefono 1000 x 2000 px: la corsa (meta' schermo) e' 1000.
 */
class InfoPanelStateTest {

  private val travel = 1000f
  private val decay = exponentialDecay<Float>()

  @Test
  fun unoSwipeInSuApreDopoPiuDiUnQuartoDiCorsa_menoTornaGiu() = runTest {
    val panel = InfoPanelState(initialOpen = false)
    panel.drag(-300f, travel)
    assertThat(panel.progress.value).isWithin(1e-4f).of(0.3f)
    assertThat(panel.endDrag(0f, travel, reducedMotion = true, decay = decay)).isTrue()
    assertThat(panel.progress.value).isEqualTo(1f)

    val short = InfoPanelState(initialOpen = false)
    short.drag(-200f, travel)
    assertThat(short.endDrag(0f, travel, reducedMotion = true, decay = decay)).isFalse()
    assertThat(short.progress.value).isEqualTo(0f)
  }

  @Test
  fun unLancioVinceSullaPosizione() = runTest {
    val panel = InfoPanelState(initialOpen = false)
    panel.drag(-50f, travel)
    // 1500 px/s verso l'alto: si apre anche con poca corsa.
    assertThat(panel.endDrag(-1500f, travel, reducedMotion = true, decay = decay)).isTrue()
    val open = InfoPanelState(initialOpen = true)
    open.drag(50f, travel)
    // 1500 px/s verso il basso: si chiude anche se e' quasi tutto aperto.
    assertThat(open.endDrag(1500f, travel, reducedMotion = true, decay = decay)).isFalse()
  }

  @Test
  fun daApertoSiChiudeDopoPiuDiUnQuartoDiCorsa() = runTest {
    val panel = InfoPanelState(initialOpen = true)
    panel.drag(300f, travel)
    assertThat(panel.endDrag(0f, travel, reducedMotion = true, decay = decay)).isFalse()
    val barely = InfoPanelState(initialOpen = true)
    barely.drag(150f, travel)
    assertThat(barely.endDrag(0f, travel, reducedMotion = true, decay = decay)).isTrue()
    assertThat(barely.progress.value).isEqualTo(1f)
  }

  @Test
  fun unPannelloCortoNonScorre() = runTest {
    val panel = InfoPanelState(initialOpen = true)
    // Le informazioni stanno nella meta' schermo: niente da scorrere, il pannello resta alto meta'.
    panel.measure(contentHeight = 800f, travel = travel, liftRoom = 400f)
    panel.drag(-300f, travel)
    assertThat(panel.offset.value).isEqualTo(0f)
    assertThat(panel.panelTop(2000f)).isEqualTo(1000f)
  }

  @Test
  fun unPannelloLungoCresceFinoAllaBarraEPoiScorreDentro() = runTest {
    val panel = InfoPanelState(initialOpen = true)
    // 1600 px di informazioni in una corsa di 1000: 600 da scorrere, di cui 400 crescendo (fino alla barra in alto) e 200 dentro.
    panel.measure(contentHeight = 1600f, travel = travel, liftRoom = 400f)
    assertThat(panel.maxOffset).isEqualTo(600f)
    assertThat(panel.maxLift).isEqualTo(400f)

    panel.drag(-300f, travel)
    assertThat(panel.progress.value).isEqualTo(1f)
    assertThat(panel.lift).isEqualTo(300f)
    assertThat(panel.inner).isEqualTo(0f)
    // Il bordo del pannello sale con lo scorrimento (e porta su il contenuto, `InfoGeometry.contentLayer`).
    assertThat(panel.panelTop(2000f)).isEqualTo(700f)

    panel.drag(-200f, travel)
    assertThat(panel.lift).isEqualTo(400f)
    assertThat(panel.inner).isEqualTo(100f)
    assertThat(panel.panelTop(2000f)).isEqualTo(600f)

    // Oltre la fine non si va.
    panel.drag(-5000f, travel)
    assertThat(panel.offset.value).isEqualTo(600f)
    assertThat(panel.inner).isEqualTo(200f)

    // Il dito lascia: resta aperto e scorso dov'e' (senza movimento, niente inerzia).
    assertThat(panel.endDrag(0f, travel, reducedMotion = true, decay = decay)).isTrue()
    assertThat(panel.offset.value).isEqualTo(600f)
  }

  @Test
  fun inGiuPrimaTornaInCimaPoiChiude() = runTest {
    val panel = InfoPanelState(initialOpen = true)
    panel.measure(contentHeight = 1600f, travel = travel, liftRoom = 400f)
    panel.drag(-500f, travel)
    // Un gesto solo: 500 per tornare in cima, 300 per chiudere un terzo.
    panel.drag(800f, travel)
    assertThat(panel.offset.value).isEqualTo(0f)
    assertThat(panel.progress.value).isWithin(1e-4f).of(0.7f)
    assertThat(panel.endDrag(0f, travel, reducedMotion = true, decay = decay)).isFalse()
    assertThat(panel.progress.value).isEqualTo(0f)
  }

  @Test
  fun chiudendoTornaInCima() = runTest {
    val panel = InfoPanelState(initialOpen = true)
    panel.measure(contentHeight = 1600f, travel = travel, liftRoom = 400f)
    panel.drag(-450f, travel)
    panel.endDrag(0f, travel, reducedMotion = true, decay = decay)
    // Chiuso da fuori (il tocco sulla foto, Indietro, il pizzico): la prossima apertura riparte dalla testa.
    panel.settle(open = false, reducedMotion = true)
    assertThat(panel.progress.value).isEqualTo(0f)
    assertThat(panel.offset.value).isEqualTo(0f)
    assertThat(panel.panelTop(2000f)).isEqualTo(2000f)
  }
}
