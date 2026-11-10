package dev.pampa.mycloudgallery.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.antigravity.fluidengine.ui.fluid.FluidDetailPlaceholder
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.feature.settings.sharing.SharingScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli screenshot delle Impostazioni a due pannelli (Fase 10, P5, D13 (4), §2.3) alle quattro larghezze del riquadro, chiaro e scuro: l'elenco (sotto 840 dp) e, da 840 dp, l'elenco con la pagina "Condivisioni"
 * nel pannello di destra (e il pannello vuoto, "Scegli una voce"). `gradlew :feature-settings:recordRoborazziDebug --tests "*SettingsPanesShots*"` scrive i PNG in `docs/screenshots/10-P5/`.
 */
abstract class SettingsPanesShots(private val width: Int) {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private val state = SettingsUiState(trust = TrustStatus.Approved(admin = true), deviceCount = 3, pendingRequests = 0, pendingSteps = emptyList())

  @Composable
  private fun Panes(preselect: SettingsPage?) {
    SettingsPanes(
      list = { twoPane, choose ->
        // Il tocco sulla riga e' provato in `SettingsPanesTest`: qui la pagina e' gia' scelta, per vederla nel pannello.
        if (preselect != null && twoPane) LaunchedEffect(Unit) { choose(preselect) }
        SettingsScreen(state = state, onBack = {}, openDiagnostics = {}, resumeStep = {}, links = SettingsLinks(openSharing = { choose(SettingsPage.SHARING) }))
      },
      detail = { shown, close ->
        if (shown == null) {
          FluidDetailPlaceholder(
            title = stringResource(R.string.settings_panes_empty_title),
            message = stringResource(R.string.settings_panes_empty_message),
            icon = Icons.Rounded.Settings,
          )
        } else {
          SharingScreen(removeLocation = false, onBack = close, onToggle = {})
        }
      },
    )
  }

  private fun shoot(name: String, dark: Boolean, content: @Composable () -> Unit) {
    rule.setContent { MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) { content() } }
    rule.waitForIdle()
    rule.onRoot().captureRoboImage("../docs/screenshots/10-P5/$name-${width}dp-${if (dark) "scuro" else "chiaro"}.png")
  }

  @Test fun impostazioniChiaro() = shoot("impostazioni", dark = false) { Panes(null) }

  @Test fun impostazioniScuro() = shoot("impostazioni", dark = true) { Panes(null) }

  // Con una pagina scelta nel pannello di destra: solo da 840 dp (sotto, l'elenco e' lo stesso di sopra).
  @Test fun paginaChiaro() {
    if (width >= 840) shoot("impostazioni-pagina", dark = false) { Panes(SettingsPage.SHARING) }
  }

  @Test fun paginaScuro() {
    if (width >= 840) shoot("impostazioni-pagina", dark = true) { Panes(SettingsPage.SHARING) }
  }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w360dp-h780dp-xhdpi")
class SettingsPanesShots360 : SettingsPanesShots(360)

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w600dp-h900dp-xhdpi")
class SettingsPanesShots600 : SettingsPanesShots(600)

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w840dp-h900dp-xhdpi")
class SettingsPanesShots840 : SettingsPanesShots(840)

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w1280dp-h800dp-land-xhdpi")
class SettingsPanesShots1280 : SettingsPanesShots(1280)
