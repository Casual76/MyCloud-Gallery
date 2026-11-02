package dev.pampa.mycloudgallery.core.designsystem.a11y

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertWithMessage
import dev.antigravity.fluidengine.foundation.AccentMode
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.ui.theme.fluidColorScheme
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryLegibility
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryAccent
import org.junit.Test

/**
 * Fase 10, P8 (D26, "Fatto quando": contrasto di almeno 4,5:1 nel caso peggiore con il contrasto alto): lo schema a contrasto alto,
 * verificato con la formula WCAG su **ogni** accento (l'Ametista e tutti i preset Fluid), in chiaro e scuro. Le soglie sono quelle
 * dell'AAA dove si puo' (testo e accenti 7:1) e comunque mai sotto il 4,5:1 chiesto dal progetto.
 */
class GalleryHighContrastTest {

  private data class Case(val name: String, val base: ColorScheme, val dark: Boolean) {
    val high: ColorScheme get() = highContrastScheme(base, dark)
  }

  private val cases: List<Case> = GalleryAccent.presets.flatMap { preset ->
    listOf(false, true).map { dark ->
      val settings = EngineSettings(accentMode = AccentMode.CUSTOM_PRESET, customAccentName = preset.name)
      Case(
        name = "${preset.name} ${if (dark) "scuro" else "chiaro"}",
        base = fluidColorScheme(settings = settings, isDark = dark, brand = GalleryAccent.Amethyst, presets = GalleryAccent.presets),
        dark = dark,
      )
    }
  }

  private fun assertAtLeast(minimum: Float, case: Case, what: String, foreground: Color, background: Color) {
    val value = GalleryLegibility.contrast(foreground, background)
    assertWithMessage("${case.name}, $what: $value").that(value).isAtLeast(minimum)
  }

  @Test
  fun ilTestoSullaCartaHa7a1() {
    for (case in cases) {
      val s = case.high
      assertAtLeast(7f, case, "onSurface su surface", s.onSurface, s.surface)
      assertAtLeast(7f, case, "onBackground su background", s.onBackground, s.background)
      assertAtLeast(7f, case, "onSurfaceVariant su surfaceVariant", s.onSurfaceVariant, s.surfaceVariant)
      assertAtLeast(7f, case, "onSurface su surfaceContainerHighest", s.onSurface, s.surfaceContainerHighest)
      assertAtLeast(7f, case, "onPrimaryContainer su primaryContainer", s.onPrimaryContainer, s.primaryContainer)
      assertAtLeast(7f, case, "onErrorContainer su errorContainer", s.onErrorContainer, s.errorContainer)
    }
  }

  @Test
  fun gliAccentiSullaCartaHanno7a1ELeScrittoSopraAgliAccentiPure() {
    for (case in cases) {
      val s = case.high
      for ((name, accent, on) in listOf(
        Triple("primary", s.primary, s.onPrimary),
        Triple("secondary", s.secondary, s.onSecondary),
        Triple("tertiary", s.tertiary, s.onTertiary),
        Triple("error", s.error, s.onError),
      )) {
        assertAtLeast(7f, case, "$name sulla carta", accent, s.surface)
        assertAtLeast(7f, case, "on$name sull'accento", on, accent)
      }
    }
  }

  @Test
  fun iBordiSonoVisibili() {
    for (case in cases) {
      val s = case.high
      assertAtLeast(7f, case, "outline sulla carta", s.outline, s.surface)
      assertAtLeast(3f, case, "outlineVariant sulla carta", s.outlineVariant, s.surface)
    }
  }

  @Test
  fun lAccentoRestaRiconoscibile() {
    // Il contrasto alto non spegne il marchio: l'accento resta piu' vicino al suo colore che all'inchiostro puro (o almeno distinguibile).
    for (case in cases) {
      val s = case.high
      assertWithMessage("${case.name}: l'accento primario e' identico all'inchiostro").that(s.primary == s.onSurface).isFalse()
    }
  }

  @Test
  fun laCartaEPiena() {
    for (case in cases) {
      val s = case.high
      assertWithMessage("${case.name}: surface con alfa").that(s.surface.alpha).isEqualTo(1f)
      assertWithMessage("${case.name}: background con alfa").that(s.background.alpha).isEqualTo(1f)
    }
  }

  @Test
  fun leSuperficiPienePerOgniCombinazioneDiRichieste() {
    for ((high, reduce, solid) in listOf(
      Triple(false, false, false),
      Triple(true, false, true),
      Triple(false, true, true),
      Triple(true, true, true),
    )) {
      assertWithMessage("alto=$high meno=$reduce").that(GalleryAccessibilityPrefs(highContrast = high, reduceTransparency = reduce).solidSurfaces).isEqualTo(solid)
    }
  }

  @Test
  fun leImpostazioniDelSistemaDiventanoRichieste() {
    val r = GalleryAccessibilityReader
    assertWithMessage("standard").that(r.fromValues(0f, highTextContrast = false, reduceTransparency = false)).isEqualTo(GalleryAccessibilityPrefs.Standard)
    assertWithMessage("medio").that(r.fromValues(0.5f, highTextContrast = false, reduceTransparency = false).highContrast).isTrue()
    assertWithMessage("alto").that(r.fromValues(1f, highTextContrast = false, reduceTransparency = false).highContrast).isTrue()
    assertWithMessage("testo ad alto contrasto").that(r.fromValues(0f, highTextContrast = true, reduceTransparency = false).highContrast).isTrue()
    assertWithMessage("meno trasparenza").that(r.fromValues(0f, highTextContrast = false, reduceTransparency = true)).isEqualTo(GalleryAccessibilityPrefs(reduceTransparency = true))
    assertWithMessage("valore impossibile").that(r.fromValues(Float.NaN, highTextContrast = false, reduceTransparency = false).highContrast).isFalse()
    assertWithMessage("contrasto ridotto non e' piu' contrasto").that(r.fromValues(-1f, highTextContrast = false, reduceTransparency = false).highContrast).isFalse()
  }
}
