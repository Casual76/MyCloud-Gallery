package dev.pampa.mycloudgallery.feature.backup.testing

import dev.pampa.mycloudgallery.core.backup.contract.BackupAction
import dev.pampa.mycloudgallery.core.backup.contract.BackupEstimate
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.BackupSetupStep
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.BackupSummary
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.DeviceFolder
import dev.pampa.mycloudgallery.core.backup.contract.ExistingCopiesState
import dev.pampa.mycloudgallery.core.backup.contract.MediaBackupState
import dev.pampa.mycloudgallery.core.backup.contract.NotOnNasReason
import dev.pampa.mycloudgallery.core.backup.contract.SourceStatus
import dev.pampa.mycloudgallery.core.backup.contract.StopBy
import dev.pampa.mycloudgallery.core.backup.contract.SummaryCount
import dev.pampa.mycloudgallery.core.backup.contract.SummaryWarning
import dev.pampa.mycloudgallery.core.backup.contract.WaitReason
import dev.pampa.mycloudgallery.feature.backup.contract.BackupActions
import dev.pampa.mycloudgallery.feature.backup.contract.BackupScreen
import dev.pampa.mycloudgallery.feature.backup.contract.DeviceFolderProposal
import dev.pampa.mycloudgallery.feature.backup.contract.ExistingFolder
import dev.pampa.mycloudgallery.feature.backup.contract.PhoneFolder

/**
 * Fase 5A (P0): gli stati di esempio del backup per i test e gli screenshot di P5 (`docs/screenshots/5-P5/`): **uno per ogni caso** del
 * §1.5 della 5A e **uno per ogni codice** del §2.6, con i numeri del §2.9 (S25: ~6.700 media, ~48 GB). `BackupSamplesTest` controlla
 * che non ne manchi nessuno quando il contratto cresce.
 */
object BackupSamples {

  /** Il riepilogo del primo backup (§2.11): 1.240 collegati, 312 con una copia simile, 5.106 nuovi. */
  val firstBackupSummary: BackupSummary = BackupSummary(
    existing = ExistingCopiesState.SETTLED,
    linked = SummaryCount(photos = 1_180, videos = 60, photoBytes = 3_900_000_000, videoBytes = 6_100_000_000),
    similar = SummaryCount(photos = 312, videos = 0, photoBytes = 700_000_000, videoBytes = 0),
    fresh = SummaryCount(photos = 4_782, videos = 324, photoBytes = 6_200_000_000, videoBytes = 31_900_000_000),
    inCommonArea = SummaryCount(photos = 40, videos = 0, photoBytes = 90_000_000, videoBytes = 0),
    bySource = mapOf(
      BackupSource.Camera.id to SummaryCount(866, 384, 3_500_000_000, 38_000_000_000),
      BackupSource.Screenshots.id to SummaryCount(915, 0, 600_000_000, 0),
    ),
    estimate = BackupEstimate(durationMillis = 9_360_000, nights = 1, wifiBytes = 97_000_000_000, mobileBytes = 0, batteryPercent = null),
    warnings = setOf(SummaryWarning.ON_BATTERY),
  )

  /**
   * Il riepilogo con le due stime di P4: "Stanotte" (`estimate`, segue il preset) e "Inizia ora" (`estimateNow`, parte subito e scavalca
   * fascia e carica), con una parte del traffico sui dati mobili (la conferma con la stima).
   */
  val twoEstimatesSummary: BackupSummary = firstBackupSummary.copy(
    estimateNow = BackupEstimate(durationMillis = 8_100_000, nights = 1, wifiBytes = 82_000_000_000, mobileBytes = 15_000_000_000, batteryPercent = 18),
    warnings = setOf(SummaryWarning.ON_BATTERY, SummaryWarning.MOBILE_DATA, SummaryWarning.PARTIAL_ACCESS),
  )

  /** Le regole di adesso non lasciano partire il backup (`BackupEstimate.neverFinishes`, P4): il foglio lo dice invece di "0 notti". */
  val neverFinishesSummary: BackupSummary = firstBackupSummary.copy(
    estimate = BackupEstimate(durationMillis = 0, nights = 0, wifiBytes = 97_000_000_000, mobileBytes = 0, batteryPercent = null, neverFinishes = true),
    warnings = setOf(SummaryWarning.LONG_FIRST_BACKUP),
  )

  /** Lo stesso riepilogo mentre si confronta ancora con le copie sul NAS (§2.11 punti 1 e 6). */
  val comparingSummary: BackupSummary =
    firstBackupSummary.copy(existing = ExistingCopiesState(privateAreaScanned = true, similarPending = 120))

  /** Un esempio per ogni caso di [BackupStatus] (problemi a parte: [problems]). */
  val statuses: List<BackupStatus> = listOf(
    BackupStatus.NotConfigured(setOf(BackupSetupStep.DEVICE_FOLDER)),
    BackupStatus.TestOnly,
    BackupStatus.FirstBackupReady(firstBackupSummary),
    BackupStatus.FirstBackupReady(comparingSummary),
    BackupStatus.UpToDate(safeCount = 6_658, atNasMillis = 1_792_119_120_000),
    BackupStatus.Waiting(items = 312, bytes = 1_400_000_000, reason = WaitReason.CHARGING),
    BackupStatus.Running(BackupKind.PHOTOS, 1_240, 6_658, 2_100_000_000, 48_000_000_000, 4_800_000, BackupTrigger.FIRST_BACKUP),
    BackupStatus.Stopped(StopBy.USER),
  )

  /** Un problema per codice (§2.6): i media riguardati per i problemi di un media, 0 per quelli del giro intero. */
  val problems: List<BackupStatus.Problem> = BackupProblemCode.entries.map { BackupStatus.Problem(it, if (it.marksMedia) 3 else 0) }

  /** Un esempio per ogni caso di [MediaBackupState] (la riga del viewer). */
  val mediaStates: List<MediaBackupState> = listOf(
    MediaBackupState.NotMine,
    MediaBackupState.NotOnNas(NotOnNasReason.SOURCE_OFF),
    MediaBackupState.Queued(WaitReason.CHARGING),
    MediaBackupState.Uploading(bytesDone = 12_000_000, bytesTotal = 48_000_000),
    MediaBackupState.Verifying(bytesDone = 30_000_000, bytesTotal = 48_000_000),
    MediaBackupState.Safe(verifiedAtMillis = 1_792_119_120_000),
    MediaBackupState.OnlyCommonCopy,
    MediaBackupState.Unverified,
    MediaBackupState.Problem(BackupProblemCode.VERIFY_FAILED),
    MediaBackupState.LostBeforeBackup,
  )

  /**
   * Le cartelle del telefono che Impostazioni -> Backup elenca (§2.1, §0 del progetto: i numeri dell'S25 del 12/10): la fotocamera e gli
   * screenshot, poi le app di messaggistica, Quick Share, una sottocartella di `DCIM` e `Pictures`.
   */
  val phoneFolders: List<PhoneFolder> = listOf(
    PhoneFolder(BackupSource.Camera, media = 866, bytes = 3_500_000_000),
    PhoneFolder(BackupSource.Screenshots, media = 915, bytes = 600_000_000),
    PhoneFolder(BackupSource.Folder("external_primary", "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/"), media = 2_105, bytes = 4_200_000_000),
    PhoneFolder(BackupSource.Folder("external_primary", "DCIM/Vecchie/"), media = 1_088, bytes = 5_100_000_000),
    PhoneFolder(BackupSource.Folder("external_primary", "Download/Quick Share/"), media = 654, bytes = 2_300_000_000),
    PhoneFolder(BackupSource.Folder("external_primary", "Pictures/Telegram/"), media = 224, bytes = 310_000_000),
  )

  /** Il nome del dispositivo ripulito per `SharePath` (D7). */
  val deviceFolder: DeviceFolder = DeviceFolder("S25 di Mario")

  /** La proposta senza nessuna cartella sul NAS con quel nome. */
  val freshFolderProposal: DeviceFolderProposal = DeviceFolderProposal(suggested = deviceFolder, existing = null, alternative = null)

  /** E-B6, E-B16: `Backup/S25 di Mario` c'e' gia' con 1.234 file: "Continua lì" o "Nuova cartella". */
  val existingFolderProposal: DeviceFolderProposal =
    DeviceFolderProposal(suggested = deviceFolder, existing = ExistingFolder(files = 1_234), alternative = DeviceFolder("S25 di Mario (2)"))

  /** Le sorgenti della pagina Backup: una a posto, una con un problema, una spenta. */
  val sources: List<SourceStatus> = listOf(
    SourceStatus(BackupSource.Camera, active = true, media = 1_250, safe = 1_250, queued = 0, problems = emptyMap()),
    SourceStatus(
      BackupSource.Screenshots, active = true, media = 915, safe = 900, queued = 12,
      problems = mapOf(BackupProblemCode.LOST_BEFORE_BACKUP to 3),
    ),
    SourceStatus(
      BackupSource.Folder("external_primary", "Pictures/Telegram/"), active = false, media = 224, safe = 0, queued = 0,
      problems = emptyMap(),
    ),
  )
}

/** Le azioni registrate (per i test delle schermate di P5: "un tocco chiama la porta, una volta"). */
class RecordingBackupActions : BackupActions {
  val performed: MutableList<BackupAction> = ArrayList()
  val opened: MutableList<BackupScreen> = ArrayList()

  var backupNowCalls: Int = 0
    private set

  var stopCalls: Int = 0
    private set

  /** Le scelte del foglio del primo backup: `true` = "Stanotte", `false` = "Inizia ora". */
  val firstBackupChoices: MutableList<Boolean> = ArrayList()

  override fun perform(action: BackupAction) {
    performed += action
  }

  override fun backupNow() {
    backupNowCalls++
  }

  override fun stop() {
    stopCalls++
  }

  override fun startFirstBackup(tonight: Boolean) {
    firstBackupChoices += tonight
  }

  override fun open(screen: BackupScreen) {
    opened += screen
  }

  /** Quante volte la pagina e' tornata davanti all'utente (BK-3, BK-13). */
  var resumedCalls: Int = 0
    private set

  override fun onPageResumed() {
    resumedCalls++
  }
}
