package dev.pampa.mycloudgallery.feature.backup

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli screenshot di P5 per le schermate del backup (pagina Backup, foglio del primo backup, impostazioni e cartella del dispositivo):
 * chiaro e scuro, telefono dell'utente (360 dp) e tablet (1280 dp), piu' il testo grande (1,3x). Sui dati di [BackupSamples] (i numeri dell'S25).
 *
 * `gradlew :feature-backup:recordRoborazziDebug --tests "*BackupScreenshot*"` scrive i PNG in `docs/screenshots/5-P5/`. Nei normali
 * `testDebugUnitTest` si verifica solo che le schermate compongano. Il piede con la terza voce, le celle con i segni del problema e delle
 * copie, e la riga del viewer hanno i loro screenshot in `feature-library` e `feature-viewer`, nella stessa cartella.
 */
abstract class BackupScreenshotBase(private val dark: Boolean, private val device: String) : BackupComposeBase() {

  private val look: String get() = if (dark) "scuro" else "chiaro"

  private fun shoot(name: String, fontScale: Float? = null, content: @Composable () -> Unit) {
    show(dark = dark, fontScale = fontScale, content = content)
    val suffix = if (fontScale != null) "-testo-grande" else ""
    composeRule.onRoot().captureRoboImage("$DIR/$name-$look-$device$suffix.png")
  }

  private fun page(
    status: BackupStatus,
    sources: List<dev.pampa.mycloudgallery.core.backup.contract.SourceStatus> = BackupSamples.sources,
    folder: Boolean = true,
    sheetOpen: Boolean = false,
  ) = BackupPageUi(status, sources, if (folder) BackupSamples.deviceFolder else null, sheetOpen)

  @Composable
  private fun Page(state: BackupPageUi) {
    BackupPageScreen(state = state, actions = BackupPageActions(), onBack = {})
  }

  // --- La pagina Backup -------------------------------------------------------------------------------------------------------

  private val firstBackupRunning =
    BackupStatus.Running(BackupKind.PHOTOS, 1_240, 6_658, 2_100_000_000, 48_000_000_000, 4_800_000, BackupTrigger.FIRST_BACKUP)

  @Test
  fun paginaPrimoBackupInCorso() = shoot("pagina-primo-backup-in-corso") { Page(page(firstBackupRunning)) }

  @Test
  fun paginaPrimoBackupInCorsoTestoGrande() = shoot("pagina-primo-backup-in-corso", fontScale = LARGE_TEXT) { Page(page(firstBackupRunning)) }

  @Test
  fun paginaTuttoAlSicuro() = shoot("pagina-tutto-al-sicuro") {
    Page(page(BackupStatus.UpToDate(safeCount = 6_658, atNasMillis = 1_792_119_120_000), sources = BackupSamples.sources.take(1) + BackupSamples.sources.drop(2)))
  }

  @Test
  fun paginaInCoda() = shoot("pagina-in-coda") { Page(page(BackupStatus.Waiting(312, 1_400_000_000, WaitReason.CHARGING))) }

  @Test
  fun paginaFermato() = shoot("pagina-fermato") { Page(page(BackupStatus.Stopped(StopBy.USER))) }

  @Test
  fun paginaProblemaQuota() = shoot("pagina-problema-quota") { Page(page(BackupStatus.Problem(BackupProblemCode.QUOTA, 0))) }

  @Test
  fun paginaProblemaQuotaTestoGrande() = shoot("pagina-problema-quota", fontScale = LARGE_TEXT) {
    Page(page(BackupStatus.Problem(BackupProblemCode.QUOTA, 0)))
  }

  @Test
  fun paginaProblemaDiUnMedia() = shoot("pagina-problema-copie-non-integre") {
    Page(page(BackupStatus.Problem(BackupProblemCode.VERIFY_FAILED, 12)))
  }

  @Test
  fun paginaProblemiDelleSorgenti() = shoot("pagina-problemi-sorgenti") {
    Page(page(firstBackupRunning, sources = BackupSamples.sources))
  }

  @Test
  fun paginaNonConfigurato() = shoot("pagina-non-configurato") {
    Page(page(BackupStatus.NotConfigured(setOf(BackupSetupStep.MEDIA_PERMISSION, BackupSetupStep.DEVICE_FOLDER)), sources = emptyList(), folder = false))
  }

  @Test
  fun paginaInProva() = shoot("pagina-in-prova") { Page(page(BackupStatus.TestOnly)) }

  @Test
  fun paginaPronto() = shoot("pagina-pronto-per-il-primo-backup") {
    Page(page(BackupStatus.FirstBackupReady(BackupSamples.firstBackupSummary), folder = false))
  }

  @Test
  fun paginaCercaSulNas() = shoot("pagina-cerca-sul-nas") {
    Page(page(BackupStatus.FirstBackupReady(BackupSamples.searchingSummary)))
  }

  /** Fase I (punto 5): la ricerca ferma per il caldo, con la clessidra. */
  @Test
  fun paginaCercaInPausa() = shoot("pagina-cerca-in-pausa") {
    Page(page(BackupStatus.FirstBackupReady(BackupSamples.heldSummary)))
  }

  /** Fase I: a ricerca finita, con copie simili da confrontare, la pagina e' pronta. */
  @Test
  fun paginaProntaConConfronto() = shoot("pagina-pronto-con-confronto") {
    Page(page(BackupStatus.FirstBackupReady(BackupSamples.comparingSummary)))
  }

  // --- Il foglio del primo backup ---------------------------------------------------------------------------------------------

  @Composable
  private fun SheetOverPage(summary: BackupSummary) {
    Page(page(BackupStatus.FirstBackupReady(summary), sheetOpen = true))
    FirstBackupSheet(visible = true, summary = summary, onTonight = {}, onNow = {}, onDismissRequest = {})
  }

  @Test
  fun foglioPrimoBackup() = shoot("foglio-primo-backup") { SheetOverPage(BackupSamples.firstBackupSummary) }

  @Test
  fun foglioPrimoBackupTestoGrande() = shoot("foglio-primo-backup", fontScale = LARGE_TEXT) { SheetOverPage(BackupSamples.firstBackupSummary) }

  @Test
  fun foglioPrimoBackupConDueStime() = shoot("foglio-primo-backup-due-stime") { SheetOverPage(BackupSamples.twoEstimatesSummary) }

  @Test
  fun foglioPrimoBackupMentreConfronta() = shoot("foglio-primo-backup-confronto") { SheetOverPage(BackupSamples.comparingSummary) }

  /** Fase I: la ricerca con il numero dei file guardati e la barra. */
  @Test
  fun foglioPrimoBackupCercaConNumero() = shoot("foglio-primo-backup-cerca-numero") { SheetOverPage(BackupSamples.searchingSummary) }

  /** Fase I (punto 5): la ricerca ferma per il caldo. */
  @Test
  fun foglioPrimoBackupCercaInPausa() = shoot("foglio-primo-backup-cerca-in-pausa") { SheetOverPage(BackupSamples.heldSummary) }

  @Test
  fun foglioPrimoBackupCercaSulNas() = shoot("foglio-primo-backup-cerca-sul-nas") {
    SheetOverPage(BackupSamples.firstBackupSummary.copy(existing = dev.pampa.mycloudgallery.core.backup.contract.ExistingCopiesState(false, 0), estimate = null))
  }

  @Test
  fun foglioPrimoBackupRegoleCheNonPartono() = shoot("foglio-primo-backup-non-parte") { SheetOverPage(BackupSamples.neverFinishesSummary) }

  // --- Impostazioni e cartella del dispositivo --------------------------------------------------------------------------------

  private fun settings(
    folders: FoldersState = FoldersState.Ready(BackupSamples.phoneFolders),
    chosen: BackupSources = BackupSources(setOf(BackupSource.Camera, BackupSource.Screenshots, BackupSamples.phoneFolders.last().source)),
  ) = SettingsUi(
    folders = folders,
    chosen = chosen,
    deviceFolder = BackupSamples.deviceFolder,
    protectNewPhotos = true,
    rulesAvailable = true,
    editable = true,
  )

  @Test
  fun impostazioni() = shoot("impostazioni") { BackupSettingsScreen(state = settings(), actions = SettingsActions(), onBack = {}) }

  @Test
  fun impostazioniTestoGrande() = shoot("impostazioni", fontScale = LARGE_TEXT) {
    BackupSettingsScreen(state = settings(), actions = SettingsActions(), onBack = {})
  }

  @Test
  fun impostazioniCartelleNonLeggibili() = shoot("impostazioni-cartelle-non-leggibili") {
    BackupSettingsScreen(state = settings(folders = FoldersState.Unavailable), actions = SettingsActions(), onBack = {})
  }

  @Test
  fun cartellaDelDispositivoCheCeGia() = shoot("cartella-dispositivo-esiste") {
    DeviceFolderScreen(
      state = FolderUi.Choose(BackupSamples.deviceFolder, BackupSamples.existingFolderProposal.existing, BackupSamples.existingFolderProposal.alternative),
      actions = FolderActions(),
      onBack = {},
    )
  }

  @Test
  fun cartellaDelDispositivoNuova() = shoot("cartella-dispositivo-nuova") {
    DeviceFolderScreen(state = FolderUi.Choose(BackupSamples.deviceFolder, null, null), actions = FolderActions(), onBack = {})
  }

  companion object {
    const val DIR = "../docs/screenshots/5-P5"
    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = SHOT_PHONE_QUALIFIERS)
class BackupPhoneScreenshotTest(dark: Boolean) : BackupScreenshotBase(dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = TABLET_QUALIFIERS)
class BackupTabletScreenshotTest(dark: Boolean) : BackupScreenshotBase(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}
