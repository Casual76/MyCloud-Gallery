package dev.pampa.mycloudgallery.feature.viewer.info

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.adaptive.GalleryWindow
import dev.pampa.mycloudgallery.feature.viewer.page.ContentLayer
import dev.pampa.mycloudgallery.feature.viewer.page.fitRect
import org.junit.Test

/**
 * H2: la geometria del pannello informazioni come l'ha descritta l'utente (la Galleria di Samsung). Per proporzioni diverse (verticale, 4:3,
 * 16:9 orizzontale, panorama, screenshot lungo) e per ogni avanzamento: dove sta il contenuto, quanto e' grande, dov'e' il bordo del pannello, e
 * che le fasi si tocchino senza salti.
 */
class InfoGeometryTest {

  /** Un telefono in verticale: 1000 x 2000 px. Meta' schermo e' 1000. */
  private val phone = Size(1000f, 2000f)

  private fun content(aspect: Float) = fitRect(phone, aspect)

  private fun at(aspect: Float, progress: Float, lift: Float = 0f): Rect {
    val rest = content(aspect)
    return InfoGeometry.contentLayer(phone, rest, InfoGeometry.panelTop(phone.height, progress, lift)).map(rest)
  }

  @Test
  fun ilPannelloApertoEAltoEsattamenteMetaSchermo() {
    assertThat(InfoGeometry.panelTop(2000f, progress = 0f)).isEqualTo(2000f)
    assertThat(InfoGeometry.panelTop(2000f, progress = 1f)).isEqualTo(1000f)
    assertThat(InfoGeometry.panelTop(2000f, progress = 0.5f)).isEqualTo(1500f)
    // Un pannello lungo che cresce scorrendo va oltre la meta'.
    assertThat(InfoGeometry.panelTop(2000f, progress = 1f, lift = 300f)).isEqualTo(700f)
  }

  @Test
  fun primaOccupaLaFasciaVuotaSottoIlContenutoSenzaMuoverlo() {
    // Una foto 4:3 orizzontale: alta 750, dal 625 al 1375. La fascia vuota sotto e' di 625.
    val rest = content(4f / 3f)
    assertThat(rest.bottom).isWithin(EPS).of(1375f)
    assertThat(InfoGeometry.contentLayer(phone, rest, panelTop = 1500f)).isEqualTo(ContentLayer.Identity)
    assertThat(InfoGeometry.contentLayer(phone, rest, panelTop = 1375f)).isEqualTo(ContentLayer.Identity)
  }

  @Test
  fun unaFotoVerticaleSaleColPannello_laParteAltaEsceDalloSchermo_nienteRiduzione() {
    // 3:4 verticale: larga 1000, alta 1333, dal 333 al 1667.
    val open = at(3f / 4f, progress = 1f)
    assertThat(open.bottom).isWithin(EPS).of(1000f)
    assertThat(open.height).isWithin(EPS).of(content(3f / 4f).height)
    assertThat(open.top).isLessThan(0f)
    assertThat(open.width).isWithin(EPS).of(1000f)
  }

  @Test
  fun unaFotoOrizzontaleSiIngrandisceQuantoBastaARiempireLaMetaAlta() {
    // 4:3: a pannello aperto riempie la meta' alta (da 0 a 1000), tagliata ai lati.
    val open = at(4f / 3f, progress = 1f)
    assertRect(open, Rect(-166.67f, 0f, 1166.67f, 1000f))
    // 16:9 in verticale: lo stesso, con un ingrandimento di 1,78.
    val wide = at(16f / 9f, progress = 1f)
    assertThat(wide.top).isWithin(EPS).of(0f)
    assertThat(wide.bottom).isWithin(EPS).of(1000f)
    assertThat(wide.width / content(16f / 9f).width).isWithin(0.01f).of(16f / 9f)
  }

  @Test
  fun aMetaCorsaIlBordoDelContenutoRestaAttaccatoAlBordoDelPannelloELaScalaCresceConLui() {
    val rest = content(4f / 3f)
    // Il pannello a meta' fra il bordo del contenuto (1375) e meta' schermo (1000).
    val top = 1187.5f
    val layer = InfoGeometry.contentLayer(phone, rest, top)
    assertThat(layer.map(rest).bottom).isWithin(EPS).of(top)
    assertThat(layer.scale).isWithin(0.001f).of((1f + 4f / 3f) / 2f)
    // Il centro resta al centro: il ritaglio e' uguale ai due lati.
    assertThat(layer.map(rest).center.x).isWithin(EPS).of(500f)
  }

  @Test
  fun unPanoramaNonDiventaUnDettaglio_siIngrandisceAlPiuDiDueVolte() {
    val open = at(4f, progress = 1f)
    assertThat(open.bottom).isWithin(EPS).of(1000f)
    assertThat(open.height).isWithin(EPS).of(content(4f).height * InfoGeometry.MaxFillScale)
    // Sopra resta del vuoto: meglio che una striscia irriconoscibile.
    assertThat(open.top).isGreaterThan(0f)
  }

  @Test
  fun unoScreenshotLunghissimoSaleSubito_eLaMetaBassaRestaInVista() {
    // 1:5 entra in altezza: dal bordo basso dello schermo il pannello lo tocca subito.
    val rest = content(0.2f)
    assertThat(rest.bottom).isWithin(EPS).of(2000f)
    val quarter = at(0.2f, progress = 0.25f)
    assertThat(quarter.bottom).isWithin(EPS).of(1750f)
    val open = at(0.2f, progress = 1f)
    assertThat(open.bottom).isWithin(EPS).of(1000f)
    assertThat(open.height).isWithin(EPS).of(2000f)
  }

  @Test
  fun leFasiSiToccanoSenzaSalti() {
    for (aspect in listOf(3f / 4f, 1f, 4f / 3f, 16f / 9f, 4f, 0.2f)) {
      val rest = content(aspect)
      // Al tocco: identita' da una parte e dall'altra del bordo.
      val justBefore = InfoGeometry.contentLayer(phone, rest, rest.bottom + 0.5f).map(rest)
      val justAfter = InfoGeometry.contentLayer(phone, rest, rest.bottom - 0.5f).map(rest)
      assertThat(justAfter.top - justBefore.top).isWithin(2f).of(-0.5f)
      // A meta' schermo: la crescita del pannello lungo continua da dove si e' fermata l'apertura.
      val atHalf = at(aspect, progress = 1f)
      val lifted = at(aspect, progress = 1f, lift = 1f)
      assertThat(lifted.height).isWithin(0.5f).of(atHalf.height)
      assertThat(lifted.bottom).isWithin(EPS).of(atHalf.bottom - 1f)
      // E passo per passo il contenuto non salta mai piu' del passo del pannello (piu' la crescita).
      var previous = at(aspect, progress = 0f)
      for (step in 1..100) {
        val now = at(aspect, progress = step / 100f)
        assertThat(kotlin.math.abs(now.bottom - previous.bottom)).isAtMost(10.5f)
        previous = now
      }
    }
  }

  @Test
  fun ilPannelloLungoPortaConSeIlContenuto() {
    val open = at(3f / 4f, progress = 1f)
    val lifted = at(3f / 4f, progress = 1f, lift = 400f)
    assertThat(lifted.bottom).isWithin(EPS).of(open.bottom - 400f)
    assertThat(lifted.height).isWithin(EPS).of(open.height)
  }

  @Test
  fun laColonnaLateraleFaStareIlContenutoNelloSpazioCheResta() {
    val tablet = Size(2000f, 1000f)
    val rest = fitRect(tablet, 1.6f)
    val open = InfoGeometry.sideLayer(tablet, rest, progress = 1f, panelWidth = 600f).map(rest)
    assertRect(open, Rect(0f, 62.5f, 1400f, 937.5f))
    assertThat(InfoGeometry.sideLayer(tablet, rest, progress = 0f, panelWidth = 600f)).isEqualTo(ContentLayer.Identity)
  }

  @Test
  fun laColonnaLateraleDaEstesaENelleFinestreBasseELarghe_ilFoglioAltrove() {
    assertThat(InfoGeometry.side(GalleryWindow.of(1280, 800))).isTrue()
    // Il telefono in orizzontale: 780 x 360.
    assertThat(InfoGeometry.side(GalleryWindow.of(780, 360))).isTrue()
    assertThat(InfoGeometry.side(GalleryWindow.of(360, 780))).isFalse()
    // Basso ma stretto (meta' schermo diviso): niente posto per una colonna.
    assertThat(InfoGeometry.side(GalleryWindow.of(420, 400))).isFalse()
  }

  // --- Lo scorrimento del pannello lungo ------------------------------------------------------------------------------

  @Test
  fun versoLAltoPrimaSiApre_poiScorre() {
    assertThat(InfoScroll.step(0f, 0f, dy = -500f, travel = 1000f, maxOffset = 300f)).isEqualTo(InfoDragStep(0.5f, 0f))
    val step = InfoScroll.step(0.9f, 0f, dy = -300f, travel = 1000f, maxOffset = 300f)
    assertThat(step.progress).isEqualTo(1f)
    assertThat(step.offset).isWithin(EPS).of(200f)
    // Oltre la fine non va.
    assertThat(InfoScroll.step(1f, 250f, dy = -100f, travel = 1000f, maxOffset = 300f).offset).isEqualTo(300f)
    // Un pannello corto non scorre.
    assertThat(InfoScroll.step(1f, 0f, dy = -100f, travel = 1000f, maxOffset = 0f)).isEqualTo(InfoDragStep(1f, 0f))
  }

  @Test
  fun versoIlBassoPrimaTornaInCima_poiSiChiude() {
    val step = InfoScroll.step(1f, 200f, dy = 350f, travel = 1000f, maxOffset = 300f)
    assertThat(step.offset).isEqualTo(0f)
    assertThat(step.progress).isWithin(EPS).of(0.85f)
  }

  @Test
  fun loScorrimentoPrimaFaCrescereIlPannello_poiScorronoLeInformazioni() {
    assertThat(InfoScroll.lift(100f, maxLift = 150f)).isEqualTo(100f)
    assertThat(InfoScroll.inner(100f, maxLift = 150f)).isEqualTo(0f)
    assertThat(InfoScroll.lift(300f, maxLift = 150f)).isEqualTo(150f)
    assertThat(InfoScroll.inner(300f, maxLift = 150f)).isEqualTo(150f)
  }

  private fun assertRect(actual: Rect, expected: Rect) {
    assertThat(actual.left).isWithin(0.5f).of(expected.left)
    assertThat(actual.top).isWithin(0.5f).of(expected.top)
    assertThat(actual.right).isWithin(0.5f).of(expected.right)
    assertThat(actual.bottom).isWithin(0.5f).of(expected.bottom)
  }

  private companion object {
    const val EPS = 0.5f
  }
}
