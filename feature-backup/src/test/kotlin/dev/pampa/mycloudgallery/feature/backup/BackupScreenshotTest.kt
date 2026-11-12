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
    // BF-12: prima del primo backup niente e' ancora al sicuro (l'anello parte da zero), come sul telefono.
    sources: List<dev.pampa.mycloudgallery.core.backup.contract.SourceStatus> =
      if (status is BackupStatus.FirstBackupReady) BackupSamples.firstBackupSources else BackupSamples.sources,
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

  // BF-12: i nuovi stati anche con il testo grande (il titolo della ricerca e la riga della clessidra sono quelli che vanno a capo).

  @Test
  fun foglioPrimoBackupCercaConNumeroTestoGrande() =
    shoot("foglio-primo-backup-cerca-numero", fontScale = LARGE_TEXT) { SheetOverPage(BackupSamples.searchingSummary) }

  @Test
  fun foglioPrimoBackupCercaInPausaTestoGrande() =
    shoot("foglio-primo-backup-cerca-in-pausa", fontScale = LARGE_TEXT) { SheetOverPage(BackupSamples.heldSummary) }

  @Test
  fun foglioPrimoBackupMentreConfrontaTestoGrande() =
    shoot("foglio-primo-backup-confronto", fontScale = LARGE_TEXT) { SheetOverPage(BackupSamples.comparingSummary) }

  @Test
  fun paginaCercaInPausaTestoGrande() = shoot("pagina-cerca-in-pausa", fontScale = LARGE_TEXT) {
    Page(page(BackupStatus.FirstBackupReady(BackupSamples.heldSummary)))
  }

  /** BF-1: «Sto partendo» dopo «Inizia ora». Giro 2 (BF2-4): con l'anello di un primo backup vero, a zero (non il 99% di un campione qualunque). */
  @Test
  fun paginaStaPartendo() = shoot("pagina-sto-partendo") { Page(startingPage) }

  @Test
  fun paginaStaPartendoTestoGrande() = shoot("pagina-sto-partendo", fontScale = LARGE_TEXT) { Page(startingPage) }

  private val startingPage: BackupPageUi
    get() = page(
      BackupStatus.Running(BackupKind.PHOTOS, 0, 8_445, 0L, 40_000_000_000, null, BackupTrigger.FIRST_BACKUP, starting = true),
      sources = BackupSamples.firstBackupSources,
    )

  /** Giro 2 (BF2-5): il primo backup grande nei primi 15 minuti (nessuna durata misurata): la frase delle ore. */
  @Test
  fun paginaPrimoBackupPrimiMinuti() = shoot("pagina-primo-backup-primi-minuti") {
    Page(page(BackupStatus.Running(BackupKind.PHOTOS, 40, 8_445, 80_000_000, 40_000_000_000, null, BackupTrigger.FIRST_BACKUP), sources = BackupSamples.firstBackupSources))
  }

  /** Giro 2 (BF2-2, BF2-7): appena aperta l'app, il riepilogo non ancora calcolato: il nome e l'anello, nessun segno ne' riga. */
  @Test
  fun paginaAFreddo() = shoot("pagina-a-freddo") { Page(page(BackupStatus.FirstBackupReady(BackupSummary.COUNTING))) }

  /** Giro 2 (BF2-7): la ricerca ferma per un motivo che il backup non nomina: la pausa e «Vedi perche'». */
  @Test
  fun paginaCercaInPausaSenzaMotivo() = shoot("pagina-cerca-in-pausa-senza-motivo") {
    Page(page(BackupStatus.FirstBackupReady(BackupSamples.pausedSearchSummary)))
  }

  @Test
  fun paginaCercaInPausaSenzaMotivoTestoGrande() = shoot("pagina-cerca-in-pausa-senza-motivo", fontScale = LARGE_TEXT) {
    Page(page(BackupStatus.FirstBackupReady(BackupSamples.pausedSearchSummary)))
  }

  @Test
  fun paginaProntoTestoGrande() = shoot("pagina-pronto-per-il-primo-backup", fontScale = LARGE_TEXT) {
    Page(page(BackupStatus.FirstBackupReady(BackupSamples.firstBackupSummary), folder = false))
  }

  @Test
  fun paginaCercaSulNasTestoGrande() = shoot("pagina-cerca-sul-nas", fontScale = LARGE_TEXT) {
    Page(page(BackupStatus.FirstBackupReady(BackupSamples.searchingSummary)))
  }

  /** Giro 2 (BF2-7): il foglio con la ricerca ferma per un motivo che il backup non nomina. */
  @Test
  fun foglioPrimoBackupCercaInPausaSenzaMotivo() = shoot("foglio-primo-backup-cerca-in-pausa-senza-motivo") {
    SheetOverPage(BackupSamples.pausedSearchSummary)
  }

  /** Giro 2 (BS2-4, BF2-7): il confronto che aspetta anteprime non ancora pronte, con la frase neutra. */
  @Test
  fun foglioPrimoBackupConfrontoInPausa() = shoot("foglio-primo-backup-confronto-in-pausa") { SheetOverPage(BackupSamples.comparingWaitingSummary) }

  @Test
  fun foglioPrimoBackupConfrontoInPausaTestoGrande() =
    shoot("foglio-primo-backup-confronto-in-pausa", fontScale = LARGE_TEXT) { SheetOverPage(BackupSamples.comparingWaitingSummary) }

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
