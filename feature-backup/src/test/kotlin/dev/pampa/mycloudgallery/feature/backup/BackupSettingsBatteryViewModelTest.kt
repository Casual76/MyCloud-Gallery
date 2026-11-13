package dev.pampa.mycloudgallery.feature.backup

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.design.BatteryGuardPort
import dev.pampa.mycloudgallery.core.designsystem.design.BatteryGuardValue
import dev.pampa.mycloudgallery.feature.backup.settings.BackupSettingsViewModel
import java.util.Optional
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Pacchetto B, BS-5 (critico Sol): il ViewModel delle impostazioni del Backup con la porta della batteria. Stato iniziale «sto caricando» (mai un 20% finto), la scelta che non si salva fa **tornare** il
 * controllo al valore salvato e lo dice (`batterySaveFailed`), la scelta seguente toglie l'avviso. Dispatcher standard (non `Unconfined`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupSettingsBatteryViewModelTest {

  private val main = StandardTestDispatcher()

  @Before fun setMain() = Dispatchers.setMain(main)

  @After fun resetMain() = Dispatchers.resetMain()

  private class FakePort(initial: BatteryGuardValue?, var saves: Boolean = true) : BatteryGuardPort {
    private val state = MutableStateFlow(initial)
    override val value: StateFlow<BatteryGuardValue?> get() = state

    fun load(v: BatteryGuardValue) {
      state.value = v
    }

    override suspend fun setLevel(percent: Int): Boolean = saves.also { if (it) state.value = state.value?.copy(levelPercent = percent) }

    override suspend fun setForBackup(on: Boolean): Boolean = saves.also { if (it) state.value = state.value?.copy(forBackup = on) }

    override suspend fun setForAnalysis(on: Boolean): Boolean = saves.also { if (it) state.value = state.value?.copy(forAnalysis = on) }
  }

  private fun viewModel(port: BatteryGuardPort?) = BackupSettingsViewModel(
    settings = Optional.empty(),
    phoneFolders = Optional.empty(),
    rules = Optional.empty(),
    actions = Optional.empty(),
    batteryGuard = Optional.ofNullable(port),
  )

  @Test
  fun senzaLaPorta_ilControlloNonCe() = runTest(main) {
    val vm = viewModel(null)
    backgroundScope.launch { vm.state.collect {} }
    advanceUntilIdle()

    assertThat(vm.state.value.battery).isNull()
    assertThat(vm.state.value.batteryLoading).isFalse()
  }

  @Test
  fun finoAllaPrimaLettura_sidiceSoStoCaricando_poiIlValoreVero() = runTest(main) {
    val port = FakePort(initial = null)
    val vm = viewModel(port)
    backgroundScope.launch { vm.state.collect {} }
    advanceUntilIdle()

    assertThat(vm.state.value.battery).isNull()
    assertThat(vm.state.value.batteryLoading).isTrue()

    port.load(BatteryGuardValue(55, forBackup = false, forAnalysis = true))
    advanceUntilIdle()
    assertThat(vm.state.value.battery).isEqualTo(BatteryGuardValue(55, forBackup = false, forAnalysis = true))
    assertThat(vm.state.value.batteryLoading).isFalse()
  }

  @Test
  fun unaScelta_cheNonSiSalva_ilControlloTornaAlSalvato_eLoDice_poiLaSuccessivaTogliLAvviso() = runTest(main) {
    val port = FakePort(initial = BatteryGuardValue(20, true, true), saves = false)
    val vm = viewModel(port)
    backgroundScope.launch { vm.state.collect {} }
    advanceUntilIdle()

    vm.setBatteryLevel(60)
    advanceUntilIdle()
    assertThat(vm.state.value.battery?.levelPercent).isEqualTo(20) // il salvato non e' cambiato
    assertThat(vm.state.value.batterySaveFailed).isTrue()

    port.saves = true
    vm.setBatteryForBackup(false)
    runCurrent()
    advanceUntilIdle()
    assertThat(vm.state.value.batterySaveFailed).isFalse()
    assertThat(vm.state.value.battery?.forBackup).isFalse()
  }
}
