package dev.pampa.mycloudgallery.core.designsystem.chrome

import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.ui.fluid.FluidSearchFieldDefaults
import org.junit.Test

/**
 * La rivelazione della barra di ricerca (CG-2, giri 1 e 2): il campo, largo quanto la riga, si sposta a destra mentre si apre; il segnaposto compare solo
 * quando la parte nascosta non lo taglia piu', in dissolvenza, cosi' la barra sembra crescere e non una finestra che si apre su una scritta tagliata.
 * Le misure sono in dp (densita' 1).
 */
class GallerySearchRevealTest {

  private val circle = FluidSearchFieldDefaults.Height.value
  private val textStart = FluidSearchFieldDefaults.IconInset.value + FluidSearchFieldDefaults.IconSize.value + 10f

  @Test
  fun ilCampoParteDalCerchioEArrivaAlSuoPosto() {
    assertThat(searchRevealHidden(width = 328f, height = circle, progress = 0f)).isEqualTo(328f - circle)
    assertThat(searchRevealHidden(width = 328f, height = circle, progress = 1f)).isEqualTo(0f)
    // Una molla che va oltre non sposta il campo oltre il suo posto.
    assertThat(searchRevealHidden(width = 328f, height = circle, progress = 1.04f)).isEqualTo(0f)
  }

  @Test
  fun ilSegnapostoCompareSoloQuandoNonETagliato() {
    // Ogni riga da 300 a 600 dp, ogni segnaposto che entra fra la lente e la X: quando comincia a vedersi, la parte nascosta e' piu' piccola dello spazio
    // a destra del testo.
    for (width in 300..600 step 4) {
      val longest = width - PlaceholderEndInset.value - textStart
      for (text in listOf(40f, 120f, 196f, longest)) {
        val end = textStart + text
        val start = revealPlaceholderStart(width.toFloat(), circle, end)
        val hidden = searchRevealHidden(width.toFloat(), circle, start)
        assertThat(hidden).isAtMost(width - end + 0.01f)
        assertThat(revealPlaceholderAlpha(start, start)).isEqualTo(0f)
      }
    }
  }

  @Test
  fun ilSegnapostoArrivaInDissolvenza_eABarraApertaCeTutto() {
    val start = revealPlaceholderStart(328f, circle, textStart + 196f)
    assertThat(start).isAtLeast(0.6f)
    assertThat(revealPlaceholderAlpha(0.3f, start)).isEqualTo(0f)
    assertThat(revealPlaceholderAlpha((start + 0.99f) / 2f, start)).isIn(Range.open(0f, 1f))
    assertThat(revealPlaceholderAlpha(1f, start)).isEqualTo(1f)
  }
}
