package dev.pampa.mycloudgallery.feature.settings.family

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.FamilyInvite
import dev.pampa.mycloudgallery.core.family.contract.OnboardingPath
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProblem
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProgress
import dev.pampa.mycloudgallery.core.family.contract.SavedNas
import dev.pampa.mycloudgallery.core.family.contract.StaleReason
import dev.pampa.mycloudgallery.core.family.contract.StepResult
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.family.testing.FakeOnboardingCoordinator
import dev.pampa.mycloudgallery.core.family.testing.FakeQrPayloadCodec
import dev.pampa.mycloudgallery.core.family.testing.FakeRegistryService
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasSessionManager
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.feature.settings.security.BackupNoticeViewModel
import dev.pampa.mycloudgallery.feature.settings.support.MainDispatcherRule
import dev.pampa.mycloudgallery.feature.settings.support.Samples
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * La verifica funzionale delle fasi 1-5A (voci ON) nelle Impostazioni: "Rientra con un QR nuovo" (ON-5), la checklist
 * solo dove il passo 13 c'e' (ON-7), la guida "ricominciare da zero" con il percorso vero (ON-6), i dati per l'ingresso
 * senza QR sulla pagina dell'invito (ON-1).
 */
class VerificaFunzionaleImpostazioniTest {

  @get:Rule
  val main = MainDispatcherRule()

  private fun progressOf(path: OnboardingPath) = OnboardingProgress.START.copy(path = path, plan = FakeOnboardingCoordinator.planFor(path))

  // --- ON-5 -----------------------------------------------------------------------------------------------------------

  @Test
  fun on5_dopoUnResetIlDispositivoVedeDoveRientrare() {
    val stale = FamilyUiState(TrustStatus.Stale(StaleReason.NEWER_UNVERIFIED), deviceCount = 2, adminCount = 1)
    assertThat(stale.isMember).isFalse()
    assertThat(stale.canRejoin).isTrue()
    assertThat(stale.newerUnverified).isTrue()
    assertThat(FamilyUiState(TrustStatus.Approved(admin = false), 2, 1).canRejoin).isTrue()
    assertThat(FamilyUiState(TrustStatus.NotConfigured, 0, 0).canRejoin).isFalse()
  }

  @Test
  fun on5_rientrareChiedeConfermaEPoiDimenticaLaFamigliaDiPrima() = runTest {
    val coordinator = FakeOnboardingCoordinator(progressOf(OnboardingPath.JOIN_QR))
    val vm = FamilyRejoinViewModel(FakeRegistryService(TrustStatus.Stale(StaleReason.NEWER_UNVERIFIED)), coordinator)
    assertThat(vm.state.value.isAdmin).isFalse()
    // Senza il foglio di conferma non parte niente.
    vm.confirm()
    assertThat(coordinator.startOvers).isEqualTo(0)
    vm.ask()
    vm.cancel()
    vm.confirm()
    assertThat(coordinator.startOvers).isEqualTo(0)
    vm.ask()
    vm.confirm()
    assertThat(coordinator.startOvers).isEqualTo(1)
    assertThat(vm.state.value.stage).isEqualTo(RejoinStage.DONE)
  }

  @Test
  fun on5_unAdminLoSaPrimaEUnErroreSiVede() = runTest {
    val coordinator = object : dev.pampa.mycloudgallery.core.family.contract.OnboardingCoordinator by FakeOnboardingCoordinator() {
      override suspend fun startOver(): StepResult = StepResult.Problem(OnboardingProblem.STEP_NOT_AVAILABLE)
    }
    val vm = FamilyRejoinViewModel(FakeRegistryService(TrustStatus.Approved(admin = true)), coordinator)
    assertThat(vm.state.value.isAdmin).isTrue()
    vm.ask()
    vm.confirm()
    assertThat(vm.state.value.stage).isEqualTo(RejoinStage.INTRO)
    assertThat(vm.state.value.feedback).isNotNull()
  }

  // --- ON-7 -----------------------------------------------------------------------------------------------------------

  @Test
  fun on7_laChecklistSiRivedeSoloSeIlPasso13ENelPiano() {
    assertThat(BackupNoticeViewModel(FakeOnboardingCoordinator(progressOf(OnboardingPath.FOUND_FAMILY))).canReview.value).isTrue()
    for (path in listOf(OnboardingPath.JOIN_QR, OnboardingPath.JOIN_MANUAL, OnboardingPath.RECOVER_ADMIN, OnboardingPath.SECOND_ADMIN)) {
      assertThat(BackupNoticeViewModel(FakeOnboardingCoordinator(progressOf(path))).canReview.value).isFalse()
    }
  }

  // --- ON-6 -----------------------------------------------------------------------------------------------------------

  @Test
  fun on6_laGuidaDaIlPercorsoEsattoDellaCartellaDellaFamiglia() = runTest {
    val coordinator = FakeOnboardingCoordinator()
    coordinator.saved = SavedNas(NasAddress("192.168.178.58", "MyCloudEX2Ultra.fritz.box"), "anna", "NAS", "anna")
    assertThat(StartOverGuideViewModel(coordinator).folder.value).isEqualTo("\\\\192.168.178.58\\NAS\\.mycloudgallery\\family")
    // Senza area comune la guida lo descrive a parole.
    assertThat(StartOverGuideViewModel(FakeOnboardingCoordinator()).folder.value).isNull()
  }

  // --- ON-1 (lato admin) ----------------------------------------------------------------------------------------------

  @Test
  fun on1_laPaginaDellInvitoMostraIDatiPerChiEntraSenzaQr() = runTest {
    val invite = FamilyInvite(
      familyId = "0123456789abcdefghjkmnpqrs",
      familyKeyFingerprint = "abcdefghjkmnpqrstvwxyz0123",
      minRegistryVersion = 3,
      nasIp = "192.168.178.58",
      nasName = null,
      nasGuid = "guid",
      homeUla = listOf("fd12:3456:789a::/48"),
      commonShare = "NAS",
      privateShare = "anna",
      nasUser = "anna",
      deviceName = "Telefono di Anna",
      presetName = null,
      expiresAtNas = Samples.NOW + 24.hours.inWholeMilliseconds,
    )
    val registry = FakeRegistryService(TrustStatus.Approved(admin = true)).also { it.invite = invite }
    val vm = InviteViewModel(registry, FakeQrPayloadCodec(mutableMapOf("MCG1:QR" to invite)), FakeNasSessionManager(), Samples.clock)
    vm.setUser("anna")
    vm.setDeviceName("Telefono di Anna")
    vm.create()
    val summary = vm.state.value.summary!!
    assertThat(summary.nasIp).isEqualTo("192.168.178.58")
    assertThat(summary.commonShare).isEqualTo("NAS")
    assertThat(summary.familyId).isEqualTo(invite.familyId)
    assertThat(summary.fingerprint).isEqualTo(invite.familyKeyFingerprint)
  }
}
