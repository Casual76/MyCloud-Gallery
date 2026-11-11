package dev.pampa.mycloudgallery.feature.viewer.gesture

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.feature.viewer.page.ContentLayer
import org.junit.Test

/**
 * H1 (D5, D7): le soglie della chiusura e la geometria del volo, in funzioni pure. Il trascinamento rimpicciolisce il contenuto, il pizzico lo
 * segue, il rilascio chiude o torna secondo strada, velocita' e pizzico; il volo parte esattamente da dove l'ha lasciato il dito e arriva sulla
 * cella con il ritaglio della miniatura; senza cella si allontana sfumando.
 */
class ViewerDismissSpecTest {

  @Test
  fun laFotoCheVolaAllaCellaHaUnOmbraCheSaleEPoiSiPosa() {
    // Facoltativo del giro 2: niente ombra al rilascio (non compare di colpo) ne' all'arrivo (la miniatura e' posata), un'ombra nel mezzo.
    assertThat(ViewerDismissSpec.flightLift(0f)).isEqualTo(0f)
    assertThat(ViewerDismissSpec.flightLift(ViewerDismissSpec.LiftIn)).isWithin(0.001f).of(1f - ViewerDismissSpec.LiftIn)
    assertThat(ViewerDismissSpec.flightLift(0.5f)).isWithin(0.001f).of(0.5f)
    assertThat(ViewerDismissSpec.flightLift(1f)).isEqualTo(0f)
    val plan = FlightPlan.ToCell(from = Rect(0f, 0f, 1000f, 1500f), to = Rect(100f, 900f, 300f, 1100f), content = Rect(0f, 0f, 1000f, 1500f))
    assertThat(plan.at(0.5f).lift).isGreaterThan(0f)
    assertThat(plan.at(0.5f).clip).isNotNull()
    assertThat(plan.at(1f).lift).isEqualTo(0f)
    // Senza cella il contenuto si allontana sfumando: nessuna sagoma, nessuna ombra.
    val away = FlightPlan.Away(from = Rect(0f, 0f, 1000f, 1500f), content = Rect(0f, 0f, 1000f, 1500f), drop = 100f)
    assertThat(away.at(0.5f).lift).isEqualTo(0f)
  }

  private val height = 2000f

  @Test
  fun ilTrascinamentoRimpicciolisceIlContenutoFinoAlSessantaPerCentoAFineCorsa() {
    assertThat(ViewerDismissSpec.dragShrink(0f, height)).isEqualTo(1f)
    // La corsa e' meta' dell'altezza: a 500 px su 2000 si e' a meta' corsa.
    assertThat(ViewerDismissSpec.dragShrink(500f, height)).isWithin(EPS).of(0.8f)
    assertThat(ViewerDismissSpec.dragShrink(1000f, height)).isWithin(EPS).of(ViewerDismissSpec.MinDragScale)
    assertThat(ViewerDismissSpec.dragShrink(1800f, height)).isWithin(EPS).of(ViewerDismissSpec.MinDragScale)
    // Verso l'alto non si rimpicciolisce niente.
    assertThat(ViewerDismissSpec.dragShrink(-300f, height)).isEqualTo(1f)
  }

  @Test
  fun loSfondoSfumaConIlPiuAvantiFraTrascinamentoEPizzico() {
    assertThat(ViewerDismissSpec.progress(travel = 0f, pinch = 1f, stageHeight = height)).isEqualTo(0f)
    assertThat(ViewerDismissSpec.progress(travel = 400f, pinch = 1f, stageHeight = height)).isWithin(EPS).of(0.4f)
    assertThat(ViewerDismissSpec.progress(travel = 0f, pinch = 0.75f, stageHeight = height)).isWithin(EPS).of(0.5f)
    assertThat(ViewerDismissSpec.progress(travel = 400f, pinch = 0.75f, stageHeight = height)).isWithin(EPS).of(0.5f)
    // A meta' misura lo sfondo e' sparito; allargare non da' progresso.
    assertThat(ViewerDismissSpec.pinchProgress(ViewerDismissSpec.PinchFullFade)).isEqualTo(1f)
    assertThat(ViewerDismissSpec.pinchProgress(1.1f)).isEqualTo(0f)
  }

  @Test
  fun ilPizzicoSegueLeDitaSottoLaMisuraPienaEResisteSopra() {
    assertThat(ViewerDismissSpec.resistPinch(0.7f)).isEqualTo(0.7f)
    assertThat(ViewerDismissSpec.resistPinch(0.1f)).isEqualTo(ViewerDismissSpec.MinPinch)
    val over = ViewerDismissSpec.resistPinch(1.2f)
    assertThat(over).isGreaterThan(1f)
    assertThat(over).isLessThan(1.2f)
    assertThat(ViewerDismissSpec.resistPinch(3f)).isEqualTo(ViewerDismissSpec.MaxPinch)
  }

  @Test
  fun laStradaChiudeDalTrentaPerCentoDellaCorsa() {
    assertThat(dismisses(dragProgress = 0.30f)).isTrue()
    assertThat(dismisses(dragProgress = 0.29f)).isFalse()
  }

  @Test
  fun unLancioVersoIlBassoChiudeAncheCorto_versoLAltoTornaSempre() {
    assertThat(dismisses(dragProgress = 0.05f, velocityYDp = 900f)).isTrue()
    assertThat(dismisses(dragProgress = 0.05f, velocityYDp = 600f)).isFalse()
    // L'utente ha cambiato idea: anche a strada lunga, un lancio verso l'alto riporta la foto al suo posto.
    assertThat(dismisses(dragProgress = 0.6f, velocityYDp = -900f)).isFalse()
  }

  @Test
  fun unPizzicoChiudeSottoLOttantacinquePerCentoOSeStringeInFretta() {
    assertThat(dismisses(pinch = 0.84f)).isTrue()
    assertThat(dismisses(pinch = 0.86f)).isFalse()
    assertThat(dismisses(pinch = 0.95f, pinchRate = -1.7f)).isTrue()
    assertThat(dismisses(pinch = 0.95f, pinchRate = -1.0f)).isFalse()
    // Riaperto in fretta: torna, anche se era stretto.
    assertThat(dismisses(pinch = 0.5f, pinchRate = 1.7f)).isFalse()
  }

  @Test
  fun ilRitaglioDellaMiniaturaEIlCentroDellaFoto() {
    val landscape = Rect(0f, 500f, 1200f, 1400f)
    val square = ViewerDismissSpec.centerCrop(landscape, 1f)
    assertRect(square, Rect(150f, 500f, 1050f, 1400f))
    val portrait = Rect(100f, 0f, 900f, 1600f)
    assertRect(ViewerDismissSpec.centerCrop(portrait, 1f), Rect(100f, 400f, 900f, 1200f))
  }

  @Test
  fun ilVoloPartedaDoveLHaLasciatoIlDitoEArrivaSullaCellaConIlRitaglioDellaMiniatura() {
    val content = Rect(0f, 550f, 1000f, 1300f)
    val gesture = ContentLayer(scale = 0.8f, translation = Offset(60f, 380f))
    val from = gesture.map(content)
    val cell = Rect(250f, 300f, 450f, 500f)
    val plan = FlightPlan.ToCell(from = from, to = cell, content = content)

    // Al via: esattamente il contenuto del gesto, ancora intero.
    val start = plan.at(0f)
    assertRect(start.map(content), from)
    assertRect(start.clip!!, content)

    // All'arrivo: il ritaglio al centro con la forma della cella, posato sulla cella.
    val end = plan.at(1f)
    val window = ViewerDismissSpec.centerCrop(content, 1f)
    assertRect(end.clip!!, window)
    assertRect(end.map(window), cell)

    // A meta': il rettangolo sullo schermo e' a meta' strada (niente salti), e la scala e' uniforme (niente deformazioni).
    val mid = plan.at(0.5f)
    val shown = mid.map(mid.clip!!)
    assertRect(shown, Rect((from.left + cell.left) / 2f, (from.top + cell.top) / 2f, (from.right + cell.right) / 2f, (from.bottom + cell.bottom) / 2f))
  }

  @Test
  fun senzaCellaSiAllontanaVersoIlBassoRimpicciolendosiESfumando() {
    val content = Rect(0f, 550f, 1000f, 1300f)
    val from = ContentLayer(scale = 0.9f, translation = Offset(50f, 200f)).map(content)
    val plan = FlightPlan.Away(from = from, content = content, drop = 240f)

    val start = plan.at(0f)
    assertRect(start.map(content), from)
    assertThat(start.alpha).isEqualTo(1f)

    val end = plan.at(1f)
    val landed = end.map(content)
    assertThat(end.alpha).isEqualTo(0f)
    assertThat(landed.width).isWithin(0.5f).of(from.width * ViewerDismissSpec.AwayScale)
    assertThat(landed.center.x).isWithin(0.5f).of(from.center.x)
    assertThat(landed.center.y).isWithin(0.5f).of(from.center.y + 240f)
  }

  @Test
  fun ilPianoDelVolo_allaCellaSeSiConosce_altrimentiVia() {
    val content = Rect(0f, 550f, 1000f, 1300f)
    val stage = androidx.compose.ui.geometry.Size(1000f, 2000f)
    assertThat(ViewerDismissState.planFor(content, content, Rect(0f, 0f, 100f, 100f), stage)).isInstanceOf(FlightPlan.ToCell::class.java)
    assertThat(ViewerDismissState.planFor(content, content, null, stage)).isInstanceOf(FlightPlan.Away::class.java)
    // Una cella vuota (fuori misura) non e' una cella.
    assertThat(ViewerDismissState.planFor(content, content, Rect(10f, 10f, 10f, 10f), stage)).isInstanceOf(FlightPlan.Away::class.java)
  }

  @Test
  fun laVelocitaDelVoloEQuellaDelDitoVersoLaCella() {
    val from = Rect(0f, 1000f, 1000f, 1750f)
    val cell = Rect(450f, 0f, 550f, 100f)
    // Il centro va da (500, 1375) a (500, 50): 1325 px verso l'alto.
    assertThat(ViewerDismissSpec.flightVelocity(Offset(0f, -2650f), from, cell)).isWithin(EPS).of(2f)
    // Un dito che va dall'altra parte non spinge indietro.
    assertThat(ViewerDismissSpec.flightVelocity(Offset(0f, 2650f), from, cell)).isEqualTo(0f)
    // Un lancio fortissimo non fa rimbalzare.
    assertThat(ViewerDismissSpec.flightVelocity(Offset(0f, -100_000f), from, cell)).isEqualTo(ViewerDismissSpec.MaxFlightVelocity)
  }

  private fun dismisses(dragProgress: Float = 0f, pinch: Float = 1f, velocityYDp: Float = 0f, pinchRate: Float = 0f): Boolean =
    ViewerDismissSpec.shouldDismiss(dragProgress, pinch, velocityYDp, pinchRate)

  private companion object {
    const val EPS = 1e-4f
  }
}

internal fun assertRect(actual: Rect, expected: Rect, tolerance: Float = 0.5f) {
  assertThat(actual.left).isWithin(tolerance).of(expected.left)
  assertThat(actual.top).isWithin(tolerance).of(expected.top)
  assertThat(actual.right).isWithin(tolerance).of(expected.right)
  assertThat(actual.bottom).isWithin(tolerance).of(expected.bottom)
}
