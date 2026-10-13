package dev.pampa.mycloudgallery.feature.library.library.testing

import androidx.compose.runtime.State
import androidx.compose.ui.graphics.ImageBitmap
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbStats
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.imaging.testing.FakeThumbnailLoader
import dev.pampa.mycloudgallery.core.imaging.testing.FakeViewerImageSource
import dev.pampa.mycloudgallery.core.library.contract.GridLayoutEngine
import dev.pampa.mycloudgallery.core.library.contract.GridLevel
import dev.pampa.mycloudgallery.core.library.contract.GridStyle
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.LocalMediaActions
import dev.pampa.mycloudgallery.core.library.layout.TimelineLayoutEngine
import dev.pampa.mycloudgallery.core.library.testing.FakeTimelineRepository
import dev.pampa.mycloudgallery.core.library.testing.InMemoryLibraryPreferences
import dev.pampa.mycloudgallery.core.library.testing.InMemoryTimelineSnapshot
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.places.testing.FakePlaceData
import dev.pampa.mycloudgallery.core.places.testing.FakePlaces
import dev.pampa.mycloudgallery.feature.library.grid.MediaGridState
import dev.pampa.mycloudgallery.feature.library.library.LibraryCommands
import dev.pampa.mycloudgallery.feature.library.library.LibraryDebugHooks
import dev.pampa.mycloudgallery.feature.library.library.LibraryPhoneStatus
import dev.pampa.mycloudgallery.feature.library.library.LibraryTabState
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// La Libreria sintetica pronta per la scheda (docs/fase3-progetto.md §5.2), per il debug (M1), i test e gli screenshot.
// Package `testing`: mai nella release.

/** Le dimensioni che il menu di debug offre per M1: quella degli screenshot e quella del collaudo (100 mila). */
val M1SyntheticSizes: List<Int> = listOf(SyntheticLibrary.DEFAULT_COUNT, 100_000)

/** Oltre questa dimensione la Libreria sintetica usa il caricatore a finestra (il finto sincrono non regge la memoria). */
const val WindowedLoaderAbove: Int = 5_000

/**
 * La Libreria sintetica: [count] elementi deterministici su 15 anni, miniature a gradiente, luoghi del mini-dataset di
 * prova, filtro e stile ricordati in memoria, preferiti che cambiano davvero la cella. Il viewer e' quello finto.
 *
 * Fino a [WindowedLoaderAbove] elementi le miniature arrivano subito (il caricatore finto sincrono: gli screenshot sono
 * ripetibili); oltre, dal [WindowedThumbnailLoader] (fuori dal thread principale, a finestra, con un tetto di memoria),
 * che e' il comportamento da misurare in M1.
 *
 * @param debugSizes se non e' null, il menu della vista offre queste dimensioni (solo debug, M1).
 * @param engine il motore di layout: quello vero di P5 (`TimelineLayoutEngine`).
 */
fun syntheticLibraryState(
  count: Int = SyntheticLibrary.DEFAULT_COUNT,
  seed: Long = 1L,
  level: GridLevel = GridLevel.DAYS,
  style: GridStyle = GridStyle.SQUARE,
  filter: LibraryFilter = LibraryFilter.ALL,
  phone: LibraryPhoneStatus = LibraryPhoneStatus(),
  debugSizes: List<Int>? = null,
  engine: GridLayoutEngine = TimelineLayoutEngine(),
  layoutDispatcher: CoroutineDispatcher = Dispatchers.Default,
  today: LocalDate = SyntheticToday,
): LibraryTabState {
  val holder = SyntheticHolder(SyntheticLibrary(seed = seed, count = count, placeIds = FakePlaceData.IDS))
  val repository = FakeTimelineRepository(holder.library.snapshot)
  val preferences = InMemoryLibraryPreferences(filter = filter, level = level, style = style)
  val loader = SwitchingThumbnailLoader(useWindowed = count > WindowedLoaderAbove)
  val grid = MediaGridState(
    view = repository.view(filter).value,
    loader = loader,
    placeholders = BoundedPlaceholderCache(),
    placeNames = FakePlaces(),
    engine = engine,
    refOf = { holder.library.ref(it) },
    level = level,
    style = style,
    refAt = { _, index -> holder.library.refAt(index) },
    layoutDispatcher = layoutDispatcher,
    today = { today },
  )
  val debug = debugSizes?.let { sizes ->
    LibraryDebugHooks(sizes = sizes, current = count) { size ->
      MainScope().launch {
        val next = withContext(Dispatchers.Default) {
          SyntheticLibrary(seed = seed, count = size, placeIds = FakePlaceData.IDS).also { it.snapshot }
        }
        holder.library = next
        loader.useWindowed = size > WindowedLoaderAbove
        grid.stopSelecting()
        repository.publish(next.snapshot)
      }
    }
  }
  val videos = if (count <= WindowedLoaderAbove) {
    val snapshot = holder.library.snapshot
    (0 until snapshot.size).count { snapshot.kindAt(it) == MediaKind.VIDEO }
  } else {
    0
  }
  return LibraryTabState(
    grid = grid,
    viewerItems = grid.viewerItems,
    viewerImages = FakeViewerImageSource(),
    photoCount = if (count <= WindowedLoaderAbove) holder.library.snapshot.size - videos else 0,
    videoCount = videos,
    timeline = repository,
    preferences = preferences,
    actions = SyntheticLocalActions(holder, repository),
    phone = MutableStateFlow(phone),
    commands = SyntheticCommands,
    debug = debug,
  )
}

/** "Oggi" per la Libreria sintetica: il giorno del suo "adesso" fisso, cosi' "Oggi" e "Ieri" compaiono negli screenshot. */
val SyntheticToday: LocalDate = java.time.Instant.ofEpochMilli(SyntheticLibrary.DEFAULT_NOW_MS).atOffset(ZoneOffset.ofHours(1)).toLocalDate()

/** La libreria corrente (cambia quando il debug ne sceglie un'altra dimensione). */
private class SyntheticHolder(@Volatile var library: SyntheticLibrary)

/** Le richieste della Libreria sintetica: nessun permesso da chiedere, nessun intent; eliminare "si puo'" (per vedere l'azione). */
private object SyntheticCommands : LibraryCommands {
  override val canDeleteFromPhone: Boolean get() = true
}

/**
 * Preferiti e nascosti della Libreria sintetica: un preferito cambia davvero il flag nell'indice (e la cella mostra il
 * cuore), come farebbe il catalogo con il suo flusso dei cambiamenti; i nascosti si registrano soltanto.
 */
private class SyntheticLocalActions(
  private val holder: SyntheticHolder,
  private val repository: FakeTimelineRepository,
) : LocalMediaActions {

  val hidden: MutableSet<MediaId> = linkedSetOf()

  override suspend fun setFavorite(ids: Set<MediaId>, on: Boolean) {
    var snapshot = repository.snapshot.value as? InMemoryTimelineSnapshot ?: return
    for (id in ids) {
      val index = snapshot.indexOf(id)
      if (index < 0) continue
      val flags = snapshot.flagsAt(index)
      val next = if (on) flags or CellFlags.FAVORITE else flags and CellFlags.FAVORITE.inv()
      if (next != flags) snapshot = snapshot.withFlags(index, next)
    }
    repository.publish(snapshot)
  }

  override suspend fun setHidden(ids: Set<MediaId>, on: Boolean) {
    if (on) hidden += ids else hidden -= ids
  }
}

/**
 * Un caricatore che passa da quello finto sincrono (pochi elementi, screenshot ripetibili) a quello a finestra (M1) senza
 * cambiare oggetto: la griglia tiene lo stesso `ThumbnailLoader` per tutta la sua vita.
 */
private class SwitchingThumbnailLoader(useWindowed: Boolean) : ThumbnailLoader {
  private val small = FakeThumbnailLoader()
  private val windowed by lazy { WindowedThumbnailLoader() }

  var useWindowed: Boolean = useWindowed
    set(value) {
      if (field && !value) windowed.trim(0)
      if (!field && value) small.trim(0)
      field = value
    }

  private val target: ThumbnailLoader get() = if (useWindowed) windowed else small

  override fun image(key: ThumbKey, ref: MediaRef): State<ImageBitmap?> = target.image(key, ref)

  override fun peek(key: ThumbKey): ImageBitmap? = target.peek(key)

  override fun window(visible: List<ThumbRequest>, ahead: List<ThumbRequest>) = target.window(visible, ahead)

  override fun trim(level: Int) = target.trim(level)

  override val stats: StateFlow<ThumbStats> get() = target.stats
}
