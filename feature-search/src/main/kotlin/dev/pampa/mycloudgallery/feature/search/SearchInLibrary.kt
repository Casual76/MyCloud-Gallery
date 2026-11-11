package dev.pampa.mycloudgallery.feature.search

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.pampa.mycloudgallery.core.designsystem.a11y.GalleryLiveAnnouncement
import dev.pampa.mycloudgallery.core.designsystem.chrome.GallerySuggestionBar
import dev.pampa.mycloudgallery.core.designsystem.design.holdsTouches
import dev.pampa.mycloudgallery.core.designsystem.motion.GalleryMotion
import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.library.contract.ViewerAnchor
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.models.contract.ModelState
import dev.pampa.mycloudgallery.core.search.contract.SearchReadiness
import dev.pampa.mycloudgallery.core.search.contract.Suggestion
import dev.pampa.mycloudgallery.feature.search.contract.SearchScreenState
import dev.pampa.mycloudgallery.feature.search.contract.SearchSelection
import dev.pampa.mycloudgallery.feature.search.contract.SearchThumbnails

// LA RICERCA DENTRO LA LIBRERIA (H4 della fase H, 11/11): i pezzi che `:app` posa nello scaffold e nella Libreria. Niente pagina: la barra di ricerca e'
// nel posto della pillola (core-designsystem), qui ci sono la barra dei suggerimenti in alto, i risultati al posto della griglia e la riga sopra la barra.
// Ogni pezzo legge dalla [SearchSource] solo cio' che gli serve: scrivere ricompone il campo e la barra in alto, non la griglia (che cambia solo quando
// cambiano i risultati).

/**
 * Cio' che la ricerca mostra adesso, letto pezzo per pezzo (ogni proprieta' e' uno stato di Compose: chi la legge si ricompone quando cambia, e solo lui).
 * In produzione la da' il [SearchController] ([rememberSearchSource]); nelle prove e negli screenshot e' fissa ([FixedSearchSource]).
 */
@Stable
interface SearchSource {
  val query: String

  /** I suggerimenti della barra in alto, al piu' tre. */
  val suggestions: List<Suggestion>

  val state: SearchScreenState

  val readiness: SearchReadiness

  val queryModel: ModelState?
}

/** Una ricerca ferma (prove e screenshot). */
@Immutable
data class FixedSearchSource(
  override val query: String = "",
  override val suggestions: List<Suggestion> = emptyList(),
  override val state: SearchScreenState = SearchScreenState.Idle,
  override val readiness: SearchReadiness = SearchReadiness.Ready,
  override val queryModel: ModelState? = null,
) : SearchSource

/** La sorgente del [SearchController], viva finche' la pagina e' in primo piano. */
@Composable
fun rememberSearchSource(controller: SearchController): SearchSource {
  val query = controller.query.collectAsStateWithLifecycle()
  val suggestions = controller.topSuggestions.collectAsStateWithLifecycle()
  val state = controller.state.collectAsStateWithLifecycle()
  val readiness = controller.readiness.collectAsStateWithLifecycle()
  val model = controller.queryModel.collectAsStateWithLifecycle()
  return remember(query, suggestions, state, readiness, model) { StateSearchSource(query, suggestions, state, readiness, model) }
}

private class StateSearchSource(
  private val queryState: State<String>,
  private val suggestionsState: State<List<Suggestion>>,
  private val screenState: State<SearchScreenState>,
  private val readinessState: State<SearchReadiness>,
  private val modelState: State<ModelState?>,
) : SearchSource {
  override val query: String get() = queryState.value
  override val suggestions: List<Suggestion> get() = suggestionsState.value
  override val state: SearchScreenState get() = screenState.value
  override val readiness: SearchReadiness get() = readinessState.value
  override val queryModel: ModelState? get() = modelState.value
}

/** Sopra la griglia della Libreria va qualcosa: i risultati, o il velo di "nessun risultato" (la Libreria resta dietro, attenuata). */
fun SearchScreenState.showsResults(): Boolean = this is SearchScreenState.Results || this is SearchScreenState.NoResults

/** Il segnaposto del campo di ricerca. */
@Composable
fun searchFieldHint(): String = stringResource(R.string.search_field_hint)

/**
 * La barra dei suggerimenti in alto (H4): i primi tre di [source], un tocco li applica ([SearchHandlers.onSuggestion]). Vuota, si ritira.
 */
@Composable
fun SearchSuggestions(source: SearchSource, handlers: SearchHandlers, backdrop: GlassBackdropState?, modifier: Modifier = Modifier) {
  val suggestions = source.suggestions
  val labels = remember(suggestions) { suggestions.map { it.label } }
  // Le risorse di adesso (cambiano con la Configuration: lingua, testo grande), chiave del remember: TalkBack non legge mai un testo vecchio.
  val resources = LocalResources.current
  val descriptions = remember(suggestions, resources) { suggestions.map { resources.getString(R.string.search_suggestion_description, it.label) } }
  GallerySuggestionBar(
    labels = labels,
    descriptions = descriptions,
    onPick = { index -> suggestions.getOrNull(index)?.let(handlers::onSuggestion) },
    backdrop = backdrop,
    modifier = modifier,
    paneTitle = stringResource(R.string.search_suggestions_title),
  )
}

/** Quanto il velo copre la Libreria quando non c'e' niente da mostrare: dietro si riconosce, ma non sembra un risultato (CU-11). */
private const val NoResultsVeil = 0.78f

/**
 * I risultati al posto della griglia della Libreria (H4): la griglia dei risultati, o, se non c'e' niente, un velo sopra la Libreria attenuata (la riga
 * sopra la barra dice perche').
 * Fra una lista e la successiva una dissolvenza breve; mentre il motore lavora resta la lista di prima. [lift] e' la salita con la tastiera (in pixel,
 * letta nel disegno): la griglia sale insieme alla barra, cosi' la tastiera non copre mai i primi risultati. [bottomExtra]: lo spazio della riga sopra
 * la barra, se c'e'.
 */
@Composable
fun SearchResults(
  source: SearchSource,
  thumbnails: SearchThumbnails,
  onOpen: (view: OrderedTimelineView, media: MediaId, anchor: ViewerAnchor) -> Unit,
  contentPadding: PaddingValues,
  modifier: Modifier = Modifier,
  selection: SearchSelection? = null,
  lift: () -> Float = { 0f },
  bottomExtra: Dp? = null,
  onUserScroll: () -> Unit = {},
) {
  val state = source.state
  val reduced = LocalFluidMotionPolicy.current.reducedMotion
  val direction = LocalLayoutDirection.current
  val padding = remember(contentPadding, bottomExtra, direction) {
    if (bottomExtra == null) {
      contentPadding
    } else {
      PaddingValues(
        start = contentPadding.calculateStartPadding(direction),
        top = contentPadding.calculateTopPadding(),
        end = contentPadding.calculateEndPadding(direction),
        bottom = contentPadding.calculateBottomPadding() + bottomExtra,
      )
    }
  }
  Box(modifier = modifier.fillMaxSize().graphicsLayer { translationY = -lift() }) {
    AnimatedContent(
      targetState = state,
      contentKey = { (it as? SearchScreenState.Results)?.let(::listKey) ?: it::class },
      // Fra una lista e la successiva una dissolvenza breve nello stesso posto (non e' una pagina che arriva).
      transitionSpec = { GalleryMotion.contentSwap(reduced) },
      label = "risultati",
    ) { shown ->
      when (shown) {
        is SearchScreenState.Results -> SearchResultsGrid(
          results = shown,
          thumbnails = thumbnails,
          onOpen = onOpen,
          contentPadding = padding,
          selection = selection,
          onUserScroll = onUserScroll,
        )
        // Niente da mostrare (nessun risultato, o la Libreria che torna): un velo del fondo della pagina sopra la Libreria, che resta dietro attenuata
        // (CU-11 del giro 1: prima lo schermo diventava un fondo vuoto e sembrava un caricamento non riuscito). Il velo trattiene i tocchi: la Libreria
        // attenuata non e' un risultato, non si apre ne' si scorre.
        else -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = NoResultsVeil)).holdsTouches())
      }
    }
    val count = (state as? SearchScreenState.Results)?.confident?.size
    if (count != null) {
      val spoken = pluralStringResource(R.plurals.search_results_count, count, GalleryNumbers.format(count))
      // Fase 10 P8 (D23 (6)): il conteggio dei risultati si annuncia una volta a ogni lista nuova, non a ogni cella.
      GalleryLiveAnnouncement(spoken)
    }
  }
}

/**
 * La chiave di una lista: la stessa finche' i media sono gli stessi nello stesso ordine (un media nascosto altrove la cambia). Cosi' un aggiornamento
 * dell'istantanea che non cambia i risultati (un preferito) non rifa' la dissolvenza ne' riporta la griglia all'inizio.
 */
private fun listKey(results: SearchScreenState.Results): Any {
  val ids = ArrayList<MediaId>(results.confident.size + results.likely.size)
  for (view in listOf(results.confident, results.likely)) for (i in 0 until view.size) ids += view.snapshot.idAt(view.snapshotIndex(i))
  return ids
}

/** La riga sopra la barra come la mostra la barra: il testo, l'azione e che cosa dice TalkBack del tocco. */
@Immutable
data class SearchLineUi(val text: String, val action: SearchLineAction?, val actionLabel: String?)

/** Che cosa fa toccare la riga sopra la barra: le uscite le fa `:app` ([SearchActions][dev.pampa.mycloudgallery.feature.search.contract.SearchActions]). */
enum class SearchLineAction { OPEN_SEARCH_SETTINGS, OPEN_TEXT_READING, ALLOW_ACCESS, RETRY }

/** La riga sopra la barra per [source] (null = niente da dire). Legge lo stato, la prontezza del motore e il modello, non il testo del campo. */
@Composable
fun rememberSearchLine(source: SearchSource): SearchLineUi? {
  val state = source.state
  val readiness = source.readiness
  val model = source.queryModel
  val line = remember(state, readiness, model) { SearchLines.of(state, readiness, model) } ?: return null
  val action = when (line.action) {
    NoticeAction.DOWNLOAD_MODEL, NoticeAction.WHY_COVERAGE, NoticeAction.ENABLE_INDEXER -> SearchLineAction.OPEN_SEARCH_SETTINGS
    NoticeAction.LEARN_TEXT_READING -> SearchLineAction.OPEN_TEXT_READING
    NoticeAction.ALLOW_ACCESS -> SearchLineAction.ALLOW_ACCESS
    NoticeAction.RETRY -> SearchLineAction.RETRY
    null -> null
  }
  return SearchLineUi(searchLineText(line), action, searchLineActionLabel(line.action))
}

/**
 * Consegna **una volta** la richiesta di "Trova simili" (TS-2): [onFind] parte quando [request] non e' nulla e subito dopo [onHandled] la azzera in chi la
 * tiene. Se la richiesta restasse al suo posto, ogni ricomposizione la ripeterebbe; e una seconda richiesta sullo stesso media non cambierebbe il valore,
 * quindi non farebbe niente.
 */
@Composable
fun FindSimilarEffect(request: List<MediaId>?, onFind: (List<MediaId>) -> Unit, onHandled: () -> Unit) {
  LaunchedEffect(request) {
    if (request != null) {
      onFind(request)
      onHandled()
    }
  }
}
