package dev.pampa.mycloudgallery.core.network.noop

import android.content.Intent
import dev.pampa.mycloudgallery.core.network.contract.DenyReason
import dev.pampa.mycloudgallery.core.network.contract.DiscoveredNas
import dev.pampa.mycloudgallery.core.network.contract.HomeCandidate
import dev.pampa.mycloudgallery.core.network.contract.HomeCapture
import dev.pampa.mycloudgallery.core.network.contract.HomeFingerprint
import dev.pampa.mycloudgallery.core.network.contract.HomeFingerprintStore
import dev.pampa.mycloudgallery.core.network.contract.LocalNetworkAccess
import dev.pampa.mycloudgallery.core.network.contract.LocalNetworkPermission
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.NasDiscovery
import dev.pampa.mycloudgallery.core.network.contract.NasRoute
import dev.pampa.mycloudgallery.core.network.contract.NasRouteGate
import dev.pampa.mycloudgallery.core.network.contract.NetworkEnvironment
import dev.pampa.mycloudgallery.core.network.contract.NetworkSnapshot
import dev.pampa.mycloudgallery.core.network.contract.ProbeResult
import dev.pampa.mycloudgallery.core.network.contract.ReachabilityProbe
import dev.pampa.mycloudgallery.core.network.contract.VpnAutomation
import dev.pampa.mycloudgallery.core.network.contract.VpnAutomationStatus
import dev.pampa.mycloudgallery.core.network.contract.VpnCapability
import dev.pampa.mycloudgallery.core.network.contract.VpnCommandResult
import dev.pampa.mycloudgallery.core.network.contract.VpnController
import dev.pampa.mycloudgallery.core.network.contract.VpnSkipReason
import dev.pampa.mycloudgallery.core.network.contract.WakeOnLan
import dev.pampa.mycloudgallery.core.network.contract.WolOutcome
import dev.pampa.mycloudgallery.core.network.contract.WolTarget
import dev.pampa.mycloudgallery.core.rules.NetworkState
import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.time.Duration

// I null-object di core-network: cio' che :app lega finche' P9 non porta le implementazioni vere.
//
// Non stanno nel package `testing` di proposito (deviazione da D6, per i soli null-object): vanno nella
// release, mentre i fake no (tools/check.ps1 lo verifica). Ognuno "fallisce chiuso": nessuna rete, nessun
// percorso consentito, nessun comando mandato. Con questi l'app non contatta mai il NAS.

/** Nessuna rete osservata: offline, mai a casa. */
object OfflineNetworkEnvironment : NetworkEnvironment {
  override val snapshot: StateFlow<NetworkSnapshot> = MutableStateFlow(NetworkSnapshot.OFFLINE)
  override val isAtHome: StateFlow<Boolean> = MutableStateFlow(false)

  override fun toNetworkState(): NetworkState = NetworkState.OFFLINE
}

/** Il cancello chiuso: nessun percorso verso il NAS. */
object ClosedNasRouteGate : NasRouteGate {
  private val denied = NasRoute.Denied(DenyReason.NOT_CONFIGURED)
  override val routes: StateFlow<NasRoute> = MutableStateFlow(denied)

  override fun current(): NasRoute = denied

  override fun onboardingRoute(candidate: HomeCandidate): NasRoute = denied
}

/** Nessuna impronta di casa, e non se ne salva nessuna. */
object NoHomeFingerprintStore : HomeFingerprintStore {
  override val fingerprint: StateFlow<HomeFingerprint?> = MutableStateFlow(null)

  override suspend fun save(fingerprint: HomeFingerprint) = Unit

  override suspend fun clear() = Unit
}

/** Nessun Wi-Fi da cui raccogliere un candidato. */
object NoHomeCapture : HomeCapture {
  override suspend fun candidateFromCurrentWifi(): HomeCandidate? = null
}

/** WireGuard non controllabile: nessun comando parte. */
object NoVpnController : VpnController {
  override val capability: StateFlow<VpnCapability> = MutableStateFlow(VpnCapability.NOT_CONFIGURED)

  override suspend fun setTunnel(up: Boolean, timeout: Duration): VpnCommandResult =
    VpnCommandResult.Skipped(VpnSkipReason.NOT_CONFIGURED)

  override fun openWireGuardIntent(): Intent? = null

  override fun refresh() = Unit
}

/** Automazione spenta. */
object NoVpnAutomation : VpnAutomation {
  override val status: StateFlow<VpnAutomationStatus> = MutableStateFlow(VpnAutomationStatus.Off)

  override fun attach(scope: CoroutineScope) = Unit
}

/** Stato del permesso di rete locale non letto. */
object UnknownLocalNetworkPermission : LocalNetworkPermission {
  override fun status(): LocalNetworkAccess = LocalNetworkAccess.UNKNOWN
}

/** Nessuna scoperta in LAN. */
object NoNasDiscovery : NasDiscovery {
  override fun discover(timeout: Duration): Flow<DiscoveredNas> = emptyFlow()

  override suspend fun resolveOnWifi(name: String): InetAddress? = null
}

/** Nessuna sonda: il NAS risulta senza rotta. */
object NoReachabilityProbe : ReachabilityProbe {
  override suspend fun tcp445(route: NasRoute.Allowed, nas: NasAddress, timeout: Duration): ProbeResult =
    ProbeResult.NoRoute
}

/** Wake-on-LAN spento (D5). */
object DisabledWakeOnLan : WakeOnLan {
  override val enabled: Boolean = false

  override suspend fun wake(target: WolTarget): WolOutcome = WolOutcome.NotAllowed
}
