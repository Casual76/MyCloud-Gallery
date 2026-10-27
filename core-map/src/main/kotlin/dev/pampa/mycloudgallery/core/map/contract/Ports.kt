package dev.pampa.mycloudgallery.core.map.contract

import kotlinx.coroutines.flow.StateFlow

// LE PORTE DELLA MAPPA (docs/fase9-progetto.md §1.2 "Porte", §1.4 `MapGraph`, D2, D23). core-map non vede core-network, core-nas ne'
// core-database (Phase9ArchitectureTest): la rete in uso, il registro del traffico e i punti arrivano da porte legate da :app (P13). Nessuna
// porta porta coordinate fuori dal telefono.

/** La rete in uso per la mappa (legata a core-network: rete validata, a consumo, roaming). */
interface MapNetworkState {
  val current: StateFlow<MapNetwork>
}

/** Il registro del traffico (proposta 3: la mappa e' contenuto visibile; tetti mensili rispettati): solo byte e rete, mai cosa. */
fun interface MapTraffic {
  fun record(bytes: Long, network: MapNetwork)
}
