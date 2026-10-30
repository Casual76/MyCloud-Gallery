package dev.pampa.mycloudgallery.core.albums.trips

import dev.pampa.mycloudgallery.core.albums.contract.AlbumCandidate
import dev.pampa.mycloudgallery.core.model.TripKind

/** Il titolo e i luoghi di una proposta con i soli media visibili adesso (R16). */
internal class ShownRow(val label: String, val placeIds: List<Int>) {
  override fun toString(): String = "ShownRow"
}

/**
 * R16, in un posto solo per chi mostra le proposte (`CatalogTrips`: sezione Viaggi, "Crea album") e per chi le porta nei ricordi (`PastTrips`,
 * D12): titolo e luoghi si fissano alla rilevazione, ma un media nascosto dopo non deve restare nel titolo ("A e B, marzo 2026" con l'unica foto di
 * B nascosta), ne' finire nei dati personali con "Crea album", ne' nel titolo di un ricordo.
 */
internal object ShownTrips {

  /**
   * Il titolo e i luoghi di [row] guardando i soli media di [pool] (gia' senza nascosti). Se tutti i luoghi della riga hanno ancora un media
   * visibile con il GPS, la riga resta com'e' (neanche un trasloco cambia i viaggi gia' proposti, E-AL15: qui non conta dov'e' la casa di
   * oggi); se un luogo non ne ha piu', titolo e luoghi si rifanno con quelli che restano.
   */
  fun of(titles: TripTitles, row: StoredProposal, pool: List<AlbumCandidate>): ShownRow {
    if (row.placeIds.isEmpty()) return ShownRow(row.label, row.placeIds)
    val located = pool.filter { it.gps != null && it.placeId != 0 }
    val seen = located.mapTo(HashSet()) { it.placeId }
    val kept = row.placeIds.filter { it in seen }
    if (kept.size == row.placeIds.size) return ShownRow(row.label, row.placeIds)
    val stats = PlaceStats.of(located.filter { it.placeId in kept })
    val label = if (row.kind == TripKind.TRIP) titles.trip(row.days, stats) else titles.event(row.days, stats)
    return ShownRow(label, kept)
  }
}
