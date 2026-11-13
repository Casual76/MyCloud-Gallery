package dev.pampa.mycloudgallery.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import dev.antigravity.fluidengine.ui.navigation.composable
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.ai.AiStack
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.contract.ViewerAnchor
import dev.pampa.mycloudgallery.core.library.contract.ViewerItems
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import dev.pampa.mycloudgallery.core.library.data.TimelineViewerItems
import dev.pampa.mycloudgallery.core.library.index.ColumnarTimelineSnapshot
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.feature.diagnostics.DiagnosticsGraphRoute
import dev.pampa.mycloudgallery.feature.search.contract.SearchActions
import dev.pampa.mycloudgallery.feature.search.contract.SearchRefs
import dev.pampa.mycloudgallery.feature.search.contract.SearchThumbnails
import dev.pampa.mycloudgallery.feature.search.models.AnalysisRoute
import dev.pampa.mycloudgallery.feature.search.models.SearchSettingsRoute
import dev.pampa.mycloudgallery.feature.search.models.TextReadingActivationRoute
import dev.pampa.mycloudgallery.feature.settings.SettingsGraphRoute
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.main.ViewerHostState
import dev.pampa.mycloudgallery.search.liveResultsView
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

// La NAVIGAZIONE della ricerca (Fase 6, P11; docs/fase6-progetto.md §1.4): la pagina Ricerca e modelli (dalle Impostazioni e dalla riga sopra la barra
// di ricerca) e la pagina di attivazione della lettura del testo. La ricerca stessa non e' una pagina (H4: sta dentro la Libreria, `MainScaffold`).
// I feature non si conoscono: ogni passaggio e' scritto qui.

/** Impostazioni → Ricerca e modelli (feature-search). */
@Serializable
data object SearchSettingsPageRoute

/** Pacchetto X: la pagina "Analisi delle foto" (dalla testata di Ricerca e modelli e dal pannello della nuvola). */
@Serializable
data object AnalysisPageRoute

/** La pagina di attivazione della lettura del testo (D41): l'unica che costruisce il consenso. */
@Serializable
data object TextReadingActivationPageRoute

fun NavGraphBuilder.searchGraph(nav: NavController, actions: SearchActions) {
  composable<SearchSettingsPageRoute> { SearchSettingsRoute(actions = actions, onBack = { nav.popBackStack() }) }
  composable<AnalysisPageRoute> {
    val host: SearchHostViewModel = hiltViewModel()
    AnalysisRoute(actions = actions, thumbnails = host.thumbnails, onBack = { nav.popBackStack() })
  }
  composable<TextReadingActivationPageRoute> { TextReadingActivationRoute(onClose = { nav.popBackStack() }) }
}

/** Cio' che la ricerca riceve dalla galleria: miniature solo locali, elementi per il viewer, richieste di "Trova simili". */
@HiltViewModel
class SearchHostViewModel @Inject constructor(private val gallery: GalleryGraph, private val stack: AiStack) : ViewModel() {

  /**
   * Le miniature di Cerca: come la Libreria (`thumbRefAt`: il telefono se c'e', altrimenti il NAS), ma senza mai leggere il NAS (D28, D33). Un media solo sul NAS
   * (sul tablet, quasi tutti) ha la miniatura nella replica locale: `SearchThumbnails` lo chiede al caricatore come richiesta "in arrivo", e la sorgente del NAS
   * non legge l'originale se la richiesta non e' visibile (CE-5).
   */
  val thumbnails = SearchThumbnails(
    loader = gallery.thumbnails,
    placeholders = gallery.placeholders,
    refs = SearchRefs { snapshot, index -> (snapshot as? ColumnarTimelineSnapshot)?.thumbRefAt(index) },
  )

  val similar get() = stack.similar

  val services get() = gallery.viewerServices

  /**
   * Gli elementi del viewer per una vista dei risultati: la vista ordinata di Cerca, **viva** (E-Q13, CE-4: un media nascosto o eliminato altrove esce anche dal
   * viewer aperto, come dalla griglia), i byte dal repository della Libreria.
   */
  fun itemsOf(results: OrderedTimelineView): ViewerItems = object : ViewerItems {
    private val delegate = TimelineViewerItems(gallery.repository, LibraryFilter.ALL)
    override val view: StateFlow<TimelineView> = liveResultsView(results, stack.search.data, viewModelScope)

    override fun ref(id: MediaId): MediaRef = delegate.ref(id)

    override fun refOrNull(id: MediaId): MediaRef? = delegate.refOrNull(id)
  }

  fun openFreeSpace() {
    gallery.fiveB?.freeSpace?.invoke(emptySet())
  }
}

/** Le azioni della ricerca fuori dalla ricerca: viewer sui risultati, Ricerca e modelli, attivazione della lettura, Diagnostica, Impostazioni. */
class AppSearchActions(
  private val nav: NavController,
  private val viewer: ViewerHostState?,
  private val host: SearchHostViewModel,
) : SearchActions {
  /** H4: il viewer sui risultati parte dalla cella toccata e ci torna, come dalla Libreria ([anchor] e' la griglia dei risultati). */
  override fun openResults(view: OrderedTimelineView, start: MediaId, anchor: ViewerAnchor) {
    viewer?.open(ViewerRequest(host.itemsOf(view), start, anchor, secure = false), host.services)
  }

  override fun openSearchSettings() = nav.navigate(SearchSettingsPageRoute)

  override fun openAnalysis() = nav.navigate(AnalysisPageRoute)

  override fun openTextReadingActivation() = nav.navigate(TextReadingActivationPageRoute)

  override fun openDiagnostics() = nav.navigate(DiagnosticsGraphRoute)

  override fun requestMediaAccess() = nav.navigate(SettingsGraphRoute)

  override fun openFreeSpace() = host.openFreeSpace()
}

/** Le azioni della ricerca per la navigazione di adesso. */
@Composable
fun rememberSearchActions(nav: NavController, viewer: ViewerHostState?, host: SearchHostViewModel = hiltViewModel()): SearchActions =
  remember(nav, viewer, host) { AppSearchActions(nav, viewer, host) }
