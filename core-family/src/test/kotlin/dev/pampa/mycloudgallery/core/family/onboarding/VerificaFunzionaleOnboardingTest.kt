package dev.pampa.mycloudgallery.core.family.onboarding

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.AdminActionResult
import dev.pampa.mycloudgallery.core.family.contract.OnboardingPath
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProblem
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.StepInput
import dev.pampa.mycloudgallery.core.family.contract.StepResult
import dev.pampa.mycloudgallery.core.family.contract.TrustSnapshot
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.family.contract.TypedFingerprint
import dev.pampa.mycloudgallery.core.family.invite.ManualInvite
import dev.pampa.mycloudgallery.core.family.setup.NasCredentialSlots
import dev.pampa.mycloudgallery.core.family.support.OnboardingFixture
import dev.pampa.mycloudgallery.core.family.support.TEST_IP
import dev.pampa.mycloudgallery.core.family.support.credentials
import dev.pampa.mycloudgallery.core.family.support.doStep
import dev.pampa.mycloudgallery.core.family.support.expectAdvanced
import dev.pampa.mycloudgallery.core.family.support.runFounderUntil
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKey
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * La verifica funzionale delle fasi 1-5A (`docs/review/funzionale-fasi-1-5a.md`, voci ON), lato coordinatore vero:
 * ogni caso e' il percorso che l'app fa davvero e che i test con il coordinatore finto non vedevano.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VerificaFunzionaleOnboardingTest {

  private fun problem(p: OnboardingProblem) = StepResult.Problem(p)

  /** Il fondatore fino alla fine dell'onboarding ("Vai alla Libreria" fatto). */
  private suspend fun OnboardingFixture.founderFinished(c: DefaultOnboardingCoordinator) {
    runFounderUntil(c, OnboardingStep.SUMMARY)
    expectAdvanced(c.submit(StepInput.Finish))
    assertThat(store.snapshot.finished).isTrue()
  }

  /** Recupero dell'admin (o secondo admin) fino alle aree fatte: e' li' che parte la ricerca del kit. */
  private suspend fun OnboardingFixture.recoveryAfterAreas(c: DefaultOnboardingCoordinator, path: OnboardingPath = OnboardingPath.RECOVER_ADMIN) {
    expectAdvanced(c.submit(StepInput.ChoosePath(path)))
    expectAdvanced(c.submit(StepInput.ConfirmHome(true)))
    expectAdvanced(c.submit(StepInput.NasAddressChosen(env.address)))
    expectAdvanced(c.submit(credentials()))
    expectAdvanced(c.submit(StepInput.Areas("NAS", "alessio")))
    assertThat(current).isEqualTo(OnboardingStep.ROLES)
  }

  // --- ON-7: "Configura il controllo" e "Rivedi la checklist" dalle Impostazioni ---------------------------------

  @Test
  fun on7_aOnboardingFinitoSiRiapronoIlControlloDellaVpnELaChecklist() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.coordinator()
    f.founderFinished(c)

    // Il passo 11 e' fatto (non saltato): dalle Impostazioni si riapre lo stesso.
    assertThat(c.resume(OnboardingStep.VPN)).isEqualTo(StepResult.Advanced(OnboardingStep.VPN))
    assertThat(f.current).isEqualTo(OnboardingStep.VPN)
    expectAdvanced(c.submit(StepInput.VpnSetup(remoteControl = true, tunnelName = "casa")))
    assertThat(f.store.snapshot.choices.tunnelName).isEqualTo("casa")
    assertThat(f.store.snapshot.finished).isTrue()

    // Anche la checklist (passo 13, nel piano del fondatore).
    assertThat(c.resume(OnboardingStep.SECURITY_NOTICE)).isEqualTo(StepResult.Advanced(OnboardingStep.SECURITY_NOTICE))
    expectAdvanced(c.submit(StepInput.SecurityNotice(externalBackupDone = true, routerChecklistDone = true)))
    assertThat(f.store.snapshot.finished).isTrue()

    // Mai un passo del NAS gia' fatto: indirizzo, account, aree e chiave stanno nella configurazione firmata.
    for (step in listOf(OnboardingStep.NAS_ADDRESS, OnboardingStep.ACCOUNT, OnboardingStep.AREAS, OnboardingStep.FAMILY_KEY, OnboardingStep.WELCOME, OnboardingStep.SUMMARY)) {
      assertThat(c.resume(step)).isEqualTo(problem(OnboardingProblem.STEP_NOT_AVAILABLE))
    }
  }

  @Test
  fun on7_unPassoFattoNonSiRiapreAOnboardingInCorsoNeFuoriDalPiano() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.coordinator()
    f.runFounderUntil(c, OnboardingStep.SUMMARY)
    // Dal riepilogo (non ancora finito) si riprendono solo i passi saltati, come prima.
    assertThat(c.resume(OnboardingStep.VPN)).isEqualTo(problem(OnboardingProblem.STEP_NOT_AVAILABLE))

    // Un familiare non ha il passo 13 nel piano: "Rivedi la checklist" non ha niente da riaprire.
    val g = OnboardingFixture(this)
    val member = g.coordinator()
    g.store.update { it.copy(progress = it.progress.copy(path = OnboardingPath.JOIN_QR, plan = OnboardingPlans.planFor(OnboardingPath.JOIN_QR), current = OnboardingStep.SUMMARY, completed = OnboardingPlans.planFor(OnboardingPath.JOIN_QR).toSet()), finished = true) }
    assertThat(member.resume(OnboardingStep.SECURITY_NOTICE)).isEqualTo(problem(OnboardingProblem.STEP_NOT_AVAILABLE))
    assertThat(member.resume(OnboardingStep.VPN)).isEqualTo(StepResult.Advanced(OnboardingStep.VPN))
  }

  // --- ON-3: il recupero riprende dopo la morte del processo ----------------------------------------------------

  @Test
  fun on3_ilRecuperoAMetaSiRiconosceDaUnCoordinatoreNuovoConNasEAree() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.coordinator()
    assertThat(c.recoveryPending()).isFalse()
    assertThat(c.savedNas()).isNull()
    f.recoveryAfterAreas(c)

    // Il processo muore: un coordinatore nuovo sullo stesso avanzamento sa che il recupero e' a meta', e con quale account.
    val restarted = f.coordinator()
    assertThat(restarted.recoveryPending()).isTrue()
    val nas = restarted.savedNas()!!
    assertThat(nas.address.ip).isEqualTo(TEST_IP)
    assertThat(nas.user).isEqualTo("alessio")
    assertThat(nas.commonShare).isEqualTo("NAS")
    assertThat(nas.privateShare).isEqualTo("alessio")

    // Recuperato (il registro dice "approvato, admin"): non c'e' piu' niente da riprendere, e si finisce.
    f.env.registry.status.value = TrustStatus.Approved(admin = true)
    assertThat(restarted.recoveryPending()).isFalse()
  }

  @Test
  fun on3_senzaAreeONelPercorsoDelFondatoreNonCeUnRecuperoDaRiprendere() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.coordinator()
    expectAdvanced(c.submit(StepInput.ChoosePath(OnboardingPath.SECOND_ADMIN)))
    expectAdvanced(c.submit(StepInput.ConfirmHome(true)))
    expectAdvanced(c.submit(StepInput.NasAddressChosen(f.env.address)))
    expectAdvanced(c.submit(credentials()))
    assertThat(c.recoveryPending()).isFalse() // le aree non ci sono ancora: il kit si cerca dopo
    val g = OnboardingFixture(this)
    val founder = g.coordinator()
    g.runFounderUntil(founder, OnboardingStep.ROLES)
    assertThat(founder.recoveryPending()).isFalse()
  }

  // --- ON-12c: un admin appena recuperato non rifa' aree e recupero -------------------------------------------------

  @Test
  fun on12c_unAdminRecuperatoNonCambiaLeAreeNeIlPercorso() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.coordinator()
    f.recoveryAfterAreas(c, OnboardingPath.SECOND_ADMIN)
    f.env.registry.status.value = TrustStatus.Approved(admin = true)
    // Indietro non riporta alle aree (non si possono piu' confermare: si resterebbe li' senza un "avanti").
    c.back()
    assertThat(f.current).isEqualTo(OnboardingStep.ROLES)
    // E le aree non si rifanno: con l'area comune cambiata il registro si leggerebbe dalla share sbagliata.
    f.store.update { it.copy(progress = it.progress.copy(current = OnboardingStep.AREAS)) }
    assertThat(c.submit(StepInput.Areas("Public", "alessio"))).isEqualTo(problem(OnboardingProblem.STEP_NOT_AVAILABLE))
    assertThat(f.store.snapshot.commonShare).isEqualTo("NAS")
    assertThat(c.submit(StepInput.ChoosePath(OnboardingPath.JOIN_QR))).isEqualTo(problem(OnboardingProblem.STEP_NOT_AVAILABLE))
    assertThat(f.store.snapshot.path).isEqualTo(OnboardingPath.SECOND_ADMIN)
  }

  // --- ON-4 e ON-5: "Configura di nuovo" --------------------------------------------------------------------------

  @Test
  fun on4_ricominciareDimenticaAvanzamentoAccountStatoEChiavi() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.coordinator()
    f.founderFinished(c)
    val env = f.env
    env.trustState.save(TrustSnapshot(ByteArray(8), "x".repeat(26), TrustIds.random(), "x".repeat(26), 1L, emptyMap()))
    env.deviceKeys.deviceSigner()
    assertThat(env.familyKeys.stored).isNotNull()
    assertThat(env.accounts.current()).isNotNull()

    expectAdvanced(c.startOver())

    assertThat(f.store.snapshot.path).isNull()
    assertThat(f.store.snapshot.finished).isFalse()
    assertThat(f.current).isEqualTo(OnboardingStep.WELCOME)
    assertThat(f.store.snapshot.nas).isNull()
    assertThat(env.accounts.current()).isNull()
    assertThat(env.credentials.removed).contains(NasCredentialSlots.PRIMARY)
    assertThat(env.trustState.snapshot).isNull()
    assertThat(env.deviceKeys.hasDeviceKey()).isFalse()
    assertThat(env.familyKeys.stored).isNull()
    // Il motore della fiducia rilegge: senza stato e senza chiave il dispositivo e' "mai configurato".
    assertThat(env.registry.refreshes).isNotEmpty()

    // E si riparte dal benvenuto, con qualunque percorso.
    expectAdvanced(c.submit(StepInput.ChoosePath(OnboardingPath.JOIN_QR)))
  }

  // --- ON-8: "Cartelle da escludere" ripresa dopo la fondazione -------------------------------------------------

  @Test
  fun on8_leCartelleRipreseDopoLaFondazioneVannoNellaConfigurazioneFirmata() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.coordinator()
    f.runFounderUntil(c, OnboardingStep.FOLDERS)
    expectAdvanced(c.skip(OnboardingStep.FOLDERS))
    f.founderFinished(c)
    assertThat(f.env.registry.exclusionUpdates).isEmpty()

    expectAdvanced(c.resume(OnboardingStep.FOLDERS))
    expectAdvanced(c.submit(StepInput.Folders(setOf("Musica"))))
    assertThat(f.env.registry.exclusionUpdates).containsExactly(setOf("Musica"))
    assertThat(f.store.snapshot.excluded).containsExactly("Musica")
    assertThat(f.store.snapshot.progress.pending).isEmpty()
  }

  @Test
  fun on8_seLaFirmaNonRiesceIlPassoResta() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.coordinator()
    f.runFounderUntil(c, OnboardingStep.FOLDERS)
    expectAdvanced(c.skip(OnboardingStep.FOLDERS))
    f.founderFinished(c)
    expectAdvanced(c.resume(OnboardingStep.FOLDERS))
    // Telefono senza blocco schermo sicuro, o la chiave non si apre: la configurazione firmata non cambia, e il passo nemmeno.
    f.env.registry.nextResult = AdminActionResult.NotAdmin
    assertThat(c.submit(StepInput.Folders(setOf("Musica")))).isEqualTo(problem(OnboardingProblem.STEP_NOT_AVAILABLE))
    assertThat(f.store.snapshot.excluded).isEmpty()
    assertThat(f.current).isEqualTo(OnboardingStep.FOLDERS)
  }

  @Test
  fun on8_primaDellaFondazioneLeCartelleAspettanoIlPasso12() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.coordinator()
    f.runFounderUntil(c, OnboardingStep.FOLDERS)
    expectAdvanced(f.doStep(c))
    // Nessuna firma a meta' onboarding: le esclusioni entrano nella configurazione di fondazione.
    assertThat(f.env.registry.exclusionUpdates).isEmpty()
  }

  // --- ON-12b: l'impronta digitata tollera O/0 e I/L/1 --------------------------------------------------------------

  @Test
  fun on12b_lImprontaDigitataTolleraGliAliasMaNonConfondeDueImpronte() {
    val fingerprint = FamilyKey.generate().publicKey.fingerprint
    // Una forma canonica resta se stessa: la normalizzazione non cambia mai un'impronta vera.
    assertThat(TypedFingerprint.normalize(fingerprint)).isEqualTo(fingerprint)
    // Letta dal foglio: maiuscole, gruppi, O al posto di 0 e I o L al posto di 1.
    val typed = TypedFingerprint.grouped(fingerprint).uppercase().replace('0', 'O').replace('1', 'I')
    assertThat(TypedFingerprint.isComplete(typed)).isTrue()
    assertThat(TypedFingerprint.normalize(typed)).isEqualTo(fingerprint)
    assertThat(TypedFingerprint.normalize("l")).isEqualTo("1")
    // Gli alias stanno fuori dall'alfabeto dell'impronta (niente i, l, o, u): due impronte diverse restano diverse.
    val alphabet = "0123456789abcdefghjkmnpqrstvwxyz"
    for (alias in listOf('i', 'l', 'o', 'u')) assertThat(alphabet).doesNotContain(alias.toString())
    repeat(200) {
      val a = FamilyKey.generate().publicKey.fingerprint
      val b = FamilyKey.generate().publicKey.fingerprint
      if (a != b) assertThat(TypedFingerprint.normalize(a)).isNotEqualTo(TypedFingerprint.normalize(b))
    }
    // Una U non e' un alias di niente: un'impronta con una U non e' completa.
    assertThat(TypedFingerprint.isComplete("u" + fingerprint.drop(1))).isFalse()
    // Stessa lettura di `Base32.normalizeHumanInput`, che usano l'invito manuale e l'ancora.
    assertThat(TypedFingerprint.normalize(typed)).isEqualTo(Base32.normalizeHumanInput(typed))
  }

  // --- ON-1 (core): l'invito manuale con lo "stato della famiglia" ---------------------------------------------------

  @Test
  fun on1_lInvitoManualeAccettaLaVersioneMinimaDelloStatoDellaFamiglia() {
    val familyId = TrustIds.random()
    val fingerprint = FamilyKey.generate().publicKey.fingerprint
    val withState = ManualInvite.create(familyId, fingerprint, TEST_IP, "mamma", "NAS", "mamma", "Tablet", minRegistryVersion = 12L)
    assertThat(withState).isInstanceOf(Verdict.Accepted::class.java)
    assertThat((withState as Verdict.Accepted).value.minRegistryVersion).isEqualTo(12L)
    // Senza aree non c'e' invito: il registro sta nell'area comune.
    assertThat(ManualInvite.create(familyId, fingerprint, TEST_IP, "mamma", "", "", "Tablet")).isInstanceOf(Verdict.Rejected::class.java)
    // Una versione impossibile no.
    assertThat(ManualInvite.create(familyId, fingerprint, TEST_IP, "mamma", "NAS", "mamma", "Tablet", minRegistryVersion = -1L))
      .isInstanceOf(Verdict.Rejected::class.java)
  }
}
