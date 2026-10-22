package dev.pampa.mycloudgallery.debugtools.eval

import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.search.contract.ChipMatching
import dev.pampa.mycloudgallery.core.search.contract.ResultOrder
import dev.pampa.mycloudgallery.core.search.contract.SearchRequest
import dev.pampa.mycloudgallery.core.search.contract.SearchText
import java.time.LocalDate
import java.util.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Una cella della Libreria che si puo' etichettare: il media che la rappresenta, la sua chiave di contenuto e il giorno. */
class EvalCell(val id: MediaId, val key: String, val day: LocalDate?) {
  override fun toString(): String = "EvalCell"
}

/** Il filtro per sfogliare la Libreria "per data e luogo" (la prima fase del metodo a pozzo): anni (estremi inclusi) e un luogo scritto a mano. */
class BrowseFilter(val fromYear: Int? = null, val toYear: Int? = null, val place: String? = null)

/**
 * SOLO debug (Fase 6, P9): lo stato dello strumento di etichettatura e di corsa, senza interfaccia (si prova sulla JVM). Il **metodo "a pozzo"**
 * (§5.3) per ogni query, per non premiare il motore che si sta misurando:
 *  1. **prima** si segnano le foto attese sfogliando la Libreria per data e luogo ([browse]), senza guardare la ricerca;
 *  2. **poi** si aggiungono dai primi [DEPTH] risultati di **due configurazioni diverse**: il motore vero ([enginePool]) e il coseno grezzo
 *     della query sui vettori, senza soglie ne' testo ([rawPool]). Cio' che l'utente non segna resta "non attesa".
 * Un gruppo conta una volta: la cella. Le etichette sono chiavi di contenuto, salvate a ogni modifica ([EvalStore]).
 */
class EvalSession(private val env: EvalEnvironment, private val store: EvalStore) {

  private val state = MutableStateFlow(LabelSet())
  val labels: StateFlow<LabelSet> get() = state

  /** Se il file delle etichette era rovinato: il motivo (con il numero di riga, mai il contenuto) e un file vuoto al suo posto. */
  var loadError: String? = null
    private set

  private val scoring = EvalScoring(env.data, env.embedding, env.parser, env.today, { env.places() }, { env.keyOf(it) }, { env.prepare() })
  private var keyIndex: Pair<TimelineSnapshot, Map<String, Int>>? = null

  fun load() {
    state.value = try {
      store.loadLabels()
    } catch (e: IllegalArgumentException) {
      loadError = e.message
      LabelSet()
    }
  }

  // --- Query ed etichette ------------------------------------------------------------------------------------------------------

  /** Il prossimo identificativo libero: `q01`, `q02`, ... */
  fun nextQueryId(): String {
    val taken = state.value.queries.map { it.id }.toSet()
    return generateSequence(1) { it + 1 }.map { "q%02d".format(it) }.first { it !in taken }
  }

  fun addQuery(category: EvalCategory, text: String): EvalQuery {
    val query = EvalQuery(nextQueryId(), category, text.trim())
    change { it.withQuery(query) }
    return query
  }

  fun removeQuery(id: String) = change { it.withoutQuery(id) }

  fun setExpected(queryId: String, key: String, on: Boolean) = change { it.withExpected(queryId, key, on) }

  fun setDocument(key: String, isDocument: Boolean?) = change { it.withDocument(key, isDocument) }

  private fun change(block: (LabelSet) -> LabelSet) {
    val next = block(state.value)
    store.saveLabels(next)
    state.value = next
  }

  // --- Prima fase: sfogliare la Libreria ---------------------------------------------------------------------------------------

  /** Le prime [limit] celle della Libreria (senza nascosti) che passano il filtro, dalla piu' recente, con la loro chiave. */
  suspend fun browse(filter: BrowseFilter, limit: Int = BROWSE_LIMIT): List<EvalCell> {
    env.prepare()
    val snapshot = env.data.snapshot.value ?: return emptyList()
    val placeIds = filter.place?.takeIf { it.isNotBlank() }?.let { name ->
      env.places().lookup(SearchText.normalize(name)).flatMap { it.option.ids }.toSet().ifEmpty { return emptyList() }
    }
    val out = ArrayList<EvalCell>()
    for (i in 0 until snapshot.size) {
      val year = snapshot.localDayAt(i) / 10_000
      if (filter.fromYear != null && year < filter.fromYear) continue
      if (filter.toYear != null && year > filter.toYear) continue
      if (placeIds != null && snapshot.placeAt(i) !in placeIds) continue
      cellAt(snapshot, i)?.let { out += it }
      if (out.size >= limit) break
    }
    return out
  }

  // --- Seconda fase: il pozzo --------------------------------------------------------------------------------------------------

  /** I primi [depth] risultati del motore vero (prima i sicuri, poi i meno probabili; ordine per pertinenza). */
  suspend fun enginePool(queryId: String, depth: Int = DEPTH): List<EvalCell> {
    val query = state.value.query(queryId) ?: return emptyList()
    val results = env.engine.search(SearchRequest(query.text, showLikely = true, order = ResultOrder.RELEVANT))
    val cells = ArrayList<EvalCell>()
    for (view in listOf(results.confident, results.likely)) {
      for (i in 0 until view.size) {
        if (cells.size >= depth) return cells
        cellAt(view.snapshot, view.snapshotIndex(i))?.let { cells += it }
      }
    }
    return cells
  }

  /** I primi [depth] per coseno grezzo fra la query e la cella (il massimo dei suoi vettori), senza soglie, testo ne' ordine: la seconda configurazione. */
  suspend fun rawPool(queryId: String, depth: Int = DEPTH): List<EvalCell> {
    val query = state.value.query(queryId) ?: return emptyList()
    val table = scoring.table(query, state.value.expectedOf(queryId)).table
    val snapshot = env.data.snapshot.value ?: return emptyList()
    val index = positionsByKey(snapshot)
    return table.cells.filter { it.s != null }.sortedByDescending { it.s }.take(depth).mapNotNull { c -> index[c.key]?.let { cellAt(snapshot, it) } }
  }

  // --- Corsa -------------------------------------------------------------------------------------------------------------------

  /** La tabella dei punteggi di ogni query con attese, il resoconto di soli numeri e il file `report-NN.txt`; il nome del file in [Run.reportName]. */
  suspend fun run(): Run {
    val labels = state.value
    var withoutContent = 0
    val tables = labels.queries.filter { labels.expectedOf(it.id).isNotEmpty() }.map { q ->
      val t = scoring.table(q, labels.expectedOf(q.id))
      if (!t.contentAvailable) withoutContent++
      t.table
    }
    val outcome = EvalRunner.summarize(tables, withoutContent, documentChoice())
    return Run(outcome, store.saveReport(outcome.text))
  }

  class Run(val outcome: EvalRunner.Outcome, val reportName: String)

  // --- "Documenti" (D34) ---------------------------------------------------------------------------------------------------------

  /** Un lotto di foto da segnare come documento o no: un campione ripetibile di celle non ancora segnate. */
  suspend fun documentBatch(count: Int = DOC_BATCH): List<EvalCell> {
    env.prepare()
    val snapshot = env.data.snapshot.value ?: return emptyList()
    val labelled = state.value.documents.keys
    val random = Random(DOC_SEED + labelled.size / count)
    val positions = (0 until snapshot.size).toMutableList().also { it.shuffle(random) }
    val out = ArrayList<EvalCell>()
    for (p in positions) {
      if (out.size >= count) break
      val cell = cellAt(snapshot, p) ?: continue
      if (cell.key !in labelled) out += cell
    }
    return out
  }

  /** La soglia di "Documenti" dal campione segnato, o null se i documenti o i non documenti sono pochi ([MIN_DOC_EACH] ciascuno). */
  suspend fun documentChoice(): DocumentChoice? {
    val labelled = state.value.documents
    if (labelled.values.count { it } < MIN_DOC_EACH || labelled.values.count { !it } < MIN_DOC_EACH) return null
    val embedder = env.embedder ?: return null
    val snapshot = env.data.snapshot.value ?: return null
    val prototypes = DocumentPrototypes.PHRASES.map { embedder.embed(it).values }
    val index = positionsByKey(snapshot)
    val samples = labelled.mapNotNull { (key, isDocument) ->
      val position = index[key] ?: return@mapNotNull null
      scoring.documentSample(snapshot.idAt(position), prototypes, env.mediaText, isDocument)
    }
    return if (samples.any { it.isDocument } && samples.any { !it.isDocument }) EvalDocuments.calibrate(samples) else null
  }

  // --- Chiavi e celle ------------------------------------------------------------------------------------------------------------

  private fun cellAt(snapshot: TimelineSnapshot, position: Int): EvalCell? {
    val id = snapshot.idAt(position)
    val key = env.keyOf(id) ?: return null
    return EvalCell(id, key, ChipMatching.dayOf(snapshot.localDayAt(position)))
  }

  /** chiave di contenuto -> posizione nell'istantanea (si ricostruisce solo se l'istantanea e' un'altra). */
  private fun positionsByKey(snapshot: TimelineSnapshot): Map<String, Int> {
    keyIndex?.takeIf { it.first === snapshot }?.let { return it.second }
    val map = HashMap<String, Int>(snapshot.size * 2)
    for (i in 0 until snapshot.size) env.keyOf(snapshot.idAt(i))?.let { map.putIfAbsent(it, i) }
    keyIndex = snapshot to map
    return map
  }

  companion object {
    /** Quante celle si guardano per configurazione (§5.3: i primi 100 risultati). */
    const val DEPTH: Int = JUDGED_DEPTH
    const val BROWSE_LIMIT: Int = 300
    const val DOC_BATCH: Int = 30
    const val DOC_SEED: Long = 7
    const val MIN_DOC_EACH: Int = 5
  }
}
