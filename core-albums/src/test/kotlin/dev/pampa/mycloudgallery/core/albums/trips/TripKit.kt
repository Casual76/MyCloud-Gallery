package dev.pampa.mycloudgallery.core.albums.trips

import dev.pampa.mycloudgallery.core.albums.contract.AlbumCandidate
import dev.pampa.mycloudgallery.core.albums.contract.AlbumCandidates
import dev.pampa.mycloudgallery.core.albums.contract.AlbumClocks
import dev.pampa.mycloudgallery.core.albums.contract.Albums
import dev.pampa.mycloudgallery.core.albums.contract.CandidateCursor
import dev.pampa.mycloudgallery.core.albums.contract.CandidateFilter
import dev.pampa.mycloudgallery.core.albums.contract.CandidatePage
import dev.pampa.mycloudgallery.core.albums.contract.CreateOutcome
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.contract.TripId
import dev.pampa.mycloudgallery.core.albums.noop.NoAlbums
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumClocks
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.places.testing.FakePlaces
import dev.pampa.mycloudgallery.core.search.contract.Today
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Il banco dei test di viaggi ed eventi (P7): una Libreria a vettori (veloce anche con 100.000 elementi, a differenza di `FakeAlbumLibrary`
// che riordina a ogni pagina), una memoria in memoria per la casa e le proposte, le proposte scartate in memoria e un `Albums` che registra.

/**
 * I candidati in un array ordinato per (giorno, istante, id): la pagina si trova con una ricerca binaria, come l'indice di P1. [hidden] sono i
 * nascosti (la query dei candidati non li da' mai); [contagion] i media che la query da' ancora ma che la Libreria non mostra (un membro di un
 * gruppo in cui un altro e' nascosto: le query di P1 non hanno il contagio, `VisibleMedia` si').
 */
internal class StoryLibrary(shots: List<AlbumCandidate>) : AlbumCandidates, VisibleMedia, HiddenMask {
  private val sorted: List<AlbumCandidate> = shots.sortedWith(compareBy<AlbumCandidate>({ it.day }, { it.takenAtUtcMs }, { it.media.value }))
  private val epoch = IntArray(sorted.size) { sorted[it].day.toEpochDay().toInt() }
  private val known: Set<MediaId> = sorted.mapTo(HashSet()) { it.media }

  val hidden: MutableSet<MediaId> = HashSet()
  val contagion: MutableSet<MediaId> = HashSet()

  /** Quante volte si e' chiesto il filtro finale (un'implementazione che non lo usa si vede). */
  var maskCalls: Int = 0
    private set

  override val snapshot: MutableStateFlow<TimelineSnapshot?> = MutableStateFlow(null)

  val all: List<AlbumCandidate> get() = sorted

  private fun lowerBound(day: Int): Int {
    var lo = 0
    var hi = epoch.size
    while (lo < hi) {
      val mid = (lo + hi) ushr 1
      if (epoch[mid] < day) lo = mid + 1 else hi = mid
    }
    return lo
  }

  override suspend fun page(filter: CandidateFilter, after: CandidateCursor?, limit: Int): CandidatePage {
    val end = filter.days?.let { lowerBound(it.to.toEpochDay().toInt() + 1) } ?: sorted.size
    var i = after?.value?.toInt() ?: filter.days?.let { lowerBound(it.from.toEpochDay().toInt()) } ?: 0
    val out = ArrayList<AlbumCandidate>()
    while (i < end && out.size < limit) {
      val c = sorted[i++]
      if (c.media in hidden || c.source !in filter.sources) continue
      if (filter.withGpsOnly && c.gps == null) continue
      if (filter.media != null && c.media !in filter.media) continue
      out += c
    }
    return CandidatePage(out, if (i < end) CandidateCursor(i.toString()) else null)
  }

  override suspend fun visibleTotal(): Int = sorted.count { it.media !in hidden }

  override suspend fun visible(ids: Collection<MediaId>): Set<MediaId> = ids.filterTo(HashSet()) { it in known && it !in hidden && it !in contagion }

  override suspend fun hiddenAmong(ids: Collection<MediaId>): Set<MediaId> {
    maskCalls++
    return ids.filterTo(HashSet()) { it !in known || it in hidden || it in contagion }
  }
}

/** La memoria locale di prova: la casa e le proposte in memoria (la Room vera la prova `TripStoreRoomTest`). */
internal class MemoryTripStore : TripStore {
  var home: Anchors? = null
  var saved: List<StoredProposal> = emptyList()
  var replacements: Int = 0

  override suspend fun anchors(): Anchors? = home

  override suspend fun saveAnchors(anchors: Anchors?, estimatedAtMs: Long) {
    home = anchors
  }

  override suspend fun proposals(): List<StoredProposal> = saved

  override suspend fun replaceProposals(rows: List<StoredProposal>) {
    saved = rows
    replacements++
  }

  override suspend fun markAccepted(id: TripId, album: AlbumId) {
    saved = saved.map { if (it.id == id) it.copy(album = album) else it }
  }
}

/** Le proposte scartate in memoria (un solo "account"): due `CatalogTrips` che le condividono sono due dispositivi dello stesso account. */
internal class MemoryDismissals : DismissedTrips {
  private val state = MutableStateFlow<Set<TripId>>(emptySet())

  override val dismissed: StateFlow<Set<TripId>> get() = state

  override suspend fun dismiss(id: TripId) {
    state.value = state.value + id
  }
}

/** Un `Albums` che registra cosa gli si chiede di creare (e non fa altro). */
internal class RecordingAlbums(private val inner: Albums = NoAlbums) : Albums by inner {
  class Created(val title: String, val kind: AlbumKind, val rules: SmartRules?, val id: AlbumId)

  val created = ArrayList<Created>()

  override suspend fun create(title: String, kind: AlbumKind, rules: SmartRules?): CreateOutcome {
    val id = AlbumId.random()
    created += Created(title, kind, rules, id)
    return CreateOutcome.Created(id)
  }
}

/** Tutto cio' che serve per far girare un `CatalogTrips` su una [StoryLibrary]. */
internal class TripsHarness(
  val library: StoryLibrary,
  val today: LocalDate,
  val store: MemoryTripStore = MemoryTripStore(),
  val dismissals: DismissedTrips = MemoryDismissals(),
  val albums: RecordingAlbums = RecordingAlbums(),
  val clocks: AlbumClocks = FakeAlbumClocks(),
) : AutoCloseable {
  val places = FakePlaces()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

  val trips: CatalogTrips = newTrips()

  /** Un altro dispositivo dello stesso account: stessa Libreria e stessi scarti, memoria locale sua. */
  fun anotherDevice(): TripsHarness = TripsHarness(library, today, MemoryTripStore(), dismissals, albums, clocks)

  private fun newTrips() = CatalogTrips(
    library, TripVisibility(library, library), store, dismissals, albums, TripTitles(places), { today }, clocks, scope, refreshDelayMs = 0,
  )

  override fun close() {
    scope.cancel()
  }
}

/** Un orologio monotono che a ogni lettura va avanti di [stepMs]: il tempo che passa mentre si leggono le pagine. */
internal class TickingClocks(private val stepMs: Long, private var now: Long = 1_000_000L) : AlbumClocks {
  override fun monotonicMs(): Long {
    now += stepMs
    return now
  }

  override fun nasNowMs(): Long? = 1_760_000_000_000L
}
