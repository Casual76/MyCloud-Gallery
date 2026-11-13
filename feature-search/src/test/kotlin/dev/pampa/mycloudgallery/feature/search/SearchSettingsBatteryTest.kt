package dev.pampa.mycloudgallery.feature.search

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.design.BatteryGuardValue
import dev.pampa.mycloudgallery.feature.search.models.SearchSettingsScreen
import dev.pampa.mycloudgallery.feature.search.testing.SearchSamples
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pacchetto B (seconda beta): le impostazioni dell'analisi ospitano lo stesso controllo del livello di batteria del Backup. C'e' solo con la porta legata, dice lo stato una
 * volta sola, e i due interruttori portano la scelta fuori.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, qualifiers = "it-w360dp-h2400dp-xhdpi")
class SearchSettingsBatteryTest {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private fun show(battery: BatteryGuardValue?, onBackup: (Boolean) -> Unit = {}, onAnalysis: (Boolean) -> Unit = {}, onLevel: (Int) -> Unit = {}) {
    rule.setSearchContent {
      SearchSettingsScreen(
        state = SearchSamples.settingsIndexer,
        measuring = false,
        hasIndexer = true,
        hasTextReading = true,
        hasModels = true,
        handlers = RecordingSettingsHandlers(),
        onBack = {},
        battery = battery,
        onBatteryLevel = onLevel,
        onBatteryForBackup = onBackup,
        onBatteryForAnalysis = onAnalysis,
      )
    }
  }

  @Test
  fun senzaLaPorta_laSezioneNonCe() {
    show(battery = null)

    rule.onNodeWithText("E con il cavo?", useUnmergedTree = true).assertDoesNotExist()
    rule.onNodeWithText("Analisi delle foto", useUnmergedTree = true).assertDoesNotExist()
  }

  @Test
  fun conLaPorta_ilControlloCe_conLoStatoDettoUnaVolta() {
    show(battery = BatteryGuardValue(levelPercent = 45, forBackup = false, forAnalysis = true))

    rule.onNodeWithText("45%", useUnmergedTree = true).assertExists()
    rule.onNodeWithText("L’analisi si ferma sotto il 45%", useUnmergedTree = true).assertExists()
  }

  @Test
  fun iDueInterruttori_portanoLaSceltaFuori() {
    val seen = mutableListOf<String>()
    show(
      battery = BatteryGuardValue(levelPercent = 20, forBackup = true, forAnalysis = true),
      onBackup = { seen += "backup=$it" },
      onAnalysis = { seen += "analisi=$it" },
    )

    rule.onNodeWithText("Analisi delle foto", useUnmergedTree = true).performClick()

    assertThat(seen).containsExactly("analisi=false")
  }
}
