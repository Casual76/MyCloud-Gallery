package dev.pampa.mycloudgallery.hidden

import dev.pampa.mycloudgallery.core.database.entity.MediaPersonalEntity
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.library.visibility.CatalogHiddenMask
import dev.pampa.mycloudgallery.core.library.visibility.CatalogVisibleMedia
import dev.pampa.mycloudgallery.core.memories.contract.DayRange
import dev.pampa.mycloudgallery.core.memories.contract.LockScreenMode
import dev.pampa.mycloudgallery.core.memories.contract.Memories
import dev.pampa.mycloudgallery.core.memories.contract.Memory
import dev.pampa.mycloudgallery.core.memories.contract.MemoryIds
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKey
import dev.pampa.mycloudgallery.core.memories.contract.MemoryTitle
import dev.pampa.mycloudgallery.core.memories.contract.NotifyFrequency
import dev.pampa.mycloudgallery.core.memories.contract.NotifyLedger
import dev.pampa.mycloudgallery.core.memories.contract.NotifyPrefs
import dev.pampa.mycloudgallery.core.memories.contract.RefreshReason
import dev.pampa.mycloudgallery.core.memories.contract.RefreshReport
import dev.pampa.mycloudgallery.core.memories.contract.ShelfFile
import dev.pampa.mycloudgallery.core.memories.notify.MemoriesOpened
import dev.pampa.mycloudgallery.core.memories.notify.MemoryNotifyPolicy
import dev.pampa.mycloudgallery.core.memories.notify.MemoryNotifyRevocation
import dev.pampa.mycloudgallery.core.memories.notify.MemoryNotifyRun
import dev.pampa.mycloudgallery.core.memories.notify.NotifyAccessState
import dev.pampa.mycloudgallery.core.memories.plan.MemoryEngine
import dev.pampa.mycloudgallery.core.memories.shelf.MemoryVisibility
import dev.pampa.mycloudgallery.core.memories.testing.FakeMemoryClock
import dev.pampa.mycloudgallery.core.memories.testing.FakeNotifyAccess
import dev.pampa.mycloudgallery.core.memories.testing.FakeNotifyPublisher
import dev.pampa.mycloudgallery.core.memories.testing.InMemoryNotifyLedgerStore
import dev.pampa.mycloudgallery.core.memories.testing.InMemoryNotifySettings
import dev.pampa.mycloudgallery.core.memories.testing.InMemoryShelfStore
import dev.pampa.mycloudgallery.core.memories.testing.ReferencePicker
import dev.pampa.mycloudgallery.core.memories.testing.RecordingNotifyEvents
import dev.pampa.mycloudgallery.core.memories.testing.RecordingNotifyScheduler
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.personal.testing.InMemoryMemoriesPersonalSettings
import dev.pampa.mycloudgallery.core.personal.testing.MemoriesRegister
import java.time.LocalDate
import java.time.LocalTime
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow

// Fase 9 (P10, D19, D31, F116): la SONDA delle notifiche dei ricordi sul codice VERO. Sul catalogo vero di un mondo casuale (Room in memoria, l'istantanea vera, i nascosti veri) si mette
// sullo scaffale del motore vero (P6) una serie di ricordi di prova, **fatti apposta per far uscire un nascosto**: la copertina nascosta "un attimo fa" (che l'istantanea della Libreria
// ha ancora), elementi nascosti dell'ultimo momento che tengono il ricordo sopra o sotto gli 8 elementi, e un ricordo pulito. Poi gira `MemoryNotifyRun` (il giorno di una notifica, di serie "Titolo e
// foto" nei mondi con un'immagine da far uscire) un giorno per ricordo, e si guarda **cosa si vedrebbe nella tendina**: la miniatura (il solo media che una notifica mostra, mai in modalita'
// generica). Poi si nasconde, dopo la notifica, la copertina di quella uscita e si chiama `MemoryNotifyRevocation.revalidate()`: la notifica deve sparire.
//
// La prova al contrario (`HiddenNotifyMutationTest`) toglie il filtro, cioe' la visibilita' che il giorno di una notifica chiede subito prima di pubblicare, e la sonda deve diventare rossa.
//
// Il registro dei Nascosti non nomina i contratti `VisibleMedia` e `HiddenMask` fuori da questo file di prova: `core-memories` li vede solo attraverso `MemoryVisibility` (l'adattatore di P13).

/** Quante volte la sonda ha davvero provato qualcosa (un controllo che non pubblica mai non controlla niente): lo legge `HiddenNotifyMutationTest`. */
internal object NotifyProbeStats {
  var worlds = 0
  var published = 0
  var withImage = 0
  var coverHiddenCandidates = 0
  var revoked = 0

  fun reset() {
    worlds = 0
    published = 0
    withImage = 0
    coverHiddenCandidates = 0
    revoked = 0
  }
}

internal object RealNotifyProbes {

  /** `MemoryVisibility` (l'adattatore che fara' P13) sopra i due contratti dei nascosti del catalogo; [leaky] toglie il filtro: niente e' nascosto, tutto e' visibile. */
  private fun visibilityOf(visible: VisibleMedia, mask: HiddenMask, leaky: Boolean): MemoryVisibility = object : MemoryVisibility {
    override val snapshot = visible.snapshot

    override suspend fun visibleOrThrow(ids: Collection<MediaId>): Set<MediaId> = if (leaky) ids.toSet() else visible.visibleOrThrow(ids)

    override suspend fun hiddenOrThrow(ids: Collection<MediaId>): Set<MediaId> = if (leaky) emptySet() else mask.hiddenAmongOrThrow(ids)
  }

  /** Il giro non fa niente: i ricordi sono quelli messi sullo scaffale (un giro vero ripulirebbe da se' i nascosti e la prova non toccherebbe il filtro della notifica). */
  private class FrozenMemories(private val inner: Memories) : Memories by inner {
    override suspend fun refresh(reason: RefreshReason, budget: kotlin.time.Duration): RefreshReport = RefreshReport.NOTHING
  }

  private fun memory(seed: Int, day: Int, members: List<MediaId>, cover: MediaId): Memory {
    val key = MemoryKey.OnThisDay(LocalDate.of(2020, 1, 1).plusDays(seed.toLong()))
    return Memory(MemoryIds.of(key), key, MemoryTitle(1, DayRange.of(LocalDate.of(2020, 1, 1)), emptyList(), null, null, null), members, cover, day)
  }

  fun notifications(leaky: Boolean = false) = SurfaceProbe { w ->
    val problems = ArrayList<String>()
    val shown = LinkedHashSet<MediaId>()
    NotifyProbeStats.worlds++
    val random = Random(w.spec.seed * 31 + 7)
    // Cio' che l'istantanea ha e che e' ancora mostrabile (puliti); cio' che e' nascosto e l'istantanea ha ancora (un attimo fa: la trappola); gli altri nascosti.
    val clean = w.liveLibraryCells.toList().sortedBy { it.value }.shuffled(random)
    val staleHidden = (w.libraryCells - w.liveLibraryCells).toList().sortedBy { it.value }.shuffled(random)
    val hiddenAtAll = w.liveMustNeverShow.toList().sortedBy { it.value }.shuffled(random)
    if (clean.size < 14) return@SurfaceProbe Probed(shown = shown, within = w.liveShowable)

    // Cinque ricordi, uno per giorno: [pulito], [copertina nascosta], [8 visibili piu' nascosti], [7 visibili piu' nascosti], [pulito].
    val extra = (staleHidden + hiddenAtAll).distinct()
    val specs = listOf(
      Triple(clean.take(12), clean[0], "pulito"),
      Triple(clean.take(12), extra.firstOrNull() ?: clean[1], "copertina nascosta"),
      Triple(clean.drop(2).take(8) + extra.take(4), clean[3], "otto visibili"),
      Triple(clean.drop(4).take(7) + extra.take(5), clean[4], "sette visibili"),
      Triple(clean.drop(6).take(12), clean[7], "pulito"),
    )
    val start = LocalDate.of(2027, 3, 2)
    val memories = specs.mapIndexed { n, (members, cover, _) ->
      val all = (members + cover).distinct()
      memory(n, dayOf(start.plusDays(n.toLong())), all, cover)
    }
    val clock = FakeMemoryClock(start, LocalTime.of(19, 0))
    val visible = CatalogVisibleMedia(w.db, MutableStateFlow(w.snapshot))
    val visibility = visibilityOf(visible, CatalogHiddenMask(w.db), leaky)
    val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    try {
      val engine = MemoryEngine(
        library = visibility, personal = InMemoryMemoriesPersonalSettings(MemoriesRegister()), store = InMemoryShelfStore(ShelfFile(memories, lastRunDay = dayOf(start))),
        clock = clock, scope = scope, picker = ReferencePicker(), dispatcher = Dispatchers.Unconfined,
      )
      engine.restore()
      val mode = if (w.spec.seed % 4L == 0L) LockScreenMode.GENERIC else LockScreenMode.TITLE_AND_PHOTO
      val publisher = FakeNotifyPublisher()
      val run = MemoryNotifyRun(
        policy = MemoryNotifyPolicy(), settings = InMemoryNotifySettings(NotifyPrefs(frequency = NotifyFrequency.DAILY, lockScreen = mode)), ledger = InMemoryNotifyLedgerStore(),
        memories = FrozenMemories(engine), visibility = visibility, opened = MemoriesOpened { false }, access = FakeNotifyAccess(NotifyAccessState.GRANTED), publisher = publisher,
        clock = clock, scheduler = RecordingNotifyScheduler(), events = RecordingNotifyEvents(),
      )
      for (n in memories.indices) {
        clock.today = start.plusDays(n.toLong())
        run.run()
      }
      NotifyProbeStats.published += publisher.published.size
      // Quello che si vedrebbe nella tendina: la miniatura (solo in "Titolo e foto"). La modalita' generica non ha niente da far uscire.
      for (notice in publisher.published) {
        notice.content.image?.let {
          shown += it
          NotifyProbeStats.withImage++
        }
        if (mode == LockScreenMode.GENERIC && notice.content.image != null) problems += "la modalita' generica porta una foto"
      }
      if (extra.isNotEmpty() && specs[1].second in staleHidden) NotifyProbeStats.coverHiddenCandidates++
      // Un ricordo con la copertina nascosta (o sotto gli 8 elementi visibili) non e' mai la notifica, in "Titolo e foto"; sopra gli 8 si'.
      val ids = publisher.published.map { it.memory }.toSet()
      // (Una copertina nascosta PRIMA dell'istantanea esce dall'istantanea e la copertina passa al primo elemento rimasto: D9. Qui conta quella che l'istantanea ha ancora.)
      if (mode == LockScreenMode.TITLE_AND_PHOTO && memories[1].id in ids && memories[1].cover in staleHidden) problems += "un ricordo con la copertina nascosta e' una notifica"
      if (memories[3].id in ids && memories[3].members.count { it !in w.liveMustNeverShow } < 8) problems += "un ricordo sotto gli 8 elementi visibili e' una notifica"

      // Dopo la notifica, la copertina (la miniatura) diventa nascosta "un attimo fa": la revoca la toglie.
      val last = publisher.current
      val cover = last?.content?.image
      if (last != null && cover != null) {
        w.db.personalDao().upsert(MediaPersonalEntity(cover.value, favorite = false, hidden = true, updatedAt = 1_780_000_200_000L))
        val revoked = MemoryNotifyRevocation(publisher, engine, visibility, RecordingNotifyEvents()).revalidate()
        if (!revoked || publisher.current != null) problems += "la copertina nascosta dopo la notifica non l'ha tolta"
        if (revoked) NotifyProbeStats.revoked++
      }
      if (publisher.published.map { it.day }.toSet().size != publisher.published.size) problems += "due notifiche nello stesso giorno"
    } finally {
      scope.cancel()
    }
    // Tutto cio' che esce (le miniature) sta fra i media che si possono mostrare adesso; mai un nascosto, nemmeno dell'ultimo momento.
    Probed(shown = shown, within = w.liveShowable, problems = problems)
  }

  private fun dayOf(date: LocalDate): Int = NotifyLedger.dayOf(date)
}
