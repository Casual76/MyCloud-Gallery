package dev.pampa.mycloudgallery.feature.viewer.gesture

import androidx.compose.ui.geometry.Offset
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * H1 (e H2 per il pannello informazioni): l'arbitraggio dei gesti del viewer come tabella. Chi prende cosa: il pager di lato, la chiusura in basso (o il pannello), le
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
  }

  @Test
  fun aPannelloApertoInAltoFaScorrereIlPannello_inBassoLoRiportaGiu() {
    // H2: un pannello lungo si scorre anche dalla foto (cresce oltre meta' e porta il contenuto con se'); in basso torna in cima e poi chiude.
    val open = rest.copy(infoOpen = true)
    assertThat(one(Offset(0f, -40f), open)).isEqualTo(GestureClaim.ScrollInfo)
    assertThat(one(Offset(5f, 40f), open)).isEqualTo(GestureClaim.CloseInfo)
    // Di lato resta il pager: si passa all'elemento dopo con il pannello aperto.
    assertThat(one(Offset(40f, 5f), open)).isEqualTo(GestureClaim.Children)
    // La colonna laterale del tablet non si trascina: la foto accanto non apre e non chiude niente in verticale.
    val side = rest.copy(infoOpen = true, sidePanel = true)
    assertThat(one(Offset(0f, -40f), side)).isEqualTo(GestureClaim.Dead)
    assertThat(one(Offset(0f, 40f), side)).isEqualTo(GestureClaim.Dead)
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
  fun conIlPannelloApertoDueDitaChiudonoIlPannello_sulTabletIngrandiscono() {
    // H2: a pannello aperto la foto e' spostata e tagliata; il secondo dito chiude il pannello appena si appoggia (prima che lo zoom veda un
    // pizzico), il pizzico dopo ingrandisce la foto intera.
    val open = rest.copy(infoOpen = true)
    assertThat(two(spanChange = 0f, context = open)).isEqualTo(GestureClaim.InfoPinch)
    assertThat(two(spanChange = -40f, context = open)).isEqualTo(GestureClaim.InfoPinch)
    assertThat(two(spanChange = 40f, context = open)).isEqualTo(GestureClaim.InfoPinch)
    // La colonna laterale lascia la foto intera accanto: li' il pizzico e' dello zoom, come sempre.
    assertThat(two(spanChange = 40f, context = open.copy(sidePanel = true))).isEqualTo(GestureClaim.Children)
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
