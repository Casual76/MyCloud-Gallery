package dev.pampa.mycloudgallery.core.memories.plan

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.common.perf.AvvioTrace
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.index.IndexOrderedTimelineView
import dev.pampa.mycloudgallery.core.memories.contract.CellTable
import dev.pampa.mycloudgallery.core.memories.contract.DayRange
import dev.pampa.mycloudgallery.core.memories.contract.LessChoice
import dev.pampa.mycloudgallery.core.memories.contract.LessLevel
import dev.pampa.mycloudgallery.core.memories.contract.LessTarget
import dev.pampa.mycloudgallery.core.memories.contract.Memories
import dev.pampa.mycloudgallery.core.memories.contract.MemoriesStatus
import dev.pampa.mycloudgallery.core.memories.contract.Memory
import dev.pampa.mycloudgallery.core.memories.contract.MemoryAlbumSignals
import dev.pampa.mycloudgallery.core.memories.contract.MemoryClock
import dev.pampa.mycloudgallery.core.memories.contract.MemoryCutouts
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.memories.contract.MemoryLookAlikes
import dev.pampa.mycloudgallery.core.memories.contract.MemoryPeople
import dev.pampa.mycloudgallery.core.memories.contract.MemoryPixels
import dev.pampa.mycloudgallery.core.memories.contract.MemoryPlanner
import dev.pampa.mycloudgallery.core.memories.contract.MemoryRules
import dev.pampa.mycloudgallery.core.memories.contract.MemoryShelfStore
import dev.pampa.mycloudgallery.core.memories.contract.MemoryTrips
import dev.pampa.mycloudgallery.core.memories.contract.MemoryVectors
import dev.pampa.mycloudgallery.core.memories.contract.MemoryView
import dev.pampa.mycloudgallery.core.memories.contract.PhotoPicker
import dev.pampa.mycloudgallery.core.memories.contract.PlanHistory
import dev.pampa.mycloudgallery.core.memories.contract.PlanInput
import dev.pampa.mycloudgallery.core.memories.contract.RefreshReason
import dev.pampa.mycloudgallery.core.memories.contract.RefreshReport
import dev.pampa.mycloudgallery.core.memories.contract.ShelfFile
import dev.pampa.mycloudgallery.core.memories.contract.ShelfRead
import dev.pampa.mycloudgallery.core.memories.contract.ShelfView
import dev.pampa.mycloudgallery.core.memories.noop.NoMemoryAlbumSignals
import dev.pampa.mycloudgallery.core.memories.noop.NoMemoryCutouts
import dev.pampa.mycloudgallery.core.memories.noop.NoMemoryLookAlikes
import dev.pampa.mycloudgallery.core.memories.noop.NoMemoryPeople
import dev.pampa.mycloudgallery.core.memories.noop.NoMemoryPixels
import dev.pampa.mycloudgallery.core.memories.noop.NoMemoryTrips
import dev.pampa.mycloudgallery.core.memories.noop.NoMemoryVectors
import dev.pampa.mycloudgallery.core.memories.shelf.MemoryVisibility
import dev.pampa.mycloudgallery.core.memories.shelf.ShelfCards
import dev.pampa.mycloudgallery.core.memories.shelf.ShelfPruner
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesChoices
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesPersonalSettings
import dev.pampa.mycloudgallery.core.personal.contract.MemoryDays
import dev.pampa.mycloudgallery.core.personal.contract.MemoryLessLevel
import java.lang.ref.SoftReference
import java.lang.ref.WeakReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Il motore dei ricordi (docs/fase9-progetto.md §2.1, §2.3, D3, D4, D9-D11, D19, D31, E-MEM1-E-MEM22): il servizio vero di [Memories]. Tutto
 * locale (nessuna rete, nessun NAS, D4); "oggi" dalla porta [MemoryClock] (D36); le scelte dai dati personali ([MemoriesPersonalSettings]); lo
 * scaffale da [MemoryShelfStore] (`FileShelfStore` in produzione). Passa `MemoriesContract` ed e' **uguale** al riferimento (`InMemoryMemories`)
 * su 500 storie sintetiche (`MemoryEngineParityTest`, §5.2).
 *
 * **Il giro** ([refresh], uguale per [RefreshReason.NIGHTLY], [RefreshReason.LIGHT] e [RefreshReason.OPEN]: cambia solo il budget; `OPEN`
 * non rifa' niente se oggi il giro e' gia' stato fatto, D4 (c)): pulisce lo scaffale (`ShelfPruner`, solo togliere), costruisce la tabella a
 * colonne dell'istantanea visibile (rifatta solo se l'istantanea e' cambiata), chiede viaggi e persone alle porte, il generatore da' i
 * candidati di oggi, `PublishRound` sceglie le foto e pubblica al piu' due ricordi nuovi (piu' il mese), e lo scaffale si riscrive **una volta**,
 * in modo atomico. Un budget finito lascia lo scaffale com'era (valido) e dice "incompleto".
 *
 * **Istantaneo** (principio "Avvio e pagine istantanei"): [restore] legge lo scaffale da file in un colpo e disegna subito Raccolte -> Ricordi
 * dall'ultimo scaffale, **senza** aspettare la Libreria, il database o la generazione, che non stanno sul percorso dell'avvio. Il lavoro
 * pesante (tabella, generatore, scelta delle foto, file) gira su [dispatcher] (di serie `Dispatchers.Default`): mai sul thread principale.
 *
 * **Le superfici si rifanno da sole**: [shelf], [lessChoices] e [peopleMemories] seguono le scelte dell'utente (anche quelle che arrivano da un
 * altro dispositivo con la sincronizzazione) e l'istantanea; [memory] e' viva sull'istantanea visibile (un nascosto esce subito, E-MEM5). Lo
 * scaffale salvato cambia solo in [refresh], [prune] e nelle scelte che lo toccano ([setLess], [setPeopleMemories]).
 *
 * **Nessun dato personale nei log**: solo numeri; mai titoli, luoghi, date, id o nomi.
 *
 * @param scope dove vivono i flussi derivati (di norma lo scope dell'app); [library] e' la visibilita' dei media (vedi [MemoryVisibility]).
 * @param choice il passo 5 per ricordo (segnali e scelta delle foto, P7, vedi [MemoryChoice]); se manca si usa [PortsMemoryChoice] sopra [picker] e le
 * porte dei segnali ([vectors], [lookAlikes], [pixels], [albums], [people]): uno dei due ci vuole.
 */
class MemoryEngine(
  private val library: MemoryVisibility,
  private val personal: MemoriesPersonalSettings,
  private val store: MemoryShelfStore,
  private val clock: MemoryClock,
  scope: CoroutineScope,
  picker: PhotoPicker? = null,
  private val planner: MemoryPlanner = IndexedPlanner(),
  private val trips: MemoryTrips = NoMemoryTrips,
  private val people: MemoryPeople = NoMemoryPeople,
  vectors: MemoryVectors = NoMemoryVectors,
  lookAlikes: MemoryLookAlikes = NoMemoryLookAlikes,
  pixels: MemoryPixels = NoMemoryPixels,
  albums: MemoryAlbumSignals = NoMemoryAlbumSignals,
  private val cutouts: MemoryCutouts = NoMemoryCutouts,
  private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
  choice: MemoryChoice? = null,
) : Memories {

  /** Lo scaffale in memoria; [file] null = nessuno scaffale noto (mai un giro, o il file non c'era o era rovinato). Confronto per identita': ogni scrittura emette. */
  private class Held(val file: ShelfFile?)

  private class CachedTable(val snapshot: WeakReference<TimelineSnapshot>, val cutouts: Set<MediaId>, val table: CellTable)

  private val lock = Mutex()
  private val held = MutableStateFlow(Held(null))
  private var loaded = false
  private var resetPending = false
  private var tableCache: SoftReference<CachedTable>? = null

  private val pruner = ShelfPruner(library, people)
  private val round = PublishRound(
    library,
    choice ?: PortsMemoryChoice(requireNotNull(picker) { "Serve una scelta delle foto: `choice` o `picker`." }, vectors, lookAlikes, pixels, albums, people),
    clock,
  )

  private val statusState = MutableStateFlow<MemoriesStatus>(MemoriesStatus.Idle(0, 0, 0))
  override val status: StateFlow<MemoriesStatus> = statusState.asStateFlow()

  override val shelf: StateFlow<ShelfView> = combine(held, personal.choices, library.snapshot) { h, choices, snapshot ->
    ShelfCards.view(h.file, choices, snapshot)
  }.stateIn(scope, SharingStarted.Eagerly, ShelfCards.view(null, personal.current(), library.snapshot.value))

  override val lessChoices: StateFlow<List<LessChoice>> = combine(personal.choices, people.available) { choices, _ -> lessChoicesOf(choices, names = true) }
    .stateIn(scope, SharingStarted.Eagerly, plainLessChoices(personal.current()))

  override val peopleMemories: StateFlow<Boolean> = personal.choices.map { it.peopleMemories }
    .stateIn(scope, SharingStarted.Eagerly, personal.current().peopleMemories)

  /** Lo scaffale di adesso nel file (per le prove e per `MemoryNotifier`); null se non c'e'. */
  fun current(): ShelfFile? = held.value.file

  // --- Avvio ------------------------------------------------------------------------------------------------------------------------

  /**
   * Legge lo scaffale da file (una volta) e lo mostra: [shelf] e [status] partono da cio' che c'era all'ultimo giro. Si chiama all'avvio, fuori
   * dal thread principale; non aspetta la Libreria. Un file rovinato vale "scaffale vuoto": [refresh] lo rigenera e lo dice.
   */
  suspend fun restore() {
    withContext(dispatcher) { lock.withLock { ensureLoaded() } }
  }

  /** Lascia la tabella a colonne dell'istantanea (`onTrimMemory`): si rifa' al giro dopo (~20 ms ogni 100 mila celle). */
  fun releaseCaches() {
    tableCache = null
  }

  // --- Ricordi ----------------------------------------------------------------------------------------------------------------------

  override fun memory(id: MemoryId): Flow<MemoryView?> =
    combine(library.snapshot, held, personal.choices) { snapshot, h, choices -> Triple(snapshot, h.file, choices) }
      .transform { (snapshot, file, choices) ->
        // RP-2: senza l'istantanea della Libreria non si sa se il ricordo c'e': niente da dire (mai `null`, che vorrebbe dire "non c'e'"). Le schermate aspettano.
        if (snapshot == null) return@transform
        val memory = file?.memories?.firstOrNull { it.id == id }
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

  /**
   * Il giro, **con la sezione di traccia** che il benchmark misura (Fase 9, P12, §5.4): `MCG:memories.generate` per il budget notturno, `MCG:memories.generate.light`
   * per `LIGHT` e `OPEN`. La sezione comincia a lavoro iniziato (dopo l'attesa del blocco): misura il giro, non la coda. Nessun dato nel nome.
   */
  override suspend fun refresh(reason: RefreshReason, budget: Duration): RefreshReport =
    withContext(dispatcher) {
      lock.withLock {
        val section = if (reason == RefreshReason.NIGHTLY) AvvioTrace.MEMORIES_GENERATE else AvvioTrace.MEMORIES_GENERATE_LIGHT
        AvvioTrace.asyncSection(section) { refreshLocked(reason, budget) }
      }
    }

  override suspend fun prune(): RefreshReport = withContext(dispatcher) { lock.withLock { pruneLocked() } }

  // --- Scelte dell'utente -----------------------------------------------------------------------------------------------------------

  override suspend fun markSeen(id: MemoryId) = personal.markSeen(id.value, MemoryDays.dayOf(clock.today()))

  override suspend fun dismiss(id: MemoryId, dismissed: Boolean) = personal.setDismissed(id.value, dismissed)

  override suspend fun setLess(target: LessTarget, level: LessLevel?) {
    when (target) {
      is LessTarget.Person -> personal.setLess(target.person, level?.let { MemoryLessLevel.ofCode(it.code) })
      is LessTarget.Days -> {
        require(level == null || level == LessLevel.NEVER) { "Per un periodo vale solo \"Mai\"." }
        personal.setNeverDays(MemoryDays.of(target.range.from, target.range.to), level != null)
      }
    }
    prune()
  }

  override suspend fun setPeopleMemories(on: Boolean) {
    personal.setPeopleMemories(on)
    prune()
  }

  // --- Il giro ----------------------------------------------------------------------------------------------------------------------

  private suspend fun refreshLocked(reason: RefreshReason, budget: Duration): RefreshReport {
    val deadline = clock.monotonicMs() + budget.inWholeMilliseconds
    val today = clock.today()
    val todayDay = MemoryDays.dayOf(today)
    val snapshot = library.snapshot.value ?: return RefreshReport.NOTHING
    ensureLoaded()
    val before = held.value.file
    if (reason == RefreshReason.OPEN && before != null && before.lastRunDay == todayDay) return RefreshReport.NOTHING
    val previous = statusState.value
    statusState.value = MemoriesStatus.Working
    var done = false
    try {
      val choices = personal.current()
      val reset = resetPending
      // La pulizia per prima (D9, R17): un guasto dei visibili lancia qui e lo scaffale resta com'era.
      val cleaned = pruner.clean(before ?: ShelfFile.EMPTY, choices, today)
      val table = tableFor(snapshot, cutouts.cutouts())
      val tripList = trips.past(today, remaining(deadline))
      val peopleOn = people.available.value
      val named = if (peopleOn && choices.peopleMemories) people.named() else emptyList()
      val history = PlanHistory(cleaned.file.memories.map { it.id }.toSet(), cleaned.file.lastPublished, cleaned.file.lastRediscovery)
      val candidates = planner.plan(PlanInput(table, tripList, named, peopleOn, choices, history), today)
      val news = round.run(candidates, table, choices, cleaned.file, todayDay, deadline)
      val all = (news.memories + cleaned.file.memories).sortedWith(compareByDescending<Memory> { it.publishedDay }.thenBy { it.id.value })
      val kept = all.take(MemoryRules.SHELF_MAX)
      val out = ShelfFile(kept, news.lastPublished, news.lastRediscovery, todayDay)
      // Si riscrive sempre (una volta per giro, ~40 KB, atomico): lo scaffale in memoria e quello su file tornano uguali anche se il file e' stato rovinato nel frattempo.
      store.write(out)
      resetPending = false
      held.value = Held(out)
      statusState.value = MemoriesStatus.Idle(todayDay, kept.size, ShelfCards.view(out, choices, snapshot).unseen)
      done = true
      val removed = cleaned.removed + (all.size - kept.size)
      GalleryLog.d(TAG) { "giro ${reason.code}: nuovi=${news.memories.size}, elementi tolti=${cleaned.trimmed}, usciti=$removed, incompleto=${news.incomplete}" }
      return RefreshReport(news.memories.size, cleaned.trimmed, removed, reset, news.incomplete, news.withoutVectors, news.dropped)
    } finally {
      if (!done) statusState.value = previous
    }
  }

  private suspend fun pruneLocked(): RefreshReport {
    ensureLoaded()
    val shelfFile = held.value.file
    if (shelfFile == null) {
      if (!resetPending) {
        // Nessun file (il primo giro non c'e' ancora stato): niente da ripulire. Lo scaffale e' "vuoto e mai riempito": la sezione dice ancora "sto preparando".
        held.value = Held(ShelfFile.EMPTY)
        return RefreshReport.NOTHING
      }
      // Il file era rovinato: si sostituisce con uno scaffale vuoto (il giro dopo lo riempie) e lo si dice.
      store.write(ShelfFile.EMPTY)
      resetPending = false
      held.value = Held(ShelfFile.EMPTY)
      return RefreshReport.NOTHING.copy(shelfReset = true)
    }
    val choices = personal.current()
    val cleaned = pruner.clean(shelfFile, choices, clock.today())
    if (cleaned.file != shelfFile) {
      store.write(cleaned.file)
      held.value = Held(cleaned.file)
    }
    statusState.value = MemoriesStatus.Idle(
      cleaned.file.lastRunDay, cleaned.file.memories.size, ShelfCards.view(cleaned.file, choices, library.snapshot.value).unseen,
    )
    return RefreshReport.NOTHING.copy(trimmed = cleaned.trimmed, removed = cleaned.removed)
  }

  /**
   * La lettura dello scaffale da file: una volta sola **finche' c'e' uno scaffale in memoria**; se non c'e' (il file non c'era, o era rovinato e non
   * si e' ancora riscritto) si rilegge a ogni giro, perche' nel frattempo puo' essere cambiato. Un errore del disco lancia e si riprova alla
   * chiamata dopo.
   */
  private suspend fun ensureLoaded() {
    if (loaded && held.value.file != null) return
    when (val read = store.read()) {
      ShelfRead.Missing -> held.value = Held(null)
      ShelfRead.Corrupt -> {
        resetPending = true
        held.value = Held(null)
        GalleryLog.w(TAG) { "scaffale rovinato: si rigenera" }
      }
      is ShelfRead.Ready -> {
        held.value = Held(read.file)
        val unseen = ShelfCards.view(read.file, personal.current(), library.snapshot.value).unseen
        statusState.value = MemoriesStatus.Idle(read.file.lastRunDay, read.file.memories.size, unseen)
      }
    }
    loaded = true
  }

  /** La tabella a colonne dell'istantanea: si rifa' solo se l'istantanea (o i ritagli) e' cambiata; leggera da tenere (si rilascia con la memoria). */
  private fun tableFor(snapshot: TimelineSnapshot, cutoutIds: Set<MediaId>): CellTable {
    val cached = tableCache?.get()
    if (cached != null && cached.snapshot.get() === snapshot && cached.cutouts == cutoutIds) return cached.table
    val table = CellTable.of(snapshot, cutoutIds)
    tableCache = SoftReference(CachedTable(WeakReference(snapshot), cutoutIds, table))
    return table
  }

  private fun remaining(deadline: Long): Duration = (deadline - clock.monotonicMs()).coerceAtLeast(0).milliseconds

  // --- Scelte "Mostra meno" e "Mai" per le Impostazioni -------------------------------------------------------------------------

  /** Le scelte senza nomi (persone e periodi, nell'ordine di sempre): il valore di partenza, finche' i nomi non si leggono. */
  private fun plainLessChoices(choices: MemoriesChoices): List<LessChoice> {
    val out = ArrayList<LessChoice>()
    for ((person, level) in choices.people.entries.sortedBy { it.key }) {
      out += LessChoice(LessTarget.Person(person), LessLevel.entries.first { it.code == level.code }, null)
    }
    for (days in choices.neverDays.sortedBy { it.from }) out += LessChoice(LessTarget.Days(DayRange(days.first, days.last)), LessLevel.NEVER, null)
    return out
  }

  /** Come [plainLessChoices], con il nome di ogni persona **letto adesso** dalla porta (mai salvato): null dove Persone non e' attiva qui. */
  private suspend fun lessChoicesOf(choices: MemoriesChoices, names: Boolean): List<LessChoice> {
    val plain = plainLessChoices(choices)
    if (!names || !people.available.value) return plain
    return plain.map { choice ->
      val target = choice.target
      if (target is LessTarget.Person) choice.copy(personName = people.label(target.person)) else choice
    }
  }

  private companion object {
    const val TAG = "MemoryEngine"
  }
}
