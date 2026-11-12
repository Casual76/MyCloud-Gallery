package dev.pampa.mycloudgallery.feature.search

import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.ModelManager
import dev.pampa.mycloudgallery.core.models.contract.ModelState
import dev.pampa.mycloudgallery.core.search.contract.ChipReadings
import dev.pampa.mycloudgallery.core.search.contract.IdleSuggestions
import dev.pampa.mycloudgallery.core.search.contract.ResultOrder
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import dev.pampa.mycloudgallery.core.search.contract.SearchData
import dev.pampa.mycloudgallery.core.search.contract.SearchEngine
import dev.pampa.mycloudgallery.core.search.contract.SearchHistory
import dev.pampa.mycloudgallery.core.search.contract.SearchNotice
import dev.pampa.mycloudgallery.core.search.contract.SearchReadiness
import dev.pampa.mycloudgallery.core.search.contract.SearchRequest
import dev.pampa.mycloudgallery.core.search.contract.SearchResults
import dev.pampa.mycloudgallery.core.search.contract.Suggestion
import dev.pampa.mycloudgallery.core.search.contract.Suggestions
import dev.pampa.mycloudgallery.core.search.engine.LibrarySuggestions
import dev.pampa.mycloudgallery.core.search.engine.LiveResults
import dev.pampa.mycloudgallery.feature.search.contract.SearchScreenState
import dev.pampa.mycloudgallery.feature.search.contract.SearchUnavailableReason
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
 * Il cervello della ricerca dentro la Libreria (H4 della fase H, 11/11; prima la schermata Cerca della Fase 6, docs/fase6-progetto.md §1.5, §2.6, D28-D35).
 * Trasforma il testo del campo, i tocchi e le risposte del motore in:
 *  - [state]: che cosa c'e' dietro la barra (la Libreria, i risultati, una riga sola);
 *  - [topSuggestions]: i tre suggerimenti della barra in alto, come la striscia delle parole di una tastiera.
 *
 * **Scrivere e' cercare**: dopo una pausa breve ([completionDebounceMillis]) arrivano i completamenti, dopo una un po' piu' lunga
 * ([searchDebounceMillis]) i risultati, sempre fuori dal thread principale (il motore lo fa da se'). Mentre il motore lavora resta cio' che c'era
 * (nessuno stato "Sto cercando"), e una risposta con gli stessi media della precedente non si ripubblica: la griglia non si ricompone a ogni tasto.
 * L'ordine e' solo **per pertinenza** (scelta dell'utente dell'11/11: il selettore "Recenti · Pertinenti" e la sua impostazione non ci sono piu').
 *
 * Nessuna rete e nessun NAS (D33): parla solo con il motore, i suggerimenti, la cronologia e lo stato del modello. **Nascosti mai** (D29): ne' risultati
 * ne' suggerimenti li contengono; in piu' i completamenti non mostrano mai parole del testo letto ([Suggestion.Term] dal motore e' scartato: i mesi e le
 * feste le aggiunge [CalendarCompletions]). Mai testo di query nei log (D40): nessun log.
 */
class SearchController(
  private val engine: SearchEngine,
  private val suggestions: Suggestions,
  private val history: SearchHistory,
  private val data: SearchData?,
  private val models: ModelManager?,
  private val scope: CoroutineScope,
  private val completionDebounceMillis: Long = COMPLETION_DEBOUNCE_MILLIS,
  private val searchDebounceMillis: Long = SEARCH_DEBOUNCE_MILLIS,
  /**
   * I2: gli esempi con la foto a rotazione e le anteprime dei suggerimenti ([SearchExamples]). Null (le prove del comportamento di sempre): i suggerimenti
   * di solo testo della fase H.
   */
  private val examples: SearchExamples? = null,
) : SearchHandlers {

  private val mutableState = MutableStateFlow<SearchScreenState>(SearchScreenState.Idle)
  val state: StateFlow<SearchScreenState> = mutableState

  private val mutableQuery = MutableStateFlow("")
  val query: StateFlow<String> = mutableQuery

  private val mutableTop = MutableStateFlow<List<Suggestion>>(emptyList())

  /** I suggerimenti della barra in alto, al piu' [STRIP_SIZE]: a campo vuoto i migliori a riposo, mentre si scrive i completamenti migliori. */
  val topSuggestions: StateFlow<List<Suggestion>> = mutableTop

  private val mutableStrip = MutableStateFlow<List<SuggestionItem>>(emptyList())

  /** I2: gli stessi suggerimenti di [topSuggestions] con la loro anteprima (la foto del primo risultato, un volto), come li mostra la barra. */
  val strip: StateFlow<List<SuggestionItem>> = mutableStrip

  /** CG-1: i volti delle anteprime, con il segno delle revoche (null senza gli esempi). */
  val faces: SuggestionFaces? get() = examples?.faceImages

  /** L'istantanea della Libreria di adesso: la barra disegna un'anteprima solo se il suo media c'e' ancora (un nascosto no, D29). */
  val library: StateFlow<dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot?> = data?.snapshot ?: MutableStateFlow(null)

  val readiness: StateFlow<SearchReadiness> get() = engine.readiness

  /** Lo stato del modello delle query (E-Q11): la riga sopra la barra dice se manca o si sta scaricando. */
  val queryModel: StateFlow<ModelState?> =
    models?.states?.mapState(scope) { it[ModelCatalog.QUERY_270M.id] } ?: MutableStateFlow<ModelState?>(null)

  private sealed interface Last {
    data class Query(val text: String, val edited: List<SearchChip>?) : Last

    data class Similar(val media: List<MediaId>) : Last
  }

  private var last: Last? = null
  private var results: SearchResults? = null
  private var recorded: String? = null
  private var textReadingOffShown = false
  private var searchJob: Job? = null
  private var typingJob: Job? = null
  private var liveJob: Job? = null

  /** Gli ultimi completamenti di cio' che si scrive, e se in alto ci sono invece le recenti perche' la ricerca non ha trovato niente (CS-12). */
  private var lastCompletions: List<SuggestionItem> = emptyList()
  private var recentsInTop = false

  init {
    scope.launch { history.entries.collect { if (mutableQuery.value.isBlank()) refreshIdleSuggestions() } }
    examples?.let { ex ->
      ex.start { suggestions.idle() }
      // Gli esempi pronti, un volto o una foto arrivati: la barra rilegge le anteprime (senza far girare la terna).
      scope.launch {
        ex.updates.collect {
          when {
            mutableQuery.value.isBlank() && mutableState.value !is SearchScreenState.NoResults -> refreshIdleSuggestions()
            else -> setTop(ex.withArt(mutableTop.value))
          }
        }
      }
    }
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
            mutableState.value = SearchScreenState.Idle
            last?.let { launch(it) }
          }
        }
      }
    }
  }

  /** La ricerca si apre (D35, CE-9): il motore delle query comincia a caricarsi, cosi' la prima ricerca non lo aspetta; i suggerimenti a riposo si rileggono. */
  fun onOpened() {
    engine.warmUp()
    if (mutableQuery.value.isBlank()) scope.launch { refreshIdleSuggestions() }
  }

  /** La ricerca si chiude (H4): il campo si svuota e dietro torna la Libreria. La prossima apertura riparte da capo. */
  fun clear() {
    cancelAll()
    last = null
    results = null
    recorded = null
    lastCompletions = emptyList()
    recentsInTop = false
    mutableQuery.value = ""
    showIdle()
    // I2: chiusa la barra, la terna degli esempi gira adesso, cosi' la prossima apertura e' gia' piena (e diversa).
    scope.launch { refreshIdleSuggestions(advance = true) }
  }

  // --- Testo ---------------------------------------------------------------------------------------------------------------------------

  override fun onQueryChange(text: String) {
    if (text == mutableQuery.value) return
    mutableQuery.value = text
    typingJob?.cancel()
    val t = text.trim()
    if (t.isEmpty()) {
      cancelAll()
      last = null
      results = null
      lastCompletions = emptyList()
      recentsInTop = false
      showIdle()
      scope.launch { refreshIdleSuggestions() }
      return
    }
    typingJob = scope.launch {
      delay(completionDebounceMillis)
      showCompletions(completions(t))
    }
    // E-Q1: una lettera sola non e' una ricerca: dietro torna la Libreria (i suggerimenti restano).
    if (t.length < MIN_QUERY_CHARS) {
      cancelSearch()
      last = null
      results = null
      showIdle()
      return
    }
    val request = Last.Query(t, null)
    // Lo stesso testo (uno spazio in piu' in fondo): niente da rifare.
    if (request == last && searchJob?.isActive != true) return
    launch(request, waitMillis = searchDebounceMillis)
  }

  override fun onSubmit() {
    val text = mutableQuery.value.trim()
    if (text.length < MIN_QUERY_CHARS) return
    val request = Last.Query(text, null)
    val shown = results
    // La ricerca per questo testo e' gia' quella in vista: basta ricordarla.
    if (request == last && shown != null && searchJob?.isActive != true) {
      scope.launch { record(text, shown.chips) }
      return
    }
    launch(request, record = true)
  }

  override fun onSuggestion(suggestion: Suggestion) {
    val text = suggestion.label
    mutableQuery.value = text
    typingJob?.cancel()
    typingJob = scope.launch { showCompletions(completions(text.trim())) }
    // Fase 8 (§2.7): una persona scelta porta il suo chip gia' fatto: un nome che e' anche un luogo non cambia lettura.
    val edited = (suggestion as? Suggestion.PersonSuggestion)?.let { listOf(ChipReadings.ofOption(it.option)) }
    launch(Last.Query(text, edited), record = true)
  }

  /** Un risultato toccato (H4): la ricerca per testo che l'ha trovato entra fra le recenti, una volta sola. */
  fun onResultOpened() {
    val request = last as? Last.Query ?: return
    val shown = results ?: return
    scope.launch { record(request.text, shown.chips) }
  }

  /** "Trova simili" dal viewer (D30): i simili al media. */
  fun findSimilar(media: MediaId) = findSimilar(listOf(media))

  /**
   * "Trova simili" (D30) dal viewer o dalla selezione della Libreria (correzioni F, TS-4): i media vicini al centroide dei media scelti. Il campo resta vuoto;
   * la riga sopra la barra dice da che cosa vengono i risultati.
   */
  fun findSimilar(media: List<MediaId>) {
    if (media.isEmpty()) return
    typingJob?.cancel()
    mutableQuery.value = ""
    scope.launch { refreshIdleSuggestions() }
    launch(Last.Similar(media))
  }

  override fun onRetry() {
    val again = last
    if (again != null) launch(again) else showIdle()
  }

  // --- Motore --------------------------------------------------------------------------------------------------------------------------

  /**
   * La ricerca [request], dopo [waitMillis] (il rimbalzo mentre si scrive; zero per un tocco). Finche' non risponde resta in vista cio' che c'era. [record]: la
   * ricerca entra fra le recenti (D32) quando risponde.
   */
  private fun launch(request: Last, waitMillis: Long = 0, record: Boolean = false) {
    cancelSearch()
    last = request
    searchJob = scope.launch {
      if (waitMillis > 0) delay(waitMillis)
      val found = try {
        when (request) {
          is Last.Query -> engine.search(SearchRequest(request.text, request.edited, showLikely = false, order = ResultOrder.RELEVANT))
          is Last.Similar ->
            if (request.media.size == 1) engine.similar(request.media.first(), ResultOrder.RELEVANT) else engine.similarToAll(request.media, ResultOrder.RELEVANT)
        }
      } catch (e: CancellationException) {
        // Annullata davvero (una ricerca nuova, la ricerca chiusa): si esce. Una cancellazione che arriva dal motore mentre noi siamo vivi non e'
        // la nostra (CE-11, motore fermo o indice non aperto): sarebbe un'attesa per sempre.
        if (!isActive) throw e
        unavailable()
        return@launch
      } catch (e: Exception) {
        unavailable()
        return@launch
      }
      results = found
      if (request is Last.Query) examples?.remember(request.text, found)
      publish(found)
      if (record && request is Last.Query) record(request.text, found.chips)
      if (request is Last.Query && mutableState.value is SearchScreenState.NoResults) offerRecents(request.text)
      follow(found)
    }
  }

  private fun unavailable() {
    results = null
    mutableState.value = SearchScreenState.Unavailable(SearchUnavailableReason.INTERNAL_ERROR)
  }

  /**
   * CE-8, CE-9: il modello delle query e' diventato pronto, o il motore ha finito di caricarsi. Se i risultati in vista erano senza il contenuto
   * (l'avviso "serve il modello" o "sto preparando"), la stessa ricerca si rifa' da sola, senza far sparire cio' che si vede.
   */
  private fun refreshContent() {
    val again = last as? Last.Query ?: return
    val shown = results ?: return
    if (shown.notices.none { it == SearchNotice.PreparingContentSearch || it == SearchNotice.OnlyFiltersAndText }) return
    launch(again)
  }

  /** E-Q13, CE-4: un media nascosto o eliminato altrove esce anche dai risultati in vista. */
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
    val notices = found.notices.filter { shownNotice(it) }
    val similarTo = (last as? Last.Similar)?.media.orEmpty()
    val similar = similarTo.size.takeIf { last is Last.Similar }
    val next = if (found.confident.size == 0 && found.likely.size == 0) {
      SearchScreenState.NoResults((last as? Last.Query)?.text, found.chips, notices, similar, similarTo)
    } else {
      SearchScreenState.Results(found.chips, found.confident, found.likely, found.coverage, notices, similar, similarTo)
    }
    // Gli stessi media nello stesso ordine (si e' aggiunta una lettera che non cambia niente): la griglia resta com'e'.
    if (SearchStates.sameShown(mutableState.value, next)) return
    mutableState.value = next
    // Tornano i risultati: in alto tornano i completamenti di cio' che si scrive, al posto delle recenti (CS-12).
    if (next is SearchScreenState.Results && recentsInTop) {
      recentsInTop = false
      setTop(lastCompletions)
    }
  }

  /** La barra in alto: i suggerimenti e, per la barra, le loro anteprime (gia' pronte o calcolate qui, fuori dal thread principale dove serve). */
  private fun setTop(items: List<SuggestionItem>) {
    mutableTop.value = items.map { it.suggestion }
    mutableStrip.value = items
  }

  /** "Le parole dentro le foto non si cercano": una volta sola per apertura dell'app, poi solo nella pagina (D31, §2.6). */
  private fun shownNotice(notice: SearchNotice): Boolean {
    if (notice != SearchNotice.TextReadingOff) return true
    if (textReadingOffShown) return false
    textReadingOffShown = true
    return true
  }

  private fun showIdle() {
    val current = mutableState.value
    if (current is SearchScreenState.Unavailable && current.reason == SearchUnavailableReason.EMPTY_LIBRARY) return
    mutableState.value = SearchScreenState.Idle
  }

  private fun cancelSearch() {
    searchJob?.cancel()
    liveJob?.cancel()
  }

  private fun cancelAll() {
    cancelSearch()
    typingJob?.cancel()
  }

  // --- Suggerimenti --------------------------------------------------------------------------------------------------------------------

  /**
   * I completamenti di cio' che si scrive nella barra in alto. Se la ricerca non trova niente e la barra propone le recenti (CS-12), un testo senza
   * completamenti le lascia dove sono: la barra non sparisce e ricompare a ogni tasto.
   */
  private suspend fun showCompletions(found: List<Suggestion>) {
    val items = examples?.withArt(found) ?: found.map { SuggestionItem(it) }
    lastCompletions = items
    if (found.isEmpty() && recentsInTop && mutableState.value is SearchScreenState.NoResults) return
    recentsInTop = false
    setTop(items)
  }

  /**
   * La ricerca di [searched] non ha trovato niente (CS-12 del giro 2): in alto le ultime ricerche, da cui ripartire, con la loro foto (I2). Senza nessuna
   * ricerca recente, gli esempi con la foto (I2: «un segno e le recenti o gli esempi, non una frase lunga»); senza nemmeno quelli la barra resta com'e'.
   */
  private suspend fun offerRecents(searched: String) {
    val idle = try {
      suggestions.idle()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return
    }
    val recents = SearchStates.recentTop(idle, searched)
    val items = when {
      recents.isNotEmpty() -> examples?.withArt(recents) ?: recents.map { SuggestionItem(it) }
      else -> examples?.examplesNow().orEmpty()
    }
    if (items.isEmpty()) return
    // Nel frattempo si e' scritto altro o e' arrivato qualcosa: non vale piu'.
    if (mutableState.value !is SearchScreenState.NoResults || mutableQuery.value.trim() != searched) return
    recentsInTop = true
    setTop(items)
  }

  private suspend fun completions(prefix: String): List<Suggestion> {
    val fromEngine = try {
      suggestions.complete(prefix).filter { it !is Suggestion.Term }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      emptyList()
    }
    return SearchStates.top(fromEngine + CalendarCompletions.of(prefix))
  }

  /**
   * La barra a campo vuoto. Con gli esempi (I2): la ricerca recente piu' nuova e gli esempi con la foto; [advance] fa girare la terna (alla chiusura,
   * per la prossima apertura). Senza: i migliori a riposo di solo testo (H4).
   */
  private suspend fun refreshIdleSuggestions(advance: Boolean = false) {
    val idle = try {
      suggestions.idle()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      IdleSuggestions(emptyList(), emptyList(), emptyList())
    }
    val items = examples?.strip(idle, advance) ?: SearchStates.idleTop(idle).map { SuggestionItem(it) }
    // Il campo si e' riempito mentre si leggevano: valgono i completamenti.
    if (mutableQuery.value.isBlank()) setTop(items)
  }

  private suspend fun record(text: String, chips: List<SearchChip>) {
    if (recorded == text) return
    recorded = text
    try {
      history.record(text, chips)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // La cronologia che non si scrive non ferma la ricerca: i dettagli sono nella Diagnostica.
    }
  }

  companion object {
    const val COMPLETION_DEBOUNCE_MILLIS: Long = 120

    /** La pausa dopo l'ultimo tasto prima di cercare: abbastanza per non cercare a ogni lettera, poco per sembrare immediata. */
    const val SEARCH_DEBOUNCE_MILLIS: Long = 250
    const val MIN_QUERY_CHARS: Int = 2

    /** Quanti suggerimenti stanno nella barra in alto (H4: «i primi tre suggerimenti»). */
    const val STRIP_SIZE: Int = 3
  }
}

/** Le regole pure della ricerca dentro la Libreria (H4): senza Compose ne' coroutine, provate su JVM. */
internal object SearchStates {

  /**
   * I migliori a riposo (H4): le ricerche recenti, poi i luoghi piu' frequenti, poi i tipi, al piu' [SearchController.STRIP_SIZE] e senza doppioni.
   * I tipi hanno le stesse parole che l'analizzatore riconosce (`LibrarySuggestions.TYPES`).
   */
  fun idleTop(idle: IdleSuggestions): List<Suggestion> {
    val labels = LibrarySuggestions.TYPES.toMap()
    val all = idle.recents.map { Suggestion.Recent(it) } +
      idle.places.map { Suggestion.PlaceSuggestion(it) } +
      idle.types.mapNotNull { type -> labels[type]?.let { Suggestion.Type(type, it) } }
    return top(all)
  }

  /** I primi [SearchController.STRIP_SIZE], senza due con la stessa parola (maiuscole a parte). */
  fun top(all: List<Suggestion>): List<Suggestion> = all.distinctBy { it.label.trim().lowercase() }.take(SearchController.STRIP_SIZE)

  /**
   * Le ricerche recenti da proporre quando la ricerca di [searched] non trova niente (CS-12 del giro 2): le ultime, senza quella appena scritta. Vengono
   * dalla stessa sorgente dei suggerimenti a riposo, con le sue regole (niente dai Nascosti, D29; "Disattiva e cancella" le toglie).
   */
  fun recentTop(idle: IdleSuggestions, searched: String): List<Suggestion> {
    val skip = searched.trim().lowercase()
    return top(idle.recents.filter { it.text.trim().lowercase() != skip }.map { Suggestion.Recent(it) })
  }

  /** Lo stesso contenuto in vista: stessi media nello stesso ordine sulla stessa istantanea, stesse note. */
  fun sameShown(a: SearchScreenState, b: SearchScreenState): Boolean = when {
    a is SearchScreenState.Results && b is SearchScreenState.Results ->
      a.chips == b.chips && a.coverage == b.coverage && a.notices == b.notices && a.similar == b.similar &&
        sameMedia(a.confident, b.confident) && sameMedia(a.likely, b.likely)
    else -> a == b
  }

  private fun sameMedia(a: OrderedTimelineView, b: OrderedTimelineView): Boolean {
    if (a === b) return true
    if (a.snapshot !== b.snapshot || a.size != b.size) return false
    for (i in 0 until a.size) if (a.snapshotIndex(i) != b.snapshotIndex(i)) return false
    return true
  }
}

private fun <T, R> StateFlow<T>.mapState(scope: CoroutineScope, transform: (T) -> R): StateFlow<R> =
  map(transform).stateIn(scope, SharingStarted.Eagerly, transform(value))
