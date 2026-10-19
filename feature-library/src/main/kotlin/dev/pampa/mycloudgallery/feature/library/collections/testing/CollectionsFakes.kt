package dev.pampa.mycloudgallery.feature.library.collections.testing

import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.imaging.testing.FakePlaceholderCache
import dev.pampa.mycloudgallery.core.imaging.testing.FakeThumbnailLoader
import dev.pampa.mycloudgallery.core.imaging.testing.SyntheticPixels
import dev.pampa.mycloudgallery.core.imaging.testing.SyntheticThumbnailSource
import dev.pampa.mycloudgallery.core.library.contract.GridLevel
import dev.pampa.mycloudgallery.core.library.contract.GridStyle
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.LocalMediaActions
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.contract.ViewerItems
import dev.pampa.mycloudgallery.core.library.testing.FakeTimelineRepository
import dev.pampa.mycloudgallery.core.library.testing.InMemoryLibraryPreferences
import dev.pampa.mycloudgallery.core.library.testing.InMemoryTimelineSnapshot
import dev.pampa.mycloudgallery.core.library.testing.InMemoryTimelineView
import dev.pampa.mycloudgallery.core.library.testing.SimpleGridLayoutEngine
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.mediastore.contract.RecentlyDeletedSource
import dev.pampa.mycloudgallery.core.mediastore.contract.TrashedItem
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.places.testing.FakePlaceData
import dev.pampa.mycloudgallery.core.places.testing.FakePlaces
import dev.pampa.mycloudgallery.feature.library.collections.CollectionItemsSource
import dev.pampa.mycloudgallery.feature.library.collections.GridEnvironment
import dev.pampa.mycloudgallery.feature.library.hidden.HiddenLibrary
import dev.pampa.mycloudgallery.feature.library.trash.TrashHiddenMask
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// I fake delle Raccolte, dei Nascosti e degli Eliminati di recente (D23): in src/main, cosi' i test di questo modulo, gli
// screenshot e i test d'integrazione di `:app` (P11) li riusano senza cicli. Mai nella release: tools/check.ps1 fallisce
// se una classe di un package `testing` compare nel dex dell'APK di release. Nessun codice di produzione li referenzia.

/**
 * Un mondo finto con dei **nascosti veri**: una [SyntheticLibrary] in cui alcuni media sono nascosti e alcuni sono
 * preferiti. Si comporta come il catalogo (E-H1):
 *
 *  - l'indice che le raccolte e la Libreria vedono ([repository], [source]) **non contiene mai i nascosti**, nemmeno se
 *    sono anche preferiti, video, screenshot o WhatsApp;
 *  - i nascosti si leggono solo da [hiddenLibrary], che conta le letture ([HiddenLibraryProbe.reads]);
 *  - [actions] sposta un media fra i due mondi e ripubblica l'indice, come farebbe `setPersonalFlags`.
 *
 * Serve a provare che nessuna superficie ha una seconda strada verso i nascosti.
 */
class FakeCollectionsWorld(
  val library: SyntheticLibrary = SyntheticLibrary(seed = 7L, count = 1_200, placeIds = FakePlaceData.IDS),
  /** Le posizioni (nell'indice completo) dei media nascosti all'inizio. Di default, un po' di ogni genere. */
  initiallyHidden: Collection<Int> = defaultHiddenPositions(library),
) {

  private val full: InMemoryTimelineSnapshot = library.snapshot
  private val hidden = LinkedHashSet<MediaId>().apply { initiallyHidden.forEach { add(full.idAt(it)) } }
  private val favoriteOverride = HashMap<MediaId, Boolean>()
  private var seq = full.seq

  /** L'indice **visibile**: quello che il repository vero costruirebbe (E-H1). */
  val repository = FakeTimelineRepository(visibleSnapshot())

  /** I nascosti adesso (identita'): serve ai test per cercarli dove non devono essere. */
  val hiddenIds: Set<MediaId> get() = hidden.toSet()

  /** Tutto cio' che e' visibile adesso. */
  val visibleIds: Set<MediaId> get() = (0 until full.size).map { full.idAt(it) }.filter { it !in hidden }.toSet()

  /** Le raccolte-filtro e il viewer: la vista dell'indice visibile con un filtro, e i riferimenti ai byte. */
  val source: CollectionItemsSource = CollectionItemsSource { filter -> LiveViewerItems(repository.view(filter), library::ref) }

  /** Preferiti e nascosti che spostano i media fra i due mondi e ripubblicano l'indice. */
  val actions: LocalMediaActions = object : LocalMediaActions {
    override suspend fun setFavorite(ids: Set<MediaId>, on: Boolean) {
      ids.forEach { favoriteOverride[it] = on }
      publish()
    }

    override suspend fun setHidden(ids: Set<MediaId>, on: Boolean) {
      if (on) hidden += ids else hidden -= ids
      publish()
    }
  }

  /** Il solo punto che legge i nascosti, con il suo contatore. */
  val hiddenLibrary: HiddenLibraryProbe = HiddenLibraryProbe()

  inner class HiddenLibraryProbe : HiddenLibrary {

    /** Quante volte sono stati letti. Prima dello sblocco deve restare a zero. */
    var reads: Int = 0
      private set

    /**
     * **Una vista viva** (GA-1), come il catalogo vero: finche' la pagina dei Nascosti o il viewer aperto da li' la guardano, "Mostra
     * nella Libreria", "Preferito" ed "Elimina" fatti dal viewer la cambiano. Prima era una fotografia: i test passavano anche se a
     * schermo non cambiava niente. Il vero smette di rileggere quando nessuno guarda; qui la vista si aggiorna per tutte le letture.
     */
    override suspend fun read(): ViewerItems {
      reads++
      val live = MutableStateFlow<TimelineView>(hiddenView())
      synchronized(liveHidden) { liveHidden += live }
      return LiveViewerItems(live) { library.ref(it) }
    }
  }

  private val liveHidden = ArrayList<MutableStateFlow<TimelineView>>()

  private fun hiddenView(): TimelineView {
    val indices = (0 until full.size).filter { full.idAt(it) in hidden }
    return InMemoryTimelineView(full.subsetOf(indices, seq) { effectiveFlags(it) }, LibraryFilter.ALL)
  }

  /** Il riconoscimento dei nascosti nel cestino: i riferimenti dati sono considerati nascosti. */
  fun trashMask(vararg hiddenRefs: MediaRef.Phone): TrashHiddenMask = TrashHiddenMask { refs -> refs.filterTo(HashSet()) { it in hiddenRefs } }

  /**
   * Il sistema ha ripristinato dei file dal cestino e la sincronizzazione ha riletto il catalogo: l'indice visibile si
   * ricostruisce. Un nascosto resta nascosto, perche' `hidden` e' un dato personale legato all'identita' del media e
   * non al file: il ripristino non lo tocca.
   */
  fun systemRestored() {
    publish()
  }

  private fun effectiveFlags(i: Int): Int {
    val base = full.flagsAt(i)
    val override = favoriteOverride[full.idAt(i)] ?: return base
    return if (override) base or CellFlags.FAVORITE else base and CellFlags.FAVORITE.inv()
  }

  private fun visibleSnapshot(): InMemoryTimelineSnapshot {
    val indices = (0 until full.size).filter { full.idAt(it) !in hidden }
    return full.subsetOf(indices, seq) { effectiveFlags(it) }
  }

  private fun publish() {
    seq++
    repository.publish(visibleSnapshot())
    val live = synchronized(liveHidden) { liveHidden.toList() }
    live.forEach { it.value = hiddenView() }
  }

  private class LiveViewerItems(
    override val view: StateFlow<TimelineView>,
    private val refOf: (MediaId) -> MediaRef,
  ) : ViewerItems {
    override fun ref(id: MediaId): MediaRef = refOf(id)
  }

  companion object {

    /**
     * Nascosti di ogni genere: preferiti, video, screenshot, WhatsApp, normali. Sono i casi in cui un nascosto
     * potrebbe finire in una raccolta se qualcosa leggesse una strada diversa dall'indice.
     */
    fun defaultHiddenPositions(library: SyntheticLibrary): List<Int> {
      val s = library.snapshot
      fun first(vararg accepts: (Int) -> Boolean, skip: Int = 0): List<Int> =
        (0 until s.size).filter { i -> accepts.all { it(i) } }.drop(skip).take(3)
      return (
        first({ s.hasFlag(it, CellFlags.FAVORITE) }) +
          first({ s.kindAt(it).isVideo }) +
          first({ s.sourceAt(it) == MediaSource.SCREENSHOT }) +
          first({ s.sourceAt(it) == MediaSource.WHATSAPP }) +
          first({ s.sourceAt(it) == MediaSource.CAMERA }, skip = 10)
        ).distinct()
    }
  }
}

/** Un sottoinsieme dell'indice, nello stesso ordine, con i flag scelti da [flagsOf] (preferiti cambiati dai test). */
fun TimelineSnapshot.subsetOf(indices: List<Int>, seq: Long, flagsOf: (Int) -> Int = { flagsAt(it) }): InMemoryTimelineSnapshot {
  val n = indices.size
  val msb = LongArray(n)
  val lsb = LongArray(n)
  for (k in 0 until n) {
    val uuid = UUID.fromString(idAt(indices[k]).value)
    msb[k] = uuid.mostSignificantBits
    lsb[k] = uuid.leastSignificantBits
  }
  val source = this
  return InMemoryTimelineSnapshot(
    seq = seq,
    idMsb = msb,
    idLsb = lsb,
    sortTime = LongArray(n) { source.sortTimeAt(indices[it]) },
    localDay = IntArray(n) { source.localDayAt(indices[it]) },
    kinds = ByteArray(n) { source.kindAt(indices[it]).ordinal.toByte() },
    sources = ByteArray(n) { source.sourceAt(indices[it]).ordinal.toByte() },
    flags = IntArray(n) { flagsOf(indices[it]) },
    aspectMilli = IntArray(n) { source.aspectMilliAt(indices[it]) },
    durationMs = LongArray(n) { source.durationMsAt(indices[it]) },
    place = IntArray(n) { source.placeAt(indices[it]) },
    groupSize = IntArray(n) { source.groupSizeAt(indices[it]) },
    thumbHashOf = { k -> source.thumbHashAt(indices[k]) },
  )
}

/**
 * Un caricatore di miniature finto che disegna anche i riferimenti del **telefono** (`MediaRef.Phone`), che quello di base
 * lascia vuoti: un gradiente dall'`_ID`, cosi' le celle degli Eliminati di recente negli screenshot non sono tutte grigie.
 */
fun fakeThumbnailLoader(): FakeThumbnailLoader {
  val synthetic = SyntheticThumbnailSource()
  return FakeThumbnailLoader { ref, px ->
    when (ref) {
      is MediaRef.Phone -> {
        val side = minOf(px, SyntheticPixels.MAX_SIDE)
        SyntheticPixels.bitmap(ref.mediaStoreId, side, side)
      }
      else -> synthetic.loadNow(ref, px)
    }
  }
}

/** L'ambiente delle griglie sui fake: miniature a gradiente, luoghi di prova, motore di layout di riferimento. */
fun fakeGridEnvironment(
  level: GridLevel = GridLevel.DAYS,
  style: GridStyle = GridStyle.SQUARE,
  loader: ThumbnailLoader = fakeThumbnailLoader(),
): GridEnvironment = GridEnvironment(
  loader = loader,
  placeholders = FakePlaceholderCache(),
  placeNames = FakePlaces(),
  engine = SimpleGridLayoutEngine(),
  preferences = InMemoryLibraryPreferences(level = level, style = style),
)

/**
 * Degli elementi del cestino di sistema di prova: i piu' recenti per primi, ognuno eliminato un giorno prima del
 * precedente e con 30 giorni di vita. [videoEvery] = ogni quanti uno e' un video.
 */
fun fakeTrashedItems(nowMs: Long, count: Int, firstId: Long = 100L, videoEvery: Int = 4): List<TrashedItem> =
  (0 until count).map { i ->
    val trashedAt = nowMs - i * DAY_MS - HOUR_MS
    val video = videoEvery > 0 && i % videoEvery == videoEvery - 1
    TrashedItem(
      ref = MediaRef.Phone(volume = "external_primary", mediaStoreId = firstId + i, mime = if (video) "video/mp4" else "image/jpeg"),
      isVideo = video,
      trashedAtMs = trashedAt,
      expiresAtMs = trashedAt + 30 * DAY_MS,
    )
  }

private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 24 * HOUR_MS

/** Un cestino di sistema che conta le letture: chiuso dietro la credenziale non deve essere mai letto. */
class CountingRecentlyDeletedSource(private val inner: RecentlyDeletedSource) : RecentlyDeletedSource {
  var reads: Int = 0
    private set

  override suspend fun list(): List<TrashedItem> {
    reads++
    return inner.list()
  }
}

/** Il riconoscimento dei nascosti del cestino, con il conto delle domande: chiuso non si chiede niente. */
class CountingTrashMask(private val inner: TrashHiddenMask) : TrashHiddenMask {
  var asked: Int = 0
    private set

  override suspend fun hiddenAmong(refs: List<MediaRef.Phone>): Set<MediaRef.Phone> {
    asked++
    return inner.hiddenAmong(refs)
  }
}
