package dev.pampa.mycloudgallery.core.search.testing

import dev.pampa.mycloudgallery.core.ai.contract.AiEngineException
import dev.pampa.mycloudgallery.core.ai.contract.TextEmbedder
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.index.IndexOrderedTimelineView
import dev.pampa.mycloudgallery.core.library.noop.EmptyTimelineSnapshot
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.SpaceCode
import dev.pampa.mycloudgallery.core.search.contract.ChipMatching
import dev.pampa.mycloudgallery.core.search.contract.CoverageReason
import dev.pampa.mycloudgallery.core.search.contract.PeopleDictionary
import dev.pampa.mycloudgallery.core.search.contract.PeopleLookup
import dev.pampa.mycloudgallery.core.search.contract.PlaceDictionary
import dev.pampa.mycloudgallery.core.search.contract.QueryParser
import dev.pampa.mycloudgallery.core.search.contract.RankParams
import dev.pampa.mycloudgallery.core.search.contract.ResultOrder
import dev.pampa.mycloudgallery.core.search.contract.ResultRanking
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import dev.pampa.mycloudgallery.core.search.contract.SearchCoverage
import dev.pampa.mycloudgallery.core.search.contract.SearchData
import dev.pampa.mycloudgallery.core.search.contract.SearchEngine
import dev.pampa.mycloudgallery.core.search.contract.SearchNotice
import dev.pampa.mycloudgallery.core.search.contract.SearchReadiness
import dev.pampa.mycloudgallery.core.search.contract.SearchRequest
import dev.pampa.mycloudgallery.core.search.contract.SearchResults
import dev.pampa.mycloudgallery.core.search.contract.SearchText
import dev.pampa.mycloudgallery.core.search.contract.SearchThresholds
import dev.pampa.mycloudgallery.core.search.contract.Today
import dev.pampa.mycloudgallery.core.search.engine.PeopleGate
import dev.pampa.mycloudgallery.core.search.engine.weighedBy
import dev.pampa.mycloudgallery.core.search.noop.NoPeopleLookup
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * L'ESECUTORE DI RIFERIMENTO della ricerca (docs/fase6-progetto.md §5.2): **forza bruta in float32**, le stesse regole di fusione,
 * soglie e ordine del motore vero (D27, D28, D48: `ResultRanking`, `SearchThresholds`, `ChipMatching`). Piccolo e leggibile: P6
 * scrive il motore veloce (indice a due passate, FTS4) e la suite di parita' li confronta su 1.000 query con i motori finti (stessa
 * classe per ogni cella salvo i casi a +-1e-4 dalla soglia, stesso ordine nei due criteri). P8, P9 e P11 lo usano finche' P6 non
 * consegna.
 *
 * Regole: filtri come vincoli; contenuto con la soglia relativa alla query (z = (s - mediana) / (1,4826 x MAD)); testo con tutte
 * le parole (sicuro) o almeno meta' (meno probabile); classe = la migliore delle due; nascosti mai (l'istantanea non li ha e il filtro
 * finale li toglie ancora, D29); senza motore delle query solo filtri e testo (D35).
 *
 * Fase 8 (P10): con [people] un nome e' un chip "Persona" e il chip e' un filtro sulle celle della porta (le sicure fra i risultati, quelle con un volto
 * nella fascia "forse" fra i meno probabili): le stesse regole del motore vero ([PeopleGate]).
 */
class InMemorySearchEngine(
  private val data: SearchData,
  private val embedder: TextEmbedder?,
  private val parser: QueryParser,
  private val today: Today,
  private val places: () -> PlaceDictionary = { PlaceDictionary.EMPTY },
  private val params: RankParams = RankParams(),
  private val people: PeopleLookup = NoPeopleLookup,
) : SearchEngine {

  override val coverage: MutableStateFlow<SearchCoverage> = MutableStateFlow(SearchCoverage.Complete)

  override val readiness: MutableStateFlow<SearchReadiness> =
    MutableStateFlow(if (embedder == null) SearchReadiness.FiltersAndTextOnly(modelMissing = true) else SearchReadiness.Ready)

  private class Cell(val index: Int, val id: MediaId)

  override suspend fun search(request: SearchRequest): SearchResults {
    val snapshot = data.snapshot.value ?: return empty(emptyList(), request.order)
    val parsed = parser.parse(request.text.take(QueryParser.MAX_QUERY_CHARS), today.date(), places(), peopleDictionary(snapshot))
    val chips = request.editedChips ?: parsed.chips
    val wordChips = chips.filterIsInstance<SearchChip.Word>()
    // CE-2: come il motore vero, le parole libere della query restano quando si modifica un chip.
    val words = if (request.editedChips != null) {
      (parsed.words + wordChips.filter { !it.exact }.flatMap { SearchText.words(it.text) }).distinct()
    } else {
      parsed.words
    }
    val exact = if (request.editedChips != null) wordChips.filter { it.exact }.map { it.text } else parsed.exact
    val filters = chips.filter { it.isFilter }
    val freeText = parsed.semantic != null || parsed.words.isNotEmpty()
    if (request.editedChips != null && filters.isEmpty() && !freeText && wordChips.isEmpty()) return empty(chips, request.order)
    val gate = PeopleGate.of(filters, people)
    val plain = filters.filterNot { it is SearchChip.Person }
    val cells = visibleCells(snapshot) { ChipMatching.accepts(plain, snapshot, it) && (gate == null || gate.accepts(snapshot.idAt(it))) }
    val notices = mutableListOf<SearchNotice>()
    parsed.corrections.forEach { (from, to) -> notices += SearchNotice.TypoCorrected(from, to) }
    if (gate != null) people.progressPercent.value?.let { notices += SearchNotice.PeopleRecognizing(it.coerceIn(0, 99)) }

    var scores: Map<MediaId, Float> = emptyMap()
    if (parsed.semantic != null) {
      val query = queryVector(parsed.semantic, notices)
      if (query != null) scores = contentScores(cells, query)
    }
    val z = standardize(scores)
    updateCoverage(cells, scores.size.takeIf { parsed.semantic != null })
    val text = textScores(cells, words, exact)
    val onlyFilters = parsed.semantic == null && words.isEmpty() && exact.isEmpty()

    val confident = mutableListOf<ResultRanking.Ranked>()
    val likely = mutableListOf<ResultRanking.Ranked>()
    for (cell in cells) {
      val day = ChipMatching.dayOf(snapshot.localDayAt(cell.index))
      if (onlyFilters) {
        val ranked = ResultRanking.Ranked(cell.index, 1f, day, snapshot.sortTimeAt(cell.index))
        if (gate != null && !gate.isSure(cell.id)) likely += ranked else confident += ranked
        continue
      }
      val zc = z[cell.id]
      val s = scores[cell.id]
      val t = text[cell.id] ?: 0f
      val contentSure = zc != null && s != null && zc >= SearchThresholds.ZC && s >= SearchThresholds.S_MIN
      val contentLikely = zc != null && zc >= SearchThresholds.ZL
      val ranked = ResultRanking.Ranked(cell.index, ResultRanking.relevance(zc, t), day, snapshot.sortTimeAt(cell.index))
      val soft = gate != null && !gate.isSure(cell.id)
      when {
        (contentSure || t >= 1f) && !soft -> confident += ranked
        contentSure || t >= 1f || contentLikely || t > 0f -> likely += ranked
      }
    }
    val cappedLikely = likely.sortedByDescending { it.relevance }.take(SearchThresholds.MAX_LIKELY)
    val comparator = ResultRanking.comparator(request.order, today.date(), params)
    return SearchResults(
      chips = chips,
      confident = IndexOrderedTimelineView(snapshot, confident.sortedWith(comparator).map { it.index }.toIntArray()),
      likely = IndexOrderedTimelineView(snapshot, cappedLikely.sortedWith(comparator).map { it.index }.toIntArray()),
      coverage = coverage.value,
      notices = notices,
      order = request.order,
      freeText = freeText,
    )
  }

  override suspend fun similar(media: MediaId, order: ResultOrder): SearchResults {
    val snapshot = data.snapshot.value ?: return empty(emptyList(), order)
    val chip = SearchChip.Similar(media, "Simili")
    // Mai dai Nascosti (D30, E-Q14): un media nascosto o fuori dall'istantanea non ha simili.
    if (snapshot.indexOf(media) < 0 || data.hidden(listOf(media)).isNotEmpty()) return empty(listOf(chip), order)
    val own = data.vectors(listOf(media), SpaceCode.CURRENT.space)[media].orEmpty()
    if (own.isEmpty()) return empty(listOf(chip), order)
    val query = normalizedMean(own)
    val cells = visibleCells(snapshot) { snapshot.idAt(it) != media }
    val scores = contentScores(cells, query)
    val z = standardize(scores)
    val confident = mutableListOf<ResultRanking.Ranked>()
    val likely = mutableListOf<ResultRanking.Ranked>()
    for (cell in cells) {
      val zc = z[cell.id] ?: continue
      val day = ChipMatching.dayOf(snapshot.localDayAt(cell.index))
      val ranked = ResultRanking.Ranked(cell.index, ResultRanking.relevance(zc, 0f), day, snapshot.sortTimeAt(cell.index))
      when {
        zc >= SearchThresholds.ZS -> confident += ranked
        zc >= SearchThresholds.ZLS -> likely += ranked
      }
    }
    val comparator = ResultRanking.comparator(order, today.date(), params)
    return SearchResults(
      chips = listOf(chip),
      confident = IndexOrderedTimelineView(snapshot, confident.sortedWith(comparator).map { it.index }.toIntArray()),
      likely = IndexOrderedTimelineView(snapshot, likely.sortedByDescending { it.relevance }.take(SearchThresholds.MAX_LIKELY).sortedWith(comparator).map { it.index }.toIntArray()),
      coverage = coverage.value,
      notices = emptyList(),
      order = order,
    )
  }

  /** Il dizionario dei nomi per l'analizzatore, con il peso delle date dell'istantanea; vuoto dove i volti non ci sono (il nome resta testo). */
  private suspend fun peopleDictionary(snapshot: TimelineSnapshot): PeopleDictionary {
    if (!people.available.value) return PeopleDictionary.EMPTY
    val loaded = try {
      people.dictionary()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return PeopleDictionary.EMPTY
    }
    return loaded.weighedBy(snapshot)
  }

  /** Le celle dell'istantanea che passano [accept], senza i nascosti (il filtro finale, D29). */
  private suspend fun visibleCells(snapshot: TimelineSnapshot, accept: (Int) -> Boolean): List<Cell> {
    val cells = (0 until snapshot.size).filter(accept).map { Cell(it, snapshot.idAt(it)) }
    val hidden = data.hidden(cells.map { it.id })
    return cells.filter { it.id !in hidden }
  }

  private suspend fun queryVector(semantic: String, notices: MutableList<SearchNotice>): FloatArray? {
    if (embedder == null) {
      notices += SearchNotice.OnlyFiltersAndText
      return null
    }
    return try {
      embedder.embed(semantic).values.also { readiness.value = SearchReadiness.Ready }
    } catch (e: AiEngineException) {
      readiness.value = SearchReadiness.FiltersAndTextOnly(modelMissing = false)
      notices += SearchNotice.OnlyFiltersAndText
      null
    }
  }

  /** Il punteggio di contenuto di ogni cella con vettori: il massimo dei suoi vettori (video: dei fotogrammi; gruppo: dei membri). */
  private suspend fun contentScores(cells: List<Cell>, query: FloatArray): Map<MediaId, Float> {
    val vectors = data.vectors(cells.map { it.id }, SpaceCode.CURRENT.space)
    return vectors.mapNotNull { (id, list) -> list.maxOfOrNull { dot(query, it) }?.let { id to it } }.toMap()
  }

  /** z = (s - mediana) / (1,4826 x MAD): la soglia relativa alla query (D27). Una distribuzione piatta non stacca niente. */
  private fun standardize(scores: Map<MediaId, Float>): Map<MediaId, Float> {
    if (scores.size < 2) return emptyMap()
    val sorted = scores.values.sorted()
    val median = median(sorted)
    val mad = median(scores.values.map { kotlin.math.abs(it - median) }.sorted())
    val sigma = SearchThresholds.MAD_SCALE * mad
    if (sigma <= 1e-6f) return scores.mapValues { 0f }
    return scores.mapValues { (_, s) -> (s - median) / sigma }
  }

  /** 1 = tutte le parole (o la frase esatta) lette nel media; 0,6 = almeno meta'; frasi esatte obbligatorie (D27). */
  private suspend fun textScores(cells: List<Cell>, words: List<String>, exact: List<String>): Map<MediaId, Float> {
    if (words.isEmpty() && exact.isEmpty()) return emptyMap()
    val texts = data.texts(cells.map { it.id })
    val wanted = words.map { SearchText.normalize(it) }.filter { it.isNotEmpty() }.distinct()
    val phrases = exact.map { SearchText.normalize(it) }.filter { it.isNotEmpty() }
    val out = HashMap<MediaId, Float>()
    for ((id, raw) in texts) {
      val norm = SearchText.normalize(raw)
      val present = norm.split(' ').toSet()
      if (phrases.any { " $it " !in " $norm " }) continue
      val found = wanted.count { it in present }
      out[id] = when {
        wanted.isEmpty() || found == wanted.size -> 1f
        found * 2 >= wanted.size -> 0.6f
        else -> continue
      }
    }
    return out
  }

  private fun updateCoverage(cells: List<Cell>, withVectors: Int?) {
    if (withVectors == null || cells.isEmpty()) return
    coverage.value = if (withVectors >= cells.size) {
      SearchCoverage.Complete
    } else {
      SearchCoverage.Partial(withVectors, cells.size, mapOf(CoverageReason.PREPARATION to cells.size - withVectors))
    }
  }

  private fun empty(chips: List<SearchChip>, order: ResultOrder): SearchResults {
    val none = IndexOrderedTimelineView(EmptyTimelineSnapshot, IntArray(0))
    return SearchResults(chips, none, none, coverage.value, emptyList(), order)
  }

  private fun median(sorted: List<Float>): Float =
    if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2f

  private fun dot(a: FloatArray, b: FloatArray): Float {
    var sum = 0f
    for (i in a.indices) sum += a[i] * b[i]
    return sum
  }

  private fun normalizedMean(vectors: List<FloatArray>): FloatArray {
    val out = FloatArray(vectors.first().size)
    for (v in vectors) for (i in out.indices) out[i] += v[i]
    val n = kotlin.math.sqrt(out.sumOf { (it * it).toDouble() }).toFloat()
    return if (n == 0f) out else FloatArray(out.size) { out[it] / n }
  }
}
