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
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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

  /**
   * Il viewer sul **solo** media toccato (§2.2: niente "successiva"), vivo sull'istantanea visibile. **Aspetta l'istantanea** (WG-4: a freddo il tocco arriva prima che la
   * timeline sia costruita) e distingue "la Libreria non lo mostra piu'" ([WidgetViewerOutcome.Gone]) da "non e' ancora pronta" ([WidgetViewerOutcome.NotReady]).
   */
  suspend fun viewerOf(media: MediaId): WidgetViewerOutcome = when (val found = lookupTappedMedia(gallery.visibleTimeline.snapshot, media)) {
    is TappedMedia.Found -> WidgetViewerOutcome.Open(
      ViewerRequest(
        items = AlbumViewerItems(found.view, gallery.visibleTimeline.snapshot, TimelineViewerItems(gallery.repository, LibraryFilter.ALL), viewModelScope),
        start = media,
        anchor = AlbumViewerAnchor,
        secure = false,
      ),
    )
    TappedMedia.Gone -> WidgetViewerOutcome.Gone
    TappedMedia.NotReady -> WidgetViewerOutcome.NotReady
  }
}

/** Cosa fare di un tocco su una foto del widget che il registro dice "Viewer" (WG-3, WG-4). */
sealed interface WidgetViewerOutcome {
  /** Apri il viewer su questa richiesta. */
  data class Open(val request: ViewerRequest) : WidgetViewerOutcome

  /** La Libreria non mostra piu' quel media (nascosto o eliminato nel frattempo): la Libreria con l'avviso "Questa foto non c'e' piu'" (E-W8). */
  data object Gone : WidgetViewerOutcome

  /** L'istantanea della Libreria non e' arrivata nel tempo (a freddo, archivio grande): la Libreria **senza** avviso, la foto c'e' ma non si sa ancora. */
  data object NotReady : WidgetViewerOutcome
}

/** Cosa trova la ricerca del media toccato sull'istantanea (puro: si prova sulla JVM con il tempo virtuale). */
internal sealed interface TappedMedia {
  data class Found(val view: IndexOrderedTimelineView) : TappedMedia

  data object Gone : TappedMedia

  data object NotReady : TappedMedia
}

/**
 * WG-4: cerca [media] sull'istantanea. A freddo (`am force-stop`, poi il tocco) l'app parte e il registro risponde "Viewer" **prima** che la timeline sia costruita: l'istantanea
 * e' ancora null. Si **aspetta** (al piu' [waitMs]) invece di rispondere "non c'e' piu'"; solo con l'istantanea in mano un media che non c'e' e' [TappedMedia.Gone], e se non arriva
 * nel tempo e' [TappedMedia.NotReady].
 */
internal suspend fun lookupTappedMedia(snapshot: StateFlow<TimelineSnapshot?>, media: MediaId, waitMs: Long = TAP_SNAPSHOT_WAIT_MS): TappedMedia {
  val current = snapshot.value ?: withTimeoutOrNull(waitMs) { snapshot.filterNotNull().first() } ?: return TappedMedia.NotReady
  val view = IndexOrderedTimelineView.of(current, listOf(media))
  return if (view.size == 0) TappedMedia.Gone else TappedMedia.Found(view)
}

/** Quanto aspetta l'istantanea un tocco a freddo prima di aprire la Libreria senza avviso: il caricamento intero costa circa 1,3 s (principio dell'interfaccia istantanea). */
internal const val TAP_SNAPSHOT_WAIT_MS: Long = 3_000

/**
 * WG-3: apre il viewer sulla foto toccata. Un viewer **gia' aperto** (l'utente guardava una foto, e' andato alla Home e ha toccato il widget) si chiude **prima**:
 * `ViewerHostState.open` con una richiesta gia' aperta esce subito e l'app tornerebbe alla foto di prima. Poi la navigazione torna alla radice e si apre la richiesta.
 */
internal fun showWidgetViewer(viewer: ViewerHostState?, request: ViewerRequest, toMain: () -> Unit, openViewer: (ViewerRequest) -> Unit) {
  viewer?.close()
  toMain()
  openViewer(request)
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
          is WidgetTarget.Viewer -> when (val outcome = host.viewerOf(target.media)) {
            is WidgetViewerOutcome.Open -> showWidgetViewer(viewer, outcome.request, { nav.popBackStack<MainRoute>(inclusive = false) }, openViewer)
            WidgetViewerOutcome.Gone -> library(nav, viewer, notice = true, context)
            WidgetViewerOutcome.NotReady -> library(nav, viewer, notice = false, context)
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
