package dev.pampa.mycloudgallery.core.nas.security

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.nas.DEVICE
import dev.pampa.mycloudgallery.core.nas.GOOD_PASSWORD
import dev.pampa.mycloudgallery.core.nas.Harness
import dev.pampa.mycloudgallery.core.nas.SHARE
import dev.pampa.mycloudgallery.core.nas.USER
import dev.pampa.mycloudgallery.core.nas.breaker.AuthBreakerRegistry
import dev.pampa.mycloudgallery.core.nas.contract.CredentialLoad
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.session.SmbNasSessionManager
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasCredentialSource
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryBreakerStore
import dev.pampa.mycloudgallery.core.nas.virtualClock
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.NasRoute
import dev.pampa.mycloudgallery.core.network.contract.NetTransport
import dev.pampa.mycloudgallery.core.network.env.LiveNetworkEnvironment
import dev.pampa.mycloudgallery.core.network.gate.DefaultNasRouteGate
import dev.pampa.mycloudgallery.core.network.link.LinkAddr
import dev.pampa.mycloudgallery.core.network.link.LinkFacts
import dev.pampa.mycloudgallery.core.network.link.LinkRoute
import dev.pampa.mycloudgallery.core.network.testing.FakeHomeFingerprintStore
import dev.pampa.mycloudgallery.core.network.testing.FakeLocalNetworkPermission
import dev.pampa.mycloudgallery.core.network.testing.FakeNetworkObservationSource
import dev.pampa.mycloudgallery.core.network.testing.LinkFactsSamples
import dev.pampa.mycloudgallery.core.network.testing.RecordingBindingFactory
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Fase 10 P11, voce **S8 (a)** della revisione finale (docs/fase10-progetto.md §2.11; C2, E-S10, E-S16): "su una rete estranea non parte
 * nessuna autenticazione SMB". Il riconoscimento vero (`LiveNetworkEnvironment` con l'impronta di casa), il cancello vero
 * (`DefaultNasRouteGate`) e il gestore della sessione vero, sopra il NAS simulato **raggiungibile allo stesso indirizzo**. 2.000 reti
 * estranee a caso, molte che "somigliano a casa": stessa sottorete `192.168.178.0/24`, stesso gateway, stesso dominio `fritz.box`, un ULA
 * diverso di un bit, nessun ULA, `fc` al posto di `fd`, Ethernet, VPN di terzi o dell'amico, dati mobili. Per ognuna: il percorso e'
 * negato, `withShare` fallisce, **nessuna connessione e nessun `SESSION_SETUP`** arrivano al NAS. Il controllo positivo alla fine: a casa
 * si autentica una volta; tornati su una rete estranea la sessione si chiude e non riparte.
 */
class S8ForeignNetworkTest {

  private fun ip(text: String) = LinkFactsSamples.ip(text)
  private fun prefix(text: String) = LinkFactsSamples.prefix(text)

  /** Una rete estranea a caso; [i] da' un id di rete nuovo a ogni giro. */
  private fun foreign(random: Random, i: Int): List<LinkFacts> {
    fun hex(bits: Int) = random.nextInt(1 shl bits).toString(16)
    val ula = when (random.nextInt(6)) {
      0 -> null
      1 -> "fd12:3456:789b" // un bit dopo l'ULA di casa (fd12:3456:789a::/48)
      2 -> "fc12:3456:789a" // fc00::/8 invece di fd00::/8
      3 -> "fd12:3457:789a"
      else -> "fd${hex(8).padStart(2, '0')}:${hex(16)}:${hex(16)}".let { if (it.startsWith("fd12:3456:789a")) "fd99:1:1" else it }
    }
    val homeLike = random.nextInt(3) != 0
    val subnet = if (homeLike) "192.168.178" else listOf("10.0.0", "192.168.1", "172.20.10", "192.168.178", "10.20.30").random(random)
    val transport = if (random.nextInt(5) == 0) NetTransport.ETHERNET else NetTransport.WIFI
    val lan = LinkFacts(
      networkId = "estranea-$i",
      transports = setOf(transport),
      validated = random.nextInt(10) != 0,
      addresses = listOfNotNull(
        LinkAddr(ip("$subnet.${2 + random.nextInt(200)}"), 24),
        ula?.let { LinkAddr(ip("$it:0:9c5b:ebff:fe00:${hex(12)}"), 64) },
        if (random.nextBoolean()) LinkAddr(ip("2001:db8:${hex(16)}:1::${hex(12)}"), 64) else null,
      ),
      routes = listOfNotNull(LinkRoute(prefix("0.0.0.0/0"), ip("$subnet.1")), LinkRoute(prefix("$subnet.0/24"), null), ula?.let { LinkRoute(prefix("$it::/64"), null) }),
      dnsServers = listOf(ip("$subnet.1")),
      domains = if (homeLike || random.nextBoolean()) listOf(listOf("fritz.box", "FRITZ.BOX.", "fritz.box.").random(random)) else emptyList(),
      dhcpServer = ip("$subnet.1"),
    )
    return when (random.nextInt(8)) {
      0 -> listOf(LinkFactsSamples.cellular(id = "cell-$i"))
      1 -> listOf(LinkFactsSamples.foreignVpn(id = "vpn-terzi-$i"), lan)
      2 -> listOf(LinkFactsSamples.friendVpn(id = "vpn-amico-$i", over = NetTransport.CELLULAR), LinkFactsSamples.cellular(id = "cell-$i"))
      3 -> listOf(LinkFactsSamples.friendWifi(id = "wifi-amico-$i"))
      else -> listOf(lan)
    }
  }

  @Test
  fun suDuemilaRetiEstraneeNessunaAutenticazione() = runTest {
    val source = FakeNetworkObservationSource()
    val environment = LiveNetworkEnvironment(source, FakeHomeFingerprintStore(LinkFactsSamples.homeFingerprint()), FakeLocalNetworkPermission(), backgroundScope, clockMillis = { 1L })
    val gate = DefaultNasRouteGate(environment, RecordingBindingFactory(), backgroundScope)
    val backend = Harness.defaultBackend()
    val account = NasAccount(NasAddress("192.168.178.58", "MyCloudEX2Ultra"), USER, "slot-nas", backend.serverGuid)
    val manager = SmbNasSessionManager(
      account = { account },
      routeGate = gate,
      credentials = FakeNasCredentialSource { CredentialLoad.Found(NasCredentials(USER, GOOD_PASSWORD.toCharArray())) },
      breakers = AuthBreakerRegistry(InMemoryBreakerStore(), backgroundScope, wallClock = { Harness.WALL_CLOCK }),
      scope = backgroundScope,
      transport = backend,
      clock = virtualClock(),
      io = Dispatchers.Unconfined,
      deviceId = { DEVICE },
      backoff = { },
      shareGuard = null,
    )
    val random = Random(20261031)
    val reasons = HashMap<String, Int>()
    repeat(2_000) { i ->
      source.set(*foreign(random, i).toTypedArray())
      val route = gate.current()
      assertWithMessage("rete $i: percorso").that(route).isInstanceOf(NasRoute.Denied::class.java)
      reasons.merge((route as NasRoute.Denied).reason.name, 1, Int::plus)
      val result = manager.withShare(SHARE, Purpose.INTERACTIVE) { }
      assertWithMessage("rete $i: withShare").that(result).isInstanceOf(NasResult.Err::class.java)
    }
    assertWithMessage("connessioni al NAS da una rete estranea").that(backend.connectedNetworks).isEmpty()
    assertWithMessage("SESSION_SETUP da una rete estranea").that(backend.authenticateCalls.get()).isEqualTo(0)

    // Il controllo positivo: a casa una sola autenticazione; poi di nuovo fuori, la sessione si chiude e non riparte.
    source.set(LinkFactsSamples.homeWifi())
    assertThat(manager.withShare(SHARE, Purpose.INTERACTIVE) { }).isInstanceOf(NasResult.Ok::class.java)
    assertThat(backend.authenticateCalls.get()).isEqualTo(1)
    source.set(LinkFactsSamples.friendWifi(id = "wifi-amico-dopo"))
    assertThat(manager.withShare(SHARE, Purpose.INTERACTIVE) { }).isInstanceOf(NasResult.Err::class.java)
    assertThat(backend.authenticateCalls.get()).isEqualTo(1)
    assertThat(backend.connectedNetworks.toSet()).containsExactly("wifi-home")
    println("MCG-SEC S8a reti_estranee=2000 negate=2000 connessioni=0 session_setup=0 motivi=${reasons.toSortedMap()} controllo_casa=1")
  }
}
