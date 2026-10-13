package dev.pampa.mycloudgallery.feature.library.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import dev.pampa.mycloudgallery.core.imaging.contract.ViewerImageSource
import dev.pampa.mycloudgallery.core.imaging.noop.NoPlaceholderCache
import dev.pampa.mycloudgallery.core.imaging.noop.NoThumbnailLoader
import dev.pampa.mycloudgallery.core.imaging.noop.NoViewerImageSource
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.ViewerItems
import dev.pampa.mycloudgallery.core.library.noop.EmptyTimelineView
import dev.pampa.mycloudgallery.core.library.noop.EmptyViewerItems
import dev.pampa.mycloudgallery.core.library.noop.NoGridLayoutEngine
import dev.pampa.mycloudgallery.core.places.noop.NoPlaceNames
import dev.pampa.mycloudgallery.feature.library.grid.MediaGridState

/**
 * Cio' che [LibraryTab] mostra: la griglia, gli elementi che il viewer scorrera' e le immagini del viewer.
 *
 * **Stub di P0.** Nel progetto la Libreria prende tutto da un `LibraryViewModel` (Hilt) alimentato dal repository della
 * timeline (P5) e dal MediaStore (P2); finche' non ci sono, [LibraryTab] riceve uno stato scelto da chi la ospita:
 * [emptyLibraryTabState] (una Libreria vuota, null-object: e' la release) oppure, solo in debug e nei test,
 * `syntheticLibraryTabState` del package `testing` (la Libreria sintetica, sui fake). P7 sostituisce questo stato con il
 * ViewModel e toglie il parametro.
 */
@Stable
class LibraryTabState(
  val grid: MediaGridState,
  val viewerItems: ViewerItems,
  val viewerImages: ViewerImageSource,
  val photoCount: Int,
  val videoCount: Int,
)

/** Una Libreria vuota, tutta sui null-object: nessun media, nessuna miniatura, nessun luogo. */
fun emptyLibraryTabState(): LibraryTabState = LibraryTabState(
  grid = MediaGridState(
    view = EmptyTimelineView(LibraryFilter.ALL),
    loader = NoThumbnailLoader,
    placeholders = NoPlaceholderCache,
    placeNames = NoPlaceNames,
    engine = NoGridLayoutEngine,
    refOf = { throw NoSuchElementException("Nessun elemento nella Libreria vuota.") },
  ),
  viewerItems = EmptyViewerItems,
  viewerImages = NoViewerImageSource,
  photoCount = 0,
  videoCount = 0,
)

@Composable
fun rememberEmptyLibraryTabState(): LibraryTabState = remember { emptyLibraryTabState() }
