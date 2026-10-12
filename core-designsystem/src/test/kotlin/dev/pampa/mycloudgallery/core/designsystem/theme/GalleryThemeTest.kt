package dev.pampa.mycloudgallery.core.designsystem.theme

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.foundation.AccentMode
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.ui.theme.fluidColorScheme
import kotlin.math.abs
import org.junit.Test

/**
 * Il tema Ametista risolto fuori dalla composizione (`fluidColorScheme` e' la stessa funzione che
 * usa `FluidTheme`): i colori del marchio, Material You e i preset Fluid arrivano tutti dal solo
 * parametro `EngineSettings`.
 */
class GalleryThemeTest {

  private fun resolve(
    settings: EngineSettings,
    isDark: Boolean,
    dynamicScheme: androidx.compose.material3.ColorScheme? = null,
  ) = fluidColorScheme(
    settings = settings,
    isDark = isDark,
    brand = GalleryAccent.Amethyst,
    presets = GalleryAccent.presets,
    dynamicScheme = dynamicScheme,
  )

  @Test
  fun ilDefaultEAmetista_inChiaroEInScuro() {
    assertThat(resolve(EngineSettings(), isDark = false).primary).isEqualTo(Color(0xFF9966CC))
    assertThat(resolve(EngineSettings(), isDark = true).primary).isEqualTo(Color(0xFFB88CE8))
  }

  @Test
  fun ilMarchioHaDefinitoIlSuoNomeELaSuaEtichetta() {
    assertThat(GalleryAccent.Amethyst.name).isEqualTo(GalleryAccent.AMETHYST_NAME)
    assertThat(GalleryAccent.Amethyst.label).isEqualTo("Ametista")
    assertThat(GalleryAccent.presets.first()).isEqualTo(GalleryAccent.Amethyst)
  }

  @Test
  fun leTreFamiglieDiColoreRestanoDistinte() {
    listOf(false, true).forEach { isDark ->
      val scheme = resolve(EngineSettings(), isDark)
      listOf(
        Triple("primario/secondario", scheme.primary, scheme.secondary),
        Triple("primario/terziario", scheme.primary, scheme.tertiary),
        Triple("secondario/terziario", scheme.secondary, scheme.tertiary),
      ).forEach { (coppia, a, b) ->
        val distanza = abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue)
        assertThat(distanza).isAtLeast(0.20f)
      }
    }
  }

  @Test
  fun unPresetFluidScelto_sostituisceLAmetista() {
    val settings = EngineSettings(accentMode = AccentMode.CUSTOM_PRESET, customAccentName = "ocean")

    assertThat(resolve(settings, isDark = false).primary).isEqualTo(Color(0xFF5856D6))
  }

  @Test
  fun unNomeDiPresetSconosciuto_ripiegaSullAmetista() {
    val settings = EngineSettings(accentMode = AccentMode.CUSTOM_PRESET, customAccentName = "non-esiste")

    assertThat(resolve(settings, isDark = false).primary).isEqualTo(Color(0xFF9966CC))
  }

  @Test
  fun materialYou_usaLaPalettaDiSistemaQuandoCe() {
    val sistema = lightColorScheme(primary = Color(0xFF102030))
    val settings = EngineSettings(accentMode = AccentMode.DYNAMIC, dynamicColorEnabled = true)

    assertThat(resolve(settings, isDark = false, dynamicScheme = sistema).primary)
      .isEqualTo(Color(0xFF102030))
  }

  @Test
  fun materialYouSpento_ripiegaSullAmetistaSenzaUsareLaPalettaDiSistema() {
    val sistema = lightColorScheme(primary = Color(0xFF102030))
    val settings = EngineSettings(accentMode = AccentMode.DYNAMIC, dynamicColorEnabled = false)

    assertThat(resolve(settings, isDark = false, dynamicScheme = sistema).primary)
      .isEqualTo(Color(0xFF9966CC))
  }
}
