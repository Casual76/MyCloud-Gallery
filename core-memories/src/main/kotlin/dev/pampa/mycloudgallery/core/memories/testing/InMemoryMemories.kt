package dev.pampa.mycloudgallery.core.memories.testing

import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.library.index.IndexOrderedTimelineView
import dev.pampa.mycloudgallery.core.memories.contract.CellTable
import dev.pampa.mycloudgallery.core.memories.contract.DropReason
import dev.pampa.mycloudgallery.core.memories.contract.LessChoice
import dev.pampa.mycloudgallery.core.memories.contract.LessLevel
import dev.pampa.mycloudgallery.core.memories.contract.LessTarget
import dev.pampa.mycloudgallery.core.memories.contract.Memories
import dev.pampa.mycloudgallery.core.memories.contract.MemoriesStatus
import dev.pampa.mycloudgallery.core.memories.contract.Memory
import dev.pampa.mycloudgallery.core.memories.contract.MemoryAlbumSignals
import dev.pampa.mycloudgallery.core.memories.contract.MemoryCandidate
import dev.pampa.mycloudgallery.core.memories.contract.MemoryCard
import dev.pampa.mycloudgallery.core.memories.contract.MemoryClock
import dev.pampa.mycloudgallery.core.memories.contract.MemoryCutouts
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.MemoryLookAlikes
import dev.pampa.mycloudgallery.core.memories.contract.MemoryPeople
import dev.pampa.mycloudgallery.core.memories.contract.MemoryPixels
import dev.pampa.mycloudgallery.core.memories.contract.MemoryPlanner
import dev.pampa.mycloudgallery.core.memories.contract.MemoryRules
import dev.pampa.mycloudgallery.core.memories.contract.MemoryShelfStore
import dev.pampa.mycloudgallery.core.memories.contract.MemoryTitle
import dev.pampa.mycloudgallery.core.memories.contract.MemoryTrips
import dev.pampa.mycloudgallery.core.memories.contract.MemoryVectors
import dev.pampa.mycloudgallery.core.memories.contract.MemoryView
import dev.pampa.mycloudgallery.core.memories.contract.PhotoPicker
import dev.pampa.mycloudgallery.core.memories.contract.PickSignals
import dev.pampa.mycloudgallery.core.memories.contract.PlanHistory
import dev.pampa.mycloudgallery.core.memories.contract.PlanInput
import dev.pampa.mycloudgallery.core.memories.contract.RefreshReason
import dev.pampa.mycloudgallery.core.memories.contract.RefreshReport
import dev.pampa.mycloudgallery.core.memories.contract.ShelfEmptyReason
import dev.pampa.mycloudgallery.core.memories.contract.ShelfFile
import dev.pampa.mycloudgallery.core.memories.contract.ShelfRead
import dev.pampa.mycloudgallery.core.memories.contract.ShelfState
import dev.pampa.mycloudgallery.core.memories.contract.ShelfView
import dev.pampa.mycloudgallery.core.memories.noop.NoMemoryAlbumSignals
import dev.pampa.mycloudgallery.core.memories.noop.NoMemoryCutouts
import dev.pampa.mycloudgallery.core.memories.noop.NoMemoryLookAlikes
import dev.pampa.mycloudgallery.core.memories.noop.NoMemoryPeople
import dev.pampa.mycloudgallery.core.memories.noop.NoMemoryPixels
import dev.pampa.mycloudgallery.core.memories.noop.NoMemoryTrips
import dev.pampa.mycloudgallery.core.memories.noop.NoMemoryVectors
import dev.pampa.mycloudgallery.core.memories.settings.ChoiceRules
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesChoices
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesPersonalSettings
import dev.pampa.mycloudgallery.core.personal.contract.MemoryDays
import dev.pampa.mycloudgallery.core.personal.contract.MemoryLessLevel
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.EnumMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Il servizio dei ricordi di riferimento (Fase 9, P0, §2.1, §2.3, D3, D4, D9-D11, §5.2). Le regole di D9-D11 scritte una per una, senza
 * ottimizzazioni ne' cache: lo scaffale del servizio vero di P6 deve essere **uguale** al suo su 500 storie sintetiche (giorni che passano,
 * nascosti, arrivi, scelte su due dispositivi). Passa `MemoriesContract`.
 *
 * Un giro ([refresh]): (1) l'istantanea visibile e la tabella; (2) le scelte (stato effettivo, E-MEM22); (3) la pulizia dello scaffale (solo
 * togliere: nascosti, eliminati, "Mai", persone tolte; sotto 3 elementi o oltre 30 giorni il ricordo esce; R17: un guasto dei visibili si
 * propaga); (4) i viaggi e le persone dalle porte; (5) il generatore; (6) i segnali una volta sola; (7) al piu' due ricordi nuovi al giorno
 * (piu' il mese), nell'ordine di priorita', ognuno con la scelta delle foto e il filtro finale `HiddenMask`; (8) al piu' 60 ricordi; (9) la
 * scrittura atomica. "Non mostrare" filtra la vista e lascia il ricordo nel file, cosi' "Annulla" lo riporta (D10).
 */
class InMemoryMemories(
  private val visible: VisibleMedia,
  private val mask: HiddenMask,
  private val personal: MemoriesPersonalSettings,
  private val store: MemoryShelfStore,
  private val clock: MemoryClock,
  private val planner: MemoryPlanner = ReferencePlanner(),
  private val picker: PhotoPicker = ReferencePicker(),
  private val trips: MemoryTrips = NoMemoryTrips,
  private val people: MemoryPeople = NoMemoryPeople,
  private val vectors: MemoryVectors = NoMemoryVectors,
  private val lookAlikes: MemoryLookAlikes = NoMemoryLookAlikes,
  private val pixels: MemoryPixels = NoMemoryPixels,
  private val albums: MemoryAlbumSignals = NoMemoryAlbumSignals,
  private val cutouts: MemoryCutouts = NoMemoryCutouts,
) : Memories {

  private val lock = Mutex()
  private var file: ShelfFile? = null
  private val version = MutableStateFlow(0L)

  private val shelfState = MutableStateFlow(ShelfView.WORKING)
  override val shelf: StateFlow<ShelfView> = shelfState.asStateFlow()

  private val lessState = MutableStateFlow<List<LessChoice>>(emptyList())
  override val lessChoices: StateFlow<List<LessChoice>> = lessState.asStateFlow()

  private val peopleState = MutableStateFlow(personal.current().peopleMemories)
  override val peopleMemories: StateFlow<Boolean> = peopleState.asStateFlow()

  private val statusState = MutableStateFlow<MemoriesStatus>(MemoriesStatus.Idle(0, 0, 0))
  override val status: StateFlow<MemoriesStatus> = statusState.asStateFlow()

  /** Il contenuto dello scaffale di adesso (per le prove). */
  fun current(): ShelfFile? = file

  override fun memory(id: MemoryId): Flow<MemoryView?> =
    combine(visible.snapshot, version, personal.choices) { snapshot, _, choices -> Triple(snapshot, file, choices) }
      .transform { (snapshot, shelfFile, choices) ->
        // Come il motore (RP-2): senza l'istantanea "non lo so ancora", e non si dice niente.
        if (snapshot == null) return@transform
        val memory = shelfFile?.memories?.firstOrNull { it.id == id }
        if (memory == null || id.value in choices.dismissed) {
          emit(null)
          return@transform
        }
        val view = IndexOrderedTimelineView.of(snapshot, memory.members)
        if (view.size < Memory.MIN_MEMBERS) {
          emit(null)
          return@transform
        }
        val cover = (listOf(memory.cover) + memory.members).first { snapshot.indexOf(it) >= 0 }
        val name = memory.title.person?.let { person -> people.resolve(person)?.let { people.label(it) } }
        emit(MemoryView(memory, view, cover, id.value in choices.seen, name))
      }

  override suspend fun refresh(reason: RefreshReason, budget: Duration): RefreshReport = lock.withLock {
    val deadline = clock.monotonicMs() + budget.inWholeMilliseconds
    val today = clock.today()
    val todayDay = MemoryDays.dayOf(today)
    val snapshot = visible.snapshot.value
    if (snapshot == null) {
      shelfState.value = ShelfView(emptyList(), 0, ShelfState.Empty(ShelfEmptyReason.LIBRARY_NOT_READY))
      return@withLock RefreshReport.NOTHING
    }
    statusState.value = MemoriesStatus.Working
    val (loaded, reset) = load()
    val choices = personal.current()
    val cleaned = clean(loaded, choices, today)
    val dropped = EnumMap<DropReason, Int>(DropReason::class.java)

    val table = CellTable.of(snapshot, cutouts.cutouts())
    val tripList = trips.past(today, remaining(deadline))
    val peopleOn = people.available.value
    val named = if (peopleOn && choices.peopleMemories) people.named() else emptyList()
    val history = PlanHistory(cleaned.file.memories.map { it.id }.toSet(), cleaned.file.lastPublished, cleaned.file.lastRediscovery)
    val candidates = planner.plan(PlanInput(table, tripList, named, peopleOn, choices, history), today)

    val allIds = candidates.flatMap { c -> c.cells.map { table.idAt(it) } }.distinct()
    val vectorMap = if (allIds.isEmpty()) emptyMap() else vectors.vectors(allIds)
    val signals = PickSignals(
      vectors = vectorMap,
      lookAlike = if (allIds.isEmpty()) null else lookAlikes.among(allIds),
      pixels = if (allIds.isEmpty()) emptyMap() else pixels.probe(allIds, remaining(deadline)),
      albums = if (allIds.isEmpty()) emptySet() else albums.inManualAlbums(allIds),
      people = if (peopleOn && allIds.isNotEmpty()) people.peopleIn(allIds) else emptyMap(),
      choices = choices,
    )

    var newToday = cleaned.file.memories.count { it.publishedDay == todayDay && it.kind != MemoryKind.MONTH }
    var monthToday = cleaned.file.memories.any { it.publishedDay == todayDay && it.kind == MemoryKind.MONTH }
    val lastPublished = cleaned.file.lastPublished.toMutableMap()
    var lastRediscovery = cleaned.file.lastRediscovery
    val published = ArrayList<Memory>()
    var incomplete = false
    for (candidate in candidates) {
      if (clock.monotonicMs() > deadline) {
        incomplete = true
        break
      }
      val isMonth = candidate.key.kind == MemoryKind.MONTH
      if (if (isMonth) monthToday else newToday >= MemoryRules.NEW_PER_DAY) continue
      val result = picker.pick(candidate.cells.map { table.candidate(it) }, MemoryRules.spec(candidate.key.kind), signals)
      for ((r, n) in result.dropped) dropped[r] = (dropped[r] ?: 0) + n
      if (!result.accepted) continue
      // Il filtro finale (D31): un media nascosto un attimo fa non entra.
      val hidden = mask.hiddenAmongOrThrow(result.members)
      val members = result.members.filter { it !in hidden }
      if (members.size < MemoryRules.spec(candidate.key.kind).minSize) continue
      val cover = result.cover!!.takeIf { it in members } ?: members.first()
      published += Memory(candidate.id, candidate.key, title(candidate, members, table), members, cover, todayDay, candidate.origin)
      lastPublished[candidate.key.kind] = todayDay
      if (candidate.rediscovery) lastRediscovery = todayDay
      if (isMonth) monthToday = true else newToday++
    }

    val all = (published + cleaned.file.memories).sortedWith(compareByDescending<Memory> { it.publishedDay }.thenBy { it.id.value })
    val kept = all.take(MemoryRules.SHELF_MAX)
    val out = ShelfFile(kept, lastPublished, lastRediscovery, lastRunDay = todayDay)
    store.write(out)
    file = out
    version.value++
    publishViews(choices)
    statusState.value = MemoriesStatus.Idle(todayDay, kept.size, shelfState.value.unseen)
    RefreshReport(
      published = published.size,
      trimmed = cleaned.trimmed,
      removed = cleaned.removed + (all.size - kept.size),
      shelfReset = reset,
      incomplete = incomplete,
      withoutVectors = allIds.isNotEmpty() && vectorMap.isEmpty(),
      dropped = dropped,
    )
  }

  override suspend fun prune(): RefreshReport = lock.withLock { pruneLocked() }

  override suspend fun markSeen(id: MemoryId) {
    personal.markSeen(id.value, MemoryDays.dayOf(clock.today()))
    lock.withLock { publishViews(personal.current()) }
  }

  override suspend fun dismiss(id: MemoryId, dismissed: Boolean) {
    personal.setDismissed(id.value, dismissed)
    lock.withLock {
      version.value++
      publishViews(personal.current())
    }
  }

  override suspend fun setLess(target: LessTarget, level: LessLevel?) {
    when (target) {
      is LessTarget.Person -> personal.setLess(target.person, level?.let { MemoryLessLevel.ofCode(it.code) })
      is LessTarget.Days -> {
        require(level == null || level == LessLevel.NEVER) { "Per un periodo vale solo \"Mai\"." }
        personal.setNeverDays(MemoryDays.of(target.range.from, target.range.to), level != null)
      }
    }
    lock.withLock { pruneLocked() }
  }

  override suspend fun setPeopleMemories(on: Boolean) {
    personal.setPeopleMemories(on)
    peopleState.value = on
    lock.withLock { pruneLocked() }
  }

  // --- Pulizia (§2.1, passo 7; D9, D11, E-MEM5, E-MEM6, E-MEM10) ---------------------------------------------------------------------

  private class Cleaned(val file: ShelfFile, val trimmed: Int, val removed: Int)

  private suspend fun load(): Pair<ShelfFile, Boolean> {
    file?.let { return it to false }
    return when (val read = store.read()) {
      ShelfRead.Missing -> ShelfFile.EMPTY to false
      ShelfRead.Corrupt -> ShelfFile.EMPTY to true
      is ShelfRead.Ready -> read.file to false
    }
  }

  private suspend fun pruneLocked(): RefreshReport {
    val (loaded, reset) = load()
    val choices = personal.current()
    val cleaned = clean(loaded, choices, clock.today())
    if (cleaned.file != loaded || reset) store.write(cleaned.file)
    file = cleaned.file
    version.value++
    publishViews(choices)
    return RefreshReport(0, cleaned.trimmed, cleaned.removed, reset, incomplete = false, withoutVectors = false, dropped = emptyMap())
  }

  /** Solo togliere (D9): elementi nascosti, eliminati, in un periodo "Mai" o con una persona "Mai"; ricordi vecchi, piccoli, di persone tolte. */
  private suspend fun clean(shelfFile: ShelfFile, choices: MemoriesChoices, today: LocalDate): Cleaned {
    if (shelfFile.memories.isEmpty()) return Cleaned(shelfFile, 0, 0)
    val snapshot = visible.snapshot.value
    val allMembers = shelfFile.memories.flatMap { it.members }.distinct()
    // R17: un guasto si propaga; lo scaffale resta com'era.
    val visibleNow = visible.visibleOrThrow(allMembers)
    val hiddenNow = mask.hiddenAmongOrThrow(allMembers)
    val peopleOn = people.available.value
    val inMedia = if (peopleOn) people.peopleIn(allMembers) else emptyMap()
    fun neverDay(id: MediaId): Boolean {
      val i = snapshot?.indexOf(id) ?: -1
      return i >= 0 && choices.neverOn(snapshot!!.localDayAt(i))
    }
    fun neverPerson(id: MediaId): Boolean = inMedia[id].orEmpty().any { choices.people[it] == MemoryLessLevel.NEVER }
    fun lessPerson(id: MediaId): Boolean = inMedia[id].orEmpty().any { choices.people[it] != null }
    var trimmed = 0
    var removed = 0
    val out = ArrayList<Memory>()
    for (memory in shelfFile.memories) {
      val published = MemoryDays.dateOf(memory.publishedDay)
      if (published == null || ChronoUnit.DAYS.between(published, today) >= MemoryRules.SHELF_DAYS) {
        removed++
        continue
      }
      val person = memory.title.person
      if (memory.kind == MemoryKind.PERSON && (person == null || !personStillShown(person, choices, peopleOn))) {
        removed++
        continue
      }
      val keep = memory.members.filter { it in visibleNow && it !in hiddenNow && !neverDay(it) && !neverPerson(it) }
      if (keep.size < Memory.MIN_MEMBERS) {
        removed++
        continue
      }
      trimmed += memory.members.size - keep.size
      val coverOk = memory.cover in keep && !lessPerson(memory.cover)
      val cover = if (coverOk) memory.cover else keep.firstOrNull { !lessPerson(it) } ?: keep.first()
      out += if (keep.size == memory.members.size && cover == memory.cover) memory else memory.copy(members = keep, cover = cover)
    }
    return Cleaned(shelfFile.copy(memories = out), trimmed, removed)
  }

  private suspend fun personStillShown(person: PersonId, choices: MemoriesChoices, peopleOn: Boolean): Boolean =
    ChoiceRules.personStillShown(choices, person, peopleOn, if (peopleOn) people.resolve(person) else null, undecided = people.undecided)

  // --- Titolo e viste ------------------------------------------------------------------------------------------------------------------

  private fun title(candidate: MemoryCandidate, members: List<MediaId>, table: CellTable): MemoryTitle {
    val places = if (candidate.tripPlaces.isNotEmpty()) {
      candidate.tripPlaces.filter { it > 0 }.distinct().take(MemoryTitle.MAX_PLACES)
    } else {
      MemoryTitle.topPlaces(members.map { id -> table.indexOf(id).let { if (it >= 0) table.placeAt(it) else 0 } })
    }
    return MemoryTitle(candidate.yearsAgo, candidate.days, places, candidate.tripLabel, candidate.person, candidate.month)
  }

  private suspend fun publishViews(choices: MemoriesChoices) {
    val memories = file?.memories.orEmpty()
    val items = memories.filter { it.id.value !in choices.dismissed }.map {
      MemoryCard(it.id, it.kind, it.title, it.cover, it.members.size, it.publishedDay, isNew = it.id.value !in choices.seen)
    }
    val state = if (items.isEmpty()) ShelfState.Empty(ShelfEmptyReason.NOT_ENOUGH_PHOTOS) else ShelfState.Ready
    shelfState.value = ShelfView(items, items.count { it.isNew }, state)
    peopleState.value = choices.peopleMemories
    val less = ArrayList<LessChoice>()
    for ((person, level) in choices.people.entries.sortedBy { it.key }) {
      less += LessChoice(LessTarget.Person(person), LessLevel.valueOf(level.name), if (people.available.value) people.label(person) else null)
    }
    for (days in choices.neverDays.sortedBy { it.from }) {
      less += LessChoice(LessTarget.Days(dev.pampa.mycloudgallery.core.memories.contract.DayRange(days.first, days.last)), LessLevel.NEVER, null)
    }
    lessState.value = less
  }

  private fun remaining(deadline: Long): Duration = (deadline - clock.monotonicMs()).coerceAtLeast(0).milliseconds
}
