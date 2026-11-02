package dev.pampa.mycloudgallery.navigation

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import dev.antigravity.fluidengine.ui.navigation.composable
import dev.antigravity.fluidengine.ui.page.FluidBackSwipe
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.R
import dev.pampa.mycloudgallery.albums.AlbumRequests
import dev.pampa.mycloudgallery.albums.AlbumViewerAnchor
import dev.pampa.mycloudgallery.albums.AlbumViewerItems
import dev.pampa.mycloudgallery.albums.AppViewerAlbumActions
import dev.pampa.mycloudgallery.albums.PickOutcome
import dev.pampa.mycloudgallery.albums.PickRelay
import dev.pampa.mycloudgallery.albums.LibraryAlbumRefs
import dev.pampa.mycloudgallery.albums.withAlbums
import dev.pampa.mycloudgallery.core.albums.contract.Albums
import dev.pampa.mycloudgallery.core.albums.contract.CreateOutcome
import dev.pampa.mycloudgallery.core.albums.contract.TitleProblem
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import dev.pampa.mycloudgallery.core.library.data.TimelineViewerItems
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.albums.choose.AddToAlbumHost
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumActions
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumThumbnails
import dev.pampa.mycloudgallery.feature.albums.list.CreateAlbumSheet
import dev.pampa.mycloudgallery.feature.albums.nav.AlbumPageDestination
import dev.pampa.mycloudgallery.feature.albums.nav.openAlbum
import dev.pampa.mycloudgallery.feature.albums.nav.openSmartEditor
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumHost
import dev.pampa.mycloudgallery.feature.diagnostics.DiagnosticsGraphRoute
import dev.pampa.mycloudgallery.feature.library.collections.MediaPickerRoute
import dev.pampa.mycloudgallery.feature.viewer.ViewerServices
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.main.ViewerHostState
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

// GLI ALBUM NELLA NAVIGAZIONE DELL'APP (Fase 7, P13; P10: "albumsGraph(nav, actions, thumbnails)", "AddToAlbumHost sopra la schermata che l'ha
// chiesto", "AlbumActions: openViewer, pickMedia, openAlbum, openDiagnostics"). feature-albums, feature-library e feature-viewer non si conoscono:
// le uscite di una verso le altre le scrive questo file.

/** Il selettore dei media degli album: [forAlbum] l'album a cui si aggiunge (null = le foto d'esempio di uno smart), al piu' [max] media. */
@Serializable
data class MediaPickerDestination(val forAlbum: String? = null, val max: Int = 1)

/**
 * Cio' che le schermate degli album ricevono dalla galleria, uno per l'activity (lo stesso per tutte le rotte degli album e per il selettore): le
 * miniature locali, il viewer aperto da un album (vivo sull'indice della Libreria visibile), la risposta del selettore.
 */
@HiltViewModel
class AlbumsHostViewModel @Inject constructor(private val gallery: GalleryGraph) : ViewModel() {

  val requests: AlbumRequests get() = gallery.albumRequests

  /** Solo cio' che e' gia' sul dispositivo (P10-8): le miniature della Libreria, senza nascosti. */
  val thumbnails: AlbumThumbnails = AlbumThumbnails(gallery.thumbnails, gallery.placeholders, LibraryAlbumRefs { gallery.visibleTimeline.snapshot.value })

  private val relay = PickRelay(gallery.albumRequests)

  /** Il selettore sta per aprirsi: [onPicked] ricevera' la scelta (una volta sola). */
  fun startPick(onPicked: (List<MediaId>) -> Unit) = relay.start(onPicked)

  /**
   * La scelta del selettore: va a chi l'ha chiesta. Se il processo e' rinato nel frattempo la richiamata non c'e' piu' (AA-3): per "Aggiungi foto" la scelta
   * diventa un "Aggiungi ad album" con l'album della rotta, per le foto d'esempio l'esito e' [PickOutcome.Lost] e la schermata lo dice.
   */
  fun picked(ids: List<MediaId>, forAlbum: String?): PickOutcome = relay.picked(ids, forAlbum)

  fun cancelPick() = relay.cancel()

  /** Il viewer sugli elementi di un album, nell'ordine dell'album e vivo (un nascosto esce anche dal viewer aperto, E-H1). */
  fun viewerRequest(view: OrderedTimelineView, start: MediaId): ViewerRequest = ViewerRequest(
    items = AlbumViewerItems(view, gallery.visibleTimeline.snapshot, TimelineViewerItems(gallery.repository, LibraryFilter.ALL), viewModelScope),
    start = start,
    anchor = AlbumViewerAnchor,
    secure = false,
  )

  /** I servizi per una richiesta delle Raccolte: quella dei Nascosti (`secure`) ha le voci degli album dei Nascosti (`fromHidden`). */
  fun servicesForRequest(base: ViewerServices, request: ViewerRequest): ViewerServices =
    if (request.secure) servicesFor(base, album = null, fromHidden = true) else base

  /** I servizi del viewer con le voci ⋮ di [album] (aperto dalla sua pagina) o dei Nascosti ([fromHidden]): sopra [base], tutto il resto uguale. */
  fun servicesFor(base: ViewerServices, album: AlbumId?, fromHidden: Boolean): ViewerServices {
    val actions = gallery.viewerAlbums as? AppViewerAlbumActions ?: return base
    return base.withAlbums(actions.within(album, fromHidden))
  }
}

/** Le uscite delle schermate degli album verso `:app` (P10): il viewer sopra la pagina, il selettore dei media, un altro album, la Diagnostica. */
class AppAlbumActions(
  private val nav: NavController,
  private val viewer: ViewerHostState?,
  private val host: AlbumsHostViewModel,
  private val services: () -> ViewerServices?,
) : AlbumActions {
  override fun openViewer(view: OrderedTimelineView, start: MediaId) {
    val base = services() ?: return
    viewer?.open(host.viewerRequest(view, start), host.servicesFor(base, currentAlbum(), fromHidden = false))
  }

  override fun openAlbum(id: AlbumId) = nav.openAlbum(id)

  override fun pickMedia(forAlbum: AlbumId?, max: Int, onPicked: (List<MediaId>) -> Unit) {
    if (max <= 0) return
    host.startPick(onPicked)
    nav.navigate(MediaPickerDestination(forAlbum?.value, max))
  }

  override fun openDiagnostics() = nav.navigate(DiagnosticsGraphRoute)

  /** L'album la cui pagina e' in cima (le voci ⋮ "Rimuovi" e "Copertina" valgono solo li'); null da "Suggerite", dai viaggi o dall'editor. */
  private fun currentAlbum(): AlbumId? {
    val entry = nav.currentBackStackEntry ?: return null
    if (!entry.destination.hasRoute<AlbumPageDestination>()) return null
    return runCatching { AlbumId(entry.toRoute<AlbumPageDestination>().id) }.getOrNull()
  }
}

/** Il selettore dei media nel grafo (P13): la pagina di feature-library, la risposta a chi l'ha aperta, poi indietro. */
fun NavGraphBuilder.mediaPickerGraph(nav: NavController, host: AlbumsHostViewModel) {
  composable<MediaPickerDestination>(backSwipe = FluidBackSwipe.EdgeOnly) { entry ->
    val route = entry.toRoute<MediaPickerDestination>()
    val title = stringResource(if (route.forAlbum != null) R.string.albums_picker_add_title else R.string.albums_picker_examples_title)
    val context = LocalContext.current
    val lost = stringResource(R.string.albums_picker_lost)
    MediaPickerRoute(
      title = title,
      max = route.max,
      onPicked = { ids ->
        nav.popBackStack()
        // AA-3: dopo la morte del processo la richiamata non c'e' piu': la scelta non si perde in silenzio.
        if (host.picked(ids, route.forAlbum) == PickOutcome.Lost) Toast.makeText(context, lost, Toast.LENGTH_LONG).show()
      },
      onBack = {
        host.cancelPick()
        nav.popBackStack()
      },
    )
  }
}

/** "Aggiungi ad album" da fuori (selezione, viewer, Nascosti): la scelta dell'album sopra la schermata di adesso, una richiesta per volta. */
@Composable
internal fun AlbumRequestsHost(host: AlbumsHostViewModel, nav: NavController, viewer: ViewerHostState?) {
  val request by host.requests.addToAlbum.collectAsStateWithLifecycle()
  val current = request ?: return
  AddToAlbumHost(
    request = current,
    onDismiss = { host.requests.done(current) },
    onOpenAlbum = { id ->
      viewer?.close()
      nav.openAlbum(id)
    },
  )
}

/** "Nuovo album" dalle Raccolte (`albums/new`): un album a mano nasce e si apre; uno smart passa dall'editor delle regole, che parte dal nome. */
@HiltViewModel
class NewAlbumViewModel @Inject constructor(private val albums: Albums) : ViewModel() {
  suspend fun createManual(title: String): CreateOutcome = albums.create(title, AlbumKind.MANUAL, null)
}

@Composable
internal fun NewAlbumHost(visible: Boolean, onDismiss: () -> Unit, nav: NavController, viewModel: NewAlbumViewModel = hiltViewModel()) {
  var problem by remember { mutableStateOf<TitleProblem?>(null) }
  var unavailable by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  val close = {
    problem = null
    unavailable = false
    onDismiss()
  }
  AlbumHost {
    CreateAlbumSheet(
      visible = visible,
      problem = problem,
      unavailable = unavailable,
      onCreate = { title, kind ->
        if (kind == AlbumKind.SMART) {
          close()
          nav.openSmartEditor(title.trim())
        } else {
          scope.launch {
            when (val out = viewModel.createManual(title)) {
              is CreateOutcome.Created -> {
                close()
                nav.openAlbum(out.id)
              }
              is CreateOutcome.Invalid -> problem = out.problem
              CreateOutcome.RulesTooLong, CreateOutcome.Unavailable -> unavailable = true
            }
          }
        }
      },
      onDismiss = close,
    )
  }
}
