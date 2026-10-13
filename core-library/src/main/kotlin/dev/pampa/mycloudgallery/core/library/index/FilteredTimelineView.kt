package dev.pampa.mycloudgallery.core.library.index

import androidx.compose.runtime.Stable
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.model.MediaId

/**
 * Una vista filtrata (D16) di un indice: la mappa dalle posizioni della vista a quelle dell'indice, in un `IntArray`.
 * Senza filtro la mappa e' l'identita' e non costa niente.
 *
 * La mappa si calcola **alla prima lettura** (O(n), circa un millisecondo per 100 mila elementi su un indice a colonne):
 * la paga chi la usa per primo, di norma il calcolo dei layout fuori dal thread principale, non chi pubblica la vista.
 */
@Stable
class FilteredTimelineView(
  override val snapshot: TimelineSnapshot,
  override val filter: LibraryFilter,
) : TimelineView {

  private val map: IntArray? by lazy { if (filter == LibraryFilter.ALL) null else buildMap() }

  override val size: Int get() = map?.size ?: snapshot.size

  override fun snapshotIndex(viewIndex: Int): Int {
    val m = map ?: return viewIndex.also { if (it !in 0 until snapshot.size) throw IndexOutOfBoundsException("Fuori dalla vista.") }
    return m[viewIndex]
  }

  override fun viewIndexOf(id: MediaId): Int {
    val index = snapshot.indexOf(id)
    if (index < 0) return -1
    val m = map ?: return index
    val found = m.binarySearch(index)
    return if (found >= 0) found else -1
  }

  /** La posizione nella vista della prima posizione dell'indice >= [snapshotIndex] (per ritrovare un vicino, E-V6). */
  fun viewIndexAtOrAfter(snapshotIndex: Int): Int {
    val m = map ?: return snapshotIndex.coerceIn(0, size)
    val found = m.binarySearch(snapshotIndex)
    return if (found >= 0) found else -(found + 1)
  }

  private fun buildMap(): IntArray {
    val s = snapshot
    val out = IntArray(s.size)
    var n = 0
    if (s is ColumnarTimelineSnapshot) {
      val kinds = ColumnarTimelineSnapshot.KINDS
      val sources = ColumnarTimelineSnapshot.SOURCES
      for (i in 0 until s.size) {
        if (filter.accepts(kinds[s.kind[i].toInt()], sources[s.source[i].toInt()], s.flags[i])) out[n++] = i
      }
    } else {
      for (i in 0 until s.size) if (filter.accepts(s.kindAt(i), s.sourceAt(i), s.flagsAt(i))) out[n++] = i
    }
    return out.copyOf(n)
  }

  override fun toString(): String = "FilteredTimelineView($filter, seq=${snapshot.seq})"
}
