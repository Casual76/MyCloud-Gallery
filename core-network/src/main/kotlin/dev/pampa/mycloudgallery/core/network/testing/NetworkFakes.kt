package dev.pampa.mycloudgallery.core.network.testing

import android.content.Intent
import dev.pampa.mycloudgallery.core.network.contract.DenyReason
import dev.pampa.mycloudgallery.core.network.contract.DiscoveredNas
import dev.pampa.mycloudgallery.core.network.contract.HomeCandidate
import dev.pampa.mycloudgallery.core.network.contract.HomeCapture
import dev.pampa.mycloudgallery.core.network.contract.HomeFingerprint
import dev.pampa.mycloudgallery.core.network.contract.HomeFingerprintStore
import dev.pampa.mycloudgallery.core.network.contract.HomeMatch
import dev.pampa.mycloudgallery.core.network.contract.LocalNetworkAccess
import dev.pampa.mycloudgallery.core.network.contract.LocalNetworkPermission
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.NasDiscovery
import dev.pampa.mycloudgallery.core.network.contract.NasRoute
import dev.pampa.mycloudgallery.core.network.contract.NasRouteGate
import dev.pampa.mycloudgallery.core.network.contract.NetPlace
import dev.pampa.mycloudgallery.core.network.contract.NetTransport
import dev.pampa.mycloudgallery.core.network.contract.NetworkEnvironment
import dev.pampa.mycloudgallery.core.network.contract.NetworkSnapshot
import dev.pampa.mycloudgallery.core.network.contract.ProbeResult
import dev.pampa.mycloudgallery.core.network.contract.ReachabilityProbe
import dev.pampa.mycloudgallery.core.network.contract.SocketBinding
import dev.pampa.mycloudgallery.core.network.contract.UnderlyingNetwork
import dev.pampa.mycloudgallery.core.network.contract.Via
import dev.pampa.mycloudgallery.core.network.contract.VpnAutomation
import dev.pampa.mycloudgallery.core.network.contract.VpnAutomationStatus
import dev.pampa.mycloudgallery.core.network.contract.VpnCapability
import dev.pampa.mycloudgallery.core.network.contract.VpnCommandResult
import dev.pampa.mycloudgallery.core.network.contract.VpnController
import dev.pampa.mycloudgallery.core.network.contract.VpnObservation
import dev.pampa.mycloudgallery.core.network.contract.WakeOnLan
import dev.pampa.mycloudgallery.core.network.contract.WolOutcome
import dev.pampa.mycloudgallery.core.network.contract.WolTarget
import dev.pampa.mycloudgallery.core.rules.NetworkState
import dev.pampa.mycloudgallery.core.rules.NetworkTransport
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlin.time.Duration

// I fake di core-network (D6): in src/main, cosi' i moduli a valle (core-nas, core-family, feature-*) li
// riusano nei loro test senza cicli. Mai nella release: tools/check.ps1 fallisce se una classe di un package
// `testing` compare nel dex dell'APK di release.

/** Istantanee pronte per i test e per le anteprime. */
object Snapshots {
  private fun wifi(id: String = "wifi-1", metered: Boolean = false) =
    UnderlyingNetwork(id, NetTransport.WIFI, metered = metered, roaming = false, validated = true, captivePortal = false)

  private fun cellular(roaming: Boolean = false) =
    UnderlyingNetwork("cell-1", NetTransport.CELLULAR, metered = true, roaming = roaming, validated = true, captivePortal = false)

  fun home(): NetworkSnapshot = NetworkSnapshot(
    NetPlace.HOME, wifi(), HomeMatch.Match, VpnObservation.NONE, LocalNetworkAccess.GRANTED_IMPLICIT, ipv6Global = true, at = 1L,
  )

  fun awayVpnWifi(): NetworkSnapshot = NetworkSnapshot(
    NetPlace.AWAY_VPN_WIFI, wifi("wifi-2"), HomeMatch.NoMatch, VpnObservation.HOME_TUNNEL, LocalNetworkAccess.GRANTED_IMPLICIT, ipv6Global = true, at = 1L,
  )

  fun awayVpnMobile(): NetworkSnapshot = NetworkSnapshot(
    NetPlace.AWAY_VPN_MOBILE, cellular(), HomeMatch.Unknown, VpnObservation.HOME_TUNNEL, LocalNetworkAccess.GRANTED_IMPLICIT, ipv6Global = true, at = 1L,
  )

  fun roaming(): NetworkSnapshot = NetworkSnapshot(
    NetPlace.ROAMING, cellular(roaming = true), HomeMatch.Unknown, VpnObservation.HOME_TUNNEL, LocalNetworkAccess.GRANTED_IMPLICIT, ipv6Global = true, at = 1L,
  )

  fun vpnOff(): NetworkSnapshot = NetworkSnapshot(
    NetPlace.VPN_OFF, cellular(), HomeMatch.Unknown, VpnObservation.NONE, LocalNetworkAccess.GRANTED_IMPLICIT, ipv6Global = true, at = 1L,
  )

  fun untrusted(): NetworkSnapshot = NetworkSnapshot(
    NetPlace.UNTRUSTED, wifi("wifi-3"), HomeMatch.Weak(emptySet()), VpnObservation.NONE, LocalNetworkAccess.GRANTED_IMPLICIT, ipv6Global = false, at = 1L,
  )

  fun foreignVpn(): NetworkSnapshot = NetworkSnapshot(
    NetPlace.UNTRUSTED, wifi("wifi-4"), HomeMatch.NoMatch, VpnObservation.FOREIGN_VPN, LocalNetworkAccess.GRANTED_IMPLICIT, ipv6Global = true, at = 1L,
  )

  fun offline(): NetworkSnapshot = NetworkSnapshot.OFFLINE
}

/**
 * Ambiente di rete pilotato dal test: si cambia [current]. [toNetworkState] traduce con la regola piu'
 * semplice (casa = [NetPlace.HOME], VPN = osservata): la traduzione vera e' di P2.
 */
class FakeNetworkEnvironment(initial: NetworkSnapshot = NetworkSnapshot.OFFLINE) : NetworkEnvironment {

  private val snapshots = MutableStateFlow(initial)
  private val home = MutableStateFlow(initial.place == NetPlace.HOME)

  override val snapshot: StateFlow<NetworkSnapshot> = snapshots
  override val isAtHome: StateFlow<Boolean> = home

  /** L'istantanea corrente; assegnarla aggiorna anche [isAtHome]. */
  var current: NetworkSnapshot
    get() = snapshots.value
    set(value) {
      snapshots.value = value
      home.value = value.place == NetPlace.HOME
    }

  override fun toNetworkState(): NetworkState = translate(snapshots.value)

  companion object {
    fun translate(s: NetworkSnapshot): NetworkState {
      val net = s.underlying ?: return NetworkState.OFFLINE
      if (s.place == NetPlace.OFFLINE) return NetworkState.OFFLINE
      val transport = when (net.transport) {
        NetTransport.WIFI -> NetworkTransport.WIFI
        NetTransport.CELLULAR -> NetworkTransport.CELLULAR
        NetTransport.ETHERNET -> NetworkTransport.ETHERNET
        NetTransport.OTHER -> NetworkTransport.NONE
      }
      return NetworkState(
        transport = transport,
        viaVpn = s.vpn == VpnObservation.HOME_TUNNEL || s.vpn == VpnObservation.FOREIGN_VPN || s.vpn == VpnObservation.UNKNOWN,
        isHome = s.place == NetPlace.HOME,
        metered = net.metered,
        roaming = net.roaming,
        validated = net.validated && !net.captivePortal,
      )
    }
  }
}

/**
 * Un binding che NON e' legato a nessuna rete: `Socket()` e `connect`. Serve solo ai test d'integrazione dal
 * PC (§5.2, P1) e ai simulatori: in produzione violerebbe D1, per questo vive qui.
 */
class DirectSocketBinding(override val networkId: String = "direct") : SocketBinding {
  override fun connect(addr: InetAddress, port: Int, timeout: Duration): Socket {
    val socket = Socket()
    try {
      socket.connect(InetSocketAddress(addr, port), timeout.inWholeMilliseconds.toInt())
    } catch (e: Exception) {
      socket.close()
      throw e
    }
    return socket
  }
}

/** Un binding che non si connette mai (per verificare che nessuno ci provi): ogni tentativo e' contato. */
class RecordingSocketBinding(override val networkId: String = "fake-net") : SocketBinding {
  var attempts: Int = 0
    private set

  override fun connect(addr: InetAddress, port: Int, timeout: Duration): Socket {
    attempts++
    throw java.net.ConnectException("RecordingSocketBinding non si connette")
  }
}

/** Cancello pilotato dal test: si cambia [routes]. Di default chiuso. */
class FakeNasRouteGate(initial: NasRoute = NasRoute.Denied(DenyReason.NOT_CONFIGURED)) : NasRouteGate {
  override val routes: MutableStateFlow<NasRoute> = MutableStateFlow(initial)

  /** Il percorso dato a [onboardingRoute]. */
  var onboarding: NasRoute = NasRoute.Denied(DenyReason.NOT_CONFIGURED)

  override fun current(): NasRoute = routes.value

  override fun onboardingRoute(candidate: HomeCandidate): NasRoute = onboarding

  /** Apre il cancello con un binding che non si connette (o con quello dato). */
  fun allow(via: Via = Via.HOME_LAN, binding: SocketBinding = RecordingSocketBinding()) {
    routes.value = NasRoute.Allowed(via, binding)
  }

  fun deny(reason: DenyReason) {
    routes.value = NasRoute.Denied(reason)
  }
}

class FakeHomeFingerprintStore(initial: HomeFingerprint? = null) : HomeFingerprintStore {
  override val fingerprint: MutableStateFlow<HomeFingerprint?> = MutableStateFlow(initial)

  override suspend fun save(fingerprint: HomeFingerprint) {
    this.fingerprint.value = fingerprint
  }

  override suspend fun clear() {
    fingerprint.value = null
  }
}

class FakeHomeCapture(var candidate: HomeCandidate? = null) : HomeCapture {
  override suspend fun candidateFromCurrentWifi(): HomeCandidate? = candidate
}

/** WireGuard finto: registra i comandi e risponde con [nextResult]. */
class FakeVpnController(initial: VpnCapability = VpnCapability.READY) : VpnController {
  override val capability: MutableStateFlow<VpnCapability> = MutableStateFlow(initial)

  val commands: MutableList<Boolean> = mutableListOf()
  var nextResult: VpnCommandResult = VpnCommandResult.Confirmed
  var intent: Intent? = null

  override suspend fun setTunnel(up: Boolean, timeout: Duration): VpnCommandResult {
    commands += up
    return nextResult
  }

  override fun openWireGuardIntent(): Intent? = intent
}

class FakeVpnAutomation(initial: VpnAutomationStatus = VpnAutomationStatus.Off) : VpnAutomation {
  override val status: MutableStateFlow<VpnAutomationStatus> = MutableStateFlow(initial)
  var attachedScopes: Int = 0
    private set

  override fun attach(scope: CoroutineScope) {
    attachedScopes++
  }
}

class FakeLocalNetworkPermission(var access: LocalNetworkAccess = LocalNetworkAccess.GRANTED_IMPLICIT) : LocalNetworkPermission {
  override fun status(): LocalNetworkAccess = access
}

class FakeNasDiscovery(
  var found: List<DiscoveredNas> = emptyList(),
  var resolved: Map<String, InetAddress> = emptyMap(),
) : NasDiscovery {
  override fun discover(timeout: Duration): Flow<DiscoveredNas> = found.asFlow()

  override suspend fun resolveOnWifi(name: String): InetAddress? = resolved[name]
}

/** Sonda finta: risponde [result] e conta le sonde (E-R15: quante volte si e' "toccato" il NAS). */
class FakeReachabilityProbe(var result: ProbeResult = ProbeResult.NoRoute) : ReachabilityProbe {
  var probes: Int = 0
    private set

  override suspend fun tcp445(route: NasRoute.Allowed, nas: NasAddress, timeout: Duration): ProbeResult {
    probes++
    return result
  }
}

class FakeWakeOnLan(override var enabled: Boolean = false, var outcome: WolOutcome = WolOutcome.NotAllowed) : WakeOnLan {
  val targets: MutableList<WolTarget> = mutableListOf()

  override suspend fun wake(target: WolTarget): WolOutcome {
    targets += target
    return outcome
  }
}
