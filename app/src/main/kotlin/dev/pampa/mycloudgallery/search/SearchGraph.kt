package dev.pampa.mycloudgallery.search

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.search.contract.MediaText
import dev.pampa.mycloudgallery.core.search.contract.PlaceLookup
import dev.pampa.mycloudgallery.core.search.contract.ResultOrder
import dev.pampa.mycloudgallery.core.search.contract.SearchCoverage
import dev.pampa.mycloudgallery.core.search.contract.SearchData
import dev.pampa.mycloudgallery.core.search.contract.SearchEngine
import dev.pampa.mycloudgallery.core.search.contract.SearchHistory
import dev.pampa.mycloudgallery.core.search.contract.SearchReadiness
import dev.pampa.mycloudgallery.core.search.contract.SearchRequest
import dev.pampa.mycloudgallery.core.search.contract.SearchResults
import dev.pampa.mycloudgallery.core.search.contract.Suggestions
import dev.pampa.mycloudgallery.core.search.contract.Today
import dev.pampa.mycloudgallery.core.search.coverage.CoverageReasons
import dev.pampa.mycloudgallery.core.search.coverage.CoverageTracker
import dev.pampa.mycloudgallery.core.search.engine.IndexSearchData
import dev.pampa.mycloudgallery.core.search.engine.LibraryPlaces
import dev.pampa.mycloudgallery.core.search.engine.LibrarySuggestions
import dev.pampa.mycloudgallery.core.search.engine.LocalSearchEngine
import dev.pampa.mycloudgallery.core.search.engine.QueryEmbedding
import dev.pampa.mycloudgallery.core.search.history.LocalSearchHistory
import dev.pampa.mycloudgallery.core.search.index.CellKeys
import dev.pampa.mycloudgallery.core.search.index.LocalAiIndex
import java.io.File
import java.time.LocalDate
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Il GRAFO DELLA RICERCA (docs/fase6-progetto.md §1.4, P11): motore di ricerca locale (P6), indice locale per chiave di contenuto,
 * analizzatore delle query (P7), suggerimenti, cronologia e testo dal vivo. Vive **anche senza NAS** (offline, D35) e **anche senza
 * `AiGraph`** (filtri e testo): il calcolo e la replica `ai` gli scrivono dentro quando ci sono ([index] e' l'`AiResults` del calcolo e
 * il bersaglio dell'`AiReplicaSink` della replica, [layouts] tiene le righe del testo letto per il viewer).
 *
 * - **Nessuna rete e nessun NAS** (D33): solo il catalogo locale, l'istantanea della Libreria e i file in [dir].
 * - [open] all'avvio (famiglia pronta, catalogo costruito): apre l'indice e segue i cambiamenti del catalogo. Prima di [open] le
 *   ricerche aspettano (il catalogo arriva in pochi istanti); dopo [stop] (azzeramento) non rispondono piu'.
 * - [maintain] nel lavoro di manutenzione della Libreria: record rovinati chiesti di nuovo e compattazione (§2.4).
 */
class SearchGraph(
  private val scope: CoroutineScope,
  val dir: File,
  private val catalog: suspend () -> MediaCatalog?,
  database: GalleryDatabase,
  snapshot: StateFlow<TimelineSnapshot?>,
  places: PlaceLookup,
  val embedding: QueryEmbedding,
  val today: Today = Today { LocalDate.now() },
  coverageReasons: CoverageReasons = CoverageReasons.PREPARATION,
  queryModelBytes: Long = 0,
  modelMissing: () -> Boolean = { false },
  /** Dove gira il lavoro delle ricerche (CE-7): fuori dal thread principale. I test ne scelgono uno loro. */
  private val compute: CoroutineDispatcher = Dispatchers.Default,
) {
  private val job = SupervisorJob(scope.coroutineContext[Job])
  private val graphScope = CoroutineScope(scope.coroutineContext + job)

  private val opened = CompletableDeferred<Boolean>()

  private val texts = CatalogTextRowStore(catalog)
  val repair = DelegatingIndexRepair()

  /** L'indice locale (vettori e testo per chiave di contenuto): `AiResults` del calcolo e bersaglio della replica `ai`. */
  val index = LocalAiIndex(File(dir, INDEX_DIR), texts, repair)

  /** Le righe del testo letto per il testo dal vivo (D43). */
  val layouts = OcrLayoutStore(File(dir, LAYOUT_DIR))

  val keys = CellKeys(CatalogCellKeySource(catalog))
  private val base = CatalogSearchData(snapshot, database, catalog)

  /** Istantanea e nascosti dalla Libreria, vettori e testo dall'indice (anche per la valutazione di P9 e per "Trova simili"). */
  val data: SearchData = IndexSearchData(base, keys, index)

  val coverage = CoverageTracker(coverageReasons, queryModelBytes, modelMissing)
  private val libraryPlaces = LibraryPlaces(places)
  // CE-10: la cronologia aspetta che l'indice si apra con il catalogo (dopo l'onboarding, nello stesso processo) invece di restare vuota per sempre.
  val history: SearchHistory = LocalSearchHistory(CatalogHistoryStore(catalog) { opened.await() }, graphScope)
  val suggestions: Suggestions = LibrarySuggestions(base, libraryPlaces, history)

  private val local = LocalSearchEngine(base, index, keys, texts, embedding, today, libraryPlaces, coverageTracker = coverage)
  private val lifecycle = Mutex()

  @Volatile private var stopped = false

  /**
   * Il motore: aspetta [open]; dopo [stop] lancia (la schermata dice "La ricerca non ha funzionato"). Il lavoro gira su [compute], mai sul thread
   * principale (CE-7): candidati, mappa delle celle, punteggi e ordine di 100 mila celle non devono bloccare la Libreria.
   */
  val engine: SearchEngine = OffMainSearchEngine(
    object : SearchEngine {
      override suspend fun search(request: SearchRequest): SearchResults {
        ready()
        return local.search(request)
      }

      override suspend fun similar(media: MediaId, order: ResultOrder): SearchResults {
        ready()
        return local.similar(media, order)
      }

      override val coverage: StateFlow<SearchCoverage> get() = local.coverage

      override val readiness: StateFlow<SearchReadiness> get() = local.readiness

      override fun warmUp() = local.warmUp()
    },
    compute,
  )

  /** Il testo letto di un media per il viewer (`ViewerTextSource`, D43). */
  val mediaText: MediaText = StoredMediaText(catalog, layouts)

  /**
   * Le ricerche aspettano [open]. Se la ricerca e' ferma (azzeramento) o l'indice non si apre, **lanciano un errore vero**, non una
   * `CancellationException`: quella la prende per "la mia ricerca e' stata annullata" chi aspetta, e la schermata resterebbe su "Sto cercando…" (CE-11).
   */
  private suspend fun ready() {
    if (stopped || !opened.await() || stopped) throw IllegalStateException("Ricerca ferma.")
  }

  /** Apre l'indice e comincia a seguire il catalogo. Idempotente; falso se non si e' potuto aprire (catalogo assente, fermata). */
  suspend fun open(): Boolean = lifecycle.withLock {
    if (opened.isCompleted) return@withLock opened.getCompleted()
    if (stopped) return@withLock false
    val cat = catalog() ?: return@withLock false
    val ok = try {
      index.open()
      true
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.w(TAG) { "indice di ricerca non aperto: ${e.javaClass.simpleName}" }
      false
    }
    if (ok) followCatalog(cat)
    opened.complete(ok)
    ok
  }

  /**
   * Le chiavi di contenuto delle celle (`CellKeys`) seguono il catalogo da subito, non dalla prima ricerca (TS-1): "Trova simili" nel menu del viewer le
   * legge per sapere se la voce c'e', e dopo un avvio a freddo mancava finche' l'utente non faceva una ricerca qualunque. Una sincronizzazione appena aperto
   * l'indice e una a ogni cambiamento del catalogo (accorpate: una raffica di scritture ne fa una sola).
   */
  private fun followCatalog(cat: MediaCatalog) {
    val dirty = Channel<Unit>(Channel.CONFLATED)
    graphScope.launch {
      for (ignored in dirty) {
        delay(SYNC_DEBOUNCE_MS)
        try {
          keys.sync()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          GalleryLog.w(TAG) { "chiavi delle celle non aggiornate: ${e.javaClass.simpleName}" }
        }
      }
    }
    graphScope.launch {
      cat.changes.collect {
        keys.invalidate(it.ids)
        dirty.trySend(Unit)
      }
    }
    dirty.trySend(Unit)
  }

  /** La manutenzione dell'indice (in `LibraryMaintenanceWorker`): record rovinati e compattazione. */
  suspend fun maintain() {
    if (stopped || !opened.isCompleted || !opened.getCompleted()) return
    runCatching { index.repairPending() }
    runCatching { index.maintain() }
  }

  /** Memoria bassa (`onTrimMemory`): il motore delle query si rilascia (D35). */
  fun onTrimMemory() {
    val lazy = embedding as? LazyQueryEmbedding ?: return
    scope.launch { lazy.release() }
  }

  /**
   * L'azzeramento, **per primo** insieme ad `AiGraph.stop` (§1.4): niente risponde piu', il motore delle query si rilascia, l'indice si
   * chiude. Poi l'eraser toglie i file di [dir] e le tabelle. Idempotente.
   */
  suspend fun stop() = lifecycle.withLock {
    if (stopped) return@withLock
    stopped = true
    if (!opened.isCompleted) opened.complete(false)
    job.cancel()
    (embedding as? LazyQueryEmbedding)?.stop()
    runCatching { index.close() }
  }

  companion object {
    private const val TAG = "SearchGraph"

    /** Quanto si aspetta, dopo un cambiamento del catalogo, prima di aggiornare le chiavi delle celle (una raffica di scritture ne fa una sola). */
    const val SYNC_DEBOUNCE_MS: Long = 250

    /** `noBackupFilesDir/search`: indice, registro delle origini e righe del testo letto. */
    const val DIR = "search"
    const val INDEX_DIR = "index"
    const val LAYOUT_DIR = "ocr-layout"
  }
}

/**
 * Un motore che fa il suo lavoro su [dispatcher] (CE-7): chi lo chiama (la schermata Cerca, sul thread principale) aspetta senza bloccarlo. Copertura e
 * stato sono flussi: non lavorano e passano com'e'.
 */
class OffMainSearchEngine(private val inner: SearchEngine, private val dispatcher: CoroutineDispatcher) : SearchEngine by inner {
  override suspend fun search(request: SearchRequest): SearchResults = withContext(dispatcher) { inner.search(request) }

  override suspend fun similar(media: MediaId, order: ResultOrder): SearchResults = withContext(dispatcher) { inner.similar(media, order) }
}
