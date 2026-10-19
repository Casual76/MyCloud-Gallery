package dev.pampa.mycloudgallery.feature.onboarding

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.JoinStep
import dev.pampa.mycloudgallery.core.family.contract.OnboardingPath
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProblem
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProgress
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.RecoveryKitRef
import dev.pampa.mycloudgallery.core.family.contract.RecoveryOutcome
import dev.pampa.mycloudgallery.core.family.contract.SavedNas
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.family.testing.FakeOnboardingCoordinator
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.network.contract.HomeCandidate
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.feature.onboarding.model.HomeUi
import dev.pampa.mycloudgallery.feature.onboarding.model.JoinPage
import dev.pampa.mycloudgallery.feature.onboarding.model.OnboardingDestination
import dev.pampa.mycloudgallery.feature.onboarding.model.RecoveryPage
import dev.pampa.mycloudgallery.feature.onboarding.model.RemovalReason
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
 * La verifica funzionale delle fasi 1-5A (`docs/review/funzionale-fasi-1-5a.md`, voci ON) lato schermate: il ViewModel
 * con i finti allineati ai veri (stesse regole dei passi, stesso controllo dell'invito, stesso profilo per il recupero).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VerificaFunzionaleViewModelTest {

  @Before
  fun setUp() {
    Dispatchers.setMain(UnconfinedTestDispatcher())
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private val nas = NasAddress("192.168.178.58", "WDMyCloudEX2Ultra")
  private val home = HomeCandidate("wifi-1", setOf("fd12:3456:789a::/48"), "192.168.178.1", "fritz.box", "192.168.178.0/24")

  private fun destination(vm: OnboardingViewModel) = vm.destination(vm.progress.value, vm.ui.value)

  private fun finished(path: OnboardingPath): OnboardingProgress {
    val plan = FakeOnboardingCoordinator.planFor(path)
    return OnboardingProgress.START.copy(path = path, plan = plan, current = OnboardingStep.SUMMARY, completed = plan.toSet())
  }

  // --- ON-1: "Entra senza QR" ---------------------------------------------------------------------------------

  @Test
  fun on1_inserisciAManoPassaAlPercorsoSenzaQrEChiedeSeECasa() = runTest {
    val env = TestEnv()
    env.coordinator.candidate = home
    val vm = env.viewModel()
    vm.choosePath(OnboardingPath.JOIN_QR)
    vm.joinEnterManually()
    // Il percorso cambia davvero: l'ingresso senza QR sul percorso con QR non partirebbe.
    assertThat(vm.progress.value.path).isEqualTo(OnboardingPath.JOIN_MANUAL)
    assertThat(destination(vm)).isEqualTo(OnboardingDestination.Join(JoinPage.Manual))
    assertThat(vm.ui.value.join?.home).isInstanceOf(HomeUi.Ask::class.java)
    vm.confirmManualHome(true)
    assertThat(vm.ui.value.join?.home).isInstanceOf(HomeUi.Confirmed::class.java)
  }

  @Test
  fun on1_lInvitoScrittoAManoHaLeAreeEIDatiNellaFormaCanonica() = runTest {
    val env = TestEnv()
    env.coordinator.candidate = home
    val vm = env.viewModel()
    vm.choosePath(OnboardingPath.JOIN_MANUAL)
    // Senza "e' casa" l'ingresso non parte (come il vero): non si contatta il NAS.
    val typedId = SAMPLE_FAMILY_ID.uppercase().chunked(4).joinToString("-")
    val typedPrint = SAMPLE_FINGERPRINT.uppercase().chunked(4).joinToString(" ")
    vm.submitManualJoin(" 192.168.178.58 ", " marta ", typedId, typedPrint, "Tablet di Marta", "Public")
    assertThat(vm.ui.value.join?.problem).isEqualTo(OnboardingProblem.NOT_AT_HOME)

    vm.confirmManualHome(true)
    vm.submitManualJoin(" 192.168.178.58 ", " marta ", typedId, typedPrint, "Tablet di Marta", "Public")
    val invite = env.join.lastInvite!!
    assertThat(invite.familyId).isEqualTo(SAMPLE_FAMILY_ID)
    assertThat(invite.familyKeyFingerprint).isEqualTo(SAMPLE_FINGERPRINT)
    assertThat(invite.commonShare).isEqualTo("Public")
    assertThat(invite.privateShare).isEqualTo("marta")
    assertThat(invite.nasUser).isEqualTo("marta")
    assertThat(invite.nasIp).isEqualTo("192.168.178.58")
    assertThat(destination(vm)).isEqualTo(OnboardingDestination.Join(JoinPage.Password))
  }

  @Test
  fun on1_senzaAreaComuneOConUnoStatoDiUnAltraFamigliaNonParteNiente() = runTest {
    val env = TestEnv()
    env.coordinator.candidate = home
    env.familyStateCodec.known["MCGS1:stato"] = sampleFamilyState()
    val vm = env.viewModel()
    vm.choosePath(OnboardingPath.JOIN_MANUAL)
    vm.confirmManualHome(true)
    vm.submitManualJoin("192.168.178.58", "marta", SAMPLE_FAMILY_ID, SAMPLE_FINGERPRINT, "Tablet", "")
    assertThat(vm.ui.value.join?.problem).isEqualTo(OnboardingProblem.INVITE_INVALID)
    vm.scanFamilyState()
    vm.onScanned("MCGS1:stato")
    vm.submitManualJoin("192.168.178.58", "marta", SAMPLE_FAMILY_ID, SAMPLE_FAMILY_ID, "Tablet", "Public")
    assertThat(vm.ui.value.join?.problem).isEqualTo(OnboardingProblem.INVITE_OTHER_FAMILY)
    assertThat(env.join.calls).isEmpty()
  }

  // --- ON-2: richiesta scaduta, si rifa' dalla pagina d'attesa -------------------------------------------------------

  @Test
  fun on2_dallAttesaSiRifaLaRichiestaESiTornaAllAttesa() = runTest {
    val env = TestEnv(finished(OnboardingPath.JOIN_QR))
    env.coordinator.finished = true
    env.coordinator.joinRegistered = true
    env.registry.status.value = TrustStatus.Pending("VECCHIO")
    env.inviteCodec.known["MCG1:valido"] = sampleInvite()
    env.join.steps += JoinStep.NeedsPassword
    env.join.steps += JoinStep.NeedsName("Tablet")
    env.join.steps += JoinStep.AwaitingApproval("NUOVO", "MCGK1:chiave")
    val vm = env.viewModel()
    vm.startRenewal()
    assertThat(destination(vm)).isEqualTo(OnboardingDestination.Join(JoinPage.Scan))
    vm.onScanned("MCG1:valido")
    vm.continueJoin()
    vm.submitJoinPassword("Album-del-Mare-1".toCharArray())
    vm.submitJoinName("Tablet")
    assertThat(vm.ui.value.join?.code).isEqualTo("NUOVO")
    vm.events.test {
      vm.continueAfterRequest()
      assertThat(awaitItem()).isEqualTo(OnboardingEvent.Finished)
    }
  }

  @Test
  fun on2_lasciandoLaScansioneSiTornaAllAttesaSenzaToccareNiente() = runTest {
    val env = TestEnv(finished(OnboardingPath.JOIN_QR))
    env.coordinator.finished = true
    env.coordinator.joinRegistered = true
    val vm = env.viewModel()
    vm.startRenewal()
    vm.events.test {
      vm.back()
      assertThat(awaitItem()).isEqualTo(OnboardingEvent.Finished)
    }
    assertThat(vm.progress.value.current).isEqualTo(OnboardingStep.SUMMARY)
    assertThat(env.coordinator.finished).isTrue()
  }

  // --- ON-3: il recupero riprende dopo la morte del processo -----------------------------------------------------------

  private fun kit() = RecoveryKitRef("marta", "kit-2026-10-12", createdAt = 1_760_000_000_000L)

  private fun afterAreas(path: OnboardingPath = OnboardingPath.RECOVER_ADMIN): TestEnv {
    val plan = FakeOnboardingCoordinator.planFor(path)
    val env = TestEnv(
      OnboardingProgress.START.copy(path = path, plan = plan, current = OnboardingStep.ROLES, completed = plan.takeWhile { it != OnboardingStep.ROLES }.toSet()),
    )
    env.coordinator.saved = SavedNas(nas, "marta", "famiglia", "marta")
    env.recovery.kits = NasResult.Ok(listOf(kit()))
    return env
  }

  @Test
  fun on3_unViewModelNuovoRiapreIKitConLAccountSalvato() = runTest {
    val env = afterAreas()
    val vm = env.viewModel()
    assertThat(destination(vm)).isEqualTo(OnboardingDestination.Recovery(RecoveryPage.Kits))
    assertThat(vm.ui.value.recovery?.kits).hasSize(1)
    val (account, share) = env.recovery.lastSearch!!
    assertThat(account.address).isEqualTo(nas)
    assertThat(account.user).isEqualTo("marta")
    assertThat(share).isEqualTo("marta")
    // E il recupero va fino in fondo: l'admin recuperato, poi i passi sul telefono.
    env.recovery.outcome = RecoveryOutcome.Recovered
    vm.kitsContinue()
    vm.chooseFate(dev.pampa.mycloudgallery.core.family.contract.OldAdminFate.REVOKED)
    vm.submitRecoveryCodeForKit("ABCDEFGH".toCharArray(), SAMPLE_FINGERPRINT)
    assertThat(vm.ui.value.recovery?.page).isEqualTo(RecoveryPage.Done)
    vm.finishRecovery()
    assertThat(destination(vm)).isEqualTo(OnboardingDestination.Step(OnboardingStep.ROLES))
  }

  @Test
  fun on3_dalRiepilogoSenzaAdminRecuperatoSiTornaAiKit() = runTest {
    val env = afterAreas()
    env.coordinator.progress.value = env.coordinator.progress.value.copy(current = OnboardingStep.SUMMARY)
    env.recovery.kits = NasResult.Ok(emptyList())
    val vm = env.viewModel()
    vm.finishRecovery() // l'utente aveva lasciato i kit
    assertThat(destination(vm)).isEqualTo(OnboardingDestination.Step(OnboardingStep.SUMMARY))
    vm.finish()
    assertThat(destination(vm)).isEqualTo(OnboardingDestination.Recovery(RecoveryPage.Kits))
  }

  // --- ON-4: revocato o dismesso ---------------------------------------------------------------------------------

  @Test
  fun on4_unDispositivoRevocatoNonVedeIlVecchioRiepilogoMaConfiguraDiNuovo() = runTest {
    val env = TestEnv(finished(OnboardingPath.JOIN_QR))
    env.coordinator.finished = true
    env.registry.status.value = TrustStatus.Revoked
    val vm = env.viewModel()
    vm.enterAtRoot()
    assertThat(destination(vm)).isEqualTo(OnboardingDestination.Removed)
    assertThat(vm.ui.value.removal?.reason).isEqualTo(RemovalReason.REVOKED)
    assertThat(vm.canGoBack(vm.progress.value, vm.ui.value)).isFalse()

    env.registry.status.value = TrustStatus.Retired
    assertThat(vm.ui.value.removal?.reason).isEqualTo(RemovalReason.RETIRED)

    vm.startOver()
    assertThat(env.coordinator.startOvers).isEqualTo(1)
    assertThat(destination(vm)).isEqualTo(OnboardingDestination.Step(OnboardingStep.WELCOME))
  }

  @Test
  fun on4_unPassoRiapertoDalleImpostazioniNonEUnDispositivoRevocato() = runTest {
    val env = TestEnv(finished(OnboardingPath.FOUND_FAMILY))
    env.coordinator.finished = true
    val vm = env.viewModel()
    vm.resume(OnboardingStep.VPN)
    assertThat(vm.ui.value.removal).isNull()
    assertThat(destination(vm)).isEqualTo(OnboardingDestination.Step(OnboardingStep.VPN))
  }

  // --- ON-7: un passo che non si riapre esce ---------------------------------------------------------------------------

  @Test
  fun on7_unPassoCheNonSiRiapreNonLasciaSulRiepilogo() = runTest {
    val env = TestEnv(finished(OnboardingPath.JOIN_QR))
    env.coordinator.finished = true
    val vm = env.viewModel()
    vm.events.test {
      // Chi e' entrato con il QR non ha la checklist del passo 13.
      vm.resume(OnboardingStep.SECURITY_NOTICE)
      assertThat(awaitItem()).isEqualTo(OnboardingEvent.Finished)
    }
    vm.resume(OnboardingStep.VPN)
    assertThat(destination(vm)).isEqualTo(OnboardingDestination.Step(OnboardingStep.VPN))
  }

  // --- ON-9: un errore nella ricerca dei kit -------------------------------------------------------------------------

  @Test
  fun on9_unErroreDelNasNonDiventaNessunKit() = runTest {
    val env = afterAreas()
    env.recovery.kits = NasResult.Err(NasError.Timeout)
    val vm = env.viewModel()
    val recovery = vm.ui.value.recovery!!
    assertThat(recovery.noKits).isFalse()
    assertThat(recovery.problem).isEqualTo(OnboardingProblem.NAS_NOT_FOUND)
    // "Riprova": il NAS ora risponde.
    env.recovery.kits = NasResult.Ok(listOf(kit()))
    vm.searchKits("marta")
    assertThat(vm.ui.value.recovery?.problem).isNull()
    assertThat(vm.ui.value.recovery?.kits).hasSize(1)
  }

  // --- ON-6: senza kit ne' codice, la guida per ripartire dal PC ------------------------------------------------------

  @Test
  fun on6_senzaKitLaGuidaDaIlPercorsoEsattoDellaCartellaDellaFamiglia() = runTest {
    val env = afterAreas()
    env.recovery.kits = NasResult.Ok(emptyList())
    val vm = env.viewModel()
    assertThat(vm.ui.value.recovery?.noKits).isTrue()
    vm.showStartOverGuide()
    assertThat(destination(vm)).isEqualTo(OnboardingDestination.Recovery(RecoveryPage.StartOver))
    assertThat(vm.ui.value.recovery?.startOverPath).isEqualTo("\\\\192.168.178.58\\famiglia\\.mycloudgallery\\family")
    vm.back()
    assertThat(destination(vm)).isEqualTo(OnboardingDestination.Recovery(RecoveryPage.Kits))
    // Cancellata la cartella dal PC: si rifa' la famiglia (nessun potere nuovo: e' il percorso "Crea la famiglia").
    vm.showStartOverGuide()
    vm.startFamilyFromScratch()
    assertThat(vm.progress.value.path).isEqualTo(OnboardingPath.FOUND_FAMILY)
    assertThat(destination(vm)).isEqualTo(OnboardingDestination.Step(OnboardingStep.NAS_ADDRESS))
  }

  // --- ON-12a: gia' approvato -----------------------------------------------------------------------------------

  @Test
  fun on12a_giaApprovatoSiContinuaConIPassiSulTelefono() = runTest {
    val env = TestEnv()
    env.inviteCodec.known["MCG1:valido"] = sampleInvite()
    env.join.steps += JoinStep.NeedsPassword
    env.join.steps += JoinStep.NeedsName("Tablet")
    env.join.steps += JoinStep.Approved
    val vm = env.viewModel()
    vm.choosePath(OnboardingPath.JOIN_QR)
    vm.onScanned("MCG1:valido")
    vm.continueJoin()
    vm.submitJoinPassword("Album-del-Mare-1".toCharArray())
    vm.submitJoinName("Tablet")
    assertThat(destination(vm)).isEqualTo(OnboardingDestination.Join(JoinPage.Approved))
    vm.events.test {
      vm.finishJoin()
      expectNoEvents()
    }
    assertThat(destination(vm)).isEqualTo(OnboardingDestination.Step(OnboardingStep.ROLES))
  }
}
