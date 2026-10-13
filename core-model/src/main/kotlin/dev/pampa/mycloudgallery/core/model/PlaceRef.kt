package dev.pampa.mycloudgallery.core.model

/**
 * Il luogo di uno scatto come riferimento al dataset offline dei luoghi (`core-places`, GeoNames):
 * [geonameId] e' l'identificativo del luogo, [dataset] la versione del file da cui e' stato letto.
 *
 * **Dato sensibile** come il GPS da cui deriva (piano §7): "Firenze" dice dove si abita quasi quanto
 * le coordinate. Per questo non e' una `data class` e il `toString()` non mostra nulla: non finisce in
 * un log o in un messaggio d'errore per distrazione. Il nome lo risolve solo l'interfaccia
 * (`PlaceNames`), al momento di mostrarlo.
 *
 * Il [dataset] serve alla manutenzione: quando l'app porta un dataset nuovo, i luoghi letti con il
 * vecchio si ricalcolano (`catalog_meta.places_dataset`).
 */
@Sensitive("Il luogo dello scatto rivela dove si abita e dove si va, come il GPS.")
class PlaceRef(val geonameId: Int, val dataset: Int) {

  init {
    require(geonameId > 0) { "Identificativo di luogo non valido." }
    require(dataset > 0) { "Versione del dataset dei luoghi non valida." }
  }

  override fun equals(other: Any?): Boolean = other is PlaceRef && other.geonameId == geonameId && other.dataset == dataset

  override fun hashCode(): Int = geonameId * 31 + dataset

  override fun toString(): String = "PlaceRef([luogo nascosto])"
}
