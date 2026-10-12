package dev.pampa.mycloudgallery.core.network.policy

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.network.contract.HomeFingerprint
import dev.pampa.mycloudgallery.core.network.contract.NetTransport
import dev.pampa.mycloudgallery.core.network.contract.VpnObservation
import dev.pampa.mycloudgallery.core.network.link.LinkAddr
import dev.pampa.mycloudgallery.core.network.link.LinkFacts
import dev.pampa.mycloudgallery.core.network.link.LinkRoute
import dev.pampa.mycloudgallery.core.network.testing.LinkFactsSamples
import dev.pampa.mycloudgallery.core.network.testing.LinkFactsSamples.ip
import dev.pampa.mycloudgallery.core.network.testing.LinkFactsSamples.prefix
import org.junit.Test

/** D2 / E-S16: non basta una VPN qualsiasi, deve essere quella di casa. */
class VpnClassifierTest {

  private val fingerprint = LinkFactsSamples.homeFingerprint()

  private fun classify(vpn: LinkFacts?, fp: HomeFingerprint? = fingerprint) = VpnClassifier.classify(vpn, fp)

  @Test
  fun senzaVpn_nessuna() {
    assertThat(classify(null)).isEqualTo(VpnObservation.NONE)
    assertThat(classify(null, null)).isEqualTo(VpnObservation.NONE)
  }

  @Test
  fun lVpnDiCasaConLUlaFraLeRotte_eDiCasa() {
    val verdict = VpnClassifier.verdict(LinkFactsSamples.homeVpn(), fingerprint)

    assertThat(verdict.observation).isEqualTo(VpnObservation.HOME_TUNNEL)
    assertThat(verdict.clues).containsAtLeast(VpnClue.ULA_ROUTE, VpnClue.ULA_ADDRESS)
  }

  @Test
  fun bastaLUlaNegliIndirizziOnei_DNS_ancheSenzaLeRotte() {
    val onlyAddress = LinkFacts("v", setOf(NetTransport.CELLULAR), isVpn = true, addresses = listOf(LinkAddr(ip("fd12:3456:789a:f::2"), 128)))
    val onlyDns = LinkFacts("v", setOf(NetTransport.CELLULAR), isVpn = true, dnsServers = listOf(ip("fd12:3456:789a::1")))

    assertThat(classify(onlyAddress)).isEqualTo(VpnObservation.HOME_TUNNEL)
    assertThat(classify(onlyDns)).isEqualTo(VpnObservation.HOME_TUNNEL)
  }

  @Test
  fun laConfigurazioneDiFabbricaDelFritzBox_rottaPredefinitaDnsEDominio_eDiCasa() {
    val verdict = VpnClassifier.verdict(LinkFactsSamples.homeVpnFullTunnel(), fingerprint)

    // Due indizi deboli diversi (DNS = gateway di casa, dominio): bastano. La rotta predefinita non conta mai.
    assertThat(verdict.observation).isEqualTo(VpnObservation.HOME_TUNNEL)
    assertThat(verdict.clues).containsExactly(VpnClue.GATEWAY_DNS, VpnClue.DOMAIN)
  }

  @Test
  fun unaVpnCommerciale_conRottaPredefinitaEDnsSuoi_eEstranea_E_S16() {
    val verdict = VpnClassifier.verdict(LinkFactsSamples.foreignVpn(), fingerprint)

    assertThat(verdict.observation).isEqualTo(VpnObservation.FOREIGN_VPN)
    assertThat(verdict.clues).isEmpty()
  }

  @Test
  fun ilTunnelVersoIlFritzBoxDiUnAmico_portaUnAltroUla_eEstraneo() {
    // Stessa sottorete, stesso DNS, stesso dominio: gli indizi deboli sarebbero tre. Ma l'ULA e' un altro.
    val verdict = VpnClassifier.verdict(LinkFactsSamples.friendVpn(), fingerprint)

    assertThat(verdict.observation).isEqualTo(VpnObservation.FOREIGN_VPN)
    assertThat(verdict.foreignUla).isTrue()
  }

  @Test
  fun unSoloIndizioDebole_nonBasta() {
    val onlyDomain = LinkFacts("v", setOf(NetTransport.WIFI), isVpn = true, routes = listOf(LinkRoute(prefix("0.0.0.0/0"), null)), domains = listOf("fritz.box"))
    val onlyDns = LinkFacts("v", setOf(NetTransport.WIFI), isVpn = true, dnsServers = listOf(ip("192.168.178.1")))
    val onlyRoute = LinkFacts("v", setOf(NetTransport.WIFI), isVpn = true, routes = listOf(LinkRoute(prefix("192.168.178.0/24"), null)))

    assertThat(classify(onlyDomain)).isEqualTo(VpnObservation.FOREIGN_VPN)
    assertThat(classify(onlyDns)).isEqualTo(VpnObservation.FOREIGN_VPN)
    assertThat(classify(onlyRoute)).isEqualTo(VpnObservation.FOREIGN_VPN)
  }

  @Test
  fun leRotteLargheNonDiconoDovePortaIlTunnel() {
    // Il trucco delle due meta' di Internet (0.0.0.0/1 e 128.0.0.0/1) contiene il gateway di casa ma non e' un indizio.
    val splitDefault = LinkFacts(
      "v",
      setOf(NetTransport.WIFI),
      isVpn = true,
      routes = listOf(LinkRoute(prefix("0.0.0.0/1"), null), LinkRoute(prefix("128.0.0.0/1"), null), LinkRoute(prefix("10.0.0.0/8"), null)),
      dnsServers = listOf(ip("192.168.178.1")),
    )

    val verdict = VpnClassifier.verdict(splitDefault, fingerprint)

    assertThat(verdict.clues).containsExactly(VpnClue.GATEWAY_DNS)
    assertThat(verdict.observation).isEqualTo(VpnObservation.FOREIGN_VPN)
  }

  @Test
  fun rotteESottoreteDiCasaPiuDominio_sonoDueIndiziDeboli() {
    val ipv4Only = LinkFacts(
      "v",
      setOf(NetTransport.WIFI),
      isVpn = true,
      routes = listOf(LinkRoute(prefix("192.168.178.0/24"), null)),
      domains = listOf("fritz.box"),
    )

    // Solo IPv4 nelle AllowedIPs (senza ULA): vale come casa per classificazione, la conferma finale e' il GUID.
    assertThat(classify(ipv4Only)).isEqualTo(VpnObservation.HOME_TUNNEL)
  }

  @Test
  fun senzaImprontaNonSiSaDiChiESiaLaVpn() {
    assertThat(classify(LinkFactsSamples.homeVpn(), null)).isEqualTo(VpnObservation.UNKNOWN)
    // Un'impronta senza ULA valido (non confrontabile) e' come nessuna impronta.
    assertThat(classify(LinkFactsSamples.homeVpn(), HomeFingerprint(emptySet(), "192.168.178.1", "fritz.box", 1L))).isEqualTo(VpnObservation.UNKNOWN)
  }

  @Test
  fun unUlaDiCasaNelleRotteVinceAncheSeIlTunnelPortaAnchePiuUla() {
    val both = LinkFacts(
      "v",
      setOf(NetTransport.WIFI),
      isVpn = true,
      routes = listOf(LinkRoute(prefix("fd12:3456:789a::/48"), null), LinkRoute(prefix("fd5e:21c7:b0a4::/48"), null)),
    )

    assertThat(classify(both)).isEqualTo(VpnObservation.HOME_TUNNEL)
  }
}
