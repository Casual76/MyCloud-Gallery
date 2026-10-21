package dev.pampa.mycloudgallery.nas

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.imaging.contract.UnavailableReason
import dev.pampa.mycloudgallery.core.imaging.viewer.NasNetwork
import dev.pampa.mycloudgallery.core.imaging.viewer.blockedReason
import dev.pampa.mycloudgallery.core.network.contract.DenyReason
import dev.pampa.mycloudgallery.core.network.contract.NasRoute
import dev.pampa.mycloudgallery.core.network.contract.Via
import dev.pampa.mycloudgallery.core.network.testing.FakeNasRouteGate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * **D-15** (prove sul campo): il collegamento verso il NAS come lo vede il viewer. Fuori casa con la VPN spenta il NAS non si raggiunge (i dati mobili non
 * servono a niente), e quando la rete cambia il viewer deve saperlo: il percorso del cancello di rete e il consumo diventano una rete sola, con i
 * suoi cambiamenti.
 */
class AppNasLinkTest {

  private val gate = FakeNasRouteGate(NasRoute.Denied(DenyReason.VPN_OFF_AWAY))
  private var metered = true
  private val meteredFlow = MutableStateFlow(true)

  private fun link() = AppNasLink(gate, { metered }, meteredFlow)

  private fun setMetered(value: Boolean) {
    metered = value
    meteredFlow.value = value
  }

  @Test
  fun ogniMotivoDiPercorsoNegatoHaLaSuaRete() {
    val expected = mapOf(
      DenyReason.OFFLINE to NasNetwork.OFFLINE,
      DenyReason.VPN_OFF_AWAY to NasNetwork.VPN_OFF,
      DenyReason.CAPTIVE_PORTAL to NasNetwork.NOT_ALLOWED,
      DenyReason.UNTRUSTED_NETWORK to NasNetwork.NOT_ALLOWED,
      DenyReason.FOREIGN_VPN to NasNetwork.NOT_ALLOWED,
      DenyReason.LOCAL_NETWORK_DENIED to NasNetwork.NOT_ALLOWED,
      DenyReason.NOT_CONFIGURED to NasNetwork.NOT_ALLOWED,
    )
    // Un motivo nuovo senza una riga qui fa fallire il test: non resta senza una rete.
    assertThat(expected.keys).containsExactlyElementsIn(DenyReason.entries)
    for ((reason, network) in expected) {
      // Il consumo non conta quando il percorso e' negato: prima il percorso.
      assertThat(AppNasLink.networkOf(NasRoute.Denied(reason), metered = true)).isEqualTo(network)
      assertThat(AppNasLink.networkOf(NasRoute.Denied(reason), metered = false)).isEqualTo(network)
    }
  }

  @Test
  fun conIlPercorsoApertoSiDistingueIlConsumo() {
    gate.allow(Via.HOME_LAN)
    assertThat(AppNasLink.networkOf(gate.current(), metered = false)).isEqualTo(NasNetwork.OPEN)
    assertThat(AppNasLink.networkOf(gate.current(), metered = true)).isEqualTo(NasNetwork.METERED)
    gate.allow(Via.VPN)
    assertThat(AppNasLink.networkOf(gate.current(), metered = true)).isEqualTo(NasNetwork.METERED)
    gate.allow(Via.HOME_LAN_THROUGH_VPN)
    assertThat(AppNasLink.networkOf(gate.current(), metered = false)).isEqualTo(NasNetwork.OPEN)
  }

  /** Il caso del campo: Wi-Fi spento, VPN spenta, dati mobili accesi: il NAS non si raggiunge, e il motivo non e' "servono i dati mobili". */
  @Test
  fun senzaVpnFuoriCasaConIDatiMobiliLaReteDiceVpnSpenta() {
    val link = link()

    assertThat(link.isMetered()).isTrue()
    assertThat(link.network()).isEqualTo(NasNetwork.VPN_OFF)
    assertThat(link.blockedReason(confirmed = false)).isEqualTo(UnavailableReason.VPN_OFF_AWAY)
    assertThat(link.blockedReason(confirmed = true)).isEqualTo(UnavailableReason.VPN_OFF_AWAY)
  }

  @Test
  fun laReteDiAdessoSegueIlPercorsoEIlConsumo() {
    val link = link()
    gate.allow(Via.VPN)
    assertThat(link.network()).isEqualTo(NasNetwork.METERED)
    setMetered(false)
    assertThat(link.network()).isEqualTo(NasNetwork.OPEN)
    gate.deny(DenyReason.OFFLINE)
    assertThat(link.network()).isEqualTo(NasNetwork.OFFLINE)
  }

  /** I cambiamenti cominciano dalla rete di adesso e poi seguono il percorso e il consumo, uno per cambiamento vero. */
  @Test
  fun iCambiamentiPartonoDallaReteDiAdessoESeguonoPercorsoEConsumo() = runTest {
    val link = link()

    link.changes.test {
      assertThat(awaitItem()).isEqualTo(NasNetwork.VPN_OFF)
      // La VPN si accende, sui dati mobili.
      gate.allow(Via.VPN)
      assertThat(awaitItem()).isEqualTo(NasNetwork.METERED)
      // Un nuovo percorso consentito con lo stesso esito (un'altra rete, lo stesso consumo) non e' un cambiamento per chi guarda.
      gate.allow(Via.VPN)
      // Torna il Wi-Fi di casa: libero.
      gate.allow(Via.HOME_LAN)
      setMetered(false)
      assertThat(awaitItem()).isEqualTo(NasNetwork.OPEN)
      // Il Wi-Fi cade e non c'e' nessuna rete.
      gate.deny(DenyReason.OFFLINE)
      assertThat(awaitItem()).isEqualTo(NasNetwork.OFFLINE)
      expectNoEvents()
    }
  }
}
