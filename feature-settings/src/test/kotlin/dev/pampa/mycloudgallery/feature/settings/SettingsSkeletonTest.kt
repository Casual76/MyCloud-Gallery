package dev.pampa.mycloudgallery.feature.settings

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProgress
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.PendingRequestView
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.family.contract.DeviceView
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.core.family.testing.FakeOnboardingCoordinator
import dev.pampa.mycloudgallery.core.family.testing.FakeRegistryService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsSkeletonTest {

  @Before
  fun setUp() {
    Dispatchers.setMain(UnconfinedTestDispatcher())
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun loStatoUnisceRegistroEPassiDaCompletare() = runTest {
    val registry = FakeRegistryService(status = TrustStatus.Approved(admin = true))
    val coordinator = FakeOnboardingCoordinator(
      OnboardingProgress.START.copy(current = OnboardingStep.SUMMARY, skipped = setOf(OnboardingStep.BATTERY, OnboardingStep.VPN)),
    )
    val viewModel = SettingsViewModel(registry, coordinator)

    viewModel.state.test {
      val first = awaitItem()
      assertThat(first.trust).isEqualTo(TrustStatus.Approved(admin = true))
      assertThat(first.pendingSteps).containsExactly(OnboardingStep.BATTERY, OnboardingStep.VPN).inOrder()

      registry.pendingRequests.value = listOf(
        PendingRequestView("id-1", "Tablet", "SM-X710", "test2", requestedAt = 1, firstSeenAt = 2, windowOpen = true),
      )
      assertThat(awaitItem().pendingRequests).isEqualTo(1)
      cancelAndIgnoreRemainingEvents()
    }
  }

  /**
   * Giro 2 della fase I (CU-29): l'unico admin approvato e' «attenzione» anche nella pagina principale (prima la spunta verde, e il «!» solo nella
   * pagina Famiglia). Con un secondo admin approvato, o se questo dispositivo non e' admin, no.
   */
  @Test
  fun lUnicoAdminSiVedeAncheNellaPaginaPrincipale() = runTest {
    fun device(id: String, admin: Boolean, status: DeviceStatus = DeviceStatus.APPROVED) =
      DeviceView(id, id, "SM-S931B", "test", admin, status, emptySet(), lastSeenAt = null, appVersion = null, isThisDevice = id == "io")
    val registry = FakeRegistryService(status = TrustStatus.Approved(admin = true), devices = listOf(device("io", admin = true), device("tablet", admin = false)))
    val viewModel = SettingsViewModel(registry, FakeOnboardingCoordinator(OnboardingProgress.START))

    viewModel.state.test {
      assertThat(awaitItem().soleAdmin).isTrue()
      registry.devices.value = registry.devices.value + device("vecchio", admin = true, status = DeviceStatus.REVOKED)
      assertThat(awaitItem().soleAdmin).isTrue()
      registry.devices.value = registry.devices.value + device("secondo", admin = true)
      assertThat(awaitItem().soleAdmin).isFalse()
      cancelAndIgnoreRemainingEvents()
    }
    val member = SettingsViewModel(FakeRegistryService(status = TrustStatus.Approved(admin = false), devices = listOf(device("io", admin = false))), FakeOnboardingCoordinator(OnboardingProgress.START))
    member.state.test {
      assertThat(awaitItem().soleAdmin).isFalse()
      cancelAndIgnoreRemainingEvents()
    }
  }
}
