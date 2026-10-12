package dev.pampa.mycloudgallery.feature.settings.devices

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.AdminActionResult
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.family.testing.FakeLostReporting
import dev.pampa.mycloudgallery.core.family.testing.FakeRegistryService
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasSessionManager
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.feature.settings.support.MainDispatcherRule
import dev.pampa.mycloudgallery.feature.settings.support.Samples
import dev.pampa.mycloudgallery.feature.settings.support.keepAlive
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class DevicesViewModelTest {

  @get:Rule
  val main = MainDispatcherRule()

  private fun viewModel(
    registry: FakeRegistryService,
    lost: FakeLostReporting = FakeLostReporting(),
  ) = DevicesViewModel(registry, lost, FakeNasSessionManager(), Samples.clock)

  @Test
  fun laListaDividePerStatoEMetteQuestoDispositivoPerPrimo() = runTest {
    val registry = FakeRegistryService(TrustStatus.Approved(admin = true), Samples.familyAsAdmin(), listOf(Samples.request))
    val vm = viewModel(registry)
    keepAlive(vm.state)

    val state = vm.state.value
    assertThat(state.thisDevice?.id).isEqualTo(Samples.ADMIN_PHONE)
    assertThat(state.others.map { it.id }).containsExactly(Samples.TABLET_MAMMA, Samples.PHONE_ANNA).inOrder()
    assertThat(state.inactive.map { it.status }).containsExactly(DeviceStatus.REVOKED, DeviceStatus.LOST, DeviceStatus.RETIRED).inOrder()
    assertThat(state.requests.map { it.id }).containsExactly(Samples.REQUEST_ID)
    assertThat(state.isAdmin).isTrue()
    assertThat(state.activeCount).isEqualTo(3)
  }

  @Test
  fun lUltimaAttivitaESempreUnaDurataNonNegativaSullOraDelNas() = runTest {
    val registry = FakeRegistryService(TrustStatus.Approved(admin = true), Samples.familyAsAdmin())
    val vm = viewModel(registry)
    keepAlive(vm.state)

    val tablet = vm.state.value.others.first { it.id == Samples.TABLET_MAMMA }
    assertThat(tablet.lastSeenAgoMs).isEqualTo(3 * 60 * 60 * 1000L)
    // Un dispositivo che non ha mai pubblicato il proprio stato non ha un "visto".
    assertThat(vm.state.value.inactive.first { it.id == Samples.REVOKED_TABLET }.lastSeenAgoMs).isNull()
  }

  @Test
  fun unDispositivoCheSembraUnaReinstallazioneNominaIlSosia() = runTest {
    val registry = FakeRegistryService(
      TrustStatus.Approved(admin = true),
      Samples.familyAsAdmin() + Samples.reinstalled,
      listOf(Samples.lookAlikeRequest),
    )
    val vm = viewModel(registry)
    keepAlive(vm.state)

    assertThat(vm.state.value.others.first { it.id == Samples.REINSTALLED }.lookAlikeOfName).isEqualTo("Telefono di Alessio")
    assertThat(vm.state.value.requests.single().lookAlikeOfName).isEqualTo("Telefono di Alessio")
  }

  @Test
  fun apreRileggeIlRegistroInModoInterattivo() = runTest {
    val registry = FakeRegistryService(TrustStatus.Approved(admin = true), Samples.familyAsAdmin())
    val vm = viewModel(registry)
    vm.refresh()
    assertThat(registry.refreshes).containsExactly(Purpose.INTERACTIVE)
  }

  @Test
  fun leSegnalazioniDiSmarrimentoLeConvertePerLAdminEMostraLEsito() = runTest {
    val lost = FakeLostReporting(AdminActionResult.NewerExists)
    val registry = FakeRegistryService(TrustStatus.Approved(admin = true), Samples.familyAsAdmin())
    val vm = viewModel(registry, lost)
    keepAlive(vm.state)

    vm.convertReports()
    assertThat(vm.state.value.feedback).isNotNull()
    vm.dismissFeedback()
    assertThat(vm.state.value.feedback).isNull()
  }

  @Test
  fun unDispositivoNonAdminNonVedeLeAzioniDaAdmin() = runTest {
    val registry = FakeRegistryService(TrustStatus.Approved(admin = false), Samples.familyAsMember())
    val vm = viewModel(registry)
    keepAlive(vm.state)

    assertThat(vm.state.value.isAdmin).isFalse()
    assertThat(vm.state.value.thisDevice?.id).isEqualTo(Samples.PHONE_ANNA)
  }
}
