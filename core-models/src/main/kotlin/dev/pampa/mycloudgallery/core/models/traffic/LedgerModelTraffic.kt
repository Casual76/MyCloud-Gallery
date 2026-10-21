package dev.pampa.mycloudgallery.core.models.traffic

import dev.pampa.mycloudgallery.core.models.contract.ModelTraffic
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficLedger
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficNetwork
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficSource

/**
 * Il traffico dei modelli nel registro dell'app (§2.1, §2.7): categoria `AI_COMPUTE`, sulla rete EFFETTIVAMENTE usata dal socket
 * (E-R12), che [network] dice a ogni blocco (`:app` la legge dallo stato della rete). Si registra a ogni blocco, prima del punto di
 * ripresa: se il processo muore, il traffico passato conta.
 */
class LedgerModelTraffic(
  private val ledger: TrafficLedger,
  private val network: () -> TrafficNetwork,
) : ModelTraffic {
  override suspend fun record(bytes: Long) {
    if (bytes > 0) ledger.record(TrafficSource.AI_COMPUTE, network(), bytes)
  }
}
