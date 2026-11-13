package dev.pampa.mycloudgallery.feature.backup

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.onRoot
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.BackupSetupStep
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.BackupSources
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.BackupSummary
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.StopBy
import dev.pampa.mycloudgallery.core.backup.contract.WaitReason
import dev.pampa.mycloudgallery.core.designsystem.design.BatteryGuardValue
import dev.pampa.mycloudgallery.core.designsystem.design.BatterySaveResult
import dev.pampa.mycloudgallery.core.designsystem.testing.A11yAssertions
import dev.pampa.mycloudgallery.core.designsystem.testing.A11yConfig
import dev.pampa.mycloudgallery.feature.backup.first.FirstBackupSheet
import dev.pampa.mycloudgallery.feature.backup.folder.DeviceFolderScreen
import dev.pampa.mycloudgallery.feature.backup.folder.FolderActions
import dev.pampa.mycloudgallery.feature.backup.folder.FolderUi
import dev.pampa.mycloudgallery.feature.backup.page.BackupPageActions
import dev.pampa.mycloudgallery.feature.backup.page.BackupPageScreen
import dev.pampa.mycloudgallery.feature.backup.page.BackupPageUi
import dev.pampa.mycloudgallery.feature.backup.settings.BackupSettingsScreen
import dev.pampa.mycloudgallery.feature.backup.settings.FoldersState
import dev.pampa.mycloudgallery.feature.backup.settings.SettingsActions
import dev.pampa.mycloudgallery.feature.backup.settings.SettingsUi
import dev.pampa.mycloudgallery.feature.backup.testing.BackupSamples
import com.github.takahirom.roborazzi.checkRoboAccessibility
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fase 10, P8 (D23, D25-D27, §2.7 "Backup, NAS, Diagnostica", D28): il backup letto come lo legge TalkBack. Gli stati del catalogo (pagina,
 * foglio del primo backup, impostazioni, cartella del dispositivo), a testo 1,0 e 2,0: nome e ruolo per ogni elemento attivo, bersagli da 48 dp
 * misurati, nessun id o nome di file, testo non tagliato; lo stato che cambia da solo e' una regione viva educata.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = PHONE_QUALIFIERS)
class BackupAccessibilityTest : BackupComposeBase() {

  private fun root(): SemanticsNode = composeRule.onRoot().fetchSemanticsNode()

  private fun config() = A11yConfig(density = composeRule.density)

  private fun page(status: BackupStatus, folder: Boolean = true, sources: List<dev.pampa.mycloudgallery.core.backup.contract.SourceStatus> = BackupSamples.sources) =
    BackupPageUi(status, sources, if (folder) BackupSamples.deviceFolder else null, false)

  @Composable
  private fun Page(state: BackupPageUi) = BackupPageScreen(state = state, actions = BackupPageActions(), onBack = {})

  @Composable
  private fun SheetOverPage(summary: BackupSummary) {
    Page(page(BackupStatus.FirstBackupReady(summary)))
    FirstBackupSheet(visible = true, summary = summary, onTonight = {}, onNow = {}, onDismissRequest = {})
  }

  private fun settings(
    folders: FoldersState = FoldersState.Ready(BackupSamples.phoneFolders),
    battery: BatteryGuardValue? = null,
    batteryLoading: Boolean = false,
    saveFailed: Boolean = false,
  ) = SettingsUi(
    folders = folders,
    chosen = BackupSources(setOf(BackupSource.Camera, BackupSource.Screenshots, BackupSamples.phoneFolders.last().source)),
    deviceFolder = BackupSamples.deviceFolder,
    protectNewPhotos = true,
    rulesAvailable = true,
    editable = true,
    battery = battery,
    batteryLoading = batteryLoading,
    batterySaveResult = if (saveFailed) BatterySaveResult(id = 1, level = 60, ok = false) else null,
  )

  /** Mostra [content] e controlla le regole a testo 1,0 e poi a 2,0 sulla stessa scena (la base ha la scala come stato). */
  private fun clean(name: String, content: @Composable () -> Unit) {
    show(content = content)
    for (value in listOf(1f, 2f)) {
      fontScale.value = value
      composeRule.waitForIdle()
      val found = A11yAssertions.check(root(), config())
      assertWithMessage("$name a testo $value: " + found.joinToString(" | ")).that(found).isEmpty()
    }
  }

  private val running = BackupStatus.Running(BackupKind.PHOTOS, 1_240, 6_658, 2_100_000_000, 48_000_000_000, 4_800_000, BackupTrigger.FIRST_BACKUP)

  // --- La pagina ----------------------------------------------------------------------------------------------------------------

  @Test fun paginaPrimoBackupInCorso() = clean("primo backup in corso") { Page(page(running)) }

  @Test
  fun paginaTuttoAlSicuro() = clean("tutto al sicuro") { Page(page(BackupStatus.UpToDate(safeCount = 6_658, atNasMillis = 1_792_119_120_000))) }

  @Test fun paginaInCoda() = clean("in coda") { Page(page(BackupStatus.Waiting(312, 1_400_000_000, WaitReason.CHARGING))) }

  @Test fun paginaFermato() = clean("fermato") { Page(page(BackupStatus.Stopped(StopBy.USER))) }

  @Test fun paginaProblemaDiQuota() = clean("problema di quota") { Page(page(BackupStatus.Problem(BackupProblemCode.QUOTA, 0))) }

  @Test
  fun paginaNonConfigurato() = clean("non configurato") {
    Page(page(BackupStatus.NotConfigured(setOf(BackupSetupStep.MEDIA_PERMISSION, BackupSetupStep.DEVICE_FOLDER)), folder = false, sources = emptyList()))
  }

  @Test
  fun paginaProntoPerIlPrimoBackup() = clean("pronto per il primo backup") {
    Page(page(BackupStatus.FirstBackupReady(BackupSamples.firstBackupSummary), folder = false))
  }

  // --- I fogli, le impostazioni e la cartella ---------------------------------------------------------------------------------

  @Test fun foglioDelPrimoBackup() = clean("foglio del primo backup") { SheetOverPage(BackupSamples.firstBackupSummary) }

  @Test fun foglioConDueStime() = clean("foglio con due stime") { SheetOverPage(BackupSamples.twoEstimatesSummary) }

  @Test fun impostazioni() = clean("impostazioni") { BackupSettingsScreen(state = settings(), actions = SettingsActions(), onBack = {}) }

  /** Pacchetto B: il controllo del livello di batteria nelle impostazioni (batteria, cursore, due interruttori), a testo 1,0 e 2,0. */
  @Test fun impostazioniConIlLivelloDellaBatteria() =
    clean("impostazioni, livello di batteria") { BackupSettingsScreen(state = settings(battery = BatteryGuardValue(35, true, false)), actions = SettingsActions(), onBack = {}) }

  @Test
  fun impostazioniCartelleNonLeggibili() =
    clean("impostazioni, cartelle non leggibili") { BackupSettingsScreen(state = settings(FoldersState.Unavailable), actions = SettingsActions(), onBack = {}) }

  @Test
  fun cartellaDelDispositivo() = clean("cartella del dispositivo") {
    DeviceFolderScreen(state = FolderUi.Choose(BackupSamples.deviceFolder, null, null), actions = FolderActions(), onBack = {})
  }

  // --- La semantica attesa del §2.7 -------------------------------------------------------------------------------------------

  @Test
  fun lAvanzamentoEUnaRegioneVivaEducata() {
    show { Page(page(running)) }
    val live = A11yAssertions.nodes(root()).filter { A11yAssertions.liveRegion(it) != null }
    assertThat(live).isNotEmpty()
    // Numeri letti per esteso: nessuna sigla tipo "1,2k" o "6.6k" nelle frasi dello stato.
    for (node in live) assertThat(A11yAssertions.name(node)).doesNotContainMatch("""\d[kKmM]\b""")
  }

  @Test
  fun leSezioniDellePaginaSonoIntestazioni() {
    show { Page(page(running)) }
    assertThat(A11yAssertions.headingNames(root())).isNotEmpty()
  }

  // --- ATF (D28): il controllo automatico indipendente, sulle schermate principali del modulo -----------------------------------------

  @Test
  fun atfPaginaInCorso() {
    show { Page(page(running)) }
    composeRule.onRoot().checkRoboAccessibility()
  }

  @Test
  fun atfImpostazioniConIlLivelloDellaBatteria() {
    show { BackupSettingsScreen(state = settings(battery = BatteryGuardValue(35, true, false)), actions = SettingsActions(), onBack = {}) }
    composeRule.onRoot().checkRoboAccessibility()
  }

  /** Con tutti e due gli interruttori spenti la batteria e il numero sfumano: devono restare leggibili per il controllo automatico. */
  @Test
  fun atfImpostazioniConIlLivelloDellaBatteriaESpenti() {
    show { BackupSettingsScreen(state = settings(battery = BatteryGuardValue(20, false, false)), actions = SettingsActions(), onBack = {}) }
    composeRule.onRoot().checkRoboAccessibility()
  }

  @Test
  fun atfImpostazioniConIlLivelloDellaBatteriaESpentiAlBuio() {
    show(dark = true) { BackupSettingsScreen(state = settings(battery = BatteryGuardValue(20, false, false)), actions = SettingsActions(), onBack = {}) }
    composeRule.onRoot().checkRoboAccessibility()
  }

  // --- Giro 2: gli stati nuovi del controllo (righe «Solo in carica», scheletro, avviso di salvataggio) a testo 1,0 e 2,0 e per l'ATF ----------

  private val onlyCharging = BatteryGuardValue(20, true, true, backupOnBattery = false, analysisOnBattery = false, backupNeedsPower = true)
  private val nightPreset = BatteryGuardValue(20, true, true, backupOnBattery = true, analysisOnBattery = false, backupNeedsPower = true)

  @Test fun impostazioniConIlControlloSoloInCarica() =
    clean("impostazioni, nessuno lavora a batteria") { BackupSettingsScreen(state = settings(battery = onlyCharging), actions = SettingsActions(), onBack = {}) }

  @Test fun impostazioniConIlPresetNotte() =
    clean("impostazioni, preset Notte") { BackupSettingsScreen(state = settings(battery = nightPreset), actions = SettingsActions(), onBack = {}) }

  private val analysisOff = BatteryGuardValue(20, true, true, backupOnBattery = true, analysisOnBattery = false, backupNeedsPower = false, analysisEnabled = false)

  @Test fun impostazioniConUnaAttivitaSpenta() =
    clean("impostazioni, analisi spenta") { BackupSettingsScreen(state = settings(battery = analysisOff), actions = SettingsActions(), onBack = {}) }

  @Test
  fun atfImpostazioniConUnaAttivitaSpenta() {
    show { BackupSettingsScreen(state = settings(battery = analysisOff), actions = SettingsActions(), onBack = {}) }
    composeRule.onRoot().checkRoboAccessibility()
  }

  @Test fun impostazioniConIlControlloInCaricamento() =
    clean("impostazioni, livello in caricamento") { BackupSettingsScreen(state = settings(batteryLoading = true), actions = SettingsActions(), onBack = {}) }

  @Test fun impostazioniConIlSalvataggioNonRiuscito() =
    clean("impostazioni, salvataggio non riuscito") { BackupSettingsScreen(state = settings(battery = BatteryGuardValue(20, true, true), saveFailed = true), actions = SettingsActions(), onBack = {}) }

  @Test
  fun atfImpostazioniConIlControlloSoloInCarica() {
    show { BackupSettingsScreen(state = settings(battery = onlyCharging), actions = SettingsActions(), onBack = {}) }
    composeRule.onRoot().checkRoboAccessibility()
  }

  @Test
  fun atfImpostazioniConIlControlloSoloInCaricaAlBuio() {
    show(dark = true) { BackupSettingsScreen(state = settings(battery = onlyCharging), actions = SettingsActions(), onBack = {}) }
    composeRule.onRoot().checkRoboAccessibility()
  }

  @Test
  fun atfImpostazioniConIlPresetNotte() {
    show { BackupSettingsScreen(state = settings(battery = nightPreset), actions = SettingsActions(), onBack = {}) }
    composeRule.onRoot().checkRoboAccessibility()
  }

  @Test
  fun atfImpostazioniConIlControlloInCaricamento() {
    show { BackupSettingsScreen(state = settings(batteryLoading = true), actions = SettingsActions(), onBack = {}) }
    composeRule.onRoot().checkRoboAccessibility()
  }

  @Test
  fun atfImpostazioniConIlSalvataggioNonRiuscito() {
    show(dark = true) { BackupSettingsScreen(state = settings(battery = BatteryGuardValue(20, true, true), saveFailed = true), actions = SettingsActions(), onBack = {}) }
    composeRule.onRoot().checkRoboAccessibility()
  }

  @Test
  fun atfImpostazioni() {
    show { BackupSettingsScreen(state = settings(), actions = SettingsActions(), onBack = {}) }
    composeRule.onRoot().checkRoboAccessibility()
  }
}

