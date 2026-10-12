package dev.pampa.mycloudgallery.core.network.env

import android.content.Context
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.RouteInfo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.network.contract.HomeMatch
import dev.pampa.mycloudgallery.core.network.contract.NasRoute
import dev.pampa.mycloudgallery.core.network.contract.NetPlace
import dev.pampa.mycloudgallery.core.network.contract.NetTransport
import dev.pampa.mycloudgallery.core.network.contract.Via
import dev.pampa.mycloudgallery.core.network.contract.VpnObservation
import dev.pampa.mycloudgallery.core.network.gate.AndroidBindingFactory
import dev.pampa.mycloudgallery.core.network.gate.DefaultNasRouteGate
import dev.pampa.mycloudgallery.core.network.home.ObservedHomeCapture
import dev.pampa.mycloudgallery.core.network.testing.FakeHomeFingerprintStore
import dev.pampa.mycloudgallery.core.network.testing.FakeLocalNetworkPermission
import dev.pampa.mycloudgallery.core.network.testing.LinkFactsSamples
import java.net.InetAddress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * Lo strato Android sottile, su Robolectric: i quattro `NetworkCallback` e la traduzione da `Network`,
 * `NetworkCapabilities` e `LinkProperties` a `LinkFacts`. Le proprieta' del collegamento si costruiscono con la
 * riflessione (i costruttori di `LinkAddress` e `RouteInfo` sono nascosti nell'SDK pubblico, ma ci sono a runtime).
 * Gli indirizzi sono sintetici (vedi `LinkFactsSamples`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AndroidNetworkObserverTest {

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val connectivity = context.getSystemService(ConnectivityManager::class.java)
  private val observer = AndroidNetworkObserver(context)

  // --- Costruzione delle reti finte --------------------------------------------------------------------

  private fun network(id: Int): Network = ShadowNetwork.newInstance(id)

  private fun caps(transports: List<Int>, capabilities: List<Int>): NetworkCapabilities {
    val caps = ShadowNetworkCapabilities.newInstance()
    val shadow = shadowOf(caps)
    transports.forEach { shadow.addTransportType(it) }
    capabilities.forEach { shadow.addCapability(it) }
    return caps
  }

  private val goodCapabilities = listOf(
    NetworkCapabilities.NET_CAPABILITY_INTERNET,
    NetworkCapabilities.NET_CAPABILITY_NOT_VPN,
    NetworkCapabilities.NET_CAPABILITY_NOT_METERED,
    NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING,
    NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED,
    NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED,
    NetworkCapabilities.NET_CAPABILITY_VALIDATED,
  )

  private fun inet(text: String): InetAddress = InetAddress.getByAddress(LinkFactsSamples.ip(text).toBytes())

  private fun linkAddress(text: String, length: Int): LinkAddress =
    LinkAddress::class.java.getConstructor(InetAddress::class.java, Int::class.javaPrimitiveType).newInstance(inet(text), length)

  private fun route(dest: String, length: Int, gateway: String?, iface: String): RouteInfo =
    RouteInfo::class.java
      .getConstructor(IpPrefix::class.java, InetAddress::class.java, String::class.java, Int::class.javaPrimitiveType)
      .newInstance(IpPrefix(inet(dest), length), gateway?.let(::inet), iface, RouteInfo.RTN_UNICAST)

  private fun homeWifiProperties(): LinkProperties =
    LinkProperties().apply {
      interfaceName = "wlan0"
      setLinkAddresses(
        listOf(
          linkAddress("fe80::9c5b:ebff:fe00:1", 64),
          linkAddress("192.168.178.20", 24),
          linkAddress("2001:db8:abcd:1:9c5b:ebff:fe00:1", 64),
          linkAddress("fd12:3456:789a:0:9c5b:ebff:fe00:1", 64),
        ),
      )
      addRoute(route("0.0.0.0", 0, "192.168.178.1", "wlan0"))
      addRoute(route("192.168.178.0", 24, null, "wlan0"))
      setDnsServers(listOf(inet("fd12:3456:789a::1"), inet("192.168.178.1")))
      domains = "fritz.box"
    }

  private fun tunnelProperties(): LinkProperties =
    LinkProperties().apply {
      interfaceName = "wg0"
      setLinkAddresses(listOf(linkAddress("192.168.178.201", 32), linkAddress("fd12:3456:789a:f::2", 128)))
      addRoute(route("192.168.178.0", 24, null, "wg0"))
      addRoute(route("fd12:3456:789a::", 48, null, "wg0"))
      setDnsServers(listOf(inet("192.168.178.1")))
      domains = "fritz.box"
    }

  /** Android consegna le reti in tre tempi: disponibile, capacita', proprieta'. */
  private fun deliver(callback: ConnectivityManager.NetworkCallback, network: Network, caps: NetworkCapabilities, properties: LinkProperties) {
    callback.onAvailable(network)
    callback.onCapabilitiesChanged(network, caps)
    callback.onLinkPropertiesChanged(network, properties)
  }

  private val defaultCallback get() = observer.registeredCallbacks[0]
  private val lanCallback get() = observer.registeredCallbacks[1]
  private val cellularCallback get() = observer.registeredCallbacks[2]
  private val vpnCallback get() = observer.registeredCallbacks[3]

  // --- Registrazione -----------------------------------------------------------------------------------

  @Test
  fun registraQuattroCallback_unaVoltaSola() {
    observer.start()
    observer.start()

    assertThat(observer.registeredCallbacks).hasSize(4)
    assertThat(shadowOf(connectivity).networkCallbacks).hasSize(4)
  }

  @Test
  fun stop_toglieICallbackELeReti() {
    observer.start()
    deliver(defaultCallback, network(100), caps(listOf(NetworkCapabilities.TRANSPORT_WIFI), goodCapabilities), homeWifiProperties())
    assertThat(observer.observation.value.networks).hasSize(1)

    observer.stop()

    assertThat(shadowOf(connectivity).networkCallbacks).isEmpty()
    assertThat(observer.observation.value.networks).isEmpty()
  }

  // --- Traduzione --------------------------------------------------------------------------------------

  @Test
  fun ilWifiDiCasa_siTraduceInLinkFacts() {
    observer.start()
    val wifi = network(100)

    deliver(defaultCallback, wifi, caps(listOf(NetworkCapabilities.TRANSPORT_WIFI), goodCapabilities), homeWifiProperties())
    deliver(lanCallback, wifi, caps(listOf(NetworkCapabilities.TRANSPORT_WIFI), goodCapabilities), homeWifiProperties())

    val observation = observer.observation.value
    val facts = observation.byId("100")!!
    assertThat(observation.defaultNetworkId).isEqualTo("100")
    // La stessa rete vista da due callback e' una sola rete.
    assertThat(observation.networks).hasSize(1)
    assertThat(facts.transports).containsExactly(NetTransport.WIFI)
    assertThat(facts.isVpn).isFalse()
    assertThat(facts.metered).isFalse()
    assertThat(facts.validated).isTrue()
    assertThat(facts.captivePortal).isFalse()
    assertThat(facts.domains).containsExactly("fritz.box")
    assertThat(facts.gatewayV4).isEqualTo(LinkFactsSamples.ip("192.168.178.1"))
    assertThat(facts.ulaPrefixes).containsExactly(LinkFactsSamples.prefix(LinkFactsSamples.HOME_ULA))
    assertThat(facts.hasGlobalIpv6).isTrue()
    assertThat(facts.ipv4Subnets).containsExactly(LinkFactsSamples.prefix("192.168.178.0/24"))
    assertThat(facts.dnsServers).contains(LinkFactsSamples.ip("192.168.178.1"))
  }

  @Test
  fun unaReteEntraSoloQuandoSiConosconoCapacitaEProprieta() {
    observer.start()
    val wifi = network(100)

    defaultCallback.onAvailable(wifi)
    assertThat(observer.observation.value.networks).isEmpty()
    defaultCallback.onCapabilitiesChanged(wifi, caps(listOf(NetworkCapabilities.TRANSPORT_WIFI), goodCapabilities))
    assertThat(observer.observation.value.networks).isEmpty()
    defaultCallback.onLinkPropertiesChanged(wifi, homeWifiProperties())

    assertThat(observer.observation.value.networks).hasSize(1)
  }

  @Test
  fun leCapacitaDelWifi_aConsumo_senzaInternet_paginaDiAccesso_congestione() {
    observer.start()
    val hotspot = network(101)

    // Niente NOT_METERED (a consumo), niente VALIDATED, c'e' CAPTIVE_PORTAL, manca NOT_CONGESTED.
    deliver(
      lanCallback,
      hotspot,
      caps(
        listOf(NetworkCapabilities.TRANSPORT_WIFI),
        listOf(
          NetworkCapabilities.NET_CAPABILITY_INTERNET,
          NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING,
          NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED,
          NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL,
        ),
      ),
      homeWifiProperties(),
    )

    val facts = observer.observation.value.byId("101")!!
    assertThat(facts.metered).isTrue()
    assertThat(facts.validated).isFalse()
    assertThat(facts.captivePortal).isTrue()
    assertThat(facts.congested).isTrue()
    assertThat(facts.suspended).isFalse()
  }

  @Test
  fun ilCellulareInRoaming_soloSeManca_NOT_ROAMING() {
    observer.start()
    val roaming = network(102)
    val home = network(103)

    deliver(cellularCallback, roaming, caps(listOf(NetworkCapabilities.TRANSPORT_CELLULAR), listOf(NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_VALIDATED)), LinkProperties())
    deliver(
      cellularCallback,
      home,
      caps(listOf(NetworkCapabilities.TRANSPORT_CELLULAR), listOf(NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING, NetworkCapabilities.NET_CAPABILITY_VALIDATED)),
      LinkProperties(),
    )

    assertThat(observer.observation.value.byId("102")!!.roaming).isTrue()
    assertThat(observer.observation.value.byId("103")!!.roaming).isFalse()
    assertThat(observer.observation.value.byId("102")!!.isCellular).isTrue()
  }

  @Test
  fun laVpn_ereditaITrasportiESiTraducono_rotteDnsEIndirizzi() {
    observer.start()
    val tunnel = network(200)

    deliver(
      vpnCallback,
      tunnel,
      caps(listOf(NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_WIFI), goodCapabilities - NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
      tunnelProperties(),
    )

    val facts = observer.observation.value.byId("200")!!
    assertThat(facts.isVpn).isTrue()
    // I trasporti sono quelli sottostanti: la VPN non e' un trasporto.
    assertThat(facts.transports).containsExactly(NetTransport.WIFI)
    assertThat(facts.routes.map { it.destination.toText() }).containsExactly("192.168.178.0/24", "fd12:3456:789a::/48")
    assertThat(facts.dnsServers).containsExactly(LinkFactsSamples.ip("192.168.178.1"))
  }

  @Test
  fun unaReteSparita_esceDallOsservazione() {
    observer.start()
    val wifi = network(100)
    deliver(defaultCallback, wifi, caps(listOf(NetworkCapabilities.TRANSPORT_WIFI), goodCapabilities), homeWifiProperties())
    deliver(lanCallback, wifi, caps(listOf(NetworkCapabilities.TRANSPORT_WIFI), goodCapabilities), homeWifiProperties())

    // Persa dal callback predefinito ma non da quello Wi-Fi: la rete c'e' ancora, non e' piu' la predefinita.
    defaultCallback.onLost(wifi)
    assertThat(observer.observation.value.networks).hasSize(1)
    assertThat(observer.observation.value.defaultNetworkId).isNull()

    lanCallback.onLost(wifi)
    assertThat(observer.observation.value.networks).isEmpty()
  }

  @Test
  fun quandoLaPredefinitaPassaAllaVpn_siVedonoTutteEDue() {
    observer.start()
    val wifi = network(100)
    val tunnel = network(200)
    deliver(defaultCallback, wifi, caps(listOf(NetworkCapabilities.TRANSPORT_WIFI), goodCapabilities), homeWifiProperties())
    deliver(lanCallback, wifi, caps(listOf(NetworkCapabilities.TRANSPORT_WIFI), goodCapabilities), homeWifiProperties())

    // La VPN sale: Android dice "disponibile" per la nuova predefinita prima di perdere la vecchia.
    val tunnelCaps = caps(listOf(NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_WIFI), goodCapabilities - NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    deliver(vpnCallback, tunnel, tunnelCaps, tunnelProperties())
    deliver(defaultCallback, tunnel, tunnelCaps, tunnelProperties())
    defaultCallback.onLost(wifi)

    val observation = observer.observation.value
    assertThat(observation.defaultNetworkId).isEqualTo("200")
    assertThat(observation.lans.map { it.networkId }).containsExactly("100")
    assertThat(observation.vpns.map { it.networkId }).containsExactly("200")
  }

  // --- Dall'inizio alla fine: Android -> ambiente -> cancello --------------------------------------------

  @Test
  fun dalleReteVereAlCancello_casa_poiCasaConVpn_poiVpnSpentaDiNuovo() = runTest {
    observer.start()
    val store = FakeHomeFingerprintStore(LinkFactsSamples.homeFingerprint())
    val environment = LiveNetworkEnvironment(observer, store, FakeLocalNetworkPermission(), backgroundScope, clockMillis = { 1L })
    val gate = DefaultNasRouteGate(environment, AndroidBindingFactory(observer), backgroundScope)
    val wifi = network(100)
    val tunnel = network(200)
    val wifiCaps = caps(listOf(NetworkCapabilities.TRANSPORT_WIFI), goodCapabilities)

    // 1. Wi-Fi di casa.
    deliver(defaultCallback, wifi, wifiCaps, homeWifiProperties())
    deliver(lanCallback, wifi, wifiCaps, homeWifiProperties())
    runCurrent()
    val onLan = gate.current() as NasRoute.Allowed
    assertThat(environment.snapshot.value.place).isEqualTo(NetPlace.HOME)
    assertThat(environment.snapshot.value.home).isEqualTo(HomeMatch.Match)
    assertThat(onLan.via).isEqualTo(Via.HOME_LAN)
    assertThat(onLan.binding.networkId).isEqualTo("100")

    // 2. Si accende WireGuard: il Wi-Fi resta visibile sotto la VPN (T-17), il percorso passa al tunnel.
    val tunnelCaps = caps(listOf(NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_WIFI), goodCapabilities - NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    deliver(vpnCallback, tunnel, tunnelCaps, tunnelProperties())
    deliver(defaultCallback, tunnel, tunnelCaps, tunnelProperties())
    runCurrent()
    val throughVpn = gate.current() as NasRoute.Allowed
    assertThat(environment.snapshot.value.vpn).isEqualTo(VpnObservation.HOME_TUNNEL)
    assertThat(environment.snapshot.value.place).isEqualTo(NetPlace.HOME)
    assertThat(throughVpn.via).isEqualTo(Via.HOME_LAN_THROUGH_VPN)
    assertThat(throughVpn.binding.networkId).isEqualTo("200")

    // 3. Si spegne: il percorso torna al Wi-Fi.
    vpnCallback.onLost(tunnel)
    defaultCallback.onLost(tunnel)
    deliver(defaultCallback, wifi, wifiCaps, homeWifiProperties())
    runCurrent()
    val backOnLan = gate.current() as NasRoute.Allowed
    assertThat(backOnLan.via).isEqualTo(Via.HOME_LAN)
    assertThat(backOnLan.binding.networkId).isEqualTo("100")
  }

  @Test
  fun ilLegameVeroDellaRete_siConnetteConUnSocketDellaSuaFabbrica() {
    observer.start()
    val wifi = network(100)
    deliver(lanCallback, wifi, caps(listOf(NetworkCapabilities.TRANSPORT_WIFI), goodCapabilities), homeWifiProperties())
    val binding = AndroidBindingFactory(observer).bindingFor("100")!!

    java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
      val socket = binding.connect(server.inetAddress, server.localPort, kotlin.time.Duration.parse("2s"))

      assertThat(binding.networkId).isEqualTo("100")
      assertThat(socket.isConnected).isTrue()
      socket.close()
    }
    // Una rete sparita non ha piu' un legame.
    lanCallback.onLost(wifi)
    assertThat(AndroidBindingFactory(observer).bindingFor("100")).isNull()
  }

  @Test
  fun laLetturaDelCandidato_ePossibileAncheConLaVpnAccesa() = runTest {
    observer.start()
    val store = FakeHomeFingerprintStore(null)
    val environment = LiveNetworkEnvironment(observer, store, FakeLocalNetworkPermission(), backgroundScope, clockMillis = { 1L })
    val wifi = network(100)
    val tunnel = network(200)
    val wifiCaps = caps(listOf(NetworkCapabilities.TRANSPORT_WIFI), goodCapabilities)
    deliver(lanCallback, wifi, wifiCaps, homeWifiProperties())
    deliver(
      vpnCallback,
      tunnel,
      caps(listOf(NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_WIFI), goodCapabilities - NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
      tunnelProperties(),
    )

    val candidate = ObservedHomeCapture(environment).candidateFromCurrentWifi()

    assertThat(candidate).isNotNull()
    assertThat(candidate!!.networkId).isEqualTo("100")
    assertThat(candidate.ulaPrefixes).containsExactly(LinkFactsSamples.HOME_ULA)
    assertThat(candidate.canBeConfirmed).isTrue()
  }
}
