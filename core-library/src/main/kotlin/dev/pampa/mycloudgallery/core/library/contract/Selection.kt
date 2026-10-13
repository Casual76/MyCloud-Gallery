package dev.pampa.mycloudgallery.core.library.contract

import dev.pampa.mycloudgallery.core.model.MediaId
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Da una posizione di lettura della griglia all'identita' dell'elemento: la selezione non conosce ne' il layout ne' la
 * vista, le basta sapere chi sta in una posizione.
 */
fun interface PositionIds {
  fun idAt(position: Int): MediaId
}

/**
 * La selezione a trascinamento (docs/fase3-progetto.md §2.4, "Selezione"): "Seleziona", oppure pressione lunga e
 * trascinamento. **La prima cella del trascinamento decide se si seleziona o si deseleziona** (come in Foto): se
 * era gia' selezionata, il trascinamento deseleziona tutte quelle che attraversa.
 *
 * Il trascinamento seleziona l'intervallo fra la prima cella e quella sotto il dito, in qualunque direzione, e
 * tornando indietro l'intervallo si restringe (si calcola sempre dalla selezione di partenza). Il modello non sa
 * niente di gesti, di righe o di scorrimento automatico: quelli sono della griglia (P7); qui c'e' solo l'insieme.
 *
 * Questa e' l'implementazione di riferimento di P0 (e' piccola e priva di dipendenze): P5 la possiede, la prova e
 * puo' ottimizzarla senza cambiare l'API.
 */
class SelectionModel {

  private val state = MutableStateFlow(SelectionSet())

  /** L'insieme selezionato adesso. */
  val selected: StateFlow<SelectionSet> = state.asStateFlow()

  /** Vero se c'e' qualcosa di selezionato (la barra delle azioni prende il posto della pillola). */
  val isActive: Boolean get() = !state.value.isEmpty

  private var dragIds: PositionIds? = null
  private var dragStart: Int = -1
  private var dragBase: Set<MediaId> = emptySet()
  private var dragSelects: Boolean = true

  /** Seleziona o deseleziona un elemento (tocco in modalita' selezione). */
  fun toggle(id: MediaId) {
    val current = state.value.ids
    state.value = SelectionSet(if (id in current) current - id else current + id)
  }

  /** Comincia un trascinamento dalla cella [position]: se era selezionata il trascinamento deseleziona. */
  fun beginDrag(position: Int, ids: PositionIds) {
    dragIds = ids
    dragStart = position
    dragBase = state.value.ids
    dragSelects = ids.idAt(position) !in dragBase
    apply(position)
  }

  /** Il dito e' ora sulla cella [position]. */
  fun dragTo(position: Int) {
    if (dragIds != null) apply(position)
  }

  /** Il dito si alza: la selezione resta com'e'. */
  fun endDrag() {
    dragIds = null
    dragStart = -1
    dragBase = emptySet()
  }

  fun clear() {
    endDrag()
    state.value = SelectionSet()
  }

  private fun apply(position: Int) {
    val ids = dragIds ?: return
    val from = min(dragStart, position)
    val to = max(dragStart, position)
    val range = (from..to).mapTo(LinkedHashSet()) { ids.idAt(it) }
    state.value = SelectionSet(if (dragSelects) dragBase + range else dragBase - range)
  }
}
