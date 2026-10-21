package dev.pampa.mycloudgallery.core.library.index

import androidx.compose.runtime.Stable
import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.model.MediaId

/**
 * Fase 6 (P0, D28): l'implementazione di riferimento di [OrderedTimelineView]. [order] sono posizioni dell'indice nell'ordine
 * voluto (i risultati di una ricerca); posizioni fuori dall'indice o ripetute si scartano (una cella compare una volta sola).
 *
 * [viewIndexOf] usa una mappa costruita alla prima lettura (O(n)), mai la ricerca binaria: le posizioni di vista non crescono con
 * quelle dell'indice. Immutabile, come [FilteredTimelineView]: un indice nuovo vuole una vista nuova.
 */
@Stable
class IndexOrderedTimelineView(
  override val snapshot: TimelineSnapshot,
  order: IntArray,
) : OrderedTimelineView {

  private val positions: IntArray = run {
    val seen = BooleanArray(snapshot.size)
    val out = IntArray(order.size)
    var n = 0
    for (index in order) {
      if (index !in 0 until snapshot.size || seen[index]) continue
      seen[index] = true
      out[n++] = index
    }
    out.copyOf(n)
  }

  private val reverse: Map<Int, Int> by lazy {
    val map = HashMap<Int, Int>(positions.size * 2)
    positions.forEachIndexed { view, index -> map[index] = view }
    map
  }

  override val size: Int get() = positions.size

  override fun snapshotIndex(viewIndex: Int): Int {
    if (viewIndex !in positions.indices) throw IndexOutOfBoundsException("Fuori dalla vista.")
    return positions[viewIndex]
  }

  override fun viewIndexOf(id: MediaId): Int {
    val index = snapshot.indexOf(id)
    if (index < 0) return -1
    return reverse[index] ?: -1
  }

  override fun toString(): String = "IndexOrderedTimelineView(${positions.size}, seq=${snapshot.seq})"

  companion object {
    /** La vista nell'ordine di [ids] (gli id che l'indice non ha piu' si saltano: nascosti, eliminati, E-Q13). */
    fun of(snapshot: TimelineSnapshot, ids: List<MediaId>): IndexOrderedTimelineView =
      IndexOrderedTimelineView(snapshot, ids.map { snapshot.indexOf(it) }.filter { it >= 0 }.toIntArray())
  }
}
