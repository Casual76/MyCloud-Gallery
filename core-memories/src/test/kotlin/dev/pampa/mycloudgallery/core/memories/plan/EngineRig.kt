package dev.pampa.mycloudgallery.core.memories.plan

import dev.pampa.mycloudgallery.core.library.testing.FakeVisibility
import dev.pampa.mycloudgallery.core.memories.contract.MemoryAlbumSignals
import dev.pampa.mycloudgallery.core.memories.contract.MemoryClock
import dev.pampa.mycloudgallery.core.memories.contract.MemoryPixels
import dev.pampa.mycloudgallery.core.memories.contract.MemoryShelfStore
import dev.pampa.mycloudgallery.core.memories.contract.MemoryTrips
import dev.pampa.mycloudgallery.core.memories.contract.MemoryVectors
import dev.pampa.mycloudgallery.core.memories.contract.RefreshReason
import dev.pampa.mycloudgallery.core.memories.contract.RefreshReport
import dev.pampa.mycloudgallery.core.memories.noop.NoMemoryPixels
import dev.pampa.mycloudgallery.core.memories.noop.NoMemoryTrips
import dev.pampa.mycloudgallery.core.memories.noop.NoMemoryVectors
import dev.pampa.mycloudgallery.core.memories.shelf.MemoryVisibility
import dev.pampa.mycloudgallery.core.memories.testing.FakeMemoryClock
import dev.pampa.mycloudgallery.core.memories.testing.FakeMemoryPeople
import dev.pampa.mycloudgallery.core.memories.testing.FakeMemoryTrips
import dev.pampa.mycloudgallery.core.memories.testing.InMemoryShelfStore
import dev.pampa.mycloudgallery.core.memories.testing.ReferencePicker
import dev.pampa.mycloudgallery.core.personal.testing.InMemoryMemoriesPersonalSettings
import dev.pampa.mycloudgallery.core.personal.testing.MemoriesRegister
import java.time.LocalDate
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel

/**
 * Un dispositivo finto con il motore vero (P6): una Libreria ([visibility], di serie vuota), le scelte su un registro che puo' essere condiviso
 * con un altro dispositivo, lo scaffale ([store], in memoria o su file), l'orologio, le porte finte. Il motore gira su [dispatcher] (di serie
 * quello che non cambia thread, cosi' le prove sono deterministiche) e i flussi derivati sul suo scope.
 */
internal class EngineRig(
  val visibility: FakeVisibility = FakeVisibility(snapshotOf(emptyList()), emptySet()),
  today: LocalDate = LocalDate.of(2026, 11, 3),
  val store: MemoryShelfStore = InMemoryShelfStore(),
  val register: MemoriesRegister = MemoriesRegister(),
  dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
  library: MemoryVisibility = visibilityOf(visibility, visibility),
  trips: MemoryTrips = NoMemoryTrips,
  vectors: MemoryVectors = NoMemoryVectors,
  pixels: MemoryPixels = NoMemoryPixels,
  albums: MemoryAlbumSignals = dev.pampa.mycloudgallery.core.memories.noop.NoMemoryAlbumSignals,
  clockOverride: MemoryClock? = null,
  choice: MemoryChoice? = null,
) {
  val scope = CoroutineScope(Dispatchers.Unconfined + Job())
  val clock = FakeMemoryClock(today)
  val personal = InMemoryMemoriesPersonalSettings(register)
  val people = FakeMemoryPeople(available = true) { ids -> visibility.visible(ids) }
  val engine = MemoryEngine(
    library = library,
    personal = personal,
    store = store,
    clock = clockOverride ?: clock,
    picker = ReferencePicker(),
    scope = scope,
    planner = IndexedPlanner(ReferencePeople),
    trips = trips,
    people = people,
    vectors = vectors,
    pixels = pixels,
    albums = albums,
    dispatcher = dispatcher,
    choice = choice,
  )

  suspend fun refresh(budget: Duration = 60.seconds, reason: RefreshReason = RefreshReason.NIGHTLY): RefreshReport = engine.refresh(reason, budget)

  fun close() = scope.cancel()
}

internal fun fakeTrips(vararg trips: dev.pampa.mycloudgallery.core.memories.contract.TripDigestView) = FakeMemoryTrips(trips.toList())
