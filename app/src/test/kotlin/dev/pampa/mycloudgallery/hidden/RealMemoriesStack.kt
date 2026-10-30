package dev.pampa.mycloudgallery.hidden

import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.visibility.CatalogHiddenMask
import dev.pampa.mycloudgallery.core.library.visibility.CatalogVisibleMedia
import dev.pampa.mycloudgallery.core.memories.contract.DayRange
import dev.pampa.mycloudgallery.core.memories.contract.MemoryCandidate
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.MemoryPlanner
import dev.pampa.mycloudgallery.core.memories.contract.PlanInput
import dev.pampa.mycloudgallery.core.memories.contract.RefreshReason
import dev.pampa.mycloudgallery.core.memories.contract.TripDigestView
import dev.pampa.mycloudgallery.core.memories.pick.MemoryPhotoPicker
import dev.pampa.mycloudgallery.core.memories.pick.PickSignalsLoader
import dev.pampa.mycloudgallery.core.memories.pick.VisiblePhotoPicker
import dev.pampa.mycloudgallery.core.memories.plan.IndexedPlanner
import dev.pampa.mycloudgallery.core.memories.plan.MemoryEngine
import dev.pampa.mycloudgallery.core.memories.plan.MemoryPhotoChoice
import dev.pampa.mycloudgallery.core.memories.shelf.MemoryVisibility
import dev.pampa.mycloudgallery.core.memories.testing.FakeMemoryClock
import dev.pampa.mycloudgallery.core.memories.testing.FakeMemoryPeople
import dev.pampa.mycloudgallery.core.memories.testing.FakeMemoryTrips
import dev.pampa.mycloudgallery.core.memories.testing.InMemoryShelfStore
import dev.pampa.mycloudgallery.core.memories.testing.ReferencePlanner
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.model.TripKind
import dev.pampa.mycloudgallery.core.personal.testing.InMemoryMemoriesPersonalSettings
import dev.pampa.mycloudgallery.core.personal.testing.MemoriesRegister
import java.time.LocalDate
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Fase 9 (P9, D31, E-MEM5, E-H8, E-H10; 30/10): lo STACK VERO dei ricordi sopra il catalogo VERO di un mondo casuale: il motore di P6 (`MemoryEngine`) con la
// scelta delle foto di P7 (`MemoryPhotoChoice` + `VisiblePhotoPicker` con la maschera vera), la visibilita' vera (`CatalogVisibleMedia`, `CatalogHiddenMask`) e le
// scelte dell'utente nel registro finto dei dati personali. Quello che il motore NON ha ancora qui sono le porte di P8 (viaggi e persone): si finge **con le
// stesse regole delle porte vere** (solo media visibili) e, di proposito, **anche con un viaggio che include dei nascosti** (una porta che sbaglia non deve far
// uscire un nascosto: il filtro finale e la scelta delle foto lo tolgono).
//
// L'istantanea del motore e' quella del mondo (costruita prima degli ultimi nascosti): resta com'e', come in produzione nell'istante fra un "Nascondi" e il
// nuovo indice. Tutto il resto lo decide il catalogo vero.

/** Tre "oggi" sul mondo casuale (i suoi media stanno nel marzo 2026): il mese prima, gli anniversari e un giorno in cui nasce solo il ricordo di una persona. */
internal enum class MemoriesDay(val today: LocalDate) {
  /** Il 3 aprile 2026: "il meglio del mese" per marzo. */
  MONTH(LocalDate.of(2026, 4, 3)),

  /** L'8 marzo 2027: "un anno fa", l'anniversario del viaggio (7-10 marzo 2026) e una persona. */
  ANNIVERSARIES(LocalDate.of(2027, 3, 8)),

  /** Il 10 maggio 2027: nessun anniversario e nessun mese, quindi nasce la persona ("Anna nel 2026"). */
  PEOPLE(LocalDate.of(2027, 5, 10)),
}

internal class RealMemoriesStack(val w: BuiltWorld, day: MemoriesDay) : AutoCloseable {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
  val visible = CatalogVisibleMedia(w.db, MutableStateFlow(w.snapshot))
  val mask = CatalogHiddenMask(w.db)

  /** La visibilita' del motore: i due contratti dei nascosti sul catalogo vero (quello che fara' `:app`, P13). */
  private val library = object : MemoryVisibility {
    override val snapshot: StateFlow<TimelineSnapshot?> get() = visible.snapshot

    override suspend fun visibleOrThrow(ids: Collection<MediaId>): Set<MediaId> = visible.visibleOrThrow(ids)

    override suspend fun hiddenOrThrow(ids: Collection<MediaId>): Set<MediaId> = mask.hiddenAmongOrThrow(ids)
  }

  val clock = FakeMemoryClock(day.today)

  /** Le persone (E-H10): una con un nome e molte foto, una **nascosta** con altre. Solo media visibili (la regola della porta vera). */
  val anna: PersonId = PersonId.fromBytes(ByteArray(16) { (it + 21).toByte() })
  val hiddenPerson: PersonId = PersonId.fromBytes(ByteArray(16) { (it + 77).toByte() })
  val people = FakeMemoryPeople(available = day != MemoriesDay.MONTH) { ids -> visible.visible(ids) }.apply {
    person(anna, "Anna", w.idsOf(w.spec.media.filter { it.day.dayOfMonth in 1..5 }.map { it.index }))
    person(hiddenPerson, "Bruno", w.idsOf(w.spec.media.filter { it.day.dayOfMonth in 6..9 }.map { it.index }))
    hidden += hiddenPerson
  }

  /** Un viaggio del 7-10 marzo con **tutti** i media di quei giorni, nascosti compresi: la porta vera non li darebbe, qui si prova che il resto li tolga lo stesso. */
  private val trips = FakeMemoryTrips(
    listOf(
      TripDigestView(
        TripKind.TRIP, DayRange(LocalDate.of(2026, 3, 7), LocalDate.of(2026, 3, 10)), "Viaggio di prova", listOf(3_000_100),
        w.idsOf(w.spec.media.filter { it.day.dayOfMonth in 7..10 }.map { it.index }).toList(), album = null, startQuarter = null,
      ),
    ),
  )

  /** I ricordi delle persone (P8) finche' non ci sono: il riferimento, solo il tipo `PERSON`. */
  private val personPlanner = object : MemoryPlanner {
    override fun plan(input: PlanInput, today: LocalDate): List<MemoryCandidate> = ReferencePlanner().plan(input, today).filter { it.key.kind == MemoryKind.PERSON }
  }

  val personal = InMemoryMemoriesPersonalSettings(MemoriesRegister())
  val store = InMemoryShelfStore()

  val engine = MemoryEngine(
    library = library, personal = personal, store = store, clock = clock, scope = scope,
    planner = IndexedPlanner(personPlanner), trips = trips, people = people,
    choice = MemoryPhotoChoice(PickSignalsLoader(people = people), VisiblePhotoPicker(MemoryPhotoPicker(), mask)),
    dispatcher = Dispatchers.Unconfined,
  )

  suspend fun refresh() = engine.refresh(RefreshReason.NIGHTLY, 60.seconds)

  override fun close() {
    scope.cancel()
  }
}
