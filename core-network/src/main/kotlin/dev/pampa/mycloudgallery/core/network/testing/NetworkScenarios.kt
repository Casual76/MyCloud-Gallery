package dev.pampa.mycloudgallery.core.network.testing

import android.content.Intent
import dev.pampa.mycloudgallery.core.network.contract.HomeFingerprint
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.NasSessionActivity
import dev.pampa.mycloudgallery.core.network.contract.NetTransport
import dev.pampa.mycloudgallery.core.network.contract.SocketBinding
import dev.pampa.mycloudgallery.core.network.contract.VpnControlConfig
import dev.pampa.mycloudgallery.core.network.contract.VpnControlConfigStore
import dev.pampa.mycloudgallery.core.network.contract.VpnSuspendReason
import dev.pampa.mycloudgallery.core.network.contract.WireGuardTunnelNames
import dev.pampa.mycloudgallery.core.network.env.NetworkObservationSource
import dev.pampa.mycloudgallery.core.network.gate.BindingFactory
import dev.pampa.mycloudgallery.core.network.link.IpAddr
import dev.pampa.mycloudgallery.core.network.link.IpPrefix
import dev.pampa.mycloudgallery.core.network.link.LinkAddr
import dev.pampa.mycloudgallery.core.network.link.LinkFacts
import dev.pampa.mycloudgallery.core.network.link.LinkRoute
import dev.pampa.mycloudgallery.core.network.link.NetworkObservation
import dev.pampa.mycloudgallery.core.network.vpn.WireGuardPlatform
import dev.pampa.mycloudgallery.core.network.wol.MagicPacketSender
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Reti SINTETICHE per i test a tabella e per le anteprime (D6: in `src/main`, package `testing`, mai nella release).
//
// Gli indirizzi sono inventati: la sottorete 192.168.178.0/24 e' quella di fabbrica del FRITZ!Box (uguale in mezza
// Italia, per questo da sola non vale come impronta, E-S10); i prefissi ULA `fd12:3456:789a::/48` (casa) e
// `fd5e:21c7:b0a4::/48` (l'amico) e i globali `2001:db8::/32` (riservato alla documentazione) non appartengono a
// nessuna rete vera. La FORMA, invece, e' quella misurata sul telefono: dominio `fritz.box`, DNS con IPv4 del
// gateway e IPv6 del router, indirizzi link-local, ULA e globali (spike/results/nasprobe*).

/** Costruisce reti sintetiche. */
object LinkFactsSamples {

  const val HOME_ULA = "fd12:3456:789a::/48"
  const val FRIEND_ULA = "fd5e:21c7:b0a4::/48"

  fun ip(text: String): IpAddr = requireNotNull(IpAddr.parse(text)) { "IP di prova non valido" }

  fun prefix(text: String): IpPrefix = requireNotNull(IpPrefix.parse(text)) { "prefisso di prova non valido" }

  private fun addr(text: String, length: Int) = LinkAddr(ip(text), length)

  private fun route(dest: String, gateway: String? = null) = LinkRoute(prefix(dest), gateway?.let(::ip))

  /** L'impronta di casa che l'onboarding avrebbe salvato. */
  fun homeFingerprint(capturedAt: Long = 1L) =
    HomeFingerprint(ulaPrefixes = setOf(HOME_ULA), gatewayV4 = "192.168.178.1", dnsDomain = "fritz.box", capturedAt = capturedAt)

  /** Il Wi-Fi di casa: IPv4 di fabbrica, ULA di casa, IPv6 globale, `fritz.box`. */
  fun homeWifi(
    id: String = "wifi-home",
    validated: Boolean = true,
    metered: Boolean = false,
    captivePortal: Boolean = false,
    congested: Boolean = false,
    globalIpv6: Boolean = true,
  ): LinkFacts =
    LinkFacts(
      networkId = id,
      transports = setOf(NetTransport.WIFI),
      metered = metered,
      validated = validated,
      captivePortal = captivePortal,
      congested = congested,
      addresses = listOfNotNull(
        addr("fe80::9c5b:ebff:fe00:1", 64),
        addr("192.168.178.20", 24),
        if (globalIpv6) addr("2001:db8:abcd:1:9c5b:ebff:fe00:1", 64) else null,
        addr("fd12:3456:789a:0:9c5b:ebff:fe00:1", 64),
        addr("fd12:3456:789a:0:552c:f5b3:6090:1", 64),
      ),
      routes = listOf(route("0.0.0.0/0", "192.168.178.1"), route("192.168.178.0/24"), route("fd12:3456:789a::/64")),
      dnsServers = listOf(ip("fd12:3456:789a::1"), ip("192.168.178.1")),
      domains = listOf("fritz.box"),
      dhcpServer = ip("192.168.178.1"),
    )

  /** Il FRITZ!Box di un amico: **stessa** sottorete, gateway e dominio, ULA **diverso** (E-S10). */
  fun friendWifi(id: String = "wifi-friend", validated: Boolean = true): LinkFacts =
    LinkFacts(
      networkId = id,
      transports = setOf(NetTransport.WIFI),
      validated = validated,
      addresses = listOf(
        addr("fe80::2a1:3cff:fe00:7", 64),
        addr("192.168.178.44", 24),
        addr("2001:db8:ffff:9:2a1:3cff:fe00:7", 64),
        addr("fd5e:21c7:b0a4:0:2a1:3cff:fe00:7", 64),
      ),
      routes = listOf(route("0.0.0.0/0", "192.168.178.1"), route("192.168.178.0/24")),
      dnsServers = listOf(ip("fd5e:21c7:b0a4::1"), ip("192.168.178.1")),
      domains = listOf("fritz.box"),
      dhcpServer = ip("192.168.178.1"),
    )

  /** Casa dopo il reset del router: stessa sottorete e dominio, ULA **nuovo** (E-R16). */
  fun homeWifiAfterRouterReset(id: String = "wifi-home-2"): LinkFacts =
    LinkFacts(
      networkId = id,
      transports = setOf(NetTransport.WIFI),
      addresses = listOf(addr("192.168.178.20", 24), addr("2001:db8:abcd:1:9c5b:ebff:fe00:1", 64), addr("fdaa:bbcc:ddee:0:9c5b:ebff:fe00:1", 64)),
      routes = listOf(route("0.0.0.0/0", "192.168.178.1"), route("192.168.178.0/24")),
      dnsServers = listOf(ip("fdaa:bbcc:ddee::1"), ip("192.168.178.1")),
      domains = listOf("fritz.box"),
    )

  /** Un Wi-Fi qualunque (un bar): altra sottorete, nessun ULA, nessun dominio. */
  fun cafeWifi(id: String = "wifi-cafe", validated: Boolean = true, captivePortal: Boolean = false): LinkFacts =
    LinkFacts(
      networkId = id,
      transports = setOf(NetTransport.WIFI),
      validated = validated,
      captivePortal = captivePortal,
      addresses = listOf(addr("10.20.30.41", 22), addr("2001:db8:5:5:1:2:3:4", 64)),
      routes = listOf(route("0.0.0.0/0", "10.20.30.1"), route("10.20.28.0/22")),
      dnsServers = listOf(ip("10.20.30.1")),
    )

  /** L'hotspot di un altro telefono: a consumo, sottorete propria, solo IPv4. */
  fun hotspotWifi(id: String = "wifi-hotspot", metered: Boolean = true): LinkFacts =
    LinkFacts(
      networkId = id,
      transports = setOf(NetTransport.WIFI),
      metered = metered,
      addresses = listOf(addr("172.20.10.2", 28)),
      routes = listOf(route("0.0.0.0/0", "172.20.10.1"), route("172.20.10.0/28")),
      dnsServers = listOf(ip("172.20.10.1")),
    )

  /** I dati mobili. Con [ipv6] falso: solo IPv4 (E-R11). */
  fun cellular(id: String = "cell-1", roaming: Boolean = false, ipv6: Boolean = true, validated: Boolean = true): LinkFacts =
    LinkFacts(
      networkId = id,
      transports = setOf(NetTransport.CELLULAR),
      metered = true,
      roaming = roaming,
      validated = validated,
      addresses = listOfNotNull(addr("10.123.227.14", 30), if (ipv6) addr("2001:db8:77:1:aa:bb:cc:dd", 64) else null),
      routes = listOf(route("0.0.0.0/0", "10.123.227.13")) + if (ipv6) listOf(route("::/0", "fe80::1")) else emptyList(),
      dnsServers = listOf(ip("10.123.227.13")),
    )

  /**
   * La VPN di casa come la vuole la guida del passo 11: `AllowedIPs` = sottorete di casa + ULA. Eredita il
   * trasporto sottostante ([over]).
   */
  fun homeVpn(id: String = "vpn-home", over: NetTransport = NetTransport.WIFI, metered: Boolean = false): LinkFacts =
    LinkFacts(
      networkId = id,
      transports = setOf(over),
      isVpn = true,
      metered = metered,
      addresses = listOf(addr("192.168.178.201", 32), addr("fd12:3456:789a:f::2", 128)),
      routes = listOf(route("192.168.178.0/24"), route("fd12:3456:789a::/48")),
      dnsServers = listOf(ip("192.168.178.1")),
      domains = listOf("fritz.box"),
    )

  /** La configurazione "di fabbrica" che esporta il FRITZ!Box: rotta predefinita, DNS del router, dominio. Niente ULA. */
  fun homeVpnFullTunnel(id: String = "vpn-home-full", over: NetTransport = NetTransport.CELLULAR): LinkFacts =
    LinkFacts(
      networkId = id,
      transports = setOf(over),
      isVpn = true,
      addresses = listOf(addr("192.168.178.201", 32)),
      routes = listOf(route("0.0.0.0/0"), route("::/0")),
      dnsServers = listOf(ip("192.168.178.1")),
      domains = listOf("fritz.box"),
    )

  /** Una VPN commerciale: rotta predefinita, DNS suoi, nessun dominio (E-S16). */
  fun foreignVpn(id: String = "vpn-foreign", over: NetTransport = NetTransport.WIFI): LinkFacts =
    LinkFacts(
      networkId = id,
      transports = setOf(over),
      isVpn = true,
      addresses = listOf(addr("10.2.0.2", 32)),
      routes = listOf(route("0.0.0.0/0"), route("::/0")),
      dnsServers = listOf(ip("10.2.0.1")),
    )

  /** Il tunnel verso il FRITZ!Box **di un amico**: stessa sottorete e dominio, ULA diverso. */
  fun friendVpn(id: String = "vpn-friend", over: NetTransport = NetTransport.WIFI): LinkFacts =
    LinkFacts(
      networkId = id,
      transports = setOf(over),
      isVpn = true,
      addresses = listOf(addr("192.168.178.201", 32)),
      routes = listOf(route("192.168.178.0/24"), route("fd5e:21c7:b0a4::/48")),
      dnsServers = listOf(ip("192.168.178.1")),
      domains = listOf("fritz.box"),
    )

  fun observation(defaultId: String?, vararg networks: LinkFacts) = NetworkObservation(defaultId, networks.toList())

  /** Le reti con la prima come predefinita. */
  fun observation(vararg networks: LinkFacts) = NetworkObservation(networks.firstOrNull()?.networkId, networks.toList())

  fun nas(ip: String = "192.168.178.50") = NasAddress(ip, name = "NasDiProva")
}

/** Reti pilotate dal test: si cambia [observation]. */
class FakeNetworkObservationSource(initial: NetworkObservation = NetworkObservation.EMPTY) : NetworkObservationSource {
  private val state = MutableStateFlow(initial)

  override val observation: StateFlow<NetworkObservation> = state

  var started: Boolean = false
    private set

  override fun start() {
    started = true
  }

  fun set(observation: NetworkObservation) {
    state.value = observation
  }

  fun set(vararg networks: LinkFacts) {
    state.value = LinkFactsSamples.observation(*networks)
  }
}

/** Configurazione del controllo VPN in memoria. */
class InMemoryVpnControlConfigStore(
  initial: VpnControlConfig = VpnControlConfig.OFF,
  initialSuspension: VpnSuspendReason? = null,
) : VpnControlConfigStore {
  override val config: MutableStateFlow<VpnControlConfig> = MutableStateFlow(initial)
  override val suspension: MutableStateFlow<VpnSuspendReason?> = MutableStateFlow(initialSuspension)

  override suspend fun save(config: VpnControlConfig) {
    this.config.value = VpnControlConfig(config.enabled, config.tunnelName?.takeIf(WireGuardTunnelNames::isValid))
    suspension.value = null
  }

  override suspend fun suspendBecause(reason: VpnSuspendReason) {
    suspension.value = reason
  }

  override suspend fun clearSuspension() {
    suspension.value = null
  }
}

/** La sessione NAS occupata o libera, a comando. */
class FakeNasSessionActivity(busy: Boolean = false) : NasSessionActivity {
  override val isBusy: MutableStateFlow<Boolean> = MutableStateFlow(busy)
}

/** WireGuard finto: registra i comandi e dice cosa e' installato. */
class FakeWireGuardPlatform(
  var installed: Boolean = true,
  var permission: Boolean = true,
) : WireGuardPlatform {
  /** (su?, nome del tunnel), nell'ordine in cui sono partiti. */
  val sent: MutableList<Pair<Boolean, String>> = mutableListOf()

  /** Chiamato a ogni comando: il test lo usa per far reagire la rete come reagirebbe WireGuard. */
  var onCommand: (up: Boolean, tunnel: String) -> Unit = { _, _ -> }

  override fun isInstalled(): Boolean = installed

  override fun hasControlPermission(): Boolean = permission

  override fun sendTunnelCommand(up: Boolean, tunnelName: String) {
    sent += up to tunnelName
    onCommand(up, tunnelName)
  }

  override fun launchIntent(): Intent? = null
}

/** Registra i pacchetti magici mandati. */
class RecordingMagicPacketSender : MagicPacketSender {
  /** (rete, lunghezza del pacchetto, porta). */
  val sent: MutableList<Triple<String, Int, Int>> = mutableListOf()

  override fun send(networkId: String, packet: ByteArray, port: Int): Boolean {
    sent += Triple(networkId, packet.size, port)
    return true
  }
}

/** Crea un legame registratore per ogni rete richiesta; `missing` simula una rete sparita. */
class RecordingBindingFactory : BindingFactory {
  val created: MutableMap<String, RecordingSocketBinding> = mutableMapOf()
  val missing: MutableSet<String> = mutableSetOf()

  override fun bindingFor(networkId: String): SocketBinding? =
    if (networkId in missing) null else created.getOrPut(networkId) { RecordingSocketBinding(networkId) }
}
