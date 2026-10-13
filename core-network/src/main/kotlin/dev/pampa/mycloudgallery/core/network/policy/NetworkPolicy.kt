package dev.pampa.mycloudgallery.core.network.policy

import dev.pampa.mycloudgallery.core.network.contract.DenyReason
import dev.pampa.mycloudgallery.core.network.contract.HomeClue
import dev.pampa.mycloudgallery.core.network.contract.HomeFingerprint
import dev.pampa.mycloudgallery.core.network.contract.HomeMatch
import dev.pampa.mycloudgallery.core.network.contract.LocalNetworkAccess
import dev.pampa.mycloudgallery.core.network.contract.NetPlace
import dev.pampa.mycloudgallery.core.network.contract.NetTransport
import dev.pampa.mycloudgallery.core.network.contract.NetworkSnapshot
import dev.pampa.mycloudgallery.core.network.contract.UnderlyingNetwork
import dev.pampa.mycloudgallery.core.network.contract.Via
import dev.pampa.mycloudgallery.core.network.contract.VpnObservation
import dev.pampa.mycloudgallery.core.network.home.HomeMatcher
import dev.pampa.mycloudgallery.core.network.link.LinkFacts
import dev.pampa.mycloudgallery.core.network.link.NetworkObservation
import dev.pampa.mycloudgallery.core.rules.NetworkState
import dev.pampa.mycloudgallery.core.rules.NetworkTransport

/** Su quale rete si apre il socket verso il NAS, o perche' non si apre. */
sealed interface PathDecision {

  /** Si puo' contattare il NAS passando dalla rete [networkId] (la `Network` a cui legare il socket). */
  data class Allowed(val via: Via, val networkId: String) : PathDecision

  data class Denied(val reason: DenyReason) : PathDecision
}

/**
 * L'esito della politica di rete: la fotografia per l'indicatore, la decisione per il cancello e i dati per la
 * Diagnostica.
 *
 * @property lanNetworkId il Wi-Fi/Ethernet considerato (anche sotto una VPN), o null.
 * @property vpnNetworkId la VPN considerata, o null.
 * @property weakClues gli indizi deboli che coincidono con casa (E-S10, E-R16): vuoti se la rete e' riconosciuta o
 *   non somiglia a casa.
 * @property vpnClues gli indizi con cui la VPN e' stata giudicata.
 * @property poor la rete sottostante e' congestionata o sospesa (E-R4): si ferma il lavoro automatico.
 */
data class NetworkEvaluation(
  val snapshot: NetworkSnapshot,
  val decision: PathDecision,
  val lanNetworkId: String? = null,
  val vpnNetworkId: String? = null,
  val weakClues: Set<HomeClue> = emptySet(),
  val vpnClues: Set<VpnClue> = emptySet(),
  val poor: Boolean = false,
) {
  /** Lo stesso esito, a meno dell'istante della fotografia (che cambia a ogni lettura). */
  internal fun sameAs(other: NetworkEvaluation): Boolean = copy(snapshot = snapshot.copy(at = other.snapshot.at)) == other

  companion object {
    val INITIAL = NetworkEvaluation(NetworkSnapshot.OFFLINE, PathDecision.Denied(DenyReason.OFFLINE))
  }
}

/**
 * La politica di rete (docs/rete-casa-vpn.md): da cio' che Android descrive, dall'impronta di casa e dal permesso
 * di rete locale ricava il luogo (`NetPlace`) e il percorso verso il NAS. **Pura**: niente Android, niente tempo.
 *
 * Il percorso e' consentito solo in tre casi, e in tutti il socket va legato alla rete scelta (D1):
 *  - **casa, senza VPN**: il Wi-Fi/Ethernet di casa ([Via.HOME_LAN]);
 *  - **casa, con la VPN di casa accesa**: la rete della VPN ([Via.HOME_LAN_THROUGH_VPN]) - WireGuard non chiama
 *    `allowBypass`, quindi un socket legato al Wi-Fi verrebbe comunque catturato dal tunnel;
 *  - **fuori casa, con la VPN di casa accesa**: la rete della VPN ([Via.VPN]).
 *
 * Tutto il resto e' [PathDecision.Denied] e non parte nemmeno la connessione TCP.
 */
object NetworkPolicy {

  fun evaluate(
    observation: NetworkObservation,
    fingerprint: HomeFingerprint?,
    localNetwork: LocalNetworkAccess,
    at: Long,
  ): NetworkEvaluation {
    val vpn = chooseVpn(observation, fingerprint)
    val vpnVerdict = VpnClassifier.verdict(vpn, fingerprint)
    val vpnKind = vpnVerdict.observation
    val lan = chooseLan(observation, fingerprint)
    val underlying = chooseUnderlying(observation, vpn, lan, fingerprint)

    // Una rete sottostante ricavata dalla sola VPN (Android non fa vedere il Wi-Fi sotto il tunnel, T-17) non ha
    // indirizzi da confrontare: "casa" resta sconosciuta, non "diversa".
    val homeMatch =
      if (underlying != null && underlying.isLan && underlying.addresses.isNotEmpty()) HomeMatcher.match(underlying, fingerprint) else HomeMatch.Unknown
    val weakClues = (homeMatch as? HomeMatch.Weak)?.clues.orEmpty()
    val poor = underlying?.let { it.congested || it.suspended } == true || vpn?.let { it.congested || it.suspended } == true

    val (place, decision) = decide(underlying, vpn, vpnKind, homeMatch, fingerprint, localNetwork)

    val snapshot = NetworkSnapshot(
      place = place,
      underlying = underlying?.toUnderlyingNetwork(),
      home = homeMatch,
      vpn = vpnKind,
      localNetwork = localNetwork,
      ipv6Global = underlying?.hasGlobalIpv6 == true,
      at = at,
      vpnCarriesInternet = vpn != null && VpnClassifier.carriesInternet(vpn),
    )
    return NetworkEvaluation(
      snapshot = snapshot,
      decision = decision,
      lanNetworkId = lan?.networkId,
      vpnNetworkId = vpn?.networkId,
      weakClues = weakClues,
      vpnClues = vpnVerdict.clues,
      poor = poor,
    )
  }

  private fun decide(
    underlying: LinkFacts?,
    vpn: LinkFacts?,
    vpnKind: VpnObservation,
    homeMatch: HomeMatch,
    fingerprint: HomeFingerprint?,
    localNetwork: LocalNetworkAccess,
  ): Pair<NetPlace, PathDecision> {
    fun denied(place: NetPlace, reason: DenyReason) = place to PathDecision.Denied(reason)

    return when {
      underlying == null -> denied(NetPlace.OFFLINE, DenyReason.OFFLINE)
      underlying.captivePortal -> denied(NetPlace.OFFLINE, DenyReason.CAPTIVE_PORTAL)
      fingerprint == null -> denied(NetPlace.UNTRUSTED, DenyReason.NOT_CONFIGURED)
      // E-S16: una VPN che non e' quella di casa (o di cui non si sa niente) si tratta da estranea, anche a casa:
      // il tunnel cattura il traffico dell'app, quindi il NAS non si contatta.
      vpnKind == VpnObservation.FOREIGN_VPN || vpnKind == VpnObservation.UNKNOWN ->
        denied(NetPlace.UNTRUSTED, DenyReason.FOREIGN_VPN)
      vpnKind == VpnObservation.HOME_TUNNEL && vpn != null -> {
        when {
          // Il tunnel poggia sul Wi-Fi/mobile: se quello non e' validato il tunnel non porta da nessuna parte.
          !underlying.validated -> denied(NetPlace.OFFLINE, DenyReason.OFFLINE)
          homeMatch == HomeMatch.Match ->
            NetPlace.HOME to PathDecision.Allowed(Via.HOME_LAN_THROUGH_VPN, vpn.networkId)
          else -> awayPlace(underlying) to PathDecision.Allowed(Via.VPN, vpn.networkId)
        }
      }
      // Senza VPN.
      homeMatch == HomeMatch.Match ->
        // Casa: il NAS e' in LAN, quindi basta anche una rete senza internet (router senza WAN).
        if (localNetwork == LocalNetworkAccess.DENIED) denied(NetPlace.HOME, DenyReason.LOCAL_NETWORK_DENIED)
        else NetPlace.HOME to PathDecision.Allowed(Via.HOME_LAN, underlying.networkId)
      !underlying.validated -> denied(NetPlace.OFFLINE, DenyReason.OFFLINE)
      // Somiglia a casa ma non lo e' (E-S10), oppure e' casa con l'ULA cambiato (E-R16).
      homeMatch is HomeMatch.Weak -> denied(NetPlace.UNTRUSTED, DenyReason.UNTRUSTED_NETWORK)
      else -> denied(NetPlace.VPN_OFF, DenyReason.VPN_OFF_AWAY)
    }
  }

  /** Fuori casa con la VPN di casa: roaming, Wi-Fi libero oppure "come dati mobili" (cellulare o Wi-Fi a consumo, E-R13). */
  private fun awayPlace(underlying: LinkFacts): NetPlace =
    when {
      underlying.roaming -> NetPlace.ROAMING
      underlying.isLan && !underlying.metered -> NetPlace.AWAY_VPN_WIFI
      else -> NetPlace.AWAY_VPN_MOBILE
    }

  // --- Scelta delle reti ----------------------------------------------------------------------------

  /** La VPN da giudicare: quella predefinita, altrimenti una di casa, altrimenti la prima. */
  private fun chooseVpn(observation: NetworkObservation, fingerprint: HomeFingerprint?): LinkFacts? {
    val vpns = observation.vpns
    if (vpns.isEmpty()) return null
    return vpns.firstOrNull { it.networkId == observation.defaultNetworkId }
      ?: vpns.firstOrNull { VpnClassifier.classify(it, fingerprint) == VpnObservation.HOME_TUNNEL }
      ?: vpns.first()
  }

  /**
   * Il Wi-Fi/Ethernet da considerare: prima quello riconosciuto come casa, poi quello che somiglia a casa
   * (piu' indizi, meglio), poi il predefinito, poi uno validato.
   */
  private fun chooseLan(observation: NetworkObservation, fingerprint: HomeFingerprint?): LinkFacts? {
    val lans = observation.lans
    if (lans.isEmpty()) return null
    return lans.maxWithOrNull(
      compareBy<LinkFacts> {
        when (val m = HomeMatcher.match(it, fingerprint)) {
          HomeMatch.Match -> MATCH_SCORE
          is HomeMatch.Weak -> WEAK_SCORE + m.clues.size
          else -> 0
        }
      }.thenBy { it.networkId == observation.defaultNetworkId }.thenBy { it.validated },
    )
  }

  /**
   * La rete sottostante: quella su cui poggia (o poggerebbe) il traffico verso il NAS.
   *  - con una VPN, quella che la VPN dichiara (o, se non dichiara niente, la migliore validata);
   *  - senza VPN, il Wi-Fi di casa se c'e' (anche se il predefinito di Android e' il cellulare, E-R12: il socket
   *    e' legato al Wi-Fi), altrimenti la rete predefinita.
   */
  private fun chooseUnderlying(
    observation: NetworkObservation,
    vpn: LinkFacts?,
    lan: LinkFacts?,
    fingerprint: HomeFingerprint?,
  ): LinkFacts? {
    val cellular = observation.cellulars.maxWithOrNull(
      compareBy<LinkFacts> { it.networkId == observation.defaultNetworkId }.thenBy { it.validated },
    )
    if (vpn != null) {
      val declared = vpn.transports
      return when {
        (NetTransport.WIFI in declared || NetTransport.ETHERNET in declared) && lan != null -> lan
        NetTransport.CELLULAR in declared && cellular != null -> cellular
        else ->
          listOfNotNull(lan, cellular).maxByOrNull { it.validated } ?: vpn.asUnderlyingOfVpn()
      }
    }
    val default = observation.defaultNetwork
    return when {
      lan != null && HomeMatcher.match(lan, fingerprint) == HomeMatch.Match -> lan
      default != null && !default.isVpn -> default
      lan != null -> lan
      else -> cellular
    }
  }

  private fun LinkFacts.toUnderlyingNetwork() =
    UnderlyingNetwork(
      networkId = networkId,
      transport = primaryTransport,
      metered = metered,
      roaming = roaming,
      validated = validated,
      captivePortal = captivePortal,
    )

  private const val MATCH_SCORE = 100
  private const val WEAK_SCORE = 10

  /**
   * Lo [NetworkState] che il motore delle regole usa. "Casa" ha una sola definizione in tutta l'app: questa.
   *  - `viaVpn` solo se la VPN e' quella di casa: una VPN estranea non rende raggiungibile il NAS, quindi per le
   *    regole la rete e' non riconosciuta;
   *  - a casa il NAS e' in LAN: una rete senza internet (non captive) vale come validata;
   *  - la rete e' quella su cui poggia il socket verso il NAS, non la predefinita del sistema (E-R12).
   */
  fun toNetworkState(snapshot: NetworkSnapshot): NetworkState {
    val net = snapshot.underlying ?: return NetworkState.OFFLINE
    if (snapshot.place == NetPlace.OFFLINE) return NetworkState.OFFLINE
    val transport = when (net.transport) {
      NetTransport.WIFI -> NetworkTransport.WIFI
      NetTransport.CELLULAR -> NetworkTransport.CELLULAR
      NetTransport.ETHERNET -> NetworkTransport.ETHERNET
      NetTransport.OTHER -> NetworkTransport.NONE
    }
    val isHome = snapshot.place == NetPlace.HOME
    return NetworkState(
      transport = transport,
      viaVpn = snapshot.vpn == VpnObservation.HOME_TUNNEL,
      isHome = isHome,
      metered = net.metered,
      roaming = net.roaming,
      validated = (net.validated || isHome) && !net.captivePortal,
    )
  }
}
