package dev.pampa.mycloudgallery.core.family.onboarding

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.OnboardingPath
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProblem
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProgress
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.core.family.contract.StepInput
import dev.pampa.mycloudgallery.core.family.contract.StepResult
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.family.contract.WipePhase
import dev.pampa.mycloudgallery.core.family.setup.NasCredentialSlots
import dev.pampa.mycloudgallery.core.family.setup.SetupResolver
import dev.pampa.mycloudgallery.core.family.support.OnboardingFixture
import dev.pampa.mycloudgallery.core.family.support.Reports
import dev.pampa.mycloudgallery.core.family.support.TEST_GUID
import dev.pampa.mycloudgallery.core.family.support.credentials
import dev.pampa.mycloudgallery.core.family.support.doStep
import dev.pampa.mycloudgallery.core.family.support.expectAdvanced
import dev.pampa.mycloudgallery.core.family.support.runFounderUntil
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.Stage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Le modifiche ai contratti di `core-family` fatte dal P9 (integrazione), ognuna con il suo caso:
 *  - "Continua piu' tardi" (E-O1) anche dopo il passo 2, e senza rimbalzare da un passo del NAS all'altro quando lo si
 *    riprende dal riepilogo con il NAS ancora spento;
 *  - l'interruttore di sicurezza dell'account **digitato** (non di quello salvato) si riapre per un tentativo quando
 *    l'utente scrive una password nuova (`OnboardingPorts.allowUserRetry`);
 *  - un azzeramento finito con il motore che dice ancora "perso" e' `Wiped`, non "azzeramento in corso".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class P9ContractFixesTest {

  private fun problem(p: OnboardingProblem) = StepResult.Problem(p)

  @Test
  fun ilNasSpentoAlPasso3SiRimandaComeAlPasso2_E_O1() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.coordinator()
    f.runFounderUntil(c, OnboardingStep.ACCOUNT)
    f.env.tester.handler = { _, _, stages -> Reports.failed(stages, Stage.TCP, NasError.Timeout) }
    assertThat(c.submit(credentials())).isEqualTo(problem(OnboardingProblem.NAS_NOT_FOUND))
    assertThat(f.store.snapshot.deferrable).isTrue()

    val later = expectAdvanced(c.skip(OnboardingStep.ACCOUNT))
    assertThat(later.next).isEqualTo(OnboardingStep.BACKUP_SOURCES)
    // Il passo 2 era fatto: restano solo quelli dopo, che hanno bisogno del NAS.
    assertThat(f.store.snapshot.progress.pending).containsExactly(
      OnboardingStep.ACCOUNT, OnboardingStep.AREAS, OnboardingStep.FOLDERS, OnboardingStep.FAMILY_KEY,
    ).inOrder()
  }

  @Test
  fun riprendendoDalRiepilogoConIlNasAncoraSpentoSiTornaAlRiepilogo() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.coordinator()
    f.runFounderUntil(c, OnboardingStep.NAS_ADDRESS)
    expectAdvanced(c.submit(StepInput.ConfirmHome(true)))
    f.env.tester.handler = { _, _, stages -> Reports.failed(stages, Stage.TCP, NasError.Timeout) }
    assertThat(c.submit(StepInput.NasAddressChosen(f.env.address))).isEqualTo(problem(OnboardingProblem.NAS_NOT_FOUND))
    expectAdvanced(c.skip(OnboardingStep.NAS_ADDRESS))
    f.runFounderUntil(c, OnboardingStep.SUMMARY)

    // Dal riepilogo si riprende il passo 2, ma il NAS e' ancora spento: "piu' tardi" di nuovo riporta al riepilogo,
    // non al passo 3 (che senza NAS fallirebbe), poi al 4...
    expectAdvanced(c.resume(OnboardingStep.NAS_ADDRESS))
    expectAdvanced(c.submit(StepInput.ConfirmHome(true)))
    assertThat(c.submit(StepInput.NasAddressChosen(f.env.address))).isEqualTo(problem(OnboardingProblem.NAS_NOT_FOUND))
    val again = expectAdvanced(c.skip(OnboardingStep.NAS_ADDRESS))
    assertThat(again.next).isNull()
    assertThat(f.current).isEqualTo(OnboardingStep.SUMMARY)
    assertThat(f.store.snapshot.progress.pending).containsExactly(
      OnboardingStep.NAS_ADDRESS, OnboardingStep.ACCOUNT, OnboardingStep.AREAS, OnboardingStep.FOLDERS, OnboardingStep.FAMILY_KEY,
    ).inOrder()

    // Quando il NAS torna, i passi si fanno in fila e alla fine si puo' finire.
    f.env.tester.handler = { _, _, stages -> Reports.ok(stages) }
    expectAdvanced(c.resume(OnboardingStep.NAS_ADDRESS))
    while (f.current != OnboardingStep.SUMMARY) expectAdvanced(f.doStep(c))
    assertThat(f.store.snapshot.progress.pending).isEmpty()
    expectAdvanced(c.submit(StepInput.Finish))
  }

  @Test
  fun unaPasswordNuovaRiapreLInterruttoreDellAccountDigitato() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val retried = mutableListOf<NasAccount>()
    f.env.allowUserRetry = { account -> retried += account }
    val c = f.coordinator()
    f.runFounderUntil(c, OnboardingStep.ACCOUNT)
    expectAdvanced(c.submit(credentials(user = "alessio")))
    // L'account e' quello digitato, sul GUID memorizzato al passo 2: la stessa chiave dell'interruttore del test.
    val account = retried.single()
    assertThat(account.user).isEqualTo("alessio")
    assertThat(account.pinnedGuid).isEqualTo(TEST_GUID)
    assertThat(account.credentialSlot).isEqualTo(NasCredentialSlots.PRIMARY)
  }

  @Test
  fun unAzzeramentoFinitoConIlMotoreCheDiceAncoraPersoEAzzerato() {
    val empty = StoreState.Ready(OnboardingSnapshot.EMPTY)
    assertThat(SetupResolver.resolve(empty, TrustStatus.Lost, WipePhase.DONE, 42L)).isEqualTo(SetupState.Wiped(42L))
    // A meta' resta "in corso".
    assertThat(SetupResolver.resolve(empty, TrustStatus.Lost, WipePhase.CREDENTIALS_WIPED, 0L))
      .isInstanceOf(SetupState.Blocked::class.java)
    assertThat(SetupResolver.resolve(empty, TrustStatus.Lost, null, 0L)).isInstanceOf(SetupState.Blocked::class.java)
    // Chi riparte dopo l'azzeramento ha scelto un percorso nuovo: onboarding.
    val restarting = StoreState.Ready(
      OnboardingSnapshot.EMPTY.copy(
        progress = OnboardingProgress.START.copy(path = OnboardingPath.JOIN_QR, current = OnboardingStep.NAS_ADDRESS),
      ),
    )
    assertThat(SetupResolver.resolve(restarting, TrustStatus.Lost, WipePhase.DONE, 0L)).isInstanceOf(SetupState.Onboarding::class.java)
  }
}
