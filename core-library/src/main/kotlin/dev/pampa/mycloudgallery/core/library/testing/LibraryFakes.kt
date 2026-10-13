package dev.pampa.mycloudgallery.core.library.testing

import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.library.contract.GridLevel
import dev.pampa.mycloudgallery.core.library.contract.GridStyle
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.LibraryPreferences
import dev.pampa.mycloudgallery.core.library.contract.LocalMediaActions
import dev.pampa.mycloudgallery.core.library.contract.PixelRect
import dev.pampa.mycloudgallery.core.library.contract.TimelineRepository
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.contract.ViewerAnchor
import dev.pampa.mycloudgallery.core.library.contract.ViewerItems
import dev.pampa.mycloudgallery.core.library.noop.EmptyTimelineSnapshot
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// I fake di core-library (D23): in src/main, cosi' i moduli a valle (feature-library, feature-viewer, :app) li riusano
// nei loro test e negli screenshot senza cicli. Mai nella release: tools/check.ps1 fallisce se una classe di un
// package `testing` compare nel dex dell'APK di release.

/**
 * Un repository con un indice scelto dal test: [publish] simula un cambiamento del catalogo (una modifica esterna, una
 * cancellazione) e tutte le viste gia' richieste si aggiornano, come farebbe il repository vero con `change_seq`.
 */
class FakeTimelineRepository(initial: TimelineSnapshot? = null) : TimelineRepository {

  private val snapshotFlow = MutableStateFlow(initial)
  private val views = HashMap<LibraryFilter, MutableStateFlow<TimelineView>>()

  override val snapshot: StateFlow<TimelineSnapshot?> = snapshotFlow

  /** Vero dopo `start()`. */
  var started: Boolean = false
    private set

  override suspend fun start() {
    started = true
  }

  override fun view(f: LibraryFilter): StateFlow<TimelineView> =
    views.getOrPut(f) { MutableStateFlow(InMemoryTimelineView(snapshotFlow.value ?: EmptyTimelineSnapshot, f)) }

  /** Il catalogo e' cambiato: nuovo indice, nuove viste. */
  fun publish(snapshot: TimelineSnapshot) {
    snapshotFlow.value = snapshot
    for ((filter, flow) in views) flow.value = InMemoryTimelineView(snapshot, filter)
  }
}

/** Le preferenze in memoria (nessun DataStore). */
class InMemoryLibraryPreferences(
  filter: LibraryFilter = LibraryFilter.ALL,
  level: GridLevel = GridLevel.DAYS,
  style: GridStyle = GridStyle.SQUARE,
) : LibraryPreferences {

  private val filterFlow = MutableStateFlow(filter)
  private val levelFlow = MutableStateFlow(level)
  private val styleFlow = MutableStateFlow(style)

  override val filter: StateFlow<LibraryFilter> = filterFlow
  override val level: StateFlow<GridLevel> = levelFlow
  override val style: StateFlow<GridStyle> = styleFlow

  override suspend fun setFilter(filter: LibraryFilter) {
    filterFlow.value = filter
  }

  override suspend fun setLevel(level: GridLevel) {
    levelFlow.value = level
  }

  override suspend fun setStyle(style: GridStyle) {
    styleFlow.value = style
  }
}

/** Preferiti e nascosti in memoria: registra cosa e' stato chiesto. Non tocca nessun indice. */
class FakeLocalMediaActions : LocalMediaActions {
  val favorites: MutableSet<MediaId> = linkedSetOf()
  val hidden: MutableSet<MediaId> = linkedSetOf()

  override suspend fun setFavorite(ids: Set<MediaId>, on: Boolean) {
    if (on) favorites += ids else favorites -= ids
  }

  override suspend fun setHidden(ids: Set<MediaId>, on: Boolean) {
    if (on) hidden += ids else hidden -= ids
  }
}

/** Gli elementi del viewer sopra una vista scelta dal test e una funzione per i riferimenti. */
class FakeViewerItems(
  initial: TimelineView,
  private val refOf: (MediaId) -> MediaRef,
) : ViewerItems {

  private val flow = MutableStateFlow(initial)
  override val view: StateFlow<TimelineView> = flow

  /** L'indice e' cambiato (E-V6: un elemento sparisce dalla vista). */
  fun publish(view: TimelineView) {
    flow.value = view
  }

  override fun ref(id: MediaId): MediaRef = refOf(id)
}

/**
 * L'ancora di un viewer finto: i bordi li sceglie il test ([bounds]) e `reveal` registra cosa e' stato portato in vista.
 */
class FakeViewerAnchor : ViewerAnchor {
  val bounds: MutableMap<MediaId, PixelRect> = HashMap()
  val revealed: MutableList<MediaId> = mutableListOf()

  override fun boundsOf(id: MediaId): PixelRect? = bounds[id]

  override suspend fun reveal(id: MediaId) {
    revealed += id
  }

  override fun thumbKey(id: MediaId): ThumbKey = ThumbKey(id, versionKey = 1, bucket = ThumbBucket.L)
}

/** Gli elementi del viewer per la Libreria sintetica: stessa vista, stessi riferimenti delle celle. */
fun SyntheticLibrary.viewerItems(filter: LibraryFilter = LibraryFilter.ALL): ViewerItems = FakeViewerItems(view(filter)) { ref(it) }
