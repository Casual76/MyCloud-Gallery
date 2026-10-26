package dev.pampa.mycloudgallery.core.search.engine

import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.index.IndexOrderedTimelineView
import dev.pampa.mycloudgallery.core.library.noop.EmptyTimelineSnapshot
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.search.contract.RankParams
import dev.pampa.mycloudgallery.core.search.contract.ResultOrder
import dev.pampa.mycloudgallery.core.search.contract.ResultRanking
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import dev.pampa.mycloudgallery.core.search.contract.SearchData
import dev.pampa.mycloudgallery.core.search.contract.SearchNotice
import dev.pampa.mycloudgallery.core.search.contract.SearchResults
import dev.pampa.mycloudgallery.core.search.contract.Today
import dev.pampa.mycloudgallery.core.search.coverage.CoverageCounts
import dev.pampa.mycloudgallery.core.search.coverage.CoverageTracker
import dev.pampa.mycloudgallery.core.search.index.CellKeys
import dev.pampa.mycloudgallery.core.search.index.CellMap
import dev.pampa.mycloudgallery.core.search.index.LocalAiIndex
import kotlin.concurrent.read

/** Una cella con i suoi punteggi: contenuto ([z], [s], dal membro [member]) e testo ([t]: 1, 0,6 o 0). */
internal class Scored(val position: Int, val z: Float?, val s: Float?, val t: Float, val member: Int)

/** Le soglie di una classifica: la ricerca (Zc, Zl, Smin) o "Trova simili" (Zs, Zls). */
internal class Thresholds(val sure: Float, val likely: Float, val minScore: Float)

/**
 * Cio' che ricerca e "Trova simili" hanno in comune: la mappa delle celle (ricostruita solo quando cambiano istantanea, celle o
 * indice), la copertura, il **filtro finale dei nascosti** (D29) e le viste ordinate.
 */
internal class SearchCore(
  val data: SearchData,
  val index: LocalAiIndex,
  val keys: CellKeys,
  val today: Today,
  val params: RankParams,
  val coverage: CoverageTracker,
  private val currentRecipe: Int,
) {
  @Volatile private var cached: CellMap? = null
  @Volatile private var coverageFor: CellMap? = null

  /** Le celle nuove o cambiate, i record rovinati da riparare: prima di ogni ricerca (poco, se niente e' cambiato). */
  suspend fun prepare() {
    keys.sync()
    if (index.needsRepair) index.repairPending()
  }

  /** [block] con la mappa valida per [snapshot], sotto il lucchetto dell'indice in lettura (senza sospendersi). */
  fun <T> withMap(snapshot: TimelineSnapshot, block: (CellMap) -> T): T = index.lock.read { block(mapFor(snapshot)) }

  private fun mapFor(snapshot: TimelineSnapshot): CellMap {
    val c = cached
    if (c != null && c.snapshot === snapshot && c.indexVersion == index.version.value && c.keysVersion == keys.version) return c
    val contents = if (c != null && c.snapshot === snapshot && c.keysVersion == keys.version) c.contents else null
    return CellMap.build(snapshot, keys, index, contents).also { cached = it }
  }

  /** La copertura, ricalcolata solo se la mappa e' nuova (D31). Conteggi sull'istantanea: mai i nascosti. */
  suspend fun refreshCoverage(snapshot: TimelineSnapshot) {
    val counts = withMap(snapshot) { map ->
      if (coverageFor === map) return@withMap null
      coverageFor = map
      var withVector = 0
      var current = 0
      var withText = 0
      val missing = ArrayList<MediaId>()
      for (i in 0 until map.size) {
        if (map.hasVector(i)) {
          withVector++
          if (map.recipe(i, index) >= currentRecipe) current++
        } else {
          missing += snapshot.idAt(i)
        }
        if (map.textKeys(i, index).isNotEmpty()) withText++
      }
      CoverageCounts(map.size, withVector, current, withText, missing)
    }
    if (counts != null) coverage.update(counts)
  }

  /**
   * Classi, filtro finale dei nascosti e ordine (D27, D28, D29, D48). [scored] sono le celle con un punteggio (o tutte, solo filtri:
   * [onlyFilters]); una cella sta fra i **sicuri** se il contenuto supera la soglia alta (e il minimo assoluto) o il testo ha tutte le
   * parole, fra i **meno probabili** se il contenuto supera la soglia bassa o il testo almeno meta'.
   *
   * Prima di ordinare si chiede quali celle **e quali membri** che hanno dato il punteggio sono nascosti adesso: una cella nascosta
   * esce; un membro nascosto non porta il suo contenuto (la cella resta solo per il testo) e la sua cella si rilegge.
   */
  suspend fun finish(
    snapshot: TimelineSnapshot,
    map: CellMap,
    chips: List<SearchChip>,
    scored: List<Scored>,
    onlyFilters: Boolean,
    thresholds: Thresholds,
    order: ResultOrder,
    notices: List<SearchNotice>,
    freeText: Boolean = false,
  ): SearchResults {
    val day = today.date()
    fun classify(c: Scored): Int {
      if (onlyFilters) return SURE
      val sure = c.z != null && c.s != null && c.z >= thresholds.sure && c.s >= thresholds.minScore
      val likely = c.z != null && c.z >= thresholds.likely
      return when {
        sure || c.t >= 1f -> SURE
        likely || c.t > 0f -> LIKELY
        else -> OUT
      }
    }
    val inResults = scored.filter { classify(it) != OUT }
    val ids = HashMap<MediaId, Int>(inResults.size * 2)
    val memberIds = HashMap<MediaId, Int>()
    for (c in inResults) {
      ids[snapshot.idAt(c.position)] = c.position
      if (c.member > 0) map.memberId(c.position, c.member)?.let { memberIds[it] = c.position }
    }
    val hidden = if (ids.isEmpty()) emptySet() else data.hidden(ids.keys + memberIds.keys)
    val hiddenCells = hidden.mapNotNullTo(HashSet()) { ids[it] }
    val hiddenContent = hidden.mapNotNullTo(HashSet()) { memberIds[it] }
    if (hiddenContent.isNotEmpty()) keys.invalidate(hidden.filter { it in memberIds })
    val confident = ArrayList<ResultRanking.Ranked>()
    val likely = ArrayList<ResultRanking.Ranked>()
    for (c0 in inResults) {
      if (c0.position in hiddenCells) continue
      val c = if (c0.position in hiddenContent) Scored(c0.position, null, null, c0.t, -1) else c0
      val ranked = Ranking.ranked(snapshot, c.position, if (onlyFilters) 1f else ResultRanking.relevance(c.z, c.t))
      when (classify(c)) {
        SURE -> confident += ranked
        LIKELY -> likely += ranked
        else -> Unit
      }
    }
    return SearchResults(
      chips = chips,
      confident = IndexOrderedTimelineView(snapshot, Ranking.order(confident, order, day, params)),
      likely = IndexOrderedTimelineView(snapshot, Ranking.order(Ranking.capLikely(likely), order, day, params)),
      coverage = coverage.state.value,
      notices = notices,
      order = order,
      freeText = freeText,
    )
  }

  fun empty(chips: List<SearchChip>, order: ResultOrder, notices: List<SearchNotice> = emptyList(), freeText: Boolean = false): SearchResults {
    val none = IndexOrderedTimelineView(EmptyTimelineSnapshot, IntArray(0))
    return SearchResults(chips, none, none, coverage.state.value, notices, order, freeText)
  }

  private companion object {
    const val SURE = 2
    const val LIKELY = 1
    const val OUT = 0
  }
}
