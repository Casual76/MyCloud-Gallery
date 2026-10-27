package dev.pampa.mycloudgallery.core.search.engine

import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.search.contract.ChipMatching
import dev.pampa.mycloudgallery.core.search.contract.PeopleDictionary
import dev.pampa.mycloudgallery.core.search.contract.PeopleLookup
import dev.pampa.mycloudgallery.core.search.contract.QueryParser
import dev.pampa.mycloudgallery.core.search.contract.RankParams
import dev.pampa.mycloudgallery.core.search.contract.ResultOrder
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
import dev.pampa.mycloudgallery.core.search.coverage.CoverageNotices
import dev.pampa.mycloudgallery.core.search.coverage.CoverageTracker
import dev.pampa.mycloudgallery.core.search.index.CellKeys
import dev.pampa.mycloudgallery.core.search.index.LocalAiIndex
import dev.pampa.mycloudgallery.core.search.index.TextRowStore
import dev.pampa.mycloudgallery.core.search.index.TextSearch
import dev.pampa.mycloudgallery.core.search.index.TwoPassIndex
import dev.pampa.mycloudgallery.core.search.noop.NoPeopleLookup
import dev.pampa.mycloudgallery.core.search.parse.ItalianQueryParser
import dev.pampa.mycloudgallery.core.search.similar.SimilarSearch
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.StateFlow

/**
 * IL MOTORE DI RICERCA LOCALE (docs/fase6-progetto.md §2.4, D23-D31, D35, D48). Una query, passo per passo:
 * 1. analisi (P7, [ItalianQueryParser]) con il dizionario dei luoghi **visibili** ([LibraryPlaces]);
 * 2. candidati = le celle dell'istantanea della Libreria (mai nascosti ne' membri di gruppo) che passano i chip ([ChipMatching]);
 * 3. contenuto: vettore della query ([QueryEmbedding]; senza, solo filtri e testo, E-Q11), indice a due passate e soglia relativa
 *    ([ContentScorer]);
 * 4. testo: FTS4 di P1 con i refusi del vocabolario ([TextSearch]), riportato alle celle; un refuso corretto si dice solo se trova
 *    qualcosa fra le celle visibili (il vocabolario ha anche le parole dei nascosti);
 * 5. classi, filtro finale dei nascosti, ordine "Recenti" o "Pertinenti" ([SearchCore.finish]).
 *
 * Fase 8 (P10, D26, §2.7): con la porta [PeopleLookup] il dizionario dei nomi va all'analizzatore (un nome diventa un chip "Persona") e il chip e' un
 * **filtro** sulle celle con un volto assegnato a quelle persone; con "Mostra anche risultati meno probabili" entrano anche le celle con un volto nella
 * fascia "forse", sempre fra i meno probabili ([PeopleGate]). Senza persone (porta di serie) la ricerca e' quella di prima.
 *
 * Mai un'eccezione per un indice incompleto: risponde con cio' che ha e lo dice ([coverage]). Le stesse regole dell'esecutore di
 * riferimento (`InMemorySearchEngine`): la suite di parita' li confronta.
 */
class LocalSearchEngine(
  data: SearchData,
  index: LocalAiIndex,
  keys: CellKeys,
  texts: TextRowStore,
  private val embedding: QueryEmbedding,
  private val today: Today,
  private val places: LibraryPlaces,
  private val parser: QueryParser = ItalianQueryParser(),
  params: RankParams = RankParams(),
  coverageTracker: CoverageTracker = CoverageTracker(),
  currentRecipe: Int = 1,
  /** PR-1: le aree senza indicizzatori e la lettura del testo non attiva, dette sopra i risultati (E-I12, D31). */
  private val coverageNotices: CoverageNotices = CoverageNotices.NONE,
  /** Fase 8 (P10): le persone con un nome per la ricerca; di serie nessuna (il nome resta testo). La lega :app (P13). */
  private val people: PeopleLookup = NoPeopleLookup,
) : SearchEngine {

  private val core = SearchCore(data, index, keys, today, params, coverageTracker, currentRecipe)
  private val text = TextSearch(texts)
  private val similarSearch = SimilarSearch(core)

  init {
    index.textListener = text::learn
  }

  override val coverage: StateFlow<SearchCoverage> get() = core.coverage.state

  override val readiness: StateFlow<SearchReadiness> get() = embedding.readiness

  override fun warmUp() = embedding.preload()

  override suspend fun search(request: SearchRequest): SearchResults {
    val snapshot = core.data.snapshot.value ?: return core.empty(emptyList(), request.order)
    core.prepare()
    val parsed = parser.parse(request.text.take(QueryParser.MAX_QUERY_CHARS), today.date(), places.dictionary(snapshot), peopleDictionary(snapshot))
    val chips = request.editedChips ?: parsed.chips
    val wordChips = chips.filterIsInstance<SearchChip.Word>()
    // CE-2: modificare un chip cambia solo quel filtro. Le parole libere della query non hanno un chip che le porti e restano; le frasi esatte e le
    // parole "come parola" stanno nei chip `Word` (toglierne uno le toglie).
    val words = if (request.editedChips != null) {
      (parsed.words + wordChips.filter { !it.exact }.flatMap { SearchText.words(it.text) }).distinct()
    } else {
      parsed.words
    }
    val exact = if (request.editedChips != null) wordChips.filter { it.exact }.map { it.text } else parsed.exact
    val filters = chips.filter { it.isFilter }
    val freeText = parsed.semantic != null || parsed.words.isNotEmpty()
    // Tolti tutti i chip e senza testo non resta niente da cercare: nessun risultato (non "tutta la Libreria").
    if (request.editedChips != null && filters.isEmpty() && !freeText && wordChips.isEmpty()) return core.empty(chips, request.order, freeText = false)
    val gate = PeopleGate.of(filters, people)
    val candidates = candidates(snapshot, filters, gate)
    val notices = ArrayList<SearchNotice>()
    parsed.corrections.forEach { (from, to) -> notices += SearchNotice.TypoCorrected(from, to) }
    if (gate != null) peopleNotice()?.let { notices += it }

    var content: ContentScores? = null
    if (parsed.semantic != null) {
      // CE-9: il motore delle query non si aspetta: se si sta ancora caricando, filtri e testo rispondono subito e il contenuto arriva dopo.
      val query = embedding.vectorIfLoaded(parsed.semantic)
      if (query == null) {
        notices += if (embedding.readiness.value is SearchReadiness.PreparingContent) SearchNotice.PreparingContentSearch else SearchNotice.OnlyFiltersAndText
      } else {
        content = core.withMap(snapshot) { map -> ContentScorer.score(query, candidates, map, TwoPassIndex(core.index, map), SearchThresholds.ZC, SearchThresholds.ZL) }
      }
    }
    val hits = text.hits(words, exact)
    val onlyFilters = parsed.semantic == null && words.isEmpty() && exact.isEmpty()
    if (!onlyFilters) notices += coverageNoticesFor(words.isNotEmpty() || exact.isNotEmpty())
    val corrections = ArrayList<Pair<SearchNotice, Set<Int>>>()
    val (map, scored) = core.withMap(snapshot) { map ->
      val t = FloatArray(snapshot.size)
      if (hits != null) {
        val isCandidate = BooleanArray(snapshot.size).also { a -> candidates.forEach { a[it] = true } }
        val textIndex = map.textIndex(core.index)
        val keysHit = HashSet(hits.exact.keys).apply { addAll(hits.loose.keys); hits.phrases?.let { addAll(it) } }
        for (key in keysHit) {
          val score = hits.score(key)
          if (score > 0f) textIndex[key]?.forEach { p -> if (isCandidate[p] && score > t[p]) t[p] = score }
        }
        // Un refuso corretto si dice solo se la parola corretta e' in una cella **fra i risultati** (dopo il filtro dei nascosti):
        // il vocabolario contiene anche le parole dei nascosti (D29).
        hits.corrected.forEachIndexed { i, (typed, fixed) ->
          val where = hits.corrections[i].second.flatMapTo(HashSet()) { k -> textIndex[k]?.filter { isCandidate[it] }.orEmpty() }
          if (where.isNotEmpty()) corrections += SearchNotice.TypoCorrected(fixed, typed) to where
        }
      }
      map to buildScored(candidates, content, t, onlyFilters, hits != null)
    }
    core.refreshCoverage(snapshot)
    val results = core.finish(
      snapshot, map, chips, scored, onlyFilters, Thresholds(SearchThresholds.ZC, SearchThresholds.ZL, SearchThresholds.S_MIN),
      request.order, notices.distinct(), freeText, softCells(snapshot, candidates, gate),
    )
    if (corrections.isEmpty()) return results
    val shown = HashSet<Int>()
    for (v in listOf(results.confident, results.likely)) for (i in 0 until v.size) shown += v.snapshotIndex(i)
    val told = corrections.filter { (_, where) -> where.any { it in shown } }.map { it.first }
    return if (told.isEmpty()) results else results.copy(notices = (results.notices + told).distinct())
  }

  override suspend fun similar(media: MediaId, order: ResultOrder): SearchResults = similarSearch.similar(media, order)

  /**
   * PR-1 (E-I12, D31): una ricerca per contenuto o per parole dice le aree che nessun dispositivo analizza (prima l'area comune) e, se si
   * cercano parole, che la lettura del testo non e' attiva (la schermata lo mostra una volta). Un errore qui non ferma la ricerca.
   */
  private suspend fun coverageNoticesFor(withWords: Boolean): List<SearchNotice> {
    val out = ArrayList<SearchNotice>()
    try {
      coverageNotices.areasWithoutIndexer().distinct().sortedDescending().forEach { out += SearchNotice.NoIndexerForArea(it) }
      if (withWords && coverageNotices.textReadingOff()) out += SearchNotice.TextReadingOff
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Gli avvisi sono solo un'informazione: senza, la ricerca risponde lo stesso.
    }
    return out
  }

  /**
   * Le posizioni (crescenti) delle celle che passano i filtri. I chip "Persona" non passano da [ChipMatching] (non si giudicano sulla cella): li
   * decide [gate], con le celle che la porta delle persone da' per quelle persone.
   */
  private fun candidates(snapshot: TimelineSnapshot, filters: List<SearchChip>, gate: PeopleGate?): IntArray {
    val plain = filters.filterNot { it is SearchChip.Person }
    if (plain.isEmpty() && gate == null) return IntArray(snapshot.size) { it }
    val out = IntArray(snapshot.size)
    var n = 0
    for (i in 0 until snapshot.size) {
      if (ChipMatching.accepts(plain, snapshot, i) && (gate == null || gate.accepts(snapshot.idAt(i)))) out[n++] = i
    }
    return out.copyOf(n)
  }

  /** Le celle candidate che hanno solo un volto nella fascia "forse": al meglio "meno probabili", mai tra i sicuri (§2.7). Null senza chip "Persona". */
  private fun softCells(snapshot: TimelineSnapshot, candidates: IntArray, gate: PeopleGate?): BooleanArray? {
    if (gate == null) return null
    val soft = BooleanArray(snapshot.size)
    for (p in candidates) soft[p] = !gate.isSure(snapshot.idAt(p))
    return soft
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

  /** "Sto ancora riconoscendo i volti: 60%" (§2.7) finche' il primo calcolo non e' finito. */
  private fun peopleNotice(): SearchNotice? =
    people.progressPercent.value?.let { SearchNotice.PeopleRecognizing(it.coerceIn(0, 99)) }

  /** Le celle con un punteggio, nell'ordine dell'istantanea (come il riferimento: conta per i pareggi dei meno probabili). */
  private fun buildScored(candidates: IntArray, content: ContentScores?, t: FloatArray, onlyFilters: Boolean, withText: Boolean): List<Scored> {
    if (onlyFilters) return candidates.map { Scored(it, null, null, 0f, -1) }
    val byPosition = HashMap<Int, Int>((content?.size ?: 0) * 2)
    content?.positions?.forEachIndexed { i, p -> byPosition[p] = i }
    val out = ArrayList<Scored>()
    for (p in candidates) {
      val i = byPosition[p]
      val tp = if (withText) t[p] else 0f
      if (i == null && tp == 0f) continue
      out += if (i == null) Scored(p, null, null, tp, -1) else Scored(p, content!!.z[i], content.scores[i], tp, content.members[i])
    }
    return out
  }
}
