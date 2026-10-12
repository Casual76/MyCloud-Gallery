package dev.pampa.mycloudgallery.feature.settings.family

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.FamilyInvite
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.family.testing.FakeQrPayloadCodec
import dev.pampa.mycloudgallery.core.family.testing.FakeRegistryService
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasSessionManager
import dev.pampa.mycloudgallery.core.rules.Preset
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.support.MainDispatcherRule
import dev.pampa.mycloudgallery.feature.settings.support.Samples
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.hours

class InviteViewModelTest {

  @get:Rule
  val main = MainDispatcherRule()

  private val invite = FamilyInvite(
    familyId = "famiglia",
    familyKeyFingerprint = "impronta",
    minRegistryVersion = 3,
    nasIp = "192.168.178.58",
    nasName = "wdmycloud",
    nasGuid = "guid",
    homeUla = listOf("fd12:3456:789a::/48"),
    commonShare = "comune",
    privateShare = "anna",
    nasUser = "anna",
    deviceName = "Telefono di Anna",
    presetName = null,
    expiresAtNas = Samples.NOW + 24.hours.inWholeMilliseconds,
  )

  private val codec = FakeQrPayloadCodec<FamilyInvite>(mutableMapOf("MCG1:QR-DI-PROVA" to invite))

  private fun viewModel(admin: Boolean = true, registry: FakeRegistryService = FakeRegistryService(TrustStatus.Approved(admin))) =
    InviteViewModel(registry.also { it.invite = invite }, codec, FakeNasSessionManager(), Samples.clock) to registry

  @Test
  fun conICampiGiustiMostraIlQrEQuantoVale() = runTest {
    val (vm, _) = viewModel()
    vm.setUser("anna")
    vm.setDeviceName("Telefono di Anna")
    vm.create()

    val state = vm.state.value
    assertThat(state.phase).isEqualTo(InvitePhase.SHOWING)
    assertThat(state.qrText).isEqualTo("MCG1:QR-DI-PROVA")
    assertThat(state.summary?.hoursLeft).isEqualTo(24)
    // L'area privata vuota vale quella con lo stesso nome dell'utente.
    assertThat(state.summary?.privateShare).isEqualTo("anna")
  }

  @Test
  fun lAccountAdminDelNasNonSiUsaMaiNemmenoInMaiuscolo() = runTest {
    val (vm, registry) = viewModel()
    vm.setUser("Admin")
    vm.setDeviceName("Telefono")
    vm.create()

    assertThat(vm.state.value.userError).isEqualTo(R.string.invite_error_admin)
    assertThat(vm.state.value.phase).isEqualTo(InvitePhase.FORM)
    assertThat(registry.invite).isNotNull()
  }

  @Test
  fun icampiNonValidiDannoUnErroreCiascunoESenzaScrivereNiente() = runTest {
    val (vm, _) = viewModel()
    vm.setUser("anna rossi")
    vm.setDeviceName(" con spazi ai bordi ")
    vm.create()

    assertThat(vm.state.value.userError).isEqualTo(R.string.invite_error_user)
    assertThat(vm.state.value.nameError).isEqualTo(R.string.invite_error_name)
    assertThat(vm.state.value.phase).isEqualTo(InvitePhase.FORM)
    assertThat(vm.state.value.qrText).isNull()
  }

  @Test
  fun unNomeDiCartellaNonValidoDaErrore() = runTest {
    val (vm, _) = viewModel()
    vm.setUser("anna")
    vm.setDeviceName("Telefono")
    vm.setPrivateShare("../altrove")
    vm.create()
    assertThat(vm.state.value.shareError).isEqualTo(R.string.invite_error_share)
  }

  @Test
  fun soloLAdminCreaGliInviti() = runTest {
    val (vm, registry) = viewModel(admin = false)
    vm.setUser("anna")
    vm.setDeviceName("Telefono")
    vm.create()
    assertThat(vm.state.value.isAdmin).isFalse()
    assertThat(vm.state.value.phase).isEqualTo(InvitePhase.FORM)
    assertThat(registry.invite).isNotNull()
  }

  @Test
  fun seLaCodificaNonRiesceNonMostraNessunQrFintoEDiceCheNonHaScrittoNiente() = runTest {
    val registry = FakeRegistryService(TrustStatus.Approved(admin = true))
    val failing = FakeQrPayloadCodec<FamilyInvite>()
    val vm = InviteViewModel(registry.also { it.invite = invite }, failing, FakeNasSessionManager(), Samples.clock)
    vm.setUser("anna")
    vm.setDeviceName("Telefono")
    vm.create()

    assertThat(vm.state.value.phase).isEqualTo(InvitePhase.FORM)
    assertThat(vm.state.value.qrText).isNull()
    assertThat(vm.state.value.feedback).isNotNull()
  }

  @Test
  fun ilPresetScelto_vaNelleParametri_eDefaultSignificaNessuno() = runTest {
    val registry = FakeRegistryService(TrustStatus.Approved(admin = true))
    val captured = mutableListOf<String?>()
    val spy = object : dev.pampa.mycloudgallery.core.family.contract.RegistryService by registry {
      override suspend fun createInvite(params: dev.pampa.mycloudgallery.core.family.contract.InviteParams): FamilyInvite {
        captured += params.presetName
        return invite
      }
    }
    val vm = InviteViewModel(spy, codec, FakeNasSessionManager(), Samples.clock)
    vm.setUser("anna")
    vm.setDeviceName("Telefono")
    vm.setPreset(Preset.NIGHT)
    vm.create()
    vm.reset()
    vm.setUser("anna")
    vm.setDeviceName("Telefono")
    vm.create()

    assertThat(captured).containsExactly("NIGHT", null).inOrder()
  }

  @Test
  fun ilTestoDelQrNonCompareNelleStringheDiStato() = runTest {
    val (vm, _) = viewModel()
    vm.setUser("anna")
    vm.setDeviceName("Telefono")
    vm.create()
    assertThat(vm.state.value.toString()).doesNotContain("MCG1")
    vm.reset()
    assertThat(vm.state.value.qrText).isNull()
  }

  @Test
  fun inviiLimitatiAlPresetUtile_nonIncludonoIlPersonalizzato() {
    assertThat(InvitablePresets).doesNotContain(Preset.CUSTOM)
    assertThat(InvitablePresets).hasSize(4)
  }
}
