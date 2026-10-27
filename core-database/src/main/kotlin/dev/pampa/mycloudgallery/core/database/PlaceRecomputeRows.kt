package dev.pampa.mycloudgallery.core.database

import dev.pampa.mycloudgallery.core.model.VersionId

// Il ricalcolo dei luoghi a un dataset nuovo (debito del 27/10, Fase 3 §2.3; `PlacesRecompute` in core-mediastore): i tipi che il catalogo scambia con chi lo guida.

/**
 * Una versione con GPS il cui luogo non e' stato calcolato con il dataset in uso. Le coordinate sono **dati sensibili** (come `GeoPoint`): la classe non e'
 * una `data class` e il testo non mostra niente.
 */
class PlaceToRecompute(val version: VersionId, val latitude: Double, val longitude: Double) {
  override fun toString(): String = "PlaceToRecompute([posizione nascosta])"
}

/** L'esito di una scrittura di luoghi: quante versioni portano ora il dataset nuovo, e a quanti media e' cambiato il luogo (l'indice annuncia solo questi). */
class PlacesSaved(val stamped: Int, val changed: Int) {
  override fun toString(): String = "PlacesSaved(stamped=$stamped, changed=$changed)"
}
