package dev.pampa.mycloudgallery.core.designsystem.design

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * La sfumatura in alto della chrome condivisa (Fase 4B, P16: spostata qui da feature-library perche' la usino anche le pagine della 4B):
 * piena sotto la barra di stato, leggera sotto i pezzi di chrome, a zero poco sotto. Gli stessi numeri di prima (G2).
 */
class GalleryTopChromeTest {

  private val stops = galleryTopEdgeStops(statusBar = 24.dp, topChrome = 80.dp)

  @Test
  fun leTappeSonoQuelleDelG2() {
    assertThat(stops.map { it.second }).containsExactly(0.85f, 0.55f, 0.15f, 0f).inOrder()
    assertThat(stops.map { it.first }).containsExactly(0.dp, 24.dp, 80.dp, 124.dp).inOrder()
  }

  @Test
  fun lOpacitaScendeSenzaSaltiFraLeTappe() {
    assertThat(galleryEdgeAlphaAt(stops, (-5).dp)).isEqualTo(0.85f)
    assertThat(galleryEdgeAlphaAt(stops, 24.dp)).isEqualTo(0.55f)
    assertThat(galleryEdgeAlphaAt(stops, 52.dp)).isWithin(1e-4f).of(0.35f)
    assertThat(galleryEdgeAlphaAt(stops, 80.dp)).isEqualTo(0.15f)
    assertThat(galleryEdgeAlphaAt(stops, 500.dp)).isEqualTo(0f)
    val samples = (0..130).map { galleryEdgeAlphaAt(stops, it.dp) }
    assertThat(samples.zipWithNext().all { (a, b) -> b <= a }).isTrue()
  }
}
