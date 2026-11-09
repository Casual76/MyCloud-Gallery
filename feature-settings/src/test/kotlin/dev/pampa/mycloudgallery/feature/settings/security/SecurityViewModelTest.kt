package dev.pampa.mycloudgallery.feature.settings.security

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.security.AppLockMode
import dev.pampa.mycloudgallery.core.security.device.ScreenLockStatus
import dev.pampa.mycloudgallery.core.security.lock.AppLockController
import dev.pampa.mycloudgallery.core.security.lock.AppLockSettings
import dev.pampa.mycloudgallery.core.security.lock.AppLockState
import dev.pampa.mycloudgallery.core.security.lock.AppLockTimeout
import dev.pampa.mycloudgallery.core.security.lock.AuthAvailability
import dev.pampa.mycloudgallery.core.security.lock.AuthResult
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.support.FakeAppLockPreferences
import dev.pampa.mycloudgallery.feature.settings.support.FakeScreenLockProbe
import dev.pampa.mycloudgallery.feature.settings.support.MainDispatcherRule
import dev.pampa.mycloudgallery.feature.settings.support.keepAlive
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class SecurityViewModelTest {

  @get:Rule
  val main = MainDispatcherRule()

  private val probe = FakeScreenLockProbe()
  private val controller = AppLockController { 0L }

  private fun viewModel(initial: AppLockSettings = AppLockSettings(), prefs: FakeAppLockPreferences = FakeAppLockPreferences(initial)) =
    SecurityViewModel(prefs, probe, controller) to prefs

  @Test
  fun accendereIlBloccoSalvaLaCredenzialeDiSistema() = runTest {
    val (vm, prefs) = viewModel()
    keepAlive(vm.state)
    vm.enable()
    assertThat(prefs.updates.last().mode).isEqualTo(AppLockMode.SYSTEM_CREDENTIAL)
    assertThat(vm.state.value.lockEnabled).isTrue()
  }

  /** OB-4 della review generale: con la memoria piena la scrittura non riesce; l'interruttore torna dov'era e la pagina dice perche' (prima l'eccezione chiudeva l'app). */
  @Test
  fun conLaMemoriaPienaIlBloccoNonSiAccende_EDiceCheNonESalvato() = runTest {
    val prefs = FakeAppLockPreferences().also { it.failWrites = true }
    val (vm, _) = viewModel(prefs = prefs)
    keepAlive(vm.state)

    vm.enable()

    assertThat(vm.state.value.lockEnabled).isFalse()
    assertThat(vm.state.value.feedback).isNotNull()
    assertThat(vm.state.value.feedback!!.title).isEqualTo(R.string.security_not_saved_title)
  }

  @Test
  fun conLaMemoriaPienaIlBloccoNonSiSpegneENonCambiaIlTimeout_EDiceCheNonESalvato() = runTest {
    val prefs = FakeAppLockPreferences(AppLockSettings(AppLockMode.SYSTEM_CREDENTIAL, AppLockTimeout.AFTER_5_MINUTES)).also { it.failWrites = true }
    val (vm, _) = viewModel(prefs = prefs)
    keepAlive(vm.state)

    vm.disable { AuthResult.Success }
    assertThat(vm.state.value.lockEnabled).isTrue()
    assertThat(vm.state.value.feedback!!.title).isEqualTo(R.string.security_not_saved_title)

    vm.setTimeout(AppLockTimeout.IMMEDIATELY)
    assertThat(vm.state.value.settings.timeout).isEqualTo(AppLockTimeout.AFTER_5_MINUTES)
  }

  @Test
  fun senzaBloccoSchermoDelTelefonoNonSiAccendeEDiceIlPerche() = runTest {
    probe.status = ScreenLockStatus.NOT_SECURE
    val (vm, prefs) = viewModel()
    keepAlive(vm.state)
    vm.enable()
    assertThat(prefs.updates).isEmpty()
    assertThat(vm.state.value.lockEnabled).isFalse()
    assertThat(vm.state.value.feedback).isNotNull()
    assertThat(vm.state.value.screenLock.needsWarning).isTrue()
  }

  @Test
  fun spegnereIlBloccoVuoleLaCredenzialeDelTelefono() = runTest {
    val (vm, prefs) = viewModel(AppLockSettings(AppLockMode.SYSTEM_CREDENTIAL, AppLockTimeout.AFTER_5_MINUTES))
    keepAlive(vm.state)

    vm.disable { AuthResult.Cancelled }
    assertThat(prefs.updates).isEmpty()

    vm.disable { AuthResult.NotAvailable(AuthAvailability.NO_SCREEN_LOCK) }
    assertThat(prefs.updates).isEmpty()
    assertThat(vm.state.value.feedback).isNotNull()

    vm.disable { AuthResult.Success }
    assertThat(prefs.updates.last().mode).isEqualTo(AppLockMode.OFF)
    // Il tempo scelto resta, per quando si riaccende.
    assertThat(prefs.updates.last().timeout).isEqualTo(AppLockTimeout.AFTER_5_MINUTES)
  }

  @Test
  fun ilTempoDiRichiusuraSiSalvaSenzaToccareIlModo() = runTest {
    val (vm, prefs) = viewModel(AppLockSettings(AppLockMode.SYSTEM_CREDENTIAL, AppLockTimeout.AFTER_1_MINUTE))
    keepAlive(vm.state)
    vm.setTimeout(AppLockTimeout.IMMEDIATELY)
    assertThat(prefs.updates.last()).isEqualTo(AppLockSettings(AppLockMode.SYSTEM_CREDENTIAL, AppLockTimeout.IMMEDIATELY))
  }

  @Test
  fun bloccaOraRichiudeSoloSeIlBloccoEAttivo() = runTest {
    val (vm, _) = viewModel()
    controller.updateSettings(AppLockSettings(AppLockMode.SYSTEM_CREDENTIAL, AppLockTimeout.AFTER_1_MINUTE))
    controller.endAuthentication(success = false)
    // Appena configurato il controller parte bloccato (a freddo); lo sblocchiamo come farebbe l'utente.
    controller.beginAuthentication()
    controller.endAuthentication(success = true)
    assertThat(controller.state.value).isEqualTo(AppLockState.UNLOCKED)

    vm.lockNow()
    assertThat(controller.state.value).isEqualTo(AppLockState.LOCKED)
  }

  @Test
  fun bloccaOraNonFaNienteConIlBloccoSpento() = runTest {
    val (vm, _) = viewModel()
    controller.updateSettings(AppLockSettings())
    vm.lockNow()
    assertThat(controller.state.value).isEqualTo(AppLockState.UNLOCKED)
  }
}
