package dev.pampa.mycloudgallery.core.family.invite

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.FamilyInvite
import dev.pampa.mycloudgallery.core.family.contract.JoinStep
import dev.pampa.mycloudgallery.core.family.contract.OnboardingPath
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProblem
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.OnboardingWarning
import dev.pampa.mycloudgallery.core.family.contract.StaleReason
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.family.contract.StepInput
import dev.pampa.mycloudgallery.core.family.onboarding.DefaultOnboardingCoordinator
import dev.pampa.mycloudgallery.core.family.support.NAS_NOON
import dev.pampa.mycloudgallery.core.family.support.OnboardingFixture
import dev.pampa.mycloudgallery.core.family.support.Reports
import dev.pampa.mycloudgallery.core.family.support.TEST_GUID
import dev.pampa.mycloudgallery.core.family.support.TEST_IP
import dev.pampa.mycloudgallery.core.family.support.TEST_ULA
import dev.pampa.mycloudgallery.core.family.support.TestFamily
import dev.pampa.mycloudgallery.core.family.testing.FakeRegistryService
import dev.pampa.mycloudgallery.core.family.support.expectAdvanced
import dev.pampa.mycloudgallery.core.nas.contract.AuthBlockReason
import dev.pampa.mycloudgallery.core.nas.contract.BreakerState
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NegotiationFacts
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.SmbDialect
import dev.pampa.mycloudgallery.core.nas.contract.Stage
import dev.pampa.mycloudgallery.core.network.contract.DenyReason
import dev.pampa.mycloudgallery.core.network.contract.LocalNetworkAccess
import dev.pampa.mycloudgallery.core.network.testing.Snapshots
import dev.pampa.mycloudgallery.core.security.credentials.SaveResult
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.acceptedOrNull
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKey
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.registry.ApprovalCodes
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.core.trust.registry.RegistrationRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * L'ingresso con invito (QR e manuale): controllo casa con l'ULA del QR, GUID uguale a quello dell'invito,
 * scadenza con l'ora del NAS, un solo tentativo di password, registro con l'ancora del QR (E-O5), configurazione
 * firmata che vince sul QR (E-O6), richiesta idempotente e chiave nuova per chi rientra (E-O7, E-S2).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultJoinFlowTest {

  private class Fixture(
    scope: TestScope,
    withFamily: Boolean = true,
    familyGuid: String? = TEST_GUID,
    signedUla: List<String> = listOf(TEST_ULA),
  ) {
    val f = OnboardingFixture(scope)
    val env = f.env
    val store = f.store
    val family = TestFamily(env.nas.share(if (withFamily) "NAS" else "Altra"), nasGuid = familyGuid, homeUla = signedUla)
    val join = DefaultJoinFlow(store, env.ports())
    val coordinator: DefaultOnboardingCoordinator = f.coordinator()
    val password: CharArray get() = "password-di-mamma-1".toCharArray()

    suspend fun choose(path: OnboardingPath = OnboardingPath.JOIN_QR) {
      expectAdvanced(coordinator.submit(StepInput.ChoosePath(path)))
    }

    /** start + authenticate con tutto buono. */
    suspend fun toNeedsName(invite: FamilyInvite = family.invite()): JoinStep.NeedsName {
      choose()
      assertThat(join.start(invite)).isEqualTo(JoinStep.NeedsPassword)
      val step = join.authenticate(password)
      assertThat(step).isInstanceOf(JoinStep.NeedsName::class.java)
      return step as JoinStep.NeedsName
    }
  }

  private fun problem(p: OnboardingProblem) = JoinStep.Failed(p)

  @Test
  fun unIngressoConQrVaDallInvitoAllaRichiestaInAttesa() = runTest(UnconfinedTestDispatcher()) {
    val j = Fixture(this)
    val env = j.env
    val invite = j.family.invite()
    j.choose()

    // 1. Controllo casa, TCP, NEGOTIATE, GUID: nessuna password e nessuna credenziale salvata.
    assertThat(j.join.start(invite)).isEqualTo(JoinStep.NeedsPassword)
    val first = env.tester.runs.single()
    assertThat(first.hadCredentials).isFalse()
    assertThat(first.stages).containsExactly(Stage.ROUTE, Stage.TCP, Stage.NEGOTIATE, Stage.GUID)
    assertThat(first.target.expectedGuid).isEqualTo(TEST_GUID)
    assertThat(first.target.candidate).isEqualTo(env.candidate)
    assertThat(first.target.user).isNull()
    assertThat(env.fingerprints.fingerprint.value!!.ulaPrefixes).containsExactly(TEST_ULA)
    assertThat(env.credentials.saved).isEmpty()
    assertThat(j.store.snapshot.join!!.registered).isFalse()
    assertThat(j.store.snapshot.progress.current).isEqualTo(OnboardingStep.NAS_ADDRESS) // ancora nessun passo "fatto"

    // 2. La password: un solo tentativo, poi il registro firmato letto con l'ancora del QR.
    val password = "password-di-mamma-1".toCharArray()
    val generation = env.breaker.state.value.generation
    val needsName = j.join.authenticate(password)
    assertThat(needsName).isEqualTo(JoinStep.NeedsName("Tablet profilo Mamma"))
    assertThat(password.all { it == '\u0000' }).isTrue()
    val auth = env.tester.runs.last()
    assertThat(auth.hadCredentials).isTrue()
    assertThat(auth.target.user).isEqualTo("mamma")
    assertThat(auth.target.expectedGuid).isEqualTo(TEST_GUID)
    assertThat(auth.target.candidate).isNull() // "casa" e' gia' registrata: il percorso lo decide il cancello
    assertThat(env.credentials.saved.single().password).isEqualTo("password-di-mamma-1")
    assertThat(env.accounts.saved.single().user).isEqualTo("mamma")
    assertThat(env.accounts.saved.single().pinnedGuid).isEqualTo(TEST_GUID)
    assertThat(env.breaker.state.value.generation).isEqualTo(generation + 1)

    // 3. Il nome, la chiave, la richiesta, il codice e il QR della chiave.
    val step = j.join.register("Tablet di Mamma")
    assertThat(step).isInstanceOf(JoinStep.AwaitingApproval::class.java)
    step as JoinStep.AwaitingApproval
    val key = env.deviceKeys.deviceSigner().publicKey
    val deviceId = TrustIds.deviceIdOf(key)
    assertThat(step.code).isEqualTo(ApprovalCodes.of(key))
    val qr = DeviceKeyCodec.decode(step.deviceKeyQr).acceptedOrNull()!!
    assertThat(qr.familyId).isEqualTo(j.family.familyId)
    assertThat(qr.deviceId).isEqualTo(deviceId)
    assertThat(qr.toPublicKey()).isEqualTo(key)

    val requestBytes = (env.nas.share("NAS").read(NasLayout.requestFile(deviceId), 10_000) as NasRead.Data).bytes
    val request = (RegistrationRequest.verify(requestBytes, j.family.familyId, deviceId) as Verdict.Accepted).value
    assertThat(request.name).isEqualTo("Tablet di Mamma")
    assertThat(request.nasUser).isEqualTo("mamma")
    assertThat(request.model).isEqualTo("SM-S931B")
    assertThat(request.requestedAt).isEqualTo(env.nasNow)

    // Lo stato fidato si e' salvato e l'avanzamento dice "fatto" per indirizzo e account insieme.
    assertThat(env.trustState.snapshot!!.familyId).isEqualTo(j.family.familyId)
    assertThat(env.trustState.snapshot!!.familyKeyFingerprint).isEqualTo(j.family.fingerprint)
    assertThat(env.trustState.snapshot!!.registryBytes).isEqualTo(j.family.state.signedBytes)
    val snapshot = j.store.snapshot
    assertThat(snapshot.join!!.registered).isTrue()
    assertThat(snapshot.join!!.requestedAt).isEqualTo(env.nasNow)
    assertThat(snapshot.progress.completed).containsAtLeast(OnboardingStep.NAS_ADDRESS, OnboardingStep.ACCOUNT)
    assertThat(snapshot.progress.current).isEqualTo(OnboardingStep.ROLES)
    assertThat(snapshot.choices.preset.name).isEqualTo("BALANCED") // il preset dell'invito e' il valore di partenza
    assertThat(env.registry.refreshes).isNotEmpty()

    // I passi locali che restano, e la fine.
    do {
      val result = expectAdvanced(
        when (j.store.snapshot.progress.current) {
          OnboardingStep.ROLES -> j.coordinator.submit(StepInput.Roles(indexer = false, preparer = false))
          OnboardingStep.PRESET -> j.coordinator.submit(StepInput.PresetChoice(dev.pampa.mycloudgallery.core.rules.Preset.BALANCED, dev.pampa.mycloudgallery.core.family.contract.PreviewQuality.COMPACT))
          OnboardingStep.PERMISSIONS -> j.coordinator.submit(StepInput.PermissionsReviewed)
          OnboardingStep.BATTERY -> j.coordinator.submit(StepInput.BatteryReviewed)
          OnboardingStep.VPN -> j.coordinator.submit(StepInput.VpnSetup(false, null))
          else -> j.coordinator.submit(StepInput.Finish)
        },
      )
    } while (result.next != null)
    assertThat(j.store.snapshot.finished).isTrue()
  }

  // --- Casa ---------------------------------------------------------------------------------------------

  @Test
  fun unWifiConUnAltroUlaNonECasaENonParteNessunaConnessione() = runTest(UnconfinedTestDispatcher()) {
    val j = Fixture(this)
    j.choose()
    j.env.capture.candidate = j.env.candidate.copy(ulaPrefixes = setOf("fd99:aaaa:bbbb::/48"))
    // Il cancello nega sulla rete corrente (E-S10): senza il candidato dell'onboarding non c'e' percorso.
    j.env.tester.handler = { target, _, stages ->
      if (target.candidate == null) Reports.failed(stages, Stage.ROUTE, NasError.NotAllowedHere(DenyReason.UNTRUSTED_NETWORK)) else Reports.ok(stages)
    }
    assertThat(j.join.start(j.family.invite())).isEqualTo(problem(OnboardingProblem.NOT_AT_HOME))
    // La rete estranea non e' mai stata passata come "casa" al test, e l'impronta provvisoria e' sparita.
    assertThat(j.env.tester.runs.single().target.candidate).isNull()
    assertThat(j.env.fingerprints.fingerprint.value).isNull()
    assertThat(j.env.credentials.saved).isEmpty()
    // Senza una sessione di ingresso la password non va da nessuna parte.
    val password = j.password
    assertThat(j.join.authenticate(password)).isEqualTo(problem(OnboardingProblem.STEP_NOT_AVAILABLE))
    assertThat(password.all { it == '\u0000' }).isTrue()
    assertThat(j.env.tester.runs).hasSize(1)
  }

  @Test
  fun senzaWifiOSenzaPermessoDiReteLocaleNonCEPercorso() = runTest(UnconfinedTestDispatcher()) {
    val j = Fixture(this)
    j.choose()
    j.env.capture.candidate = null
    j.env.tester.handler = { _, _, stages -> Reports.failed(stages, Stage.ROUTE, NasError.NotAllowedHere(DenyReason.OFFLINE)) }
    assertThat(j.join.start(j.family.invite())).isEqualTo(problem(OnboardingProblem.NOT_AT_HOME))

    j.env.localNetwork.access = LocalNetworkAccess.DENIED
    val runs = j.env.tester.runs.size
    assertThat(j.join.start(j.family.invite())).isEqualTo(problem(OnboardingProblem.LOCAL_NETWORK_DENIED))
    assertThat(j.env.tester.runs).hasSize(runs)
  }

  @Test
  fun fuoriCasaConLaVpnDiCasaSiEntraLoStesso() = runTest(UnconfinedTestDispatcher()) {
    val j = Fixture(this)
    j.choose()
    j.env.capture.candidate = null
    j.env.network.current = Snapshots.awayVpnMobile()
    // Il cancello riconosce la VPN dall'impronta, che il dispositivo nuovo ancora non ha: quella del QR vale in attesa.
    j.env.tester.handler = { target, _, stages ->
      if (target.candidate == null && j.env.fingerprints.fingerprint.value == null) {
        Reports.failed(stages, Stage.ROUTE, NasError.NotAllowedHere(DenyReason.NOT_CONFIGURED))
      } else {
        Reports.ok(stages)
      }
    }
    assertThat(j.join.start(j.family.invite())).isEqualTo(JoinStep.NeedsPassword)
    assertThat(j.env.tester.runs.single().target.candidate).isNull()
    assertThat(j.env.fingerprints.fingerprint.value!!.ulaPrefixes).containsExactly(TEST_ULA)
  }

  @Test
  fun unaVpnDiTerziNonVaBene() = runTest(UnconfinedTestDispatcher()) {
    val j = Fixture(this)
    j.choose()
    j.env.capture.candidate = null
    j.env.tester.handler = { _, _, stages -> Reports.failed(stages, Stage.ROUTE, NasError.NotAllowedHere(DenyReason.FOREIGN_VPN)) }
    assertThat(j.join.start(j.family.invite())).isEqualTo(problem(OnboardingProblem.VPN_ACTIVE))
    assertThat(j.env.fingerprints.fingerprint.value).isNull()
  }

  // --- GUID e scadenza ----------------------------------------------------------------------------------

  @Test
  fun ilGuidDelNasDeveEssereQuelloDelQrAltrimentiNonCePassword() = runTest(UnconfinedTestDispatcher()) {
    val j = Fixture(this)
    j.choose()
    // Il test dice che e' tutto a posto ma il GUID e' un altro: lo controlla anche l'ingresso, perche' e' l'unica conferma.
    j.env.tester.handler = { _, _, stages -> Reports.ok(stages, NegotiationFacts(SmbDialect.SMB_3_1_1, true, "guid-di-un-altro-nas", 0L)) }
    assertThat(j.join.start(j.family.invite())).isEqualTo(problem(OnboardingProblem.IDENTITY_MISMATCH))
    j.env.tester.handler = { _, _, stages -> Reports.failed(stages, Stage.GUID, NasError.IdentityMismatch) }
    assertThat(j.join.start(j.family.invite())).isEqualTo(problem(OnboardingProblem.IDENTITY_MISMATCH))
    // Nessuna casa registrata, nessuna password partita.
    assertThat(j.env.fingerprints.fingerprint.value).isNull()
    assertThat(j.env.tester.authAttempts).isEqualTo(0)
    assertThat(j.env.credentials.saved).isEmpty()
  }

  @Test
  fun unNasSenzaCifraturaOConUnDialettoVecchioNonSiUsa() = runTest(UnconfinedTestDispatcher()) {
    val j = Fixture(this)
    j.choose()
    j.env.tester.handler = { _, _, stages -> Reports.ok(stages, NegotiationFacts(SmbDialect.SMB_3_0_2, true, TEST_GUID, 0L)) }
    assertThat(j.join.start(j.family.invite())).isEqualTo(problem(OnboardingProblem.NAS_INSECURE))
    j.env.tester.handler = { _, _, stages -> Reports.ok(stages, NegotiationFacts(SmbDialect.SMB_3_1_1, false, TEST_GUID, 0L)) }
    assertThat(j.join.start(j.family.invite())).isEqualTo(problem(OnboardingProblem.NAS_INSECURE))
    j.env.tester.handler = { _, _, stages -> Reports.ok(stages, null) }
    assertThat(j.join.start(j.family.invite())).isEqualTo(problem(OnboardingProblem.NAS_NOT_FOUND))
  }

  @Test
  fun laScadenzaSiGiudicaConLOraDelNasNonConQuellaDelTelefono() = runTest(UnconfinedTestDispatcher()) {
    val j = Fixture(this)
    j.choose()
    assertThat(j.join.start(j.family.invite(expiresAt = NAS_NOON - 1))).isEqualTo(problem(OnboardingProblem.INVITE_EXPIRED))
    // Il telefono pensa che manchi un'ora, ma il NAS e' avanti di due: scaduto.
    j.env.tester.handler = { _, _, stages -> Reports.ok(stages, NegotiationFacts(SmbDialect.SMB_3_1_1, true, TEST_GUID, 2 * 60 * 60 * 1000L)) }
    assertThat(j.join.start(j.family.invite(expiresAt = NAS_NOON + 60 * 60 * 1000L))).isEqualTo(problem(OnboardingProblem.INVITE_EXPIRED))
    // Una scadenza troppo lontana non l'ha scritta la nostra app.
    j.env.tester.handler = { _, _, stages -> Reports.ok(stages) }
    assertThat(j.join.start(j.family.invite(expiresAt = NAS_NOON + 3 * 24 * 60 * 60 * 1000L))).isEqualTo(problem(OnboardingProblem.INVITE_INVALID))
    // E nessuna di queste cose ha lasciato una casa registrata.
    assertThat(j.env.fingerprints.fingerprint.value).isNull()
    assertThat(j.join.start(j.family.invite(expiresAt = NAS_NOON))).isEqualTo(JoinStep.NeedsPassword)
  }

  @Test
  fun unInvitoOstileOUnPercorsoSbagliatoNonPartono() = runTest(UnconfinedTestDispatcher()) {
    val j = Fixture(this)
    // Prima di scegliere il percorso non c'e' un ingresso da fare.
    assertThat(j.join.start(j.family.invite())).isEqualTo(problem(OnboardingProblem.STEP_NOT_AVAILABLE))
    j.choose()
    assertThat(j.join.start(j.family.invite(user = "admin"))).isEqualTo(problem(OnboardingProblem.INVITE_INVALID))
    assertThat(j.join.start(j.family.invite(nasIp = "nas.fritz.box"))).isEqualTo(problem(OnboardingProblem.INVITE_INVALID))
    assertThat(j.join.start(j.family.invite().copy(homeUla = emptyList(), nasGuid = "x"))).isEqualTo(problem(OnboardingProblem.INVITE_INVALID))
    assertThat(j.env.tester.runs).isEmpty()
    // Un invito manuale su un percorso "con QR" (e il contrario) non e' il percorso scelto.
    val manual = ManualInvite.create(j.family.familyId, j.family.fingerprint, TEST_IP, "mamma", "NAS", "mamma", "Tablet").acceptedOrNull()!!
    assertThat(j.join.start(manual)).isEqualTo(problem(OnboardingProblem.STEP_NOT_AVAILABLE))
  }

  @Test
  fun ilNasSpentoOffreContinuaPiuTardi_E_O1() = runTest(UnconfinedTestDispatcher()) {
    val j = Fixture(this)
    j.choose()
    j.env.tester.handler = { _, _, stages -> Reports.failed(stages, Stage.TCP, NasError.Timeout) }
    assertThat(j.join.start(j.family.invite())).isEqualTo(problem(OnboardingProblem.NAS_NOT_FOUND))
    assertThat(j.store.snapshot.deferrable).isTrue()
    // "Continua piu' tardi": i passi locali si fanno, e non si puo' finire senza la richiesta.
    val later = expectAdvanced(j.coordinator.skip(OnboardingStep.NAS_ADDRESS))
    assertThat(later.next).isEqualTo(OnboardingStep.ROLES)
    assertThat(j.store.snapshot.progress.pending).containsExactly(OnboardingStep.NAS_ADDRESS, OnboardingStep.ACCOUNT).inOrder()
  }

  // --- Password -----------------------------------------------------------------------------------------

  @Test
  fun unSoloTentativoDiPasswordELaStessaNonTornaAlNas_E_R8() = runTest(UnconfinedTestDispatcher()) {
    val j = Fixture(this)
    j.choose()
    assertThat(j.join.start(j.family.invite())).isEqualTo(JoinStep.NeedsPassword)
    j.env.tester.handler = { _, creds, stages ->
      if (creds == null) Reports.ok(stages) else Reports.failed(stages, Stage.AUTHENTICATE, NasError.Auth(AuthBlockReason.AUTH_FAILED), Reports.FACTS)
    }
    val wrong = "password-sbagliata-1".toCharArray()
    assertThat(j.join.authenticate(wrong)).isEqualTo(problem(OnboardingProblem.AUTH_FAILED))
    assertThat(wrong.all { it == '\u0000' }).isTrue()
    j.env.breaker.open(AuthBlockReason.AUTH_FAILED)
    assertThat(j.join.authenticate("password-sbagliata-1".toCharArray())).isEqualTo(problem(OnboardingProblem.AUTH_FAILED))
    assertThat(j.join.authenticate(CharArray(0))).isEqualTo(problem(OnboardingProblem.AUTH_FAILED))
    assertThat(j.env.tester.authAttempts).isEqualTo(1)
    assertThat(j.env.credentials.saved).isEmpty()
    assertThat(j.env.accounts.saved).isEmpty()

    // Una password nuova: l'interruttore si richiude e c'e' un altro (unico) tentativo.
    j.env.tester.handler = { _, _, stages -> Reports.ok(stages) }
    assertThat(j.join.authenticate("password-giusta-1".toCharArray())).isInstanceOf(JoinStep.NeedsName::class.java)
    assertThat(j.env.tester.authAttempts).isEqualTo(2)
    assertThat(j.env.breaker.state.value).isInstanceOf(BreakerState.Closed::class.java)
  }

  @Test
  fun ogniProblemaDAccountHaIlSuoMessaggioEUnaAutenticazioneSaltataNonVale() = runTest(UnconfinedTestDispatcher()) {
    val j = Fixture(this)
    j.choose()
    assertThat(j.join.start(j.family.invite())).isEqualTo(JoinStep.NeedsPassword)
    val cases = mapOf(
      AuthBlockReason.ACCOUNT_LOCKED to OnboardingProblem.ACCOUNT_LOCKED,
      AuthBlockReason.ACCOUNT_DISABLED to OnboardingProblem.ACCOUNT_DISABLED,
      AuthBlockReason.PASSWORD_EXPIRED to OnboardingProblem.PASSWORD_EXPIRED,
      AuthBlockReason.ACCOUNT_RESTRICTED to OnboardingProblem.ACCOUNT_RESTRICTED,
    )
    var n = 0
    for ((reason, expected) in cases) {
      j.env.tester.handler = { _, _, stages -> Reports.failed(stages, Stage.AUTHENTICATE, NasError.Auth(reason), Reports.FACTS) }
      assertThat(j.join.authenticate("password-numero-${n++}".toCharArray())).isEqualTo(problem(expected))
    }
    j.env.tester.handler = { _, creds, stages -> if (creds == null) Reports.ok(stages) else Reports.authSkipped(stages) }
    assertThat(j.join.authenticate("password-numero-${n++}".toCharArray())).isEqualTo(problem(OnboardingProblem.AUTH_FAILED))
    j.env.tester.handler = { _, _, stages -> Reports.ok(stages) }
    j.env.credentials.result = SaveResult.HardwareKeyUnavailable
    assertThat(j.join.authenticate("password-numero-${n++}".toCharArray())).isEqualTo(problem(OnboardingProblem.CREDENTIALS_NOT_SAVED))
    assertThat(j.env.accounts.saved).isEmpty()
  }

  // --- Registro firmato ------------------------------------------------------------------------------------

  @Test
  fun unQrDiUnAltraFamigliaNonPortaANulla_E_O5() = runTest(UnconfinedTestDispatcher()) {
    val j = Fixture(this)
    j.choose()
    val wrongFingerprint = j.family.invite(fingerprint = FamilyKey.generate().publicKey.fingerprint)
    assertThat(j.join.start(wrongFingerprint)).isEqualTo(JoinStep.NeedsPassword)
    assertThat(j.join.authenticate(j.password)).isEqualTo(problem(OnboardingProblem.INVITE_OTHER_FAMILY))
    // Nulla resta: ne' password salvate, ne' account, ne' sessione per proseguire.
    assertThat(j.env.credentials.removed).containsExactly("nas-primary")
    assertThat(j.env.accounts.current()).isNull()
    assertThat(j.join.register("Tablet")).isEqualTo(problem(OnboardingProblem.STEP_NOT_AVAILABLE))
    assertThat(j.env.trustState.snapshot).isNull()
    assertThat(j.store.snapshot.join!!.registered).isFalse()

    val wrongId = j.family.invite(familyId = TrustIds.random())
    assertThat(j.join.start(wrongId)).isEqualTo(JoinStep.NeedsPassword)
    assertThat(j.join.authenticate(j.password)).isEqualTo(problem(OnboardingProblem.INVITE_OTHER_FAMILY))
  }

  @Test
  fun unNasSenzaFamigliaEUnQrDiUnaFamigliaCheNonCe_E_O5() = runTest(UnconfinedTestDispatcher()) {
    // L'area comune e' vuota: il QR parla di una famiglia che qui non esiste.
    val j = Fixture(this, withFamily = false)
    j.choose()
    assertThat(j.join.start(j.family.invite())).isEqualTo(JoinStep.NeedsPassword)
    assertThat(j.join.authenticate(j.password)).isEqualTo(problem(OnboardingProblem.INVITE_OTHER_FAMILY))
    assertThat(j.env.credentials.removed).containsExactly("nas-primary")
    assertThat(j.env.accounts.current()).isNull()
  }

  @Test
  fun unRegistroPiuVecchioDiQuelloDelQrEUnRollback() = runTest(UnconfinedTestDispatcher()) {
    val j = Fixture(this)
    j.choose()
    assertThat(j.join.start(j.family.invite(minVersion = 5))).isEqualTo(JoinStep.NeedsPassword)
    assertThat(j.join.authenticate(j.password)).isEqualTo(problem(OnboardingProblem.REGISTRY_UNTRUSTED))
    assertThat(j.env.credentials.removed).containsExactly("nas-primary")
  }

  @Test
  fun unRegistroDiUnAppPiuNuovaChiedeDiAggiornare_E_I3() = runTest(UnconfinedTestDispatcher()) {
    val j = Fixture(this)
    j.choose()
    // Il file del registro dichiara un formato piu' nuovo (quinto byte): non si interpreta.
    val path = NasLayout.registryFile(2)
    val bytes = (j.env.nas.share("NAS").read(NasLayout.registryFile(1), 10_000) as NasRead.Data).bytes.also { it[4] = 9 }
    j.env.nas.share("NAS").put(path, bytes)
    assertThat(j.join.start(j.family.invite())).isEqualTo(JoinStep.NeedsPassword)
    assertThat(j.join.authenticate(j.password)).isEqualTo(problem(OnboardingProblem.NEEDS_APP_UPDATE))
    assertThat(j.env.credentials.removed).containsExactly("nas-primary")
  }

  @Test
  fun laConfigurazioneFirmataVinceSulQr_E_O6() = runTest(UnconfinedTestDispatcher()) {
    val j = Fixture(this)
    j.choose()
    // Il QR e' stato stampato quando il NAS stava a .59; il registro firmato dice .58.
    val invite = j.family.invite(nasIp = "192.168.178.59")
    assertThat(j.join.start(invite)).isEqualTo(JoinStep.NeedsPassword)
    assertThat(j.join.authenticate(j.password)).isInstanceOf(JoinStep.NeedsName::class.java)
    // Dietro il nuovo indirizzo si e' verificato lo stesso NAS (stesso GUID) prima di fidarsene.
    val check = j.env.tester.runs.last()
    assertThat(check.hadCredentials).isFalse()
    assertThat(check.target.address.ip).isEqualTo(TEST_IP)
    assertThat(check.target.expectedGuid).isEqualTo(TEST_GUID)
    assertThat(j.env.accounts.saved.last().address.ip).isEqualTo(TEST_IP)
    assertThat(j.store.snapshot.nas!!.ip).isEqualTo(TEST_IP)
  }

  @Test
  fun seDietroIlNuovoIndirizzoCEUnAltroNasNonSiVa() = runTest(UnconfinedTestDispatcher()) {
    val j = Fixture(this)
    j.choose()
    assertThat(j.join.start(j.family.invite(nasIp = "192.168.178.59"))).isEqualTo(JoinStep.NeedsPassword)
    j.env.tester.handler = { target, creds, stages ->
      if (creds == null && target.address.ip == TEST_IP) Reports.failed(stages, Stage.GUID, NasError.IdentityMismatch) else Reports.ok(stages)
    }
    assertThat(j.join.authenticate(j.password)).isEqualTo(problem(OnboardingProblem.IDENTITY_MISMATCH))
    assertThat(j.env.credentials.removed).containsExactly("nas-primary")
    assertThat(j.env.accounts.current()).isNull()
  }

  @Test
  fun ilGuidFirmatoDallAdminEUnaConfermaIndipendenteDalQr() = runTest(UnconfinedTestDispatcher()) {
    val j = Fixture(this, familyGuid = "11111111-2222-3333-4444-555555555555")
    j.choose()
    // Il GUID del QR e quello della NEGOTIATE coincidono, ma non sono quelli che la famiglia ha firmato.
    assertThat(j.join.start(j.family.invite())).isEqualTo(JoinStep.NeedsPassword)
    assertThat(j.join.authenticate(j.password)).isEqualTo(problem(OnboardingProblem.IDENTITY_MISMATCH))
    assertThat(j.env.credentials.removed).containsExactly("nas-primary")
    assertThat(j.env.accounts.current()).isNull()
    assertThat(j.env.trustState.snapshot).isNull()
  }

  @Test
  fun senzaQrIlGuidSiConfermaConQuelloFirmatoOra() = runTest(UnconfinedTestDispatcher()) {
    val j = Fixture(this, familyGuid = "11111111-2222-3333-4444-555555555555")
    j.choose(OnboardingPath.JOIN_MANUAL)
    expectAdvanced(j.coordinator.submit(StepInput.ConfirmHome(true)))
    val manual = ManualInvite.create(j.family.familyId, j.family.fingerprint, TEST_IP, "mamma", "NAS", "mamma", "Tablet").acceptedOrNull()!!
    assertThat(j.join.start(manual)).isEqualTo(JoinStep.NeedsPassword)
    assertThat(j.join.authenticate(j.password)).isEqualTo(problem(OnboardingProblem.IDENTITY_MISMATCH))

    // Una configurazione firmata senza GUID (nessuna conferma da fare) non ferma l'ingresso.
    val old = Fixture(this, familyGuid = null)
    old.toNeedsName()
    assertThat(old.join.register("Tablet")).isInstanceOf(JoinStep.AwaitingApproval::class.java)
  }

  @Test
  fun iPrefissiUlaFirmatiSiAggiungonoAllaCasa_E_O6() = runTest(UnconfinedTestDispatcher()) {
    val j = Fixture(this, signedUla = listOf(TEST_ULA, "fdd1:2345:6789::/48"))
    j.toNeedsName()
    assertThat(j.env.fingerprints.fingerprint.value!!.ulaPrefixes).containsExactly(TEST_ULA, "fdd1:2345:6789::/48")
  }

  // --- Richiesta ---------------------------------------------------------------------------------------------

  @Test
  fun unNomeImpossibileSiRichiedeDiNuovoSenzaScrivereNiente() = runTest(UnconfinedTestDispatcher()) {
    val j = Fixture(this)
    j.toNeedsName()
    for (bad in listOf("", "   ", "Tablet\u0007", "x".repeat(65))) {
      assertThat(j.join.register(bad)).isEqualTo(JoinStep.NeedsName("Tablet profilo Mamma"))
    }
    assertThat(j.env.nas.share("NAS").fileCount()).isEqualTo(1) // solo il registro
    assertThat(j.store.snapshot.join!!.registered).isFalse()
  }

  @Test
  fun unaRichiestaGiaScrittaDaQuestaChiaveVaBene() = runTest(UnconfinedTestDispatcher()) {
    val j = Fixture(this)
    j.toNeedsName()
    val signer = j.env.deviceKeys.deviceSigner()
    val deviceId = TrustIds.deviceIdOf(signer.publicKey)
    // Un tentativo precedente (processo morto subito dopo la scrittura) l'ha gia' pubblicata, con un altro nome.
    val old = RegistrationRequest.sign(signer, j.family.familyId, "Vecchio nome", "SM-S931B", "mamma", NAS_NOON)
    j.env.nas.share("NAS").put(NasLayout.requestFile(deviceId), old)
    val step = j.join.register("Nuovo nome")
    assertThat(step).isInstanceOf(JoinStep.AwaitingApproval::class.java)
    assertThat((j.env.nas.share("NAS").read(NasLayout.requestFile(deviceId), 10_000) as NasRead.Data).bytes).isEqualTo(old)
  }

  @Test
  fun unFileEstraneoAlPostoDellaRichiestaNonVale() = runTest(UnconfinedTestDispatcher()) {
    val j = Fixture(this)
    j.toNeedsName()
    val deviceId = TrustIds.deviceIdOf(j.env.deviceKeys.deviceSigner().publicKey)
    j.env.nas.share("NAS").put(NasLayout.requestFile(deviceId), ByteArray(120) { 3 })
    assertThat(j.join.register("Tablet")).isEqualTo(problem(OnboardingProblem.NAS_WRITE_FAILED))
    assertThat(j.store.snapshot.join!!.registered).isFalse()
    assertThat(j.env.trustState.snapshot).isNull()
  }

  @Test
  fun unDispositivoRevocatoCheRientraHaUnaChiaveNuova_E_S2_E_O7() = runTest(UnconfinedTestDispatcher()) {
    val j = Fixture(this)
    val oldKey = j.env.deviceKeys.deviceSigner()
    j.family.approve(oldKey, name = "Tablet di prima")
    j.family.setStatus(oldKey, DeviceStatus.REVOKED)
    j.toNeedsName(j.family.invite(minVersion = 1))
    val step = j.join.register("Tablet di Mamma") as JoinStep.AwaitingApproval
    assertThat(j.env.deviceKeys.destroyed).isEqualTo(1)
    val newKey = j.env.deviceKeys.deviceSigner().publicKey
    assertThat(newKey).isNotEqualTo(oldKey.publicKey)
    assertThat(step.code).isEqualTo(ApprovalCodes.of(newKey))
    assertThat(j.env.nas.share("NAS").exists(NasLayout.requestFile(TrustIds.deviceIdOf(newKey)))).isTrue()
    assertThat(j.env.nas.share("NAS").exists(NasLayout.requestFile(TrustIds.deviceIdOf(oldKey.publicKey)))).isFalse()
  }

  @Test
  fun unDispositivoGiaApprovatoNonDeveChiedereDiNuovo() = runTest(UnconfinedTestDispatcher()) {
    val j = Fixture(this)
    val key = j.env.deviceKeys.deviceSigner()
    j.family.approve(key)
    j.toNeedsName(j.family.invite(minVersion = 1))
    assertThat(j.join.register("Tablet")).isEqualTo(JoinStep.Approved)
    assertThat(j.env.nas.share("NAS").exists(NasLayout.requestFile(TrustIds.deviceIdOf(key.publicKey)))).isFalse()
    assertThat(j.store.snapshot.join!!.registered).isTrue()
  }

  @Test
  fun unaRichiestaSiFirmaUnaSolaVoltaPerRipubblicareGliStessiByte() = runTest(UnconfinedTestDispatcher()) {
    val j = Fixture(this)
    j.toNeedsName()
    val deviceId = TrustIds.deviceIdOf(j.env.deviceKeys.deviceSigner().publicKey)
    // Il NAS non risponde alla scrittura: errore di rete, niente avanzamento.
    j.env.nas.share("NAS").failWith = NasError.Timeout
    assertThat(j.join.register("Tablet")).isEqualTo(problem(OnboardingProblem.NAS_WRITE_FAILED))
    assertThat(j.store.snapshot.join!!.registered).isFalse()
    j.env.nas.share("NAS").failWith = null
    val step = j.join.register("Tablet")
    assertThat(step).isInstanceOf(JoinStep.AwaitingApproval::class.java)
    assertThat(j.env.nas.share("NAS").exists(NasLayout.requestFile(deviceId))).isTrue()
  }

  // --- Senza QR ------------------------------------------------------------------------------------------------

  @Test
  fun senzaQrServeCasaConfermataELaVersioneMinimaEZero() = runTest(UnconfinedTestDispatcher()) {
    val j = Fixture(this)
    val chosen = expectAdvanced(j.coordinator.submit(StepInput.ChoosePath(OnboardingPath.JOIN_MANUAL)))
    assertThat(chosen.warnings).containsExactly(OnboardingWarning.NO_MIN_REGISTRY_VERSION)
    val manual = ManualInvite.create(j.family.familyId, j.family.fingerprint, TEST_IP, "mamma", "NAS", "mamma", "Tablet di Mamma").acceptedOrNull()!!

    // Senza aver confermato "e' casa" non si va: l'ULA del QR non c'e', quindi lo conferma l'utente.
    assertThat(j.join.start(manual)).isEqualTo(problem(OnboardingProblem.NOT_AT_HOME))
    j.env.network.current = Snapshots.awayVpnWifi()
    assertThat(j.join.start(manual)).isEqualTo(problem(OnboardingProblem.VPN_ACTIVE))
    j.env.network.current = Snapshots.home()
    expectAdvanced(j.coordinator.submit(StepInput.ConfirmHome(true)))
    j.env.tester.runs.clear()

    assertThat(j.join.start(manual)).isEqualTo(JoinStep.NeedsPassword)
    val run = j.env.tester.runs.single()
    assertThat(run.target.expectedGuid).isNull() // si impara dalla NEGOTIATE, sul Wi-Fi confermato
    assertThat(run.target.candidate).isEqualTo(j.env.candidate)
    assertThat(j.store.snapshot.nas!!.guid).isEqualTo(TEST_GUID)
    assertThat(j.env.fingerprints.fingerprint.value!!.ulaPrefixes).containsExactly(TEST_ULA)

    // Il registro si accetta con l'ancora dei soli id e impronta digitati (versione minima 0).
    assertThat(j.join.authenticate(j.password)).isEqualTo(JoinStep.NeedsName("Tablet di Mamma"))
    assertThat(j.join.register("Tablet di Mamma")).isInstanceOf(JoinStep.AwaitingApproval::class.java)
    assertThat(j.store.snapshot.join!!.manual).isTrue()
    assertThat(j.store.snapshot.join!!.minRegistryVersion).isEqualTo(0L)

    // E alla fine, l'avviso resta: la Diagnostica lo mostra finche' non si vede la versione corrente.
    var last = expectAdvanced(j.coordinator.submit(StepInput.Roles(false, false)))
    while (last.next != null && last.next != OnboardingStep.SUMMARY) {
      last = expectAdvanced(
        when (last.next) {
          OnboardingStep.PRESET -> j.coordinator.submit(StepInput.PresetChoice(dev.pampa.mycloudgallery.core.rules.Preset.NIGHT, dev.pampa.mycloudgallery.core.family.contract.PreviewQuality.STANDARD))
          OnboardingStep.PERMISSIONS -> j.coordinator.submit(StepInput.PermissionsReviewed)
          OnboardingStep.BATTERY -> j.coordinator.submit(StepInput.BatteryReviewed)
          else -> j.coordinator.submit(StepInput.VpnSetup(false, null))
        },
      )
    }
    assertThat(expectAdvanced(j.coordinator.submit(StepInput.Finish)).warnings).contains(OnboardingWarning.NO_MIN_REGISTRY_VERSION)
  }

  @Test
  fun senzaQrUnIdOUnaImprontaDiUnAltraFamigliaNonPortanoANulla_E_O5() = runTest(UnconfinedTestDispatcher()) {
    val j = Fixture(this)
    j.choose(OnboardingPath.JOIN_MANUAL)
    expectAdvanced(j.coordinator.submit(StepInput.ConfirmHome(true)))
    val manual = ManualInvite.create(
      j.family.familyId, FamilyKey.generate().publicKey.fingerprint, TEST_IP, "mamma", "NAS", "mamma", "Tablet",
    ).acceptedOrNull()!!
    assertThat(j.join.start(manual)).isEqualTo(JoinStep.NeedsPassword)
    assertThat(j.join.authenticate(j.password)).isEqualTo(problem(OnboardingProblem.INVITE_OTHER_FAMILY))
    assertThat(j.env.credentials.removed).containsExactly("nas-primary")
  }

  // --- Finestra scaduta e attesa -----------------------------------------------------------------------------

  @Test
  fun seLaFinestraDellAdminScadeSiRifaLaRichiestaConUnaChiaveNuova() = runTest(UnconfinedTestDispatcher()) {
    val j = Fixture(this)
    val invite = j.family.invite()
    j.toNeedsName(invite)
    val first = j.join.register("Tablet di Mamma") as JoinStep.AwaitingApproval
    val oldKey = j.env.deviceKeys.deviceSigner().publicKey
    assertThat(j.store.snapshot.join!!.registered).isTrue()

    // Senza essere in attesa non si rifa niente (per esempio gia' approvato, o mai registrato).
    j.env.registry.status.value = TrustStatus.Approved(admin = false)
    assertThat(j.join.start(invite)).isEqualTo(problem(OnboardingProblem.STEP_NOT_AVAILABLE))

    // L'admin dice "finestra scaduta": il dispositivo rifa' l'ingresso (stesso QR) e la richiesta ha una chiave nuova.
    j.env.registry.status.value = TrustStatus.Pending(first.code)
    assertThat(j.join.start(invite)).isEqualTo(JoinStep.NeedsPassword)
    assertThat(j.join.authenticate(j.password)).isInstanceOf(JoinStep.NeedsName::class.java)
    val second = j.join.register("Tablet di Mamma") as JoinStep.AwaitingApproval
    val newKey = j.env.deviceKeys.deviceSigner().publicKey
    assertThat(j.env.deviceKeys.destroyed).isEqualTo(1)
    assertThat(newKey).isNotEqualTo(oldKey)
    assertThat(second.code).isNotEqualTo(first.code)
    assertThat(j.env.nas.share("NAS").exists(NasLayout.requestFile(TrustIds.deviceIdOf(newKey)))).isTrue()
    assertThat(j.store.snapshot.join!!.registered).isTrue()
  }

  @Test
  fun unaScritturaCheFallisceDopoLaChiaveNuovaNonNeCreaUnaTerza() = runTest(UnconfinedTestDispatcher()) {
    val j = Fixture(this)
    val invite = j.family.invite()
    j.toNeedsName(invite)
    j.join.register("Tablet")
    j.env.registry.status.value = TrustStatus.Pending("CODE")
    assertThat(j.join.start(invite)).isEqualTo(JoinStep.NeedsPassword)
    assertThat(j.join.authenticate(j.password)).isInstanceOf(JoinStep.NeedsName::class.java)
    j.env.nas.share("NAS").failWith = NasError.Timeout
    assertThat(j.join.register("Tablet")).isEqualTo(problem(OnboardingProblem.NAS_WRITE_FAILED))
    j.env.nas.share("NAS").failWith = null
    assertThat(j.join.register("Tablet")).isInstanceOf(JoinStep.AwaitingApproval::class.java)
    assertThat(j.env.deviceKeys.destroyed).isEqualTo(1)
  }

  @Test
  fun lAttesaRileggeIlRegistroOgniDieciSecondiEFiniscePerConto_suo() = runTest {
    val registry = FakeRegistryService(status = TrustStatus.Pending("ABCD"))
    val seen = mutableListOf<Pair<Long, TrustStatus>>()
    val job = launch {
      ApprovalWatcher(registry).watch().collect { seen += currentTime to it }
    }
    // Approvato a 25 secondi: lo si scopre al giro dei 30.
    launch {
      delay(25_000)
      registry.status.value = TrustStatus.Approved(admin = false)
    }
    advanceUntilIdle()
    assertThat(job.isCompleted).isTrue()
    assertThat(seen.map { it.first }).containsExactly(0L, 10_000L, 20_000L, 30_000L).inOrder()
    assertThat(seen.last().second).isEqualTo(TrustStatus.Approved(admin = false))
    assertThat(registry.refreshes).hasSize(4)
    assertThat(registry.refreshes.toSet()).containsExactly(Purpose.INTERACTIVE)
  }

  @Test
  fun lAttesaContinuaSeIlNasNonRispondeESiFermaSuUnEsitoFinale() = runTest {
    val registry = FakeRegistryService(status = TrustStatus.Stale(StaleReason.NOT_REFRESHED))
    val seen = mutableListOf<TrustStatus>()
    val job = launch { ApprovalWatcher(registry).watch().collect { seen += it } }
    advanceTimeBy(35_000)
    assertThat(job.isCompleted).isFalse()
    registry.status.value = TrustStatus.Revoked
    advanceUntilIdle()
    assertThat(job.isCompleted).isTrue()
    assertThat(seen.last()).isEqualTo(TrustStatus.Revoked)
    // Se la schermata si chiude (il collector si annulla) nessun altro giro.
    val closed = FakeRegistryService(status = TrustStatus.Pending("X"))
    val watching = launch { ApprovalWatcher(closed).watch().collect { } }
    advanceTimeBy(15_000)
    val rounds = closed.refreshes.size
    watching.cancel()
    advanceTimeBy(60_000)
    assertThat(closed.refreshes).hasSize(rounds)
  }
}
