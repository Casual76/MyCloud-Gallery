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

  /** Il filo sottile fra i sicuri e i meno probabili, senza parole: l'intestazione sta in cima ai meno probabili ([LikelyHeader]). */
  data object LikelyBoundary : GridEntry {
    override val key: String get() = "likely-boundary"
  }

  /** L'intestazione dei meno probabili, «Meno probabili · N risultati»: **sopra** le loro celle, come si legge (CU-3). */
  data class LikelyHeader(val count: Int) : GridEntry {
    override val key: String get() = "likely-header"
  }
}

/** Dalla vista dei risultati alle voci della griglia: pura e senza Compose, quindi si prova su JVM. */
internal object SearchGridEntries {

  /**
   * Le voci dei risultati, **solo per pertinenza** e **senza intestazioni di data** (H4), nell'ordine della griglia che cresce dal basso: prima i sicuri
   * (il primo e' il piu' adatto e sta subito sopra la barra di ricerca), poi il filo e i meno probabili, lontani dalla barra, e **per ultima** la loro
   * intestazione, che nella griglia rovesciata sta **sopra** di loro, dove si legge un titolo (CU-3 / CS-2 del giro 1: prima stava sotto, attaccata ai
   * sicuri, e sembrava il loro titolo).
   */
  fun build(results: SearchScreenState.Results): List<GridEntry> {
    val out = ArrayList<GridEntry>(results.confident.size + results.likely.size + 2)
    addCells(out, ResultSection.CONFIDENT, results.confident)
    if (results.likely.size > 0) {
      if (results.confident.size > 0) out += GridEntry.LikelyBoundary
      addCells(out, ResultSection.LIKELY, results.likely)
      out += GridEntry.LikelyHeader(results.likely.size)
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
