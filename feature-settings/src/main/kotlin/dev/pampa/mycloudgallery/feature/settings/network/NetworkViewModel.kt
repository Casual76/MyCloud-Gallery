package dev.pampa.mycloudgallery.feature.settings.network

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.family.contract.AdminActionResult
import dev.pampa.mycloudgallery.core.family.contract.RegistryService
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.nas.contract.ConnectionTester
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.Stage
import dev.pampa.mycloudgallery.core.nas.contract.TestTarget
import dev.pampa.mycloudgallery.core.network.contract.ControlPermissionOutcome
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
import dev.pampa.mycloudgallery.core.network.contract.VpnControlConfigStore
import dev.pampa.mycloudgallery.core.network.contract.VpnController
import dev.pampa.mycloudgallery.core.network.contract.VpnSuspendReason
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

  /** Salvata su questo telefono; [scope] dice fin dove vale (verifica funzionale, RE-5). */
  data class Saved(val scope: SaveScope) : CaptureUi
}

/** Fin dove vale una modifica all'indirizzo del NAS o all'impronta di casa (RE-5). */
internal enum class SaveScope {
  /** Anche per la famiglia: l'admin l'ha scritta nella configurazione firmata, da cui nascono i nuovi inviti. */
  FAMILY,

  /** Solo per questo telefono: chi non e' admin non firma la configurazione, gli altri e gli inviti li aggiorna l'admin. */
  THIS_PHONE,

  /** L'admin l'ha salvata qui, ma la firma non e' riuscita (NAS non raggiungibile, versione piu' nuova...): "Riprova". */
  FAMILY_FAILED,
}

/** Com'e' andata l'ultima richiesta del permesso di WireGuard (RE-1). */
internal enum class PermissionUi {
  /** Mai chiesto (o concesso). */
  IDLE,

  /** Rifiutato: Android lo richiede ancora. */
  DENIED,

  /** Rifiutato e Android non lo chiede piu': resta la scheda dell'app. */
  DENIED_FOR_GOOD,
}

/** L'ultimo comando provato a mano a WireGuard. */
internal sealed interface VpnCommandUi {
  data object Idle : VpnCommandUi

  data class Running(val up: Boolean) : VpnCommandUi

  /** [reactivated]: la prova riuscita ha tolto la sospensione del controllo automatico (RE-2). */
  data class Finished(val up: Boolean, val result: VpnCommandResult, val reactivated: Boolean = false) : VpnCommandUi
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
  /** Questo dispositivo e' l'admin: le sue modifiche all'impronta di casa valgono anche per i nuovi inviti (RE-5). */
  val admin: Boolean = false,
  /** L'ultima richiesta del permesso di WireGuard (RE-1). */
  val permission: PermissionUi = PermissionUi.IDLE,
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
  private val automation: VpnAutomation,
  private val vpnConfig: VpnControlConfigStore,
  private val tester: ConnectionTester,
  accountSettings: Optional<NasAccountSettings>,
  private val registry: RegistryService,
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

  val state: StateFlow<NetworkUiState> = combine(facts, local, registry.status) { base, local, trust ->
    base.copy(
      capture = local.capture,
      command = local.command,
      canOpenWireGuard = vpn.openWireGuardIntent() != null,
      nowMillis = clock.nowMillis(),
      admin = trust.isAdmin(),
      permission = local.permission,
    )
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
    initialValue = facts.value,
  )

  /** L'intent che apre WireGuard, o null se non e' installato. */
  fun wireGuardIntent(): Intent? = vpn.openWireGuardIntent()

  // --- WireGuard ---------------------------------------------------------------------------------------

  /**
   * Il dialogo di sistema del permesso di WireGuard e' finito (RE-1). Qualunque sia l'esito si rilegge: la capacita' non cambia da
   * sola, e senza questa riga restava "manca il permesso" anche a permesso concesso. Un rifiuto resta visibile con il modo di riprovare
   * (il dialogo, oppure la scheda dell'app se Android non lo mostra piu').
   */
  fun onControlPermissionResult(outcome: ControlPermissionOutcome) {
    vpn.refresh()
    local.update {
      it.copy(
        permission = when (outcome) {
          ControlPermissionOutcome.GRANTED -> PermissionUi.IDLE
          ControlPermissionOutcome.DENIED -> PermissionUi.DENIED
          ControlPermissionOutcome.DENIED_FOR_GOOD -> PermissionUi.DENIED_FOR_GOOD
        },
      )
    }
  }

  /** Si rilegge cio' che Android puo' aver cambiato fuori dall'app: l'installazione di WireGuard, il permesso dato dalla scheda dell'app. */
  fun refreshVpn() = vpn.refresh()

  /**
   * "Riattiva il controllo" (RE-2): l'automazione si era fermata da sola (VPN che non risponde, o rete sparita spegnendo il tunnel) e
   * solo l'utente puo' ripartire. Toglie la sospensione e rilegge; l'automazione riparte da zero.
   */
  fun reactivateAutomation() {
    viewModelScope.launch {
      vpnConfig.clearSuspension()
      vpn.refresh()
    }
  }

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
      val reactivated = result == VpnCommandResult.Confirmed && liftsSuspension(up) && clearSuspensionQuietly()
      local.update { it.copy(command = VpnCommandUi.Finished(up, result, reactivated)) }
    }
  }

  /**
   * Una prova a mano riuscita dimostra che WireGuard risponde (RE-2): se l'automazione era sospesa per questo, si riattiva. Spegnere il
   * tunnel senza perdere la rete prova anche che il "blocco" sospettato non c'e'; accenderlo no.
   */
  private fun liftsSuspension(up: Boolean): Boolean =
    when ((automation.status.value as? VpnAutomationStatus.Suspended)?.reason) {
      VpnSuspendReason.NOT_CONTROLLABLE -> true
      VpnSuspendReason.LOCKDOWN_SUSPECTED -> !up
      null -> false
    }

  private suspend fun clearSuspensionQuietly(): Boolean = try {
    vpnConfig.clearSuspension()
    true
  } catch (e: CancellationException) {
    throw e
  } catch (_: Exception) {
    false
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

  /**
   * L'utente conferma "si', questa e' la rete di casa": solo ora l'impronta si salva, su questo telefono. Se e' l'admin la scrive anche nella
   * configurazione **firmata** della famiglia (E-R16, RE-5): da li' i nuovi inviti prendono "dov'e' casa". Chi non e' admin la cambia solo
   * per se', e la pagina lo dice. La firma e' quella di ogni azione dell'admin (`RegistryService`): non c'e' un controllo qui.
   */
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
      sharedPrefixes = found.ulaPrefixes
      setCapture(CaptureUi.Saved(shareWithFamily(found.ulaPrefixes)))
    }
  }

  /** "Riprova" dopo una firma non riuscita: l'impronta e' gia' salvata qui, si ripete solo la parte della famiglia. */
  fun retryShareCapture() {
    val prefixes = sharedPrefixes ?: return
    if (local.value.capture != CaptureUi.Saved(SaveScope.FAMILY_FAILED)) return
    viewModelScope.launch { setCapture(CaptureUi.Saved(shareWithFamily(prefixes))) }
  }

  private suspend fun shareWithFamily(prefixes: Set<String>): SaveScope =
    when (val result = signHomeUla(prefixes)) {
      AdminActionResult.Done -> SaveScope.FAMILY
      AdminActionResult.NotAdmin -> SaveScope.THIS_PHONE
      else -> {
        GalleryLog.d(TAG) { "impronta di casa non firmata: ${result.javaClass.simpleName}" }
        SaveScope.FAMILY_FAILED
      }
    }

  private suspend fun signHomeUla(prefixes: Set<String>): AdminActionResult = try {
    registry.updateHomeUla(prefixes)
  } catch (e: CancellationException) {
    throw e
  } catch (_: Exception) {
    AdminActionResult.Failed(NasError.Unexpected(null))
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
    val permission: PermissionUi = PermissionUi.IDLE,
  )

  private fun TrustStatus.isAdmin(): Boolean = this is TrustStatus.Approved && admin

  /** I prefissi dell'ultima impronta salvata, per ripetere la firma. */
  private var sharedPrefixes: Set<String>? = null

  private companion object {
    const val TAG = "NetworkViewModel"
    const val STOP_TIMEOUT_MS = 5_000L
  }
}
