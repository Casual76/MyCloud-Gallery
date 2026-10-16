package dev.pampa.mycloudgallery.feature.backup

import androidx.compose.material3.ColorScheme
import com.google.common.truth.Truth.assertWithMessage
import dev.antigravity.fluidengine.foundation.AccentMode
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.ui.theme.fluidColorScheme
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryLegibility
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryAccent
import dev.pampa.mycloudgallery.feature.backup.ui.BackupTone
import dev.pampa.mycloudgallery.feature.backup.ui.backupToneStyle
import org.junit.Test

/**
 * Il contrasto di cio' che disegnano le schermate del backup (P1 di `docs/grafica-progetto.md` §3), con la formula WCAG su **ogni**
 * accento in chiaro e scuro: il testo di una scheda regge 4,5:1 sul suo riempimento, i glifi e la barra di avanzamento 3:1. I cinque
 * toni sono quelli del piede della Libreria e dell'indicatore di stato globale: `GalleryFooterStatusTest` verifica le stesse coppie per
 * le capsule, qui si verificano le schede che le schermate costruiscono con gli stessi ruoli dello schema. Le righe di Fluid (sorgenti,
 * impostazioni) sono dell'engine, con i suoi test; la chrome in alto e' `GalleryTopChrome` (`GalleryLegibilityTest`, misure G2 §12.6).
 */
class BackupContrastTest {

  private data class Case(val name: String, val scheme: ColorScheme)

  private val cases: List<Case> = GalleryAccent.presets.flatMap { preset ->
    listOf(false, true).map { dark ->
      val settings = EngineSettings(accentMode = AccentMode.CUSTOM_PRESET, customAccentName = preset.name)
      Case(
        name = "${preset.name} ${if (dark) "scuro" else "chiaro"}",
        scheme = fluidColorScheme(settings = settings, isDark = dark, brand = GalleryAccent.Amethyst, presets = GalleryAccent.presets),
      )
    }
  }

  @Test
  fun ilTestoDiOgniSchedaSiLegge() {
    for (case in cases) {
      for (tone in BackupTone.entries) {
        val style = backupToneStyle(tone, case.scheme)
        val ratio = GalleryLegibility.contrast(style.text, style.container)
        assertWithMessage("${case.name}, $tone: testo $ratio").that(ratio).isAtLeast(GalleryLegibility.TextContrast)
      }
    }
  }

  @Test
  fun iGlifiELaBarraDiAvanzamentoSiVedono() {
    for (case in cases) {
      for (tone in BackupTone.entries) {
        val style = backupToneStyle(tone, case.scheme)
        val glyph = GalleryLegibility.contrast(style.glyph, style.container)
        assertWithMessage("${case.name}, $tone: glifo $glyph").that(glyph).isAtLeast(GalleryLegibility.LargeContrast)
      }
      // La barra di avanzamento si riempie con il colore del testo della scheda "in corso": come il testo, regge 4,5:1.
      val working = backupToneStyle(BackupTone.Working, case.scheme)
      val bar = GalleryLegibility.contrast(working.text, working.container)
      assertWithMessage("${case.name}: barra $bar").that(bar).isAtLeast(GalleryLegibility.LargeContrast)
    }
  }

  @Test
  fun iCinqueToniSonoDistinti() {
    for (case in cases) {
      val containers = BackupTone.entries.map { backupToneStyle(it, case.scheme).container }.toSet()
      assertWithMessage("${case.name}: contenitori").that(containers.size).isAtLeast(4)
    }
  }
}
