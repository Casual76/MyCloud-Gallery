package dev.pampa.mycloudgallery.core.library.contract

import dev.pampa.mycloudgallery.core.model.MediaId

// Fase 7 (P9, D23, E-H2): "Nascosti" protegge solo dentro l'app. Un media che sta nell'area comune del NAS, o in un album condiviso, lo
// vedono tutti i familiari anche se qui e' nascosto: quando lo si nasconde, l'app lo dice ("I familiari continuano a vederlo").

/**
 * Quali di [ids] i **familiari** continuano a vedere anche se qui sono nascosti: i media che hanno una copia nell'area comune del NAS (dove
 * stanno anche gli elementi degli album condivisi, D9) oppure, quando ci saranno, che sono elementi di un album condiviso. Sola lettura, solo
 * per dirlo all'utente al momento di nascondere: non decide niente e non cambia niente. `:app` lo lega al catalogo
 * (`CatalogFamilyExposure`); la sua risposta e' un'informazione, mai un permesso.
 */
fun interface FamilyExposure {
  suspend fun seenByFamily(ids: Collection<MediaId>): Set<MediaId>

  companion object {
    /** Nessuno vede niente in piu': nessun NAS, nessun avviso. */
    val None: FamilyExposure = FamilyExposure { emptySet() }
  }
}
