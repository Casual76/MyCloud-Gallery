package dev.pampa.mycloudgallery.feature.onboarding

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.OnboardingPath
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProblem
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProgress
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.family.testing.FakeOnboardingCoordinator
import dev.pampa.mycloudgallery.feature.onboarding.model.OnboardingDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * R7 della revisione di sicurezza della Fase 5B (`docs/review/5b-fase5b.md`): "Configura di nuovo" dalla pagina "fuori dalla
 * famiglia" non parte con un tocco solo. Il ViewModel con il coordinatore finto allineato al vero.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CorrezioniConfigurazione5BViewModelTest {

  @Before
  fun setUp() {
    Dispatchers.setMain(UnconfinedTestDispatcher())
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun destination(vm: OnboardingViewModel) = vm.destination(vm.progress.value, vm.ui.value)

  private fun finished(path: OnboardingPath): OnboardingProgress {
    val plan = FakeOnboardingCoordinator.planFor(path)
    return OnboardingProgress.START.copy(path = path, plan = plan, current = OnboardingStep.SUMMARY, completed = plan.toSet())
  }

  private fun removedEnv(path: OnboardingPath = OnboardingPath.JOIN_QR, status: TrustStatus = TrustStatus.Revoked): TestEnv {
    val env = TestEnv(finished(path))
    env.coordinator.finished = true
    env.registry.status.value = status
    return env
  }

  @Test
  fun r7_ilTastoApreLaConfermaENonToccaNiente() = runTest {
    val env = removedEnv()
    val vm = env.viewModel()
    vm.enterAtRoot()
    assertThat(destination(vm)).isEqualTo(OnboardingDestination.Removed)

    vm.askStartOver()

    // Prima il tasto chiamava subito il coordinatore: chiavi e password sparivano con un tocco solo.
    assertThat(vm.ui.value.removal?.confirming).isTrue()
    assertThat(env.coordinator.startOvers).isEqualTo(0)
    assertThat(destination(vm)).isEqualTo(OnboardingDestination.Removed)
  }

  @Test
  fun r7_annullareOChiudereIlFoglioNonConfermaNiente() = runTest {
    val env = removedEnv()
    val vm = env.viewModel()
    vm.enterAtRoot()
    vm.askStartOver()

    vm.cancelStartOver()

    assertThat(vm.ui.value.removal?.confirming).isFalse()
    assertThat(env.coordinator.startOvers).isEqualTo(0)
    // E senza il foglio aperto, la conferma da sola non fa niente (un tocco fuori posto, un doppio tocco, un test).
    vm.startOver()
    assertThat(env.coordinator.startOvers).isEqualTo(0)
    assertThat(destination(vm)).isEqualTo(OnboardingDestination.Removed)
  }

  @Test
  fun r7_laConfermaDelFoglioConfiguraDiNuovo() = runTest {
    val env = removedEnv()
    val vm = env.viewModel()
    vm.enterAtRoot()
    vm.askStartOver()

    vm.startOver()

    assertThat(env.coordinator.startOvers).isEqualTo(1)
    assertThat(destination(vm)).isEqualTo(OnboardingDestination.Step(OnboardingStep.WELCOME))
  }

  @Test
  fun r7_unAdminVedeLAvvisoDellaChiaveUnFamiliareNo() = runTest {
    val admin = removedEnv(OnboardingPath.FOUND_FAMILY, TrustStatus.NotConfigured)
    admin.coordinator.founded = true
    val vmAdmin = admin.viewModel()
    vmAdmin.enterAtRoot()
    vmAdmin.askStartOver()
    assertThat(vmAdmin.ui.value.removal?.holdsKey).isTrue()

    val member = removedEnv(OnboardingPath.JOIN_QR)
    val vmMember = member.viewModel()
    vmMember.enterAtRoot()
    vmMember.askStartOver()
    assertThat(vmMember.ui.value.removal?.holdsKey).isFalse()
  }

  @Test
  fun r7_unProblemaDelleChiaviSiVedeEIlRiprovaChiedeDiNuovoLaConferma() = runTest {
    val env = removedEnv(OnboardingPath.FOUND_FAMILY, TrustStatus.NotConfigured)
    env.coordinator.founded = true
    env.coordinator.keysStuck = true
    val vm = env.viewModel()
    vm.enterAtRoot()
    vm.askStartOver()

    vm.startOver()

    assertThat(vm.ui.value.removal?.problem).isEqualTo(OnboardingProblem.KEYS_NOT_REMOVED)
    assertThat(vm.ui.value.removal?.busy).isFalse()
    assertThat(vm.ui.value.removal?.confirming).isFalse()
    assertThat(destination(vm)).isEqualTo(OnboardingDestination.Removed)

    // "Riprova" riapre il foglio: le chiavi non si toccano senza un gesto confermato di nuovo.
    vm.askStartOver()
    assertThat(vm.ui.value.removal?.confirming).isTrue()
    assertThat(vm.ui.value.removal?.problem).isNull()
    env.coordinator.keysStuck = false
    vm.startOver()
    assertThat(destination(vm)).isEqualTo(OnboardingDestination.Step(OnboardingStep.WELCOME))
  }

  /**
   * R7, il residuo: con il Keystore che non risponde lo stato e' `KeystoreUnavailable`, non "non configurato". La pagina della
   * radice in onboarding non diventa "fuori dalla famiglia" (la radice mostra la pagina con "Riprova"), e il tasto "Configura di
   * nuovo" di questa pagina non c'e' e non fa niente.
   */
  @Test
  fun r7_conIlKeystoreCheNonRispondeNessunaPaginaFuoriDallaFamiglia() = runTest {
    val env = removedEnv(OnboardingPath.FOUND_FAMILY, TrustStatus.KeystoreUnavailable)
    env.coordinator.founded = true
    val vm = env.viewModel()
    vm.enterAtRoot()

    assertThat(vm.ui.value.removal).isNull()
    assertThat(destination(vm)).isNotEqualTo(OnboardingDestination.Removed)
    vm.askStartOver()
    vm.startOver()
    assertThat(env.coordinator.startOvers).isEqualTo(0)

    // Se lo stato passa a "non configurato" (per esempio lo stato salvato tolto davvero), la pagina di sempre torna.
    env.registry.status.value = TrustStatus.NotConfigured
    assertThat(destination(vm)).isEqualTo(OnboardingDestination.Removed)
  }
}
