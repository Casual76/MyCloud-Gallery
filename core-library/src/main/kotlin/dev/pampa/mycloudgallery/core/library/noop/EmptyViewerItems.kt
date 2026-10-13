package dev.pampa.mycloudgallery.core.library.noop

import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.contract.ViewerItems
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Nessun elemento da scorrere: il viewer collegato a una Libreria vuota. Chiedere i byte di un id e' un errore di
 * programmazione (non c'e' nessun id), non uno stato dell'interfaccia.
 */
object EmptyViewerItems : ViewerItems {
  override val view: StateFlow<TimelineView> = MutableStateFlow(EmptyTimelineView())

  override fun ref(id: MediaId): MediaRef = throw NoSuchElementException("Nessun elemento nella vista vuota.")
}
