package dev.pampa.mycloudgallery.feature.viewer.gesture

import androidx.compose.ui.geometry.Offset
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * H1: l'arbitraggio dei gesti del viewer come tabella. Chi prende cosa: il pager di lato, la chiusura in basso (o il pannello), le
 * informazioni in alto, il pizzico che stringe a zoom minimo per chiudere, quello che allarga per ingrandire, tutto allo zoom quando la foto e'
 * ingrandita, niente prima della soglia di tocco (tocco, doppio tocco e pressione lunga restano dei figli).
 */
class ViewerGestureArbiterTest {

  private val slop = 16f
  private val rest = GestureContext(enabled = true, zoomed = false, infoOpen = false, sidePanel = false, returning = false)

  private fun one(pan: Offset, context: GestureContext = rest) = ViewerGestureArbiter.decide(context, fingers = 1, pan = pan, spanChange = 0f, slop = slop)

  private fun two(spanChange: Float, context: GestureContext = rest) = ViewerGestureArbiter.decide(context, fingers = 2, pan = Offset.Zero, spanChange = spanChange, slop = slop)

  @Test
  fun primaDellaSogliaNonSiDecide_tocchiDoppiTocchiEPressioniLungheRestanoAiFigli() {
    assertThat(one(Offset(5f, 10f))).isEqualTo(GestureClaim.Undecided)
    assertThat(two(spanChange = -3f)).isEqualTo(GestureClaim.Undecided)
  }

  @Test
  fun diLatoEIlPager_anchePerUnaDiagonalePerfetta() {
    assertThat(one(Offset(40f, 5f))).isEqualTo(GestureClaim.Children)
    assertThat(one(Offset(-40f, 10f))).isEqualTo(GestureClaim.Children)
    assertThat(one(Offset(30f, 30f))).isEqualTo(GestureClaim.Children)
  }

  @Test
  fun inBassoChiude_aPannelloApertoChiudeIlPannello() {
    assertThat(one(Offset(5f, 40f))).isEqualTo(GestureClaim.Dismiss)
    assertThat(one(Offset(5f, 40f), rest.copy(infoOpen = true))).isEqualTo(GestureClaim.CloseInfo)
  }

  @Test
  fun inAltoApreLeInformazioniSulTelefono_sulTabletNonFaNienteMaNonEUnTocco() {
    assertThat(one(Offset(0f, -40f))).isEqualTo(GestureClaim.OpenInfo)
    assertThat(one(Offset(0f, -40f), rest.copy(sidePanel = true))).isEqualTo(GestureClaim.Dead)
    assertThat(one(Offset(0f, -40f), rest.copy(infoOpen = true))).isEqualTo(GestureClaim.Dead)
  }

  @Test
  fun dueDitaCheStringonoAZoomMinimoChiudono_cheAllarganoIngrandiscono() {
    // Prima di telephoto: a meta' della soglia.
    assertThat(two(spanChange = -slop * ViewerGestureArbiter.PinchSlopShare)).isEqualTo(GestureClaim.Dismiss)
    assertThat(two(spanChange = slop * ViewerGestureArbiter.PinchSlopShare)).isEqualTo(GestureClaim.Children)
  }

  @Test
  fun aFotoIngranditaTuttoEDelloZoom() {
    val zoomed = rest.copy(zoomed = true)
    assertThat(one(Offset(0f, 80f), zoomed)).isEqualTo(GestureClaim.Children)
    assertThat(one(Offset(0f, -80f), zoomed)).isEqualTo(GestureClaim.Children)
    assertThat(two(spanChange = -40f, context = zoomed)).isEqualTo(GestureClaim.Children)
  }

  @Test
  fun conIlPannelloApertoDueDitaIngrandiscono() {
    assertThat(two(spanChange = -40f, context = rest.copy(infoOpen = true))).isEqualTo(GestureClaim.Children)
  }

  @Test
  fun mentreIlContenutoTornaSoloUnaChiusuraLoRiprende() {
    val returning = rest.copy(returning = true)
    assertThat(one(Offset(0f, 40f), returning)).isEqualTo(GestureClaim.Dismiss)
    assertThat(two(spanChange = -20f, context = returning)).isEqualTo(GestureClaim.Dismiss)
    assertThat(one(Offset(0f, -40f), returning)).isEqualTo(GestureClaim.Children)
  }

  @Test
  fun spentoNonPrendeNiente_chiusuraInCorso_selezioneDelTesto_ritaglio() {
    val off = rest.copy(enabled = false)
    assertThat(one(Offset(0f, 80f), off)).isEqualTo(GestureClaim.Children)
    assertThat(two(spanChange = -40f, context = off)).isEqualTo(GestureClaim.Children)
  }
}
