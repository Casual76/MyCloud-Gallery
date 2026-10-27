package dev.pampa.mycloudgallery.core.albums.trips

import dev.pampa.mycloudgallery.core.albums.contract.HomeClearance
import dev.pampa.mycloudgallery.core.model.GeoPoint

// L'ORACOLO "TROPPO VICINO A CASA" (R16 della revisione di fine Fase 7; docs/fase7-progetto.md D18). La casa sta solo in trips/ (`TripStore`): chi
// deve scrivere il centro di un luogo nei dati personali (gli album smart sulla propria citta') non la vede mai, chiede qui se un punto sta troppo
// vicino a casa o a un luogo abituale e riceve un si'/no. Le coordinate entrano, nessuna coordinata di casa esce.

internal class StoreHomeClearance(private val store: TripStore) : HomeClearance {

  override suspend fun closeToHome(points: List<GeoPoint>): List<Boolean> {
    val anchors = store.anchors() ?: return List(points.size) { false }
    return points.map { anchors.hidesCenter(it.latitude, it.longitude) }
  }

  override fun toString(): String = "StoreHomeClearance"
}
