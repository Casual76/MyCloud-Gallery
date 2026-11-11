package dev.pampa.mycloudgallery.feature.search

import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.library.index.IndexOrderedTimelineView
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.search.contract.SearchScreenState

/** Le due sezioni dei risultati (D27, D28): i sicuri e i meno probabili, tutte e due per pertinenza. */
internal enum class ResultSection { CONFIDENT, LIKELY }

/** Una voce della griglia dei risultati, dal **piu' adatto** al meno adatto. [key] e' stabile (nessuna cella cambia posto fra due composizioni uguali). */
internal sealed interface GridEntry {
  val key: String

  /** Una cella: la posizione [viewIndex] nella vista della sezione. La chiave e' il media: lo stesso media non sta in due sezioni. */
  data class Cell(val section: ResultSection, val viewIndex: Int, val media: MediaId) : GridEntry {
    override val key: String get() = media.value
  }

  /** Il separatore leggero prima dei meno probabili (H4): «Meno probabili», in coda ai risultati. */
  data class LikelyDivider(val count: Int) : GridEntry {
    override val key: String get() = "likely-divider"
  }
}

/** Dalla vista dei risultati alle voci della griglia: pura e senza Compose, quindi si prova su JVM. */
internal object SearchGridEntries {

  /**
   * Le voci dei risultati, **solo per pertinenza** e **senza intestazioni di data** (H4): prima i sicuri, poi il separatore e i meno probabili. Il
   * primo e' il piu' adatto: la griglia lo mette subito sopra la barra di ricerca.
   */
  fun build(results: SearchScreenState.Results): List<GridEntry> {
    val out = ArrayList<GridEntry>(results.confident.size + results.likely.size + 1)
    addCells(out, ResultSection.CONFIDENT, results.confident)
    if (results.likely.size > 0) {
      out += GridEntry.LikelyDivider(results.likely.size)
      addCells(out, ResultSection.LIKELY, results.likely)
    }
    return out
  }

  private fun addCells(out: MutableList<GridEntry>, section: ResultSection, view: OrderedTimelineView) {
    for (i in 0 until view.size) out += GridEntry.Cell(section, i, view.snapshot.idAt(view.snapshotIndex(i)))
  }

  /**
   * La vista con cui si apre il viewer: i sicuri e poi i meno probabili, **nell'ordine che si vede** (§2.6: "scorre i risultati nell'ordine mostrato").
   * Lo stesso istante dell'istantanea di partenza: la vista viva (E-Q13) la rifa' il motore.
   */
  fun openingView(results: SearchScreenState.Results): OrderedTimelineView {
    if (results.likely.size == 0) return results.confident
    val ids = ArrayList<MediaId>(results.confident.size + results.likely.size)
    for (view in listOf(results.confident, results.likely)) {
      for (i in 0 until view.size) ids += view.snapshot.idAt(view.snapshotIndex(i))
    }
    return IndexOrderedTimelineView.of(results.confident.snapshot, ids)
  }
}
