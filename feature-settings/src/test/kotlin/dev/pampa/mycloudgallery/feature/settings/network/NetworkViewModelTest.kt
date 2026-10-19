package dev.pampa.mycloudgallery.feature.settings.network

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.AdminActionResult
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.family.testing.FakeRegistryService
import dev.pampa.mycloudgallery.core.nas.contract.ConnectionReport
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.Stage
import dev.pampa.mycloudgallery.core.nas.contract.StageOutcome
import dev.pampa.mycloudgallery.core.nas.contract.StageResult
import dev.pampa.mycloudgallery.core.nas.testing.FakeConnectionTester
import dev.pampa.mycloudgallery.core.network.contract.ControlPermissionOutcome
import dev.pampa.mycloudgallery.core.network.contract.DenyReason
import dev.pampa.mycloudgallery.core.network.contract.HomeCandidate
import dev.pampa.mycloudgallery.core.network.contract.HomeFingerprint
import dev.pampa.mycloudgallery.core.network.contract.Via
import dev.pampa.mycloudgallery.core.network.contract.VpnAutomationStatus
import dev.pampa.mycloudgallery.core.network.contract.VpnCapability
import dev.pampa.mycloudgallery.core.network.contract.VpnCommandResult
import dev.pampa.mycloudgallery.core.network.contract.VpnControlConfig
import dev.pampa.mycloudgallery.core.network.contract.VpnSuspendReason
import dev.pampa.mycloudgallery.core.network.testing.FakeHomeCapture
import dev.pampa.mycloudgallery.core.network.testing.FakeHomeFingerprintStore
import dev.pampa.mycloudgallery.core.network.testing.FakeNasRouteGate
import dev.pampa.mycloudgallery.core.network.testing.FakeNetworkEnvironment
import dev.pampa.mycloudgallery.core.network.testing.FakeVpnAutomation
import dev.pampa.mycloudgallery.core.network.testing.FakeVpnController
import dev.pampa.mycloudgallery.core.network.testing.FakeWireGuardPlatform
import dev.pampa.mycloudgallery.core.network.testing.InMemoryVpnControlConfigStore
import dev.pampa.mycloudgallery.core.network.testing.Snapshots
import dev.pampa.mycloudgallery.core.network.vpn.WireGuardVpnController
import dev.pampa.mycloudgallery.feature.settings.support.FakeNasAccountSettings
import dev.pampa.mycloudgallery.feature.settings.support.MainDispatcherRule
import dev.pampa.mycloudgallery.feature.settings.support.Samples
import dev.pampa.mycloudgallery.feature.settings.support.keepAlive
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.util.Optional
import kotlin.time.Duration.Companion.milliseconds

class NetworkViewModelTest {

  @get:Rule
  val main = MainDispatcherRule()

  private val environment = FakeNetworkEnvironment(Snapshots.home())
  private val gate = FakeNasRouteGate().apply { allow(Via.HOME_LAN) }
  private val store = FakeHomeFingerprintStore(HomeFingerprint(setOf("fd12:3456:789a::/48"), "192.168.178.1", "fritz.box", capturedAt = 1_000L))
  private val capture = FakeHomeCapture()
  private val vpn = FakeVpnController()
  private val vpnConfig = InMemoryVpnControlConfigStore(VpnControlConfig(enabled = true, tunnelName = "casa"))
  private val automation = FakeVpnAutomation()
  private val registry = FakeRegistryService(status = TrustStatus.Approved(admin = true))
  private val tester = FakeConnectionTester()

  private val candidate = HomeCandidate(
    networkId = "wifi-1",
    ulaPrefixes = setOf("fd99:aaaa:bbbb::/48"),
    gatewayV4 = "192.168.178.1",
    dnsDomain = "fritz.box",
    ipv4Subnet = "192.168.178.0/24",
  )

  private fun passed() = ConnectionReport(
    stages = listOf(Stage.ROUTE, Stage.TCP, Stage.NEGOTIATE, Stage.GUID).map { StageResult(it, StageOutcome.PASSED, 1.milliseconds) },
    negotiation = null,
  )

  private fun viewModel(
    accounts: FakeNasAccountSettings? = FakeNasAccountSettings(),
    controller: dev.pampa.mycloudgallery.core.network.contract.VpnController = vpn,
  ) = NetworkViewModel(
    environment = environment,
    gate = gate,
    fingerprintStore = store,
    homeCapture = capture,
    vpn = controller,
    automation = automation,
    vpnConfig = vpnConfig,
    tester = tester,
    accountSettings = Optional.ofNullable(accounts),
    registry = registry,
    clock = Samples.clock,
  )

  @Test
  fun inCasaMostraIlPercorsoELImprontaNota() = runTest {
    val vm = viewModel()
    keepAlive(vm.state)
    assertThat(vm.state.value.route).isEqualTo(RouteUi.Allowed(Via.HOME_LAN))
    assertThat(vm.state.value.fingerprint?.ulaPrefixes).hasSize(1)
    assertThat(vm.state.value.vpnCapability).isEqualTo(VpnCapability.READY)
  }

  @Test
  fun unPercorsoNegatoDiceIlMotivo() = runTest {
    gate.deny(DenyReason.UNTRUSTED_NETWORK)
    environment.current = Snapshots.untrusted()
    val vm = viewModel()
    keepAlive(vm.state)
    assertThat(vm.state.value.route).isEqualTo(RouteUi.Denied(DenyReason.UNTRUSTED_NETWORK))
  }

  @Test
  fun laNuovaImprontaSiSalvaSoloDopoLaVerificaDelNasELaConfermaDellUtente() = runTest {
    capture.candidate = candidate
    tester.report = passed()
    val vm = viewModel()
    keepAlive(vm.state)

    vm.startCapture()
    val ready = vm.state.value.capture as CaptureUi.Ready
    assertThat(ready.sameAsSaved).isFalse()
    // Verificata ma non salvata: l'impronta di prima c'e' ancora.
    assertThat(store.fingerprint.value?.ulaPrefixes).containsExactly("fd12:3456:789a::/48")
    assertThat(tester.credentialsSeen).isEqualTo(0)
    assertThat(tester.targets.single().candidate).isEqualTo(candidate)
    assertThat(tester.targets.single().expectedGuid).isEqualTo("guid-del-nas")

    vm.confirmCapture()
    assertThat(store.fingerprint.value?.ulaPrefixes).containsExactly("fd99:aaaa:bbbb::/48")
    assertThat(store.fingerprint.value?.capturedAt).isEqualTo(Samples.NOW)
    // L'admin la scrive anche nella configurazione firmata (RE-5): da li' nascono i nuovi inviti.
    assertThat(vm.state.value.capture).isEqualTo(CaptureUi.Saved(SaveScope.FAMILY))
    assertThat(registry.homeUlaUpdates).containsExactly(setOf("fd99:aaaa:bbbb::/48"))
  }

  /** RE-5: chi non e' admin cambia l'impronta solo per se', e la pagina lo dice. */
  @Test
  fun unFamiliareNonAdminCambiaLImprontaSoloPerSe() = runTest {
    capture.candidate = candidate
    tester.report = passed()
    registry.nextResult = AdminActionResult.NotAdmin
    val vm = viewModel()
    keepAlive(vm.state)
    vm.startCapture()

    vm.confirmCapture()

    assertThat(store.fingerprint.value?.ulaPrefixes).containsExactly("fd99:aaaa:bbbb::/48")
    assertThat(vm.state.value.capture).isEqualTo(CaptureUi.Saved(SaveScope.THIS_PHONE))
  }

  @Test
  fun seLaFirmaDellAdminNonRiesceL_improntaResta_eSiPuoRiprovare() = runTest {
    capture.candidate = candidate
    tester.report = passed()
    registry.nextResult = AdminActionResult.Failed(NasError.Timeout)
    val vm = viewModel()
    keepAlive(vm.state)
    vm.startCapture()
    vm.confirmCapture()

    assertThat(store.fingerprint.value?.ulaPrefixes).containsExactly("fd99:aaaa:bbbb::/48")
    assertThat(vm.state.value.capture).isEqualTo(CaptureUi.Saved(SaveScope.FAMILY_FAILED))

    registry.nextResult = AdminActionResult.Done
    vm.retryShareCapture()
    assertThat(vm.state.value.capture).isEqualTo(CaptureUi.Saved(SaveScope.FAMILY))
    assertThat(registry.homeUlaUpdates).hasSize(2)
  }

  @Test
  fun lAdminLoVedeNellaPagina_unFamiliareNo() = runTest {
    val admin = viewModel()
    keepAlive(admin.state)
    assertThat(admin.state.value.admin).isTrue()

    registry.status.value = TrustStatus.Approved(admin = false)
    val member = viewModel()
    keepAlive(member.state)
    assertThat(member.state.value.admin).isFalse()
  }

  // --- RE-1: il permesso di WireGuard ----------------------------------------------------------------------

  /**
   * Con il controllore vero su una piattaforma finta che parte come il telefono (WireGuard installato, permesso non ancora concesso): il
   * rifiuto si vede e si puo' riprovare, la concessione fa tornare "pronto" (prima nessuno rileggeva e restava "manca il permesso").
   */
  @Test
  fun ilPermessoDiWireGuard_concessoRilegge_rifiutatoResta_eSiPuoRiprovare() = runTest {
    val platform = FakeWireGuardPlatform(installed = true, permission = false)
    val controller = WireGuardVpnController(platform, vpnConfig, environment)
    val vm = viewModel(controller = controller)
    keepAlive(vm.state)
    assertThat(vm.state.value.vpnCapability).isEqualTo(VpnCapability.PERMISSION_MISSING)

    vm.onControlPermissionResult(ControlPermissionOutcome.DENIED)
    assertThat(vm.state.value.vpnCapability).isEqualTo(VpnCapability.PERMISSION_MISSING)
    assertThat(vm.state.value.permission).isEqualTo(PermissionUi.DENIED)

    vm.onControlPermissionResult(ControlPermissionOutcome.DENIED_FOR_GOOD)
    assertThat(vm.state.value.permission).isEqualTo(PermissionUi.DENIED_FOR_GOOD)

    // Concesso dal dialogo (o dalla scheda dell'app): si rilegge e il controllo e' pronto.
    platform.permission = true
    vm.onControlPermissionResult(ControlPermissionOutcome.GRANTED)
    assertThat(vm.state.value.vpnCapability).isEqualTo(VpnCapability.READY)
    assertThat(vm.state.value.permission).isEqualTo(PermissionUi.IDLE)
  }

  @Test
  fun tornandoDaWireGuardOdallaSchedaDellApp_ilPermessoSiRilegge() = runTest {
    val platform = FakeWireGuardPlatform(installed = true, permission = false)
    val controller = WireGuardVpnController(platform, vpnConfig, environment)
    val vm = viewModel(controller = controller)
    keepAlive(vm.state)

    platform.permission = true
    assertThat(vm.state.value.vpnCapability).isEqualTo(VpnCapability.PERMISSION_MISSING)
    vm.refreshVpn()

    assertThat(vm.state.value.vpnCapability).isEqualTo(VpnCapability.READY)
  }

  // --- RE-2: riattivare il controllo -----------------------------------------------------------------------

  @Test
  fun riattivareIlControllo_togliELaSospensioneERilegge() = runTest {
    vpnConfig.suspension.value = VpnSuspendReason.NOT_CONTROLLABLE
    val vm = viewModel()
    keepAlive(vm.state)

    vm.reactivateAutomation()

    assertThat(vpnConfig.suspension.value).isNull()
    assertThat(vpn.refreshes).isEqualTo(1)
  }

  @Test
  fun unaProvaARiuscita_riattivaIlControlloSospesoPerchePrimaWireGuardNonRispondeva() = runTest {
    automation.status.value = VpnAutomationStatus.Suspended(VpnSuspendReason.NOT_CONTROLLABLE)
    vpnConfig.suspension.value = VpnSuspendReason.NOT_CONTROLLABLE
    val vm = viewModel()
    keepAlive(vm.state)

    vm.tryTunnel(up = true)

    assertThat(vpnConfig.suspension.value).isNull()
    assertThat(vm.state.value.command).isEqualTo(VpnCommandUi.Finished(up = true, result = VpnCommandResult.Confirmed, reactivated = true))
  }

  @Test
  fun dopoIlSospettoDiBloccoSoloSpegnereSenzaPerdereLaReteRiattiva() = runTest {
    automation.status.value = VpnAutomationStatus.Suspended(VpnSuspendReason.LOCKDOWN_SUSPECTED)
    vpnConfig.suspension.value = VpnSuspendReason.LOCKDOWN_SUSPECTED
    val vm = viewModel()
    keepAlive(vm.state)

    // Accendere non prova niente sul blocco.
    vm.tryTunnel(up = true)
    assertThat(vpnConfig.suspension.value).isEqualTo(VpnSuspendReason.LOCKDOWN_SUSPECTED)

    // Spegnere e vedere la rete restare si'.
    vm.tryTunnel(up = false)
    assertThat(vpnConfig.suspension.value).isNull()
  }

  @Test
  fun unaProvaFallitaNonRiattivaNiente() = runTest {
    automation.status.value = VpnAutomationStatus.Suspended(VpnSuspendReason.NOT_CONTROLLABLE)
    vpnConfig.suspension.value = VpnSuspendReason.NOT_CONTROLLABLE
    vpn.nextResult = VpnCommandResult.NotObserved
    val vm = viewModel()
    keepAlive(vm.state)

    vm.tryTunnel(up = true)

    assertThat(vpnConfig.suspension.value).isEqualTo(VpnSuspendReason.NOT_CONTROLLABLE)
    assertThat(vm.state.value.command).isEqualTo(VpnCommandUi.Finished(up = true, result = VpnCommandResult.NotObserved))
  }

  @Test
  fun annullareLaRaccoltaNonSalvaNiente() = runTest {
    capture.candidate = candidate
    tester.report = passed()
    val vm = viewModel()
    keepAlive(vm.state)
    vm.startCapture()
    vm.cancelCapture()
    vm.confirmCapture()
    assertThat(store.fingerprint.value?.ulaPrefixes).containsExactly("fd12:3456:789a::/48")
    assertThat(vm.state.value.capture).isEqualTo(CaptureUi.Idle)
  }

  @Test
  fun seQuiRispondeUnNasDiversoNonSiSalvaNiente() = runTest {
    capture.candidate = candidate
    tester.report = ConnectionReport(
      listOf(StageResult(Stage.GUID, StageOutcome.FAILED, 1.milliseconds, NasError.IdentityMismatch)),
      negotiation = null,
    )
    val vm = viewModel()
    keepAlive(vm.state)
    vm.startCapture()

    assertThat(vm.state.value.capture).isEqualTo(CaptureUi.Rejected(identityMismatch = true, error = NasError.IdentityMismatch))
    vm.confirmCapture()
    assertThat(store.fingerprint.value?.ulaPrefixes).containsExactly("fd12:3456:789a::/48")
  }

  @Test
  fun senzaWifiOSenzaUlaNonSiPropone() = runTest {
    val vm = viewModel()
    keepAlive(vm.state)
    capture.candidate = null
    vm.startCapture()
    assertThat(vm.state.value.capture).isEqualTo(CaptureUi.NoWifi)

    capture.candidate = candidate.copy(ulaPrefixes = emptySet())
    vm.startCapture()
    assertThat(vm.state.value.capture).isEqualTo(CaptureUi.NoUla)
    assertThat(tester.targets).isEmpty()
  }

  @Test
  fun senzaUnAccountSuCuiVerificareNonSiProponeUnaImpronta() = runTest {
    capture.candidate = candidate
    val vm = viewModel(accounts = null)
    keepAlive(vm.state)
    vm.startCapture()
    assertThat(vm.state.value.capture).isEqualTo(CaptureUi.CannotVerify)
    vm.confirmCapture()
    assertThat(store.fingerprint.value?.ulaPrefixes).containsExactly("fd12:3456:789a::/48")
  }

  @Test
  fun laProvaAMano_dellaVpn_mostraLEsitoOsservato() = runTest {
    val vm = viewModel()
    keepAlive(vm.state)

    vm.tryTunnel(up = false)
    assertThat(vpn.commands).containsExactly(false)
    assertThat(vm.state.value.command).isEqualTo(VpnCommandUi.Finished(up = false, result = VpnCommandResult.Confirmed))

    vpn.nextResult = VpnCommandResult.LostConnectivity
    vm.tryTunnel(up = false)
    assertThat(vm.state.value.command).isEqualTo(VpnCommandUi.Finished(up = false, result = VpnCommandResult.LostConnectivity))
  }
}
