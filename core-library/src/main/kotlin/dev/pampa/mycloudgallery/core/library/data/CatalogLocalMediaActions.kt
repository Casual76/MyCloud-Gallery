package dev.pampa.mycloudgallery.core.library.data

import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.library.contract.LocalMediaActions
import dev.pampa.mycloudgallery.core.model.MediaId

/**
 * Preferiti e nascosti sopra `MediaCatalog.setPersonalFlags` (§1.2): una selezione e' **una** transazione e **un**
 * cambiamento, che il repository riapplica all'indice come gli altri.
 *
 * - **La cella e' il gruppo** (D20): nascondere o mostrare la copertina di una raffica o di una coppia RAW+JPEG vale per
 *   tutti i suoi membri, altrimenti al posto della copertina nascosta comparirebbe il membro successivo (la stessa foto,
 *   un attimo dopo). Il preferito vale per il media della cella.
 * - **Nascondere** (E-H1): prima si tolgono le celle dall'indice e dall'istantanea su file
 *   ([CatalogTimelineRepository.forget]), poi si scrive il catalogo. Se la scrittura fallisce l'indice si ricostruisce
 *   (le celle tornano) e l'errore arriva a chi ha chiesto.
 */
class CatalogLocalMediaActions(
  private val catalog: MediaCatalog,
  private val source: TimelineSource,
  private val repository: CatalogTimelineRepository?,
) : LocalMediaActions {

  override suspend fun setFavorite(ids: Set<MediaId>, on: Boolean) {
    if (ids.isEmpty()) return
    catalog.setPersonalFlags(ids) { it.copy(favorite = on) }
  }

  override suspend fun setHidden(ids: Set<MediaId>, on: Boolean) {
    if (ids.isEmpty()) return
    val all = source.withGroupMates(ids)
    if (on) repository?.forget(all)
    try {
      catalog.setPersonalFlags(all) { it.copy(hidden = on) }
    } catch (e: Exception) {
      if (on) repository?.requestRebuild()
      throw e
    }
  }
}
