package dev.pampa.mycloudgallery.backup

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.BackupSummary
import dev.pampa.mycloudgallery.core.backup.contract.ExistingCopiesState
import dev.pampa.mycloudgallery.core.backup.contract.SummaryCount
import dev.pampa.mycloudgallery.core.backup.contract.WaitReason as BackupWait
import dev.pampa.mycloudgallery.core.library.contract.LibraryBackupStatus
import dev.pampa.mycloudgallery.core.library.contract.LibraryBackupWait
import dev.pampa.mycloudgallery.core.rules.AndroidLimits
import dev.pampa.mycloudgallery.core.rules.DeviceRoles
import dev.pampa.mycloudgallery.core.rules.NetworkState
import dev.pampa.mycloudgallery.core.rules.NetworkTransport
import dev.pampa.mycloudgallery.core.rules.PowerState
import dev.pampa.mycloudgallery.core.rules.Preset
import dev.pampa.mycloudgallery.core.rules.RuleEvaluator
import dev.pampa.mycloudgallery.core.rules.SystemState
import dev.pampa.mycloudgallery.core.rules.ThermalStatus
import dev.pampa.mycloudgallery.core.rules.WorkRequest
import dev.pampa.mycloudgallery.core.rules.settings.RuleSettings
import dev.pampa.mycloudgallery.core.rules.traffic.BillingCycle
import dev.pampa.mycloudgallery.core.rules.traffic.CycleUsage
import java.time.Duration
import java.time.LocalDateTime
import java.time.YearMonth
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Pacchetto B, BS-1 (critico Sol): con l'interruttore del Backup spento il **primo backup non resta fermo «per batteria»** per un prerequisito condiviso. Si prova il flusso intero con il valutatore
 * vero: la ricerca nell'area privata (`INDEX_SYNC`) e il confronto che aspetta le anteprime (`PREPARATION`) passano da [ExistingCopiesLive] (il `heldBy` del riepilogo), e il riepilogo
 * `FirstBackupReady` al piede del Backup: niente motivo di batteria. Con tutti e due gli interruttori accesi (come prima) il motivo c'e'.
 */
class BatteryFirstBackupTest {

  private val evaluator = RuleEvaluator()

  private fun phone(level: Int) = SystemState(
    now = LocalDateTime.of(2026, 10, 13, 12, 0),
    power = PowerState(
      pluggedIn = false,
      levelPercent = level,
      chargingPaused = false,
      powerSaveMode = false,
      thermalStatus = ThermalStatus.NONE,
      thermalHeadroom = null,
      unpluggedFor = Duration.ofHours(1),
      timeSinceThermalSevere = null,
    ),
    network = NetworkState(NetworkTransport.WIFI, isHome = true),
    nasReachable = true,
    roles = DeviceRoles(indexer = true, preparer = true),
    androidLimits = AndroidLimits(),
    appInForeground = false,
    profileActive = true,
    nasFrozen = null,
  )

  /** Soglia al 60%, telefono al 55%, a batteria, app in secondo piano: la situazione del critico. */
  private fun settings(forBackup: Boolean, forAnalysis: Boolean) =
    RuleSettings(preset = Preset.WIFI_ALWAYS, batteryThresholdPercent = 60, batteryThresholdForBackup = forBackup, batteryThresholdForAnalysis = forAnalysis)

  private suspend fun held(settings: RuleSettings, state: ExistingCopiesState, awaitingPreviews: Boolean): ExistingCopiesState =
    ExistingCopiesLive.of(state, scan = null, awaitingPreviews = awaitingPreviews) { request: WorkRequest ->
      evaluator.evaluate(request, settings.toRuleSet(), phone(55), CycleUsage(BillingCycle(YearMonth.of(2026, 10))))
    }

  private val scanning = ExistingCopiesState(privateAreaScanned = false, similarPending = 0)
  private val comparing = ExistingCopiesState(privateAreaScanned = true, similarPending = 7)

  private fun footer(existing: ExistingCopiesState): LibraryBackupStatus {
    val summary = BackupSummary.EMPTY.copy(fresh = SummaryCount(photos = 8, videos = 2, photoBytes = 10, videoBytes = 90), existing = existing)
    return BackupStatusReducers.footer(BackupStatus.FirstBackupReady(summary))
  }

  @Test
  fun conTuttiEDueGliInterruttoriAccesi_laScansioneEFermaPerLaBatteria_comePrima() = runTest {
    val state = held(settings(true, true), scanning, awaitingPreviews = false)

    assertThat(state.heldBy).isEqualTo(BackupWait.BATTERY)
    assertThat(footer(state)).isEqualTo(LibraryBackupStatus.Comparing(wait = LibraryBackupWait.BATTERY))
  }

  @Test
  fun conIlBackupSpento_laScansioneDelPrimoBackupNonEFermaPerLaBatteria_neIlPiede() = runTest {
    val state = held(settings(forBackup = false, forAnalysis = true), scanning, awaitingPreviews = false)

    assertThat(state.heldBy).isNull()
    assertThat(footer(state)).isEqualTo(LibraryBackupStatus.Comparing())
  }

  @Test
  fun conIlBackupSpento_ilConfrontoCheAspettaLeAnteprimeNonEFermoPerLaBatteria() = runTest {
    assertThat(held(settings(true, true), comparing, awaitingPreviews = true).heldBy).isEqualTo(BackupWait.BATTERY)
    assertThat(held(settings(forBackup = false, forAnalysis = true), comparing, awaitingPreviews = true).heldBy).isNull()
  }

  @Test
  fun conL_AnalisiSpenta_ilBackupAcceso_lIndiceELaPreparazioneNonSiFermanoNeppureLoro() = runTest {
    // BS-1: il prerequisito condiviso si ferma solo con tutti e due accesi; se anche uno solo e' spento, il gruppo escluso ha cio' che gli serve.
    assertThat(held(settings(forBackup = true, forAnalysis = false), scanning, awaitingPreviews = false).heldBy).isNull()
    assertThat(held(settings(forBackup = true, forAnalysis = false), comparing, awaitingPreviews = true).heldBy).isNull()
  }

  @Test
  fun conTuttiEDueSpenti_nienteSiFermaPerLaBatteria() = runTest {
    assertThat(held(settings(false, false), scanning, awaitingPreviews = false).heldBy).isNull()
    assertThat(held(settings(false, false), comparing, awaitingPreviews = true).heldBy).isNull()
  }
}
