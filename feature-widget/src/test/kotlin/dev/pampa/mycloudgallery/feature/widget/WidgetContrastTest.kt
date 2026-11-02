package dev.pampa.mycloudgallery.feature.widget

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.glance.unit.ColorProvider
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertWithMessage
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.antigravity.fluidengine.widget.EngineWidgetPalette
import dev.antigravity.fluidengine.widget.engineWidgetPalette
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryAccent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Il contrasto di ogni coppia di colori che il widget disegna (Fase 10, P3, D26 "anche il widget segue", "Fatto quando": contrasto alto): **testo >= 4,5:1, glifi >= 3:1**, nel chiaro,
 * nello scuro e nello schema piu' contrastato del kit (AMOLED), con la palette vera di `engineWidgetPalette` e il marchio dell'app. Le foto no (sono foto): i testi del widget stanno
 * sempre su un fondo della palette, mai sopra una foto.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetContrastTest {

  private val context: Application get() = ApplicationProvider.getApplicationContext()

  private fun ColorProvider.color(): Color = getColor(context)

  private fun ratio(a: Color, b: Color): Double {
    val l1 = a.luminance().toDouble()
    val l2 = b.luminance().toDouble()
    return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
  }

  private fun check(label: String, foreground: ColorProvider, background: ColorProvider, minimum: Double) {
    val value = ratio(foreground.color(), background.color())
    assertWithMessage("$label: contrasto ${"%.2f".format(value)}:1, serve almeno $minimum:1").that(value).isAtLeast(minimum)
  }

  private fun palette(mode: ThemeMode): EngineWidgetPalette = engineWidgetPalette(context, EngineSettings(themeMode = mode), GalleryAccent.Amethyst)

  @Test
  fun iTestiELeIconeHannoIlContrastoGiustoInOgniSchema() {
    for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.AMOLED)) {
      val p = palette(mode)
      val name = mode.name
      // Il titolo del ricordo sul fondo del widget; il messaggio "Apri MyCloud Gallery" sulla scheda.
      check("$name titolo su fondo", p.onSurface, p.background, 4.5)
      check("$name messaggio su scheda", p.onSurface, p.card, 4.5)
      check("$name testo secondario su fondo", p.onSurfaceVariant, p.background, 4.5)
      check("$name testo secondario su scheda", p.onSurfaceVariant, p.card, 4.5)
      // Il glifo del messaggio e il segno del video: a tratto, sul tono primario del kit.
      check("$name glifo su piastrella", p.primaryTone.content, p.primaryTone.container, 3.0)
      check("$name segno video su piastrella", p.primaryTone.content, p.primaryTone.container, 3.0)
      check("$name azione su contenitore", p.onAccentContainer, p.accentContainer, 4.5)
    }
  }

  /** Il contrasto alto e' piu' alto di quello normale: l'AMOLED del kit non peggiora nessuna coppia di testo del chiaro e dello scuro. */
  @Test
  fun loSchemaPiuContrastatoNonPeggioraIlTesto() {
    val dark = palette(ThemeMode.DARK)
    val amoled = palette(ThemeMode.AMOLED)
    val darkRatio = ratio(dark.onSurface.color(), dark.background.color())
    val amoledRatio = ratio(amoled.onSurface.color(), amoled.background.color())
    assertWithMessage("AMOLED ${"%.2f".format(amoledRatio)} contro scuro ${"%.2f".format(darkRatio)}").that(amoledRatio).isAtLeast(darkRatio)
    assertWithMessage("il titolo a contrasto alto arriva a 7:1 (AAA)").that(amoledRatio).isAtLeast(7.0)
  }
}
