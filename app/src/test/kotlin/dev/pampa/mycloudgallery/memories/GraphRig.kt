package dev.pampa.mycloudgallery.memories

import android.app.Application
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.memories.contract.MemoryTrips
import dev.pampa.mycloudgallery.core.memories.contract.NotifyPrefs
import dev.pampa.mycloudgallery.core.memories.contract.TripDigestView
import dev.pampa.mycloudgallery.core.memories.noop.NoMemoryAlbumSignals
import dev.pampa.mycloudgallery.core.memories.noop.NoMemoryCutouts
import dev.pampa.mycloudgallery.core.memories.noop.NoMemoryLookAlikes
import dev.pampa.mycloudgallery.core.memories.noop.NoMemoryPeople
import dev.pampa.mycloudgallery.core.memories.noop.NoMemoryPixels
import dev.pampa.mycloudgallery.core.memories.noop.NoMemoryTrips
import dev.pampa.mycloudgallery.core.memories.noop.NoMemoryVectors
import dev.pampa.mycloudgallery.core.memories.notify.MemoryNotifyPolicy
import dev.pampa.mycloudgallery.core.memories.notify.MemoryNotifyRun
import dev.pampa.mycloudgallery.core.memories.notify.SeenTodayOpened
import dev.pampa.mycloudgallery.core.memories.plan.MemoryEngine
import dev.pampa.mycloudgallery.core.memories.plan.PortsMemoryChoice
import dev.pampa.mycloudgallery.core.memories.settings.less.NoPeopleSwitch
import dev.pampa.mycloudgallery.core.memories.settings.less.PeopleLessJanitor
import dev.pampa.mycloudgallery.core.memories.shelf.MemoryVisibility
import dev.pampa.mycloudgallery.core.memories.testing.FakeMemoryClock
import dev.pampa.mycloudgallery.core.memories.testing.FakeNotifyAccess
import dev.pampa.mycloudgallery.core.memories.testing.FakeNotifyPublisher
import dev.pampa.mycloudgallery.core.memories.testing.InMemoryMusicSettings
import dev.pampa.mycloudgallery.core.memories.testing.InMemoryNotifyLedgerStore
import dev.pampa.mycloudgallery.core.memories.testing.InMemoryNotifySettings
import dev.pampa.mycloudgallery.core.memories.testing.InMemoryShelfStore
import dev.pampa.mycloudgallery.core.memories.testing.RecordingNotifyScheduler
import dev.pampa.mycloudgallery.core.memories.testing.ReferencePicker
import dev.pampa.mycloudgallery.core.memories.testing.SyntheticMemoryLibrary
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.personal.testing.InMemoryMemoriesPersonalSettings
import dev.pampa.mycloudgallery.core.personal.testing.MemoriesRegister
import dev.pampa.mycloudgallery.notify.MemoryNotifier
import dev.pampa.mycloudgallery.work.MemoryNotifySlot
import java.io.File
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Il grafo dei ricordi (`MemoriesGraph`) con le **porte finte** al posto del grafo della galleria: la Libreria sintetica, le scelte in memoria, lo scaffale in memoria, la
 * notifica finta e il lavoro unico finto. Per provare l'ordine delle cose (montaggio, primo disegno, famiglia pronta, stop) senza il NAS e senza Hilt. Il montaggio e' quello
 * vero (`MemoriesGraph`, `MemoryEngine`, `MemoryNotifyRun`, `PeopleLessJanitor`); i tempi dei collettori sono brevi ([quiet]) perche' la prova non aspetti 3 secondi veri.
 */
internal class GraphRig(
  app: Application,
  dir: File,
  val today: LocalDate = LocalDate.of(2026, 11, 3),
  canMount: Boolean = true,
  withMemory: Boolean = true,
  val trips: SwitchableTrips = SwitchableTrips(),
  quiet: Duration = 20.milliseconds,
) {
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  val lib = SyntheticMemoryLibrary().also { if (withMemory) it.photos(today.minusYears(1), 9, place = 3_100_001) }
  val clock = FakeMemoryClock(today)
  val ready = MutableStateFlow(false)
  val firstDraw = CompletableDeferred<Unit>()
  val personal = InMemoryMemoriesPersonalSettings(MemoriesRegister())
  val slot = MemoryNotifySlot()
  val scheduler = RecordingNotifyScheduler()
  val publisher = FakeNotifyPublisher()
  val counters = MemoriesCounters()
  val notifySettings = InMemoryNotifySettings(NotifyPrefs())
  val builds = AtomicInteger(0)

  val visibility: MemoryVisibility = object : MemoryVisibility {
    override val snapshot: MutableStateFlow<TimelineSnapshot?> get() = lib.visibility.snapshot

    override suspend fun visibleOrThrow(ids: Collection<MediaId>): Set<MediaId> = lib.visibility.visible(ids)

    override suspend fun hiddenOrThrow(ids: Collection<MediaId>): Set<MediaId> = lib.visibility.hiddenAmong(ids)
  }

  private val graphHolder = arrayOfNulls<MemoriesGraph>(1)

  val graph: MemoriesGraph = MemoriesGraph(
    parent = scope,
    ready = ready,
    canMount = canMount,
    build = { mountScope -> build(mountScope) },
    visibility = visibility,
    notifier = lazy { MemoryNotifier(app, graphHolder[0]!!.memories, visibility, dir, today = { clock.today() }) },
    notifySettings = lazy { notifySettings },
    musicSettings = lazy { InMemoryMusicSettings() },
    slot = slot,
    afterFirstDraw = { firstDraw.await() },
    counters = counters,
    peopleChanged = emptyFlow(),
    today = { clock.today() },
    snapshotQuiet = quiet,
  ).also { graphHolder[0] = it }

  private suspend fun build(mountScope: CoroutineScope): MemoriesLive {
    builds.incrementAndGet()
    val choice = PortsMemoryChoice(ReferencePicker(), NoMemoryVectors, NoMemoryLookAlikes, NoMemoryPixels, NoMemoryAlbumSignals, NoMemoryPeople)
    val engine = MemoryEngine(
      library = visibility,
      personal = personal,
      store = InMemoryShelfStore(),
      clock = clock,
      scope = mountScope,
      trips = trips,
      cutouts = NoMemoryCutouts,
      choice = choice,
    )
    val run = MemoryNotifyRun(
      MemoryNotifyPolicy(), notifySettings, InMemoryNotifyLedgerStore(), engine, visibility, SeenTodayOpened(personal), FakeNotifyAccess(), publisher, clock, scheduler, counters,
    )
    val janitor = PeopleLessJanitor(personal, NoMemoryPeople, NoPeopleSwitch, engine)
    return MemoriesLive(mountScope, engine, personal, NoMemoryPeople, janitor, run, trips, NoMemoryCutouts, choice)
  }

  fun close() = scope.cancel()
}

/** I viaggi che si possono far guastare dal test (un errore del motore: la lettura di una porta). */
internal class SwitchableTrips : MemoryTrips {
  @Volatile var fail: Boolean = false

  override suspend fun past(until: LocalDate, budget: Duration): List<TripDigestView> {
    if (fail) throw java.io.IOException("porta dei viaggi guasta (simulata)")
    return NoMemoryTrips.past(until, budget)
  }
}
