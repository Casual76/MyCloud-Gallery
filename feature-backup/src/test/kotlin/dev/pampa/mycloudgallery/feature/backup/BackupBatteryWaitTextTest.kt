package dev.pampa.mycloudgallery.feature.backup

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import android.app.Application
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.pampa.mycloudgallery.core.backup.contract.WaitReason
import dev.pampa.mycloudgallery.core.designsystem.state.LocalBatteryThreshold
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.feature.backup.first.waitTitleText
import dev.pampa.mycloudgallery.feature.backup.page.waitText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pacchetto B (seconda beta): la pagina Backup dice il numero vero quando il backup e' fermo per la batteria («Batteria sotto il 60%»), e senza il livello la frase
 * che e' vera a ogni livello («Batteria sotto la soglia»). Le altre attese non cambiano.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w412dp-h900dp-xhdpi")
class BackupBatteryWaitTextTest {

  @get:Rule
  val composeRule = createComposeRule()

  private fun show(threshold: Int?, reason: WaitReason, title: Boolean = false) {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings()) {
        CompositionLocalProvider(LocalBatteryThreshold provides threshold) { Text(if (title) waitTitleText(reason) else waitText(reason)) }
      }
    }
  }

  @Test
  fun laTestata_colLivelloScelto_dicePropriolIlNumero() {
    show(threshold = 60, reason = WaitReason.BATTERY)
    composeRule.onNodeWithText("Batteria sotto il 60%").assertExists()
  }

  @Test
  fun ilFoglioDelPrimoBackup_colLivelloScelto_dicePropriolIlNumero() {
    show(threshold = 35, reason = WaitReason.BATTERY, title = true)
    composeRule.onNodeWithText("Batteria sotto il 35%").assertExists()
  }

  @Test
  fun senzaIlLivello_siDiceLaFraseVeraAQualunqueLivello() {
    show(threshold = null, reason = WaitReason.BATTERY)
    composeRule.onNodeWithText("Batteria sotto la soglia").assertExists()
  }

  @Test
  fun leAltreAttese_nonCambiano_ancheConIlLivello() {
    show(threshold = 60, reason = WaitReason.THERMAL)
    composeRule.onNodeWithText("Il telefono è troppo caldo").assertExists()
  }
}
