package dev.pampa.mycloudgallery.feature.settings

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProgress
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.PendingRequestView
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
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
}
