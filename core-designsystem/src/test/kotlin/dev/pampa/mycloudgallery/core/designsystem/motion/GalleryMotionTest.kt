package dev.pampa.mycloudgallery.core.designsystem.motion

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** La logica pura dei tre gesti: espansione, swipe per chiudere, pinch fra i livelli. */
class GalleryMotionTest {

  // --- espansione -------------------------------------------------------------------------

  @Test
  fun lEspansioneParteDallaMiniaturaEArrivaAlTraguardo() {
    val thumb = Rect(10f, 20f, 60f, 70f)
    val full = Rect(0f, 0f, 400f, 800f)

    assertThat(GalleryExpandSpec.frame(0f, thumb, full)).isEqualTo(thumb)
    assertThat(GalleryExpandSpec.frame(1f, thumb, full)).isEqualTo(full)
    val half = GalleryExpandSpec.frame(0.5f, thumb, full)
    assertThat(half.left).isWithin(0.001f).of(5f)
    assertThat(half.bottom).isWithin(0.001f).of(435f)
  }

  // --- swipe giu' per chiudere ------------------------------------------------------------

  @Test
  fun ilProgressoDelTrascinamentoSaleConLaDistanzaESiFermaA1() {
    assertThat(GalleryDismissSpec.dragProgress(0f, 1000f)).isEqualTo(0f)
    assertThat(GalleryDismissSpec.dragProgress(-300f, 1000f)).isEqualTo(0f)
    assertThat(GalleryDismissSpec.dragProgress(250f, 1000f)).isWithin(0.001f).of(0.5f)
    assertThat(GalleryDismissSpec.dragProgress(5000f, 1000f)).isEqualTo(1f)
    assertThat(GalleryDismissSpec.dragProgress(100f, 0f)).isEqualTo(0f)
  }

  @Test
  fun ilViewerSiRimpicciolisceFinoAlMinimoEMaiOltre() {
    assertThat(GalleryDismissSpec.viewerProgress(0f)).isEqualTo(1f)
    assertThat(GalleryDismissSpec.viewerProgress(1f)).isWithin(0.0001f).of(GalleryDismissSpec.MinViewerProgress)
    assertThat(GalleryDismissSpec.viewerProgress(5f)).isWithin(0.0001f).of(GalleryDismissSpec.MinViewerProgress)
  }

  @Test
  fun siChiudeConAbbastanzaStradaOConUnLancioVeloce() {
    val density = 2f
    assertThat(GalleryDismissSpec.shouldDismiss(0.1f, 100f, density)).isFalse()
    assertThat(GalleryDismissSpec.shouldDismiss(GalleryDismissSpec.DismissDragProgress, 0f, density)).isTrue()
    // 1.900 px/s a 2x sono 950 dp/s: oltre la soglia di 900, anche con poca strada.
    assertThat(GalleryDismissSpec.shouldDismiss(0.05f, 1900f, density)).isTrue()
    assertThat(GalleryDismissSpec.shouldDismiss(0.05f, 1700f, density)).isFalse()
  }

  @Test
  fun ilDitoGuidaLOrologioDellEspansioneEIlRilascioDecide() = runBlocking {
    val state = GalleryViewerTransitionState(initialProgress = 1f)
    val height = 1000f

    state.dragBy(Offset(0f, 100f), height)
    assertThat(state.dragging).isTrue()
    assertThat(state.progress.value).isLessThan(1f)
    assertThat(state.drag.value.y).isEqualTo(100f)

    // Poca strada e nessuna velocita': torna a posto, aperto.
    val dismissedSmall = state.release(velocityY = 0f, density = 1f, stageHeight = height, reducedMotion = true)
    assertThat(dismissedSmall).isFalse()
    assertThat(state.progress.value).isEqualTo(1f)
    assertThat(state.drag.value).isEqualTo(Offset.Zero)

    // Tanta strada: si chiude, alla miniatura.
    state.dragBy(Offset(0f, 400f), height)
    val dismissedFar = state.release(velocityY = 0f, density = 1f, stageHeight = height, reducedMotion = true)
    assertThat(dismissedFar).isTrue()
    assertThat(state.progress.value).isEqualTo(0f)
    assertThat(state.drag.value).isEqualTo(Offset.Zero)
    assertThat(state.dragging).isFalse()
  }

  // --- pinch fra i livelli -----------------------------------------------------------------

  private val columns = listOf(7, 5, 4, 3)

  @Test
  fun aScalaUnoIlLivelloNonCambia() {
    columns.indices.forEach { level ->
      val result = GalleryPinchSpec.resolve(columns, level, 1f)
      assertThat(result.level).isEqualTo(level)
      assertThat(result.scale).isWithin(0.0001f).of(1f)
    }
  }

  @Test
  fun ilCambioDiLivelloConservaLaDimensioneApparenteDelleCelle() {
    // Le "colonne continue" colonne/scala sono quello che l'occhio vede: non devono saltare.
    val raw = 1.6f
    val result = GalleryPinchSpec.resolve(columns, level = 1, rawScale = raw)
    assertThat(result.level).isNotEqualTo(1)
    val before = columns[1] / raw
    val after = columns[result.level] / result.scale
    assertThat(abs(before - after)).isLessThan(0.001f)
  }

  @Test
  fun zoomAvantiVaVersoMenoColonneEZoomIndietroVersoPiuColonne() {
    assertThat(GalleryPinchSpec.resolve(columns, 1, 1.6f).level).isGreaterThan(1)
    assertThat(GalleryPinchSpec.resolve(columns, 1, 0.65f).level).isLessThan(1)
  }

  @Test
  fun agliEstremiLElasticoSiFermaEIlLivelloNonEsce() {
    val farIn = GalleryPinchSpec.resolve(columns, level = 3, rawScale = 50f)
    assertThat(farIn.level).isEqualTo(3)
    assertThat(columns[3] / farIn.scale).isWithin(0.001f).of(columns.min() / GalleryPinchSpec.EdgeSlack)

    val farOut = GalleryPinchSpec.resolve(columns, level = 0, rawScale = 0.01f)
    assertThat(farOut.level).isEqualTo(0)
    assertThat(columns[0] / farOut.scale).isWithin(0.001f).of(columns.max() * GalleryPinchSpec.EdgeSlack)
  }

  @Test
  fun lIsteresiEvitaLoSfarfallioAMetaStrada() {
    // Appena oltre la meta' fra due livelli, ma meno dell'isteresi: resta dov'e'.
    val midpoint = sqrt(5f * 4f)
    val justPast = GalleryPinchSpec.resolve(columns, level = 1, rawScale = 5f / (midpoint - 0.01f))
    assertThat(justPast.level).isEqualTo(1)
  }

  @Test
  fun ilPinchDelloStatoCambiaLivelloESiAssestaAUno() = runBlocking {
    val state = GalleryGridPinchState(initialLevel = 1)
    assertThat(state.columns).isEqualTo(5)

    state.onPinch(zoom = 1.7f, focalFraction = Offset(0.25f, 0.75f))
    assertThat(state.pinching).isTrue()
    assertThat(state.focal).isEqualTo(Offset(0.25f, 0.75f))
    assertThat(state.level).isGreaterThan(1)

    state.onRelease(reducedMotion = true)
    assertThat(state.pinching).isFalse()
    assertThat(state.scale.value).isEqualTo(1f)
  }

  @Test
  fun saltareAUnLivelloCambiaIColonne() = runBlocking {
    val state = GalleryGridPinchState(initialLevel = 0)
    state.jumpTo(3, reducedMotion = true)
    assertThat(state.level).isEqualTo(3)
    assertThat(state.columns).isEqualTo(3)
    assertThat(state.scale.value).isEqualTo(1f)
  }
}
