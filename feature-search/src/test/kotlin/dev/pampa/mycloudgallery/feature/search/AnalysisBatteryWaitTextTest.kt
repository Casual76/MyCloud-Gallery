package dev.pampa.mycloudgallery.feature.search

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.pampa.mycloudgallery.core.designsystem.state.LocalBatteryThreshold
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.library.contract.LibraryAnalysisWait
import dev.pampa.mycloudgallery.feature.search.models.analysisWaitText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pacchetto B (seconda beta): l'analisi ferma per la batteria dice il numero vero («Batteria sotto il 60%»); prima diceva «Batteria scarica», falso con la soglia al 60%
 * e il telefono al 55%. Senza il livello, la frase vera a ogni livello. Le altre attese non cambiano.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, qualifiers = "it-w360dp-h800dp-xhdpi")
class AnalysisBatteryWaitTextTest {

  @get:Rule
  val composeRule = createComposeRule()

  private fun show(threshold: Int?, reason: LibraryAnalysisWait) {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings()) {
        CompositionLocalProvider(LocalBatteryThreshold provides threshold) { Text(analysisWaitText(reason)) }
      }
    }
  }

  @Test
  fun colLivelloScelto_dicePropriolIlNumero() {
    show(threshold = 60, reason = LibraryAnalysisWait.BATTERY)
    composeRule.onNodeWithText("Batteria sotto il 60%").assertExists()
  }

  @Test
  fun senzaIlLivello_siDiceLaFraseVeraAQualunqueLivello() {
    show(threshold = null, reason = LibraryAnalysisWait.BATTERY)
    composeRule.onNodeWithText("Batteria sotto la soglia").assertExists()
  }

  @Test
  fun leAltreAttese_nonCambiano_ancheConIlLivello() {
    show(threshold = 60, reason = LibraryAnalysisWait.THERMAL)
    composeRule.onNodeWithText("Telefono caldo").assertExists()
  }
}
