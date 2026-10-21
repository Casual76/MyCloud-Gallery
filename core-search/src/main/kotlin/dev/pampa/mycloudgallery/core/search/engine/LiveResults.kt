package dev.pampa.mycloudgallery.core.search.engine

import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.index.IndexOrderedTimelineView
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.search.contract.SearchData
import dev.pampa.mycloudgallery.core.search.contract.SearchResults
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

/**
 * LA VISTA ORDINATA VIVA (E-Q13, D28): i risultati seguono l'istantanea della Libreria. A ogni istantanea nuova le due viste si
 * rifanno con gli stessi media **nello stesso ordine**, saltando quelli che l'istantanea non ha piu' (nascosti o eliminati altrove)
 * e, per sicurezza, quelli che il catalogo dice nascosti adesso (D29). Il viewer aperto su un risultato continua a scorrere la vista
 * nuova; la cella sparita non c'e' piu'.
 */
object LiveResults {

  fun follow(results: SearchResults, data: SearchData): Flow<SearchResults> {
    val confident = ids(results.confident)
    val likely = ids(results.likely)
    return data.snapshot.filterNotNull().distinctUntilChanged { a, b -> a === b }.map { snapshot ->
      if (snapshot === results.confident.snapshot) return@map results
      val hidden = data.hidden(confident + likely)
      results.copy(confident = rebuild(snapshot, confident, hidden), likely = rebuild(snapshot, likely, hidden))
    }
  }

  private fun ids(view: OrderedTimelineView): List<MediaId> = (0 until view.size).map { view.snapshot.idAt(view.snapshotIndex(it)) }

  private fun rebuild(snapshot: TimelineSnapshot, ids: List<MediaId>, hidden: Set<MediaId>): OrderedTimelineView =
    IndexOrderedTimelineView.of(snapshot, ids.filter { it !in hidden })
}
