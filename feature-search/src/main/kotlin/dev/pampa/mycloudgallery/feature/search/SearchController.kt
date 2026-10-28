package dev.pampa.mycloudgallery.feature.search

import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.ModelManager
import dev.pampa.mycloudgallery.core.models.contract.ModelState
import dev.pampa.mycloudgallery.core.models.contract.ModelTrigger
import dev.pampa.mycloudgallery.core.search.contract.ChipReadings
import dev.pampa.mycloudgallery.core.search.contract.HistoryEntry
import dev.pampa.mycloudgallery.core.search.contract.IdleSuggestions
import dev.pampa.mycloudgallery.core.search.contract.PersonOption
import dev.pampa.mycloudgallery.core.search.contract.PlaceOption
import dev.pampa.mycloudgallery.core.search.contract.ResultOrder
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import dev.pampa.mycloudgallery.core.search.contract.SearchData
import dev.pampa.mycloudgallery.core.search.contract.SearchEngine
import dev.pampa.mycloudgallery.core.search.contract.SearchHistory
import dev.pampa.mycloudgallery.core.search.contract.SearchMediaType
import dev.pampa.mycloudgallery.core.search.contract.SearchNotice
import dev.pampa.mycloudgallery.core.search.contract.SearchReadiness
import dev.pampa.mycloudgallery.core.search.contract.SearchRequest
import dev.pampa.mycloudgallery.core.search.contract.SearchResults
import dev.pampa.mycloudgallery.core.search.contract.Suggestion
import dev.pampa.mycloudgallery.core.search.contract.Suggestions
import dev.pampa.mycloudgallery.core.search.contract.Today
import dev.pampa.mycloudgallery.core.search.engine.LiveResults
import dev.pampa.mycloudgallery.feature.search.contract.IndexerControl
import dev.pampa.mycloudgallery.feature.search.contract.SearchOrderStore
import dev.pampa.mycloudgallery.feature.search.contract.SearchScreenState
import dev.pampa.mycloudgallery.feature.search.contract.SearchUnavailableReason
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Il cervello della schermata Cerca (docs/fase6-progetto.md §1.5, §2.6; D28-D35, D48): trasforma testo, tocchi e risposte del motore in
 * [SearchScreenState]. Nessuna rete e nessun NAS (D33): parla solo con il motore di ricerca, i suggerimenti, la cronologia e i contratti
 * di core-models e delle porte. **Nascosti mai** (D29): ne' risultati ne' suggerimenti ne' recenti li contengono, perche' li' non ci sono; qui
 * in piu' i completamenti non mostrano mai parole del testo letto ([Suggestion.Term] dal motore e' scartato: i mesi e le feste le aggiunge
 * [CalendarCompletions]).
 *
 * Le azioni di navigazione (aprire il viewer, le Impostazioni, la pagina di attivazione) non sono qui: le fa `SearchActions`, in [SearchRoute].
 * Mai testo di query nei log (D40): nessun log.
 */
class SearchController(
  private val engine: SearchEngine,
  private val suggestions: Suggestions,
  private val history: SearchHistory,
  private val data: SearchData?,
  private val today: Today,
  private val orderStore: SearchOrderStore,
  private val models: ModelManager?,
  private val indexer: IndexerControl?,
  private val scope: CoroutineScope,
  private val debounceMillis: Long = COMPLETION_DEBOUNCE_MILLIS,
) : SearchHandlers {

  private val mutableState = MutableStateFlow<SearchScreenState>(SearchScreenState.Idle(emptyIdle(), emptyList(), known = false))
  val state: StateFlow<SearchScreenState> = mutableState

  private val mutableQuery = MutableStateFlow("")
  val query: StateFlow<String> = mutableQuery

  val readiness: StateFlow<SearchReadiness> get() = engine.readiness

  val order: StateFlow<ResultOrder> get() = orderStore.order

  /** Lo stato del modello delle query (E-Q11): "Scarica" lo avvia, la schermata lo mostra mentre arriva. */
  val queryModel: StateFlow<ModelState?> =
    models?.states?.mapState(scope) { it[ModelCatalog.QUERY_270M.id] } ?: MutableStateFlow<ModelState?>(null)

  private sealed interface Last {
    data class Query(val text: String, val edited: List<SearchChip>?) : Last

    data class Similar(val media: List<MediaId>) : Last
  }

  private var last: Last? = null
  private var results: SearchResults? = null
  private var showLikely = false
  private var recordNext = false
  private var textReadingOffShown = false
  private var searchJob: Job? = null
  private var typingJob: Job? = null
  private var liveJob: Job? = null

  init {
    scope.launch { history.entries.collect { if (mutableState.value is SearchScreenState.Idle) refreshIdle() } }
    // CE-8, CE-9: il contenuto che mancava (modello scaricato, motore delle query caricato) si aggiunge ai risultati senza che l'utente ripeta la ricerca.
    scope.launch {
      var wasReady = queryModel.value is ModelState.Ready
      queryModel.collect { model ->
        val ready = model is ModelState.Ready
        if (ready && !wasReady) refreshContent()
        wasReady = ready
      }
    }
    scope.launch {
      var wasReady = readiness.value == SearchReadiness.Ready
      readiness.collect { now ->
        val ready = now == SearchReadiness.Ready
        if (ready && !wasReady) refreshContent()
        wasReady = ready
      }
    }
    data?.let { source ->
      scope.launch {
        source.snapshot.collect { snapshot ->
          val current = mutableState.value
          if (snapshot != null && snapshot.size == 0) {
            mutableState.value = SearchScreenState.Unavailable(SearchUnavailableReason.EMPTY_LIBRARY)
          } else if (current is SearchScreenState.Unavailable && current.reason == SearchUnavailableReason.EMPTY_LIBRARY) {
            refreshIdle()
          }
        }
      }
    }
  }

  /** La data di oggi, per le intestazioni di periodo di "Recenti" (la stessa del motore, D39). */
  fun todayDate(): LocalDate = today.date()

  /** La scheda Cerca si apre (D35, CE-9): il motore delle query comincia a caricarsi, cosi' la prima ricerca non lo aspetta. */
  fun onOpened() {
    engine.warmUp()
  }

  // --- Testo ---------------------------------------------------------------------------------------------------------------------------

  override fun onQueryChange(text: String) {
    mutableQuery.value = text
    searchJob?.cancel()
    typingJob?.cancel()
    liveJob?.cancel()
    val t = text.trim()
    if (t.isEmpty()) {
      last = null
      results = null
      scope.launch { refreshIdle() }
      return
    }
    val previous = (mutableState.value as? SearchScreenState.Typing)?.completions.orEmpty()
    mutableState.value = SearchScreenState.Typing(text, previous)
    typingJob = scope.launch {
      delay(debounceMillis)
      val completions = try {
        complete(t)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        emptyList()
      }
      mutableState.value = SearchScreenState.Typing(text, completions)
    }
  }

  private suspend fun complete(prefix: String): List<Suggestion> {
    val fromEngine = suggestions.complete(prefix).filter { it !is Suggestion.Term }
    return (fromEngine + CalendarCompletions.of(prefix)).distinctBy { it.label.lowercase() }.take(MAX_COMPLETIONS)
  }

  override fun onSubmit() {
    val text = mutableQuery.value.trim()
    // E-Q1: una lettera sola non e' una ricerca (restano i suggerimenti).
    if (text.length < MIN_QUERY_CHARS) return
    recordNext = true
    launch(Last.Query(text, null))
  }

  override fun onCompletion(suggestion: Suggestion) {
    if (suggestion is Suggestion.PersonSuggestion) {
      onPerson(suggestion.option)
      return
    }
    val text = suggestion.label
    mutableQuery.value = text
    recordNext = true
    launch(Last.Query(text, null))
  }

  // --- A riposo ------------------------------------------------------------------------------------------------------------------------

  override fun onRecent(entry: HistoryEntry) {
    mutableQuery.value = entry.text
    recordNext = false
    launch(Last.Query(entry.text, null))
  }

  override fun onDeleteRecent(entry: HistoryEntry) {
    scope.launch { guarded { history.delete(entry.id) } }
  }

  override fun onClearRecents() {
    scope.launch { guarded { history.clear() } }
  }

  override fun onPlace(option: PlaceOption) = searchText(option.label)

  override fun onType(type: SearchMediaType, label: String) = searchText(label)

  private fun searchText(text: String) {
    mutableQuery.value = text
    recordNext = true
    launch(Last.Query(text, null))
  }

  // --- Chip, ordine, meno probabili ----------------------------------------------------------------------------------------------------

  override fun onRemoveChip(chip: SearchChip) {
    val current = results ?: return
    val remaining = current.chips - chip
    // CE-2: tolto l'ultimo chip la ricerca continua con le parole libere della query; Cerca torna a riposo solo se non resta niente da cercare.
    if (chip is SearchChip.Similar || (remaining.isEmpty() && !current.freeText)) {
      mutableQuery.value = ""
      onQueryChange("")
      return
    }
    recordNext = false
    launch(Last.Query(mutableQuery.value, remaining))
  }

  /** Fase 8 (§2.7): il nome scelto dalla riga "Persone" o dai completamenti, con il chip gia' fatto: un nome che e' anche un luogo non cambia lettura. */
  override fun onPerson(option: PersonOption) {
    mutableQuery.value = option.label
    recordNext = true
    launch(Last.Query(option.label, listOf(ChipReadings.ofOption(option))))
  }

  /** Fase 8 (E-F36): un'altra lettura della stessa parola prende il posto del chip; la ricerca riparte con i chip cosi'. */
  override fun onSwitchReading(chip: SearchChip, to: SearchChip) {
    val current = results ?: return
    recordNext = false
    launch(Last.Query(mutableQuery.value, current.chips.map { if (it == chip) to else it }))
  }

  override fun onChooseAlternative(chip: SearchChip.Place, option: PlaceOption) {
    val current = results ?: return
    val others = (listOf(chip.option) + chip.alternatives).filter { it != option }
    val replaced = SearchChip.Place(option, others, chip.word, chip.person)
    recordNext = false
    launch(Last.Query(mutableQuery.value, current.chips.map { if (it == chip) replaced else it }))
  }

  override fun onPlaceAsWord(chip: SearchChip.Place) {
    val current = results ?: return
    recordNext = false
    launch(Last.Query(mutableQuery.value, current.chips.map { if (it == chip) SearchChip.Word(chip.word, exact = false) else it }))
  }

  override fun onSetOrder(order: ResultOrder) {
    if (order == orderStore.order.value) return
    scope.launch { guarded { orderStore.setOrder(order) } }
    val again = last ?: return
    recordNext = false
    launch(again, order)
  }

  override fun onShowLikely() {
    val current = results ?: return
    showLikely = true
    publish(current)
  }

  /** "Trova simili" dal viewer (D30): i simili al media, con il chip "Simili a". */
  fun findSimilar(media: MediaId) = findSimilar(listOf(media))

  /**
   * "Trova simili" dalla selezione della Libreria (D30, correzioni F, TS-4): i media vicini al centroide dei media scelti, con il chip "Simili a" che porta
   * tutta la scelta. Con un media solo e' [findSimilar] di sempre.
   */
  fun findSimilar(media: List<MediaId>) {
    if (media.isEmpty()) return
    mutableQuery.value = ""
    recordNext = false
    launch(Last.Similar(media))
  }

  // --- Messaggi ------------------------------------------------------------------------------------------------------------------------

  override fun onDownloadModel() {
    val manager = models ?: return
    scope.launch { guarded { manager.ensure(ModelCatalog.QUERY_270M.id, ModelTrigger.USER_NOW, DOWNLOAD_BUDGET) } }
  }

  override fun onEnableIndexerHere() {
    val control = indexer ?: return
    scope.launch { guarded { control.setIndexer(true) } }
  }

  override fun onRetry() {
    val again = last
    if (again != null) launch(again) else scope.launch { refreshIdle() }
  }

  // Le navigazioni le fa SearchActions (SearchRoute).
  override fun onOpen(view: OrderedTimelineView, media: MediaId) = Unit

  override fun onWhyCoverage() = Unit

  override fun onLearnTextReading() = Unit

  override fun onAllowAccess() = Unit

  override fun onDiagnostics() = Unit

  // --- Motore --------------------------------------------------------------------------------------------------------------------------

  /** [quiet]: la ricerca di prima si rifa' senza passare da "Sto cercando…" (i risultati restano in vista finche' non arrivano quelli nuovi). */
  private fun launch(request: Last, orderOverride: ResultOrder? = null, quiet: Boolean = false) {
    searchJob?.cancel()
    typingJob?.cancel()
    liveJob?.cancel()
    last = request
    if (orderOverride == null && !quiet) showLikely = false
    if (!quiet) mutableState.value = SearchScreenState.Searching((request as? Last.Query)?.edited.orEmpty())
    searchJob = scope.launch {
      val found = try {
        val order = orderOverride ?: orderStore.order.value
        when (request) {
          is Last.Query -> engine.search(SearchRequest(request.text, request.edited, showLikely = false, order = order))
          is Last.Similar -> if (request.media.size == 1) engine.similar(request.media.first(), order) else engine.similarToAll(request.media, order)
        }
      } catch (e: CancellationException) {
        // Annullata davvero (una ricerca nuova, la schermata chiusa): si esce. Una cancellazione che arriva dal motore mentre noi siamo vivi non e'
        // la nostra (CE-11, motore fermo o indice non aperto): sarebbe "Sto cercando…" per sempre.
        if (!isActive) throw e
        mutableState.value = SearchScreenState.Unavailable(SearchUnavailableReason.INTERNAL_ERROR)
        return@launch
      } catch (e: Exception) {
        mutableState.value = SearchScreenState.Unavailable(SearchUnavailableReason.INTERNAL_ERROR)
        return@launch
      }
      results = found
      publish(found)
      if (recordNext && request is Last.Query) {
        recordNext = false
        guarded { history.record(request.text, found.chips) }
      }
      follow(found)
    }
  }

  /**
   * CE-8, CE-9: il modello delle query e' diventato pronto, o il motore ha finito di caricarsi. Se i risultati in vista erano senza il contenuto
   * (l'avviso "serve il modello" o "sto preparando"), la stessa ricerca si rifa' da sola, senza far sparire cio' che si vede.
   */
  private fun refreshContent() {
    val again = last as? Last.Query ?: return
    val shown = results ?: return
    val state = mutableState.value
    if (state !is SearchScreenState.Results && state !is SearchScreenState.NoResults) return
    if (shown.notices.none { it == SearchNotice.PreparingContentSearch || it == SearchNotice.OnlyFiltersAndText }) return
    recordNext = false
    launch(again, quiet = true)
  }

  private fun follow(found: SearchResults) {
    val source = data ?: return
    liveJob?.cancel()
    liveJob = scope.launch {
      LiveResults.follow(found, source).collect { updated ->
        if (updated === found) return@collect
        results = updated
        publish(updated)
      }
    }
  }

  private fun publish(found: SearchResults) {
    val confident = found.confident.size
    val likely = found.likely.size
    val notices = found.notices.filter { shownNotice(it) }
    mutableState.value = when {
      confident == 0 && likely == 0 -> SearchScreenState.NoResults(found.chips, 0, filtersOf(found.chips))
      confident == 0 && !showLikely -> SearchScreenState.NoResults(found.chips, likely, filtersOf(found.chips))
      else -> SearchScreenState.Results(
        chips = found.chips,
        order = found.order,
        confident = found.confident,
        likely = found.likely,
        likelyCount = likely,
        showLikely = showLikely,
        coverage = found.coverage,
        notices = notices,
      )
    }
  }

  /** "Le parole dentro le foto non si cercano": una volta sola nella schermata, poi solo nella pagina (D31, §2.6). */
  private fun shownNotice(notice: SearchNotice): Boolean {
    if (notice != SearchNotice.TextReadingOff) return true
    if (textReadingOffShown) return false
    textReadingOffShown = true
    return true
  }

  private fun filtersOf(chips: List<SearchChip>): List<SearchChip> = chips.filter { it.isFilter }

  private suspend fun refreshIdle() {
    val idle = try {
      suggestions.idle()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      emptyIdle()
    }
    val recents = idle.recents.take(MAX_RECENTS)
    val snapshot = data?.snapshot?.value
    mutableState.value = if (snapshot != null && snapshot.size == 0) {
      SearchScreenState.Unavailable(SearchUnavailableReason.EMPTY_LIBRARY)
    } else {
      SearchScreenState.Idle(idle.copy(recents = recents), recents)
    }
  }

  private suspend fun guarded(block: suspend () -> Unit) {
    try {
      block()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Un'azione di contorno (cronologia, ruolo, scaricamento) che non riesce non ferma la ricerca: i dettagli sono nella Diagnostica.
    }
  }

  private fun emptyIdle() = IdleSuggestions(emptyList(), emptyList(), emptyList())

  companion object {
    const val COMPLETION_DEBOUNCE_MILLIS: Long = 120
    const val MIN_QUERY_CHARS: Int = 2
    const val MAX_RECENTS: Int = 10
    const val MAX_COMPLETIONS: Int = 12
    private val DOWNLOAD_BUDGET = 30.minutes
  }
}

private fun <T, R> StateFlow<T>.mapState(scope: CoroutineScope, transform: (T) -> R): StateFlow<R> =
  map(transform).stateIn(scope, SharingStarted.Eagerly, transform(value))
