package dev.pampa.mycloudgallery.feature.settings.rules

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.rules.Preset
import dev.pampa.mycloudgallery.core.rules.PresetCatalog
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.sharing.SharingComposeBase
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private fun uiState(pauseOnPowerSaver: Boolean) = RulesUiState(
  preset = Preset.NIGHT,
  cap = CapChoice.FromPreset,
  presetCapBytes = PresetCatalog.DEFAULT_MOBILE_CAP_BYTES,
  allowRoaming = false,
  pauseOnPowerSaver = pauseOnPowerSaver,
)

/**
 * FAB-4 della review generale (pacchetto RG-D): l'interruttore «Metti in pausa con il risparmio energetico» in "Quando lavora l'app", legato a
 * `pauseOnPowerSaver` (`docs/regole-attivita.md` §6, punto 7). Prima l'impostazione esisteva (`RuleSettings`) e il motore la leggeva, ma nessuna
 * schermata la mostrava: con il risparmio Samsung sempre acceso il backup non partiva mai e l'utente non aveva dove cambiarlo.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w412dp-h2400dp-xhdpi")
class RulesScreenTest : SharingComposeBase() {

  private fun page(pause: Boolean, onPause: (Boolean) -> Unit = {}) {
    show { RulesScreen(state = uiState(pause), actions = RulesActions({}, {}, {}, {}, onPause)) }
    // La pagina e' lunga: la riga nuova e' in fondo, la si porta in vista come farebbe l'utente scorrendo.
    rule.onNode(hasScrollAction()).performScrollToNode(hasText(string(R.string.rules_power_saver)))
    rule.waitForIdle()
  }

  @Test
  fun lInterruttoreDelRisparmioEnergeticoCeEDiceCosaFa() {
    page(pause = true)

    // Il titolo di sezione e' in maiuscolo (FluidSectionHeader): si cerca senza badare alle maiuscole.
    rule.onNodeWithText(string(R.string.rules_section_power_saver), ignoreCase = true).assertExists()
    rule.onNodeWithText(string(R.string.rules_power_saver)).assertIsDisplayed()
    assertThat(has(string(R.string.rules_power_saver_detail))).isTrue()
  }

  @Test
  fun accesoSiSpegneConUnTocco_spentoSiAccende() {
    val seen = mutableListOf<Boolean>()
    page(pause = true, onPause = { seen += it })
    rule.onNodeWithText(string(R.string.rules_power_saver)).performClick()
    assertThat(seen).containsExactly(false)
  }

  @Test
  fun spentoSiAccendeConUnTocco() {
    val seen = mutableListOf<Boolean>()
    page(pause = false, onPause = { seen += it })
    rule.onNodeWithText(string(R.string.rules_power_saver)).performClick()
    assertThat(seen).containsExactly(true)
  }
}

/**
 * Gli screenshot della pagina "Quando lavora l'app" con l'interruttore nuovo, acceso e spento: chiaro e scuro, telefono e tablet. La pagina e' lunga:
 * si porta in vista la riga nuova prima dello scatto. `gradlew :feature-settings:recordRoborazziDebug --tests "*RulesScreenShots*"` scrive i PNG in
 * `docs/screenshots/beta-grafica/RG-D/`.
 */
abstract class RulesScreenShots(private val device: String) {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private fun shoot(dark: Boolean, pause: Boolean) {
    rule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
        RulesScreen(state = uiState(pause), actions = RulesActions.None)
      }
    }
    rule.waitForIdle()
    // Si porta in vista la sezione (titolo e riga), non solo la riga.
    val section = rule.activity.getString(R.string.rules_section_power_saver)
    rule.onNode(hasScrollAction()).performScrollToNode(hasText(section, ignoreCase = true))
    rule.waitForIdle()
    val look = if (dark) "scuro" else "chiaro"
    val state = if (pause) "acceso" else "spento"
    rule.onRoot().captureRoboImage("../docs/screenshots/beta-grafica/RG-D/quando-lavora-l-app-risparmio-energetico-$state-$look-$device.png")
  }

  @Test
  fun accesoChiaro() = shoot(dark = false, pause = true)

  @Test
  fun accesoScuro() = shoot(dark = true, pause = true)

  @Test
  fun spentoChiaro() = shoot(dark = false, pause = false)

  @Test
  fun spentoScuro() = shoot(dark = true, pause = false)
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w360dp-h780dp-xhdpi")
class RulesScreenPhoneShots : RulesScreenShots("telefono")

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w1280dp-h800dp-xhdpi")
class RulesScreenTabletShots : RulesScreenShots("tablet")
