package dev.pampa.mycloudgallery.core.family.onboarding

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.family.contract.OnboardingPath
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProblem
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.OnboardingWarning
import dev.pampa.mycloudgallery.core.family.contract.PreviewQuality
import dev.pampa.mycloudgallery.core.family.contract.StepInput
import dev.pampa.mycloudgallery.core.family.contract.StepResult
import dev.pampa.mycloudgallery.core.family.support.OnboardingFixture
import dev.pampa.mycloudgallery.core.family.support.Reports
import dev.pampa.mycloudgallery.core.family.support.TEST_GUID
import dev.pampa.mycloudgallery.core.family.support.TEST_ULA
import dev.pampa.mycloudgallery.core.family.support.credentials
import dev.pampa.mycloudgallery.core.family.support.doStep
import dev.pampa.mycloudgallery.core.family.support.expectAdvanced
import dev.pampa.mycloudgallery.core.family.support.runFounderUntil
import dev.pampa.mycloudgallery.core.nas.contract.AuthBlockReason
import dev.pampa.mycloudgallery.core.nas.contract.BreakerState
import dev.pampa.mycloudgallery.core.nas.contract.InsecureKind
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.NegotiationFacts
import dev.pampa.mycloudgallery.core.nas.contract.ShareAccess
import dev.pampa.mycloudgallery.core.nas.contract.SmbDialect
import dev.pampa.mycloudgallery.core.nas.contract.Stage
import dev.pampa.mycloudgallery.core.network.contract.DenyReason
import dev.pampa.mycloudgallery.core.network.contract.LocalNetworkAccess
import dev.pampa.mycloudgallery.core.network.testing.Snapshots
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.NightWindows
import dev.pampa.mycloudgallery.core.rules.Preset
import dev.pampa.mycloudgallery.core.rules.platform.health.BackgroundHealth
import dev.pampa.mycloudgallery.core.rules.platform.health.HealthPermission
import dev.pampa.mycloudgallery.core.rules.platform.health.PermissionGrant
import dev.pampa.mycloudgallery.core.security.credentials.SaveResult
import dev.pampa.mycloudgallery.core.security.device.ScreenLockStatus
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * I passi 1-11 e la navigazione di [DefaultOnboardingCoordinator] (il 12 e il 14 hanno le loro prove in
 * `FamilyKeyStepTest`): persistenza e ripresa (E-O9), un solo tentativo di password (E-R8, E-S12), errori
 * specifici per ogni problema di rete (E-O1), delle aree (E-O2, E-O3) e dei permessi (E-O4).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingCoordinatorTest {

  private fun problem(p: OnboardingProblem) = StepResult.Problem(p)

  // --- Il percorso intero e la ripresa -----------------------------------------------------------------

  @Test
  fun ilFondatoreFaTuttiIQuattordiciPassiEAlLaFineLaFamigliaEsiste() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.coordinator()
    val seen = mutableListOf<OnboardingStep>()
    do {
      seen += f.current
      val result = expectAdvanced(f.doStep(c))
    } while (result.next != null)

    assertThat(seen).containsExactlyElementsIn(OnboardingStep.entries).inOrder()
    val s = f.store.snapshot
    assertThat(s.finished).isTrue()
    assertThat(s.progress.completed).containsExactlyElementsIn(OnboardingStep.entries)
    assertThat(s.progress.pending).isEmpty()
    assertThat(s.progress.startedAt).isEqualTo(f.env.deviceNow)
    assertThat(s.finishedAt).isEqualTo(f.env.deviceNow)
    assertThat(c.progress.value).isEqualTo(s.progress)
    // Si salva a ogni passo (E-O9): almeno una scrittura per ciascuno dei quattordici.
    assertThat(f.store.writes).isAtLeast(14)

    // Cio' che il percorso ha lasciato nel resto dell'app.
    val env = f.env
    assertThat(env.fingerprints.fingerprint.value!!.ulaPrefixes).containsExactly(TEST_ULA)
    assertThat(env.credentials.saved.single().user).isEqualTo("alessio")
    assertThat(env.accounts.saved.single().pinnedGuid).isEqualTo(TEST_GUID)
    assertThat(s.excluded).containsExactly("Musica", "Film/Serie")
    assertThat(s.commonShare).isEqualTo("NAS")
    assertThat(s.privateShare).isEqualTo("alessio")
    assertThat(env.nas.share("NAS").exists(NasLayout.registryFile(1))).isTrue()
    assertThat(env.trustState.snapshot!!.familyId).isEqualTo(s.foundation!!.familyId)
    assertThat(env.rules.current().preset).isEqualTo(Preset.BALANCED)
    assertThat(env.choices.applied.last().camera).isTrue()
    assertThat(env.choices.applied.last().screenshots).isFalse()
    assertThat(env.choices.applied.last().indexer).isTrue()
  }

  @Test
  fun ognunoDeiPassiSiRiprendeDaUnCoordinatoreNuovoE_E_O9() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    var c = f.coordinator()
    assertThat(c.progress.value.current).isEqualTo(OnboardingStep.WELCOME)
    do {
      val result = expectAdvanced(f.doStep(c))
      // Il processo muore (o si esce dall'app, come ai passi 10 e 11): un coordinatore nuovo, lo stesso avanzamento.
      c = f.coordinator()
      assertThat(c.progress.value).isEqualTo(f.store.snapshot.progress)
      assertThat(c.progress.value.current).isEqualTo(result.next ?: OnboardingStep.SUMMARY)
    } while (result.next != null)
    assertThat(f.store.snapshot.finished).isTrue()
  }

  @Test
  fun siPuoRiprendereAMetaDelPasso2() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    f.runFounderUntil(f.coordinator(), OnboardingStep.NAS_ADDRESS)
    expectAdvanced(f.coordinator().submit(StepInput.ConfirmHome(true)))
    // Il processo muore fra "e' casa" e l'indirizzo: il Wi-Fi confermato e' stato salvato.
    val next = f.coordinator()
    expectAdvanced(next.submit(StepInput.NasAddressChosen(f.env.address)))
    assertThat(f.current).isEqualTo(OnboardingStep.ACCOUNT)
  }

  @Test
  fun unInputDiUnAltroPassoNonSiAccettaEUnaPasswordSiAzzeraComunque() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.coordinator()
    val writes = f.store.writes
    assertThat(c.submit(StepInput.Areas("NAS", "alessio"))).isEqualTo(problem(OnboardingProblem.STEP_NOT_AVAILABLE))
    val credentials = credentials(password = "una-password-lunga-12")
    assertThat(c.submit(credentials)).isEqualTo(problem(OnboardingProblem.STEP_NOT_AVAILABLE))
    assertThat(credentials.password.all { it == '\u0000' }).isTrue()
    val code = StepInput.RecoveryCodeTyped("ABCD-EFGH".toCharArray())
    assertThat(c.submit(code)).isEqualTo(problem(OnboardingProblem.STEP_NOT_AVAILABLE))
    assertThat(code.code.all { it == '\u0000' }).isTrue()
    assertThat(c.submit(StepInput.Finish)).isEqualTo(problem(OnboardingProblem.STEP_NOT_AVAILABLE))
    assertThat(f.store.writes).isEqualTo(writes)
    assertThat(f.env.tester.runs).isEmpty()
  }

  @Test
  fun conLArchivioNonLeggibileNessunPassoParte() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.coordinator()
    f.store.setState(StoreState.Unavailable)
    assertThat(c.submit(StepInput.ChoosePath(OnboardingPath.FOUND_FAMILY))).isEqualTo(problem(OnboardingProblem.STEP_NOT_AVAILABLE))
    assertThat(c.skip(OnboardingStep.FOLDERS)).isEqualTo(problem(OnboardingProblem.STEP_NOT_AVAILABLE))
    assertThat(c.proposeAreas()).isEmpty()
  }

  // --- Passo 1 ---------------------------------------------------------------------------------------------

  @Test
  fun ognuiPercorsoHaIlSuoPianoELaScelta_ricominciaDaCapo() = runTest(UnconfinedTestDispatcher()) {
    val expected = mapOf(
      OnboardingPath.FOUND_FAMILY to OnboardingStep.entries.toList(),
      OnboardingPath.JOIN_QR to listOf(
        OnboardingStep.WELCOME, OnboardingStep.NAS_ADDRESS, OnboardingStep.ACCOUNT, OnboardingStep.ROLES, OnboardingStep.PRESET,
        OnboardingStep.PERMISSIONS, OnboardingStep.BATTERY, OnboardingStep.VPN, OnboardingStep.SUMMARY,
      ),
      OnboardingPath.RECOVER_ADMIN to listOf(
        OnboardingStep.WELCOME, OnboardingStep.NAS_ADDRESS, OnboardingStep.ACCOUNT, OnboardingStep.AREAS, OnboardingStep.ROLES,
        OnboardingStep.PRESET, OnboardingStep.PERMISSIONS, OnboardingStep.BATTERY, OnboardingStep.VPN, OnboardingStep.SUMMARY,
      ),
    )
    expected.forEach { (path, plan) ->
      val f = OnboardingFixture(this)
      val result = expectAdvanced(f.coordinator().submit(StepInput.ChoosePath(path)))
      assertThat(result.next).isEqualTo(OnboardingStep.NAS_ADDRESS)
      assertThat(f.store.snapshot.progress.plan).isEqualTo(plan)
      assertThat(f.store.snapshot.progress.path).isEqualTo(path)
    }
    assertThat(OnboardingPlans.planFor(OnboardingPath.SECOND_ADMIN)).isEqualTo(OnboardingPlans.planFor(OnboardingPath.RECOVER_ADMIN))
    assertThat(OnboardingPlans.planFor(OnboardingPath.JOIN_MANUAL)).isEqualTo(OnboardingPlans.planFor(OnboardingPath.JOIN_QR))
  }

  @Test
  fun senzaQrSiAvvisaCheLaVersioneMinimaDelRegistroEZero() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val result = expectAdvanced(f.coordinator().submit(StepInput.ChoosePath(OnboardingPath.JOIN_MANUAL)))
    assertThat(result.warnings).containsExactly(OnboardingWarning.NO_MIN_REGISTRY_VERSION)
  }

  @Test
  fun tornandoAlBenvenutoSiPuoCambiarePercorsoMaNonDopoLaFondazione() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.coordinator()
    expectAdvanced(c.submit(StepInput.ChoosePath(OnboardingPath.FOUND_FAMILY)))
    c.back()
    assertThat(f.current).isEqualTo(OnboardingStep.WELCOME)
    expectAdvanced(c.submit(StepInput.ChoosePath(OnboardingPath.JOIN_QR)))
    assertThat(f.store.snapshot.progress.plan).hasSize(9)
  }

  // --- Passo 2 -----------------------------------------------------------------------------------------------

  private suspend fun OnboardingFixture.atAddressStep(): DefaultOnboardingCoordinator {
    val c = coordinator()
    runFounderUntil(c, OnboardingStep.NAS_ADDRESS)
    return c
  }

  @Test
  fun senzaWifiOSenzaUlaSiSpiegaCheCosaManca() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.atAddressStep()

    f.env.capture.candidate = null
    assertThat(c.homeCandidate()).isNull()
    assertThat(c.submit(StepInput.ConfirmHome(true))).isEqualTo(problem(OnboardingProblem.NOT_ON_WIFI))
    assertThat(c.submit(StepInput.ConfirmHome(false))).isEqualTo(problem(OnboardingProblem.NOT_ON_WIFI))

    // Un Wi-Fi senza ULA: "casa" non si puo' confermare, perche' gateway e dominio valgono per mezza Italia (E-S10).
    f.env.capture.candidate = f.env.candidate.copy(ulaPrefixes = emptySet())
    assertThat(c.submit(StepInput.ConfirmHome(true))).isEqualTo(problem(OnboardingProblem.NO_ULA))
    assertThat(f.store.snapshot.homeConfirmed).isNull()

    // Senza aver confermato casa, l'indirizzo non si prova nemmeno.
    assertThat(c.submit(StepInput.NasAddressChosen(f.env.address))).isEqualTo(problem(OnboardingProblem.NOT_ON_WIFI))
    assertThat(f.env.tester.runs).isEmpty()
  }

  @Test
  fun conLaVpnAccesaSiChiedeDiSpegnerlaPerQuestoPasso() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.atAddressStep()
    f.env.network.current = Snapshots.awayVpnWifi()
    assertThat(c.submit(StepInput.ConfirmHome(true))).isEqualTo(problem(OnboardingProblem.VPN_ACTIVE))
    f.env.network.current = Snapshots.foreignVpn()
    assertThat(c.submit(StepInput.ConfirmHome(true))).isEqualTo(problem(OnboardingProblem.VPN_ACTIVE))
    // Accesa dopo aver confermato casa: l'indirizzo non parte.
    f.env.network.current = Snapshots.home()
    expectAdvanced(c.submit(StepInput.ConfirmHome(true)))
    f.env.network.current = Snapshots.awayVpnWifi()
    assertThat(c.submit(StepInput.NasAddressChosen(f.env.address))).isEqualTo(problem(OnboardingProblem.VPN_ACTIVE))
    assertThat(f.env.tester.runs).isEmpty()
  }

  @Test
  fun ilPermessoDiReteLocaleSiControllaPrimaDiDireNasSpento() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.atAddressStep()
    f.env.localNetwork.access = LocalNetworkAccess.DENIED
    assertThat(c.submit(StepInput.ConfirmHome(true))).isEqualTo(problem(OnboardingProblem.LOCAL_NETWORK_DENIED))
    f.env.localNetwork.access = LocalNetworkAccess.GRANTED
    expectAdvanced(c.submit(StepInput.ConfirmHome(true)))
    f.env.localNetwork.access = LocalNetworkAccess.DENIED
    assertThat(c.submit(StepInput.NasAddressChosen(f.env.address))).isEqualTo(problem(OnboardingProblem.LOCAL_NETWORK_DENIED))
    assertThat(f.env.tester.runs).isEmpty()
    // Se invece il sistema lo dice dalla NEGOTIATE, il messaggio e' lo stesso.
    f.env.localNetwork.access = LocalNetworkAccess.GRANTED
    f.env.tester.handler = { _, _, stages -> Reports.failed(stages, Stage.TCP, NasError.LocalNetworkBlocked) }
    assertThat(c.submit(StepInput.NasAddressChosen(f.env.address))).isEqualTo(problem(OnboardingProblem.LOCAL_NETWORK_DENIED))
    assertThat(f.store.snapshot.deferrable).isFalse()
  }

  @Test
  fun unIndirizzoBuonoMemorizzaIlGuidEDiventaLaRete_diCasa() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.atAddressStep()
    expectAdvanced(c.submit(StepInput.ConfirmHome(true)))
    assertThat(f.env.fingerprints.fingerprint.value).isNull() // ancora non e' casa: manca il GUID
    expectAdvanced(c.submit(StepInput.NasAddressChosen(f.env.address)))

    // Una sola prova, senza password, sul percorso dell'onboarding: percorso, TCP, NEGOTIATE e GUID.
    val run = f.env.tester.runs.single()
    assertThat(run.hadCredentials).isFalse()
    assertThat(run.stages).containsExactly(Stage.ROUTE, Stage.TCP, Stage.NEGOTIATE, Stage.GUID)
    assertThat(run.target.candidate).isEqualTo(f.env.candidate)
    assertThat(run.target.expectedGuid).isNull()
    assertThat(f.store.snapshot.nas!!.guid).isEqualTo(TEST_GUID)
    val fingerprint = f.env.fingerprints.fingerprint.value!!
    assertThat(fingerprint.ulaPrefixes).containsExactly(TEST_ULA)
    assertThat(fingerprint.gatewayV4).isEqualTo("192.168.178.1")
    assertThat(fingerprint.dnsDomain).isEqualTo("fritz.box")
  }

  @Test
  fun unNasDiVersioneVecchiaOSenzaCifraturaOSenzaGuidSiRifiuta() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.atAddressStep()
    expectAdvanced(c.submit(StepInput.ConfirmHome(true)))
    fun facts(dialect: SmbDialect = SmbDialect.SMB_3_1_1, encryption: Boolean = true, guid: String = TEST_GUID) =
      NegotiationFacts(dialect, encryption, guid, 0L)
    val cases = listOf(
      facts(dialect = SmbDialect.SMB_3_0_2) to OnboardingProblem.NAS_INSECURE,
      facts(dialect = SmbDialect.SMB_2_1) to OnboardingProblem.NAS_INSECURE,
      facts(encryption = false) to OnboardingProblem.NAS_INSECURE,
      facts(guid = "") to OnboardingProblem.IDENTITY_MISMATCH,
    )
    for ((negotiation, expected) in cases) {
      f.env.tester.handler = { _, _, stages -> Reports.ok(stages, negotiation) }
      assertThat(c.submit(StepInput.NasAddressChosen(f.env.address))).isEqualTo(problem(expected))
    }
    f.env.tester.handler = { _, _, stages -> Reports.failed(stages, Stage.NEGOTIATE, NasError.InsecureSession(InsecureKind.NO_ENCRYPTION)) }
    assertThat(c.submit(StepInput.NasAddressChosen(f.env.address))).isEqualTo(problem(OnboardingProblem.NAS_INSECURE))
    f.env.tester.handler = { _, _, stages -> Reports.ok(stages, null) }
    assertThat(c.submit(StepInput.NasAddressChosen(f.env.address))).isEqualTo(problem(OnboardingProblem.NAS_NOT_FOUND))
    f.env.tester.handler = { _, _, stages -> Reports.failed(stages, Stage.ROUTE, NasError.NotAllowedHere(DenyReason.UNTRUSTED_NETWORK)) }
    assertThat(c.submit(StepInput.NasAddressChosen(f.env.address))).isEqualTo(problem(OnboardingProblem.NOT_ON_WIFI))
    // Nessuna di queste cose e' "NAS spento": non si offre di continuare piu' tardi, e casa non e' stata registrata.
    assertThat(f.store.snapshot.deferrable).isFalse()
    assertThat(c.skip(OnboardingStep.NAS_ADDRESS)).isEqualTo(problem(OnboardingProblem.STEP_NOT_AVAILABLE))
    assertThat(f.env.fingerprints.fingerprint.value).isNull()
  }

  @Test
  fun IlNasSpentoOffreContinuaPiuTardiEIPassiLocaliSiFannoLoStesso_E_O1() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.atAddressStep()
    expectAdvanced(c.submit(StepInput.ConfirmHome(true)))
    f.env.tester.handler = { _, _, stages -> Reports.failed(stages, Stage.TCP, NasError.Timeout) }
    assertThat(c.submit(StepInput.NasAddressChosen(f.env.address))).isEqualTo(problem(OnboardingProblem.NAS_NOT_FOUND))
    f.env.tester.handler = { _, _, stages -> Reports.failed(stages, Stage.TCP, NasError.ServiceDown) }
    assertThat(c.submit(StepInput.NasAddressChosen(f.env.address))).isEqualTo(problem(OnboardingProblem.NAS_SERVICE_DOWN))
    assertThat(f.store.snapshot.deferrable).isTrue()
    assertThat(f.env.fingerprints.fingerprint.value).isNull()

    // "Continua piu' tardi": i passi che hanno bisogno del NAS aspettano, quelli locali si fanno.
    val later = expectAdvanced(c.skip(OnboardingStep.NAS_ADDRESS))
    assertThat(later.next).isEqualTo(OnboardingStep.BACKUP_SOURCES)
    assertThat(f.store.snapshot.progress.pending).containsExactly(
      OnboardingStep.NAS_ADDRESS, OnboardingStep.ACCOUNT, OnboardingStep.AREAS, OnboardingStep.FOLDERS, OnboardingStep.FAMILY_KEY,
    ).inOrder()
    f.runFounderUntil(c, OnboardingStep.SECURITY_NOTICE)
    f.runFounderUntil(c, OnboardingStep.SUMMARY)
    // Senza famiglia non si puo' dire "fatto".
    assertThat(c.submit(StepInput.Finish)).isEqualTo(problem(OnboardingProblem.STEP_NOT_AVAILABLE))
    assertThat(f.store.snapshot.finished).isFalse()

    // Il NAS e' tornato: i passi rimandati si riprendono uno dopo l'altro, senza rifare gli altri.
    f.env.tester.handler = { _, _, stages -> Reports.ok(stages) }
    assertThat(expectAdvanced(c.resume(OnboardingStep.NAS_ADDRESS)).next).isEqualTo(OnboardingStep.NAS_ADDRESS)
    assertThat(expectAdvanced(f.doStep(c)).next).isEqualTo(OnboardingStep.ACCOUNT)
    assertThat(expectAdvanced(f.doStep(c)).next).isEqualTo(OnboardingStep.AREAS)
    assertThat(expectAdvanced(f.doStep(c)).next).isEqualTo(OnboardingStep.FOLDERS)
    assertThat(expectAdvanced(f.doStep(c)).next).isEqualTo(OnboardingStep.FAMILY_KEY)
    assertThat(expectAdvanced(f.doStep(c)).next).isNull() // niente altro da completare
    assertThat(f.current).isEqualTo(OnboardingStep.SUMMARY)
    assertThat(f.store.snapshot.progress.pending).isEmpty()
    expectAdvanced(c.submit(StepInput.Finish))
    assertThat(f.store.snapshot.finished).isTrue()
  }

  // --- Passo 3 -----------------------------------------------------------------------------------------------

  private suspend fun OnboardingFixture.atAccountStep(): DefaultOnboardingCoordinator {
    val c = coordinator()
    runFounderUntil(c, OnboardingStep.ACCOUNT)
    return c
  }

  private fun failAuth(f: OnboardingFixture, error: NasError) {
    f.env.tester.handler = { _, creds, stages ->
      if (creds == null) Reports.ok(stages) else Reports.failed(stages, Stage.AUTHENTICATE, error, Reports.FACTS)
    }
  }

  @Test
  fun lAccountAdminNonSiUsaMaiNemmenoSeSiInsiste() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.atAccountStep()
    val runs = f.env.tester.runs.size
    for (user in listOf("admin", "Admin", "ADMIN", "  admin  ")) {
      val input = credentials(user = user)
      assertThat(c.submit(input)).isEqualTo(problem(OnboardingProblem.ADMIN_ACCOUNT_REFUSED))
      assertThat(input.password.all { it == '\u0000' }).isTrue()
    }
    assertThat(f.env.tester.runs).hasSize(runs) // il NAS non ha visto niente
    assertThat(f.env.credentials.saved).isEmpty()
  }

  @Test
  fun unaPasswordSottoIDodiciCaratteriAvvisaMaVaAvanti() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.atAccountStep()
    val result = expectAdvanced(c.submit(credentials(password = "corta-11-ch")))
    assertThat(result.warnings).containsExactly(OnboardingWarning.SHORT_PASSWORD)
    assertThat(result.next).isEqualTo(OnboardingStep.AREAS)

    val g = OnboardingFixture(this)
    val d = g.atAccountStep()
    assertThat(expectAdvanced(d.submit(credentials(password = "esattamente-12"))).warnings).isEmpty()
  }

  @Test
  fun unSoloTentativoDiPasswordELaStessaPasswordNonTornaAlNas_E_R8() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.atAccountStep()
    failAuth(f, NasError.Auth(AuthBlockReason.AUTH_FAILED))
    val wrong = credentials(password = "password-sbagliata-1")
    assertThat(c.submit(wrong)).isEqualTo(problem(OnboardingProblem.AUTH_FAILED))
    assertThat(wrong.password.all { it == '\u0000' }).isTrue()
    assertThat(f.env.tester.authAttempts).isEqualTo(1)
    // Niente e' stato salvato: ne' la password, ne' l'account.
    assertThat(f.env.credentials.saved).isEmpty()
    assertThat(f.env.accounts.saved).isEmpty()
    assertThat(f.current).isEqualTo(OnboardingStep.ACCOUNT)

    // L'interruttore (di P1) si e' aperto. Lo stesso identico tentativo non arriva al NAS: un doppio tocco bloccherebbe l'account.
    f.env.breaker.open(AuthBlockReason.AUTH_FAILED)
    assertThat(c.submit(credentials(password = "password-sbagliata-1"))).isEqualTo(problem(OnboardingProblem.AUTH_FAILED))
    assertThat(c.submit(credentials(user = "ALESSIO", password = "password-sbagliata-1"))).isEqualTo(problem(OnboardingProblem.AUTH_FAILED))
    assertThat(f.env.tester.authAttempts).isEqualTo(1)

    // Una password nuova, scritta dall'utente, richiude l'interruttore e fa un nuovo (unico) tentativo.
    val generation = f.env.breaker.state.value.generation
    f.env.tester.handler = { _, _, stages -> Reports.ok(stages) }
    expectAdvanced(c.submit(credentials(password = "password-giusta-12")))
    assertThat(f.env.tester.authAttempts).isEqualTo(2)
    assertThat(f.env.breaker.state.value).isInstanceOf(BreakerState.Closed::class.java)
    assertThat(f.env.breaker.state.value.generation).isEqualTo(generation + 2) // richiuso + password salvata
    assertThat(f.env.credentials.saved.single().password).isEqualTo("password-giusta-12")
  }

  @Test
  fun ognunDeiProblemiDiAccountHaIlSuoMessaggioENonSalvaNiente() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.atAccountStep()
    val cases = listOf(
      NasError.Auth(AuthBlockReason.ACCOUNT_LOCKED) to OnboardingProblem.ACCOUNT_LOCKED,
      NasError.Auth(AuthBlockReason.ACCOUNT_DISABLED) to OnboardingProblem.ACCOUNT_DISABLED,
      NasError.Auth(AuthBlockReason.PASSWORD_EXPIRED) to OnboardingProblem.PASSWORD_EXPIRED,
      NasError.Auth(AuthBlockReason.ACCOUNT_RESTRICTED) to OnboardingProblem.ACCOUNT_RESTRICTED,
      NasError.InsecureSession(InsecureKind.GUEST) to OnboardingProblem.NAS_INSECURE,
      NasError.InsecureSession(InsecureKind.NO_ENCRYPTION) to OnboardingProblem.NAS_INSECURE,
      NasError.IdentityMismatch to OnboardingProblem.IDENTITY_MISMATCH,
      NasError.Timeout to OnboardingProblem.NAS_NOT_FOUND,
      NasError.NotAllowedHere(DenyReason.UNTRUSTED_NETWORK) to OnboardingProblem.NOT_ON_WIFI,
    )
    cases.forEachIndexed { index, (error, expected) ->
      failAuth(f, error)
      assertWithMessage("$error").that(c.submit(credentials(password = "password-numero-$index"))).isEqualTo(problem(expected))
    }
    assertThat(f.env.credentials.saved).isEmpty()
    assertThat(f.env.accounts.saved).isEmpty()
  }

  @Test
  fun unAutenticazioneSaltataNonEUnSuccesso() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.atAccountStep()
    // L'interruttore aperto fa saltare l'autenticazione: per `passed` il rapporto sembra riuscito, ma la password non e' stata provata.
    f.env.tester.handler = { _, creds, stages -> if (creds == null) Reports.ok(stages) else Reports.authSkipped(stages) }
    assertThat(c.submit(credentials())).isEqualTo(problem(OnboardingProblem.AUTH_FAILED))
    assertThat(f.env.credentials.saved).isEmpty()
    assertThat(f.env.accounts.saved).isEmpty()
  }

  @Test
  fun senzaKeystoreHardwareLeCredenzialiNonSiSalvanoENemmenoLAccount() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.atAccountStep()
    f.env.credentials.result = SaveResult.HardwareKeyUnavailable
    assertThat(c.submit(credentials())).isEqualTo(problem(OnboardingProblem.CREDENTIALS_NOT_SAVED))
    f.env.credentials.result = SaveResult.Unavailable
    assertThat(c.submit(credentials(password = "un-altra-password-1"))).isEqualTo(problem(OnboardingProblem.CREDENTIALS_NOT_SAVED))
    assertThat(f.env.accounts.saved).isEmpty()
    assertThat(f.current).isEqualTo(OnboardingStep.ACCOUNT)
  }

  @Test
  fun unNomeOUnaPasswordImpossibiliNonArrivanoAlNas() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.atAccountStep()
    val runs = f.env.tester.runs.size
    assertThat(c.submit(credentials(user = "ma rio"))).isEqualTo(problem(OnboardingProblem.AUTH_FAILED))
    assertThat(c.submit(credentials(user = ""))).isEqualTo(problem(OnboardingProblem.AUTH_FAILED))
    assertThat(c.submit(credentials(user = "m\u00e0rio"))).isEqualTo(problem(OnboardingProblem.AUTH_FAILED))
    assertThat(c.submit(credentials(password = ""))).isEqualTo(problem(OnboardingProblem.AUTH_FAILED))
    assertThat(f.env.tester.runs).hasSize(runs)
  }

  @Test
  fun lAutenticazioneRiuscitaLasciaAccountECredenzialiAGuidFissato() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.atAccountStep()
    val input = credentials()
    val result = expectAdvanced(c.submit(input))
    assertThat(result.next).isEqualTo(OnboardingStep.AREAS)
    assertThat(input.password.all { it == '\u0000' }).isTrue()

    val run = f.env.tester.runs.last()
    assertThat(run.hadCredentials).isTrue()
    assertThat(run.target.user).isEqualTo("alessio")
    assertThat(run.target.expectedGuid).isEqualTo(TEST_GUID)
    // "Casa" e' gia' registrata al passo 2: il percorso lo decide il cancello di rete, non il Wi-Fi di quel momento.
    assertThat(run.target.candidate).isNull()
    assertThat(run.stages).containsExactly(Stage.ROUTE, Stage.TCP, Stage.NEGOTIATE, Stage.GUID, Stage.AUTHENTICATE, Stage.SECURITY_CHECK)
    val saved = f.env.credentials.saved.single()
    assertThat(saved.slot).isEqualTo("nas-primary")
    assertThat(saved.user).isEqualTo("alessio")
    assertThat(saved.password).isEqualTo("una-password-lunga-12")
    val account = f.env.accounts.saved.single()
    assertThat(account.user).isEqualTo("alessio")
    assertThat(account.credentialSlot).isEqualTo("nas-primary")
    assertThat(f.store.snapshot.nasUser).isEqualTo("alessio")
    // La password non e' nello stato che si salva.
    assertThat(OnboardingSnapshotCodec.encode(f.store.snapshot)).doesNotContain("una-password")
  }

  // --- Passo 4 -----------------------------------------------------------------------------------------------

  private suspend fun OnboardingFixture.atAreasStep(): DefaultOnboardingCoordinator {
    val c = coordinator()
    runFounderUntil(c, OnboardingStep.AREAS)
    return c
  }

  @Test
  fun leAreeProposteSonoLeShareProvateConLoStatoDellaFamiglia() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.atAreasStep()
    f.env.probe.access = f.env.probe.access + mapOf(
      "Foto" to NasResult.Ok(ShareAccess.READ_ONLY),
      "Chiusa" to NasResult.Ok(ShareAccess.DENIED),
      "Timeout" to NasResult.Err(NasError.Timeout),
    )
    f.env.nas.share("Foto").put(NasLayout.registryFile(1), "x".repeat(10).toByteArray())

    val areas = c.proposeAreas(extra = listOf("Foto", "Chiusa", "alessio", "NAS", "..", "con barra/", "Timeout"))
    assertThat(areas.map { it.share }).containsExactly("alessio", "Public", "NAS", "Foto", "Chiusa", "Timeout").inOrder()
    val byName = areas.associateBy { it.share }
    assertThat(byName.getValue("Public").isPublic).isTrue()
    assertThat(byName.getValue("alessio").isPublic).isNull()
    assertThat(byName.getValue("alessio").access).isEqualTo(NasResult.Ok(ShareAccess.READ_WRITE))
    assertThat(byName.getValue("Foto").hasFamily).isTrue()
    assertThat(byName.getValue("NAS").hasFamily).isFalse()
    assertThat(byName.getValue("Chiusa").hasFamily).isFalse()
    assertThat(byName.getValue("Timeout").access).isEqualTo(NasResult.Err(NasError.Timeout))
  }

  @Test
  fun leAreeBuoneVannoAvantiEUnaShareScoperta_siAvvisaComeSoft() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.atAreasStep()
    f.env.probe.access = f.env.probe.access + ("Archivio" to NasResult.Ok(ShareAccess.READ_ONLY))
    c.proposeAreas(extra = listOf("Archivio"))
    val result = expectAdvanced(c.submit(StepInput.Areas("NAS", "alessio")))
    // L'account apre anche una share che non e' ne' sua ne' scelta: puo' essere privata di un altro.
    assertThat(result.warnings).containsExactly(OnboardingWarning.ACCOUNT_SEES_OTHER_PRIVATE_SHARES)
    assertThat(result.next).isEqualTo(OnboardingStep.FOLDERS)
    assertThat(f.store.snapshot.commonShare).isEqualTo("NAS")
    assertThat(f.store.snapshot.privateShare).isEqualTo("alessio")
  }

  @Test
  fun unaSharePubblicaComeAreaComuneAvvisa() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.atAreasStep()
    val result = expectAdvanced(c.submit(StepInput.Areas("Public", "alessio")))
    assertThat(result.warnings).contains(OnboardingWarning.SHARE_IS_PUBLIC)
  }

  @Test
  fun nessunaShareAccessibileELAreaComuneNonScrivibile_E_O2_E_O3() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.atAreasStep()
    fun set(vararg pairs: Pair<String, NasResult<ShareAccess>>) {
      f.env.probe.access = mapOf(*pairs)
    }
    // E-O2: ne' l'area comune ne' la privata si aprono.
    set("NAS" to NasResult.Ok(ShareAccess.DENIED), "alessio" to NasResult.Ok(ShareAccess.MISSING))
    assertThat(c.submit(StepInput.Areas("NAS", "alessio"))).isEqualTo(problem(OnboardingProblem.NO_ACCESSIBLE_SHARE))
    // E-O3: l'area comune manca, e' vietata o e' in sola lettura. L'app non crea share.
    for (access in listOf(ShareAccess.MISSING, ShareAccess.DENIED, ShareAccess.READ_ONLY)) {
      set("NAS" to NasResult.Ok(access), "alessio" to NasResult.Ok(ShareAccess.READ_WRITE))
      assertThat(c.submit(StepInput.Areas("NAS", "alessio"))).isEqualTo(problem(OnboardingProblem.COMMON_AREA_NOT_WRITABLE))
    }
    // L'area privata che manca o non si scrive.
    set("NAS" to NasResult.Ok(ShareAccess.READ_WRITE), "alessio" to NasResult.Ok(ShareAccess.DENIED))
    assertThat(c.submit(StepInput.Areas("NAS", "alessio"))).isEqualTo(problem(OnboardingProblem.NO_ACCESSIBLE_SHARE))
    set("NAS" to NasResult.Ok(ShareAccess.READ_WRITE), "alessio" to NasResult.Ok(ShareAccess.READ_ONLY))
    assertThat(c.submit(StepInput.Areas("NAS", "alessio"))).isEqualTo(problem(OnboardingProblem.NAS_WRITE_FAILED))
    // Il NAS che non risponde non e' una share assente.
    set("NAS" to NasResult.Err(NasError.Timeout), "alessio" to NasResult.Ok(ShareAccess.READ_WRITE))
    assertThat(c.submit(StepInput.Areas("NAS", "alessio"))).isEqualTo(problem(OnboardingProblem.NAS_NOT_FOUND))
    // Nomi che non sono share, o la stessa share due volte.
    assertThat(c.submit(StepInput.Areas("..", "alessio"))).isEqualTo(problem(OnboardingProblem.STEP_NOT_AVAILABLE))
    assertThat(c.submit(StepInput.Areas("NAS", "nas"))).isEqualTo(problem(OnboardingProblem.STEP_NOT_AVAILABLE))
    assertThat(f.current).isEqualTo(OnboardingStep.AREAS)
  }

  @Test
  fun doveCEGiaUnaFamigliaChiLaCreaNonLaSovrascrive() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.atAreasStep()
    f.env.nas.share("NAS").put(NasLayout.registryFile(1), ByteArray(300) { 7 })
    assertThat(c.submit(StepInput.Areas("NAS", "alessio"))).isEqualTo(problem(OnboardingProblem.FAMILY_ALREADY_EXISTS))
    assertThat(f.current).isEqualTo(OnboardingStep.AREAS)
    // Chi recupera l'admin, invece, si aspetta di trovarla.
    val g = OnboardingFixture(this)
    g.env.nas.share("NAS").put(NasLayout.registryFile(1), ByteArray(300) { 7 })
    val d = g.coordinator()
    expectAdvanced(d.submit(StepInput.ChoosePath(OnboardingPath.RECOVER_ADMIN)))
    expectAdvanced(d.submit(StepInput.ConfirmHome(true)))
    expectAdvanced(d.submit(StepInput.NasAddressChosen(g.env.address)))
    expectAdvanced(d.submit(credentials()))
    assertThat(g.current).isEqualTo(OnboardingStep.AREAS)
    expectAdvanced(d.submit(StepInput.Areas("NAS", "alessio")))
    assertThat(g.current).isEqualTo(OnboardingStep.ROLES)
  }

  // --- Passo 5 ----------------------------------------------------------------------------------------------

  @Test
  fun leCartelleDaEscludereSiValidanoESiPossonoSaltare() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.coordinator()
    f.runFounderUntil(c, OnboardingStep.FOLDERS)
    assertThat(c.proposeFolders()).isEqualTo(f.survey.proposal)
    for (bad in listOf("..", "a/../b", "/assoluto", "C:\\x", ".mycloudgallery/tmp", ".MyCloudGallery", "con\\barra")) {
      assertWithMessage(bad).that(c.submit(StepInput.Folders(setOf(bad)))).isEqualTo(problem(OnboardingProblem.STEP_NOT_AVAILABLE))
    }
    assertThat(c.submit(StepInput.Folders((1..300).map { "cartella$it" }.toSet()))).isEqualTo(problem(OnboardingProblem.STEP_NOT_AVAILABLE))
    // Saltato: finisce in "Da completare" e si riprende.
    assertThat(expectAdvanced(c.skip(OnboardingStep.FOLDERS)).next).isEqualTo(OnboardingStep.BACKUP_SOURCES)
    assertThat(f.store.snapshot.progress.pending).containsExactly(OnboardingStep.FOLDERS)
    f.runFounderUntil(c, OnboardingStep.SUMMARY)
    assertThat(expectAdvanced(c.resume(OnboardingStep.FOLDERS)).next).isEqualTo(OnboardingStep.FOLDERS)
    val done = expectAdvanced(c.submit(StepInput.Folders(setOf("Musica"))))
    assertThat(done.next).isNull() // ripreso da "Da completare": finito quello, non c'e' altro
    assertThat(f.store.snapshot.progress.pending).isEmpty()
    assertThat(f.store.snapshot.excluded).containsExactly("Musica")
    assertThat(f.current).isEqualTo(OnboardingStep.SUMMARY)
  }

  @Test
  fun proposeFoldersNonFaNienteSenzaAreaComune() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    assertThat(f.coordinator().proposeFolders().excluded).isEmpty()
  }

  // --- Passi 6-11 --------------------------------------------------------------------------------------------

  private suspend fun OnboardingFixture.atLocalSteps(until: OnboardingStep): DefaultOnboardingCoordinator {
    val c = coordinator()
    runFounderUntil(c, until)
    return c
  }

  @Test
  fun sorgentiERuoliSiSalvanoESiConsegnanoAChiLeApplica() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.atLocalSteps(OnboardingStep.BACKUP_SOURCES)
    expectAdvanced(c.submit(StepInput.BackupSources(camera = false, screenshots = true)))
    expectAdvanced(c.submit(StepInput.Roles(indexer = false, preparer = true)))
    val choices = f.store.snapshot.choices
    assertThat(choices.camera).isFalse()
    assertThat(choices.screenshots).isTrue()
    assertThat(choices.indexer).isFalse()
    assertThat(choices.preparer).isTrue()
    assertThat(f.env.choices.applied.map { it.preparer }).containsExactly(false, true).inOrder()
  }

  @Test
  fun ilPresetVaNelleRegoleELaFasciaNotteEQuellaDelNasSempreAcceso() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.atLocalSteps(OnboardingStep.PRESET)
    expectAdvanced(c.submit(StepInput.PresetChoice(Preset.WIFI_ALWAYS, PreviewQuality.STANDARD)))
    val settings = f.env.rules.current()
    assertThat(settings.preset).isEqualTo(Preset.WIFI_ALWAYS)
    assertThat(settings.nightWindow).isEqualTo(NightWindows.DEFAULT)
    // Qualita' "Standard": le anteprime ad alta qualita' sono spente.
    assertThat(settings.disabledActivities).contains(BackgroundActivity.HQ_PREVIEWS)
    assertThat(f.store.snapshot.choices.preset).isEqualTo(Preset.WIFI_ALWAYS)

    val g = OnboardingFixture(this)
    val d = g.atLocalSteps(OnboardingStep.PRESET)
    expectAdvanced(d.submit(StepInput.PresetChoice(Preset.MAXIMUM, PreviewQuality.HIGH)))
    assertThat(g.env.rules.current().disabledActivities).doesNotContain(BackgroundActivity.HQ_PREVIEWS)
    assertThat(g.store.snapshot.choices.quality).isEqualTo(PreviewQuality.HIGH)
  }

  @Test
  fun iPermessiNegatiNonBloccanoMaRestanoDaCompletare_E_O4() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.atLocalSteps(OnboardingStep.PERMISSIONS)
    f.env.health.health = BackgroundHealth.UNKNOWN.copy(
      permissions = mapOf(
        HealthPermission.NOTIFICATIONS to PermissionGrant.GRANTED,
        HealthPermission.MEDIA_IMAGES to PermissionGrant.DENIED,
        HealthPermission.MEDIA_PARTIAL to PermissionGrant.GRANTED,
        HealthPermission.CAMERA to PermissionGrant.DENIED,
      ),
    )
    val result = expectAdvanced(c.submit(StepInput.PermissionsReviewed))
    assertThat(result.next).isEqualTo(OnboardingStep.BATTERY)
    assertThat(f.store.snapshot.progress.pending).containsExactly(OnboardingStep.PERMISSIONS)
  }

  @Test
  fun iPermessiConcessiCompletanoIlPassoEIlBloccoSchermoMancanteAvvisa_E_S3() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.atLocalSteps(OnboardingStep.PERMISSIONS)
    f.env.health.health = BackgroundHealth.UNKNOWN.copy(
      permissions = mapOf(HealthPermission.NOTIFICATIONS to PermissionGrant.GRANTED, HealthPermission.CAMERA to PermissionGrant.DENIED),
    )
    f.env.screenLock = ScreenLockStatus.NOT_SECURE
    val result = expectAdvanced(c.submit(StepInput.PermissionsReviewed))
    assertThat(result.warnings).containsExactly(OnboardingWarning.NO_SCREEN_LOCK)
    assertThat(f.store.snapshot.progress.pending).isEmpty()
    // Se Android non lo sa dire non si avvisa: un avviso sbagliato insegna a ignorare gli avvisi.
    val g = OnboardingFixture(this)
    g.env.screenLock = ScreenLockStatus.UNKNOWN
    val d = g.atLocalSteps(OnboardingStep.PERMISSIONS)
    assertThat(expectAdvanced(d.submit(StepInput.PermissionsReviewed)).warnings).isEmpty()
  }

  @Test
  fun laBatteriaSiVerificaEQuandoNonEGiustaResta_in_DaCompletare_E_P2() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.atLocalSteps(OnboardingStep.BATTERY)
    f.env.health.health = BackgroundHealth.UNKNOWN.copy(ignoringBatteryOpt = false)
    // L'utente e' uscito, ha guardato le impostazioni e torna (E-O9, E-P15): non ha cambiato niente.
    assertThat(expectAdvanced(c.submit(StepInput.BatteryReviewed)).next).isEqualTo(OnboardingStep.VPN)
    assertThat(f.store.snapshot.progress.pending).containsExactly(OnboardingStep.BATTERY)

    f.runFounderUntil(c, OnboardingStep.SUMMARY)
    f.env.health.health = BackgroundHealth.UNKNOWN.copy(ignoringBatteryOpt = true)
    expectAdvanced(c.resume(OnboardingStep.BATTERY))
    expectAdvanced(c.submit(StepInput.BatteryReviewed))
    assertThat(f.store.snapshot.progress.pending).isEmpty()
    assertThat(f.store.snapshot.progress.completed).contains(OnboardingStep.BATTERY)
  }

  @Test
  fun laBatteriaCheAndroidNonSaDireVaCometFatta() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.atLocalSteps(OnboardingStep.BATTERY)
    f.env.health.health = BackgroundHealth.UNKNOWN // ignoringBatteryOpt == null
    expectAdvanced(c.submit(StepInput.BatteryReviewed))
    assertThat(f.store.snapshot.progress.pending).isEmpty()
  }

  @Test
  fun laVpnSiPuoSaltareEIlNomeDelTunnelSiValida() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.atLocalSteps(OnboardingStep.VPN)
    for (bad in listOf("", "   ", "nome con spazi", "nome-troppo-lungo-per-wireguard", "../x", "casa/vpn")) {
      assertWithMessage(bad).that(c.submit(StepInput.VpnSetup(remoteControl = true, tunnelName = bad)))
        .isEqualTo(problem(OnboardingProblem.STEP_NOT_AVAILABLE))
    }
    assertThat(c.submit(StepInput.VpnSetup(remoteControl = true, tunnelName = null))).isEqualTo(problem(OnboardingProblem.STEP_NOT_AVAILABLE))
    expectAdvanced(c.submit(StepInput.VpnSetup(remoteControl = true, tunnelName = "casa_vpn")))
    assertThat(f.store.snapshot.choices.remoteControl).isTrue()
    assertThat(f.store.snapshot.choices.tunnelName).isEqualTo("casa_vpn")
    assertThat(f.env.choices.applied.last().tunnelName).isEqualTo("casa_vpn")

    // Senza controllo remoto il nome non conta e non si tiene.
    val g = OnboardingFixture(this)
    val d = g.atLocalSteps(OnboardingStep.VPN)
    expectAdvanced(d.submit(StepInput.VpnSetup(remoteControl = false, tunnelName = "avanzo")))
    assertThat(g.store.snapshot.choices.tunnelName).isNull()
    // Saltato del tutto.
    val h = OnboardingFixture(this)
    val e = h.atLocalSteps(OnboardingStep.VPN)
    assertThat(expectAdvanced(e.skip(OnboardingStep.VPN)).next).isEqualTo(OnboardingStep.FAMILY_KEY)
    assertThat(h.store.snapshot.progress.pending).containsExactly(OnboardingStep.VPN)
  }

  // --- Navigazione --------------------------------------------------------------------------------------------

  @Test
  fun sioSaltaSoloIlPassoCorrenteESoloSeSiPuo() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.coordinator()
    f.runFounderUntil(c, OnboardingStep.AREAS)
    assertThat(c.skip(OnboardingStep.AREAS)).isEqualTo(problem(OnboardingProblem.STEP_NOT_AVAILABLE)) // non saltabile
    assertThat(c.skip(OnboardingStep.FOLDERS)).isEqualTo(problem(OnboardingProblem.STEP_NOT_AVAILABLE)) // non e' il corrente
    assertThat(c.resume(OnboardingStep.FOLDERS)).isEqualTo(problem(OnboardingProblem.STEP_NOT_AVAILABLE)) // non e' stato saltato
  }

  @Test
  fun indietroTornaAlPassoPrecedenteESaltaQuelliGiaFattiAlRitorno() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.coordinator()
    f.runFounderUntil(c, OnboardingStep.ROLES)
    c.back()
    c.back()
    assertThat(f.current).isEqualTo(OnboardingStep.FOLDERS)
    // Rifare il passo 5: il prossimo non e' il 6 ma il primo non ancora fatto (il 7).
    val result = expectAdvanced(c.submit(StepInput.Folders(setOf("Altro"))))
    assertThat(result.next).isEqualTo(OnboardingStep.ROLES)
    assertThat(f.store.snapshot.progress.completed).contains(OnboardingStep.BACKUP_SOURCES)
    // Non si torna prima del benvenuto, e dopo la fine non si torna proprio.
    f.runFounderUntil(c, OnboardingStep.SUMMARY)
    expectAdvanced(c.submit(StepInput.Finish))
    c.back()
    assertThat(f.current).isEqualTo(OnboardingStep.SUMMARY)
    val fresh = OnboardingFixture(this)
    val d = fresh.coordinator()
    d.back()
    assertThat(fresh.current).isEqualTo(OnboardingStep.WELCOME)
  }

  @Test
  fun fondataLaFamigliaIndirizzoAccountEAreeNonSiRifanno() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.coordinator()
    f.runFounderUntil(c, OnboardingStep.SUMMARY)
    val runs = f.env.tester.runs.size
    // Tornando indietro si rivedono i passi, ma la configurazione firmata non si cambia.
    while (f.current != OnboardingStep.AREAS) c.back()
    assertThat(c.submit(StepInput.Areas("Public", "alessio"))).isEqualTo(problem(OnboardingProblem.STEP_NOT_AVAILABLE))
    c.back()
    val password = credentials()
    assertThat(c.submit(password)).isEqualTo(problem(OnboardingProblem.STEP_NOT_AVAILABLE))
    assertThat(password.password.all { it == ' ' }).isTrue()
    c.back()
    assertThat(f.current).isEqualTo(OnboardingStep.NAS_ADDRESS)
    assertThat(c.submit(StepInput.ConfirmHome(true))).isEqualTo(problem(OnboardingProblem.STEP_NOT_AVAILABLE))
    assertThat(c.submit(StepInput.NasAddressChosen(f.env.address))).isEqualTo(problem(OnboardingProblem.STEP_NOT_AVAILABLE))
    assertThat(f.env.tester.runs).hasSize(runs)
    assertThat(f.store.snapshot.commonShare).isEqualTo("NAS")
  }

  @Test
  fun lAvanzamentoNonMostraMaiUtenteIndirizziONomiDiShare() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    f.runFounderUntil(f.coordinator(), OnboardingStep.SUMMARY)
    val text = f.store.snapshot.toString() + f.store.snapshot.foundation + f.store.snapshot.nas + f.store.snapshot.join
    for (secret in listOf("alessio", "192.168", "NAS", "Musica", TEST_GUID, "fd12")) {
      assertWithMessage(secret).that(text).doesNotContain(secret)
    }
  }

  @Test
  fun iPassiDelRecuperoDellAdminNonSiFinisconoSenzaChiaveRecuperata() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.coordinator()
    expectAdvanced(c.submit(StepInput.ChoosePath(OnboardingPath.SECOND_ADMIN)))
    expectAdvanced(c.submit(StepInput.ConfirmHome(true)))
    expectAdvanced(c.submit(StepInput.NasAddressChosen(f.env.address)))
    expectAdvanced(c.submit(credentials()))
    expectAdvanced(c.submit(StepInput.Areas("NAS", "alessio")))
    do {
      val next = expectAdvanced(f.doStep(c)).next
    } while (next != OnboardingStep.SUMMARY)
    // Finche' il registro non dice "approvato, admin" (lo fa AdminRecovery di P3) non c'e' niente da finire.
    assertThat(c.submit(StepInput.Finish)).isEqualTo(problem(OnboardingProblem.STEP_NOT_AVAILABLE))
    f.env.registry.status.value = dev.pampa.mycloudgallery.core.family.contract.TrustStatus.Approved(admin = true)
    expectAdvanced(c.submit(StepInput.Finish))
    assertThat(f.store.snapshot.finished).isTrue()
  }

  @Test
  fun inUnIngressoConInvitoIndirizzoEAccountSonoDiJoinFlow() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.coordinator()
    expectAdvanced(c.submit(StepInput.ChoosePath(OnboardingPath.JOIN_QR)))
    assertThat(c.submit(StepInput.ConfirmHome(true))).isEqualTo(problem(OnboardingProblem.STEP_NOT_AVAILABLE))
    assertThat(c.submit(StepInput.NasAddressChosen(f.env.address))).isEqualTo(problem(OnboardingProblem.STEP_NOT_AVAILABLE))
    assertThat(f.env.tester.runs).isEmpty()
    // Senza la richiesta inviata non c'e' niente da finire.
    assertThat(f.current).isEqualTo(OnboardingStep.NAS_ADDRESS)
  }
}
