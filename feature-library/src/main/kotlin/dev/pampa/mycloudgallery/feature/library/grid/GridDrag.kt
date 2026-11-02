package dev.pampa.mycloudgallery.feature.library.grid

import dev.pampa.mycloudgallery.core.designsystem.adaptive.DragPointer
import dev.pampa.mycloudgallery.core.designsystem.adaptive.DragSubject
import dev.pampa.mycloudgallery.core.designsystem.adaptive.DragTouch

// Fase 10 (P6, D20, E-T12, E-T18): COSA SI TRASCINA da una griglia verso un'altra app. Solo la scelta della sorgente (`Modifier.mediaDragSource` del design system fa il gesto e la porta fa il
// resto): la cella sotto il dito, o la **selezione** se la cella ne fa parte. Decisa **al tocco**, prima della pressione lunga, cosi' e' la selezione di quel momento.
//  - **Tocco e penna**: la pressione lunga su una cella **gia' selezionata** (in modalita' selezione) trascina la selezione; su una cella non selezionata resta quello che e' sempre stata,
//    entrare in selezione (come Galleria di Samsung: si seleziona, poi si tiene premuto per trascinare).
//  - **Mouse**: tasto principale premuto e trascinato su una cella la porta via (da sola se non e' selezionata, altrimenti con la selezione), senza entrare in selezione.
// Mai dai Nascosti: chi non passa `dragOrigin` alla griglia non ha trascinamento (E-T18).

/** La sorgente del trascinamento per il tocco [touch] sulla griglia di [state], o null (niente da trascinare da li'). */
internal fun gridDragSubject(state: MediaGridState, touch: DragTouch): DragSubject? {
  if (state.pinching) return null
  val hit = state.positionAt(touch.position)
  if (hit < 0) return null
  val id = state.idAt(hit) ?: return null
  val selected = state.selection.selected.value.ids
  val ids = when {
    id in selected -> {
      // L'ordine della vista (dal piu' recente), non quello in cui si e' selezionato: il trascinamento e' lo stesso ogni volta.
      val view = state.view
      selected.map { it to view.viewIndexOf(it) }.filter { it.second >= 0 }.sortedBy { it.second }.map { it.first.value }
    }
    touch.pointer == DragPointer.MOUSE && !state.selecting -> listOf(id.value)
    else -> return null
  }
  if (ids.isEmpty()) return null
  // L'ombra e' la miniatura locale della cella premuta: la stessa bitmap che la cella ha in memoria (mai una lettura del file).
  return DragSubject(ids, state.loader.peek(state.thumbKey(id)))
}
