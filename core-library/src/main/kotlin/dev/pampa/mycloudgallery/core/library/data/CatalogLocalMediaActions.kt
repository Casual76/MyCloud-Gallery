package dev.pampa.mycloudgallery.core.library.data

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.library.contract.FamilyExposure
import dev.pampa.mycloudgallery.core.library.contract.LocalMediaActions
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlin.coroutines.cancellation.CancellationException

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
 * - **Avviso E-H2** (Fase 7, P9): [familyStillSees] dice quanti degli elementi nascosti i familiari vedono ancora (area comune, album
 *   condivisi). Senza [exposure] (le Librerie senza NAS) non c'e' nessuno da avvisare.
 */
class CatalogLocalMediaActions(
  private val catalog: MediaCatalog,
  private val source: TimelineSource,
  private val repository: CatalogTimelineRepository?,
  private val exposure: FamilyExposure = FamilyExposure.None,
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

  /**
   * Quanti di [ids] hanno una copia che i familiari vedono (E-H2). La cella e' il gruppo: se a essere visibile ai familiari e' un altro
   * membro del gruppo, la cella conta lo stesso (almeno una). **Nel dubbio si avvisa**: se la lettura non riesce si risponde "tutti", perche'
   * un avviso in meno lascerebbe credere che nessuno li veda.
   */
  override suspend fun familyStillSees(ids: Set<MediaId>): Int {
    if (ids.isEmpty()) return 0
    return try {
      val seen = exposure.seenByFamily(source.withGroupMates(ids))
      if (seen.isEmpty()) 0 else ids.count { it in seen }.coerceAtLeast(1)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.w(TAG, e) { "Esposizione ai familiari non letta: si avvisa lo stesso." }
      ids.size
    }
  }

  private companion object {
    const val TAG = "LocalMediaActions"
  }
}
