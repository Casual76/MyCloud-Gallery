package dev.pampa.mycloudgallery.core.network.home

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.network.contract.HomeClue
import dev.pampa.mycloudgallery.core.network.contract.HomeFingerprint
import dev.pampa.mycloudgallery.core.network.contract.HomeMatch
import dev.pampa.mycloudgallery.core.network.contract.NetTransport
import dev.pampa.mycloudgallery.core.network.link.LinkFacts
import dev.pampa.mycloudgallery.core.network.testing.LinkFactsSamples
import org.junit.Test

/** "Casa" si riconosce dall'ULA, e solo da quello. Gli altri indizi dicono al massimo "somiglia a casa". */
class HomeMatcherTest {

  private val fingerprint = LinkFactsSamples.homeFingerprint()

  @Test
  fun ilWifiDiCasa_ecasa_perLUla() {
    assertThat(HomeMatcher.match(LinkFactsSamples.homeWifi(), fingerprint)).isEqualTo(HomeMatch.Match)
  }

  @Test
  fun casaSiRiconosceAncheConUnaSolaDelleDueUlaTemporanee() {
    val onlyTemporary = LinkFactsSamples.homeWifi().let {
      LinkFacts(it.networkId, it.transports, addresses = it.addresses.filter { a -> !a.ip.isUla || a.ip.toText().contains("552c") })
    }
    assertThat(HomeMatcher.match(onlyTemporary, fingerprint)).isEqualTo(HomeMatch.Match)
  }

  @Test
  fun ilFritzBoxDiUnAmico_stessaSottoreteStessoDominio_UlaDiverso_nonECasa_E_S10() {
    val result = HomeMatcher.match(LinkFactsSamples.friendWifi(), fingerprint)

    assertThat(result).isNotEqualTo(HomeMatch.Match)
    // Tutti e tre gli indizi deboli coincidono: e' proprio il caso in cui "gateway e dominio" ingannano.
    assertThat(result).isEqualTo(HomeMatch.Weak(setOf(HomeClue.GATEWAY_V4, HomeClue.IPV4_SUBNET, HomeClue.DNS_DOMAIN)))
  }

  @Test
  fun casaConUlaCambiato_dopoIlResetDelRouter_ecasaSoloDebole_E_R16() {
    val result = HomeMatcher.match(LinkFactsSamples.homeWifiAfterRouterReset(), fingerprint)

    assertThat(result).isInstanceOf(HomeMatch.Weak::class.java)
  }

  @Test
  fun unaReteSenzaUla_conIndiziDeboliIdentici_nonECasa() {
    val noIpv6 = LinkFacts(
      "wifi-x",
      setOf(NetTransport.WIFI),
      addresses = LinkFactsSamples.homeWifi().addresses.filter { it.ip.isV4 },
      routes = LinkFactsSamples.homeWifi().routes,
      domains = listOf("fritz.box"),
    )

    assertThat(HomeMatcher.match(noIpv6, fingerprint)).isInstanceOf(HomeMatch.Weak::class.java)
  }

  @Test
  fun unWifiQualunque_nonSomigliaACasa() {
    assertThat(HomeMatcher.match(LinkFactsSamples.cafeWifi(), fingerprint)).isEqualTo(HomeMatch.NoMatch)
    assertThat(HomeMatcher.match(LinkFactsSamples.hotspotWifi(), fingerprint)).isEqualTo(HomeMatch.NoMatch)
  }

  @Test
  fun senzaImprontaOSenzaWifi_ilConfrontoENonRiuscito() {
    assertThat(HomeMatcher.match(LinkFactsSamples.homeWifi(), null)).isEqualTo(HomeMatch.Unknown)
    assertThat(HomeMatcher.match(null, fingerprint)).isEqualTo(HomeMatch.Unknown)
    // Il cellulare e una VPN non sono casa e non si confrontano.
    assertThat(HomeMatcher.match(LinkFactsSamples.cellular(), fingerprint)).isEqualTo(HomeMatch.Unknown)
    assertThat(HomeMatcher.match(LinkFactsSamples.homeVpn(), fingerprint)).isEqualTo(HomeMatch.Unknown)
  }

  @Test
  fun unPrefissoGlobaleNellImprontaNonConta_soloUnUlaValido() {
    val globalOnly = HomeFingerprint(setOf("2001:db8:abcd::/48"), "192.168.178.1", "fritz.box", 1L)

    // La rete ha davvero quell'indirizzo globale, ma non e' un ULA: non vale come impronta forte.
    assertThat(HomeMatcher.match(LinkFactsSamples.homeWifi(), globalOnly)).isInstanceOf(HomeMatch.Weak::class.java)
  }

  @Test
  fun unPrefissoTroppoLargoNellImprontaNonAllargaIlRiconoscimento() {
    // Una configurazione sbagliata (o manomessa) con fc00::/7 combacerebbe con qualunque rete locale unica.
    val tooBroad = HomeFingerprint(setOf("fc00::/7", "fd00::/8", "fd12::/16"), "192.168.178.1", "fritz.box", 1L)

    assertThat(HomeMatcher.match(LinkFactsSamples.friendWifi(), tooBroad)).isInstanceOf(HomeMatch.Weak::class.java)
    assertThat(HomeMatcher.match(LinkFactsSamples.homeWifi(), tooBroad)).isInstanceOf(HomeMatch.Weak::class.java)
  }

  @Test
  fun unPrefissoMalFormatoSiScartaSenzaFarCadere() {
    val garbage = HomeFingerprint(setOf("non-un-prefisso", "fd12:3456:789a::/48", "fd12::/999"), null, null, 1L)

    assertThat(HomeMatcher.match(LinkFactsSamples.homeWifi(), garbage)).isEqualTo(HomeMatch.Match)
  }

  @Test
  fun unUlaVicinoMaDiversoNonCoincide() {
    // Cambia un solo bit nel quarto gruppo di bit del /48.
    val almost = HomeFingerprint(setOf("fd12:3456:789b::/48"), "192.168.178.1", "fritz.box", 1L)

    assertThat(HomeMatcher.match(LinkFactsSamples.homeWifi(), almost)).isInstanceOf(HomeMatch.Weak::class.java)
  }

  @Test
  fun ilDominioSiConfrontaSenzaMaiuscoleNeSenzaPuntoFinale() {
    val upper = HomeFingerprint(setOf(LinkFactsSamples.HOME_ULA), null, "FRITZ.BOX.", 1L)

    assertThat(HomeMatcher.weakClues(LinkFactsSamples.friendWifi(), upper)).containsExactly(HomeClue.DNS_DOMAIN)
  }

  // --- Candidati e impronte --------------------------------------------------------------------------

  @Test
  fun ilCandidatoRaccogliUlaGatewayDominioESottorete() {
    val candidate = HomeCandidates.from(LinkFactsSamples.homeWifi())!!

    assertThat(candidate.networkId).isEqualTo("wifi-home")
    assertThat(candidate.ulaPrefixes).containsExactly(LinkFactsSamples.HOME_ULA)
    assertThat(candidate.gatewayV4).isEqualTo("192.168.178.1")
    assertThat(candidate.dnsDomain).isEqualTo("fritz.box")
    assertThat(candidate.ipv4Subnet).isEqualTo("192.168.178.0/24")
    assertThat(candidate.canBeConfirmed).isTrue()
  }

  @Test
  fun unaReteSenzaUla_dauncandidatoChePerNonSiConferma() {
    val noUla = LinkFactsSamples.hotspotWifi()
    val candidate = HomeCandidates.from(noUla)!!

    assertThat(candidate.canBeConfirmed).isFalse()
    assertThat(HomeFingerprints.fromCandidate(candidate, 5L)).isNull()
  }

  @Test
  fun ilCellulareEUnaVpnNonSonoCandidatiACasa() {
    assertThat(HomeCandidates.from(LinkFactsSamples.cellular())).isNull()
    assertThat(HomeCandidates.from(LinkFactsSamples.homeVpn())).isNull()
  }

  @Test
  fun ilCandidatoRiconfermatoDiventaUnaImprontaCheLoRiconosce() {
    val candidate = HomeCandidates.from(LinkFactsSamples.homeWifi())!!
    val saved = HomeFingerprints.fromCandidate(candidate, capturedAt = 42L)!!

    assertThat(saved.capturedAt).isEqualTo(42L)
    assertThat(HomeMatcher.match(LinkFactsSamples.homeWifi(), saved)).isEqualTo(HomeMatch.Match)
    assertThat(HomeMatcher.match(LinkFactsSamples.friendWifi(), saved)).isInstanceOf(HomeMatch.Weak::class.java)
  }

  @Test
  fun alMassimoQuattroUlaNelCandidato() {
    val many = LinkFacts(
      "wifi-many",
      setOf(NetTransport.WIFI),
      addresses = (1..6).map { LinkFactsSamples.ip("fd0$it:0:0:0::1").let { ip -> dev.pampa.mycloudgallery.core.network.link.LinkAddr(ip, 64) } },
    )

    assertThat(HomeCandidates.from(many)!!.ulaPrefixes).hasSize(HomeCandidates.MAX_ULA)
  }
}
