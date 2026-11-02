package dev.pampa.mycloudgallery.navigation

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.R
import dev.pampa.mycloudgallery.albums.AlbumViewerAnchor
import dev.pampa.mycloudgallery.albums.AlbumViewerItems
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import dev.pampa.mycloudgallery.core.library.data.TimelineViewerItems
import dev.pampa.mycloudgallery.core.library.index.IndexOrderedTimelineView
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.widget.contract.WidgetTapRequest
import dev.pampa.mycloudgallery.core.widget.contract.WidgetTarget
import dev.pampa.mycloudgallery.feature.memories.nav.openAllMemories
import dev.pampa.mycloudgallery.feature.memories.nav.openMemory
import dev.pampa.mycloudgallery.main.ViewerHostState
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.widget.OpenWidgetRequests
import dev.pampa.mycloudgallery.widget.ServeOpenWidgetRequests
import dev.pampa.mycloudgallery.widget.WidgetGraph
import dev.pampa.mycloudgallery.window.ServesEntryRequests
import javax.inject.Inject
import kotlinx.coroutines.launch

// IL TOCCO SUL WIDGET, DOPO IL BLOCCO (Fase 10, P13; docs/fase10-progetto.md §2.2 "Tocco su una foto", D9, C17, E-W7, E-W8). L'evento interno porta solo i due numeri;
// cosa aprire lo decide il registro locale (`WidgetTaps.resolve` con la visibilita' di adesso): il viewer su quel media soltanto, la pagina del ricordo (o "Tutti i
// ricordi" se e' uscito), o la Libreria (con l'avviso se la foto non c'e' piu'). Lo serve solo la finestra principale, sotto il blocco dell'app.

/** Il tocco sul widget per la schermata principale. */
@HiltViewModel
class WidgetHostViewModel @Inject constructor(
  val requests: OpenWidgetRequests,
  private val widget: WidgetGraph,
  private val gallery: GalleryGraph,
) : ViewModel() {

  suspend fun resolve(tap: WidgetTapRequest): WidgetTarget = widget.resolve(tap)

  /** Il viewer sul **solo** media toccato (§2.2: niente "successiva"), vivo sull'istantanea visibile; null se la Libreria non lo mostra piu'. */
  fun viewerOf(media: MediaId): ViewerRequest? {
    val snapshot = gallery.visibleTimeline.snapshot.value ?: return null
    val view = IndexOrderedTimelineView.of(snapshot, listOf(media))
    if (view.size == 0) return null
    return ViewerRequest(
      items = AlbumViewerItems(view, gallery.visibleTimeline.snapshot, TimelineViewerItems(gallery.repository, LibraryFilter.ALL), viewModelScope),
      start = media,
      anchor = AlbumViewerAnchor,
      secure = false,
    )
  }
}

/** Il tocco sul widget servito **dopo il blocco dell'app** (questa composizione esiste solo ad app sbloccata) e solo dalla finestra principale. */
@Composable
internal fun WidgetTapEffect(nav: NavHostController, viewer: ViewerHostState?, host: WidgetHostViewModel, openViewer: (ViewerRequest) -> Unit) {
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  ServesEntryRequests {
    ServeOpenWidgetRequests(host.requests) { tap ->
      scope.launch {
        when (val target = host.resolve(tap)) {
          is WidgetTarget.Viewer -> {
            val request = host.viewerOf(target.media)
            if (request != null) {
              nav.popBackStack<MainRoute>(inclusive = false)
              openViewer(request)
            } else {
              library(nav, viewer, notice = true, context)
            }
          }
          is WidgetTarget.MemoryPage -> {
            viewer?.close()
            nav.popBackStack<MainRoute>(inclusive = false)
            runCatching { nav.openMemory(target.id) }.onFailure { nav.openAllMemories() }
          }
          is WidgetTarget.Library -> library(nav, viewer, target.notice, context)
        }
      }
    }
  }
}

private fun library(nav: NavHostController, viewer: ViewerHostState?, notice: Boolean, context: android.content.Context) {
  viewer?.close()
  nav.popBackStack<MainRoute>(inclusive = false)
  // E-W8: la foto toccata non c'e' piu' (nascosta, eliminata): lo si dice, senza dire quale.
  if (notice) Toast.makeText(context.applicationContext, R.string.widget_tap_gone, Toast.LENGTH_SHORT).show()
}
