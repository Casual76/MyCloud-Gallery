package dev.pampa.mycloudgallery.core.memories.eval

import dev.pampa.mycloudgallery.core.memories.contract.Memories
import dev.pampa.mycloudgallery.core.memories.contract.Memory
import dev.pampa.mycloudgallery.core.memories.contract.PixelProbe
import dev.pampa.mycloudgallery.core.memories.contract.RefreshReason
import dev.pampa.mycloudgallery.core.memories.noop.NoMemoryAlbumSignals
import dev.pampa.mycloudgallery.core.memories.pick.MemoryPhotoPicker
import dev.pampa.mycloudgallery.core.memories.pick.PickSignalsLoader
import dev.pampa.mycloudgallery.core.memories.pick.VisiblePhotoPicker
import dev.pampa.mycloudgallery.core.memories.plan.IndexedPlanner
import dev.pampa.mycloudgallery.core.memories.plan.MemoryEngine
import dev.pampa.mycloudgallery.core.memories.plan.MemoryPhotoChoice
import dev.pampa.mycloudgallery.core.memories.plan.people.PeoplePlanner
import dev.pampa.mycloudgallery.core.memories.plan.visibilityOf
import dev.pampa.mycloudgallery.core.memories.testing.FakeMemoryClock
import dev.pampa.mycloudgallery.core.memories.testing.FakeMemoryPeople
import dev.pampa.mycloudgallery.core.memories.testing.FakeMemoryPixels
import dev.pampa.mycloudgallery.core.memories.testing.FakeMemoryTrips
import dev.pampa.mycloudgallery.core.memories.testing.FakeMemoryVectors
import dev.pampa.mycloudgallery.core.memories.testing.InMemoryMemories
import dev.pampa.mycloudgallery.core.memories.testing.InMemoryShelfStore
import dev.pampa.mycloudgallery.core.memories.testing.ReferencePicker
import dev.pampa.mycloudgallery.core.memories.testing.ReferencePlanner
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.personal.testing.InMemoryMemoriesPersonalSettings
import dev.pampa.mycloudgallery.core.personal.testing.MemoriesRegister
import java.time.LocalDate
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first

// IL BANCO DELLA VALUTAZIONE (docs/fase9-progetto.md §5.3, riga P11 del §4.4): il mondo finto della famiglia dietro le porte del motore, i due esecutori
// (il riferimento senza ottimizzazioni e il motore vero con la scelta di P7), la corsa "come se oggi fosse ognuno degli ultimi 45 giorni" e l'utente
// ideale che compila i giudizi. Solo test: in `:app` la corsa la lega P13 sopra il grafo vero (vedi `docs/decisioni.md`, "Fase 9 P11").

/** Chi genera i ricordi: il riferimento (`InMemoryMemories`) o il motore vero (`MemoryEngine`, scelta di P7 e maschera dei Nascosti). */
internal enum class Executor { REFERENCE, REAL }

/**
 * Un dispositivo finto sopra il mondo di una famiglia: scaffale, scelte e orologio **suoi** (in memoria: la corsa non scrive niente), le porte con i dati
 * del mondo. [sharpnessScale] riporta le misure di nitidezza a un'altra soglia senza toccare il codice: una foto e' "mossa" se `nitidezza < 100`
 * ([PickWeights.SHARPNESS_MIN]), quindi cambiare la soglia a T equivale a moltiplicare le misure per 100 / T (e' esatto, il punteggio guarda solo quel confronto).
 */
internal class FamilyRig(val world: FamilyWorld, executor: Executor, sharpnessScale: Double = 1.0) : AutoCloseable {
  private val scope = CoroutineScope(Dispatchers.Unconfined + Job())
  val clock = FakeMemoryClock(world.today)
  val store = InMemoryShelfStore()
  private val personal = InMemoryMemoriesPersonalSettings(MemoriesRegister())
  private val visibility = world.library.visibility
  private val trips = FakeMemoryTrips(world.trips)
  private val vectors = FakeMemoryVectors(world.vectors.toMutableMap())
  private val pixels = FakeMemoryPixels(
    world.probes.mapValues { (_, p) -> PixelProbe((p.sharpness * sharpnessScale).toFloat(), p.luma) }.toMutableMap(),
  )
  private val people = FakeMemoryPeople(available = true) { ids -> visibility.visible(ids) }.also { fake ->
    for ((id, media) in world.personMedia) fake.person(id, world.personName(id), media)
  }

  val memories: Memories = when (executor) {
    Executor.REFERENCE -> InMemoryMemories(
      visible = visibility, mask = visibility, personal = personal, store = store, clock = clock,
      planner = ReferencePlanner(), picker = ReferencePicker(), trips = trips, people = people, vectors = vectors, pixels = pixels,
    )
    Executor.REAL -> MemoryEngine(
      library = visibilityOf(world.library), personal = personal, store = store, clock = clock, scope = scope,
      planner = IndexedPlanner(PeoplePlanner()), trips = trips, people = people, dispatcher = Dispatchers.Unconfined,
      choice = MemoryPhotoChoice(
        PickSignalsLoader(vectors = vectors, pixels = pixels, albums = NoMemoryAlbumSignals, people = people),
        VisiblePhotoPicker(MemoryPhotoPicker(), visibility),
      ),
    )
  }

  override fun close() = scope.cancel()
}

/**
 * La corsa dello strumento: per ognuno degli ultimi [days] giorni, in ordine, "oggi" e' quel giorno e si fa un giro di notte; ogni ricordo **nuovo** sullo
 * scaffale dopo il giro e' un ricordo da giudicare, nato in quel giorno. Lo scaffale e le scelte sono in memoria e partono vuoti: niente si scrive. E' la
 * definizione che l'adattatore di P13 deve seguire sul grafo vero (con le scelte vere dell'utente copiate, in sola lettura, nelle scelte in memoria).
 */
internal suspend fun FamilyRig.run(days: Int = MemoriesEvalDays.WINDOW, describe: (Memory) -> String = ::describeNeutral): List<EvalMemory> {
  val seen = HashSet<String>()
  val out = ArrayList<EvalMemory>()
  for (day in MemoriesEvalDays.window(world.today, days)) {
    clock.today = day
    memories.refresh(RefreshReason.NIGHTLY, 60.seconds)
    for (card in memories.shelf.value.items) {
      if (!seen.add(card.id.value)) continue
      val memory = memories.memory(card.id).first()?.memory ?: continue
      out += EvalMemory(memory.id.value, memory.kind.code, day, memory.cover.value, memory.members.map { it.value }, describe(memory))
    }
  }
  clock.today = world.today
  return out
}

/** Il titolo del banco: il tipo e quanti giorni copre (mai date o luoghi: il banco non ne ha di veri). */
internal fun describeNeutral(memory: Memory): String = "${MemoriesEvalKinds.label(memory.kind.code)}, ${memory.title.days.days} giorni"

/** L'utente ideale: sa la verita' di ogni foto (il motore no) e compila i giudizi come li compilerebbe una persona che guarda ogni ricordo con calma. */
internal object FamilyIdealUser {
  /** Fino a una foto da togliere ogni sei il ricordo e' "Bello", fino a una su tre "Cosi' cosi'", oltre e' "Da non mostrare" (sbagliato). */
  const val GOOD_MAX_FAULT_SHARE: Double = 1.0 / 6.0
  const val SO_SO_MAX_FAULT_SHARE: Double = 1.0 / 3.0

  /** Le foto da togliere di un ricordo: ogni doppione tranne il primo scatto del gruppo, poi il difetto proprio della foto. */
  fun faults(world: FamilyWorld, memory: EvalMemory): Map<String, EvalFault> {
    val shots = memory.members.map { world.shots.getValue(MediaId(it)) }
    val keeper = shots.filter { it.dupGroup != null }.groupBy { it.dupGroup }
      .mapValues { (_, group) -> group.minWithOrNull(compareBy<FamilyShot> { it.day }.thenBy { it.secondOfDay }.thenBy { it.id.value })!! }
    val out = LinkedHashMap<String, EvalFault>()
    for (shot in shots) {
      val duplicate = shot.dupGroup?.let { keeper.getValue(it) !== shot } ?: false
      val fault = if (duplicate) EvalFault.DUPLICATE else shot.fault
      if (fault != null) out[shot.id.value] = fault
    }
    return out
  }

  fun judgement(world: FamilyWorld, memory: EvalMemory, faults: Map<String, EvalFault>): EvalJudgement {
    val sad = memory.members.count { world.shots.getValue(MediaId(it)).fault == EvalFault.SAD }
    val coverSad = world.shots.getValue(MediaId(memory.cover)).fault == EvalFault.SAD
    val share = faults.size.toDouble() / memory.members.size
    return when {
      coverSad || sad.toDouble() / memory.members.size >= SAD_SHARE -> EvalJudgement(EvalVerdict.DONT_SHOW, EvalWhy.SAD)
      share <= GOOD_MAX_FAULT_SHARE -> EvalJudgement(EvalVerdict.GOOD)
      share <= SO_SO_MAX_FAULT_SHARE -> EvalJudgement(EvalVerdict.SO_SO)
      else -> EvalJudgement(EvalVerdict.DONT_SHOW, EvalWhy.WRONG)
    }
  }

  /** Tutti i ricordi della corsa giudicati e le foto da togliere segnate. */
  fun label(world: FamilyWorld, run: List<EvalMemory>): MemoriesLabelSet {
    var labels = MemoriesLabelSet().withRun(run)
    for (memory in run) {
      val faults = faults(world, memory)
      for ((media, fault) in faults) labels = labels.withFault(memory.id, media, fault)
      val j = judgement(world, memory, faults)
      labels = labels.withJudgement(memory.id, j.verdict, j.why)
    }
    return labels
  }
}

/** Oggi nei test: fissato, per i numeri attesi (gli anniversari sono relativi e valgono per qualunque altro giorno). */
internal val EVAL_TODAY: LocalDate = LocalDate.of(2026, 10, 30)
