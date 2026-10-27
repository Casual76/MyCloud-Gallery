package dev.pampa.mycloudgallery.core.memories.noop

import dev.pampa.mycloudgallery.core.memories.contract.LessChoice
import dev.pampa.mycloudgallery.core.memories.contract.LessLevel
import dev.pampa.mycloudgallery.core.memories.contract.LessTarget
import dev.pampa.mycloudgallery.core.memories.contract.LockScreenMode
import dev.pampa.mycloudgallery.core.memories.contract.LookAlike
import dev.pampa.mycloudgallery.core.memories.contract.Memories
import dev.pampa.mycloudgallery.core.memories.contract.MemoriesStatus
import dev.pampa.mycloudgallery.core.memories.contract.Memory
import dev.pampa.mycloudgallery.core.memories.contract.MemoryAlbumSignals
import dev.pampa.mycloudgallery.core.memories.contract.MemoryClips
import dev.pampa.mycloudgallery.core.memories.contract.MemoryCutouts
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.memories.contract.MemoryLookAlikes
import dev.pampa.mycloudgallery.core.memories.contract.MemoryNotifyDecisions
import dev.pampa.mycloudgallery.core.memories.contract.MemoryPeople
import dev.pampa.mycloudgallery.core.memories.contract.MemoryPixels
import dev.pampa.mycloudgallery.core.memories.contract.MemoryPlanner
import dev.pampa.mycloudgallery.core.memories.contract.MemoryTrips
import dev.pampa.mycloudgallery.core.memories.contract.MemoryVectors
import dev.pampa.mycloudgallery.core.memories.contract.MemoryView
import dev.pampa.mycloudgallery.core.memories.contract.MusicChoice
import dev.pampa.mycloudgallery.core.memories.contract.MusicLibrary
import dev.pampa.mycloudgallery.core.memories.contract.MusicSource
import dev.pampa.mycloudgallery.core.memories.contract.MusicTrackState
import dev.pampa.mycloudgallery.core.memories.contract.NotifyCandidate
import dev.pampa.mycloudgallery.core.memories.contract.NotifyContent
import dev.pampa.mycloudgallery.core.memories.contract.NotifyLedger
import dev.pampa.mycloudgallery.core.memories.contract.NotifyPrefs
import dev.pampa.mycloudgallery.core.memories.contract.NotifyTexts
import dev.pampa.mycloudgallery.core.memories.contract.PersonDigest
import dev.pampa.mycloudgallery.core.memories.contract.PhotoPicker
import dev.pampa.mycloudgallery.core.memories.contract.PickResult
import dev.pampa.mycloudgallery.core.memories.contract.PixelProbe
import dev.pampa.mycloudgallery.core.memories.contract.RefreshReason
import dev.pampa.mycloudgallery.core.memories.contract.RefreshReport
import dev.pampa.mycloudgallery.core.memories.contract.ShelfView
import dev.pampa.mycloudgallery.core.memories.contract.SlotDecision
import dev.pampa.mycloudgallery.core.memories.contract.TextRef
import dev.pampa.mycloudgallery.core.memories.contract.TripDigestView
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf

// I null-object dei ricordi (Fase 9, P0), fuori da `testing` e quindi buoni anche per la release: :app li lega finche' P13 non integra i pezzi
// veri. Rispondono "niente": nessun ricordo, nessun viaggio, nessuna persona, nessun vettore, nessuna notifica. Nessuno scrive niente.

/** Nessun ricordo: la sezione dice "I ricordi arrivano quando ci sono abbastanza foto". */
object NoMemories : Memories {
  override val shelf: StateFlow<ShelfView> = MutableStateFlow(ShelfView.EMPTY).asStateFlow()

  override fun memory(id: MemoryId): Flow<MemoryView?> = flowOf(null)

  override suspend fun refresh(reason: RefreshReason, budget: Duration): RefreshReport = RefreshReport.NOTHING

  override suspend fun prune(): RefreshReport = RefreshReport.NOTHING

  override suspend fun markSeen(id: MemoryId) = Unit

  override suspend fun dismiss(id: MemoryId, dismissed: Boolean) = Unit

  override suspend fun setLess(target: LessTarget, level: LessLevel?) = Unit

  override val lessChoices: StateFlow<List<LessChoice>> = MutableStateFlow(emptyList<LessChoice>()).asStateFlow()

  override val peopleMemories: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()

  override suspend fun setPeopleMemories(on: Boolean) = Unit

  override val status: StateFlow<MemoriesStatus> = MutableStateFlow<MemoriesStatus>(MemoriesStatus.Idle(0, 0, 0)).asStateFlow()
}

object NoMemoryPlanner : MemoryPlanner {
  override fun plan(input: dev.pampa.mycloudgallery.core.memories.contract.PlanInput, today: LocalDate) =
    emptyList<dev.pampa.mycloudgallery.core.memories.contract.MemoryCandidate>()
}

/** Rifiuta tutto: nessun ricordo nasce. */
object NoPhotoPicker : PhotoPicker {
  override fun pick(
    candidates: List<dev.pampa.mycloudgallery.core.memories.contract.CandidateCell>,
    spec: dev.pampa.mycloudgallery.core.memories.contract.PickSpec,
    signals: dev.pampa.mycloudgallery.core.memories.contract.PickSignals,
  ): PickResult = PickResult.rejected(emptyMap())
}

/** Nessun viaggio: i ricordi di viaggio ed evento tacciono. */
object NoMemoryTrips : MemoryTrips {
  override suspend fun past(until: LocalDate, budget: Duration): List<TripDigestView> = emptyList()
}

/** Persone non disponibili qui: nessun ricordo `PERSON`, nessuna persona nelle foto. */
object NoMemoryPeople : MemoryPeople {
  override val available: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

  override suspend fun named(): List<PersonDigest> = emptyList()

  override suspend fun peopleIn(ids: Collection<MediaId>): Map<MediaId, Set<PersonId>> = emptyMap()

  override suspend fun resolve(person: PersonId): PersonId? = null

  override suspend fun label(person: PersonId): String? = null
}

object NoMemoryVectors : MemoryVectors {
  override suspend fun vectors(ids: Collection<MediaId>): Map<MediaId, FloatArray> = emptyMap()
}

object NoMemoryLookAlikes : MemoryLookAlikes {
  override suspend fun among(ids: Collection<MediaId>): LookAlike? = null
}

object NoMemoryPixels : MemoryPixels {
  override suspend fun probe(ids: Collection<MediaId>, budget: Duration): Map<MediaId, PixelProbe> = emptyMap()
}

object NoMemoryAlbumSignals : MemoryAlbumSignals {
  override suspend fun inManualAlbums(ids: Collection<MediaId>): Set<MediaId> = emptySet()
}

object NoMemoryCutouts : MemoryCutouts {
  override suspend fun cutouts(): Set<MediaId> = emptySet()
}

object NoMemoryClips : MemoryClips {
  override suspend fun clip(id: MediaId): dev.pampa.mycloudgallery.core.memories.contract.ClipSource? = null
}

/** Nessun brano installabile (il catalogo di Q3 e' vuoto finche' l'utente non approva fonti e brani). */
object NoMusicLibrary : MusicLibrary {
  override val tracks: StateFlow<Map<String, MusicTrackState>> = MutableStateFlow(emptyMap<String, MusicTrackState>()).asStateFlow()

  override suspend fun install(trackId: String): Boolean = false

  override suspend fun open(choice: MusicChoice): MusicSource? = null
}

/** Nessuna notifica, mai: nessun orario, nessuna scelta, contenuto sempre generico. */
object NeverNotify : MemoryNotifyDecisions {
  override fun nextSlot(now: LocalDateTime, prefs: NotifyPrefs): LocalDateTime? = null

  override fun slot(now: LocalDateTime, prefs: NotifyPrefs): SlotDecision = SlotDecision.Wait(now.plusDays(1), skipped = false)

  override fun choose(candidates: List<NotifyCandidate>, prefs: NotifyPrefs, ledger: NotifyLedger, today: LocalDate, openedToday: Boolean): MemoryId? =
    null

  override fun content(memory: Memory, mode: LockScreenMode): NotifyContent {
    val title = TextRef(NotifyTexts.GENERIC_TITLE)
    val text = TextRef(NotifyTexts.GENERIC_TEXT)
    return NotifyContent(title, text, null, title, text)
  }
}
