package dev.pampa.mycloudgallery.core.network.policy

import dev.pampa.mycloudgallery.core.network.contract.HomeFingerprint
import dev.pampa.mycloudgallery.core.network.contract.VpnObservation
import dev.pampa.mycloudgallery.core.network.home.HomeMatcher
import dev.pampa.mycloudgallery.core.network.home.HomePrefixes
import dev.pampa.mycloudgallery.core.network.link.IpAddr
import dev.pampa.mycloudgallery.core.network.link.IpPrefix
import dev.pampa.mycloudgallery.core.network.link.LinkFacts

/** Gli indizi che una VPN porta verso casa (D2). I primi tre sono forti (l'ULA), gli altri deboli. */
enum class VpnClue(val strong: Boolean) {
  /** Una rotta del tunnel (`AllowedIPs`) copre il /48 ULA di casa. */
  ULA_ROUTE(strong = true),

  /** Un DNS del tunnel sta nel /48 ULA di casa. */
  ULA_DNS(strong = true),

  /** Un indirizzo del tunnel sta nel /48 ULA di casa. */
  ULA_ADDRESS(strong = true),

  /** Una rotta specifica (non predefinita) contiene il gateway IPv4 di casa. */
  SUBNET_ROUTE(strong = false),

  /** Il DNS del tunnel e' il gateway IPv4 di casa (il FRITZ!Box). */
  GATEWAY_DNS(strong = false),

  /** Il dominio di ricerca del tunnel e' quello di casa (`fritz.box`). */
  DOMAIN(strong = false),
}

/** Il verdetto sulla VPN e gli indizi che l'hanno prodotto (per la Diagnostica). */
class VpnVerdict(val observation: VpnObservation, val clues: Set<VpnClue>, val foreignUla: Boolean) {
  override fun toString(): String = "VpnVerdict($observation, ${clues.size} indizi)"
}

/**
 * Dice se una VPN e' **quella di casa** (D2, E-S16): non basta che ci sia un tunnel.
 *
 * Regole, in ordine:
 *  1. senza VPN: [VpnObservation.NONE]; senza impronta di casa (o senza ULA valido) non si puo' dire di chi sia:
 *     [VpnObservation.UNKNOWN];
 *  2. un indizio **forte** (l'ULA di casa fra le rotte, i DNS o gli indirizzi del tunnel): [VpnObservation.HOME_TUNNEL];
 *  3. il tunnel porta un ULA, ma di un'altra casa (il tunnel verso il FRITZ!Box di un amico): estraneo;
 *  4. almeno **due** indizi deboli diversi (rotta sulla sottorete, DNS = gateway, dominio): casa (e' il caso della
 *     configurazione che il FRITZ!Box esporta di fabbrica, con rotta predefinita, DNS e dominio);
 *  5. tutto il resto (un tunnel commerciale con rotta predefinita e DNS pubblici, un VPN aziendale): estraneo.
 *
 * Gli indizi deboli, da soli, non distinguono la propria casa da quella di un amico: la conferma finale e' il GUID
 * del NAS, letto dalla NEGOTIATE prima di qualunque autenticazione (core-nas). Una rotta predefinita non e' mai un
 * indizio: ogni tunnel completo ce l'ha.
 */
object VpnClassifier {

  fun classify(vpn: LinkFacts?, fingerprint: HomeFingerprint?): VpnObservation = verdict(vpn, fingerprint).observation

  /**
   * Revisione 2, F2: la VPN porta anche il traffico verso internet, non solo quello verso casa? Vero con la rotta
   * predefinita (`AllowedIPs = 0.0.0.0/0` o `::/0`) e con le rotte ampie verso indirizzi pubblici che WireGuard usa per
   * "escludi gli IP privati" (`0.0.0.0/5`, `8.0.0.0/7`...). Le reti di casa (private, ULA, o un prefisso IPv6 pubblico
   * di casa, da /48 in giu') non contano.
   */
  fun carriesInternet(vpn: LinkFacts): Boolean = vpn.routes.any { route ->
    val destination = route.destination
    val private = PRIVATE_SPACE.any { it.contains(destination) }
    val wide = if (destination.isV4) destination.length <= MAX_INTERNET_V4 else destination.length <= MAX_INTERNET_V6
    !private && wide
  }

  /** Lo spazio privato: RFC 1918, CGNAT, link-local, ULA. */
  private val PRIVATE_SPACE: List<IpPrefix> =
    listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10", "169.254.0.0/16", "fc00::/7", "fe80::/10")
      .map { requireNotNull(IpPrefix.parse(it)) }

  /** Una rotta pubblica IPv4 di /16 o piu' ampia non e' una rete di casa. */
  private const val MAX_INTERNET_V4 = 16

  /** Una rotta pubblica IPv6 piu' ampia di /32 non e' il prefisso di una casa (di solito /48-/64). */
  private const val MAX_INTERNET_V6 = 32

  fun verdict(vpn: LinkFacts?, fingerprint: HomeFingerprint?): VpnVerdict {
    if (vpn == null) return VpnVerdict(VpnObservation.NONE, emptySet(), foreignUla = false)
    val homeUla = fingerprint?.let(HomePrefixes::ula).orEmpty()
    if (fingerprint == null || homeUla.isEmpty()) return VpnVerdict(VpnObservation.UNKNOWN, emptySet(), foreignUla = false)

    val clues = LinkedHashSet<VpnClue>()
    var foreignUla = false

    // --- Indizi forti: l'ULA ---------------------------------------------------------------------------
    for (route in vpn.routes) {
      val dest = route.destination
      if (dest.isV4 || !dest.network.isUla || dest.length < IpPrefix.ULA_LENGTH) continue
      if (homeUla.any { it.overlaps(dest) }) clues += VpnClue.ULA_ROUTE else foreignUla = true
    }
    for (dns in vpn.dnsServers) {
      if (!dns.isUla) continue
      if (homeUla.any { it.contains(dns) }) clues += VpnClue.ULA_DNS else foreignUla = true
    }
    for (addr in vpn.addresses) {
      if (!addr.ip.isUla) continue
      if (homeUla.any { it.contains(addr.ip) }) clues += VpnClue.ULA_ADDRESS else foreignUla = true
    }

    // --- Indizi deboli ---------------------------------------------------------------------------------
    val homeGateway = fingerprint.gatewayV4?.let(IpAddr::parse)
    if (homeGateway != null) {
      if (vpn.routes.any { !it.isDefault && it.destination.isV4 && it.destination.length >= MIN_SUBNET_ROUTE && it.destination.contains(homeGateway) }) {
        clues += VpnClue.SUBNET_ROUTE
      }
      if (vpn.dnsServers.any { it == homeGateway }) clues += VpnClue.GATEWAY_DNS
    }
    val homeDomain = fingerprint.dnsDomain?.let(HomeMatcher::normalizeDomain)
    if (homeDomain != null && vpn.domains.any { HomeMatcher.normalizeDomain(it) == homeDomain }) clues += VpnClue.DOMAIN

    val strong = clues.any { it.strong }
    val weak = clues.count { !it.strong }
    val observation = when {
      strong -> VpnObservation.HOME_TUNNEL
      foreignUla -> VpnObservation.FOREIGN_VPN
      weak >= MIN_WEAK_CLUES -> VpnObservation.HOME_TUNNEL
      else -> VpnObservation.FOREIGN_VPN
    }
    return VpnVerdict(observation, clues, foreignUla)
  }

  /** Una rotta IPv4 piu' larga di un /16 (`0.0.0.0/1`, `128.0.0.0/1`, `10.0.0.0/8`...) non dice dove porta il tunnel. */
  private const val MIN_SUBNET_ROUTE = 16

  private const val MIN_WEAK_CLUES = 2
}
