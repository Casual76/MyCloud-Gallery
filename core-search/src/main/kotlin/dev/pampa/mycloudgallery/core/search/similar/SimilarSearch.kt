package dev.pampa.mycloudgallery.core.search.similar

import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.search.contract.ResultOrder
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import dev.pampa.mycloudgallery.core.search.contract.SearchNotice
import dev.pampa.mycloudgallery.core.search.contract.SearchResults
import dev.pampa.mycloudgallery.core.search.contract.SearchThresholds
import dev.pampa.mycloudgallery.core.search.contract.SimilarSelection
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
      map to ContentScorer.score(query, candidates, map, TwoPassIndex(core.index, map), SearchThresholds.ZS, SearchThresholds.ZLS)
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

  /**
   * Correzioni F (TS-4): "Trova simili" da una selezione. Il centroide normalizzato dei media scelti: di ogni cella la media normalizzata dei suoi vettori (come
   * per un media solo), poi la media normalizzata di quelle, cosi' ogni media conta uno anche se una cella e' un video o un gruppo. Una passata sola
   * dell'indice, le stesse soglie. Esclusi tutti i media scelti (e i loro gruppi, che sono le loro celle); mai i Nascosti: un media nascosto, o che non e'
   * una cella dell'istantanea, non entra nella query ne' nel chip. Un media senza vettore e' lasciato fuori e l'avviso lo conta.
   */
  suspend fun similarToAll(media: List<MediaId>, order: ResultOrder): SearchResults {
    require(media.isNotEmpty()) { "Nessun media da cui cercare i simili." }
    val requested = media.distinct().take(SimilarSelection.MAX)
    val snapshot = core.data.snapshot.value ?: return core.empty(listOf(chipOf(requested.take(1))), order)
    core.prepare()
    val hidden = core.data.hidden(requested)
    val used = requested.filter { it !in hidden && snapshot.indexOf(it) >= 0 }
    // Il chip porta solo cio' che si e' usato davvero: un nascosto non finisce mai in una richiesta che la UI riconosce.
    val chip = chipOf(used.ifEmpty { requested.take(1) })
    if (used.isEmpty()) return core.empty(listOf(chip), order)
    val positions = used.map { snapshot.indexOf(it) }
    val found = core.withMap(snapshot) { map ->
      val cells = positions.map { map.slots.copyOfRange(map.start[it], map.start[it + 1]) }
      val query = queryOf(cells) ?: return@withMap null
      val excluded = positions.toHashSet()
      val candidates = IntArray(snapshot.size - excluded.size)
      var next = 0
      for (position in 0 until snapshot.size) if (position !in excluded) candidates[next++] = position
      Triple(map, query, ContentScorer.score(query.vector, candidates, map, TwoPassIndex(core.index, map), SearchThresholds.ZS, SearchThresholds.ZLS))
    }
    val notices = ArrayList<SearchNotice>()
    if (found == null) {
      notices += SearchNotice.SimilarWithoutVectors(used.size)
      return core.empty(listOf(chip), order, notices)
    }
    val (map, query, content) = found
    if (query.cellsWithVector < used.size) notices += SearchNotice.SimilarWithoutVectors(used.size - query.cellsWithVector)
    val scored = content.positions.indices.sortedBy { content.positions[it] }.map {
      Scored(content.positions[it], content.z[it], content.scores[it], 0f, content.members[it])
    }
    return core.finish(
      snapshot, map, listOf(chip), scored, onlyFilters = false,
      thresholds = Thresholds(SearchThresholds.ZS, SearchThresholds.ZLS, Float.NEGATIVE_INFINITY), order = order, notices = notices,
    )
  }

  private fun chipOf(media: List<MediaId>): SearchChip.Similar = SearchChip.Similar(media.first(), LABEL, media.drop(1))

  /** Il centroide normalizzato (e quante celle hanno almeno un vettore sano): di ogni cella la media normalizzata, poi la media normalizzata di quelle. */
  private class SetQuery(val vector: FloatArray, val cellsWithVector: Int)

  private fun queryOf(cells: List<IntArray>): SetQuery? {
    val sum = FloatArray(core.index.dimension)
    var used = 0
    for (slots in cells) {
      val one = queryOf(slots) ?: continue
      for (i in sum.indices) sum[i] += one[i]
      used++
    }
    if (used == 0) return null
    val norm = sqrt(sum.sumOf { (it * it).toDouble() }).toFloat()
    return SetQuery(if (norm == 0f) sum else FloatArray(sum.size) { sum[it] / norm }, used)
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
