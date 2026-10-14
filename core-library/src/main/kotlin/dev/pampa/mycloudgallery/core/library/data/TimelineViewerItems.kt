package dev.pampa.mycloudgallery.core.library.data

import androidx.compose.runtime.Stable
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.contract.ViewerItems
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import kotlinx.coroutines.flow.StateFlow

/**
 * Gli elementi del viewer sopra il repository vero: **la stessa vista** della Libreria (stesso filtro, stessa
 * `StateFlow`), quindi il viewer segue l'indice a ogni cambiamento (modifica esterna, cancellazione: E-V6).
 *
 * [ref] legge il riferimento dall'indice (o dagli elementi appena usciti, per il viewer che li mostra ancora): la copia
 * del telefono se c'e', altrimenti quella del NAS (Fase 4, D18). Se nessuno dei due ha adesso una copia leggibile
 * (E-P22) non c'e' un riferimento: [refOrNull] da' null e [ref] lancia `NoSuchElementException`. La griglia e il viewer
 * usano [refOrNull] e mostrano il segnaposto.
 */
@Stable
class TimelineViewerItems(
  private val repository: CatalogTimelineRepository,
  filter: LibraryFilter,
) : ViewerItems {

  override val view: StateFlow<TimelineView> = repository.view(filter)

  override fun ref(id: MediaId): MediaRef = refOrNull(id) ?: throw NoSuchElementException("Nessuna copia leggibile adesso.")

  override fun refOrNull(id: MediaId): MediaRef? = repository.mediaRefOf(id)
}
