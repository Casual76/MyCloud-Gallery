package dev.pampa.mycloudgallery.core.network.env

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import dev.pampa.mycloudgallery.core.network.contract.NetTransport
import dev.pampa.mycloudgallery.core.network.link.IpAddr
import dev.pampa.mycloudgallery.core.network.link.IpPrefix
import dev.pampa.mycloudgallery.core.network.link.LinkAddr
import dev.pampa.mycloudgallery.core.network.link.LinkFacts
import dev.pampa.mycloudgallery.core.network.link.LinkRoute
import dev.pampa.mycloudgallery.core.network.link.NetworkObservation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Da un identificativo opaco alla `Network` di Android (serve a legare i socket e il datagramma del Wake-on-LAN). */
interface NetworkLookup {
  fun network(id: String): Network?
}

/**
 * Le reti, osservate con quattro `NetworkCallback` (docs/rete-casa-vpn.md):
 *  1. la **rete predefinita** del sistema (con la VPN accesa e' la VPN);
 *  2. il **Wi-Fi/Ethernet sottostante** (`NOT_VPN`), che resta visibile anche con la VPN accesa: e' da li' che si
 *     riconosce casa quando il tunnel e' su;
 *  3. il **cellulare sottostante** (`NOT_VPN`, con `INTERNET`: l'IMS non conta), perche' con una VPN sul cellulare
 *     la rete predefinita e' la VPN e non dice quanto costa o se e' in roaming la rete vera;
 *  4. le **VPN** (`TRANSPORT_VPN`): rotte, DNS e indirizzi del tunnel (`LinkProperties`), per capire se e' quello
 *     di casa.
 *
 * Nessuna di queste richiede il permesso di posizione (niente SSID): bastano `ACCESS_NETWORK_STATE` e `INTERNET`
 * del manifest. `registerNetworkCallback` non tiene accesa nessuna rete e non genera traffico.
 *
 * Una rete entra nell'osservazione solo quando se ne conoscono sia le capacita' sia le proprieta' del
 * collegamento: Android le consegna in due callback distinte, e una rete a meta' darebbe un falso "rete senza
 * indirizzi".
 */
class AndroidNetworkObserver(context: Context) : NetworkObservationSource, NetworkLookup {

  private val connectivity: ConnectivityManager? = context.applicationContext.getSystemService(ConnectivityManager::class.java)

  private enum class Kind { DEFAULT, LAN, CELLULAR, VPN }

  private class Entry(val network: Network) {
    var capabilities: NetworkCapabilities? = null
    var properties: LinkProperties? = null
    var facts: LinkFacts? = null
    val kinds: MutableSet<Kind> = mutableSetOf()
  }

  private val lock = Any()
  private val entries = LinkedHashMap<String, Entry>()
  private var defaultId: String? = null
  private var started = false
  private val registered = mutableListOf<ConnectivityManager.NetworkCallback>()

  private val state = MutableStateFlow(NetworkObservation.EMPTY)

  override val observation: StateFlow<NetworkObservation> = state

  override fun network(id: String): Network? = synchronized(lock) { entries[id]?.network }

  /** Solo per i test: i quattro callback registrati, nell'ordine predefinita, LAN, cellulare, VPN. */
  internal val registeredCallbacks: List<ConnectivityManager.NetworkCallback>
    get() = synchronized(lock) { registered.toList() }

  override fun start() {
    val cm = connectivity ?: return
    synchronized(lock) {
      if (started) return
      started = true
    }
    try {
      register(cm, Kind.DEFAULT, null)
      register(cm, Kind.LAN, lanRequest())
      register(cm, Kind.CELLULAR, cellularRequest())
      register(cm, Kind.VPN, vpnRequest())
    } catch (_: SecurityException) {
      // Manca ACCESS_NETWORK_STATE: nessuna rete osservata, quindi nessun percorso verso il NAS (fail-closed).
    }
  }

  /** Smette di osservare (test, o chiusura del processo). */
  fun stop() {
    val cm = connectivity ?: return
    synchronized(lock) {
      started = false
      registered.forEach { callback ->
        try {
          cm.unregisterNetworkCallback(callback)
        } catch (_: IllegalArgumentException) {
          // gia' tolto
        }
      }
      registered.clear()
      entries.clear()
      defaultId = null
    }
    state.value = NetworkObservation.EMPTY
  }

  private fun register(cm: ConnectivityManager, kind: Kind, request: NetworkRequest?) {
    val callback = Callback(kind)
    if (request == null) cm.registerDefaultNetworkCallback(callback) else cm.registerNetworkCallback(request, callback)
    synchronized(lock) { registered += callback }
  }

  private fun lanRequest(): NetworkRequest =
    NetworkRequest.Builder()
      .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
      .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
      .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
      .build()

  private fun cellularRequest(): NetworkRequest =
    NetworkRequest.Builder()
      .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
      .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
      .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
      .build()

  private fun vpnRequest(): NetworkRequest =
    NetworkRequest.Builder()
      .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
      .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
      .build()

  private inner class Callback(private val kind: Kind) : ConnectivityManager.NetworkCallback() {

    override fun onAvailable(network: Network) {
      update(network) { entry ->
        entry.kinds += kind
        if (kind == Kind.DEFAULT) defaultId = LinkFactsMapper.idOf(network)
      }
    }

    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
      update(network) { entry ->
        entry.kinds += kind
        entry.capabilities = networkCapabilities
        if (kind == Kind.DEFAULT) defaultId = LinkFactsMapper.idOf(network)
      }
    }

    override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
      update(network) { entry ->
        entry.kinds += kind
        entry.properties = linkProperties
        if (kind == Kind.DEFAULT) defaultId = LinkFactsMapper.idOf(network)
      }
    }

    override fun onLost(network: Network) {
      val id = LinkFactsMapper.idOf(network)
      synchronized(lock) {
        val entry = entries[id]
        if (entry != null) {
          entry.kinds -= kind
          if (entry.kinds.isEmpty()) entries.remove(id)
        }
        if (kind == Kind.DEFAULT && defaultId == id) defaultId = null
        publishLocked()
      }
    }
  }

  private fun update(network: Network, change: (Entry) -> Unit) {
    val id = LinkFactsMapper.idOf(network)
    synchronized(lock) {
      val entry = entries.getOrPut(id) { Entry(network) }
      change(entry)
      val caps = entry.capabilities
      val props = entry.properties
      if (caps != null && props != null) entry.facts = LinkFactsMapper.map(network, caps, props)
      publishLocked()
    }
  }

  private fun publishLocked() {
    val facts = entries.values.mapNotNull { it.facts }
    state.value = NetworkObservation(defaultId?.takeIf { id -> facts.any { it.networkId == id } }, facts)
  }
}

/**
 * Da `Network`, `NetworkCapabilities` e `LinkProperties` a [LinkFacts]. Legge solo API pubbliche e nessun dato che
 * richieda la posizione.
 */
internal object LinkFactsMapper {

  /** `Network.toString()` e' l'identificativo numerico della rete: cambia a ogni riconnessione. */
  fun idOf(network: Network): String = network.toString()

  fun map(network: Network, caps: NetworkCapabilities, lp: LinkProperties): LinkFacts {
    val transports = transportsOf(caps)
    val notMetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
      (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED))
    val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    return LinkFacts(
      networkId = idOf(network),
      transports = transports,
      isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
      metered = !notMetered,
      // Il roaming ha senso solo sul cellulare; il Wi-Fi non e' mai in roaming.
      roaming = NetTransport.CELLULAR in transports && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING),
      validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
      captivePortal = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
      congested = modern && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED),
      suspended = modern && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED),
      addresses = addressesOf(lp),
      routes = routesOf(lp),
      dnsServers = lp.dnsServers.mapNotNull { IpAddr.fromBytes(it.address) },
      domains = lp.domains?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
      dhcpServer = dhcpOf(lp),
    )
  }

  private fun transportsOf(caps: NetworkCapabilities): Set<NetTransport> {
    val out = LinkedHashSet<NetTransport>()
    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) out += NetTransport.WIFI
    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) out += NetTransport.ETHERNET
    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) out += NetTransport.CELLULAR
    if (out.isEmpty() && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) out += NetTransport.OTHER
    return out
  }

  private fun addressesOf(lp: LinkProperties): List<LinkAddr> =
    lp.linkAddresses.mapNotNull { address ->
      val ip = IpAddr.fromBytes(address.address.address) ?: return@mapNotNull null
      val max = if (ip.isV4) V4_BITS else V6_BITS
      if (address.prefixLength !in 0..max) null else LinkAddr(ip, address.prefixLength)
    }

  private fun routesOf(lp: LinkProperties): List<LinkRoute> =
    lp.routes.mapNotNull { route ->
      // Solo le rotte vere: `unreachable` e `throw` non portano da nessuna parte (il tipo si legge da Android 13).
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && route.type != android.net.RouteInfo.RTN_UNICAST) return@mapNotNull null
      val destination = route.destination
      val ip = IpAddr.fromBytes(destination.rawAddress) ?: return@mapNotNull null
      val max = if (ip.isV4) V4_BITS else V6_BITS
      if (destination.prefixLength !in 0..max) return@mapNotNull null
      val gateway = if (route.hasGateway()) route.gateway?.let { IpAddr.fromBytes(it.address) } else null
      LinkRoute(IpPrefix(ip, destination.prefixLength), gateway)
    }

  private fun dhcpOf(lp: LinkProperties): IpAddr? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) lp.dhcpServerAddress?.let { IpAddr.fromBytes(it.address) } else null

  private const val V4_BITS = 32
  private const val V6_BITS = 128
}
