package dev.pampa.mycloudgallery.feature.settings.account

import com.google.common.truth.Truth.assertThat
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

  private fun viewModel(account: FakeNasAccountSettings? = accounts) = AddressViewModel(Optional.ofNullable(account), tester, sessions)

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

  @Test
  fun senzaAccountCollegatoNonSiFaNiente() = runTest {
    val vm = viewModel(account = null)
    vm.setTyped("192.168.178.99")
    vm.verify()
    assertThat(vm.state.value.available).isFalse()
    assertThat(tester.targets).isEmpty()
  }
}
