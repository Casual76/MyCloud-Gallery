package dev.pampa.mycloudgallery.core.designsystem.chrome

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.antigravity.fluidengine.foundation.AccentMode
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.pampa.mycloudgallery.core.designsystem.design.ContentRange
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeDefaults
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryLegibility
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryAccent
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * K1 (GF-1): il menu ⋮ del viewer si legge sopra qualunque foto. Il pannello e' il pop-up dell'engine (la pellicola dei modali sopra lo scrim del pop-up
 * sopra la foto) con sotto le voci il velo di [GalleryMenuLegibility.onPhoto]. Sul caso peggiore (la foto bianca e quella nera dietro), in chiaro, in scuro
 * e in AMOLED, su ogni accento: le voci e la voce distruttiva reggono 4,5:1 col margine dei veli (5,5 di calcolo), e il rosso resta un rosso.
 *
 * La pellicola e lo schema si leggono dal tema vero (una composizione): sono quelli che l'engine usa per disegnare il pop-up, non una copia dei suoi numeri.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class GalleryMenuLegibilityTest {

  @get:Rule
  val composeRule = createComposeRule()

  private data class Case(val name: String, val scheme: ColorScheme, val film: Color, val dark: Boolean)

  private fun cases(): List<Case> {
    val modes = listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.AMOLED)
    val settings = GalleryAccent.presets.flatMap { preset ->
      modes.map { mode -> "${preset.name} $mode" to EngineSettings(themeMode = mode, accentMode = AccentMode.CUSTOM_PRESET, customAccentName = preset.name) }
    }
    val found = ArrayList<Case>()
    composeRule.setContent {
      Column {
        for ((name, setting) in settings) {
          MyCloudGalleryTheme(settings = setting) {
            val scheme = MaterialTheme.colorScheme
            val film = GlassDefaults.modalTint().overlay
            val dark = GlassDefaults.isDarkSurface()
            SideEffect { if (found.none { it.name == name }) found += Case(name, scheme, film, dark) }
          }
        }
      }
    }
    composeRule.waitForIdle()
    assertThat(found).hasSize(settings.size)
    return found
  }

  /** Il fondo sotto le voci sui due casi peggiori: il velo sopra la pellicola sopra lo scrim sopra la foto bianca o nera. */
  private fun backgrounds(case: Case, veil: Color): List<Color> =
    GalleryMenuLegibility.worstCase(case.scheme, case.film, case.dark).samples.map { veil.compositeOver(it) }

  @Test
  fun leVociELaVoceDistruttivaReggonoSopraLaFotoBiancaENera() {
    for (case in cases()) {
      val inks = GalleryMenuLegibility.onPhoto(case.scheme, case.film, case.dark, solid = false)
      for (background in backgrounds(case, inks.veil)) {
        val text = GalleryLegibility.contrast(inks.ink, background)
        val red = GalleryLegibility.contrast(inks.destructive, background)
        assertWithMessage("${case.name}: voci su $background ($text)").that(text).isAtLeast(GalleryChromeDefaults.MeasuredTarget)
        assertWithMessage("${case.name}: voce distruttiva su $background ($red)").that(red).isAtLeast(GalleryChromeDefaults.MeasuredTarget)
      }
    }
  }

  @Test
  fun senzaIlVeloIlRossoNonReggeva_eIlVeloEDavveroNecessario() {
    // La misura del critico: tema scuro sopra una foto chiara, il rosso dell'errore sulla sola pellicola del pop-up. E' il caso che il velo corregge.
    val dark = cases().first { it.name == "${GalleryAccent.AMETHYST_NAME} ${ThemeMode.DARK}" }
    val bare = GalleryMenuLegibility.worstCase(dark.scheme, dark.film, dark.dark)
    assertThat(GalleryLegibility.worstContrast(dark.scheme.error, Color.Transparent, bare)).isLessThan(GalleryLegibility.LargeContrast)
    assertThat(GalleryLegibility.worstContrast(dark.scheme.onSurface, Color.Transparent, bare)).isLessThan(GalleryLegibility.TextContrast)
    val inks = GalleryMenuLegibility.onPhoto(dark.scheme, dark.film, dark.dark, solid = false)
    assertThat(inks.veil.alpha).isGreaterThan(0f)
  }

  @Test
  fun ilRossoResteUnRosso_alPiuUnTerzoVersoIlTesto() {
    for (case in cases()) {
      val inks = GalleryMenuLegibility.onPhoto(case.scheme, case.film, case.dark, solid = false)
      // Il rosso e' l'errore portato verso il testo di una frazione (`lerp` dei colori, in Oklab): si cerca la frazione che lo da', e non supera il limite.
      val fraction = (0..1000).map { it / 1000f }.minBy { t -> distance(lerp(case.scheme.error, case.scheme.onSurface, t), inks.destructive) }
      assertWithMessage("${case.name}: ${inks.destructive}").that(distance(lerp(case.scheme.error, case.scheme.onSurface, fraction), inks.destructive)).isLessThan(0.01f)
      assertWithMessage("${case.name}: il rosso va verso il testo di $fraction").that(fraction).isAtMost(GalleryMenuLegibility.DestructiveShift + 0.002f)
      // E si vede ancora rosso: il canale rosso domina.
      assertWithMessage("${case.name}: ${inks.destructive}").that(inks.destructive.red).isGreaterThan(maxOf(inks.destructive.green, inks.destructive.blue) + 0.2f)
    }
  }

  private fun distance(a: Color, b: Color): Float = maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue))

  @Test
  fun conLeSuperficiPieneIlVeloEPienoELeVociReggono() {
    for (case in cases()) {
      val inks = GalleryMenuLegibility.onPhoto(case.scheme, case.film, case.dark, solid = true)
      assertThat(inks.veil.alpha).isEqualTo(1f)
      assertWithMessage(case.name).that(GalleryLegibility.contrast(inks.ink, inks.veil)).isAtLeast(GalleryLegibility.TextContrast)
      assertWithMessage(case.name).that(GalleryLegibility.contrast(inks.destructive, inks.veil)).isAtLeast(GalleryLegibility.TextContrast)
    }
  }

  @Test
  fun ilVeloDelTestoELoStessoDellaChromeDellaLibreria_eQuelloDelViewerNonEMaiPiuLeggero() {
    for (case in cases()) {
      val text = GalleryMenuLegibility.textVeil(case.scheme, case.film, case.dark, solid = false)
      // Il velo della Libreria regge le voci col margine...
      val range = ContentRange(backgrounds(case, Color.Transparent))
      assertWithMessage(case.name).that(GalleryLegibility.worstContrast(case.scheme.onSurface, text, range)).isAtLeast(GalleryChromeDefaults.MeasuredTarget)
      // ... e il viewer, che deve reggere anche il rosso, ne stende almeno altrettanto.
      val viewer = GalleryMenuLegibility.onPhoto(case.scheme, case.film, case.dark, solid = false)
      assertThat(viewer.veil.alpha).isAtLeast(text.alpha)
      assertThat(viewer.veil.copy(alpha = 1f)).isEqualTo(case.scheme.surface.copy(alpha = 1f))
    }
  }
}
