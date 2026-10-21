package dev.pampa.mycloudgallery.feature.viewer.livetext

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import org.junit.Test

/**
 * Il passaggio immagine / contenuto / schermo (Fase 6, P12, D45): a ogni livello di zoom un punto dell'immagine va sullo schermo e
 * ritorna, e il punto sotto il dito e' quello giusto. Le maniglie e la pressione prolungata usano questi conti.
 */
class ContentMappingTest {

  private val page = Size(824f, 1830f)
  private val aspect = 16f / 9f

  private fun near(a: Offset, b: Offset, tolerance: Float = 1e-3f) {
    assertThat(abs(a.x - b.x)).isAtMost(tolerance)
    assertThat(abs(a.y - b.y)).isAtMost(tolerance)
  }

  /** Come lo zoom di telephoto: scala intorno a un perno e spostamento. */
  private fun zoomed(scale: Float, offset: Offset, pivot: Offset = Offset.Zero) = ContentMapping(page, aspect, scale, scale, offset, pivot)

  @Test
  fun aUnoXLImmagineStaNelRettangoloFitAlCentro() {
    val mapping = ContentMapping.identity(page, aspect)
    assertThat(mapping.fit.width).isEqualTo(824f)
    assertThat(mapping.fit.height).isWithin(0.5f).of(463.5f)
    near(mapping.imageToScreen(Offset(0.5f, 0.5f)), Offset(412f, 915f), 0.5f)
    near(mapping.imageToScreen(Offset(0f, 0f)), Offset(0f, mapping.fit.top))
    assertThat(mapping.zoom).isEqualTo(1f)
  }

  @Test
  fun aOgniLivelloDiZoomLAndataERitornoTornano() {
    for (scale in listOf(1f, 1.5f, 2f, 3f, 5f, 10f, 20f, 40f)) {
      for (pivot in listOf(Offset.Zero, Offset(412f, 915f))) {
        val mapping = zoomed(scale, offset = Offset(-37f * scale, 21f * scale), pivot = pivot)
        for (p in listOf(Offset(0f, 0f), Offset(1f, 1f), Offset(0.3f, 0.7f), Offset(0.5f, 0.5f))) {
          near(mapping.screenToImage(mapping.imageToScreen(p)), p, 1e-3f)
        }
        assertThat(mapping.zoom).isEqualTo(scale)
      }
    }
  }

  @Test
  fun ingrandireIntornoAlCentroLasciaIlCentroDoveEra() {
    val center = Offset(412f, 915f)
    for (scale in listOf(2f, 6f, 25f)) {
      val mapping = zoomed(scale, Offset.Zero, pivot = center)
      near(mapping.imageToScreen(Offset(0.5f, 0.5f)), center, 0.5f)
    }
  }

  @Test
  fun unPuntoAllontanatoDalCentroSiAllontanaDiScalaVolte() {
    val center = Offset(412f, 915f)
    val one = ContentMapping.identity(page, aspect).imageToScreen(Offset(0.6f, 0.5f)).x - center.x
    for (scale in listOf(2f, 6f, 25f)) {
      val spread = zoomed(scale, Offset.Zero, pivot = center).imageToScreen(Offset(0.6f, 0.5f)).x - center.x
      assertThat(spread).isWithin(0.01f * scale).of(one * scale)
    }
  }

  @Test
  fun lospostamentoSegueIlDito() {
    val base = zoomed(4f, Offset.Zero)
    val moved = zoomed(4f, Offset(-120f, 80f))
    val p = Offset(0.4f, 0.45f)
    near(moved.imageToScreen(p), base.imageToScreen(p) + Offset(-120f, 80f))
  }

  @Test
  fun leTolleranzeDelDitoSiTraduconoInAltezzeDellImmagine() {
    val one = ContentMapping.identity(page, aspect)
    // 12 dp a xhdpi = 24 px: sull'immagine intera (463 px alta) e' circa il 5% dell'altezza.
    assertThat(one.pxToImageHeights(24f)).isWithin(0.002f).of(24f / one.fit.height)
    // A 10x lo stesso dito copre un decimo dell'immagine: il dito resta preciso.
    assertThat(zoomed(10f, Offset.Zero).pxToImageHeights(24f)).isWithin(1e-4f).of(24f / (one.fit.height * 10f))
  }

  @Test
  fun unaProporzioneNonNotaNonRompeLaMappa() {
    val mapping = ContentMapping(page, aspect = 0f)
    near(mapping.imageToScreen(Offset(0.5f, 0.5f)), Offset(412f, 915f), 0.5f)
    near(mapping.screenToImage(Offset(412f, 915f)), Offset(0.5f, 0.5f), 1e-3f)
  }
}
