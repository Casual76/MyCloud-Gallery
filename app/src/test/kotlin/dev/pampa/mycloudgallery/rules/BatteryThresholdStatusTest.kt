package dev.pampa.mycloudgallery.rules

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.ai.AnalysisStatusReducers
import dev.pampa.mycloudgallery.backup.BackupStatusReducers
import dev.pampa.mycloudgallery.core.ai.contract.AiStatus
import dev.pampa.mycloudgallery.core.backup.contract.WaitReason as BackupWait
import dev.pampa.mycloudgallery.core.library.contract.LibraryAnalysisStatus
import dev.pampa.mycloudgallery.core.library.contract.LibraryAnalysisWait
import dev.pampa.mycloudgallery.core.rules.AndroidLimits
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.DeviceRoles
import dev.pampa.mycloudgallery.core.rules.NetworkState
import dev.pampa.mycloudgallery.core.rules.NetworkTransport
import dev.pampa.mycloudgallery.core.rules.PowerState
import dev.pampa.mycloudgallery.core.rules.Preset
import dev.pampa.mycloudgallery.core.rules.RuleDecision
import dev.pampa.mycloudgallery.core.rules.RuleEvaluator
import dev.pampa.mycloudgallery.core.rules.SystemState
import dev.pampa.mycloudgallery.core.rules.ThermalStatus
import dev.pampa.mycloudgallery.core.rules.WaitReason
import dev.pampa.mycloudgallery.core.rules.WorkRequest
import dev.pampa.mycloudgallery.core.rules.settings.RuleSettings
import dev.pampa.mycloudgallery.core.rules.settings.RuleSettingsStore
import dev.pampa.mycloudgallery.core.rules.traffic.BillingCycle
import dev.pampa.mycloudgallery.core.rules.traffic.CycleUsage
import dev.pampa.mycloudgallery.core.search.contract.SearchCoverage
import java.time.Duration
import java.time.LocalDateTime
import java.time.YearMonth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Pacchetto B: lo stato che le pagine mostrano dice il vero con una soglia alta, e **non dice «batteria» quando l'interruttore di quel gruppo e' spento**. Si prova
 * con il valutatore vero e i riduttori veri (backup e analisi), non con verdetti scritti a mano; e la porta che le pagine usano, con le impostazioni in memoria.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BatteryThresholdStatusTest {

  private val evaluator = RuleEvaluator()

  private fun phone(level: Int, plugged: Boolean = false): SystemState = SystemState(
    now = LocalDateTime.of(2026, 10, 13, 12, 0),
    power = PowerState(
      pluggedIn = plugged,
      levelPercent = level,
      chargingPaused = false,
      powerSaveMode = false,
      thermalStatus = ThermalStatus.NONE,
      thermalHeadroom = null,
      unpluggedFor = if (plugged) Duration.ZERO else Duration.ofHours(1),
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

  private fun decide(activity: BackgroundActivity, settings: RuleSettings, level: Int): RuleDecision = evaluator.evaluate(
    WorkRequest.Background(activity, writesToNas = true),
    settings.toRuleSet(),
    phone(level),
    CycleUsage(BillingCycle(YearMonth.of(2026, 10))),
  )

  private fun analysis(decision: RuleDecision): LibraryAnalysisStatus = AnalysisStatusReducers.reduce(
    AnalysisStatusReducers.Facts(
      indexer = true,
      status = AiStatus.Idle(null),
      remaining = 10,
      coverage = SearchCoverage.Partial(covered = 40, total = 100, reasons = emptyMap()),
      decision = decision,
    ),
  )

  // --- Il backup ----------------------------------------------------------------------------------------

  @Test
  fun backup_conSogliaAlta_eTelefonoSotto_dicePerche_conIlNumeroNelVerdetto() {
    val settings = RuleSettings(preset = Preset.WIFI_ALWAYS, batteryThresholdPercent = 60)

    val decision = decide(BackgroundActivity.PHOTO_BACKUP, settings, level = 55)

    assertThat(decision).isEqualTo(RuleDecision.Wait(WaitReason.BatteryLow(55, 60)))
    assertThat(BackupStatusReducers.waitReasonOf(decision)).isEqualTo(BackupWait.BATTERY)
  }

  @Test
  fun backup_conInterruttoreSpento_lostatoNonDiceBatteria() {
    val settings = RuleSettings(preset = Preset.WIFI_ALWAYS, batteryThresholdPercent = 60, batteryThresholdForBackup = false)

    for (activity in listOf(BackgroundActivity.PHOTO_BACKUP, BackgroundActivity.VIDEO_BACKUP)) {
      val decision = decide(activity, settings, level = 55)
      assertThat(decision).isInstanceOf(RuleDecision.Run::class.java)
      assertThat(BackupStatusReducers.waitReasonOf(decision)).isEqualTo(BackupWait.NEXT_RUN)
    }
  }

  // --- L'analisi ----------------------------------------------------------------------------------------

  @Test
  fun analisi_conSogliaAlta_eTelefonoSotto_ePausaPerLaBatteria() {
    val settings = RuleSettings(preset = Preset.WIFI_ALWAYS, batteryThresholdPercent = 60)

    val status = analysis(decide(BackgroundActivity.AI_COMPUTE, settings, level = 55))

    assertThat(status).isEqualTo(LibraryAnalysisStatus.Paused(LibraryAnalysisWait.BATTERY, 10, 40, 100))
  }

  @Test
  fun analisi_conInterruttoreSpento_lostatoNonDiceBatteria() {
    val settings = RuleSettings(preset = Preset.WIFI_ALWAYS, batteryThresholdPercent = 60, batteryThresholdForAnalysis = false)

    val status = analysis(decide(BackgroundActivity.AI_COMPUTE, settings, level = 55))

    // Le regole lasciano partire: in coda, senza un motivo di batteria.
    assertThat(status).isEqualTo(LibraryAnalysisStatus.Queued(10, 40, 100))
  }

  @Test
  fun gliInterruttori_sonoIndipendenti_ilBackupSpentoNonSpegneLaPausaDellAnalisi() {
    val settings = RuleSettings(preset = Preset.WIFI_ALWAYS, batteryThresholdPercent = 60, batteryThresholdForBackup = false)

    assertThat(decide(BackgroundActivity.PHOTO_BACKUP, settings, level = 55)).isInstanceOf(RuleDecision.Run::class.java)
    assertThat(analysis(decide(BackgroundActivity.AI_COMPUTE, settings, level = 55)))
      .isEqualTo(LibraryAnalysisStatus.Paused(LibraryAnalysisWait.BATTERY, 10, 40, 100))
  }

  // --- La porta delle pagine ----------------------------------------------------------------------------

  private class MemoryRuleStore(initial: RuleSettings = RuleSettings()) : RuleSettingsStore {
    val state = MutableStateFlow(initial)
    override fun observe(): Flow<RuleSettings> = state
    override suspend fun current(): RuleSettings = state.value
    override suspend fun save(settings: RuleSettings) {
      state.value = settings
    }
    override suspend fun update(transform: (RuleSettings) -> RuleSettings) {
      state.value = transform(state.value)
    }
  }

  @Test
  fun laPorta_senzaScelte_diceIlLivelloDiPrimaConTuttoAcceso() = runTest(UnconfinedTestDispatcher()) {
    val port = AppBatteryGuard(MemoryRuleStore(), backgroundScope)

    val value = port.value.value
    assertThat(value.levelPercent).isEqualTo(20)
    assertThat(value.forBackup).isTrue()
    assertThat(value.forAnalysis).isTrue()
  }

  @Test
  fun laPorta_scriveSulleImpostazioniDelleRegole_eLeAltreSceltePassanoIntatte() = runTest(UnconfinedTestDispatcher()) {
    val store = MemoryRuleStore(RuleSettings(preset = Preset.BALANCED, allowRoaming = true))
    val port = AppBatteryGuard(store, backgroundScope)

    port.setLevel(55)
    port.setForBackup(false)
    port.setForAnalysis(false)

    assertThat(store.state.value.batteryThresholdPercent).isEqualTo(55)
    assertThat(store.state.value.batteryThresholdForBackup).isFalse()
    assertThat(store.state.value.batteryThresholdForAnalysis).isFalse()
    assertThat(store.state.value.preset).isEqualTo(Preset.BALANCED)
    assertThat(store.state.value.allowRoaming).isTrue()
    assertThat(port.value.value.levelPercent).isEqualTo(55)
    assertThat(port.value.value.forBackup).isFalse()
  }

  @Test
  fun laPorta_portaIlLivelloSulPassoDiCinquePiuVicino() = runTest(UnconfinedTestDispatcher()) {
    val store = MemoryRuleStore()
    val port = AppBatteryGuard(store, backgroundScope)

    port.setLevel(33)
    assertThat(store.state.value.batteryThresholdPercent).isEqualTo(35)

    port.setLevel(2)
    assertThat(store.state.value.batteryThresholdPercent).isEqualTo(5)

    port.setLevel(100)
    assertThat(store.state.value.batteryThresholdPercent).isEqualTo(80)
  }
}
