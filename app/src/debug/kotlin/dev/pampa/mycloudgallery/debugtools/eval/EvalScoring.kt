package dev.pampa.mycloudgallery.debugtools.eval

import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.SpaceCode
import dev.pampa.mycloudgallery.core.search.contract.ChipMatching
import dev.pampa.mycloudgallery.core.search.contract.MediaText
import dev.pampa.mycloudgallery.core.search.contract.PlaceDictionary
import dev.pampa.mycloudgallery.core.search.contract.QueryParser
import dev.pampa.mycloudgallery.core.search.contract.SearchData
import dev.pampa.mycloudgallery.core.search.contract.SearchText
import dev.pampa.mycloudgallery.core.search.contract.Today
import dev.pampa.mycloudgallery.core.search.engine.QueryEmbedding
import java.time.temporal.ChronoUnit

// LE TABELLE DEI PUNTEGGI dai dati veri o sintetici (§5.3): per una query il punteggio di contenuto (coseno esatto in forza bruta, mediana e
// deviazione robusta, come `InMemorySearchEngine`) e di testo di ogni cella, riportati a chiavi di contenuto. Il motore vero usa la prima
// passata int8 e un campione esatto per le statistiche (D24): qui si usa il coseno esatto di tutte le celle, che e' la verita' di cui il
// motore e' un'approssimazione (parita' provata al 100% dei primi 20 in P6). Stesso file in core-search (test) e in :app (debug).

/**
 * Cio' che serve a costruire le tabelle: il catalogo ([data], di norma `IndexSearchData`), il motore delle query ([embedding]), l'analizzatore,
 * la data di oggi, i luoghi, e la chiave di contenuto della cella di un media ([keyOf]: null se non ne ha una, la cella non si etichetta).
 */
class EvalScoring(
  private val data: SearchData,
  private val embedding: QueryEmbedding,
  private val parser: QueryParser,
  private val today: Today,
  private val places: () -> PlaceDictionary,
  private val keyOf: (MediaId) -> String?,
  /** Da chiamare prima di leggere le chiavi (di norma `CellKeys.sync`): le celle nuove devono avere la loro chiave di contenuto. */
  private val prepare: suspend () -> Unit = {},
  private val batch: Int = 2_000,
) {

  /** La tabella di [query] con le sue [expected]. Senza motore delle query (modello assente) la tabella ha solo il testo, e lo dice [Table.contentAvailable]. */
  suspend fun table(query: EvalQuery, expected: Set<String>): Table {
    prepare()
    val snapshot = data.snapshot.value ?: return Table(ScoreTable(query.id, query.category, expected, emptyList()), contentAvailable = false, cells = 0)
    val day = today.date()
    val parsed = parser.parse(query.text.take(QueryParser.MAX_QUERY_CHARS), day, places())
    val filters = parsed.chips.filter { it.isFilter }
    val positions = (0 until snapshot.size).filter { ChipMatching.accepts(filters, snapshot, it) }
    val hidden = data.hidden(positions.map { snapshot.idAt(it) })
    val cells = positions.filter { snapshot.idAt(it) !in hidden }
    val ids = cells.map { snapshot.idAt(it) }

    val semantic = parsed.semantic
    val vector = if (semantic != null) embedding.vector(semantic) else null
    val scores: Map<MediaId, Float> = if (vector == null) emptyMap() else contentScores(ids, vector)
    val z = standardize(scores)
    val text = textScores(ids, parsed.words, parsed.exact)
    val onlyFilters = semantic == null && parsed.words.isEmpty() && parsed.exact.isEmpty()

    val out = ArrayList<ScoredCell>()
    for (p in cells) {
      val id = snapshot.idAt(p)
      val key = keyOf(id) ?: continue
      val age = ageDays(snapshot, p, day)
      if (onlyFilters) {
        // Solo filtri (E-Q4): tutti sicuri con pertinenza 1, per data: nella tabella e' il testo "tutte le parole" (1) senza contenuto.
        out += ScoredCell(key, null, null, 1f, age)
        continue
      }
      val zi = z[id]
      val ti = text[id] ?: 0f
      if (zi == null && ti == 0f) continue
      out += ScoredCell(key, zi, scores[id], ti, age)
    }
    return Table(ScoreTable.of(query.id, query.category, expected, out), contentAvailable = vector != null || semantic == null, cells = cells.size)
  }

  /** La tabella e se il contenuto c'era (il motore delle query poteva rispondere); [cells] sono le celle candidate (senza nascosti). */
  class Table(val table: ScoreTable, val contentAvailable: Boolean, val cells: Int)

  /** L'eta' in giorni dello scatto della cella [p]; un giorno non valido vale come oggi (come il motore). */
  private fun ageDays(snapshot: TimelineSnapshot, p: Int, today: java.time.LocalDate): Int {
    val d = ChipMatching.dayOf(snapshot.localDayAt(p)) ?: return 0
    return ChronoUnit.DAYS.between(d, today).toInt().coerceAtLeast(0)
  }

  /** Il coseno piu' alto fra i vettori di ogni cella (video: fotogrammi; gruppo: membri) e la query, a lotti. */
  private suspend fun contentScores(ids: List<MediaId>, query: FloatArray): Map<MediaId, Float> {
    val out = HashMap<MediaId, Float>()
    for (chunk in ids.chunked(batch)) {
      for ((id, list) in data.vectors(chunk, SpaceCode.CURRENT.space)) {
        list.maxOfOrNull { dot(query, it) }?.let { out[id] = it }
      }
    }
    return out
  }

  private fun standardize(scores: Map<MediaId, Float>): Map<MediaId, Float> {
    if (scores.size < 2) return emptyMap()
    val sorted = scores.values.sorted()
    val median = median(sorted)
    val mad = median(scores.values.map { kotlin.math.abs(it - median) }.sorted())
    val sigma = 1.4826f * mad
    if (sigma <= 1e-6f) return scores.mapValues { 0f }
    return scores.mapValues { (_, s) -> (s - median) / sigma }
  }

  /** 1 = tutte le parole (o la frase esatta) lette nel media; 0,6 = almeno meta'; frasi esatte obbligatorie (D27). */
  private suspend fun textScores(ids: List<MediaId>, words: List<String>, exact: List<String>): Map<MediaId, Float> {
    if (words.isEmpty() && exact.isEmpty()) return emptyMap()
    val wanted = words.map { SearchText.normalize(it) }.filter { it.isNotEmpty() }.distinct()
    val phrases = exact.map { SearchText.normalize(it) }.filter { it.isNotEmpty() }
    val out = HashMap<MediaId, Float>()
    for (chunk in ids.chunked(batch)) {
      for ((id, raw) in data.texts(chunk)) {
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
    }
    return out
  }

  /**
   * Un esempio per "Documenti" (D34): il punteggio sui prototipi ([prototypes]: i loro vettori nello spazio del testo), le righe e i caratteri
   * letti ([mediaText]) e l'etichetta dell'utente. Null se la cella non ha una chiave o un vettore e un testo da guardare.
   */
  suspend fun documentSample(id: MediaId, prototypes: List<FloatArray>, mediaText: MediaText, label: Boolean): DocSample? {
    prepare()
    val key = keyOf(id) ?: return null
    val vectors = data.vectors(listOf(id), SpaceCode.CURRENT.space)[id].orEmpty()
    val score = if (vectors.isEmpty() || prototypes.isEmpty()) null else vectors.maxOf { v -> prototypes.maxOf { dot(v, it) } }
    val layout = mediaText.layout(id)
    return DocSample(key, score, layout?.lines?.size ?: 0, layout?.lines?.sumOf { it.text.length } ?: 0, label)
  }

  private fun median(sorted: List<Float>): Float =
    if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2f

  private fun dot(a: FloatArray, b: FloatArray): Float {
    var sum = 0f
    for (i in a.indices) sum += a[i] * b[i]
    return sum
  }
}
