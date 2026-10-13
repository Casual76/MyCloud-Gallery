package dev.pampa.mycloudgallery.feature.library.testing

import dev.pampa.mycloudgallery.core.imaging.testing.FakePlaceholderCache
import dev.pampa.mycloudgallery.core.imaging.testing.FakeThumbnailLoader
import dev.pampa.mycloudgallery.core.imaging.testing.FakeViewerImageSource
import dev.pampa.mycloudgallery.core.library.contract.GridLevel
import dev.pampa.mycloudgallery.core.library.contract.GridStyle
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.testing.SimpleGridLayoutEngine
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.library.testing.viewerItems
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.places.testing.FakePlaceData
import dev.pampa.mycloudgallery.core.places.testing.FakePlaces
import dev.pampa.mycloudgallery.feature.library.grid.MediaGridState
import dev.pampa.mycloudgallery.feature.library.library.LibraryTabState

// I fake della Libreria (D23): in src/main, cosi' :app (solo debug) e i test li usano senza cicli. Mai nella release:
// tools/check.ps1 fallisce se una classe di un package `testing` compare nel dex dell'APK di release. Nessun codice di
// produzione di questo modulo li referenzia.

/**
 * La Libreria sintetica pronta per la griglia: [count] elementi deterministici su 15 anni, miniature a gradiente, luoghi
 * veri del mini-dataset di prova, motore di layout di riferimento e un viewer finto (docs/fase3-progetto.md §5.2).
 * E' il "funziona sui fake" di P0: la griglia sintetica che M1 misurera' (P9) e che gli screenshot (2.000 elementi) usano.
 */
fun syntheticLibraryTabState(
  count: Int = SyntheticLibrary.DEFAULT_COUNT,
  seed: Long = 1L,
  level: GridLevel = GridLevel.DAYS,
  style: GridStyle = GridStyle.SQUARE,
  filter: LibraryFilter = LibraryFilter.ALL,
): LibraryTabState {
  val library = SyntheticLibrary(seed = seed, count = count, placeIds = FakePlaceData.IDS)
  val view = library.view(filter)
  val grid = MediaGridState(
    view = view,
    loader = FakeThumbnailLoader(),
    placeholders = FakePlaceholderCache(),
    placeNames = FakePlaces(),
    engine = SimpleGridLayoutEngine(),
    refOf = { library.ref(it) },
    level = level,
    style = style,
  )
  val snapshot = library.snapshot
  val videos = (0 until snapshot.size).count { snapshot.kindAt(it) == MediaKind.VIDEO }
  return LibraryTabState(
    grid = grid,
    viewerItems = library.viewerItems(filter),
    viewerImages = FakeViewerImageSource(),
    photoCount = snapshot.size - videos,
    videoCount = videos,
  )
}
