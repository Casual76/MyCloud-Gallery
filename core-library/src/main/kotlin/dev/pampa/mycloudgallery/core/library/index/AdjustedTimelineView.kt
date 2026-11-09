package dev.pampa.mycloudgallery.core.library.index

import androidx.compose.runtime.Stable
import dev.pampa.mycloudgallery.core.library.contract.FilterExtras
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.model.MediaId

/**
 * Fase grafica (G2b): una vista filtrata con le correzioni del catalogo ([FilterExtras]): le celle di [base], piu' quelle di `plus` che sono
 * nell'indice, meno quelle di `minus`. **Nell'ordine dell'indice**, come ogni vista filtrata (il piu' recente prima): la griglia e il viewer la
 * scorrono come [FilteredTimelineView].
 *
 * Le identita' si cercano nell'indice di [base] (`indexOf`, una ricerca per id): un id che non c'e' (nascosto, membro di un gruppo che non lo
 * rappresenta, sparito) non conta. La mappa si calcola alla prima lettura, O(n + k log k), come quella di [FilteredTimelineView]: la paga chi la
 * usa per primo, di norma il calcolo del layout fuori dal thread principale.
 */
@Stable
class AdjustedTimelineView private constructor(
  private val base: TimelineView,
  private val extras: FilterExtras,
) : TimelineView {

  override val snapshot: TimelineSnapshot get() = base.snapshot
  override val filter: LibraryFilter get() = base.filter

  private val map: IntArray by lazy { buildMap() }

  override val size: Int get() = map.size

  override fun snapshotIndex(viewIndex: Int): Int = map[viewIndex]

  override fun viewIndexOf(id: MediaId): Int {
    val index = snapshot.indexOf(id)
    if (index < 0) return -1
    val found = map.binarySearch(index)
    return if (found >= 0) found else -1
  }

  private fun buildMap(): IntArray {
    val s = base.snapshot
    val plus = extras.plus.mapNotNullTo(ArrayList()) { id -> s.indexOf(id).takeIf { it >= 0 } }.toIntArray().apply { sort() }
    val minus = extras.minus.mapNotNullTo(ArrayList()) { id -> s.indexOf(id).takeIf { it >= 0 } }.toIntArray().apply { sort() }
    val out = IntArray(base.size + plus.size)
    var n = 0
    var p = 0
    var last = -1
    fun take(index: Int) {
      if (index == last) return
      last = index
      if (minus.binarySearch(index) < 0) out[n++] = index
    }
    // Le due liste sono gia' nell'ordine dell'indice: una fusione, senza riordinare tutto.
    for (v in 0 until base.size) {
      val index = base.snapshotIndex(v)
      while (p < plus.size && plus[p] < index) take(plus[p++])
      take(index)
    }
    while (p < plus.size) take(plus[p++])
    return out.copyOf(n)
  }

  override fun toString(): String = "AdjustedTimelineView($filter, $extras, seq=${snapshot.seq})"

  companion object {
    /** [base] con [extras]; senza correzioni e' [base] stessa (nessun costo). */
    fun of(base: TimelineView, extras: FilterExtras): TimelineView = if (extras.isEmpty) base else AdjustedTimelineView(base, extras)
  }
}
