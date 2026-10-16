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

  override fun perform(action: BackupAction) {
    performed += action
  }

  override fun backupNow() {
    backupNowCalls++
  }

  override fun stop() {
    stopCalls++
  }

  override fun open(screen: BackupScreen) {
    opened += screen
  }
}
