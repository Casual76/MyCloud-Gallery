package dev.pampa.mycloudgallery.core.network

import dev.pampa.mycloudgallery.core.network.contract.HomeFingerprint
import dev.pampa.mycloudgallery.core.network.contract.LocalNetworkAccess
import dev.pampa.mycloudgallery.core.network.contract.NetTransport
import dev.pampa.mycloudgallery.core.network.contract.NetworkSnapshot
import dev.pampa.mycloudgallery.core.network.link.LinkFacts
import dev.pampa.mycloudgallery.core.network.link.NetworkObservation
import dev.pampa.mycloudgallery.core.network.policy.NetworkEvaluation
import dev.pampa.mycloudgallery.core.network.policy.NetworkPolicy
import dev.pampa.mycloudgallery.core.network.testing.LinkFactsSamples

/** Istantanee ricavate dalla politica vera da reti sintetiche, per i test che hanno bisogno di un `NetworkSnapshot`. */
internal object TestSnapshots {

  val fingerprint: HomeFingerprint = LinkFactsSamples.homeFingerprint()

  fun evaluate(
    vararg networks: LinkFacts,
    default: String? = networks.firstOrNull()?.networkId,
    fp: HomeFingerprint? = fingerprint,
    local: LocalNetworkAccess = LocalNetworkAccess.GRANTED_IMPLICIT,
  ): NetworkEvaluation = NetworkPolicy.evaluate(NetworkObservation(default, networks.toList()), fp, local, at = 0L)

  fun snapshot(vararg networks: LinkFacts, fp: HomeFingerprint? = fingerprint): NetworkSnapshot =
    evaluate(*networks, fp = fp).snapshot

  fun homeNoVpn(wifiId: String = "wifi-1"): NetworkSnapshot = snapshot(LinkFactsSamples.homeWifi(id = wifiId))

  fun homeTunnel(wifiId: String = "wifi-1"): NetworkSnapshot = snapshot(LinkFactsSamples.homeVpn(), LinkFactsSamples.homeWifi(id = wifiId))

  fun awayNoVpn(cellId: String = "cell-1"): NetworkSnapshot = snapshot(LinkFactsSamples.cellular(id = cellId))

  fun awayTunnel(cellId: String = "cell-1"): NetworkSnapshot =
    snapshot(LinkFactsSamples.homeVpn(over = NetTransport.CELLULAR), LinkFactsSamples.cellular(id = cellId))

  fun foreignVpn(): NetworkSnapshot = snapshot(LinkFactsSamples.foreignVpn(), LinkFactsSamples.cafeWifi())
}
