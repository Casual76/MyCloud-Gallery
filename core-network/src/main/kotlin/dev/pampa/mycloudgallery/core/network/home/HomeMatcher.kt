package dev.pampa.mycloudgallery.core.network.home

import dev.pampa.mycloudgallery.core.network.contract.HomeCandidate
import dev.pampa.mycloudgallery.core.network.contract.HomeClue
import dev.pampa.mycloudgallery.core.network.contract.HomeFingerprint
import dev.pampa.mycloudgallery.core.network.contract.HomeMatch
import dev.pampa.mycloudgallery.core.network.link.IpAddr
import dev.pampa.mycloudgallery.core.network.link.IpPrefix
import dev.pampa.mycloudgallery.core.network.link.LinkFacts

/**
 * Il confronto fra una rete e l'impronta di casa (docs/rete-casa-vpn.md, "Riconoscere casa").
 *
 * **Solo l'ULA decide.** Il prefisso IPv6 locale /48 e' casuale per ogni FRITZ!Box: due case con la stessa
 * sottorete IPv4, lo stesso gateway e lo stesso dominio `fritz.box` hanno ULA diversi (E-S10). Gateway, dominio e
 * sottorete IPv4 sono indizi che coincidono ovunque: bastano a dire "somiglia a casa" ([HomeMatch.Weak]), mai
 * "e' casa".
 */
object HomeMatcher {

  fun match(network: LinkFacts?, fingerprint: HomeFingerprint?): HomeMatch {
    if (fingerprint == null || network == null || !network.isLan) return HomeMatch.Unknown
    val home = HomePrefixes.ula(fingerprint)
    if (home.isNotEmpty() && network.addresses.any { addr -> addr.ip.isUla && home.any { it.contains(addr.ip) } }) {
      return HomeMatch.Match
    }
    val clues = weakClues(network, fingerprint)
    return if (clues.isEmpty()) HomeMatch.NoMatch else HomeMatch.Weak(clues)
  }

  /** Gli indizi deboli che coincidono. Dicono "somiglia a casa", non "e' casa". */
  fun weakClues(network: LinkFacts, fingerprint: HomeFingerprint): Set<HomeClue> {
    val clues = LinkedHashSet<HomeClue>()
    val homeGateway = fingerprint.gatewayV4?.let(IpAddr::parse)
    if (homeGateway != null) {
      if (network.gatewayV4 == homeGateway) clues += HomeClue.GATEWAY_V4
      if (network.ipv4Subnets.any { it.contains(homeGateway) }) clues += HomeClue.IPV4_SUBNET
    }
    val homeDomain = fingerprint.dnsDomain?.let(::normalizeDomain)
    if (homeDomain != null && network.domains.any { normalizeDomain(it) == homeDomain }) clues += HomeClue.DNS_DOMAIN
    return clues
  }

  internal fun normalizeDomain(domain: String): String = domain.trim().trimEnd('.').lowercase()
}

/** I prefissi dell'impronta, letti con cautela: un valore non valido si scarta, mai si allarga (fail-closed). */
internal object HomePrefixes {

  /** Le lunghezze ammesse per un ULA memorizzato: il /48 della rete (o, al piu', un /64 dentro di esso). */
  private const val MIN_LENGTH = 48
  private const val MAX_LENGTH = 64

  /**
   * I prefissi ULA validi dell'impronta. Si scarta tutto cio' che non e' un ULA o e' piu' largo di un /48:
   * `fc00::/7` combacerebbe con qualunque rete locale unica del mondo (una configurazione manomessa o sbagliata
   * non deve poter allargare il riconoscimento).
   */
  fun ula(fingerprint: HomeFingerprint): Set<IpPrefix> =
    fingerprint.ulaPrefixes
      .mapNotNull(IpPrefix::parse)
      .filter { it.network.isUla && it.length in MIN_LENGTH..MAX_LENGTH }
      .toSet()
}

/** Costruisce candidati e impronte a partire da una rete osservata. */
object HomeCandidates {

  /** Al massimo quattro ULA nella configurazione di famiglia (`homeUla[] <= 4`, docs/fase2-progetto.md 2.3). */
  const val MAX_ULA = 4

  /**
   * Cio' che il Wi-Fi o l'Ethernet [network] offre come candidato a "casa". Null se non e' una rete locale
   * (una VPN o il cellulare non sono casa).
   */
  fun from(network: LinkFacts): HomeCandidate? {
    if (!network.isLan) return null
    val ula = network.ulaPrefixes.map { it.toText() }.sorted().take(MAX_ULA).toSet()
    return HomeCandidate(
      networkId = network.networkId,
      ulaPrefixes = ula,
      gatewayV4 = network.gatewayV4?.toText(),
      dnsDomain = network.domains.firstOrNull()?.let(HomeMatcher::normalizeDomain)?.takeIf { it.isNotEmpty() },
      ipv4Subnet = network.ipv4Subnets.firstOrNull()?.toText(),
    )
  }
}

/** Da un candidato confermato dall'utente all'impronta da salvare. */
object HomeFingerprints {

  /** Null se il candidato non ha nessun ULA: senza, "casa" non si riconoscerebbe in modo affidabile. */
  fun fromCandidate(candidate: HomeCandidate, capturedAt: Long): HomeFingerprint? {
    if (!candidate.canBeConfirmed) return null
    return HomeFingerprint(
      ulaPrefixes = candidate.ulaPrefixes,
      gatewayV4 = candidate.gatewayV4,
      dnsDomain = candidate.dnsDomain,
      capturedAt = capturedAt,
    )
  }
}
