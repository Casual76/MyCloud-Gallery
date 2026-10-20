package dev.pampa.mycloudgallery.core.family.invite

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.JoinStep
import dev.pampa.mycloudgallery.core.family.contract.OnboardingPath
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProblem
import dev.pampa.mycloudgallery.core.family.contract.StepInput
import dev.pampa.mycloudgallery.core.family.contract.TrustSnapshot
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.family.support.OnboardingFixture
import dev.pampa.mycloudgallery.core.family.support.Reports
import dev.pampa.mycloudgallery.core.family.support.TEST_GUID
import dev.pampa.mycloudgallery.core.family.support.TEST_IP
import dev.pampa.mycloudgallery.core.family.support.TestFamily
import dev.pampa.mycloudgallery.core.family.support.expectAdvanced
import dev.pampa.mycloudgallery.core.nas.contract.Stage
import dev.pampa.mycloudgallery.core.trust.acceptedOrNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * R18 della revisione di sicurezza della Fase 5B (`docs/review/5b-fase5b.md`): il rinnovo della richiesta senza QR usa il GUID
 * gia' fissato la prima volta e non riporta a 0 la versione minima del registro (un'ancora di sicurezza: si alza e basta).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CorrezioniIngresso5BTest {

  private class Fixture(scope: TestScope) {
    val f = OnboardingFixture(scope)
    val env = f.env
    val family = TestFamily(env.nas.share("NAS"))
    val join = DefaultJoinFlow(f.store, env.ports())
    val coordinator = f.coordinator()
    val password: CharArray get() = "Album-del-Mare-1".toCharArray()

    val manual get() = ManualInvite.create(family.familyId, family.fingerprint, TEST_IP, "mamma", "NAS", "mamma", "Tablet di Mamma").acceptedOrNull()!!

    /** La prima richiesta senza QR, fatta e in attesa dell'admin. */
    suspend fun firstManualRequest(): JoinStep.AwaitingApproval {
      expectAdvanced(coordinator.submit(StepInput.ChoosePath(OnboardingPath.JOIN_MANUAL)))
      expectAdvanced(coordinator.submit(StepInput.ConfirmHome(true)))
      assertThat(join.start(manual)).isEqualTo(JoinStep.NeedsPassword)
      assertThat(join.authenticate(password)).isInstanceOf(JoinStep.NeedsName::class.java)
      val waiting = join.register("Tablet di Mamma")
      assertThat(waiting).isInstanceOf(JoinStep.AwaitingApproval::class.java)
      env.registry.status.value = TrustStatus.Pending((waiting as JoinStep.AwaitingApproval).code)
      return waiting
    }
  }

  @Test
  fun r18_ilRinnovoSenzaQrUsaIlGuidGiaFissatoEPrimaDellaPassword() = runTest(UnconfinedTestDispatcher()) {
    val j = Fixture(this)
    j.firstManualRequest()
    assertThat(j.f.store.snapshot.nas!!.guid).isEqualTo(TEST_GUID)

    // Il rinnovo: l'indirizzo e' ridigitato, il GUID no. Prima partiva senza GUID atteso.
    j.env.tester.runs.clear()
    assertThat(j.join.start(j.manual)).isEqualTo(JoinStep.NeedsPassword)
    val run = j.env.tester.runs.single()
    assertThat(run.hadCredentials).isFalse()
    assertThat(run.target.expectedGuid).isEqualTo(TEST_GUID)
  }

  @Test
  fun r18_unNasConUnAltroGuidNelRinnovoNonVedeMaiLaPassword() = runTest(UnconfinedTestDispatcher()) {
    val j = Fixture(this)
    j.firstManualRequest()

    // Un impostore in LAN risponde all'IP digitato con un GUID che non e' quello della famiglia.
    j.env.tester.handler = { _, _, stages -> Reports.ok(stages, Reports.FACTS.copy(serverGuid = "99999999-0000-0000-0000-000000000000")) }
    j.env.tester.runs.clear()
    assertThat(j.join.start(j.manual)).isEqualTo(JoinStep.Failed(OnboardingProblem.IDENTITY_MISMATCH))

    // Nessuna password e' partita (nessuna prova con le credenziali), nessun account nuovo salvato.
    assertThat(j.env.tester.runs.none { it.hadCredentials || Stage.AUTHENTICATE in it.stages }).isTrue()
    assertThat(j.join.authenticate(j.password)).isEqualTo(JoinStep.Failed(OnboardingProblem.STEP_NOT_AVAILABLE))
    assertThat(j.env.credentials.saved.size).isEqualTo(1) // solo quella della prima richiesta
  }

  @Test
  fun r18_unInvitoConUnGuidDiversoDaQuelloFissatoNonParteNemmenoSullaRete() = runTest(UnconfinedTestDispatcher()) {
    val j = Fixture(this)
    // La prima richiesta con il QR: il GUID del NAS e' quello dell'invito.
    expectAdvanced(j.coordinator.submit(StepInput.ChoosePath(OnboardingPath.JOIN_QR)))
    val invite = j.family.invite()
    assertThat(j.join.start(invite)).isEqualTo(JoinStep.NeedsPassword)
    assertThat(j.join.authenticate(j.password)).isInstanceOf(JoinStep.NeedsName::class.java)
    val waiting = j.join.register("Tablet di Mamma") as JoinStep.AwaitingApproval
    j.env.registry.status.value = TrustStatus.Pending(waiting.code)
    j.env.tester.runs.clear()

    // Il rinnovo con un invito che porta un altro GUID: il NAS e' quello fissato, non si prova nemmeno.
    val other = j.family.invite(guid = "99999999-0000-0000-0000-000000000000")
    assertThat(j.join.start(other)).isEqualTo(JoinStep.Failed(OnboardingProblem.IDENTITY_MISMATCH))
    assertThat(j.env.tester.runs).isEmpty()
  }

  @Test
  fun r18_ilRinnovoNonRiportaA0LaVersioneMinima() = runTest(UnconfinedTestDispatcher()) {
    val j = Fixture(this)
    j.firstManualRequest()
    // L'ingresso senza QR parte da 0; il registro accettato la prima volta era la versione 1 (lo stato fidato).
    assertThat(j.f.store.snapshot.join!!.minRegistryVersion).isEqualTo(0L)
    val trusted = j.env.trustState.snapshot!!.minRegistryVersion
    assertThat(trusted).isAtLeast(1L)

    assertThat(j.join.start(j.manual)).isEqualTo(JoinStep.NeedsPassword)
    // Prima del rinnovo il record tornava a 0 (invito senza QR): adesso si alza al piu' alto dei due.
    assertThat(j.f.store.snapshot.join!!.minRegistryVersion).isEqualTo(trusted)
    assertThat(j.join.authenticate(j.password)).isInstanceOf(JoinStep.NeedsName::class.java)
    assertThat(j.join.register("Tablet di Mamma")).isInstanceOf(JoinStep.AwaitingApproval::class.java)
    assertThat(j.f.store.snapshot.join!!.minRegistryVersion).isEqualTo(trusted)
  }

  @Test
  fun r18_nelRinnovoUnRegistroPiuVecchioDiQuelloGiaAccettatoEUnRollback() = runTest(UnconfinedTestDispatcher()) {
    val j = Fixture(this)
    j.firstManualRequest()
    // Questo dispositivo aveva accettato un registro piu' recente (versione 7); sul NAS ora ce n'e' uno piu' vecchio.
    val saved = j.env.trustState.snapshot!!
    j.env.trustState.snapshot = TrustSnapshot(saved.registryBytes, saved.signerFingerprint, saved.familyId, saved.familyKeyFingerprint, 7L, emptyMap())

    assertThat(j.join.start(j.manual)).isEqualTo(JoinStep.NeedsPassword)
    // Con la versione minima 0 di prima il registro vecchio veniva accettato; con 7 e' un rollback e non porta a nulla.
    assertThat(j.join.authenticate(j.password)).isEqualTo(JoinStep.Failed(OnboardingProblem.REGISTRY_UNTRUSTED))
  }

  @Test
  fun r18_unaPrimaRichiestaSenzaQrRestaComeSempreAVersioneMinimaZero() = runTest(UnconfinedTestDispatcher()) {
    val j = Fixture(this)
    j.firstManualRequest()
    // L'ancora debole della prima richiesta senza QR e' il limite noto (L22): non e' cambiato.
    assertThat(j.f.store.snapshot.join!!.minRegistryVersion).isEqualTo(0L)
    assertThat(j.f.store.snapshot.join!!.manual).isTrue()
  }
}
