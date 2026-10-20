package dev.pampa.mycloudgallery.root

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.BlockReason
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProgress
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.core.family.contract.StepResult
import dev.pampa.mycloudgallery.core.family.testing.FakeFamilySetupRepository
import dev.pampa.mycloudgallery.core.family.testing.FakeOnboardingCoordinator
import dev.pampa.mycloudgallery.core.nas.contract.NasConnState
import dev.pampa.mycloudgallery.core.nas.contract.SessionInfo
import dev.pampa.mycloudgallery.core.nas.contract.SmbDialect
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasSessionManager
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.NasRoute
import dev.pampa.mycloudgallery.core.network.contract.ProbeResult
import dev.pampa.mycloudgallery.core.network.contract.Via
import dev.pampa.mycloudgallery.core.network.testing.FakeNasRouteGate
import dev.pampa.mycloudgallery.core.network.testing.FakeNetworkEnvironment
import dev.pampa.mycloudgallery.core.network.testing.FakeReachabilityProbe
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
import dev.pampa.mycloudgallery.graph.ObservedReachabilityProbe
import dev.pampa.mycloudgallery.status.GlobalStatus
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
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
      // R7: il Keystore che non risponde e' una pagina ferma sua, mai l'onboarding ("fuori dalla famiglia").
      SetupState.Blocked(BlockReason.KEYSTORE_UNAVAILABLE) to RootDestination.Blocked(BlockReason.KEYSTORE_UNAVAILABLE),
    )
    for ((state, destination) in expectations) {
      rig.setup.state.value = state
      assertThat(rig.viewModel.destination.value).isEqualTo(destination)
    }
  }

  @Test
  fun ogniDestinazioneHaLaSuaRottaDiPartenza() {
    assertThat(startRoute(RootDestination.Onboarding)).isEqualTo(OnboardingGraphRoute)
    assertThat(startRoute(RootDestination.AwaitingApproval("ABCD"))).isEqualTo(AwaitingApprovalRoute)
    assertThat(startRoute(RootDestination.Main)).isEqualTo(MainRoute)
    assertThat(startRoute(RootDestination.Wiped)).isEqualTo(WipedRoute)
    val blocked = startRoute(RootDestination.Blocked(BlockReason.STORAGE_LOCKED)) as BlockedRoute
    assertThat(blocked.reason).isEqualTo(BlockReason.STORAGE_LOCKED)
  }

  // --- R7: la pagina "Keystore non disponibile" ------------------------------------------------------------------------

  /**
   * Le porte della pagina sopra il coordinatore finto (allineato al vero: `CorrezioniConfigurazione5BTest` di core-family) e un
   * Keystore che si guarisce dal test. "Riprova" guarito riporta lo stato di prima (qui: la Libreria).
   */
  private class KeystorePorts(val coordinator: FakeOnboardingCoordinator, val setup: FakeFamilySetupRepository) : RootPorts by RootPorts.None {
    var rechecks = 0

    override suspend fun recheckKeys(): Boolean {
      rechecks++
      if (!coordinator.keystoreDown) setup.state.value = SetupState.Ready(admin = true)
      return !coordinator.keystoreDown
    }

    override suspend fun holdsFamilyKey(): Boolean = coordinator.holdsFamilyKey()

    override suspend fun startOver(): StepResult =
      coordinator.startOver().also { if (it is StepResult.Advanced) setup.state.value = SetupState.NotConfigured }
  }

  private class KeystoreRig(member: Boolean = true) {
    val setup = FakeFamilySetupRepository(SetupState.Blocked(BlockReason.KEYSTORE_UNAVAILABLE))
    val coordinator = FakeOnboardingCoordinator().apply {
      finished = true
      if (member) joinRegistered = true else founded = true
      keystoreDown = true
    }
    val ports = KeystorePorts(coordinator, setup)
    val viewModel = RootViewModel(
      setup, FakeNetworkEnvironment(Snapshots.offline()), FakeNasSessionManager(), FakeVpnController(), AppLockController { 0L }, flowOf(AppLockSettings()), ports,
    )
  }

  @Test
  fun r7_riprovaRicalcolaLoStatoEConIlKeystoreGuaritoSiTornaAllaLibreria() = runTest {
    val rig = KeystoreRig()
    val vm = rig.viewModel
    assertThat(vm.destination.value).isEqualTo(RootDestination.Blocked(BlockReason.KEYSTORE_UNAVAILABLE))
    assertThat(vm.keystore.value).isEqualTo(KeystoreUi())

    // Ancora guasto: la pagina resta e lo dice.
    vm.retryKeystore()
    assertThat(rig.ports.rechecks).isEqualTo(1)
    assertThat(vm.keystore.value.retrying).isFalse()
    assertThat(vm.keystore.value.stillUnavailable).isTrue()
    assertThat(vm.destination.value).isEqualTo(RootDestination.Blocked(BlockReason.KEYSTORE_UNAVAILABLE))

    // Guarito: lo stato di prima, e la pagina, se tornasse, riparte pulita.
    rig.coordinator.keystoreDown = false
    vm.retryKeystore()
    assertThat(rig.ports.rechecks).isEqualTo(2)
    assertThat(vm.destination.value).isEqualTo(RootDestination.Main)
    assertThat(vm.keystore.value).isEqualTo(KeystoreUi())
    // "Riprova" non tocca mai niente.
    assertThat(rig.coordinator.startOvers).isEqualTo(0)
  }

  @Test
  fun r7_configuraDiNuovoPassaSoloDalFoglioConLAvvisoDellAdmin() = runTest {
    val rig = KeystoreRig(member = true)
    val vm = rig.viewModel

    // Senza il foglio aperto la conferma non fa niente (un tocco fuori posto, un doppio tocco).
    vm.confirmKeystoreStartOver()
    assertThat(rig.coordinator.startOvers).isEqualTo(0)

    vm.askKeystoreStartOver()
    assertThat(vm.keystore.value.confirming).isTrue()
    // Con il Keystore che non risponde non si sa se e' un admin: nel dubbio il foglio dice che si perde la chiave di famiglia.
    assertThat(vm.keystore.value.holdsKey).isTrue()
    assertThat(rig.coordinator.startOvers).isEqualTo(0)

    vm.cancelKeystoreStartOver()
    assertThat(vm.keystore.value.confirming).isFalse()
    vm.confirmKeystoreStartOver()
    assertThat(rig.coordinator.startOvers).isEqualTo(0)
    assertThat(vm.destination.value).isEqualTo(RootDestination.Blocked(BlockReason.KEYSTORE_UNAVAILABLE))
  }

  @Test
  fun r7_configuraDiNuovoConfermatoConIlKeystoreGuastoNonToccaNienteEPoiSiRiprovaDalFoglio() = runTest {
    val rig = KeystoreRig(member = false)
    val vm = rig.viewModel
    vm.askKeystoreStartOver()
    assertThat(vm.keystore.value.holdsKey).isTrue()

    vm.confirmKeystoreStartOver()

    assertThat(rig.coordinator.startOvers).isEqualTo(1)
    assertThat(vm.keystore.value.problem).isEqualTo(KeystoreProblem.KEYS_NOT_REMOVED)
    assertThat(vm.keystore.value.busy).isFalse()
    assertThat(vm.keystore.value.confirming).isFalse()
    assertThat(rig.coordinator.finished).isTrue() // niente toccato
    assertThat(vm.destination.value).isEqualTo(RootDestination.Blocked(BlockReason.KEYSTORE_UNAVAILABLE))

    // Chi vuole davvero ricominciare (la chiave e' guasta per sempre, il Keystore risponde): di nuovo dal foglio, poi il benvenuto.
    rig.coordinator.keystoreDown = false
    vm.confirmKeystoreStartOver()
    assertThat(rig.coordinator.startOvers).isEqualTo(1)
    vm.askKeystoreStartOver()
    assertThat(vm.keystore.value.problem).isNull()
    vm.confirmKeystoreStartOver()
    assertThat(rig.coordinator.startOvers).isEqualTo(2)
    assertThat(vm.destination.value).isEqualTo(RootDestination.Onboarding)
    assertThat(vm.keystore.value).isEqualTo(KeystoreUi())
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

  /**
   * Verifica funzionale, RE-6: dopo una sonda fallita l'indicatore restava «NAS non raggiungibile» anche col NAS tornato. L'esito si
   * dimenticava solo al cambio di percorso, quindi alla chiusura della sessione (a riposo) tornava a galla la sonda vecchia. Qui la sonda e'
   * quella vera dell'app (`ObservedReachabilityProbe`), legata alla sessione.
   */
  @Test
  fun dopoUnaSondaFallitaIlNasTornatoNonRestaIrraggiungibile() = runTest {
    val network = FakeNetworkEnvironment(Snapshots.home())
    val session = FakeNasSessionManager()
    val gate = FakeNasRouteGate().apply { allow() }
    val real = ObservedReachabilityProbe(FakeReachabilityProbe(ProbeResult.TimedOut), gate, backgroundScope, session.state)
    runCurrent()
    val ports = object : RootPorts by RootPorts.None {
      override val probe: StateFlow<ProbeResult?> = real.lastResult
    }
    val viewModel = RootViewModel(FakeFamilySetupRepository(SetupState.Ready(admin = true)), network, session, FakeVpnController(), AppLockController { 0L }, flowOf(AppLockSettings()), ports)

    // Il NAS e' spento: la sonda fallisce e, a sessione a riposo, l'indicatore lo dice.
    val route = gate.current() as NasRoute.Allowed
    real.tcp445(route, NasAddress("192.168.178.58"), 6.seconds)
    assertThat(viewModel.status.value).isEqualTo(GlobalStatus.NAS_UNREACHABLE)

    // Il NAS e' tornato: una sessione si apre...
    session.state.value = NasConnState.Ready(
      SessionInfo(SmbDialect.SMB_3_1_1, encrypted = true, cipher = "AES-128-GCM", signingRequired = true, serverGuid = "g", nasTimeOffsetMs = 0, via = Via.HOME_LAN),
    )
    runCurrent()
    assertThat(viewModel.status.value).isEqualTo(GlobalStatus.HOME_CONNECTED)
    // ...e si chiude per inattivita': non torna a galla la sonda di prima.
    session.state.value = NasConnState.Idle
    runCurrent()
    assertThat(viewModel.status.value).isEqualTo(GlobalStatus.HOME_IDLE)
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
