package dev.pampa.mycloudgallery.feature.settings.family

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProblem
import dev.pampa.mycloudgallery.core.family.contract.ResetOutcome
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.family.testing.FakeFamilyReset
import dev.pampa.mycloudgallery.core.family.testing.FakeRegistryService
import dev.pampa.mycloudgallery.feature.settings.support.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class FamilyResetViewModelTest {

  @get:Rule
  val main = MainDispatcherRule()

  private fun viewModel(admin: Boolean = true, reset: FakeFamilyReset = FakeFamilyReset()) =
    FamilyResetViewModel(FakeRegistryService(TrustStatus.Approved(admin)), reset)

  @Test
  fun ilResetVuoleDueConfermeELaParolaGiusta() = runTest {
    val reset = FakeFamilyReset()
    val vm = viewModel(reset = reset)

    vm.confirm()
    assertThat(reset.attempts).isEqualTo(0)

    vm.ask()
    assertThat(vm.state.value.stage).isEqualTo(ResetStage.FIRST_CONFIRM)
    // Dal primo foglio non si arriva alla scrittura.
    vm.confirm()
    assertThat(reset.attempts).isEqualTo(0)

    vm.continueToWord()
    assertThat(vm.state.value.stage).isEqualTo(ResetStage.TYPE_WORD)
    vm.setTyped("reimposto")
    vm.confirm()
    assertThat(reset.attempts).isEqualTo(0)

    vm.setTyped("  reimposta ")
    assertThat(vm.state.value.wordMatches).isTrue()
    vm.confirm()
    assertThat(reset.attempts).isEqualTo(1)
    assertThat(vm.state.value.stage).isEqualTo(ResetStage.DONE)
  }

  @Test
  fun annullareTornaAllIntroENonScriveNiente() = runTest {
    val reset = FakeFamilyReset()
    val vm = viewModel(reset = reset)
    vm.ask()
    vm.cancel()
    assertThat(vm.state.value.stage).isEqualTo(ResetStage.INTRO)
    vm.ask()
    vm.continueToWord()
    vm.setTyped("REIMPOSTA")
    vm.cancel()
    assertThat(vm.state.value.stage).isEqualTo(ResetStage.INTRO)
    assertThat(vm.state.value.typed).isEmpty()
    assertThat(reset.attempts).isEqualTo(0)
  }

  @Test
  fun soloLAdminPuoIniziare() = runTest {
    val vm = viewModel(admin = false)
    vm.ask()
    assertThat(vm.state.value.stage).isEqualTo(ResetStage.INTRO)
    assertThat(vm.state.value.isAdmin).isFalse()
  }

  @Test
  fun seIlResetFallisceSiResta_conIlMotivo_ESiPuoRiprovare() = runTest {
    val reset = FakeFamilyReset(ResetOutcome.Failed(OnboardingProblem.NAS_WRITE_FAILED))
    val vm = viewModel(reset = reset)
    vm.ask()
    vm.continueToWord()
    vm.setTyped("REIMPOSTA")
    vm.confirm()

    assertThat(vm.state.value.stage).isEqualTo(ResetStage.TYPE_WORD)
    assertThat(vm.state.value.feedback).isNotNull()
    reset.outcome = ResetOutcome.Done("famiglia-nuova")
    vm.confirm()
    assertThat(vm.state.value.stage).isEqualTo(ResetStage.DONE)
    assertThat(reset.attempts).isEqualTo(2)
  }
}
