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
 * tornando indietro l'intervallo si restringe: le celle che escono dall'intervallo tornano **come erano prima del
 * trascinamento**. Il modello non sa niente di gesti, di righe o di scorrimento automatico: quelli sono della griglia
 * (P7); qui c'e' solo l'insieme.
 *
 * Implementazione di P5: a ogni [dragTo] si toccano solo le celle entrate o uscite dall'intervallo (non tutto
 * l'intervallo), e una posizione uguale alla precedente non emette niente. Si usa dal thread principale.
 */
class SelectionModel {

  private val state = MutableStateFlow(SelectionSet())

  /** L'insieme selezionato adesso. */
  val selected: StateFlow<SelectionSet> = state.asStateFlow()

  /** Vero se c'e' qualcosa di selezionato (la barra delle azioni prende il posto della pillola). */
  val isActive: Boolean get() = !state.value.isEmpty

  /** Vero durante un trascinamento. */
  val isDragging: Boolean get() = dragIds != null

  private var dragIds: PositionIds? = null
  private var dragStart: Int = -1
  private var dragLo: Int = -1
  private var dragHi: Int = -1
  private var dragBase: Set<MediaId> = emptySet()
  private var dragSelects: Boolean = true
  private var working: LinkedHashSet<MediaId> = LinkedHashSet()

  /** Seleziona o deseleziona un elemento (tocco in modalita' selezione). */
  fun toggle(id: MediaId) {
    val current = state.value.ids
    state.value = SelectionSet(if (id in current) current - id else current + id)
  }

  /** Aggiunge [ids] alla selezione ("seleziona il giorno" da un'intestazione). */
  fun selectAll(ids: Collection<MediaId>) {
    if (ids.isEmpty()) return
    state.value = SelectionSet(state.value.ids + ids)
  }

  /** Comincia un trascinamento dalla cella [position]: se era selezionata il trascinamento deseleziona. */
  fun beginDrag(position: Int, ids: PositionIds) {
    dragIds = ids
    dragStart = position
    dragBase = state.value.ids
    working = LinkedHashSet(dragBase)
    val id = ids.idAt(position)
    dragSelects = id !in dragBase
    dragLo = position
    dragHi = position
    mark(id, inRange = true)
    emit()
  }

  /** Il dito e' ora sulla cella [position]. */
  fun dragTo(position: Int) {
    val ids = dragIds ?: return
    val lo = min(dragStart, position)
    val hi = max(dragStart, position)
    if (lo == dragLo && hi == dragHi) return
    // Escono dall'intervallo: tornano come prima del trascinamento.
    for (p in dragLo until lo) mark(ids.idAt(p), inRange = false)
    for (p in hi + 1..dragHi) mark(ids.idAt(p), inRange = false)
    // Entrano: seguono la prima cella.
    for (p in lo until dragLo) mark(ids.idAt(p), inRange = true)
    for (p in dragHi + 1..hi) mark(ids.idAt(p), inRange = true)
    dragLo = lo
    dragHi = hi
    emit()
  }

  /** Il dito si alza: la selezione resta com'e'. */
  fun endDrag() {
    dragIds = null
    dragStart = -1
    dragLo = -1
    dragHi = -1
    dragBase = emptySet()
    working = LinkedHashSet()
  }

  fun clear() {
    endDrag()
    state.value = SelectionSet()
  }

  /** Tiene solo gli elementi per cui [keep] e' vero (dopo un cambiamento dell'indice: un elemento sparito o nascosto). */
  fun retain(keep: (MediaId) -> Boolean) {
    val current = state.value.ids
    val kept = current.filterTo(LinkedHashSet(), keep)
    if (kept.size != current.size) state.value = SelectionSet(kept)
  }

  private fun mark(id: MediaId, inRange: Boolean) {
    val selected = if (inRange) dragSelects else id in dragBase
    if (selected) working.add(id) else working.remove(id)
  }

  private fun emit() {
    state.value = SelectionSet(LinkedHashSet(working))
  }
}
