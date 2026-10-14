package dev.pampa.mycloudgallery.core.designsystem.grid

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.designsystem.design.ContentRange
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryLegibility
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryOnPhoto
import org.junit.Test

/**
 * Il **test dei bordi della cella** (G2, `docs/grafica-progetto.md` §12): i segni di presenza (solo telefono, solo NAS,
 * sul telefono e sul NAS, al sicuro, da verificare) stanno in alto a destra e non toccano mai il cerchio della selezione,
 * la durata di un video ne' i segni del contenuto, su ogni cella dai 64 dp in su, quadrata o di qualunque proporzione
 * (la griglia giustificata), a due densita'. Sotto i 64 dp di lato corto nessun segno, come prima. E il glifo bianco sullo
 * scrim regge 3:1 (anzi 4,5) sopra qualunque foto.
 */
class GalleryCellBadgesTest {

  private val densities = listOf(Density(2.625f, 1f), Density(2f, 1.3f))

  /** Le larghezze di testo di una durata: niente, "0:42", "12:05", "1:03:20" (in pixel, a 2,6x circa). */
  private val durations = listOf(null, 52f, 66f, 92f)

  private fun Rect.overlapsWithGap(other: Rect, gap: Float): Boolean =
    left < other.right + gap && other.left < right + gap && top < other.bottom + gap && other.top < bottom + gap

  private fun Rect.inside(width: Float, height: Float): Boolean = left >= 0f && top >= 0f && right <= width + 0.01f && bottom <= height + 0.01f

  @Test
  fun nessunSegnoNeToccaUnAltro() {
    var checked = 0
    for (density in densities) {
      val m = badgeMetrics(density)
      val sides = (64..420 step 9).map { it * density.density }
      for (w in sides) for (h in sides) {
        for (glyphs in 0..2) for (duration in durations) for (presence in listOf(null) + GalleryPresence.entries) {
          val slots = badgeSlots(w, h, m, glyphs, duration, presence)
          val placed = listOfNotNull(slots.start, slots.end, slots.presence)
          for (rect in placed + slots.indicator) {
            assertWithMessage("cella ${w}x$h: $rect sta dentro").that(rect.inside(w, h)).isTrue()
          }
          // Il cerchio della selezione (in alto a sinistra) e ogni segno; i segni fra loro, con lo spazio di una fila.
          for (rect in placed) {
            assertWithMessage("cella ${w}x$h: $rect e il cerchio della selezione ${slots.indicator}")
              .that(rect.overlapsWithGap(slots.indicator, 0f)).isFalse()
          }
          for (i in placed.indices) for (j in i + 1 until placed.size) {
            assertWithMessage("cella ${w}x$h: ${placed[i]} e ${placed[j]}").that(placed[i].overlapsWithGap(placed[j], 0f)).isFalse()
          }
          checked++
        }
      }
    }
    assertThat(checked).isGreaterThan(10_000)
  }

  /** La presenza c'e' su ogni cella dai 64 dp in su (e' l'informazione della Fase 4): non la scaccia nessun altro segno. */
  @Test
  fun laPresenzaCeSempreDai64Dp() {
    for (density in densities) {
      val m = badgeMetrics(density)
      for (side in 64..420 step 4) {
        val px = side * density.density
        for (presence in GalleryPresence.entries) {
          val slots = badgeSlots(px, px, m, contentGlyphs = 2, durationTextWidth = 92f, presence = presence)
          assertWithMessage("$presence su $side dp").that(slots.presence).isNotNull()
          assertWithMessage("durata su $side dp").that(slots.end).isNotNull()
        }
      }
    }
  }

  /** Come prima della G2: sotto i 64 dp di lato corto (il livello Anni) la cella resta pulita. */
  @Test
  fun sottoI64DpNessunSegno() {
    val density = densities.first()
    val m = badgeMetrics(density)
    val px = 63 * density.density
    val slots = badgeSlots(px, px, m, contentGlyphs = 2, durationTextWidth = 52f, presence = GalleryPresence.PhoneOnly)
    assertThat(listOfNotNull(slots.start, slots.end, slots.presence)).isEmpty()
  }

  /** La gerarchia: gli stati che chiedono attenzione hanno il disco intero, quelli di tutti i giorni il piccolo. */
  @Test
  fun laGerarchiaLaFaLaTaglia() {
    val m = badgeMetrics(densities.first())
    val side = 120 * densities.first().density
    val loud = GalleryPresence.entries.filter { it.prominent }.map { badgeSlots(side, side, m, 0, null, it).presence!!.width }.toSet()
    val quiet = GalleryPresence.entries.filter { !it.prominent }.map { badgeSlots(side, side, m, 0, null, it).presence!!.width }.toSet()
    assertThat(loud).containsExactly(m.presenceDisc)
    assertThat(quiet).containsExactly(m.quietDisc)
    assertThat(m.quietDisc).isLessThan(m.presenceDisc)
    assertThat(GalleryPresence.entries.filter { it.prominent })
      .containsExactly(GalleryPresence.PhoneOnly, GalleryPresence.NasOnly, GalleryPresence.ToVerify)
  }

  /** Il glifo bianco sullo scrim dei segni regge 4,5:1 (le icone ne chiedono 3) sopra le foto peggiori. */
  @Test
  fun ilGlifoSiLeggeSopraQualunqueFoto() {
    val scrim = Color(0xFF000000).copy(alpha = GalleryOnPhoto.ScrimAlpha)
    val photos = listOf(
      Color(0xFFFFFFFF), Color(0xFF000000), Color(0xFF808080), Color(0xFFFFE600), Color(0xFF00E6FF), Color(0xFFFF00FF), Color(0xFF00C800),
    )
    val worst = GalleryLegibility.worstContrast(GalleryOnPhoto.Content, scrim, ContentRange(photos))
    assertThat(worst).isAtLeast(GalleryLegibility.TextContrast)
  }
}
