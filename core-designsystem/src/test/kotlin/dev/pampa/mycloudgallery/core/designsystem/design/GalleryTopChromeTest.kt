package dev.pampa.mycloudgallery.core.designsystem.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
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

  // --- GS-5: il percorso dei colori fra due scelte del vetro adattivo --------------------------------------------------------------

  private val black = Color(0xFF000000)
  private val white = Color(0xFFFFFFFF)
  private val palette = GalleryChromeStyles.adaptivePaletteFor(black)

  /** Cio' che puo' passare sotto un pezzo di chrome: i poli, i grigi, le foto sature, le righe bianche e nere. */
  private val contents: List<ContentRange> = listOf(
    listOf(black),
    listOf(white),
    listOf(Color(0xFFF2F2F2)),
    listOf(Color(0xFF777777)),
    listOf(Color(0xFF202020), Color(0xFF404040)),
    listOf(white, black),
    listOf(Color(0xFFFF0000), Color(0xFFFFE600), Color(0xFF00E6FF)),
    listOf(Color(0xFF0000FF), Color(0xFF333333)),
  ).map { ContentRange(it) }

  /**
   * GS-5 (review della beta): da ogni scelta a ogni altra (tutte le coppie di contenuti, partendo da tutte e due le polarita'), il
   * percorso mostrato da [adaptiveFilmAlpha], campionato lungo la molla (anche oltre i capi: il rimbalzo), **non scende mai sotto
   * 4,5:1 sul contenuto di arrivo**.
   */
  @Test
  fun ilPercorsoFraDueScelteNonScendeMaiSottoLaSoglia() {
    var polarityChanges = 0
    for (fromRange in contents) for (toRange in contents) for (startDark in listOf(true, false)) {
      val from = GalleryLegibility.adapt(fromRange, palette, previousDark = startDark)
      val to = GalleryLegibility.adapt(toRange, palette, previousDark = from.darkInk)
      if (from.darkInk != to.darkInk) polarityChanges++
      for (step in -SpringOvershootSteps..PathSteps + SpringOvershootSteps) {
        val t = step / PathSteps.toFloat()
        val animated = from.film.alpha + (to.film.alpha - from.film.alpha) * t
        val shown = to.film.copy(alpha = adaptiveFilmAlpha(animated, animatedDark = from.darkInk, to = to))
        val contrast = GalleryLegibility.worstContrast(to.ink, shown, toRange)
        assertWithMessage("${fromRange.samples} -> ${toRange.samples}, a $t: pellicola $shown").that(contrast).isAtLeast(to.contrast - Epsilon)
        assertThat(contrast).isAtLeast(minOf(GalleryLegibility.TextContrast, to.contrast) - Epsilon)
      }
    }
    assertWithMessage("fra i casi ci sono cambi di polarita'").that(polarityChanges).isGreaterThan(0)
  }

  /**
   * Il numero del critico, per memoria: animando inchiostro e pellicola separatamente con la stessa molla (com'era), dal chiaro sul
   * nero allo scuro sul bianco, a meta' strada il testo sta a circa 2,56:1; gli estremi sono a 21:1 e a piu' di 20:1.
   */
  @Test
  fun animandoInchiostroEPellicolaInsiemeAMetaStradaIlTestoNonSiLeggeva() {
    val from = GalleryLegibility.adapt(ContentRange(listOf(black)), palette, previousDark = false)
    val to = GalleryLegibility.adapt(ContentRange(listOf(white)), palette, previousDark = from.darkInk)
    assertThat(from.darkInk).isFalse()
    assertThat(to.darkInk).isTrue()
    assertThat(from.contrast).isAtLeast(20f)
    assertThat(to.contrast).isAtLeast(20f)
    val middle = GalleryLegibility.contrastOver(lerp(from.ink, to.ink, 0.5f), lerp(from.film, to.film, 0.5f), white)
    assertThat(middle).isLessThan(3f)
    // Ora la polarita' cambia in un fotogramma: a qualunque punto della molla si mostra la pellicola di arrivo.
    assertThat(adaptiveFilmAlpha(animated = from.film.alpha, animatedDark = from.darkInk, to = to)).isEqualTo(to.film.alpha)
  }

  private companion object {
    const val PathSteps = 20

    /** La molla standard arriva con un rimbalzo: il percorso si campiona anche un po' oltre i due capi. */
    const val SpringOvershootSteps = 4
    const val Epsilon = 1e-3f
  }
}
