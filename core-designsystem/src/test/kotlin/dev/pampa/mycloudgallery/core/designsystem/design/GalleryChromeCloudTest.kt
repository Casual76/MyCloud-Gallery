package dev.pampa.mycloudgallery.core.designsystem.design

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.antigravity.fluidengine.foundation.AccentMode
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.ui.theme.fluidColorScheme
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryAccent
import org.junit.Test

/**
 * La nuvola di stato della chrome (B2 della beta) e la leggibilita' sotto il vetro (B7): sopra le foto chiare (e scure) il segno del
 * tono della nuvola (errore, attenzione) ha l'anello dell'inchiostro, che regge 4,5:1 sopra la pellicola adattiva come il resto della
 * chrome (data, contatori, "Fine", la nuvola stessa); dentro il tono pieno, riconoscibile, e il punto esclamativo a 4,5:1 sopra di
 * lui. Su **ogni** accento, chiaro e scuro, e sui contenuti peggiori: foto bianche, quasi bianche, nere, grigie, sature.
 */
class GalleryChromeCloudTest {

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

  private val white = Color(0xFFFFFFFF)
  private val black = Color(0xFF000000)

  /** I contenuti sotto la chrome: le foto molto chiare (la richiesta della beta) prima di tutto, poi il resto dei casi peggiori. */
  private val ranges: List<ContentRange> = listOf(
    listOf(white),
    listOf(Color(0xFFF7F7F2)),
    listOf(Color(0xFFFFF4D6), Color(0xFFEFF6FF)),
    listOf(Color(0xFFDDDDDD), white),
    listOf(black),
    listOf(Color(0xFF777777)),
    listOf(white, black),
    listOf(Color(0xFFFF0000), Color(0xFFFFE600), Color(0xFF00E6FF)),
    listOf(Color(0xFF0000FF), Color(0xFF333333)),
  ).map { ContentRange(it) }

  private fun stylesFor(scheme: ColorScheme): List<Pair<ContentRange, GalleryChromeStyle>> {
    val palette = GalleryChromeStyles.adaptivePaletteFor(scheme.scrim)
    return ranges.map { range ->
      range to GalleryChromeStyles.adaptiveFor(GalleryLegibility.adapt(range, palette), scheme)
    }
  }

  @Test
  fun lAnelloDelSegnoELInchiostroEReggeSopraLaPellicola() {
    for (case in cases) {
      for ((range, style) in stylesFor(case.scheme)) {
        val marks = GalleryCloudMarks.of(style, case.scheme)
        assertThat(marks.ring).isEqualTo(style.ink)
        val worst = GalleryLegibility.worstContrast(marks.ring, style.tint.overlay, range)
        assertWithMessage("${case.name}, ${range.samples}: anello del segno ($worst)").that(worst).isAtLeast(GalleryLegibility.TextContrast)
      }
    }
  }

  @Test
  fun ilPuntoEsclamativoSiLeggeSulSegnoEIlTonoSiRiconosce() {
    for (case in cases) {
      for ((range, style) in stylesFor(case.scheme)) {
        val marks = GalleryCloudMarks.of(style, case.scheme)
        val alarm = GalleryLegibility.contrast(marks.onAlarm, marks.alarm)
        val attention = GalleryLegibility.contrast(marks.onAttention, marks.attention)
        assertWithMessage("${case.name}, ${range.samples}: ! sull'errore ($alarm)").that(alarm).isAtLeast(GalleryLegibility.TextContrast)
        assertWithMessage("${case.name}, ${range.samples}: ! sull'attenzione ($attention)").that(attention).isAtLeast(GalleryLegibility.TextContrast)
        // Il tono resta il colore dello schema: errore e attenzione non si confondono fra loro ne' con l'anello.
        assertThat(marks.alarm).isEqualTo(case.scheme.error)
        assertThat(marks.attention).isEqualTo(case.scheme.tertiary)
        assertThat(marks.alarm).isNotEqualTo(marks.attention)
        assertThat(marks.alarm).isNotEqualTo(marks.ring)
      }
    }
  }

  /** B7: le scritte della chrome (data, contatori, titoli in selezione) e la nuvola reggono 4,5:1 sul contenuto che hanno sotto. */
  @Test
  fun lInchiostroDellaChromeReggeSulleFotoChiare() {
    for (case in cases) {
      for ((range, style) in stylesFor(case.scheme)) {
        val worst = GalleryLegibility.worstContrast(style.ink, style.tint.overlay, range)
        assertWithMessage("${case.name}, ${range.samples}: inchiostro ($worst)").that(worst).isAtLeast(GalleryLegibility.TextContrast)
      }
    }
  }

  /** Sopra una foto molto chiara la chrome sceglie l'inchiostro scuro, con la pellicola minima: il vetro resta vetro. */
  @Test
  fun sopraUnaFotoMoltoChiaraLInchiostroEScuro() {
    for (case in cases) {
      val palette = GalleryChromeStyles.adaptivePaletteFor(case.scheme.scrim)
      val choice = GalleryLegibility.adapt(ContentRange(listOf(Color(0xFFF7F7F2))), palette)
      assertWithMessage(case.name).that(choice.darkInk).isTrue()
      assertThat(choice.film.alpha).isWithin(0.01f).of(palette.floor)
    }
  }

  @Test
  fun ogniStatoHaIlSuoGlifoEIProblemiSonoTre() {
    val glyphs = GalleryCloudState.entries.map { it.glyph() }
    // In attesa, da guardare e serve l'utente condividono la nuvola vuota: li distingue il segno del tono.
    assertThat(glyphs.toSet().size).isEqualTo(GalleryCloudState.entries.size - 2)
    assertThat(GalleryCloudState.entries.filter { it.isProblem })
      .containsExactly(GalleryCloudState.Offline, GalleryCloudState.Attention, GalleryCloudState.Alarm)
  }
}
