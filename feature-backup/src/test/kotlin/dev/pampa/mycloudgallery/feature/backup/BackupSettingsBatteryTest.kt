package dev.pampa.mycloudgallery.feature.backup

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlin.math.abs
import dev.pampa.mycloudgallery.core.backup.contract.BackupSources
import dev.pampa.mycloudgallery.core.designsystem.design.BatteryGuardValue
import dev.pampa.mycloudgallery.core.designsystem.design.BatterySaveResult
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

  private fun ui(battery: BatteryGuardValue?, loading: Boolean = false, failed: Boolean = false) = SettingsUi(
    folders = FoldersState.Ready(BackupSamples.phoneFolders),
    chosen = BackupSources.DEFAULT,
    deviceFolder = null,
    protectNewPhotos = true,
    rulesAvailable = true,
    editable = true,
    battery = battery,
    batteryLoading = loading,
    batterySaveResult = if (failed) BatterySaveResult(id = 1, level = 60, ok = false) else null,
  )

  @Test
  fun bs5_conIlValoreNonAncoraLetto_ilControlloESceletro_nonUnVentiPerCentoFinto() {
    show { BackupSettingsScreen(state = ui(null, loading = true), actions = SettingsActions(), onBack = {}) }

    composeRule.onNodeWithText("E con il cavo?", useUnmergedTree = true).assertExists()
    composeRule.onNodeWithText("20%", useUnmergedTree = true).assertDoesNotExist()
    composeRule.onNodeWithText("Ferma l’analisi", useUnmergedTree = true).assertDoesNotExist()
  }

  @Test
  fun bs5_unaSceltaNonSalvata_lAvvisoEQuelloDelleAltreImpostazioni() {
    show { BackupSettingsScreen(state = ui(BatteryGuardValue(20, true, true), failed = true), actions = SettingsActions(), onBack = {}) }

    composeRule.onNodeWithText("Non sono riuscito a salvare la scelta", substring = true, useUnmergedTree = true).assertExists()
  }

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
    composeRule.onNodeWithText("Ferma l’analisi", useUnmergedTree = true).assertExists()
    // La figura della batteria non e' piu' fra i segni «Il backup parte con»: resta solo il titolo della sezione «Batteria» (una volta), e senza il backup solo in carica nessun segno «In carica».
    composeRule.onAllNodesWithText("Batteria", useUnmergedTree = true).assertCountEquals(1)
    composeRule.onNodeWithText("In carica", useUnmergedTree = true).assertDoesNotExist()
  }

  @Test
  fun bu3_conIlBackupNormaleSoloInCarica_fraIlBackupPartConCeIlSegnoInCarica() {
    show { BackupSettingsScreen(state = ui(BatteryGuardValue(20, true, true, backupOnBattery = true, analysisOnBattery = false, backupNeedsPower = true)), actions = SettingsActions(), onBack = {}) }

    composeRule.onNodeWithText("In carica", useUnmergedTree = true).assertExists()
    // E l'analisi che lavora solo in carica dice «Solo in carica» al posto dell'interruttore.
    composeRule.onNodeWithText("Solo in carica", useUnmergedTree = true).assertExists()
  }

  /** BU-11: nella pagina lo scheletro occupa quanto il controllo vero (dal titolo «Batteria» alla nota «E con il cavo?»): la scheda non salta quando arriva il valore. */
  @Test
  fun bu11_loSchelettoEAltoComeIlControllo_nellaPagina() {
    val loaded = mutableStateOf(false)
    show {
      val state = if (loaded.value) ui(BatteryGuardValue(20, true, true)) else ui(null, loading = true)
      BackupSettingsScreen(state = state, actions = SettingsActions(), onBack = {})
    }
    fun span(): Float {
      val title = composeRule.onAllNodesWithText("Batteria", useUnmergedTree = true).onFirst().fetchSemanticsNode().boundsInRoot.top
      val note = composeRule.onNodeWithText("E con il cavo?", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
      return (note - title) / composeRule.density.density
    }

    val skeleton = span()
    loaded.value = true
    composeRule.waitForIdle()
    val real = span()

    assertWithMessage("scheletro $skeleton dp, controllo $real dp").that(abs(skeleton - real) < 8f).isTrue()
  }

  /** BU2-3: con tre segni e il testo molto grande nessun nome si taglia (due righe ammesse). */
  @Test
  fun bu23_conTreSegniETestoMoltoGrande_nessunNomeSiTaglia() {
    show(fontScale = 2f) {
      BackupSettingsScreen(
        state = ui(BatteryGuardValue(20, true, true, backupOnBattery = true, analysisOnBattery = false, backupNeedsPower = true)),
        actions = SettingsActions(),
        onBack = {},
      )
    }

    val cut = mutableListOf<String>()
    for (name in listOf("Notte", "Wi-Fi di casa", "In carica")) {
      val results = mutableListOf<TextLayoutResult>()
      val node = composeRule.onNodeWithText(name, useUnmergedTree = true).fetchSemanticsNode()
      node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
      assertThat(results).isNotEmpty()
      // Tagliato = il testo ha bisogno di piu' righe di quelle ammesse (due): `didOverflowHeight` (la larghezza del testo centrato non conta).
      val r = results.first()
      if (r.didOverflowHeight || r.lineCount > 2) cut += "$name (${r.lineCount} righe)"
    }
    assertThat(cut).isEmpty()
  }

  @Test
  fun bu6_laNotaChe_CosaProtegge_stasubitoSottoLaSuaScheda_primaDelTitoloBatteria() {
    show { BackupSettingsScreen(state = ui(BatteryGuardValue(20, true, true)), actions = SettingsActions(), onBack = {}) }

    val protects = composeRule.onNodeWithText("Che cosa protegge", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
    val title = composeRule.onAllNodesWithText("Batteria", useUnmergedTree = true).onFirst().fetchSemanticsNode().boundsInRoot.top
    val cable = composeRule.onNodeWithText("E con il cavo?", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
    assertThat(protects).isLessThan(title)
    assertThat(title).isLessThan(cable)
  }

  @Test
  fun iDueInterruttori_portanoLaSceltaFuori() {
    val seen = mutableListOf<String>()
    val actions = SettingsActions(
      setBatteryForBackup = { seen += "backup=$it" },
      setBatteryForAnalysis = { seen += "analisi=$it" },
    )
    show { BackupSettingsScreen(state = ui(BatteryGuardValue(20, forBackup = true, forAnalysis = false)), actions = actions, onBack = {}) }

    composeRule.onNodeWithText("Ferma l’analisi", useUnmergedTree = true).performClick()
    composeRule.onNodeWithText("Ferma il backup", useUnmergedTree = true).performClick()

    assertThat(seen).containsExactly("analisi=true", "backup=false").inOrder()
  }
}
