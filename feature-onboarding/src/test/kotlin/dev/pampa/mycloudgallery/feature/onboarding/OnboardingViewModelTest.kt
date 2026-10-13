package dev.pampa.mycloudgallery.feature.onboarding

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.AreaCandidate
import dev.pampa.mycloudgallery.core.family.contract.ExcludedFolder
import dev.pampa.mycloudgallery.core.family.contract.ExclusionReason
import dev.pampa.mycloudgallery.core.family.contract.FolderProposal
import dev.pampa.mycloudgallery.core.family.contract.JoinStep
import dev.pampa.mycloudgallery.core.family.contract.OldAdminFate
import dev.pampa.mycloudgallery.core.family.contract.OnboardingPath
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProblem
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProgress
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.OnboardingWarning
import dev.pampa.mycloudgallery.core.family.contract.PreviewQuality
import dev.pampa.mycloudgallery.core.family.contract.RecoveryKitRef
import dev.pampa.mycloudgallery.core.family.contract.RecoveryOutcome
import dev.pampa.mycloudgallery.core.family.contract.StepResult
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.ShareAccess
import dev.pampa.mycloudgallery.core.network.contract.DiscoveredNas
import dev.pampa.mycloudgallery.core.network.contract.HomeCandidate
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.VpnCommandResult
import dev.pampa.mycloudgallery.core.network.contract.VpnControlConfig
import dev.pampa.mycloudgallery.core.qr.contract.ScanRejection
import dev.pampa.mycloudgallery.core.qr.contract.ScanVerdict
import dev.pampa.mycloudgallery.core.rules.Preset
import dev.pampa.mycloudgallery.core.rules.platform.health.BackgroundHealth
import dev.pampa.mycloudgallery.feature.onboarding.model.HomeUi
import dev.pampa.mycloudgallery.feature.onboarding.model.JoinPage
import dev.pampa.mycloudgallery.feature.onboarding.model.KeyPage
import dev.pampa.mycloudgallery.feature.onboarding.model.OnboardingBanner
import dev.pampa.mycloudgallery.core.family.testing.FakeOnboardingCoordinator
import dev.pampa.mycloudgallery.feature.onboarding.model.OnboardingDestination
import dev.pampa.mycloudgallery.feature.onboarding.model.RecoveryPage
import dev.pampa.mycloudgallery.feature.onboarding.model.VpnTestUi
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
 * Il ViewModel con i fake: cosa mostra a ogni esito del coordinatore, dell'ingresso e del recupero. Nessuna schermata:
 * qui si provano le regole (un solo tentativo, password azzerate, avvisi, percorsi).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

  @Before
  fun setUp() {
    Dispatchers.setMain(UnconfinedTestDispatcher())
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private val nas = NasAddress("192.168.178.58", "WDMyCloudEX2Ultra")

  private fun candidate(ula: Set<String> = setOf("fd12:3456:789a::/48")) =
    HomeCandidate("wifi-1", ula, "192.168.178.1", "fritz.box", "192.168.178.0/24")

  // --- Percorsi -------------------------------------------------------------------------------------

  @Test
  fun creareLaFamigliaPortaAlPassoDelNas() = runTest {
    val env = TestEnv()
    val vm = env.viewModel()
    vm.choosePath(OnboardingPath.FOUND_FAMILY)
    assertThat(vm.progress.value.path).isEqualTo(OnboardingPath.FOUND_FAMILY)
    assertThat(vm.progress.value.current).isEqualTo(OnboardingStep.NAS_ADDRESS)
    assertThat(vm.ui.value.join).isNull()
    assertThat(vm.ui.value.recovery).isNull()
    assertThat(vm.destination(vm.progress.value, vm.ui.value)).isEqualTo(OnboardingDestination.Step(OnboardingStep.NAS_ADDRESS))
  }

  @Test
  fun entraConIlQrApreLaScansione() = runTest {
    val vm = TestEnv().viewModel()
    vm.choosePath(OnboardingPath.JOIN_QR)
    assertThat(vm.destination(vm.progress.value, vm.ui.value)).isEqualTo(OnboardingDestination.Join(JoinPage.Scan))
  }

  @Test
  fun entraSenzaQrApreIDatiAMano() = runTest {
    val vm = TestEnv().viewModel()
    vm.choosePath(OnboardingPath.JOIN_MANUAL)
    assertThat(vm.destination(vm.progress.value, vm.ui.value)).isEqualTo(OnboardingDestination.Join(JoinPage.Manual))
  }

  @Test
  fun unProblemaNellaSceltaDelPercorsoResta() = runTest {
    val env = TestEnv()
    env.coordinator.scripted += StepResult.Problem(OnboardingProblem.STEP_NOT_AVAILABLE)
    val vm = env.viewModel()
    vm.choosePath(OnboardingPath.FOUND_FAMILY)
    assertThat(vm.ui.value.welcomeProblem).isEqualTo(OnboardingProblem.STEP_NOT_AVAILABLE)
    assertThat(vm.ui.value.welcomeBusy).isFalse()
  }

  @Test
  fun unProcessoMortoDuranteUnIngressoRiparteDallaScansione() = runTest {
    val progress = OnboardingProgress.START.copy(path = OnboardingPath.JOIN_QR, current = OnboardingStep.NAS_ADDRESS)
    val vm = TestEnv(progress).viewModel()
    assertThat(vm.ui.value.join?.page).isEqualTo(JoinPage.Scan)
  }

  /** P9: con la richiesta gia' sul NAS (passi 2 e 3 fatti) si riprende dai passi locali, non dalla scansione. */
  @Test
  fun dopoLaRichiestaIlProcessoRiparteDaiPassiLocali() = runTest {
    val progress = OnboardingProgress.START.copy(
      path = OnboardingPath.JOIN_QR,
      plan = FakeOnboardingCoordinator.planFor(OnboardingPath.JOIN_QR),
      current = OnboardingStep.ROLES,
    )
    val vm = TestEnv(progress).viewModel()
    assertThat(vm.ui.value.join).isNull()
    assertThat(vm.destination(vm.progress.value, vm.ui.value)).isEqualTo(OnboardingDestination.Step(OnboardingStep.ROLES))
  }

  /** P9: dalla pagina d'attesa si finiscono intanto i passi sul telefono. */
  @Test
  fun dallAttesaSiContinuaConIPassiSulTelefono() = runTest {
    val progress = OnboardingProgress.START.copy(path = OnboardingPath.JOIN_QR, current = OnboardingStep.NAS_ADDRESS)
    val env = TestEnv(progress)
    val vm = env.viewModel()
    assertThat(vm.ui.value.join).isNotNull()
    vm.continueAfterRequest()
    assertThat(vm.ui.value.join).isNull()
    // Il passo corrente lo dice il coordinatore: nessuna nuova scansione.
    env.coordinator.progress.value = progress.copy(current = OnboardingStep.ROLES)
    assertThat(vm.ui.value.join).isNull()
  }

  /** P9 (E-O1): "Continua piu' tardi" chiede al coordinatore di rimandare; senza niente da rimandare, l'app va in pausa. */
  @Test
  fun continuaPiuTardiRimandaIPassiDelNasOPassaLaMano() = runTest {
    // Un passo locale: niente da rimandare.
    var paused = 0
    val local = TestEnv(OnboardingProgress.START.copy(current = OnboardingStep.PRESET)).viewModel()
    local.continueLater { paused++ }
    assertThat(paused).isEqualTo(1)
    // Il passo 2 (il coordinatore finto non lo rimanda): la configurazione resta salvata e l'app va in pausa.
    val env = TestEnv(OnboardingProgress.START.copy(path = OnboardingPath.FOUND_FAMILY, current = OnboardingStep.NAS_ADDRESS))
    env.viewModel().continueLater { paused++ }
    assertThat(paused).isEqualTo(2)
    // Il passo 5 (saltabile): il coordinatore lo rimanda e si va avanti, nessuna pausa.
    val folders = TestEnv(OnboardingProgress.START.copy(path = OnboardingPath.FOUND_FAMILY, current = OnboardingStep.FOLDERS))
    folders.viewModel().continueLater { paused++ }
    assertThat(paused).isEqualTo(2)
    assertThat(folders.coordinator.progress.value.skipped).contains(OnboardingStep.FOLDERS)
  }

  @Test
  fun unProfiloSecondarioDelTabletSiVede() = runTest {
    val env = TestEnv()
    env.health.health = BackgroundHealth.UNKNOWN.copy(isSystemUser = false)
    val vm = env.viewModel()
    assertThat(vm.ui.value.isSystemUser).isFalse()
  }

  // --- 2. Indirizzo ---------------------------------------------------------------------------------

  @Test
  fun senzaWifiNonSiChiedeSeECasa() = runTest {
    val env = TestEnv()
    env.coordinator.candidate = null
    val vm = env.viewModel()
    vm.enterAddressStep()
    assertThat(vm.ui.value.address.home).isEqualTo(HomeUi.NoWifi)
  }

  @Test
  fun senzaUlaNonSiPuoConfermareCasa() = runTest {
    val env = TestEnv()
    env.coordinator.candidate = candidate(ula = emptySet())
    val vm = env.viewModel()
    vm.enterAddressStep()
    val home = vm.ui.value.address.home as HomeUi.Ask
    assertThat(home.candidate.canConfirm).isFalse()
  }

  @Test
  fun confermatoCasaParteLaScopertaEPoiIlControlloDelNas() = runTest {
    val env = TestEnv()
    env.coordinator.candidate = candidate()
    env.coordinator.discovered = listOf(DiscoveredNas("WDMyCloudEX2Ultra", nas))
    val vm = env.viewModel()
    vm.choosePath(OnboardingPath.FOUND_FAMILY)
    vm.enterAddressStep()
    env.coordinator.scripted += StepResult.Advanced(OnboardingStep.NAS_ADDRESS)
    vm.confirmHome(true)
    assertThat(vm.ui.value.address.home).isInstanceOf(HomeUi.Confirmed::class.java)
    assertThat(vm.ui.value.address.found.map { it.ip }).containsExactly("192.168.178.58")
    vm.checkNas(nas)
    assertThat(vm.progress.value.current).isEqualTo(OnboardingStep.ACCOUNT)
    assertThat(vm.ui.value.banner).isEqualTo(OnboardingBanner.NAS_VERIFIED)
    assertThat(vm.ui.value.address.checking).isFalse()
  }

  @Test
  fun laVpnAccesaFermaLaConfermaDiCasa() = runTest {
    val env = TestEnv()
    env.coordinator.candidate = candidate()
    val vm = env.viewModel()
    vm.enterAddressStep()
    env.coordinator.scripted += StepResult.Problem(OnboardingProblem.VPN_ACTIVE)
    vm.confirmHome(true)
    assertThat(vm.ui.value.address.problem).isEqualTo(OnboardingProblem.VPN_ACTIVE)
    assertThat(vm.ui.value.address.home).isInstanceOf(HomeUi.Ask::class.java)
  }

  @Test
  fun rispondereNoNonESuCasa() = runTest {
    val env = TestEnv()
    env.coordinator.candidate = candidate()
    val vm = env.viewModel()
    vm.enterAddressStep()
    env.coordinator.scripted += StepResult.Advanced(OnboardingStep.NAS_ADDRESS)
    vm.confirmHome(false)
    assertThat(vm.ui.value.address.home).isEqualTo(HomeUi.NotHome)
  }

  @Test
  fun ilNasSpentoEUnProblemaDaPoterRiprendere() = runTest {
    val env = TestEnv()
    val vm = env.viewModel()
    env.coordinator.scripted += StepResult.Problem(OnboardingProblem.NAS_NOT_FOUND)
    vm.checkNas(nas)
    assertThat(vm.ui.value.address.problem).isEqualTo(OnboardingProblem.NAS_NOT_FOUND)
    assertThat(vm.ui.value.address.checking).isFalse()
    assertThat(vm.progress.value.current).isEqualTo(OnboardingStep.WELCOME)
  }

  // --- 3. Account -----------------------------------------------------------------------------------

  @Test
  fun laPasswordSiAzzeraEUnErroreDiAutenticazioneChiedeLaNuova() = runTest {
    val env = TestEnv()
    val vm = env.viewModel()
    env.coordinator.scripted += StepResult.Problem(OnboardingProblem.AUTH_FAILED)
    val password = "una-password-lunga".toCharArray()
    vm.submitAccount("marta", password)
    assertThat(password.all { it == '\u0000' }).isTrue()
    assertThat(vm.ui.value.account.problem).isEqualTo(OnboardingProblem.AUTH_FAILED)
    assertThat(vm.ui.value.account.needsNewPassword).isTrue()
    assertThat(env.coordinator.inputs).containsExactly("Credentials")
  }

  @Test
  fun unAvvisoDelPassoArrivaNelPassoDopoESiChiude() = runTest {
    val env = TestEnv()
    val vm = env.viewModel()
    env.coordinator.scripted += StepResult.Advanced(OnboardingStep.AREAS, setOf(OnboardingWarning.ACCOUNT_SEES_OTHER_PRIVATE_SHARES))
    vm.submitAccount("marta", "Gatto-Nero-7".toCharArray())
    assertThat(vm.ui.value.warnings).containsExactly(OnboardingWarning.ACCOUNT_SEES_OTHER_PRIVATE_SHARES)
    assertThat(vm.ui.value.banner).isEqualTo(OnboardingBanner.CREDENTIALS_SAVED)
    assertThat(vm.ui.value.account.needsNewPassword).isFalse()
    vm.dismissWarning(OnboardingWarning.ACCOUNT_SEES_OTHER_PRIVATE_SHARES)
    assertThat(vm.ui.value.warnings).isEmpty()
  }

  @Test
  fun ognunoDegliErroriDelNasEUnProblemaConIlSuoMessaggio() = runTest {
    val auth = listOf(
      OnboardingProblem.AUTH_FAILED,
      OnboardingProblem.ACCOUNT_LOCKED,
      OnboardingProblem.ACCOUNT_DISABLED,
      OnboardingProblem.PASSWORD_EXPIRED,
      OnboardingProblem.ACCOUNT_RESTRICTED,
    )
    auth.forEach { problem ->
      val env = TestEnv()
      val vm = env.viewModel()
      env.coordinator.scripted += StepResult.Problem(problem)
      vm.submitAccount("marta", "password".toCharArray())
      assertThat(vm.ui.value.account.problem).isEqualTo(problem)
      assertThat(vm.ui.value.account.needsNewPassword).isTrue()
    }
    val notAuth = listOf(OnboardingProblem.ADMIN_ACCOUNT_REFUSED, OnboardingProblem.CREDENTIALS_NOT_SAVED, OnboardingProblem.NAS_INSECURE)
    notAuth.forEach { problem ->
      val env = TestEnv()
      val vm = env.viewModel()
      env.coordinator.scripted += StepResult.Problem(problem)
      vm.submitAccount("marta", "password".toCharArray())
      assertThat(vm.ui.value.account.problem).isEqualTo(problem)
      assertThat(vm.ui.value.account.needsNewPassword).isFalse()
    }
  }

  // --- 4-5. Aree e cartelle -------------------------------------------------------------------------

  @Test
  fun leAreeSiProvanoEUnaFamigliaGiaEsistenteSiVede() = runTest {
    val env = TestEnv()
    env.coordinator.areas = listOf(
      AreaCandidate("marta", NasResult.Ok(ShareAccess.READ_WRITE), isPublic = false, hasFamily = false),
      AreaCandidate("Public", NasResult.Ok(ShareAccess.READ_WRITE), isPublic = true, hasFamily = true),
    )
    val vm = env.viewModel()
    vm.enterAreasStep()
    assertThat(vm.ui.value.areas.loading).isFalse()
    assertThat(vm.ui.value.areas.candidates).hasSize(2)
    env.coordinator.scripted += StepResult.Problem(OnboardingProblem.FAMILY_ALREADY_EXISTS)
    vm.submitAreas("Public", "marta")
    assertThat(vm.ui.value.areas.problem).isEqualTo(OnboardingProblem.FAMILY_ALREADY_EXISTS)
  }

  @Test
  fun unaShareDigitataSiAggiungeEDueVolteNo() = runTest {
    val env = TestEnv()
    val vm = env.viewModel()
    vm.addShare("Famiglia")
    vm.addShare("Famiglia")
    vm.addShare("   ")
    assertThat(env.coordinator.areas).isEmpty()
    assertThat(vm.ui.value.areas.loading).isFalse()
  }

  @Test
  fun leCartelleProposteSiMostranoESiPossonoSaltare() = runTest {
    val env = TestEnv(OnboardingProgress.START.copy(current = OnboardingStep.FOLDERS, plan = OnboardingStep.entries.toList()))
    env.coordinator.folders = FolderProposal(listOf(ExcludedFolder("Musica", ExclusionReason.MUSIC)), scanned = 120, truncated = false)
    val vm = env.viewModel()
    vm.enterFoldersStep()
    assertThat(vm.ui.value.folders.proposal?.excluded).hasSize(1)
    vm.skip(OnboardingStep.FOLDERS)
    assertThat(vm.progress.value.skipped).contains(OnboardingStep.FOLDERS)
    assertThat(vm.progress.value.current).isEqualTo(OnboardingStep.BACKUP_SOURCES)
  }

  @Test
  fun unPassoNonSaltabileDaUnProblema() = runTest {
    val env = TestEnv(OnboardingProgress.START.copy(current = OnboardingStep.ACCOUNT))
    val vm = env.viewModel()
    vm.skip(OnboardingStep.ACCOUNT)
    assertThat(vm.ui.value.account.problem).isEqualTo(OnboardingProblem.STEP_NOT_AVAILABLE)
  }

  // --- 6-14 -----------------------------------------------------------------------------------------

  @Test
  fun ilPresetSiInviaEIlPassoFinisce() = runTest {
    val env = TestEnv(OnboardingProgress.START.copy(current = OnboardingStep.PRESET))
    val vm = env.viewModel()
    vm.submitPreset(Preset.BALANCED, PreviewQuality.HIGH)
    assertThat(env.coordinator.inputs).containsExactly("PresetChoice")
    assertThat(vm.ui.value.preset.submitting).isFalse()
    assertThat(vm.progress.value.current).isEqualTo(OnboardingStep.PERMISSIONS)
  }

  @Test
  fun laBatteriaSiRileggeDaAndroid() = runTest {
    val env = TestEnv()
    env.health.health = BackgroundHealth.UNKNOWN.copy(ignoringBatteryOpt = true)
    val vm = env.viewModel()
    vm.refreshBattery()
    assertThat(vm.ui.value.battery.health?.ignoringBatteryOpt).isTrue()
    assertThat(env.health.reads).isAtLeast(2)
  }

  @Test
  fun laProvaDellaVpnSiOsservaSiaInAccensioneSiaInSpegnimento() = runTest {
    val env = TestEnv()
    val vm = env.viewModel()
    vm.runVpnTest("casa")
    assertThat(env.vpn.commands).containsExactly(true, false).inOrder()
    val done = vm.ui.value.vpn.test as VpnTestUi.Done
    assertThat(done.up).isEqualTo(VpnCommandResult.Confirmed)
    assertThat(done.down).isEqualTo(VpnCommandResult.Confirmed)
    // P9: la prova usa il nome scritto e poi rimette la configurazione di prima (la scelta la salva "Continua").
    assertThat(env.vpnConfig.config.value).isEqualTo(VpnControlConfig.OFF)
  }

  @Test
  fun laProvaDellaVpnUsaIlNomeDelTunnelAppenaScritto() = runTest {
    val env = TestEnv()
    var seen: VpnControlConfig? = null
    val controller = object : dev.pampa.mycloudgallery.core.network.contract.VpnController by env.vpn {
      override suspend fun setTunnel(up: Boolean, timeout: kotlin.time.Duration): VpnCommandResult {
        seen = env.vpnConfig.config.value
        return VpnCommandResult.NotObserved
      }
    }
    val vm = OnboardingViewModel(
      env.coordinator, env.join, env.recovery, env.inviteCodec, env.familyStateCodec, controller, env.health, env.localNetwork, env.vpnConfig,
    )
    vm.runVpnTest(" casa ")
    assertThat(seen).isEqualTo(VpnControlConfig(enabled = true, tunnelName = "casa"))
    assertThat(env.vpnConfig.config.value).isEqualTo(VpnControlConfig.OFF)
  }

  @Test
  fun senzaUnaVpnCheRisponde_nonSiProvaASpegnere() = runTest {
    val env = TestEnv()
    env.vpn.nextResult = VpnCommandResult.NotObserved
    val vm = env.viewModel()
    vm.runVpnTest("casa")
    assertThat(env.vpn.commands).containsExactly(true)
    val done = vm.ui.value.vpn.test as VpnTestUi.Done
    assertThat(done.up).isEqualTo(VpnCommandResult.NotObserved)
    assertThat(done.down).isNull()
  }

  @Test
  fun seSpegnendoLaVpnLaReteSparisceLaRiaccende() = runTest {
    val env = TestEnv()
    val vm = env.viewModel()
    env.vpn.nextResult = VpnCommandResult.Confirmed
    // primo comando confermato, secondo: la rete sparisce
    val results = ArrayDeque(listOf(VpnCommandResult.Confirmed, VpnCommandResult.LostConnectivity))
    val controller = object : dev.pampa.mycloudgallery.core.network.contract.VpnController by env.vpn {
      override suspend fun setTunnel(up: Boolean, timeout: kotlin.time.Duration): VpnCommandResult {
        env.vpn.commands += up
        return results.removeFirstOrNull() ?: VpnCommandResult.Confirmed
      }
    }
    val custom = OnboardingViewModel(
      env.coordinator, env.join, env.recovery, env.inviteCodec, env.familyStateCodec, controller, env.health, env.localNetwork, env.vpnConfig,
    )
    custom.runVpnTest("casa")
    assertThat(env.vpn.commands).containsExactly(true, false, true).inOrder()
    assertThat((custom.ui.value.vpn.test as VpnTestUi.Done).down).isEqualTo(VpnCommandResult.LostConnectivity)
    assertThat(vm.ui.value.vpn.test).isEqualTo(VpnTestUi.Idle)
  }

  // --- 12. Chiave e kit -----------------------------------------------------------------------------

  @Test
  fun ilCodiceDiRecuperoSiMostraInSetteGruppi() = runTest {
    val vm = TestEnv().viewModel()
    vm.generateKey()
    assertThat(vm.ui.value.key.page).isEqualTo(KeyPage.ShowCode)
    assertThat(vm.ui.value.key.groups).hasSize(7)
    vm.codeWritten()
    assertThat(vm.ui.value.key.page).isEqualTo(KeyPage.Retype)
    vm.showCodeAgain()
    assertThat(vm.ui.value.key.page).isEqualTo(KeyPage.ShowCode)
  }

  @Test
  fun unCodiceSbagliatoRiportaAllaPaginaDelCodiceSenzaPerderlo() = runTest {
    val env = TestEnv()
    val vm = env.viewModel()
    vm.generateKey()
    env.coordinator.scripted += StepResult.Problem(OnboardingProblem.RECOVERY_CODE_MISMATCH)
    val typed = "ABCDABCD".toCharArray()
    vm.submitRecoveryCode(typed)
    assertThat(typed.all { it == '\u0000' }).isTrue()
    assertThat(vm.ui.value.key.page).isEqualTo(KeyPage.Retype)
    assertThat(vm.ui.value.key.problem).isEqualTo(OnboardingProblem.RECOVERY_CODE_MISMATCH)
    assertThat(vm.ui.value.key.groups).hasSize(7)
  }

  @Test
  fun ilKitVerificatoChiudeIlCodiceEDaLaConferma() = runTest {
    val env = TestEnv(OnboardingProgress.START.copy(current = OnboardingStep.FAMILY_KEY))
    val vm = env.viewModel()
    vm.generateKey()
    vm.submitRecoveryCode("ABCD".repeat(7).toCharArray())
    assertThat(vm.ui.value.key.groups).isEmpty()
    assertThat(vm.ui.value.key.page).isEqualTo(KeyPage.Intro)
    assertThat(vm.ui.value.banner).isEqualTo(OnboardingBanner.KIT_VERIFIED)
    assertThat(vm.progress.value.current).isEqualTo(OnboardingStep.SECURITY_NOTICE)
  }

  @Test
  fun seLaFamigliaEsisteGiaSiTornaAllInizioSenzaIlCodice() = runTest {
    val env = TestEnv()
    val vm = env.viewModel()
    vm.generateKey()
    env.coordinator.scripted += StepResult.Problem(OnboardingProblem.FAMILY_ALREADY_EXISTS)
    vm.submitRecoveryCode("ABCD".repeat(7).toCharArray())
    assertThat(vm.ui.value.key.page).isEqualTo(KeyPage.Intro)
    assertThat(vm.ui.value.key.groups).isEmpty()
    assertThat(vm.ui.value.key.problem).isEqualTo(OnboardingProblem.FAMILY_ALREADY_EXISTS)
  }

  @Test
  fun ilRitornoDallaPaginaDelCodiceVaSoloVersoIlCodice() = runTest {
    val env = TestEnv(OnboardingProgress.START.copy(current = OnboardingStep.FAMILY_KEY))
    val vm = env.viewModel()
    vm.generateKey()
    assertThat(vm.canGoBack(vm.progress.value, vm.ui.value)).isFalse()
    vm.codeWritten()
    assertThat(vm.canGoBack(vm.progress.value, vm.ui.value)).isTrue()
    vm.back()
    assertThat(vm.ui.value.key.page).isEqualTo(KeyPage.ShowCode)
  }

  @Test
  fun finireInviaIlSegnaleDiFine() = runTest {
    val env = TestEnv(OnboardingProgress.START.copy(current = OnboardingStep.SUMMARY))
    val vm = env.viewModel()
    vm.events.test {
      vm.finish()
      assertThat(awaitItem()).isEqualTo(OnboardingEvent.Finished)
    }
  }

  // --- Ingresso con invito --------------------------------------------------------------------------

  @Test
  fun ilQrDiWireGuardEUnaPasswordEVieneRifiutato() = runTest {
    val vm = TestEnv().viewModel()
    vm.choosePath(OnboardingPath.JOIN_QR)
    val verdict = vm.onScanned("[Interface]\nPrivateKey = abc")
    assertThat(verdict).isEqualTo(ScanVerdict.Reject(ScanRejection.WIREGUARD_SECRET))
    assertThat(vm.ui.value.join?.rejection).isEqualTo(ScanRejection.WIREGUARD_SECRET)
  }

  @Test
  fun unTestoCheNonENostroEIlLoStessoDueVolteSiIgnora() = runTest {
    val vm = TestEnv().viewModel()
    vm.choosePath(OnboardingPath.JOIN_QR)
    assertThat(vm.onScanned("https://esempio.it")).isEqualTo(ScanVerdict.Reject(ScanRejection.NOT_OURS))
    assertThat(vm.onScanned("https://esempio.it")).isEqualTo(ScanVerdict.Ignore)
  }

  @Test
  fun unInvitoRovinatoSiRifiutaConIlSuoMotivo() = runTest {
    val vm = TestEnv().viewModel()
    vm.choosePath(OnboardingPath.JOIN_QR)
    assertThat(vm.onScanned("MCG1:rovinato")).isEqualTo(ScanVerdict.Reject(ScanRejection.MALFORMED))
  }

  @Test
  fun unInvitoValidoFermaLoScannerEPortaAlRiepilogo() = runTest {
    val env = TestEnv()
    env.inviteCodec.known["MCG1:valido"] = sampleInvite()
    val vm = env.viewModel()
    vm.choosePath(OnboardingPath.JOIN_QR)
    assertThat(vm.onScanned("MCG1:valido")).isEqualTo(ScanVerdict.Accept)
    assertThat(vm.ui.value.join?.page).isEqualTo(JoinPage.Summary)
    assertThat(vm.ui.value.join?.invite?.nasUser).isEqualTo("marta")
  }

  @Test
  fun dalRiepilogoSiArrivaAllaPasswordAlNomeEAllAttesa() = runTest {
    val env = TestEnv()
    env.inviteCodec.known["MCG1:valido"] = sampleInvite()
    env.join.steps += JoinStep.NeedsPassword
    env.join.steps += JoinStep.NeedsName("Tablet — profilo Mamma")
    env.join.steps += JoinStep.AwaitingApproval("K7QD-2M9X-FA4T", "MCGK1:chiave")
    val vm = env.viewModel()
    vm.choosePath(OnboardingPath.JOIN_QR)
    vm.onScanned("MCG1:valido")
    vm.continueJoin()
    assertThat(vm.ui.value.join?.page).isEqualTo(JoinPage.Password)
    val password = "password-lunga-e-giusta".toCharArray()
    vm.submitJoinPassword(password)
    assertThat(password.all { it == '\u0000' }).isTrue()
    assertThat(vm.ui.value.join?.page).isEqualTo(JoinPage.Name)
    assertThat(vm.ui.value.join?.suggestedName).isEqualTo("Tablet — profilo Mamma")
    vm.submitJoinName("Tablet — profilo Mamma")
    val join = vm.ui.value.join!!
    assertThat(join.page).isEqualTo(JoinPage.Awaiting)
    assertThat(join.code).isEqualTo("K7QD-2M9X-FA4T")
    assertThat(join.deviceKeyQr).isEqualTo("MCGK1:chiave")
    assertThat(env.join.calls).containsExactly("start", "authenticate", "register").inOrder()
  }

  @Test
  fun unaPasswordSbagliataChiedeLaNuovaEUnNonACasaNonChiedeNiente() = runTest {
    val env = TestEnv()
    env.inviteCodec.known["MCG1:valido"] = sampleInvite()
    env.join.steps += JoinStep.Failed(OnboardingProblem.NOT_AT_HOME)
    val vm = env.viewModel()
    vm.choosePath(OnboardingPath.JOIN_QR)
    vm.onScanned("MCG1:valido")
    vm.continueJoin()
    assertThat(vm.ui.value.join?.problem).isEqualTo(OnboardingProblem.NOT_AT_HOME)
    assertThat(vm.ui.value.join?.page).isEqualTo(JoinPage.Summary)
    env.join.steps += JoinStep.NeedsPassword
    vm.continueJoin()
    env.join.steps += JoinStep.Failed(OnboardingProblem.AUTH_FAILED)
    vm.submitJoinPassword("sbagliata".toCharArray())
    assertThat(vm.ui.value.join?.needsNewPassword).isTrue()
    assertThat(vm.ui.value.join?.problem).isEqualTo(OnboardingProblem.AUTH_FAILED)
  }

  @Test
  fun entraSenzaQrCostruisceUnInvitoConVersioneMinimaZeroEUnAvviso() = runTest {
    val env = TestEnv()
    env.join.steps += JoinStep.NeedsPassword
    val vm = env.viewModel()
    vm.choosePath(OnboardingPath.JOIN_MANUAL)
    vm.submitManualJoin("192.168.178.58", "marta", "famiglia-rossi", "K7QD 2M9X FA4T", "Tablet")
    val invite = vm.ui.value.join?.invite
    assertThat(invite?.minRegistryVersion).isEqualTo(0L)
    assertThat(invite?.familyKeyFingerprint).isEqualTo("K7QD2M9XFA4T")
    assertThat(vm.ui.value.warnings).contains(OnboardingWarning.NO_MIN_REGISTRY_VERSION)
    assertThat(vm.ui.value.join?.page).isEqualTo(JoinPage.Password)
  }

  @Test
  fun loStatoDellaFamigliaAlzaLaVersioneMinima() = runTest {
    val env = TestEnv()
    env.familyStateCodec.known["MCGS1:stato"] = sampleFamilyState()
    env.join.steps += JoinStep.NeedsPassword
    val vm = env.viewModel()
    vm.choosePath(OnboardingPath.JOIN_MANUAL)
    vm.scanFamilyState()
    assertThat(vm.onScanned("MCGS1:stato")).isEqualTo(ScanVerdict.Accept)
    assertThat(vm.ui.value.join?.familyState?.registryVersion).isEqualTo(12L)
    vm.submitManualJoin("192.168.178.58", "marta", "famiglia-rossi", "K7QD2M9XFA4T", "Tablet")
    assertThat(vm.ui.value.join?.invite?.minRegistryVersion).isEqualTo(12L)
    assertThat(vm.ui.value.warnings).doesNotContain(OnboardingWarning.NO_MIN_REGISTRY_VERSION)
  }

  @Test
  fun tornareIndietroDalleSchermateDiIngresso() = runTest {
    val env = TestEnv()
    env.inviteCodec.known["MCG1:valido"] = sampleInvite()
    val vm = env.viewModel()
    vm.choosePath(OnboardingPath.JOIN_QR)
    vm.onScanned("MCG1:valido")
    vm.back()
    assertThat(vm.ui.value.join?.page).isEqualTo(JoinPage.Scan)
    vm.back()
    assertThat(vm.ui.value.join).isNull()
    assertThat(vm.progress.value.current).isEqualTo(OnboardingStep.WELCOME)
  }

  // --- Recupero dell'admin e secondo admin ----------------------------------------------------------

  private fun kit(name: String = "kit-2026-10-12") = RecoveryKitRef("marta", name, createdAt = 1_760_000_000_000L)

  @Test
  fun dopoLAccountDelRecuperoSiCercanoIKit() = runTest {
    val env = TestEnv()
    env.recovery.kits = NasResult.Ok(listOf(kit()))
    val vm = env.viewModel()
    vm.choosePath(OnboardingPath.RECOVER_ADMIN)
    vm.checkNas(nas)
    vm.submitAccount("marta", "password-lunga".toCharArray())
    val recovery = vm.ui.value.recovery!!
    assertThat(recovery.kitsLoaded).isTrue()
    assertThat(recovery.kits).hasSize(1)
    assertThat(recovery.secondAdmin).isFalse()
    assertThat(vm.destination(vm.progress.value, vm.ui.value)).isEqualTo(OnboardingDestination.Recovery(RecoveryPage.Kits))
  }

  @Test
  fun ilRecuperoChiedeCosaFareDelVecchioAdminPrimaDelCodice() = runTest {
    val env = TestEnv()
    env.recovery.kits = NasResult.Ok(listOf(kit()))
    env.recovery.outcome = RecoveryOutcome.Recovered
    val vm = env.viewModel()
    vm.choosePath(OnboardingPath.RECOVER_ADMIN)
    vm.checkNas(nas)
    vm.submitAccount("marta", "password-lunga".toCharArray())
    vm.kitsContinue()
    assertThat(vm.ui.value.recovery?.page).isEqualTo(RecoveryPage.Fate)
    vm.chooseFate(OldAdminFate.LOST)
    assertThat(vm.ui.value.recovery?.page).isEqualTo(RecoveryPage.Code)
    val code = "ABCDEFGH".toCharArray()
    vm.submitRecoveryCodeForKit(code)
    assertThat(code.all { it == '\u0000' }).isTrue()
    assertThat(vm.ui.value.recovery?.page).isEqualTo(RecoveryPage.Done)
    vm.finishRecovery()
    assertThat(vm.ui.value.recovery).isNull()
  }

  @Test
  fun unCodiceCheNonApreIlKitSiPuoRiprovare() = runTest {
    val env = TestEnv()
    env.recovery.kits = NasResult.Ok(listOf(kit()))
    env.recovery.outcome = RecoveryOutcome.WrongCode
    val vm = env.viewModel()
    vm.choosePath(OnboardingPath.RECOVER_ADMIN)
    vm.checkNas(nas)
    vm.submitAccount("marta", "password-lunga".toCharArray())
    vm.kitsContinue()
    vm.chooseFate(OldAdminFate.REVOKED)
    vm.submitRecoveryCodeForKit("SBAGLIATO".toCharArray())
    assertThat(vm.ui.value.recovery?.wrongCode).isTrue()
    assertThat(vm.ui.value.recovery?.page).isEqualTo(RecoveryPage.Code)
    env.recovery.outcome = RecoveryOutcome.Recovered
    vm.submitRecoveryCodeForKit("GIUSTO123".toCharArray())
    assertThat(vm.ui.value.recovery?.page).isEqualTo(RecoveryPage.Done)
  }

  @Test
  fun ilSecondoAdminLasciaApprovatoIlPrimoESaltaLaScelta() = runTest {
    val env = TestEnv()
    env.recovery.kits = NasResult.Ok(listOf(kit()))
    val vm = env.viewModel()
    vm.choosePath(OnboardingPath.SECOND_ADMIN)
    vm.checkNas(nas)
    vm.submitAccount("marta", "password-lunga".toCharArray())
    assertThat(vm.ui.value.recovery?.secondAdmin).isTrue()
    vm.kitsContinue()
    assertThat(vm.ui.value.recovery?.page).isEqualTo(RecoveryPage.Code)
  }

  @Test
  fun senzaKitSiDiceECheNonCeNeSono() = runTest {
    val env = TestEnv()
    env.recovery.kits = NasResult.Ok(emptyList())
    val vm = env.viewModel()
    vm.choosePath(OnboardingPath.RECOVER_ADMIN)
    vm.checkNas(nas)
    vm.submitAccount("marta", "password-lunga".toCharArray())
    assertThat(vm.ui.value.recovery?.noKits).isTrue()
    assertThat(vm.ui.value.recovery?.kitsLoaded).isTrue()
  }

  @Test
  fun unQrCheNonELoStatoDellaFamigliaSiRifiutaNelRecupero() = runTest {
    val env = TestEnv()
    env.recovery.kits = NasResult.Ok(listOf(kit()))
    val vm = env.viewModel()
    vm.choosePath(OnboardingPath.RECOVER_ADMIN)
    vm.checkNas(nas)
    vm.submitAccount("marta", "password-lunga".toCharArray())
    vm.scanFamilyState()
    assertThat(vm.onScanned("MCG1:invito")).isEqualTo(ScanVerdict.Reject(ScanRejection.NOT_OURS))
    assertThat(vm.ui.value.recovery?.rejection).isEqualTo(ScanRejection.NOT_OURS)
  }

  // --- Navigazione ----------------------------------------------------------------------------------

  @Test
  fun inCimaAlCamminoNonSiTornaIndietro() = runTest {
    val vm = TestEnv().viewModel()
    assertThat(vm.canGoBack(vm.progress.value, vm.ui.value)).isFalse()
  }

  @Test
  fun lAnagraficaDelNasNonAmmetteIndirizziNonLetterali() {
    assertThat(NasAddress.parseOrNull("nas.fritz.box")).isNull()
    assertThat(NasAddress.parseOrNull("192.168.178.58")).isNotNull()
  }
}
