package dev.pampa.mycloudgallery.core.family.invite

import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.family.contract.FamilyInvite
import dev.pampa.mycloudgallery.core.family.contract.JoinStep
import dev.pampa.mycloudgallery.core.family.contract.OnboardingPath
import dev.pampa.mycloudgallery.core.family.contract.StepInput
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.family.support.NAS_NOON
import dev.pampa.mycloudgallery.core.family.support.OnboardingFixture
import dev.pampa.mycloudgallery.core.family.support.TEST_IP
import dev.pampa.mycloudgallery.core.family.support.TestFamily
import dev.pampa.mycloudgallery.core.family.testing.FakeJoinFlow
import dev.pampa.mycloudgallery.core.family.testing.FakeOnboardingCoordinator
import dev.pampa.mycloudgallery.core.trust.acceptedOrNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * L'ingresso finto e quello vero rispondono allo stesso modo a [JoinFlow.start][dev.pampa.mycloudgallery.core.family.contract.JoinFlow.start]
 * per tutto cio' che si decide prima della rete: invito malformato, percorso sbagliato, casa non confermata, invito
 * scaduto, rinnovo di chi non e' in attesa. Il finto accettava tutto e nascondeva ON-1 della verifica funzionale.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeJoinFlowParityTest {

  private class Both(scope: TestScope) {
    val f = OnboardingFixture(scope)
    val family = TestFamily(f.env.nas.share("NAS"))
    val real = DefaultJoinFlow(f.store, f.env.ports())
    val realCoordinator = f.coordinator()
    val coordinator = FakeOnboardingCoordinator().apply { candidate = f.env.candidate }
    val fake = FakeJoinFlow(JoinStep.NeedsPassword).also {
      it.coordinator = coordinator
      it.nasNow = NAS_NOON
      it.status = { f.env.registry.status.value }
    }

    suspend fun choose(path: OnboardingPath) {
      realCoordinator.submit(StepInput.ChoosePath(path))
      coordinator.submit(StepInput.ChoosePath(path))
    }

    suspend fun confirmHome() {
      realCoordinator.submit(StepInput.ConfirmHome(true))
      coordinator.submit(StepInput.ConfirmHome(true))
    }

    suspend fun same(what: String, invite: FamilyInvite) {
      val expected = real.start(invite)
      val actual = fake.start(invite)
      // Dove il vero va avanti (chiede la password), il finto da' il suo passo in coda: qui conta che nessuno dei due si fermi.
      val kind = { step: JoinStep -> if (step is JoinStep.Failed) step.problem else "avanti" }
      assertWithMessage(what).that(kind(actual)).isEqualTo(kind(expected))
    }

    fun manual(common: String = "NAS", private: String = "mamma"): FamilyInvite = FamilyInvite(
      familyId = family.familyId,
      familyKeyFingerprint = family.fingerprint,
      minRegistryVersion = 0L,
      nasIp = TEST_IP,
      nasName = null,
      nasGuid = "",
      homeUla = emptyList(),
      commonShare = common,
      privateShare = private,
      nasUser = "mamma",
      deviceName = "Tablet",
      presetName = null,
      expiresAtNas = ManualInvite.NO_EXPIRY,
    )
  }

  @Test
  fun senzaQrInvitoPercorsoECasaSiGiudicanoAlloStessoModo() = runTest(UnconfinedTestDispatcher()) {
    val b = Both(this)
    b.choose(OnboardingPath.JOIN_MANUAL)
    // ON-1: l'invito senza aree (com'era quello della pagina) non e' un invito.
    b.same("senza aree", b.manual(common = "", private = ""))
    // Un invito con QR sul percorso senza QR non e' quello scelto.
    b.same("percorso sbagliato", b.family.invite())
    // Senza aver confermato "e' casa?" non si va.
    b.same("casa non confermata", b.manual())
    b.confirmHome()
    b.same("tutto a posto", b.manual())
    // L'invito costruito come lo costruisce la pagina adesso (ManualInvite) passa anche dal finto.
    val built = ManualInvite.create(b.family.familyId.uppercase(), b.family.fingerprint.uppercase(), TEST_IP, "mamma", "NAS", "mamma", "Tablet").acceptedOrNull()!!
    b.same("invito costruito", built)
  }

  @Test
  fun conIlQrScadenzaEPercorsoSiGiudicanoAlloStessoModo() = runTest(UnconfinedTestDispatcher()) {
    val b = Both(this)
    b.choose(OnboardingPath.JOIN_QR)
    b.same("invito scaduto", b.family.invite(expiresAt = NAS_NOON - 1))
    b.same("invito senza QR sul percorso con QR", b.manual())
    b.same("invito buono", b.family.invite())
  }

  @Test
  fun ilRinnovoSoloPerChiEInAttesa() = runTest(UnconfinedTestDispatcher()) {
    val b = Both(this)
    b.choose(OnboardingPath.JOIN_QR)
    // La richiesta e' sul NAS (il vero lo segna con register, il finto con il suo passo riuscito).
    b.real.start(b.family.invite())
    b.real.authenticate("Album-del-Mare-1".toCharArray())
    b.real.register("Tablet")
    b.coordinator.markJoinRegistered()
    b.f.env.registry.status.value = TrustStatus.Approved(admin = false)
    b.same("gia' approvato", b.family.invite())
    b.f.env.registry.status.value = TrustStatus.Pending("CODICE")
    b.same("in attesa: si rifa' la richiesta", b.family.invite())
  }
}
