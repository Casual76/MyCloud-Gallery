package dev.pampa.mycloudgallery.feature.map.contract

import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.map.contract.CellKey
import dev.pampa.mycloudgallery.core.model.MediaId

/**
 * Cosa le schermate della mappa chiedono a :app (P13): la navigazione. feature-map non conosce il viewer, le Raccolte ne' le impostazioni
 * (D2): chiede e basta. Nessun parametro porta coordinate (una zona e' la sua cella, un luogo il suo id).
 */
interface MapActions {
  /** Il viewer della Libreria sulla vista [view] di una zona, da [start] (anche per i media solo sul NAS, E-MAP6). */
  fun openViewer(view: OrderedTimelineView, start: MediaId)

  /** La griglia della zona [key] (sotto 30 elementi o al livello massimo, D28). */
  fun openZone(key: CellKey)

  /** La griglia del luogo [placeId] (sezione Luoghi, D30). */
  fun openPlace(placeId: Int)

  /** La mappa a schermo pieno (dalla riga "Mappa" di Luoghi). */
  fun openMap()

  /** La mappa centrata su [media] ("Mostra sulla mappa" dal viewer, E-MAP19). */
  fun openMapAt(media: MediaId)

  /** Impostazioni -> Mappa (interruttore, nota, "Solo sul Wi-Fi"). */
  fun openMapSettings()

  /** "Zone senza Internet". */
  fun openRegions()
}
