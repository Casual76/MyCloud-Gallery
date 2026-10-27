package dev.pampa.mycloudgallery.core.metadata.extract

import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.PlaceRef
import dev.pampa.mycloudgallery.core.places.contract.PlacePrecision
import dev.pampa.mycloudgallery.core.places.contract.ReverseGeocoder

/**
 * Il luogo che si scrive per un punto GPS: **una regola sola** per chi legge un file (`DefaultMetadataExtractor`) e per chi ricalcola i luoghi quando
 * il dataset cambia (`PlacesRecompute`, debito del 27/10). Se le due strade avessero regole diverse, lo stesso punto darebbe due luoghi.
 */
object PlaceChoice {

  /**
   * Il luogo di [point] con il dataset di [geocoder], solo se e' abbastanza vicino da dirne il nome (`CITY` o `NEAR`): oltre, "Firenze" per una foto a
   * 120 km sarebbe una bugia. Null senza punto, senza dataset aperto (`dataset` <= 0) o senza un luogo vicino.
   */
  fun of(geocoder: ReverseGeocoder, point: GeoPoint?): PlaceRef? {
    if (point == null || geocoder.dataset <= 0) return null
    val match = geocoder.nearest(point) ?: return null
    if (match.precision != PlacePrecision.CITY && match.precision != PlacePrecision.NEAR) return null
    return PlaceRef(match.place.id.value, geocoder.dataset)
  }
}
