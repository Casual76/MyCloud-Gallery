package dev.pampa.mycloudgallery.core.network.gate

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.network.contract.DenyReason
import dev.pampa.mycloudgallery.core.network.contract.HomeCandidate
import dev.pampa.mycloudgallery.core.network.contract.LocalNetworkAccess
import dev.pampa.mycloudgallery.core.network.contract.NasRoute
import dev.pampa.mycloudgallery.core.network.contract.NetPlace
import dev.pampa.mycloudgallery.core.network.contract.NetTransport
import dev.pampa.mycloudgallery.core.network.contract.Via
import dev.pampa.mycloudgallery.core.network.env.LiveNetworkEnvironment
import dev.pampa.mycloudgallery.core.network.home.HomeCandidates
import dev.pampa.mycloudgallery.core.network.link.LinkFacts
import dev.pampa.mycloudgallery.core.network.testing.FakeHomeFingerprintStore
import dev.pampa.mycloudgallery.core.network.testing.FakeLocalNetworkPermission
import dev.pampa.mycloudgallery.core.network.testing.FakeNetworkObservationSource
import dev.pampa.mycloudgallery.core.network.testing.LinkFactsSamples
import dev.pampa.mycloudgallery.core.network.testing.RecordingBindingFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Il cancello: dal luogo al percorso con il socket legato. Una rete non riconosciuta da' `Denied` e il tipo non
 * contiene nessun socket: non parte nemmeno la TCP.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NasRouteGateTest {

  private val source = FakeNetworkObservationSource()
  private val store = FakeHomeFingerprintStore(LinkFactsSamples.homeFingerprint())
  private val permission = FakeLocalNetworkPermission()
  private val bindings = RecordingBindingFactory()

  private class Rig(val environment: LiveNetworkEnvironment, val gate: DefaultNasRouteGate)

  private fun TestScope.rig(): Rig {
    val environment = LiveNetworkEnvironment(source, store, permission, backgroundScope, clockMillis = { 1L })
    return Rig(environment, DefaultNasRouteGate(environment, bindings, backgroundScope))
  }

  private fun allowed(route: NasRoute): NasRoute.Allowed = route as NasRoute.Allowed

  @Test
  fun casa_ilSocketEIlWifi() = runTest {
    source.set(LinkFactsSamples.homeWifi())
    val gate = rig().gate

    val route = allowed(gate.current())

    assertThat(route.via).isEqualTo(Via.HOME_LAN)
    assertThat(route.binding.networkId).isEqualTo("wifi-home")
  }

  @Test
  fun casaConVpnNonControllabile_ilSocketELaVpn() = runTest {
    source.set(LinkFactsSamples.homeVpn(), LinkFactsSamples.homeWifi())
    val gate = rig().gate

    val route = allowed(gate.current())

    assertThat(route.via).isEqualTo(Via.HOME_LAN_THROUGH_VPN)
    assertThat(route.binding.networkId).isEqualTo("vpn-home")
  }

  @Test
  fun fuoriCasa_ilSocketELaVpn() = runTest {
    source.set(LinkFactsSamples.homeVpn(over = NetTransport.CELLULAR), LinkFactsSamples.cellular())
    val gate = rig().gate

    val route = allowed(gate.current())

    assertThat(route.via).isEqualTo(Via.VPN)
    assertThat(route.binding.networkId).isEqualTo("vpn-home")
  }

  @Test
  fun E_S10_unaReteChe_somigliaACasa_nonHaNessunSocket() = runTest {
    source.set(LinkFactsSamples.friendWifi())
    val gate = rig().gate

    assertThat(gate.current()).isEqualTo(NasRoute.Denied(DenyReason.UNTRUSTED_NETWORK))
    // Nemmeno un legame creato: il cancello non ha nessuna rete su cui aprire un socket.
    assertThat(bindings.created).isEmpty()
  }

  @Test
  fun E_S16_laVpnDiTerzi_nonHaNessunSocket() = runTest {
    source.set(LinkFactsSamples.foreignVpn(), LinkFactsSamples.cafeWifi())
    val gate = rig().gate

    assertThat(gate.current()).isEqualTo(NasRoute.Denied(DenyReason.FOREIGN_VPN))
    assertThat(bindings.created).isEmpty()
  }

  @Test
  fun ilPercorsoSegueLaReteSenzaAspettareLoScheduler() = runTest {
    source.set(LinkFactsSamples.homeWifi())
    val gate = rig().gate
    assertThat(gate.current()).isInstanceOf(NasRoute.Allowed::class.java)

    // Il telefono lascia casa: la rete cambia e `current()` lo vede subito, senza che nessuno abbia girato.
    source.set(LinkFactsSamples.cafeWifi())

    assertThat(gate.current()).isEqualTo(NasRoute.Denied(DenyReason.VPN_OFF_AWAY))
  }

  @Test
  fun lIstanzaDelPercorsoCambiaSoloQuandoCambiaLaSostanza() = runTest {
    source.set(LinkFactsSamples.homeWifi())
    val gate = rig().gate
    val first = gate.current()

    // Un aggiornamento che non cambia ne' la rete ne' la via (qui: il segnale diventa congestionato).
    source.set(LinkFactsSamples.homeWifi(congested = true))
    runCurrent()
    val second = gate.current()
    // Un'altra connessione Wi-Fi: un'altra Network, quindi un altro socket e un'altra istanza.
    source.set(LinkFactsSamples.homeWifi(id = "wifi-home-3"))
    val third = gate.current()

    assertThat(second).isSameInstanceAs(first)
    assertThat(third).isNotSameInstanceAs(first)
    assertThat(allowed(third).binding.networkId).isEqualTo("wifi-home-3")
  }

  @Test
  fun routes_siAggiornaQuandoLaReteCambia() = runTest {
    source.set(LinkFactsSamples.homeWifi())
    val gate = rig().gate
    runCurrent()
    assertThat(gate.routes.value).isInstanceOf(NasRoute.Allowed::class.java)

    source.set(LinkFactsSamples.cellular())
    runCurrent()

    assertThat(gate.routes.value).isEqualTo(NasRoute.Denied(DenyReason.VPN_OFF_AWAY))
  }

  @Test
  fun siAccendeLaVpnInCasa_ilLegameCambiaRete() = runTest {
    source.set(LinkFactsSamples.homeWifi())
    val gate = rig().gate
    val onLan = allowed(gate.current())

    source.set(LinkFactsSamples.homeVpn(), LinkFactsSamples.homeWifi())
    val throughVpn = allowed(gate.current())

    // core-nas confronta networkId e invalida la sessione.
    assertThat(onLan.binding.networkId).isNotEqualTo(throughVpn.binding.networkId)
    assertThat(throughVpn.via).isEqualTo(Via.HOME_LAN_THROUGH_VPN)
  }

  @Test
  fun laReteSparitaFraValutazioneEDLegame_nonDaPercorso() = runTest {
    source.set(LinkFactsSamples.homeWifi())
    bindings.missing += "wifi-home"
    val gate = rig().gate

    assertThat(gate.current()).isEqualTo(NasRoute.Denied(DenyReason.OFFLINE))
  }

  @Test
  fun senzaImpronta_ilCancelloEChiuso() = runTest {
    store.fingerprint.value = null
    source.set(LinkFactsSamples.homeWifi())
    val gate = rig().gate

    assertThat(gate.current()).isEqualTo(NasRoute.Denied(DenyReason.NOT_CONFIGURED))

    // L'impronta arriva (dopo l'onboarding): il cancello si apre.
    store.fingerprint.value = LinkFactsSamples.homeFingerprint()
    assertThat(gate.current()).isInstanceOf(NasRoute.Allowed::class.java)
  }

  @Test
  fun reteLocaleNegata_acasa() = runTest {
    source.set(LinkFactsSamples.homeWifi())
    permission.access = LocalNetworkAccess.DENIED
    val rig = rig()

    assertThat(rig.gate.current()).isEqualTo(NasRoute.Denied(DenyReason.LOCAL_NETWORK_DENIED))

    permission.access = LocalNetworkAccess.GRANTED
    rig.environment.onPermissionsChanged()
    assertThat(rig.gate.current()).isInstanceOf(NasRoute.Allowed::class.java)
  }

  // --- Onboarding ----------------------------------------------------------------------------------------

  private fun candidateOf(wifi: LinkFacts): HomeCandidate = HomeCandidates.from(wifi)!!

  @Test
  fun onboarding_ilWifiDaCuiELettoIlCandidato_ePercorsoDiCasaSenzaImpronta() = runTest {
    store.fingerprint.value = null
    val wifi = LinkFactsSamples.homeWifi()
    source.set(wifi)
    val gate = rig().gate

    val route = allowed(gate.onboardingRoute(candidateOf(wifi)))

    assertThat(route.via).isEqualTo(Via.HOME_LAN)
    assertThat(route.binding.networkId).isEqualTo("wifi-home")
  }

  @Test
  fun onboarding_senzaUla_ilPercorsoCeComunque_deciderelInterfaccia() = runTest {
    store.fingerprint.value = null
    val wifi = LinkFactsSamples.hotspotWifi()
    source.set(wifi)
    val gate = rig().gate
    val candidate = candidateOf(wifi)

    assertThat(candidate.canBeConfirmed).isFalse()
    assertThat(gate.onboardingRoute(candidate)).isInstanceOf(NasRoute.Allowed::class.java)
  }

  @Test
  fun onboarding_unCandidatoDiUnAltroWifi_enonValido() = runTest {
    val home = LinkFactsSamples.homeWifi()
    val other = LinkFactsSamples.friendWifi()
    source.set(other)
    val gate = rig().gate

    assertThat(gate.onboardingRoute(candidateOf(home))).isEqualTo(NasRoute.Denied(DenyReason.UNTRUSTED_NETWORK))
  }

  @Test
  fun onboarding_conLaVpnAccesa_chiedeDiSpegnerla() = runTest {
    val wifi = LinkFactsSamples.homeWifi()
    source.set(LinkFactsSamples.homeVpn(), wifi)
    val gate = rig().gate

    assertThat(gate.onboardingRoute(candidateOf(wifi))).isEqualTo(NasRoute.Denied(DenyReason.FOREIGN_VPN))
  }

  @Test
  fun onboarding_laReteCambiataDopoLaLettura_nonEPiuQuellaDelCandidato() = runTest {
    val wifi = LinkFactsSamples.homeWifi()
    val candidate = candidateOf(wifi)
    // Stessa Network, ma il router ha cambiato ULA nel frattempo.
    val changed = LinkFacts(wifi.networkId, wifi.transports, addresses = LinkFactsSamples.homeWifiAfterRouterReset().addresses, routes = wifi.routes, domains = wifi.domains)
    source.set(changed)
    val gate = rig().gate

    assertThat(gate.onboardingRoute(candidate)).isEqualTo(NasRoute.Denied(DenyReason.UNTRUSTED_NETWORK))
  }

  @Test
  fun onboarding_paginaDiAccessoEReteLocaleNegata_eNessunaRete() = runTest {
    val captive = LinkFactsSamples.cafeWifi(validated = false, captivePortal = true)
    source.set(captive)
    val gate = rig().gate
    assertThat(gate.onboardingRoute(candidateOf(captive))).isEqualTo(NasRoute.Denied(DenyReason.CAPTIVE_PORTAL))

    val wifi = LinkFactsSamples.homeWifi()
    source.set(wifi)
    permission.access = LocalNetworkAccess.DENIED
    assertThat(gate.onboardingRoute(candidateOf(wifi))).isEqualTo(NasRoute.Denied(DenyReason.LOCAL_NETWORK_DENIED))

    source.set()
    assertThat(gate.onboardingRoute(candidateOf(wifi))).isEqualTo(NasRoute.Denied(DenyReason.OFFLINE))
  }

  @Test
  fun lAmbienteDiceDoveSiTrova() = runTest {
    source.set(LinkFactsSamples.homeWifi())
    val env = rig().environment
    runCurrent()

    assertThat(env.snapshot.value.place).isEqualTo(NetPlace.HOME)
    assertThat(env.isAtHome.value).isTrue()
    assertThat(source.started).isTrue()

    source.set(LinkFactsSamples.cafeWifi())
    runCurrent()
    assertThat(env.isAtHome.value).isFalse()
    assertThat(env.snapshot.value.place).isEqualTo(NetPlace.VPN_OFF)
  }
}
