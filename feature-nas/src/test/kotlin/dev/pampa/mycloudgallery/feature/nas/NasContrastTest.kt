package dev.pampa.mycloudgallery.feature.nas

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.google.common.truth.Truth.assertWithMessage
import dev.antigravity.fluidengine.foundation.AccentMode
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.ui.theme.fluidColorScheme
import dev.pampa.mycloudgallery.core.designsystem.design.ContentRange
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeStyles
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryLegibility
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryAccent
import dev.pampa.mycloudgallery.feature.nas.ui.NasTone
import dev.pampa.mycloudgallery.feature.nas.ui.nasBarColor
import dev.pampa.mycloudgallery.feature.nas.ui.nasBarTrack
import dev.pampa.mycloudgallery.feature.nas.ui.nasChromeChoice
import dev.pampa.mycloudgallery.feature.nas.ui.nasToneStyle
import org.junit.Test

/**
 * Il contrasto di cio' che disegnano le pagine della 4B (P1 di `docs/grafica-progetto.md` §3), con la formula WCAG su **ogni**
 * accento in chiaro e scuro: il testo regge 4,5:1, le icone e i segni 3:1, sul **caso peggiore** del contenuto sotto.
 *
 *  - la chrome (etichetta, comandi, barra in fondo): pellicola e inchiostro del vetro adattivo, calcolati per il nero e il bianco
 *    pieni sotto la sfumatura della pagina; qui si ricalcola con le stesse funzioni e si controlla il numero che garantiscono;
 *  - il rosso dell'azione che cancella sulla pellicola;
 *  - il testo e i glifi sulla pagina e sulle schede (le coppie del tema che le pagine usano);
 *  - gli avvisi (i cinque toni), il cerchio di selezione, la barra di avanzamento.
 *
 * I numeri minimi trovati stanno nell'uscita del test (e nel report di P14). Sui pixel veri del vetro, la chrome di queste pagine e'
 * quella delle Raccolte e degli Eliminati di recente (stesso codice, stessa aritmetica): le misure di G2 (`§12.6`) valgono anche qui.
 */
class NasContrastTest {

  private data class Case(val name: String, val scheme: ColorScheme, val dark: Boolean)

  private val cases: List<Case> = GalleryAccent.presets.flatMap { preset ->
    listOf(false, true).map { dark ->
      val settings = EngineSettings(accentMode = AccentMode.CUSTOM_PRESET, customAccentName = preset.name)
      Case(
        name = "${preset.name} ${if (dark) "scuro" else "chiaro"}",
        scheme = fluidColorScheme(settings = settings, isDark = dark, brand = GalleryAccent.Amethyst, presets = GalleryAccent.presets),
        dark = dark,
      )
    }
  }

  private val minimums = linkedMapOf<String, Pair<Float, String>>()

  private fun record(what: String, value: Float, where: String) {
    val current = minimums[what]
    if (current == null || value < current.first) minimums[what] = value to where
  }

  private fun check(minimum: Float, what: String, where: String, value: Float) {
    record(what, value, where)
    assertWithMessage("$where: $what $value").that(value).isAtLeast(minimum)
  }

  private fun ratio(a: Color, b: Color): Float = GalleryLegibility.contrast(a, b)

  /** Il velo che la sfumatura in alto stende sotto i pezzi della riga (il piu' leggero: la tappa `EdgeRow`). */
  private fun veil(scheme: ColorScheme): Color = scheme.background.copy(alpha = 0.15f)

  @Test
  fun laChromeInAltoRegge4e5Sopra_NeroEBianco() {
    for (case in cases) {
      val palette = GalleryChromeStyles.adaptivePaletteFor(case.scheme.scrim)
      val choice = nasChromeChoice(palette.unknown, palette, case.dark, veil(case.scheme))
      check(GalleryLegibility.TextContrast, "chrome in alto: testo dell'etichetta e dei comandi", case.name, choice.contrast)
      // Il numero garantito e' quello vero: ricalcolato sui due estremi con la pellicola scelta.
      val seen = ContentRange(palette.unknown.samples.map { veil(case.scheme).compositeOver(it) })
      check(GalleryLegibility.TextContrast, "chrome in alto: ricalcolato sul caso peggiore", case.name, GalleryLegibility.worstContrast(choice.ink, choice.film, seen))
    }
  }

  @Test
  fun laBarraInFondoRegge4e5ELIconeCheCancellaNe3() {
    for (case in cases) {
      val palette = GalleryChromeStyles.adaptivePaletteFor(case.scheme.scrim)
      val choice = nasChromeChoice(palette.unknown, palette, case.dark, veil = null)
      check(GalleryLegibility.TextContrast, "barra in fondo: testo dei pulsanti", case.name, choice.contrast)
      val destructive = GalleryChromeStyles.legibleTowards(case.scheme.error, choice.ink, choice.film, palette.unknown, GalleryLegibility.LargeContrast)
      check(GalleryLegibility.LargeContrast, "barra in fondo: rosso dell'azione che cancella", case.name, GalleryLegibility.worstContrast(destructive, choice.film, palette.unknown))
    }
  }

  @Test
  fun ilTestoSullaPaginaESulleSchedeSiLegge() {
    for (case in cases) {
      val s = case.scheme
      check(GalleryLegibility.TextContrast, "titoli sulla pagina (onSurface su background)", case.name, ratio(s.onSurface, s.background))
      check(GalleryLegibility.TextContrast, "didascalie sulla pagina (onSurfaceVariant su background)", case.name, ratio(s.onSurfaceVariant, s.background))
      check(GalleryLegibility.TextContrast, "righe: titolo su scheda (onSurface su surfaceContainerLow)", case.name, ratio(s.onSurface, s.surfaceContainerLow))
      check(GalleryLegibility.TextContrast, "righe: sottotitolo su scheda (onSurfaceVariant su surfaceContainerLow)", case.name, ratio(s.onSurfaceVariant, s.surfaceContainerLow))
      check(GalleryLegibility.TextContrast, "fogli: testo sulla scheda", case.name, ratio(s.onSurface, s.surfaceContainerLow))
    }
  }

  @Test
  fun gliAvvisiHannoTestoEGlifoLeggibili() {
    for (case in cases) {
      for (tone in NasTone.entries) {
        val style = nasToneStyle(tone, case.scheme)
        check(GalleryLegibility.TextContrast, "avvisi: testo sul tono", "${case.name} $tone", ratio(style.text, style.container))
        check(GalleryLegibility.LargeContrast, "avvisi: glifo sul tono", "${case.name} $tone", ratio(style.glyph, style.container))
      }
    }
  }

  @Test
  fun ilCerchioDiSelezioneEIPulsantiSiVedono() {
    for (case in cases) {
      val s = case.scheme
      check(GalleryLegibility.LargeContrast, "cerchio di selezione: spunta su primary", case.name, ratio(s.onPrimary, s.primary))
      check(GalleryLegibility.LargeContrast, "cerchio di selezione vuoto: anello (onSurfaceVariant) su scheda", case.name, ratio(s.onSurfaceVariant, s.surfaceContainerLow))
      check(GalleryLegibility.TextContrast, "pulsante principale del foglio (onPrimary su primary)", case.name, ratio(s.onPrimary, s.primary))
      check(GalleryLegibility.TextContrast, "pulsante che cancella (onErrorContainer su errorContainer)", case.name, ratio(s.onErrorContainer, s.errorContainer))
      check(GalleryLegibility.TextContrast, "pulsante Annulla (onPrimaryContainer su primaryContainer)", case.name, ratio(s.onPrimaryContainer, s.primaryContainer))
    }
  }

  @Test
  fun laBarraDiAvanzamentoSiVedeSullaScheda() {
    for (case in cases) {
      val s = case.scheme
      // La pista e' `onSurface` al 10% sulla scheda; il riempimento (`primary`, portato verso `onSurface` se serve) deve distinguersi (3:1).
      check(GalleryLegibility.LargeContrast, "barra di avanzamento: riempimento sulla pista", case.name, ratio(nasBarColor(s), nasBarTrack(s)))
    }
  }

  @Test
  fun stampaIMinimiTrovati() {
    // Non e' una prova: lascia nell'uscita del test i numeri peggiori di ogni pezzo, da riportare.
    ilTestoSullaPaginaESulleSchedeSiLegge()
    laChromeInAltoRegge4e5Sopra_NeroEBianco()
    laBarraInFondoRegge4e5ELIconeCheCancellaNe3()
    gliAvvisiHannoTestoEGlifoLeggibili()
    ilCerchioDiSelezioneEIPulsantiSiVedono()
    laBarraDiAvanzamentoSiVedeSullaScheda()
    println("=== CONTRASTI MINIMI (peggiore fra ${cases.size} combinazioni di accento e tema) ===")
    minimums.forEach { (what, found) -> println("%.2f : %s [%s]".format(java.util.Locale.ROOT, found.first, what, found.second)) }
  }
}
