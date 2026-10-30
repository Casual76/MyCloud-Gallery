package dev.pampa.mycloudgallery.core.memories.plan.people

import dev.pampa.mycloudgallery.core.memories.contract.MemoryShelfStore
import dev.pampa.mycloudgallery.core.memories.contract.RefreshReason
import dev.pampa.mycloudgallery.core.memories.contract.RefreshReport
import dev.pampa.mycloudgallery.core.memories.pick.MemoryPhotoPicker
import dev.pampa.mycloudgallery.core.memories.pick.PickSignalsLoader
import dev.pampa.mycloudgallery.core.memories.pick.VisiblePhotoPicker
import dev.pampa.mycloudgallery.core.memories.plan.IndexedPlanner
import dev.pampa.mycloudgallery.core.memories.plan.MemoryEngine
import dev.pampa.mycloudgallery.core.memories.plan.MemoryPhotoChoice
import dev.pampa.mycloudgallery.core.memories.plan.visibilityOf
import dev.pampa.mycloudgallery.core.memories.settings.less.AliasFollowingSettings
import dev.pampa.mycloudgallery.core.memories.settings.less.PeopleLessJanitor
import dev.pampa.mycloudgallery.core.memories.settings.less.PhaseGatedPeople
import dev.pampa.mycloudgallery.core.memories.testing.FakeMemoryClock
import dev.pampa.mycloudgallery.core.memories.testing.FakeMemoryPeople
import dev.pampa.mycloudgallery.core.memories.testing.FakePeopleSwitch
import dev.pampa.mycloudgallery.core.memories.testing.InMemoryShelfStore
import dev.pampa.mycloudgallery.core.memories.testing.SyntheticMemoryLibrary
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.personal.testing.InMemoryMemoriesPersonalSettings
import dev.pampa.mycloudgallery.core.personal.testing.MemoriesRegister
import java.time.LocalDate
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel

/**
 * Un dispositivo finto con **tutti i pezzi di P8 veri** (P8, docs/fase9-progetto.md D11, D12): il motore di P6 con il generatore delle persone
 * ([PeoplePlanner]), la scelta delle foto di P7 (di serie; [production] falso = il riferimento), le scelte dell'utente su un registro che puo'
 * essere condiviso ([register]) con `AliasFollowingSettings` sopra, le persone finte, lo stato di Persone e il janitor. Il motore gira su
 * `Dispatchers.Unconfined`: le prove sono deterministiche.
 */
internal class PeopleRig(
  val lib: SyntheticMemoryLibrary = SyntheticMemoryLibrary(),
  today: LocalDate = LocalDate.of(2026, 11, 3),
  val register: MemoriesRegister = MemoriesRegister(),
  val store: MemoryShelfStore = InMemoryShelfStore(),
  production: Boolean = true,
) {
  val scope = CoroutineScope(Dispatchers.Unconfined + Job())
  val clock = FakeMemoryClock(today)
  val raw = InMemoryMemoriesPersonalSettings(register)
  val people = FakeMemoryPeople(available = true) { ids -> lib.visibility.visible(ids) }
  val switch = FakePeopleSwitch()

  /** Le persone come le vede il motore: spente subito con lo stato di Persone (P13 le lega cosi'). */
  val gated = PhaseGatedPeople(people, switch, scope)
  val settings = AliasFollowingSettings(raw, gated)

  val engine = MemoryEngine(
    library = visibilityOf(lib),
    personal = settings,
    store = store,
    clock = clock,
    scope = scope,
    picker = if (production) null else dev.pampa.mycloudgallery.core.memories.testing.ReferencePicker(),
    planner = IndexedPlanner(people = PeoplePlanner()),
    people = gated,
    dispatcher = Dispatchers.Unconfined,
    choice = if (production) {
      MemoryPhotoChoice(PickSignalsLoader(people = gated), VisiblePhotoPicker(MemoryPhotoPicker(), lib.visibility))
    } else {
      null
    },
  )

  val janitor = PeopleLessJanitor(settings, gated, switch, engine)

  suspend fun refresh(): RefreshReport = engine.refresh(RefreshReason.NIGHTLY, 60.seconds)

  /** Una persona con [name] e [perDay] foto in ognuno dei [days]; torna l'id e i suoi media. */
  fun person(name: String, days: List<LocalDate>, perDay: Int = 8, place: Int = 0): Pair<PersonId, List<MediaId>> {
    val id = PersonId.random()
    val media = days.flatMap { lib.photos(it, perDay, place = place) }
    people.person(id, name, media)
    return id to media
  }

  /** Quanti ricordi di persone ci sono sullo scaffale in memoria. */
  fun personMemories(): List<dev.pampa.mycloudgallery.core.memories.contract.Memory> =
    engine.current()?.memories.orEmpty().filter { it.kind == dev.pampa.mycloudgallery.core.memories.contract.MemoryKind.PERSON }

  fun close() = scope.cancel()

  companion object {
    /** Cinque giorni di 2025 sparsi: 40 foto in 5 giorni, abbastanza per un ricordo `PERSON` (D3). */
    val DAYS_2025: List<LocalDate> = listOf(3, 9, 15, 21, 27).map { LocalDate.of(2025, 4, it) }
  }
}
