package dev.pampa.mycloudgallery.navigation

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.albums.AlbumViewerAnchor
import dev.pampa.mycloudgallery.albums.AlbumViewerItems
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import dev.pampa.mycloudgallery.core.library.data.TimelineViewerItems
import dev.pampa.mycloudgallery.core.library.index.ColumnarTimelineSnapshot
import dev.pampa.mycloudgallery.core.map.contract.CellKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.feature.map.contract.MapActions
import dev.pampa.mycloudgallery.feature.map.contract.MapRefs
import dev.pampa.mycloudgallery.feature.map.contract.MapThumbnails
import dev.pampa.mycloudgallery.feature.map.mini.MiniMapLook
import dev.pampa.mycloudgallery.feature.map.nav.openMap
import dev.pampa.mycloudgallery.feature.map.nav.openMapAt
import dev.pampa.mycloudgallery.feature.map.nav.openMapSettings
import dev.pampa.mycloudgallery.feature.map.nav.openPlace
import dev.pampa.mycloudgallery.feature.map.nav.openZone
import dev.pampa.mycloudgallery.feature.viewer.ViewerServices
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.main.ViewerHostState
import dev.pampa.mycloudgallery.map.AppViewerPlaceMap
import dev.pampa.mycloudgallery.map.MapGraph
import javax.inject.Inject

// LA MAPPA NELLA NAVIGAZIONE DELL'APP (Fase 9, P13; "per P13" di P4): le uscite delle schermate della mappa verso il viewer e le altre pagine, "Mostra sulla
// mappa" dal viewer, il tema della mini-mappa. feature-map, feature-viewer e feature-settings non si conoscono: le uscite le scrive questo file.

/**
 * Cio' che le schermate della mappa ricevono dalla galleria, uno per l'activity: le miniature della Libreria (come la Libreria) e il viewer aperto da una zona o
 * da un luogo (vivo sull'istantanea visibile, E-H1). Nasce con la schermata principale **senza costruire niente della mappa** (ne' impostazioni, ne' indice).
 */
@HiltViewModel
class MapHostViewModel @Inject constructor(
  private val gallery: GalleryGraph,
  val map: MapGraph,
  val placeMap: AppViewerPlaceMap,
) : ViewModel() {

  val thumbnails: MapThumbnails = MapThumbnails(
    gallery.thumbnails,
    gallery.placeholders,
    object : MapRefs {
      override fun refAt(snapshot: TimelineSnapshot, index: Int): MediaRef? = (snapshot as? ColumnarTimelineSnapshot)?.thumbRefAt(index)
    },
  )

  fun viewerRequest(view: OrderedTimelineView, start: MediaId): ViewerRequest = ViewerRequest(
    items = AlbumViewerItems(view, gallery.visibleTimeline.snapshot, TimelineViewerItems(gallery.repository, LibraryFilter.ALL), viewModelScope),
    start = start,
    anchor = AlbumViewerAnchor,
    secure = false,
  )
}

/** Le uscite delle schermate della mappa verso `:app` (P4, `MapActions`). "Zone senza Internet" e' di P5: finche' non c'e', il tocco non porta da nessuna parte. */
class AppMapActions(
  private val nav: NavController,
  private val viewer: ViewerHostState?,
  private val host: MapHostViewModel,
  private val services: () -> ViewerServices?,
) : MapActions {
  override fun openViewer(view: OrderedTimelineView, start: MediaId) {
    val base = services() ?: return
    viewer?.open(host.viewerRequest(view, start), base)
  }

  override fun openZone(key: CellKey) = nav.openZone(key)

  override fun openPlace(placeId: Int) = nav.openPlace(placeId)

  override fun openMap() = nav.openMap()

  override fun openMapAt(media: MediaId) = nav.openMapAt(media)

  override fun openMapSettings() = nav.openMapSettings()

  /** P5 (regioni offline): il punto d'aggancio della pagina delle regioni. */
  override fun openRegions() = Unit
}

/**
 * "Mostra sulla mappa" dal viewer (E-MAP19): chiude il viewer e apre la mappa centrata sul media; il colore del segno e il tema della mini-mappa dal tema dell'app
 * (mai colori scritti a mano); quando la galleria esce dalla composizione (blocco app) la mini-mappa dimentica le immagini.
 */
@Composable
internal fun MapHostEffect(nav: NavController, viewer: ViewerHostState?, host: MapHostViewModel) {
  val colors = MaterialTheme.colorScheme
  // Il tema dell'app (che puo' non seguire quello di sistema): scuro se la superficie e' scura.
  val dark = colors.surface.luminance() < DARK_LUMINANCE
  val density = LocalDensity.current.density
  SideEffect {
    host.map.look = MiniMapLook(dark = dark, marker = colors.primary.toArgb(), ring = colors.surface.toArgb(), density = density)
    host.placeMap.onShow = { media ->
      viewer?.close()
      nav.openMapAt(media)
    }
  }
  DisposableEffect(host) {
    onDispose {
      host.placeMap.onShow = {}
      host.map.onLocked()
    }
  }
}

private const val DARK_LUMINANCE = 0.5f
