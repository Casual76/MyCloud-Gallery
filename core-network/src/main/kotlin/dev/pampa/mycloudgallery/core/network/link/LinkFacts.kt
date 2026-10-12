package dev.pampa.mycloudgallery.core.network.link

import dev.pampa.mycloudgallery.core.network.contract.NetTransport

/** Un indirizzo dell'interfaccia con la lunghezza del suo prefisso (`LinkAddress` di Android). */
class LinkAddr(val ip: IpAddr, val prefixLength: Int) {

  /** La sottorete dell'indirizzo (per un IPv4 `192.168.x.y/24` e' `192.168.x.0/24`). */
  val prefix: IpPrefix get() = IpPrefix(ip, prefixLength)

  override fun equals(other: Any?): Boolean = other is LinkAddr && other.ip == ip && other.prefixLength == prefixLength

  override fun hashCode(): Int = ip.hashCode() * 31 + prefixLength

  override fun toString(): String = "LinkAddr($ip/$prefixLength)"
}

/** Una rotta di `LinkProperties`. Quella predefinita ha il prefisso a lunghezza zero. */
class LinkRoute(val destination: IpPrefix, val gateway: IpAddr?) {

  val isDefault: Boolean get() = destination.isDefault

  override fun equals(other: Any?): Boolean = other is LinkRoute && other.destination == destination && other.gateway == gateway

  override fun hashCode(): Int = destination.hashCode() * 31 + (gateway?.hashCode() ?: 0)

  override fun toString(): String = "LinkRoute($destination)"
}

/**
 * Tutto cio' che si sa di una rete, come lo descrivono `NetworkCapabilities` e `LinkProperties`, **senza permesso
 * di posizione** (quindi senza SSID). E' l'unico ingresso della logica di rete: la logica non conosce Android,
 * quindi i test la provano su reti sintetiche (`testing/LinkFactsSamples`).
 *
 * Contiene indirizzi veri: vive solo in memoria e il suo `toString` non li stampa (§4.2).
 *
 * @property networkId la `Network` di Android (opaco, cambia a ogni riconnessione).
 * @property transports i trasporti della rete **senza** la VPN. Una VPN che eredita i trasporti sottostanti
 *   ne ha (es. `{WIFI}`), una che non li dice no.
 * @property isVpn la rete ha `TRANSPORT_VPN`.
 * @property validated `NET_CAPABILITY_VALIDATED`: falso senza internet e dietro una pagina di accesso.
 * @property captivePortal `NET_CAPABILITY_CAPTIVE_PORTAL`.
 * @property congested manca `NOT_CONGESTED` (Android 11+).
 * @property suspended manca `NOT_SUSPENDED` (Android 11+).
 */
class LinkFacts(
  val networkId: String,
  val transports: Set<NetTransport>,
  val isVpn: Boolean = false,
  val metered: Boolean = false,
  val roaming: Boolean = false,
  val validated: Boolean = true,
  val captivePortal: Boolean = false,
  val congested: Boolean = false,
  val suspended: Boolean = false,
  val addresses: List<LinkAddr> = emptyList(),
  val routes: List<LinkRoute> = emptyList(),
  val dnsServers: List<IpAddr> = emptyList(),
  val domains: List<String> = emptyList(),
  val dhcpServer: IpAddr? = null,
) {

  /** Wi-Fi o Ethernet, e non VPN: le reti in cui puo' stare il NAS di casa. */
  val isLan: Boolean get() = !isVpn && (NetTransport.WIFI in transports || NetTransport.ETHERNET in transports)

  val isCellular: Boolean get() = !isVpn && NetTransport.CELLULAR in transports

  /** Il trasporto "principale": Wi-Fi, poi Ethernet, poi cellulare, poi altro. */
  val primaryTransport: NetTransport
    get() = when {
      NetTransport.WIFI in transports -> NetTransport.WIFI
      NetTransport.ETHERNET in transports -> NetTransport.ETHERNET
      NetTransport.CELLULAR in transports -> NetTransport.CELLULAR
      else -> NetTransport.OTHER
    }

  /** I prefissi ULA /48 degli indirizzi dell'interfaccia (l'impronta forte della rete di casa). */
  val ulaPrefixes: Set<IpPrefix>
    get() = addresses.mapNotNull { IpPrefix.ula48Of(it.ip) }.toSet()

  /** Il gateway IPv4 predefinito, se c'e'. */
  val gatewayV4: IpAddr?
    get() = routes.firstOrNull { it.isDefault && it.destination.isV4 && it.gateway != null }?.gateway

  /** C'e' un IPv6 globale (assegnato dal provider)? Senza, la rete e' "solo IPv4" per il FRITZ!Box in CGNAT (E-R11). */
  val hasGlobalIpv6: Boolean get() = addresses.any { it.ip.isGlobalV6 }

  /** Le sottoreti IPv4 dell'interfaccia. */
  val ipv4Subnets: List<IpPrefix> get() = addresses.filter { it.ip.isV4 }.map { it.prefix }

  /**
   * La rete sottostante ricavata dalla sola VPN, quando Android non fa vedere quella vera: senza VPN e senza
   * indirizzi (quelli sono del tunnel). Il "validata" della VPN non dice niente su cio' che sta sotto (un tunnel
   * che non porta la rotta predefinita non viene validato da Android): qui vale vero, per non negare un
   * percorso che funziona.
   */
  internal fun asUnderlyingOfVpn(): LinkFacts =
    LinkFacts(
      networkId = networkId,
      transports = transports.ifEmpty { setOf(NetTransport.OTHER) },
      isVpn = false,
      metered = metered,
      roaming = roaming,
      validated = true,
      captivePortal = captivePortal,
      congested = congested,
      suspended = suspended,
    )

  override fun equals(other: Any?): Boolean =
    other is LinkFacts &&
      other.networkId == networkId &&
      other.transports == transports &&
      other.isVpn == isVpn &&
      other.metered == metered &&
      other.roaming == roaming &&
      other.validated == validated &&
      other.captivePortal == captivePortal &&
      other.congested == congested &&
      other.suspended == suspended &&
      other.addresses == addresses &&
      other.routes == routes &&
      other.dnsServers == dnsServers &&
      other.domains == domains &&
      other.dhcpServer == dhcpServer

  override fun hashCode(): Int = networkId.hashCode() * 31 + addresses.hashCode()

  /** Mai gli indirizzi: solo la forma della rete (§4.2). */
  override fun toString(): String =
    "LinkFacts($networkId, $transports${if (isVpn) ", vpn" else ""}, ${addresses.size} indirizzi, ${routes.size} rotte)"
}

/**
 * Le reti che Android descrive in questo momento. `networks` unisce le quattro osservazioni: la rete predefinita, il
 * Wi-Fi/Ethernet sottostante (`NOT_VPN`), il cellulare sottostante (`NOT_VPN`) e le VPN, senza doppioni.
 *
 * @property defaultNetworkId la rete predefinita del sistema (con la VPN accesa e' la VPN), o null senza rete.
 */
class NetworkObservation(
  val defaultNetworkId: String?,
  val networks: List<LinkFacts>,
) {

  val lans: List<LinkFacts> get() = networks.filter { it.isLan }
  val cellulars: List<LinkFacts> get() = networks.filter { it.isCellular }
  val vpns: List<LinkFacts> get() = networks.filter { it.isVpn }

  fun byId(id: String): LinkFacts? = networks.firstOrNull { it.networkId == id }

  val defaultNetwork: LinkFacts? get() = defaultNetworkId?.let(::byId)

  override fun equals(other: Any?): Boolean =
    other is NetworkObservation && other.defaultNetworkId == defaultNetworkId && other.networks == networks

  override fun hashCode(): Int = (defaultNetworkId?.hashCode() ?: 0) * 31 + networks.hashCode()

  override fun toString(): String = "NetworkObservation(${networks.size} reti)"

  companion object {
    val EMPTY = NetworkObservation(defaultNetworkId = null, networks = emptyList())
  }
}
