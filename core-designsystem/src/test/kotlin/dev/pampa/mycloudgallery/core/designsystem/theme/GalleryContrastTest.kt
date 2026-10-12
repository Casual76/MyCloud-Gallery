package dev.pampa.mycloudgallery.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertWithMessage
import dev.antigravity.fluidengine.foundation.AccentMode
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.ui.theme.fluidColorScheme
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryStatus
import dev.pampa.mycloudgallery.core.designsystem.state.galleryStatusStyle
import org.junit.Test

/**
 * Il contrasto di cio' che la galleria disegna, verificato con la formula WCAG su **ogni** accento
 * (l'Ametista e tutti i preset Fluid) in chiaro e scuro: non basta che funzioni con il marchio.
 *
 * Soglie: 4,5:1 per il testo e per i glifi che portano da soli il significato; 3:1 per le icone
 * che accompagnano un'etichetta.
 */
class GalleryContrastTest {

  private data class Case(val name: String, val scheme: ColorScheme)

  private val cases: List<Case> = GalleryAccent.presets.flatMap { preset ->
    listOf(false, true).map { dark ->
      val settings = EngineSettings(accentMode = AccentMode.CUSTOM_PRESET, customAccentName = preset.name)
      Case(
        name = "${preset.name} ${if (dark) "scuro" else "chiaro"}",
        scheme = fluidColorScheme(
          settings = settings,
          isDark = dark,
          brand = GalleryAccent.Amethyst,
          presets = GalleryAccent.presets,
        ),
      )
    }
  }

  private fun ratio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
  }

  private fun assertAtLeast(minimum: Float, what: String, foreground: Color, background: Color) {
    val value = ratio(foreground, background)
    assertWithMessage("$what: $value").that(value).isAtLeast(minimum)
  }

  @Test
  fun ilTestoBiancoSulloScrimRegge4e5SopraQualunqueFoto() {
    val photos = listOf(
      "bianca" to Color.White,
      "nera" to Color.Black,
      "grigio medio" to Color(0.5f, 0.5f, 0.5f),
      "giallo acceso" to Color(1f, 0.95f, 0.1f),
      "ciano chiaro" to Color(0.6f, 1f, 1f),
    )
    cases.forEach { case ->
      val scrim = GalleryOnPhoto.scrim(case.scheme)
      photos.forEach { (photoName, photo) ->
        assertAtLeast(
          4.5f,
          "${case.name}: scrim sopra foto $photoName",
          GalleryOnPhoto.Content,
          scrim.compositeOver(photo),
        )
      }
    }
  }

  @Test
  fun ilBadgeDiErroreEPienoEInLeggibile() {
    cases.forEach { case ->
      assertAtLeast(4.5f, "${case.name}: onError su error", case.scheme.onError, case.scheme.error)
    }
  }

  @Test
  fun laSpuntaDellaSelezioneRegge3e1() {
    cases.forEach { case ->
      assertAtLeast(3f, "${case.name}: onPrimary su primary", case.scheme.onPrimary, case.scheme.primary)
    }
  }

  @Test
  fun ogniStatoGlobaleHaTestoEGlifoLeggibili() {
    val statuses = listOf(
      GalleryStatus.AllSafe,
      GalleryStatus.BackingUp(32, 120),
      GalleryStatus.WaitingForCharge,
      GalleryStatus.NasUnreachable,
      GalleryStatus.WakingNas,
      GalleryStatus.Error("quota esaurita"),
    )
    cases.forEach { case ->
      statuses.forEach { status ->
        val style = galleryStatusStyle(status, case.scheme)
        assertAtLeast(4.5f, "${case.name}: testo di $status", style.text, style.container)
        assertAtLeast(3f, "${case.name}: glifo di $status", style.glyph, style.container)
      }
    }
  }

  @Test
  fun lePiastrelleDeiFogliHannoIlGlifoLeggibile() {
    cases.forEach { case ->
      val s = case.scheme
      assertAtLeast(4.5f, "${case.name}: primario", s.onPrimaryContainer, s.primaryContainer)
      assertAtLeast(3f, "${case.name}: errore", s.error, s.errorContainer)
      assertAtLeast(4.5f, "${case.name}: terziario", s.onTertiaryContainer, s.tertiaryContainer)
    }
  }

  @Test
  fun laVoceCheCancellaSiLeggeSullaSuperficie() {
    cases.forEach { case ->
      assertAtLeast(4.5f, "${case.name}: errore su superficie", case.scheme.error, case.scheme.surface)
    }
  }
}
