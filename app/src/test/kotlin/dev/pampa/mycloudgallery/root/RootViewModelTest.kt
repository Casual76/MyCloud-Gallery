package dev.pampa.mycloudgallery.root

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.BlockReason
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProgress
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.core.family.testing.FakeFamilySetupRepository
import dev.pampa.mycloudgallery.core.nas.contract.NasConnState
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasSessionManager
import dev.pampa.mycloudgallery.core.network.testing.FakeNetworkEnvironment
import dev.pampa.mycloudgallery.core.network.testing.FakeVpnController
import dev.pampa.mycloudgallery.core.network.testing.Snapshots
import dev.pampa.mycloudgallery.core.security.AppLockMode
import dev.pampa.mycloudgallery.core.security.lock.AppLockController
import dev.pampa.mycloudgallery.core.security.lock.AppLockSettings
import dev.pampa.mycloudgallery.core.security.lock.AppLockState
import dev.pampa.mycloudgallery.navigation.AwaitingApprovalRoute
import dev.pampa.mycloudgallery.navigation.BlockedRoute
import dev.pampa.mycloudgallery.navigation.MainRoute
import dev.pampa.mycloudgallery.navigation.WipedRoute
import dev.pampa.mycloudgallery.navigation.startRoute
import dev.pampa.mycloudgallery.feature.onboarding.OnboardingGraphRoute
import dev.pampa.mycloudgallery.status.GlobalStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Il RootViewModel con i fake (P0): ogni stato del dispositivo porta alla sua destinazione e alla sua rotta
 * di partenza; l'indicatore segue rete e NAS; il blocco app riceve le sue impostazioni.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RootViewModelTest {

  @Before
  fun setUp() {
    Dispatchers.setMain(UnconfinedTestDispatcher())
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private class Rig(initial: SetupState, lockSettings: AppLockSettings = AppLockSettings()) {
    val setup = FakeFamilySetupRepository(initial)
    val network = FakeNetworkEnvironment(Snapshots.offline())
    val session = FakeNasSessionManager()
    val lock = AppLockController { 0L }
    val viewModel = RootViewModel(setup, network, session, FakeVpnController(), lock, flowOf(lockSettings))
  }

  @Test
  fun ogniStatoHaLaSuaDestinazione() = runTest {
    val rig = Rig(SetupState.Loading)
    assertThat(rig.viewModel.destination.value).isEqualTo(RootDestination.Loading)

    val expectations = listOf(
      SetupState.NotConfigured to RootDestination.Onboarding,
      SetupState.Onboarding(OnboardingProgress.START) to RootDestination.Onboarding,
      SetupState.AwaitingApproval(code = "K7QD-2M9X", since = 0L) to RootDestination.AwaitingApproval("K7QD-2M9X"),
      SetupState.Ready(admin = false) to RootDestination.Main,
      SetupState.Wiped(at = 0L) to RootDestination.Wiped,
      SetupState.Blocked(BlockReason.NEEDS_APP_UPDATE) to RootDestination.Blocked(BlockReason.NEEDS_APP_UPDATE),
    )
    for ((state, destination) in expectations) {
      rig.setup.state.value = state
      assertThat(rig.viewModel.destination.value).isEqualTo(destination)
    }
  }

  @Test
  fun ogniDestinazioneHaLaSuaRottaDiPartenza() {
    assertThat(startRoute(RootDestination.Onboarding)).isEqualTo(OnboardingGraphRoute)
    assertThat(startRoute(RootDestination.AwaitingApproval("ABCD"))).isEqualTo(AwaitingApprovalRoute("ABCD"))
    assertThat(startRoute(RootDestination.Main)).isEqualTo(MainRoute)
    assertThat(startRoute(RootDestination.Wiped)).isEqualTo(WipedRoute)
    val blocked = startRoute(RootDestination.Blocked(BlockReason.STORAGE_LOCKED)) as BlockedRoute
    assertThat(blocked.reason).isEqualTo(BlockReason.STORAGE_LOCKED)
  }

  @Test
  fun lIndicatoreSegueReteENas() = runTest {
    val rig = Rig(SetupState.Ready(admin = true))
    assertThat(rig.viewModel.status.value).isEqualTo(GlobalStatus.OFFLINE)
    rig.network.current = Snapshots.home()
    assertThat(rig.viewModel.status.value).isEqualTo(GlobalStatus.HOME_IDLE)
    rig.session.state.value = NasConnState.WakingUp(since = 1L)
    assertThat(rig.viewModel.status.value).isEqualTo(GlobalStatus.WAKING_NAS)
  }

  @Test
  fun ilBloccoAppRiceveLeImpostazioni() = runTest {
    val settings = MutableStateFlow(AppLockSettings(mode = AppLockMode.SYSTEM_CREDENTIAL))
    val lock = AppLockController { 0L }
    RootViewModel(
      FakeFamilySetupRepository(SetupState.Ready(admin = true)),
      FakeNetworkEnvironment(),
      FakeNasSessionManager(),
      FakeVpnController(),
      lock,
      settings,
    )
    // Blocco attivo all'avvio: si parte bloccati (fail closed), mai dalla galleria aperta.
    assertThat(lock.state.value).isEqualTo(AppLockState.LOCKED)
  }
}
