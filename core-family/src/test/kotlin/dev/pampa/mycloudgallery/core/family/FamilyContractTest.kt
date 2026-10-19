package dev.pampa.mycloudgallery.core.family

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.OnboardingPath
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProblem
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProgress
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.PreflightResult
import dev.pampa.mycloudgallery.core.family.contract.QrPayloads
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.core.family.contract.StepInput
import dev.pampa.mycloudgallery.core.family.contract.StepResult
import dev.pampa.mycloudgallery.core.family.contract.WipePhase
import dev.pampa.mycloudgallery.core.family.contract.WipeReason
import dev.pampa.mycloudgallery.core.family.noop.ClosedTrustGuard
import dev.pampa.mycloudgallery.core.family.noop.IdleOnboardingCoordinator
import dev.pampa.mycloudgallery.core.family.noop.NotConfiguredSetupRepository
import dev.pampa.mycloudgallery.core.family.testing.FakeOnboardingCoordinator
import dev.pampa.mycloudgallery.core.family.testing.OperationLog
import dev.pampa.mycloudgallery.core.family.testing.RecordingSelfWipe
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FamilyContractTest {

  @Test
  fun iPassiSonoQuattordiciInOrdineConQuelliSaltabiliDelProgetto() {
    assertThat(OnboardingStep.entries.map { it.number }).isEqualTo((1..14).toList())
    assertThat(OnboardingStep.entries.filter { it.skippable })
      .containsExactly(OnboardingStep.FOLDERS, OnboardingStep.BATTERY, OnboardingStep.VPN)
  }

  @Test
  fun iNullObjectLascianoIlDispositivoNonConfiguratoESenzaPermessi() = runTest {
    assertThat(NotConfiguredSetupRepository.state.value).isEqualTo(SetupState.NotConfigured)
    assertThat(ClosedTrustGuard.preflight(Purpose.BACKGROUND)).isInstanceOf(PreflightResult.Stop::class.java)
    assertThat(ClosedTrustGuard.writePermit()).isNull()

    val password = "segreta-lunga".toCharArray()
    val result = IdleOnboardingCoordinator.submit(StepInput.Credentials("mario", password))
    assertThat(result).isEqualTo(StepResult.Problem(OnboardingProblem.STEP_NOT_AVAILABLE))
    // Anche il null-object azzera la password che riceve.
    assertThat(password.all { it == '\u0000' }).isTrue()
  }

  @Test
  fun ilCoordinatoreFintoAvanzaSaltaERiprende() = runTest {
    val coordinator = FakeOnboardingCoordinator(OnboardingProgress.START)
    coordinator.submit(StepInput.ChoosePath(OnboardingPath.FOUND_FAMILY))
    assertThat(coordinator.progress.value.current).isEqualTo(OnboardingStep.NAS_ADDRESS)

    // Un passo non saltabile non si salta.
    assertThat(coordinator.skip(OnboardingStep.NAS_ADDRESS)).isEqualTo(StepResult.Problem(OnboardingProblem.STEP_NOT_AVAILABLE))

    // Come il vero: un input si accetta solo al suo passo.
    assertThat(coordinator.submit(StepInput.PermissionsReviewed)).isEqualTo(StepResult.Problem(OnboardingProblem.STEP_NOT_AVAILABLE))
    coordinator.submit(StepInput.NasAddressChosen(dev.pampa.mycloudgallery.core.network.contract.NasAddress("192.168.178.58", null)))
    coordinator.submit(StepInput.Credentials("mario", "x".repeat(12).toCharArray()))
    coordinator.submit(StepInput.Areas("NAS", "mario"))
    assertThat(coordinator.progress.value.current).isEqualTo(OnboardingStep.FOLDERS)
    coordinator.skip(OnboardingStep.FOLDERS)
    assertThat(coordinator.progress.value.pending).containsExactly(OnboardingStep.FOLDERS)
    assertThat(coordinator.resume(OnboardingStep.FOLDERS)).isEqualTo(StepResult.Advanced(OnboardingStep.FOLDERS))

    val credentials = StepInput.Credentials("mario", "x".repeat(12).toCharArray())
    assertThat(credentials.toString()).doesNotContain("x")
  }

  @Test
  fun lAzzeramentoFintoRegistraLOrdineERiprendeAMeta() = runTest {
    val log = OperationLog()
    val wipe = RecordingSelfWipe(log, stopAfter = WipePhase.DEVICE_KEY_DESTROYED)
    assertThat(wipe.run(WipeReason.MARKED_LOST)).isEqualTo(WipePhase.DEVICE_KEY_DESTROYED)
    wipe.stopAfter = null
    wipe.resumeIfInterrupted()
    assertThat(log.all()).isEqualTo(WipePhase.entries.map { "wipe:${it.name}" })
    // La chiave sparisce prima dei dati.
    assertThat(log.all().indexOf("wipe:DEVICE_KEY_DESTROYED")).isLessThan(log.all().indexOf("wipe:LOCAL_DATA_ERASED"))
  }

  @Test
  fun ilQrDiWireGuardSiRiconosce() {
    assertThat(QrPayloads.looksLikeWireGuardConfig("[Interface]\nPrivateKey = abc")).isTrue()
    assertThat(QrPayloads.looksLikeWireGuardConfig("MCG1:ABCDEF")).isFalse()
  }
}
