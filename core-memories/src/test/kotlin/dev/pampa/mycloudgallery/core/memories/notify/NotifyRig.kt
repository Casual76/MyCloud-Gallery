package dev.pampa.mycloudgallery.core.memories.notify

import dev.pampa.mycloudgallery.core.library.testing.FakeVisibility
import dev.pampa.mycloudgallery.core.memories.contract.Memories
import dev.pampa.mycloudgallery.core.memories.contract.MemoryNotifyDecisions
import dev.pampa.mycloudgallery.core.memories.contract.MemoryTrips
import dev.pampa.mycloudgallery.core.memories.contract.NotifyLedgerStore
import dev.pampa.mycloudgallery.core.memories.contract.NotifyMark
import dev.pampa.mycloudgallery.core.memories.contract.NotifyPrefs
import dev.pampa.mycloudgallery.core.memories.contract.RefreshReason
import dev.pampa.mycloudgallery.core.memories.contract.RefreshReport
import dev.pampa.mycloudgallery.core.memories.noop.NoMemoryTrips
import dev.pampa.mycloudgallery.core.memories.plan.EngineRig
import dev.pampa.mycloudgallery.core.memories.plan.TestShot
import dev.pampa.mycloudgallery.core.memories.plan.snapshotOf
import dev.pampa.mycloudgallery.core.memories.plan.visibilityOf
import dev.pampa.mycloudgallery.core.memories.plan.visibilityOver
import dev.pampa.mycloudgallery.core.memories.shelf.MemoryVisibility
import dev.pampa.mycloudgallery.core.memories.testing.FakeNotifyAccess
import dev.pampa.mycloudgallery.core.memories.testing.FakeNotifyPublisher
import dev.pampa.mycloudgallery.core.memories.testing.InMemoryNotifyLedgerStore
import dev.pampa.mycloudgallery.core.memories.testing.InMemoryNotifySettings
import dev.pampa.mycloudgallery.core.memories.testing.ProcessDied
import dev.pampa.mycloudgallery.core.memories.testing.RecordingNotifyEvents
import dev.pampa.mycloudgallery.core.memories.testing.RecordingNotifyScheduler
import dev.pampa.mycloudgallery.core.model.MediaId
import java.time.LocalDate
import java.time.LocalTime
import kotlin.time.Duration

/**
 * Un dispositivo finto per le notifiche (Fase 9, P10): il **motore vero** dei ricordi (P6) su una Libreria sintetica, lo scaffale, le scelte
 * dell'utente e i nascosti, e di fronte le porte finte del sistema (permesso, notifica, lavoro unico, Diagnostica). Il tempo e' quello del
 * test: [at] sposta "oggi" e l'ora, nessuna attesa vera.
 */
internal class NotifyRig(
  val shots: List<TestShot>,
  today: LocalDate = LocalDate.of(2026, 11, 3),
  prefs: NotifyPrefs = NotifyPrefs(),
  trips: MemoryTrips = NoMemoryTrips,
  access: NotifyAccessState = NotifyAccessState.GRANTED,
  val ledger: NotifyLedgerStore = InMemoryNotifyLedgerStore(),
  visibilityOverride: ((FakeVisibility) -> MemoryVisibility)? = null,
) {
  val fake: FakeVisibility = visibilityOver(shots)
  val engine = EngineRig(visibility = fake, today = today, trips = trips)
  val counting = CountingMemories(engine.engine)
  val visibility: MemoryVisibility = visibilityOverride?.invoke(fake) ?: visibilityOf(fake, fake)
  val settings = InMemoryNotifySettings(prefs)
  val publisher = FakeNotifyPublisher()
  val access = FakeNotifyAccess(access)
  val scheduler = RecordingNotifyScheduler()
  val events = RecordingNotifyEvents()
  val clock get() = engine.clock

  fun run(policy: MemoryNotifyDecisions = MemoryNotifyPolicy(), ledgerStore: NotifyLedgerStore = ledger) =
    MemoryNotifyRun(policy, settings, ledgerStore, counting, visibility, SeenTodayOpened(engine.personal), access, publisher, clock, scheduler, events)

  val revocation get() = MemoryNotifyRevocation(publisher, counting, visibility, events)

  /** Sposta il giorno e l'ora di questo dispositivo (il fuso resta quello locale: l'ora e' sempre locale). */
  fun at(day: LocalDate, time: LocalTime = LocalTime.of(19, 0)) {
    clock.today = day
    clock.time = time
  }

  /** Il giro della notte: i ricordi di oggi sono gia' sullo scaffale quando parte il lavoro. */
  suspend fun night(): RefreshReport = engine.refresh()

  /** I media di cui un ricordo e' fatto, per nasconderne alcuni. */
  fun hide(ids: Collection<MediaId>) {
    fake.hidden += ids
  }

  /** Nascondi e ripulisci: l'istantanea della Libreria perde i nascosti e lo scaffale toglie quegli elementi (come fa P13 a ogni cambio dei nascosti). */
  suspend fun hideAndPrune(ids: Collection<MediaId>) {
    hide(ids)
    fake.snapshot.value = snapshotOf(shots.filter { it.id !in fake.hidden })
    engine.engine.prune()
  }

  fun close() = engine.close()
}

/** `Memories` che conta i giri (`refresh`) e li lascia passare: per provare che il passo leggero parte solo quando la notte non c'e' stata. */
internal class CountingMemories(private val inner: Memories) : Memories by inner {
  val refreshes: MutableList<RefreshReason> = ArrayList()

  /** Il passo che si guasta (il disco, una porta): il motore lancia, e il lavoro delle notifiche deve andare avanti con cio' che c'e'. */
  var failRefresh: Boolean = false

  override suspend fun refresh(reason: RefreshReason, budget: Duration): RefreshReport {
    refreshes += reason
    if (failRefresh) throw java.io.IOException("giro fallito")
    return inner.refresh(reason, budget)
  }
}

/** Un registro che muore: prima della scrittura (niente scritto) o subito dopo (scritto intero), come il processo fra il registro e la notifica. */
internal class DyingLedger(private val inner: NotifyLedgerStore) : NotifyLedgerStore by inner {
  var dieBefore: Boolean = false
  var dieAfter: Boolean = false

  override suspend fun record(mark: NotifyMark, today: LocalDate) {
    if (dieBefore) {
      dieBefore = false
      throw ProcessDied()
    }
    inner.record(mark, today)
    if (dieAfter) {
      dieAfter = false
      throw ProcessDied()
    }
  }
}
