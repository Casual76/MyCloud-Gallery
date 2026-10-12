package dev.pampa.mycloudgallery.core.network.policy

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.network.contract.DenyReason
import dev.pampa.mycloudgallery.core.network.contract.HomeClue
import dev.pampa.mycloudgallery.core.network.contract.HomeFingerprint
import dev.pampa.mycloudgallery.core.network.contract.HomeMatch
import dev.pampa.mycloudgallery.core.network.contract.LocalNetworkAccess
import dev.pampa.mycloudgallery.core.network.contract.NetPlace
import dev.pampa.mycloudgallery.core.network.contract.NetTransport
import dev.pampa.mycloudgallery.core.network.contract.Via
import dev.pampa.mycloudgallery.core.network.contract.VpnObservation
import dev.pampa.mycloudgallery.core.network.home.HomeCandidates
import dev.pampa.mycloudgallery.core.network.home.HomeFingerprints
import dev.pampa.mycloudgallery.core.network.link.LinkFacts
import dev.pampa.mycloudgallery.core.network.link.NetworkObservation
import dev.pampa.mycloudgallery.core.network.testing.LinkFactsSamples
import dev.pampa.mycloudgallery.core.rules.NetworkClass
import dev.pampa.mycloudgallery.core.rules.NetworkClassifier
import org.junit.Test

/**
 * La politica di rete, a tabella: ogni riga e' una fotografia sintetica di Android (`LinkFacts`) e cio' che deve
 * uscirne. Nessun Android, nessun tempo.
 */
class NetworkPolicyTest {

  private val fingerprint = LinkFactsSamples.homeFingerprint()

  private fun eval(
    vararg networks: LinkFacts,
    default: String? = networks.firstOrNull()?.networkId,
    fp: HomeFingerprint? = fingerprint,
    local: LocalNetworkAccess = LocalNetworkAccess.GRANTED_IMPLICIT,
  ) = NetworkPolicy.evaluate(NetworkObservation(default, networks.toList()), fp, local, at = 7L)

  private fun allowed(via: Via, networkId: String) = PathDecision.Allowed(via, networkId)

  private fun denied(reason: DenyReason) = PathDecision.Denied(reason)

  private class Row(
    val name: String,
    val evaluation: NetworkEvaluation,
    val place: NetPlace,
    val decision: PathDecision,
    val vpn: VpnObservation = VpnObservation.NONE,
  )

  @Test
  fun tabella_luogoEPercorso() {
    val w = LinkFactsSamples
    val rows = listOf(
      Row("casa", eval(w.homeWifi()), NetPlace.HOME, allowed(Via.HOME_LAN, "wifi-home")),
      Row("casa su Ethernet", eval(ethernet(w.homeWifi())), NetPlace.HOME, allowed(Via.HOME_LAN, "wifi-home")),
      Row("casa, rete marcata a consumo", eval(w.homeWifi(metered = true)), NetPlace.HOME, allowed(Via.HOME_LAN, "wifi-home")),
      Row(
        "casa + VPN di casa accesa (e' il tunnel a catturare il traffico)",
        eval(w.homeVpn(), w.homeWifi()),
        NetPlace.HOME,
        allowed(Via.HOME_LAN_THROUGH_VPN, "vpn-home"),
        VpnObservation.HOME_TUNNEL,
      ),
      Row("E-S10: il FRITZ!Box di un amico, stessa sottorete e ULA diverso", eval(w.friendWifi()), NetPlace.UNTRUSTED, denied(DenyReason.UNTRUSTED_NETWORK)),
      Row("un bar, VPN spenta", eval(w.cafeWifi()), NetPlace.VPN_OFF, denied(DenyReason.VPN_OFF_AWAY)),
      Row("dati mobili, VPN spenta", eval(w.cellular()), NetPlace.VPN_OFF, denied(DenyReason.VPN_OFF_AWAY)),
      Row(
        "da un amico con la VPN di casa: il tunnel poggia sul Wi-Fi",
        eval(w.homeVpn(), w.friendWifi()),
        NetPlace.AWAY_VPN_WIFI,
        allowed(Via.VPN, "vpn-home"),
        VpnObservation.HOME_TUNNEL,
      ),
      Row(
        "dati mobili con la VPN di casa",
        eval(w.homeVpn(over = NetTransport.CELLULAR), w.cellular()),
        NetPlace.AWAY_VPN_MOBILE,
        allowed(Via.VPN, "vpn-home"),
        VpnObservation.HOME_TUNNEL,
      ),
      Row(
        "E-R13: hotspot a consumo con la VPN di casa vale come dati mobili",
        eval(w.homeVpn(metered = true), w.hotspotWifi()),
        NetPlace.AWAY_VPN_MOBILE,
        allowed(Via.VPN, "vpn-home"),
        VpnObservation.HOME_TUNNEL,
      ),
      Row("E-R13: hotspot a consumo senza VPN", eval(w.hotspotWifi()), NetPlace.VPN_OFF, denied(DenyReason.VPN_OFF_AWAY)),
      Row("pagina di accesso (captive portal)", eval(w.cafeWifi(validated = false, captivePortal = true)), NetPlace.OFFLINE, denied(DenyReason.CAPTIVE_PORTAL)),
      Row("un Wi-Fi senza internet fuori casa", eval(w.cafeWifi(validated = false)), NetPlace.OFFLINE, denied(DenyReason.OFFLINE)),
      Row("nessuna rete", eval(), NetPlace.OFFLINE, denied(DenyReason.OFFLINE)),
      Row(
        "E-R11/E-R17: solo IPv4 con la VPN di casa sul cellulare",
        eval(w.homeVpnFullTunnel(), w.cellular(ipv6 = false)),
        NetPlace.AWAY_VPN_MOBILE,
        allowed(Via.VPN, "vpn-home-full"),
        VpnObservation.HOME_TUNNEL,
      ),
      Row(
        "E-S16: VPN di terzi fuori casa",
        eval(w.foreignVpn(), w.cafeWifi()),
        NetPlace.UNTRUSTED,
        denied(DenyReason.FOREIGN_VPN),
        VpnObservation.FOREIGN_VPN,
      ),
      Row(
        "E-S16: VPN di terzi anche a casa (cattura il traffico dell'app)",
        eval(w.foreignVpn(), w.homeWifi()),
        NetPlace.UNTRUSTED,
        denied(DenyReason.FOREIGN_VPN),
        VpnObservation.FOREIGN_VPN,
      ),
      Row(
        "E-S16: il tunnel verso il FRITZ!Box di un amico non e' la VPN di casa",
        eval(w.friendVpn(), w.cafeWifi()),
        NetPlace.UNTRUSTED,
        denied(DenyReason.FOREIGN_VPN),
        VpnObservation.FOREIGN_VPN,
      ),
      Row("E-R16: casa dopo il reset del router (ULA nuovo)", eval(w.homeWifiAfterRouterReset()), NetPlace.UNTRUSTED, denied(DenyReason.UNTRUSTED_NETWORK)),
      Row(
        "roaming con la VPN di casa",
        eval(w.homeVpnFullTunnel(), w.cellular(roaming = true)),
        NetPlace.ROAMING,
        allowed(Via.VPN, "vpn-home-full"),
        VpnObservation.HOME_TUNNEL,
      ),
      Row("roaming senza VPN", eval(w.cellular(roaming = true)), NetPlace.VPN_OFF, denied(DenyReason.VPN_OFF_AWAY)),
      Row("casa senza internet (router senza WAN): il NAS e' in LAN", eval(w.homeWifi(validated = false)), NetPlace.HOME, allowed(Via.HOME_LAN, "wifi-home")),
      Row(
        "la VPN poggia su un Wi-Fi senza internet: il tunnel non porta da nessuna parte",
        eval(w.homeVpn(), w.cafeWifi(validated = false)),
        NetPlace.OFFLINE,
        denied(DenyReason.OFFLINE),
        VpnObservation.HOME_TUNNEL,
      ),
    )

    val failures = rows.mapNotNull { row ->
      val actual = row.evaluation
      val problems = buildList {
        if (actual.snapshot.place != row.place) add("luogo ${actual.snapshot.place}, atteso ${row.place}")
        if (actual.decision != row.decision) add("percorso ${actual.decision}, atteso ${row.decision}")
        if (actual.snapshot.vpn != row.vpn) add("vpn ${actual.snapshot.vpn}, attesa ${row.vpn}")
      }
      if (problems.isEmpty()) null else "${row.name}: ${problems.joinToString("; ")}"
    }
    assertWithMessage(failures.joinToString("\n")).that(failures).isEmpty()
  }

  /** Lo stesso Wi-Fi visto come Ethernet. */
  private fun ethernet(wifi: LinkFacts) =
    LinkFacts(wifi.networkId, setOf(NetTransport.ETHERNET), addresses = wifi.addresses, routes = wifi.routes, dnsServers = wifi.dnsServers, domains = wifi.domains)

  // --- Casi che meritano un'asserzione in piu' -----------------------------------------------------------

  @Test
  fun E_S10_ilFritzBoxDiUnAmico_nonDaAlcunPercorso_eDiceCheSomigliaACasa() {
    val result = eval(LinkFactsSamples.friendWifi())

    assertThat(result.decision).isInstanceOf(PathDecision.Denied::class.java)
    assertThat(result.snapshot.home).isInstanceOf(HomeMatch.Weak::class.java)
    assertThat(result.weakClues).containsExactly(HomeClue.GATEWAY_V4, HomeClue.IPV4_SUBNET, HomeClue.DNS_DOMAIN)
  }

  @Test
  fun E_S10_ilGatewayELaSottoreteDaSoliNonBastano_neppureConLaVpnSpenta() {
    // Una rete con lo stesso IPv4 e lo stesso gateway ma senza dominio e senza ULA.
    val lookAlike = LinkFacts(
      "wifi-lookalike",
      setOf(NetTransport.WIFI),
      addresses = LinkFactsSamples.friendWifi().addresses.filter { it.ip.isV4 },
      routes = LinkFactsSamples.friendWifi().routes,
    )

    val result = eval(lookAlike)

    assertThat(result.decision).isEqualTo(denied(DenyReason.UNTRUSTED_NETWORK))
  }

  @Test
  fun E_R12_dueReti_ilWifiDiCasaSenzaInternetEIlCellularePredefinito_ilSocketVaSulWifi() {
    // Android usa i dati mobili perche' il Wi-Fi non ha internet; ma il NAS e' in casa: il socket e' legato al Wi-Fi.
    val result = eval(LinkFactsSamples.cellular(), LinkFactsSamples.homeWifi(validated = false), default = "cell-1")

    assertThat(result.snapshot.place).isEqualTo(NetPlace.HOME)
    assertThat(result.decision).isEqualTo(allowed(Via.HOME_LAN, "wifi-home"))
    assertThat(result.snapshot.underlying?.transport).isEqualTo(NetTransport.WIFI)
    // Le regole seguono la rete del socket, non quella del sistema: nessun dato mobile e' in gioco.
    val state = NetworkPolicy.toNetworkState(result.snapshot)
    assertThat(state.isHome).isTrue()
    assertThat(state.validated).isTrue()
    assertThat(NetworkClassifier.classify(state)).isEqualTo(NetworkClass.HOME_WIFI)
  }

  @Test
  fun E_R12_dueReti_unBarSenzaInternetEIlCellulareConLaVpn_contaIlCellulare() {
    val result = eval(
      LinkFactsSamples.homeVpn(over = NetTransport.CELLULAR, metered = true),
      LinkFactsSamples.cellular(),
      LinkFactsSamples.cafeWifi(validated = false),
    )

    assertThat(result.snapshot.place).isEqualTo(NetPlace.AWAY_VPN_MOBILE)
    assertThat(result.snapshot.underlying?.transport).isEqualTo(NetTransport.CELLULAR)
    assertThat(NetworkClassifier.classify(NetworkPolicy.toNetworkState(result.snapshot))).isEqualTo(NetworkClass.MOBILE)
  }

  @Test
  fun E_R12_conDueWifi_siSceglieQuelloDiCasa() {
    // L'amico e' il predefinito di Android, ma sul telefono c'e' anche il Wi-Fi di casa (es. due radio).
    val result = eval(LinkFactsSamples.friendWifi(), LinkFactsSamples.homeWifi(), default = "wifi-friend")

    assertThat(result.snapshot.place).isEqualTo(NetPlace.HOME)
    assertThat(result.decision).isEqualTo(allowed(Via.HOME_LAN, "wifi-home"))
  }

  @Test
  fun E_R13_hotspotAConsumo_leRegoleLoVedonoComeDatiMobili() {
    val result = eval(LinkFactsSamples.homeVpn(metered = true), LinkFactsSamples.hotspotWifi())

    assertThat(NetworkClassifier.classify(NetworkPolicy.toNetworkState(result.snapshot))).isEqualTo(NetworkClass.MOBILE)
  }

  @Test
  fun E_R13_unWifiDiCasaMarcatoAConsumoRestaCasa() {
    val result = eval(LinkFactsSamples.homeWifi(metered = true))

    assertThat(NetworkClassifier.classify(NetworkPolicy.toNetworkState(result.snapshot))).isEqualTo(NetworkClass.HOME_WIFI)
  }

  @Test
  fun E_R13_laPaginaDiAccesso_leRegoleVedonoOffline() {
    val result = eval(LinkFactsSamples.cafeWifi(validated = false, captivePortal = true))

    assertThat(NetworkClassifier.classify(NetworkPolicy.toNetworkState(result.snapshot))).isEqualTo(NetworkClass.OFFLINE)
  }

  @Test
  fun E_R11_E_R17_soloIpv4ConLaVpn_lIpv6GlobaleManca() {
    val ipv4Only = eval(LinkFactsSamples.homeVpnFullTunnel(), LinkFactsSamples.cellular(ipv6 = false))
    val dual = eval(LinkFactsSamples.homeVpnFullTunnel(), LinkFactsSamples.cellular(ipv6 = true))
    val ipv4OnlyOff = eval(LinkFactsSamples.cafeWifi().let { LinkFacts("w", it.transports, addresses = it.addresses.filter { a -> a.ip.isV4 }) })

    assertThat(ipv4Only.snapshot.ipv6Global).isFalse()
    assertThat(dual.snapshot.ipv6Global).isTrue()
    assertThat(ipv4OnlyOff.snapshot.ipv6Global).isFalse()
    assertThat(ipv4OnlyOff.snapshot.place).isEqualTo(NetPlace.VPN_OFF)
  }

  @Test
  fun E_S16_laVpnDiTerzi_nellaNetworkStateNonEUnaVpnDiCasa() {
    val result = eval(LinkFactsSamples.foreignVpn(), LinkFactsSamples.cafeWifi())
    val state = NetworkPolicy.toNetworkState(result.snapshot)

    // Per le regole la rete non e' riconosciuta: nessun lavoro verso il NAS passa da qui.
    assertThat(state.viaVpn).isFalse()
    assertThat(NetworkClassifier.classify(state)).isEqualTo(NetworkClass.UNTRUSTED)
  }

  @Test
  fun E_R16_dopoLaNuovaRaccoltaConfermata_casaTornaRiconosciuta() {
    val reset = LinkFactsSamples.homeWifiAfterRouterReset()
    assertThat(eval(reset).decision).isEqualTo(denied(DenyReason.UNTRUSTED_NETWORK))

    // L'utente conferma la nuova raccolta (e il GUID e' lo stesso: lo verifica core-nas).
    val newFingerprint = HomeFingerprints.fromCandidate(HomeCandidates.from(reset)!!, capturedAt = 99L)!!
    val after = eval(reset, fp = newFingerprint)

    assertThat(after.snapshot.place).isEqualTo(NetPlace.HOME)
    assertThat(after.decision).isEqualTo(allowed(Via.HOME_LAN, "wifi-home-2"))
  }

  @Test
  fun senzaImpronta_nienteSiContatta_neSullaVpn() {
    assertThat(eval(LinkFactsSamples.homeWifi(), fp = null).decision).isEqualTo(denied(DenyReason.NOT_CONFIGURED))
    val withVpn = eval(LinkFactsSamples.homeVpn(), LinkFactsSamples.cafeWifi(), fp = null)
    assertThat(withVpn.decision).isEqualTo(denied(DenyReason.NOT_CONFIGURED))
    assertThat(withVpn.snapshot.vpn).isEqualTo(VpnObservation.UNKNOWN)
  }

  @Test
  fun reteLocaleNegata_acasaNonSiContattaIlNas_maLaVpnFuoriCasaSi() {
    val home = eval(LinkFactsSamples.homeWifi(), local = LocalNetworkAccess.DENIED)
    assertThat(home.snapshot.place).isEqualTo(NetPlace.HOME)
    assertThat(home.decision).isEqualTo(denied(DenyReason.LOCAL_NETWORK_DENIED))
    assertThat(home.snapshot.localNetwork).isEqualTo(LocalNetworkAccess.DENIED)

    // Se un indirizzo privato dentro il tunnel conti come "locale" non e' documentato (T-19): non si nega in anticipo.
    val away = eval(LinkFactsSamples.homeVpn(over = NetTransport.CELLULAR), LinkFactsSamples.cellular(), local = LocalNetworkAccess.DENIED)
    assertThat(away.decision).isEqualTo(allowed(Via.VPN, "vpn-home"))
  }

  @Test
  fun siSceglieLaVpnPredefinita_oQuellaDiCasa() {
    val a = LinkFactsSamples.foreignVpn(id = "vpn-a")
    val b = LinkFactsSamples.homeVpn(id = "vpn-b")

    val defaultForeign = eval(a, b, LinkFactsSamples.cafeWifi(), default = "vpn-a")
    val noDefault = eval(a, b, LinkFactsSamples.cafeWifi(), default = null)

    assertThat(defaultForeign.vpnNetworkId).isEqualTo("vpn-a")
    assertThat(defaultForeign.decision).isEqualTo(denied(DenyReason.FOREIGN_VPN))
    assertThat(noDefault.vpnNetworkId).isEqualTo("vpn-b")
    assertThat(noDefault.snapshot.vpn).isEqualTo(VpnObservation.HOME_TUNNEL)
  }

  @Test
  fun laVpnCheNonDichiaraITrasporti_poggiaSullaReteMigliore() {
    val noTransports = LinkFacts("vpn-x", emptySet(), isVpn = true, addresses = LinkFactsSamples.homeVpn().addresses, routes = LinkFactsSamples.homeVpn().routes)

    val onWifi = eval(noTransports, LinkFactsSamples.friendWifi())
    val onCellular = eval(noTransports, LinkFactsSamples.cellular())
    val alone = eval(noTransports)

    assertThat(onWifi.snapshot.underlying?.transport).isEqualTo(NetTransport.WIFI)
    assertThat(onCellular.snapshot.underlying?.transport).isEqualTo(NetTransport.CELLULAR)
    // Senza nessuna rete sotto, la VPN sola: trasporto sconosciuto, ma il percorso c'e'.
    assertThat(alone.snapshot.underlying?.transport).isEqualTo(NetTransport.OTHER)
    assertThat(alone.decision).isEqualTo(allowed(Via.VPN, "vpn-x"))
  }

  @Test
  fun seIlWifiSottoLaVpnNonSiVede_laVpnFunzionaMaCasaRestaSconosciuta() {
    // T-17 non confermato: Android non fa vedere il Wi-Fi sotto il tunnel. Il percorso c'e' (via tunnel), ma non si
    // puo' dire "sono a casa", quindi l'automazione non spegne la VPN e l'indicatore dice "via VPN".
    val result = eval(LinkFactsSamples.homeVpn())

    assertThat(result.snapshot.home).isEqualTo(HomeMatch.Unknown)
    assertThat(result.snapshot.place).isEqualTo(NetPlace.AWAY_VPN_WIFI)
    assertThat(result.decision).isEqualTo(allowed(Via.VPN, "vpn-home"))
  }

  @Test
  fun laRetePoorSiLeggeDallaCongestione() {
    assertThat(eval(LinkFactsSamples.homeWifi(congested = true)).poor).isTrue()
    assertThat(eval(LinkFactsSamples.homeWifi()).poor).isFalse()
  }

  @Test
  fun lIstanteDellaFotografiaEQuelloDato() {
    assertThat(eval(LinkFactsSamples.homeWifi()).snapshot.at).isEqualTo(7L)
  }

  @Test
  fun sameAs_ignoraSoloLIstante() {
    val a = eval(LinkFactsSamples.homeWifi())
    val later = a.copy(snapshot = a.snapshot.copy(at = 1_000L))
    val different = a.copy(decision = denied(DenyReason.OFFLINE))

    assertThat(a.sameAs(later)).isTrue()
    assertThat(a.sameAs(different)).isFalse()
  }

  // --- Traduzione per il motore delle regole -------------------------------------------------------------

  @Test
  fun toNetworkState_casaConVpn_restaCasa() {
    val state = NetworkPolicy.toNetworkState(eval(LinkFactsSamples.homeVpn(), LinkFactsSamples.homeWifi()).snapshot)

    assertThat(state.isHome).isTrue()
    assertThat(state.viaVpn).isTrue()
    assertThat(NetworkClassifier.classify(state)).isEqualTo(NetworkClass.HOME_WIFI)
  }

  @Test
  fun toNetworkState_roamingEdatiMobili() {
    val roaming = NetworkPolicy.toNetworkState(eval(LinkFactsSamples.homeVpnFullTunnel(), LinkFactsSamples.cellular(roaming = true)).snapshot)
    val mobile = NetworkPolicy.toNetworkState(eval(LinkFactsSamples.homeVpnFullTunnel(), LinkFactsSamples.cellular()).snapshot)

    assertThat(NetworkClassifier.classify(roaming)).isEqualTo(NetworkClass.ROAMING)
    assertThat(NetworkClassifier.classify(mobile)).isEqualTo(NetworkClass.MOBILE)
  }

  @Test
  fun toNetworkState_ilWifiSconosciutoSenzaVpn_eNonRiconosciuto() {
    val state = NetworkPolicy.toNetworkState(eval(LinkFactsSamples.cafeWifi()).snapshot)

    assertThat(NetworkClassifier.classify(state)).isEqualTo(NetworkClass.UNTRUSTED)
  }

  @Test
  fun toNetworkState_offline() {
    assertThat(NetworkClassifier.classify(NetworkPolicy.toNetworkState(eval().snapshot))).isEqualTo(NetworkClass.OFFLINE)
  }
}
