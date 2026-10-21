package dev.pampa.mycloudgallery.core.search.similar

import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.search.contract.ResultOrder
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import dev.pampa.mycloudgallery.core.search.contract.SearchResults
import dev.pampa.mycloudgallery.core.search.contract.SearchThresholds
import dev.pampa.mycloudgallery.core.search.engine.ContentScorer
import dev.pampa.mycloudgallery.core.search.engine.Scored
import dev.pampa.mycloudgallery.core.search.engine.SearchCore
import dev.pampa.mycloudgallery.core.search.engine.Thresholds
import dev.pampa.mycloudgallery.core.search.index.HalfTable
import dev.pampa.mycloudgallery.core.search.index.TwoPassIndex
import kotlin.math.sqrt

/**
 * "TROVA SIMILI" (D30, E-Q14): il vettore del media come query (la media normalizzata dei suoi vettori: i fotogrammi di un video, i
 * membri del suo gruppo), lo stesso indice a due passate, le soglie sue (`ZS`, `ZLS`: le distribuzioni immagine-immagine sono
 * diverse), esclusa la sua cella (che e' anche il suo gruppo). Mai dai Nascosti: un media nascosto (o che non e' una cella
 * dell'istantanea) non ha simili; un media senza vettore nemmeno (la UI lo spiega). L'ordine e' quello scelto (D48).
 */
internal class SimilarSearch(private val core: SearchCore) {

  suspend fun similar(media: MediaId, order: ResultOrder): SearchResults {
    val chip = SearchChip.Similar(media, LABEL)
    val snapshot = core.data.snapshot.value ?: return core.empty(listOf(chip), order)
    core.prepare()
    val position = snapshot.indexOf(media)
    if (position < 0 || core.data.hidden(listOf(media)).isNotEmpty()) return core.empty(listOf(chip), order)
    val found = core.withMap(snapshot) { map ->
      val query = queryOf(map.slots.copyOfRange(map.start[position], map.start[position + 1])) ?: return@withMap null
      val candidates = IntArray(snapshot.size - 1) { if (it < position) it else it + 1 }
      map to ContentScorer.score(query, candidates, map, TwoPassIndex(core.index, map), SearchThresholds.ZLS)
    } ?: return core.empty(listOf(chip), order)
    val (map, content) = found
    val scored = content.positions.indices.sortedBy { content.positions[it] }.map {
      Scored(content.positions[it], content.z[it], content.scores[it], 0f, content.members[it])
    }
    return core.finish(
      snapshot, map, listOf(chip), scored, onlyFilters = false,
      thresholds = Thresholds(SearchThresholds.ZS, SearchThresholds.ZLS, Float.NEGATIVE_INFINITY), order = order, notices = emptyList(),
    )
  }

  /** La media normalizzata dei vettori sani degli [slots] (sotto il lucchetto dell'indice), o null se non ce n'e'. */
  private fun queryOf(slots: IntArray): FloatArray? {
    val index = core.index
    val sum = FloatArray(index.dimension)
    val scratch = ShortArray(index.dimension)
    var count = 0
    for (slot in slots) {
      if (!index.verify(slot)) continue
      index.file.halves(index.offsets[slot], scratch)
      for (i in sum.indices) sum[i] += HalfTable.of(scratch[i])
      count++
    }
    if (count == 0) return null
    val norm = sqrt(sum.sumOf { (it * it).toDouble() }).toFloat()
    return if (norm == 0f) sum else FloatArray(sum.size) { sum[it] / norm }
  }

  companion object {
    const val LABEL: String = "Simili a"
  }
}
