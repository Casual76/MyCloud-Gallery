package dev.pampa.mycloudgallery.feature.settings.network

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.nas.contract.ConnectionTester
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.Stage
import dev.pampa.mycloudgallery.core.nas.contract.TestTarget
import dev.pampa.mycloudgallery.core.network.contract.DenyReason
import dev.pampa.mycloudgallery.core.network.contract.HomeCandidate
import dev.pampa.mycloudgallery.core.network.contract.HomeCapture
import dev.pampa.mycloudgallery.core.network.contract.HomeFingerprint
import dev.pampa.mycloudgallery.core.network.contract.HomeFingerprintStore
import dev.pampa.mycloudgallery.core.network.contract.NasRoute
import dev.pampa.mycloudgallery.core.network.contract.NasRouteGate
import dev.pampa.mycloudgallery.core.network.contract.NetworkEnvironment
import dev.pampa.mycloudgallery.core.network.contract.NetworkSnapshot
import dev.pampa.mycloudgallery.core.network.contract.Via
import dev.pampa.mycloudgallery.core.network.contract.VpnAutomation
import dev.pampa.mycloudgallery.core.network.contract.VpnAutomationStatus
import dev.pampa.mycloudgallery.core.network.contract.VpnCapability
import dev.pampa.mycloudgallery.core.network.contract.VpnCommandResult
import dev.pampa.mycloudgallery.core.network.contract.VpnController
import dev.pampa.mycloudgallery.feature.settings.port.NasAccountSettings
import dev.pampa.mycloudgallery.feature.settings.port.SettingsClock
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Il percorso verso il NAS in due parole: consentito (e da dove) oppure negato (e perche'). */
internal sealed interface RouteUi {
  data class Allowed(val via: Via) : RouteUi

  data class Denied(val reason: DenyReason) : RouteUi
}

/** A che punto e' la nuova raccolta dell'impronta di casa (E-R16). */
internal sealed interface CaptureUi {
  data object Idle : CaptureUi

  data object Capturing : CaptureUi

  /** Nessun Wi-Fi da cui leggere. */
  data object NoWifi : CaptureUi

  /** Il Wi-Fi attuale non ha un ULA: "casa" non si puo' confermare. */
  data object NoUla : CaptureUi

  /** Si verifica che sia lo stesso NAS (GUID) prima di proporre. */
  data object Verifying : CaptureUi

  /** Pronta da confermare: [sameAsSaved] se il prefisso coincide gia' con quello salvato. */
  data class Ready(val ulaCount: Int, val sameAsSaved: Boolean) : CaptureUi

  /** Sul Wi-Fi attuale risponde un NAS diverso (o niente): non si salva niente. */
  data class Rejected(val identityMismatch: Boolean, val error: NasError?) : CaptureUi

  /** Non si puo' verificare il NAS (account non collegato). */
  data object CannotVerify : CaptureUi

  data object Saved : CaptureUi
}

/** L'ultimo comando provato a mano a WireGuard. */
internal sealed interface VpnCommandUi {
  data object Idle : VpnCommandUi

  data class Running(val up: Boolean) : VpnCommandUi

  data class Finished(val up: Boolean, val result: VpnCommandResult) : VpnCommandUi
}

internal data class NetworkUiState(
  val snapshot: NetworkSnapshot,
  val route: RouteUi,
  val fingerprint: HomeFingerprint?,
  val vpnCapability: VpnCapability,
  val automation: VpnAutomationStatus,
  val capture: CaptureUi = CaptureUi.Idle,
  val command: VpnCommandUi = VpnCommandUi.Idle,
  /** WireGuard e' installato (c'e' un intent per aprirlo). */
  val canOpenWireGuard: Boolean = false,
  /** Ora del dispositivo, per scrivere "raccolta il ...". */
  val nowMillis: Long = 0L,
)

/**
 * "Rete e VPN" (piano 2.1 e 2.5): dove si trova il telefono rispetto al NAS, l'impronta della rete di casa e il
 * controllo di WireGuard.
 *
 *  - **Impronta di casa**: solo l'ULA e' un indizio forte (gateway e nome di rete sono uguali in mezza Italia).
 *    Una nuova raccolta (E-R16, dopo un reset del router) si propone solo se sul Wi-Fi attuale risponde **lo
 *    stesso NAS** (GUID uguale, senza autenticare) e solo l'utente la conferma.
 *  - **WireGuard**: stato del controllo e prova a mano (accendi/spegni) con l'esito *osservato*: "acceso" non
 *    si dice perche' l'intent e' partito.
 */
@HiltViewModel
internal class NetworkViewModel @Inject constructor(
  environment: NetworkEnvironment,
  gate: NasRouteGate,
  private val fingerprintStore: HomeFingerprintStore,
  private val homeCapture: HomeCapture,
  private val vpn: VpnController,
  automation: VpnAutomation,
  private val tester: ConnectionTester,
  accountSettings: Optional<NasAccountSettings>,
  private val clock: SettingsClock,
) : ViewModel() {

  private val accounts: NasAccountSettings? = accountSettings.orElse(null)
  private val local = MutableStateFlow(Local())
  private var candidate: HomeCandidate? = null

  private val facts: StateFlow<NetworkUiState> = combine(
    environment.snapshot,
    gate.routes,
    fingerprintStore.fingerprint,
    vpn.capability,
    automation.status,
  ) { snapshot, route, fingerprint, capability, status ->
    NetworkUiState(
      snapshot = snapshot,
      route = when (route) {
        is NasRoute.Allowed -> RouteUi.Allowed(route.via)
        is NasRoute.Denied -> RouteUi.Denied(route.reason)
      },
      fingerprint = fingerprint,
      vpnCapability = capability,
      automation = status,
    )
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
    initialValue = NetworkUiState(
      snapshot = environment.snapshot.value,
      route = RouteUi.Denied(DenyReason.NOT_CONFIGURED),
      fingerprint = fingerprintStore.fingerprint.value,
      vpnCapability = vpn.capability.value,
      automation = automation.status.value,
    ),
  )

  val state: StateFlow<NetworkUiState> = combine(facts, local) { base, local ->
    base.copy(
      capture = local.capture,
      command = local.command,
      canOpenWireGuard = vpn.openWireGuardIntent() != null,
      nowMillis = clock.nowMillis(),
    )
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
    initialValue = facts.value,
  )

  /** L'intent che apre WireGuard, o null se non e' installato. */
  fun wireGuardIntent(): Intent? = vpn.openWireGuardIntent()

  // --- WireGuard ---------------------------------------------------------------------------------------

  /** Prova a mano ad accendere o spegnere il tunnel di casa. L'esito e' quello *osservato* (§2.4). */
  fun tryTunnel(up: Boolean) {
    if (local.value.command is VpnCommandUi.Running) return
    local.update { it.copy(command = VpnCommandUi.Running(up)) }
    viewModelScope.launch {
      val result = try {
        vpn.setTunnel(up)
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        VpnCommandResult.NotObserved
      }
      local.update { it.copy(command = VpnCommandUi.Finished(up, result)) }
    }
  }

  // --- impronta di casa --------------------------------------------------------------------------------

  /**
   * Legge il Wi-Fi attuale e, se si puo', verifica che il NAS risponda con lo stesso GUID prima di proporre
   * l'impronta. Senza un account su cui verificare non si propone: sarebbe un'impronta non confermata.
   */
  fun startCapture() {
    val phase = local.value.capture
    if (phase is CaptureUi.Capturing || phase is CaptureUi.Verifying) return
    local.update { it.copy(capture = CaptureUi.Capturing) }
    viewModelScope.launch {
      try {
        val found = homeCapture.candidateFromCurrentWifi()
        if (found == null) {
          setCapture(CaptureUi.NoWifi)
          return@launch
        }
        if (!found.canBeConfirmed) {
          setCapture(CaptureUi.NoUla)
          return@launch
        }
        val account = accounts?.account?.value
        if (account == null) {
          setCapture(CaptureUi.CannotVerify)
          return@launch
        }
        setCapture(CaptureUi.Verifying)
        val report = tester.run(
          target = TestTarget(address = account.address, user = null, share = null, expectedGuid = account.pinnedGuid, candidate = found),
          credentials = null,
          stages = setOf(Stage.ROUTE, Stage.TCP, Stage.NEGOTIATE, Stage.GUID),
        )
        if (report.passed) {
          candidate = found
          val saved = fingerprintStore.fingerprint.value
          setCapture(CaptureUi.Ready(found.ulaPrefixes.size, sameAsSaved = saved != null && saved.ulaPrefixes.containsAll(found.ulaPrefixes)))
        } else {
          val failure = report.firstFailure
          setCapture(CaptureUi.Rejected(identityMismatch = failure?.error == NasError.IdentityMismatch || failure?.stage == Stage.GUID, error = failure?.error))
        }
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        setCapture(CaptureUi.Rejected(identityMismatch = false, error = null))
      }
    }
  }

  /** L'utente conferma "si', questa e' la rete di casa": solo ora l'impronta si salva. */
  fun confirmCapture() {
    val found = candidate ?: return
    if (local.value.capture !is CaptureUi.Ready) return
    viewModelScope.launch {
      fingerprintStore.save(
        HomeFingerprint(
          ulaPrefixes = found.ulaPrefixes,
          gatewayV4 = found.gatewayV4,
          dnsDomain = found.dnsDomain,
          capturedAt = clock.nowMillis(),
        ),
      )
      candidate = null
      setCapture(CaptureUi.Saved)
    }
  }

  fun cancelCapture() {
    candidate = null
    setCapture(CaptureUi.Idle)
  }

  private fun setCapture(capture: CaptureUi) {
    local.update { it.copy(capture = capture) }
  }

  private data class Local(
    val capture: CaptureUi = CaptureUi.Idle,
    val command: VpnCommandUi = VpnCommandUi.Idle,
  )

  private companion object {
    const val STOP_TIMEOUT_MS = 5_000L
  }
}
