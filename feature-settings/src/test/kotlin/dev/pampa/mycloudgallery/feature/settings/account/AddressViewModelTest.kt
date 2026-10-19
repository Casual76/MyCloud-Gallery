package dev.pampa.mycloudgallery.feature.settings.account

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.AdminActionResult
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.family.testing.FakeRegistryService
import dev.pampa.mycloudgallery.core.nas.contract.ConnectionReport
import dev.pampa.mycloudgallery.core.nas.contract.ConnectionTester
import dev.pampa.mycloudgallery.core.nas.contract.InvalidateReason
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.Stage
import dev.pampa.mycloudgallery.core.nas.contract.StageOutcome
import dev.pampa.mycloudgallery.core.nas.contract.StageResult
import dev.pampa.mycloudgallery.core.nas.contract.TestTarget
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasSessionManager
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import dev.pampa.mycloudgallery.feature.settings.network.SaveScope
import dev.pampa.mycloudgallery.feature.settings.support.FakeNasAccountSettings
import dev.pampa.mycloudgallery.feature.settings.support.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.util.Optional
import kotlin.time.Duration.Companion.milliseconds

/** Il cambio d'indirizzo del NAS (E-R7, C4): verificato senza password e mai applicato da solo. */
class AddressViewModelTest {

  @get:Rule
  val main = MainDispatcherRule()

  private val accounts = FakeNasAccountSettings()
  private val sessions = FakeNasSessionManager()
  private val registry = FakeRegistryService(status = TrustStatus.Approved(admin = true))

  private val tester = object : ConnectionTester {
    var report: ConnectionReport = ok()
    val targets = mutableListOf<TestTarget>()
    var credentialsSeen = 0
    var stagesSeen: Set<Stage> = emptySet()

    override suspend fun run(target: TestTarget, credentials: NasCredentials?, stages: Set<Stage>): ConnectionReport {
      targets += target
      stagesSeen = stages
      if (credentials != null) credentialsSeen++
      return report
    }
  }

  private fun ok() = ConnectionReport(
    stages = listOf(Stage.ROUTE, Stage.TCP, Stage.NEGOTIATE, Stage.GUID).map { StageResult(it, StageOutcome.PASSED, 1.milliseconds) },
    negotiation = null,
  )

  private fun failed(stage: Stage, error: NasError) = ConnectionReport(
    stages = listOf(StageResult(stage, StageOutcome.FAILED, 1.milliseconds, error)),
    negotiation = null,
  )

  private fun viewModel(account: FakeNasAccountSettings? = accounts) =
    AddressViewModel(Optional.ofNullable(account), tester, sessions, registry)

  private fun AddressViewModel.verifyAndConfirm(ip: String) {
    setTyped(ip)
    verify()
    askConfirm()
    confirm()
  }

  @Test
  fun verificaSenzaPasswordEUsaIlGuidMemorizzato() = runTest {
    val vm = viewModel()
    vm.setTyped("192.168.178.99")
    vm.verify()

    assertThat(tester.credentialsSeen).isEqualTo(0)
    assertThat(tester.stagesSeen).containsExactly(Stage.ROUTE, Stage.TCP, Stage.NEGOTIATE, Stage.GUID)
    assertThat(tester.targets.single().expectedGuid).isEqualTo("guid-del-nas")
    assertThat(tester.targets.single().user).isNull()
    assertThat(vm.state.value.phase).isEqualTo(AddressPhase.VERIFIED)
    // Verificato non vuol dire applicato.
    assertThat(accounts.addressUpdates).isEmpty()
  }

  @Test
  fun unIndirizzoNonNumericoNonSiVerificaNemmeno() = runTest {
    val vm = viewModel()
    vm.setTyped("nas.fritz.box")
    assertThat(vm.state.value.canVerify).isFalse()
    vm.verify()
    assertThat(tester.targets).isEmpty()
  }

  @Test
  fun seIlGuidEDiversoNonSiCambiaNienteENonSiOfreLaConferma() = runTest {
    tester.report = failed(Stage.GUID, NasError.IdentityMismatch)
    val vm = viewModel()
    vm.setTyped("192.168.178.99")
    vm.verify()

    assertThat(vm.state.value.phase).isEqualTo(AddressPhase.MISMATCH)
    vm.askConfirm()
    vm.confirm()
    assertThat(accounts.addressUpdates).isEmpty()
  }

  @Test
  fun unNasChiusoDaUnMessaggioEResta() = runTest {
    tester.report = failed(Stage.TCP, NasError.Timeout)
    val vm = viewModel()
    vm.setTyped("192.168.178.99")
    vm.verify()
    assertThat(vm.state.value.phase).isEqualTo(AddressPhase.FORM)
    assertThat(vm.state.value.feedback).isNotNull()
  }

  @Test
  fun lIndirizzoCambiaSoloDopoLaConfermaDelFoglio() = runTest {
    val vm = viewModel()
    vm.setTyped("192.168.178.99")
    vm.confirm()
    assertThat(accounts.addressUpdates).isEmpty()

    vm.verify()
    // Dal modulo verificato non si salta il foglio di conferma.
    vm.confirm()
    assertThat(accounts.addressUpdates).isEmpty()

    vm.askConfirm()
    assertThat(vm.state.value.phase).isEqualTo(AddressPhase.CONFIRM)
    vm.cancelConfirm()
    vm.confirm()
    assertThat(accounts.addressUpdates).isEmpty()

    vm.askConfirm()
    vm.confirm()
    assertThat(accounts.addressUpdates.single().ip).isEqualTo("192.168.178.99")
    assertThat(sessions.invalidations).containsExactly(InvalidateReason.USER_REQUEST)
    assertThat(vm.state.value.phase).isEqualTo(AddressPhase.DONE)
  }

  /**
   * RE-5: il NAS cambia IP. Su questo telefono l'indirizzo cambia subito; l'admin lo scrive anche nella configurazione **firmata** della
   * famiglia, da cui nasce l'indirizzo del QR d'invito (prima il QR nuovo portava ancora quello vecchio).
   */
  @Test
  fun l_adminScriveILIndirizzoNuovoAncheNellaConfigurazioneFirmata() = runTest {
    val vm = viewModel()
    assertThat(vm.state.value.admin).isTrue()

    vm.verifyAndConfirm("192.168.178.99")

    assertThat(accounts.addressUpdates.single().ip).isEqualTo("192.168.178.99")
    assertThat(registry.addressUpdates).containsExactly("192.168.178.99")
    assertThat(vm.state.value.saved).isEqualTo(SaveScope.FAMILY)
  }

  @Test
  fun chiNonEAdminCambiaSoloPerSeELaPaginaLoDice() = runTest {
    registry.status.value = TrustStatus.Approved(admin = false)
    registry.nextResult = AdminActionResult.NotAdmin
    val vm = viewModel()
    assertThat(vm.state.value.admin).isFalse()

    vm.verifyAndConfirm("192.168.178.99")

    assertThat(accounts.addressUpdates.single().ip).isEqualTo("192.168.178.99")
    assertThat(vm.state.value.phase).isEqualTo(AddressPhase.DONE)
    assertThat(vm.state.value.saved).isEqualTo(SaveScope.THIS_PHONE)
  }

  @Test
  fun seLaFirmaNonRiesceL_indirizzoCambiaComunque_eSiPuoRiprovare() = runTest {
    registry.nextResult = AdminActionResult.Failed(NasError.Timeout)
    val vm = viewModel()

    vm.verifyAndConfirm("192.168.178.99")

    assertThat(vm.state.value.currentIp).isEqualTo("192.168.178.99")
    assertThat(vm.state.value.saved).isEqualTo(SaveScope.FAMILY_FAILED)

    registry.nextResult = AdminActionResult.Done
    vm.retryShare()
    assertThat(vm.state.value.saved).isEqualTo(SaveScope.FAMILY)
    assertThat(registry.addressUpdates).containsExactly("192.168.178.99", "192.168.178.99")
  }

  @Test
  fun unNasDiversoNonFirmaNiente() = runTest {
    tester.report = failed(Stage.GUID, NasError.IdentityMismatch)
    val vm = viewModel()

    vm.verifyAndConfirm("192.168.178.99")

    assertThat(registry.addressUpdates).isEmpty()
    assertThat(accounts.addressUpdates).isEmpty()
  }

  @Test
  fun senzaAccountCollegatoNonSiFaNiente() = runTest {
    val vm = viewModel(account = null)
    vm.setTyped("192.168.178.99")
    vm.verify()
    assertThat(vm.state.value.available).isFalse()
    assertThat(tester.targets).isEmpty()
  }
}
