package dev.pampa.mycloudgallery.feature.settings.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.feature.settings.devices.ApprovalActions
import dev.pampa.mycloudgallery.feature.settings.devices.ApprovalScreen
import dev.pampa.mycloudgallery.feature.settings.devices.ApprovalUiState
import dev.pampa.mycloudgallery.feature.settings.devices.DeviceMapper
import dev.pampa.mycloudgallery.feature.settings.devices.ProofMode
import dev.pampa.mycloudgallery.feature.settings.support.MainDispatcherRule
import dev.pampa.mycloudgallery.feature.settings.support.Samples
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli screenshot della pagina "Nuovo dispositivo" con il tasto fermo in fondo (G3, B6): chiaro e scuro, con la tastiera aperta e senza, su un telefono basso (360 x 640 dp) e su uno normale
 * (411 x 891 dp). La tastiera non si disegna in Robolectric: qui e' un riquadro grigio alto quanto la tastiera, e il sistema le dice che l'IME occupa quello spazio, cosi' la pagina si
 * alza davvero con `imePadding`. `gradlew :feature-settings:recordRoborazziDebug --tests "*ApprovalG3Shots*"` scrive i PNG in `docs/screenshots/beta-grafica/g3-viewer/`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class ApprovalG3Shots(private val device: String, private val keyboardDp: Int) {

  @get:Rule
  val main = MainDispatcherRule()

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private val request = DeviceMapper.requests(listOf(Samples.request), Samples.familyAsAdmin(), Samples.NOW).single()

  private fun shot(dark: Boolean, keyboard: Boolean) {
    rule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
        Box(Modifier.fillMaxSize()) {
          SettingsHost {
            ApprovalScreen(
              ApprovalUiState(request, isAdmin = true, mode = ProofMode.CODE, code = "K7QD-2M9X", accountConfirmed = true),
              ApprovalActions.None,
            )
          }
          if (keyboard) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(keyboardDp.dp).background(if (dark) Color(0xFF2B2B30) else Color(0xFFD4D6DC))) {
              Text("Tastiera", modifier = Modifier.align(Alignment.Center), color = if (dark) Color.White else Color.Black)
            }
          }
        }
      }
    }
    rule.waitForIdle()
    if (keyboard) {
      val px = (keyboardDp * rule.density.density).toInt()
      rule.runOnUiThread { rule.activity.openKeyboard(px) }
      rule.waitForIdle()
      // Chi scrive il codice ha il campo in vista: il campo preso dal fuoco si porta sopra la tastiera (qui lo si fa a mano, senza una tastiera vera).
      rule.onNode(hasSetTextAction()).performScrollTo()
      rule.waitForIdle()
    }
    val state = if (keyboard) "con-tastiera" else "senza-tastiera"
    rule.onRoot().captureRoboImage("../docs/screenshots/beta-grafica/g3-viewer/nuovo-dispositivo-$state-$device-${if (dark) "scuro" else "chiaro"}.png")
  }

  @Test fun senzaTastiera_chiaro() = shot(dark = false, keyboard = false)

  @Test fun senzaTastiera_scuro() = shot(dark = true, keyboard = false)

  @Test fun conTastiera_chiaro() = shot(dark = false, keyboard = true)

  @Test fun conTastiera_scuro() = shot(dark = true, keyboard = true)
}

@Config(qualifiers = "w360dp-h640dp-xhdpi")
class ApprovalG3ShotsLow : ApprovalG3Shots("telefono-basso", keyboardDp = 270)

@Config(qualifiers = "w411dp-h891dp-xhdpi")
class ApprovalG3ShotsPhone : ApprovalG3Shots("telefono", keyboardDp = 300)
