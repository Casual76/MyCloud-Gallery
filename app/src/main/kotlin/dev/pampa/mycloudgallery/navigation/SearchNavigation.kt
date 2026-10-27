package dev.pampa.mycloudgallery.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.mycloudgallery.ai.AiStack
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.library.contract.PixelRect
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.contract.ViewerAnchor
import dev.pampa.mycloudgallery.core.library.contract.ViewerItems
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import dev.pampa.mycloudgallery.core.library.data.TimelineViewerItems
import dev.pampa.mycloudgallery.core.library.index.ColumnarTimelineSnapshot
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.feature.diagnostics.DiagnosticsGraphRoute
import dev.pampa.mycloudgallery.feature.search.SearchRoute
import dev.pampa.mycloudgallery.feature.search.contract.SearchActions
import dev.pampa.mycloudgallery.feature.search.contract.SearchRefs
import dev.pampa.mycloudgallery.feature.search.contract.SearchThumbnails
import dev.pampa.mycloudgallery.feature.search.models.SearchSettingsRoute
import dev.pampa.mycloudgallery.feature.search.models.TextReadingActivationRoute
import dev.pampa.mycloudgallery.feature.settings.SettingsGraphRoute
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.main.CollectionsSlot
import dev.pampa.mycloudgallery.main.ViewerHostState
import dev.pampa.mycloudgallery.search.liveResultsView
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

// La NAVIGAZIONE della ricerca (Fase 6, P11; docs/fase6-progetto.md §1.4): la scheda Cerca al posto del segnaposto, la pagina Ricerca e modelli
// (dalle Impostazioni e da Cerca) e la pagina di attivazione della lettura del testo. I feature non si conoscono: ogni passaggio e' scritto qui.

/** Impostazioni → Ricerca e modelli (feature-search). */
@Serializable
data object SearchSettingsPageRoute

/** La pagina di attivazione della lettura del testo (D41): l'unica che costruisce il consenso. */
@Serializable
data object TextReadingActivationPageRoute

fun NavGraphBuilder.searchGraph(nav: NavController, actions: SearchActions) {
  composable<SearchSettingsPageRoute> { SearchSettingsRoute(actions = actions, onBack = { nav.popBackStack() }) }
  composable<TextReadingActivationPageRoute> { TextReadingActivationRoute(onClose = { nav.popBackStack() }) }
}

/** Cio' che la scheda Cerca riceve dalla galleria: miniature solo locali, elementi per il viewer, richieste di "Trova simili". */
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

/** Il viewer aperto da Cerca non ha una griglia della Libreria sotto: niente bordi da cui partire, la bitmap si ricarica. */
private object SearchViewerAnchor : ViewerAnchor {
  override fun boundsOf(id: MediaId): PixelRect? = null

  override suspend fun reveal(id: MediaId) = Unit

  override fun thumbKey(id: MediaId): ThumbKey = ThumbKey(id, 0L, ThumbBucket.M)
}

/** Le azioni di Cerca fuori da Cerca: viewer sui risultati, Ricerca e modelli, attivazione della lettura, Diagnostica, Impostazioni. */
class AppSearchActions(
  private val nav: NavController,
  private val viewer: ViewerHostState?,
  private val host: SearchHostViewModel,
) : SearchActions {
  override fun openResults(view: OrderedTimelineView, start: MediaId) {
    viewer?.open(ViewerRequest(host.itemsOf(view), start, SearchViewerAnchor, secure = false), host.services)
  }

  override fun openSearchSettings() = nav.navigate(SearchSettingsPageRoute)

  override fun openTextReadingActivation() = nav.navigate(TextReadingActivationPageRoute)

  override fun openDiagnostics() = nav.navigate(DiagnosticsGraphRoute)

  override fun requestMediaAccess() = nav.navigate(SettingsGraphRoute)

  override fun openFreeSpace() = host.openFreeSpace()
}

/** La scheda Cerca nello scaffold (al posto del segnaposto): consegna alla pillola il suo fondale, come le altre schede. */
@Composable
fun SearchTab(
  slot: CollectionsSlot,
  actions: SearchActions,
  host: SearchHostViewModel,
  findSimilar: List<MediaId>?,
  onFindSimilarHandled: () -> Unit,
) {
  val backdrop = rememberGlassBackdrop()
  SideEffect { slot.backdropOut(backdrop) }
  Box(modifier = Modifier.fillMaxSize().glassBackdropSource(backdrop).padding(slot.contentPadding)) {
    SearchRoute(actions = actions, thumbnails = host.thumbnails, findSimilar = findSimilar, onFindSimilarHandled = onFindSimilarHandled)
  }
}

/** Le azioni di Cerca per la navigazione di adesso. */
@Composable
fun rememberSearchActions(nav: NavController, viewer: ViewerHostState?, host: SearchHostViewModel = hiltViewModel()): SearchActions =
  remember(nav, viewer, host) { AppSearchActions(nav, viewer, host) }
