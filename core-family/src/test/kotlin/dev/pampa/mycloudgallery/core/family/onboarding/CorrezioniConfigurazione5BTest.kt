package dev.pampa.mycloudgallery.core.family.onboarding

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.JoinStep
import dev.pampa.mycloudgallery.core.family.contract.OnboardingPath
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProblem
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.StepInput
import dev.pampa.mycloudgallery.core.family.contract.StepResult
import dev.pampa.mycloudgallery.core.family.contract.TrustSnapshot
import dev.pampa.mycloudgallery.core.family.invite.DefaultJoinFlow
import dev.pampa.mycloudgallery.core.family.setup.NasCredentialSlots
import dev.pampa.mycloudgallery.core.family.support.OnboardingFixture
import dev.pampa.mycloudgallery.core.family.support.Reports
import dev.pampa.mycloudgallery.core.family.support.TEST_GUID
import dev.pampa.mycloudgallery.core.family.support.TestFamily
import dev.pampa.mycloudgallery.core.family.support.doStep
import dev.pampa.mycloudgallery.core.family.support.expectAdvanced
import dev.pampa.mycloudgallery.core.family.support.runFounderUntil
import dev.pampa.mycloudgallery.core.network.contract.HomeFingerprint
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Le correzioni della revisione di sicurezza della Fase 5B su "Configura di nuovo" (`docs/review/5b-fase5b.md`, R6, R7, R19),
 * lato coordinatore vero: ogni caso era rosso sul codice di prima.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CorrezioniConfigurazione5BTest {

  private fun problem(p: OnboardingProblem) = StepResult.Problem(p)

  /** Il fondatore fino alla fine dell'onboarding ("Vai alla Libreria" fatto). */
  private suspend fun OnboardingFixture.founderFinished(c: DefaultOnboardingCoordinator) {
    runFounderUntil(c, OnboardingStep.SUMMARY)
    expectAdvanced(c.submit(StepInput.Finish))
    assertThat(store.snapshot.finished).isTrue()
    env.trustState.save(TrustSnapshot(ByteArray(8), "x".repeat(26), TrustIds.random(), "x".repeat(26), 1L, emptyMap()))
    env.deviceKeys.deviceSigner()
  }

  /**
   * Un familiare entra con il QR dell'admin, con l'account [user] sul NAS di [guid], e arriva al riepilogo (la richiesta e' sul NAS:
   * "Vai alla Libreria" non aspetta l'approvazione). E' l'ingresso vero: dopo "Configura di nuovo" chi rientra non fonda una
   * famiglia, entra in quella che c'e'.
   */
  private suspend fun OnboardingFixture.memberUntilSummary(
    c: DefaultOnboardingCoordinator,
    family: TestFamily,
    user: String,
    guid: String = TEST_GUID,
  ) {
    val join = DefaultJoinFlow(store, env.ports())
    env.tester.handler = { _, _, stages -> Reports.ok(stages, Reports.FACTS.copy(serverGuid = guid)) }
    expectAdvanced(c.submit(StepInput.ChoosePath(OnboardingPath.JOIN_QR)))
    assertThat(join.start(family.invite(user = user, guid = guid))).isEqualTo(JoinStep.NeedsPassword)
    assertThat(join.authenticate("Album-del-Mare-1".toCharArray())).isInstanceOf(JoinStep.NeedsName::class.java)
    assertThat(join.register("Tablet di $user")).isInstanceOf(JoinStep.AwaitingApproval::class.java)
    var guard = 0
    while (current != OnboardingStep.SUMMARY) {
      expectAdvanced(doStep(c))
      check(++guard < 40) { "il percorso non arriva al riepilogo" }
    }
  }

  /** Come [memberUntilSummary], poi "Vai alla Libreria"; lo stato fidato e le chiavi ci sono, come dopo un ingresso vero. */
  private suspend fun OnboardingFixture.memberFinished(c: DefaultOnboardingCoordinator, family: TestFamily, user: String) {
    memberUntilSummary(c, family, user)
    expectAdvanced(c.submit(StepInput.Finish))
    assertThat(store.snapshot.finished).isTrue()
    assertThat(env.deviceKeys.hasDeviceKey()).isTrue()
  }

  // --- R19: le chiavi non si fingono tolte ----------------------------------------------------------------------

  @Test
  fun r19_unaChiaveCheNonSiToglieNonFaProseguireEDiceProblema() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.coordinator()
    f.founderFinished(c)
    val env = f.env
    env.familyKeys.destroyFailures = 100

    // Prima: runCatching ignorava il guasto, la chiave di famiglia restava e "Configura di nuovo" diceva "fatto".
    assertThat(c.startOver()).isEqualTo(problem(OnboardingProblem.KEYS_NOT_REMOVED))

    // Si e' provato piu' volte, e dopo il fallimento niente altro si e' toccato: la pagina di prima, da cui si riprova.
    assertThat(env.familyKeys.destroyAttempts).isGreaterThan(1)
    assertThat(env.familyKeys.stored).isNotNull()
    assertThat(f.store.snapshot.finished).isTrue()
    assertThat(f.store.snapshot.nasUser).isEqualTo("alessio")
    assertThat(env.accounts.current()).isNotNull()
    assertThat(env.credentials.removed).isEmpty()
    assertThat(env.trustState.snapshot).isNotNull()
    assertThat(env.restarts).isEqualTo(0)

    // E il tentativo dopo, a Keystore guarito, va in fondo.
    env.familyKeys.destroyFailures = 0
    expectAdvanced(c.startOver())
    assertThat(env.familyKeys.stored).isNull()
    assertThat(f.store.snapshot.finished).isFalse()
    assertThat(env.accounts.current()).isNull()
  }

  @Test
  fun r19_unGuastoPasseggeroSiRipeteEBastaUnTentativoDiPiu() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.coordinator()
    f.founderFinished(c)
    val env = f.env
    env.deviceKeys.destroyFailures = 1
    env.familyKeys.destroyFailures = 1

    expectAdvanced(c.startOver())

    assertThat(env.deviceKeys.hasDeviceKey()).isFalse()
    assertThat(env.familyKeys.stored).isNull()
    assertThat(env.familyKeys.destroyAttempts).isEqualTo(2)
    assertThat(env.restarts).isEqualTo(1)
  }

  // --- R7: un Keystore che non risponde non e' "mai configurato" ---------------------------------------------------

  @Test
  fun r7_conIlKeystoreCheNonRispondeLaChiaveDiFamigliaNonSiDistrugge() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.coordinator()
    f.founderFinished(c)
    val env = f.env
    env.deviceKeys.broken = true

    assertThat(c.startOver()).isEqualTo(problem(OnboardingProblem.KEYS_NOT_REMOVED))

    assertThat(env.familyKeys.stored).isNotNull()
    assertThat(env.familyKeys.destroyAttempts).isEqualTo(0)
    assertThat(f.store.snapshot.finished).isTrue()
    assertThat(env.accounts.current()).isNotNull()
    assertThat(env.trustState.snapshot).isNotNull()
    assertThat(env.credentials.removed).isEmpty()
  }

  @Test
  fun r7_ilFondatoreEUnAdminRecuperatoTengonoLaChiaveDiFamigliaUnFamiliareNo() = runTest(UnconfinedTestDispatcher()) {
    val founder = OnboardingFixture(this)
    val c = founder.coordinator()
    founder.founderFinished(c)
    assertThat(c.holdsFamilyKey()).isTrue()

    val recovered = OnboardingFixture(this)
    val r = recovered.coordinator()
    expectAdvanced(r.submit(StepInput.ChoosePath(OnboardingPath.RECOVER_ADMIN)))
    assertThat(r.holdsFamilyKey()).isTrue()

    val member = OnboardingFixture(this)
    val m = member.coordinator()
    expectAdvanced(m.submit(StepInput.ChoosePath(OnboardingPath.JOIN_QR)))
    assertThat(m.holdsFamilyKey()).isFalse()
  }

  // --- R6: i dati del vecchio account non passano al nuovo -----------------------------------------------------------------

  @Test
  fun r6_rientrareConUnAltroAccountTogliIDatiDiPrimaPrimaDiFinire() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.coordinator()
    val family = TestFamily(f.env.nas.share("NAS"), nasGuid = null)
    f.memberFinished(c, family, "alessio")
    val env = f.env
    env.fingerprints.save(HomeFingerprint(setOf("fd12:3456:789a::/48"), null, null, 1L))

    expectAdvanced(c.startOver())

    // "Configura di nuovo" ricorda di chi erano i dati (un riassunto, mai l'account in chiaro) e dimentica la casa di prima.
    val left = f.store.snapshot.previousOwner
    assertThat(left).isNotNull()
    assertThat(left).doesNotContain("alessio")
    assertThat(left).doesNotContain(TEST_GUID)
    assertThat(env.fingerprints.fingerprint.value).isNull()
    assertThat(env.previousData.erasures).isEqualTo(0)

    // Bob entra: si arriva al riepilogo senza toccare niente...
    f.memberUntilSummary(c, family, user = "bob")
    assertThat(env.previousData.erasures).isEqualTo(0)
    assertThat(f.store.snapshot.previousOwner).isEqualTo(left)

    // ... e "Vai alla Libreria" toglie i dati di Alice PRIMA di finire (la Libreria non si apre sopra i dati di un altro).
    var finishedWhenErased: Boolean? = null
    env.previousData.onErase = { finishedWhenErased = f.store.snapshot.finished }
    expectAdvanced(c.submit(StepInput.Finish))
    assertThat(env.previousData.erasures).isEqualTo(1)
    assertThat(finishedWhenErased).isFalse()
    assertThat(f.store.snapshot.finished).isTrue()
    assertThat(f.store.snapshot.previousOwner).isNull()
  }

  @Test
  fun r6_rientrareConLoStessoAccountSulloStessoNasTieneIDati() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.coordinator()
    val family = TestFamily(f.env.nas.share("NAS"), nasGuid = null)
    f.memberFinished(c, family, "alessio")
    expectAdvanced(c.startOver())
    assertThat(f.store.snapshot.previousOwner).isNotNull()

    f.memberUntilSummary(c, family, user = "alessio")
    expectAdvanced(c.submit(StepInput.Finish))

    // ON-4, ON-5: la stessa persona che rientra non perde i suoi dati; il ricordo si toglie lo stesso.
    assertThat(f.env.previousData.erasures).isEqualTo(0)
    assertThat(f.store.snapshot.previousOwner).isNull()
  }

  @Test
  fun r6_ancheUnAltroNasConLoStessoAccountToglieIDati() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.coordinator()
    val family = TestFamily(f.env.nas.share("NAS"), nasGuid = null)
    f.memberFinished(c, family, "alessio")
    expectAdvanced(c.startOver())

    f.memberUntilSummary(c, family, user = "alessio", guid = "11111111-2222-3333-4444-555555555555")
    expectAdvanced(c.submit(StepInput.Finish))

    assertThat(f.env.previousData.erasures).isEqualTo(1)
  }

  @Test
  fun r6_seITogliereNonRiesceLaFineSiFermaESiRiprova() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.coordinator()
    val family = TestFamily(f.env.nas.share("NAS"), nasGuid = null)
    f.memberFinished(c, family, "alessio")
    expectAdvanced(c.startOver())
    f.memberUntilSummary(c, family, user = "bob")
    f.env.previousData.result = false

    assertThat(c.submit(StepInput.Finish)).isEqualTo(problem(OnboardingProblem.LOCAL_DATA_NOT_REMOVED))
    assertThat(f.store.snapshot.finished).isFalse()
    assertThat(f.store.snapshot.previousOwner).isNotNull()

    f.env.previousData.result = true
    expectAdvanced(c.submit(StepInput.Finish))
    assertThat(f.env.previousData.erasures).isEqualTo(2)
    assertThat(f.store.snapshot.finished).isTrue()
  }

  @Test
  fun r6_cambiarePercorsoOConfigurareDueVolteNonFaDimenticareChiSiELasciato() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.coordinator()
    f.founderFinished(c)
    expectAdvanced(c.startOver())
    val left = f.store.snapshot.previousOwner

    // Un percorso, poi un altro: l'avanzamento riparte da capo ma il ricordo resta.
    expectAdvanced(c.submit(StepInput.ChoosePath(OnboardingPath.JOIN_QR)))
    expectAdvanced(c.submit(StepInput.ChoosePath(OnboardingPath.FOUND_FAMILY)))
    assertThat(f.store.snapshot.previousOwner).isEqualTo(left)

    // Un secondo "Configura di nuovo" prima di aver scelto un account non ha niente di nuovo da ricordare: il primo resta.
    expectAdvanced(c.startOver())
    assertThat(f.store.snapshot.previousOwner).isEqualTo(left)
  }

  @Test
  fun r6_ilRicordoSopravvivealSalvataggioSuFile() {
    val snapshot = OnboardingSnapshot.EMPTY.copy(previousOwner = "0123456789abcdef0123456789abcdef")
    val decoded = OnboardingSnapshotCodec.decode(OnboardingSnapshotCodec.encode(snapshot))
    assertThat(decoded!!.previousOwner).isEqualTo("0123456789abcdef0123456789abcdef")
    // Uno snapshot di prima, senza il campo, si legge come prima.
    val old = OnboardingSnapshotCodec.encode(OnboardingSnapshot.EMPTY)
    assertThat(OnboardingSnapshotCodec.decode(old)!!.previousOwner).isNull()
  }

  @Test
  fun r6_ilRiassuntoNonDipendeDaMaiuscoleESpaziEDistingueAccountENas() {
    val nas = NasFacts("192.168.178.58", null, 445, TEST_GUID)
    val a = OwnerStamp.of(nas, "Alessio")
    assertThat(a).isEqualTo(OwnerStamp.of(nas.copy(guid = TEST_GUID.uppercase()), " alessio "))
    assertThat(a).isNotEqualTo(OwnerStamp.of(nas, "bob"))
    assertThat(a).isNotEqualTo(OwnerStamp.of(nas.copy(guid = "altro-guid"), "alessio"))
    assertThat(OwnerStamp.of(null, "alessio")).isNull()
    assertThat(OwnerStamp.of(nas, null)).isNull()
    assertThat(NasCredentialSlots.PRIMARY).isNotEmpty() // il nome dello slot non c'entra col riassunto
  }
}
