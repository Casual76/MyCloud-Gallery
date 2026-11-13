package dev.pampa.mycloudgallery.feature.backup

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.BackupSources
import dev.pampa.mycloudgallery.core.designsystem.design.BatteryGuardValue
import dev.pampa.mycloudgallery.feature.backup.settings.BackupSettingsScreen
import dev.pampa.mycloudgallery.feature.backup.testing.BackupSamples
import dev.pampa.mycloudgallery.feature.backup.settings.FoldersState
import dev.pampa.mycloudgallery.feature.backup.settings.SettingsActions
import dev.pampa.mycloudgallery.feature.backup.settings.SettingsUi
import org.junit.Test

/**
 * Pacchetto B: il controllo del livello di batteria nelle impostazioni del Backup. C'e' solo se `:app` ha legato la porta; i due interruttori portano la scelta fuori; la
 * figura della batteria non si ripete fra i segni «Il backup parte con» quando il controllo c'e'.
 */
class BackupSettingsBatteryTest : ComposeBackupTallTest() {

  private fun ui(battery: BatteryGuardValue?) = SettingsUi(
    folders = FoldersState.Ready(BackupSamples.phoneFolders),
    chosen = BackupSources.DEFAULT,
    deviceFolder = null,
    protectNewPhotos = true,
    rulesAvailable = true,
    editable = true,
    battery = battery,
  )

  @Test
  fun senzaLaPorta_ilControlloNonCe_eIlSegnoDellaBatteriaResta() {
    show { BackupSettingsScreen(state = ui(null), actions = SettingsActions(), onBack = {}) }

    composeRule.onNodeWithText("Batteria", useUnmergedTree = true).assertExists()
    composeRule.onNodeWithText("E con il cavo?", useUnmergedTree = true).assertDoesNotExist()
  }

  @Test
  fun conLaPorta_ilControlloCeConLoStatoDettoUnaVolta() {
    show { BackupSettingsScreen(state = ui(BatteryGuardValue(60, forBackup = true, forAnalysis = false)), actions = SettingsActions(), onBack = {}) }

    composeRule.onNodeWithText("60%", useUnmergedTree = true).assertExists()
    composeRule.onNodeWithText("Sotto questo livello si ferma il backup", useUnmergedTree = true).assertExists()
    composeRule.onNodeWithText("Analisi delle foto", useUnmergedTree = true).assertExists()
    // La figura della batteria non e' piu' fra i segni «Il backup parte con»: il controllo e' la riga della batteria.
    composeRule.onNodeWithText("Batteria", useUnmergedTree = true).assertDoesNotExist()
  }

  @Test
  fun iDueInterruttori_portanoLaSceltaFuori() {
    val seen = mutableListOf<String>()
    val actions = SettingsActions(
      setBatteryForBackup = { seen += "backup=$it" },
      setBatteryForAnalysis = { seen += "analisi=$it" },
    )
    show { BackupSettingsScreen(state = ui(BatteryGuardValue(20, forBackup = true, forAnalysis = false)), actions = actions, onBack = {}) }

    composeRule.onNodeWithText("Analisi delle foto", useUnmergedTree = true).performClick()
    composeRule.onNodeWithText("Backup", useUnmergedTree = true).performClick()

    assertThat(seen).containsExactly("analisi=true", "backup=false").inOrder()
  }
}
