package dev.pampa.mycloudgallery.core.designsystem.design

import androidx.compose.material3.ColorScheme
import com.google.common.truth.Truth.assertWithMessage
import dev.antigravity.fluidengine.foundation.AccentMode
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.ui.theme.fluidColorScheme
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryAccent
import org.junit.Test

/**
 * Il piede delle griglie (G2): le capsule della rete e del NAS si leggono su **ogni** accento, in chiaro e in scuro (testo
 * 4,5:1 sul contenitore, glifo 3:1), e il dettaglio sotto (`onSurfaceVariant` sulla pagina) regge 4,5:1. Il piede sta
 * sulla pagina, non sopra le foto: qui il contrasto e' quello del tema, e lo si verifica sul tema.
 */
class GalleryFooterStatusTest {

  private val schemes: List<Pair<String, ColorScheme>> = GalleryAccent.presets.flatMap { preset ->
    listOf(false, true).map { dark ->
      val settings = EngineSettings(accentMode = AccentMode.CUSTOM_PRESET, customAccentName = preset.name)
      "${preset.name} ${if (dark) "scuro" else "chiaro"}" to fluidColorScheme(settings = settings, isDark = dark, brand = GalleryAccent.Amethyst, presets = GalleryAccent.presets)
    }
  }

  @Test
  fun ogniTonoDelPiedeSiLegge() {
    for ((name, scheme) in schemes) {
      for (tone in GalleryFooterTone.entries) {
        val style = galleryFooterStyle(tone, scheme)
        val text = GalleryLegibility.contrast(style.text, style.container)
        val glyph = GalleryLegibility.contrast(style.glyph, style.container)
        assertWithMessage("$name, $tone: testo $text").that(text).isAtLeast(GalleryLegibility.TextContrast)
        assertWithMessage("$name, $tone: glifo $glyph").that(glyph).isAtLeast(GalleryLegibility.LargeContrast)
      }
    }
  }

  @Test
  fun ilDettaglioSottoLeCapsuleSiLeggeSullaPagina() {
    for ((name, scheme) in schemes) {
      val c = GalleryLegibility.contrast(scheme.onSurfaceVariant, scheme.background)
      assertWithMessage("$name: dettaglio $c").that(c).isAtLeast(GalleryLegibility.TextContrast)
    }
  }

  /**
   * Le tre voci del piede (rete, NAS e, dalla Fase 5A, backup) hanno **solo questi cinque toni**: `ogniTonoDelPiedeSiLegge` li verifica
   * su ogni accento, chiaro e scuro, e quindi vale anche per la terza voce. Un sesto tono non passerebbe di qui senza essere
   * verificato (`BackupFooterProblemsTest` di feature-library controlla che i problemi del backup ne usino solo di questi).
   */
  @Test
  fun leTreVociDelPiedeUsanoSoloITreToniVerificati() {
    assertWithMessage("toni del piede").that(GalleryFooterTone.entries.map { it.name })
      .containsExactly("Calm", "Working", "Waiting", "Attention", "Alarm").inOrder()
  }

  /** I toni sono distinti: il colore non e' l'unico segnale (c'e' sempre la frase), ma i cinque non si confondono. */
  @Test
  fun iToniSonoDiversi() {
    for ((name, scheme) in schemes) {
      val containers = GalleryFooterTone.entries.map { galleryFooterStyle(it, scheme).container }.toSet()
      assertWithMessage("$name: contenitori").that(containers.size).isAtLeast(4)
    }
  }
}
