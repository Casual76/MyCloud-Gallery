package dev.pampa.mycloudgallery.core.memories.plan

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.memories.contract.DayRange
import dev.pampa.mycloudgallery.core.memories.contract.LessLevel
import dev.pampa.mycloudgallery.core.memories.contract.LessTarget
import dev.pampa.mycloudgallery.core.memories.contract.Memories
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.RefreshReason
import dev.pampa.mycloudgallery.core.memories.contract.RefreshReport
import dev.pampa.mycloudgallery.core.memories.pick.MemoryPhotoPicker
import dev.pampa.mycloudgallery.core.memories.pick.PickSignalsLoader
import dev.pampa.mycloudgallery.core.memories.pick.VisiblePhotoPicker
import dev.pampa.mycloudgallery.core.memories.testing.FakeMemoryClock
import dev.pampa.mycloudgallery.core.memories.testing.FakeMemoryPeople
import dev.pampa.mycloudgallery.core.memories.testing.FakeMemoryTrips
import dev.pampa.mycloudgallery.core.memories.testing.InMemoryMemories
import dev.pampa.mycloudgallery.core.memories.testing.InMemoryShelfStore
import dev.pampa.mycloudgallery.core.memories.testing.ReferencePicker
import dev.pampa.mycloudgallery.core.memories.testing.ReferencePlanner
import dev.pampa.mycloudgallery.core.personal.testing.InMemoryMemoriesPersonalSettings
import dev.pampa.mycloudgallery.core.personal.testing.MemoriesRegister
import java.time.LocalDate
import java.util.EnumMap
import java.util.Random
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Il motore vero contro il riferimento (`InMemoryMemories`; docs/fase9-progetto.md §5.2): su **500 storie sintetiche** (giorni che passano,
 * foto che arrivano dopo, nascosti, eliminati, scelte su due dispositivi, scaffale rovinato, scrittura che muore) lo scaffale del motore e'
 * **uguale** a quello del riferimento dopo ogni passo: stessi ricordi, stessi elementi, stessa copertina, stesso ordine, stessi conti.
 */
class MemoryEngineParityTest {

  /** Un dispositivo finto con la sua Libreria, uno scaffale in memoria e il servizio da provare; i due lati di una storia sono identici. */
  private class Side(seed: Long, start: LocalDate, make: (Side) -> Memories) {
    val world = RandomWorld(seed, years = 2022..2026, anchorsPerYear = 4, photosPerAnchor = 6..9, noise = 60, months = 1, tripCount = 4)
    val clock = FakeMemoryClock(start)
    val register = MemoriesRegister()
    val phone = InMemoryMemoriesPersonalSettings(register)
    val tablet = InMemoryMemoriesPersonalSettings(register)
    val store = InMemoryShelfStore()
    val trips = FakeMemoryTrips(world.trips)
    val people = FakeMemoryPeople(available = true) { ids -> world.lib.visibility.visible(ids) }.also { fake ->
      world.people.forEachIndexed { n, digest -> fake.person(digest.person, "Persona $n", digest.members) }
    }
    val service: Memories = make(this)
  }

  /** I ricordi sul file (uguali sui due lati): da qui si sceglie su quale ricordo agire, non dalla sezione (che nel riferimento si rifa' solo a certi passi). */
  private fun ids(side: Side) = side.store.peek()?.memories?.map { it.id }.orEmpty()

  /** Un passo di una storia: fa lo stesso su entrambi i lati. */
  private fun op(block: suspend (Side) -> Any?): suspend (Side) -> Any? = block

  private fun report(r: RefreshReport) = listOf(r.published, r.trimmed, r.removed, r.shelfReset, r.incomplete, r.dropped)

  private fun startOf(seed: Long, world: RandomWorld): LocalDate = when {
    seed % 3 == 0L -> world.monthClusters.first().let { (year, month) -> LocalDate.of(year, month, 1).plusMonths(1).plusDays(seed % 3 + seed % 2) }
    seed % 7 == 0L -> LocalDate.of(2027, 2, 27)
    else -> world.anchors[(seed % world.anchors.size).toInt()].let { (month, day) -> runCatching { LocalDate.of(2026, month, day) }.getOrElse { LocalDate.of(2026, month, 28) } }.minusDays(seed % 2)
  }

  private fun runStory(seed: Long, kinds: MutableMap<MemoryKind, Int>, p7: Boolean) {
    val scope = CoroutineScope(Dispatchers.Unconfined + Job())
    try {
      val reference = Side(seed, LocalDate.of(2026, 11, 2)) { side ->
        InMemoryMemories(
          visible = side.world.lib.visibility, mask = side.world.lib.visibility, personal = side.phone, store = side.store, clock = side.clock,
          planner = ReferencePlanner(), picker = ReferencePicker(), trips = side.trips, people = side.people,
        )
      }
      val engine = Side(seed, LocalDate.of(2026, 11, 2)) { side ->
        MemoryEngine(
          library = visibilityOf(side.world.lib), personal = side.phone, store = side.store, clock = side.clock, picker = ReferencePicker(), scope = scope,
          planner = IndexedPlanner(ReferencePeople), trips = side.trips, people = side.people, dispatcher = Dispatchers.Unconfined,
          // Con `p7` il passo 5 e' la scelta vera di P7: raccoglitore dei segnali + scelta con la maschera dei Nascosti.
          choice = if (p7) MemoryPhotoChoice(PickSignalsLoader(people = side.people), VisiblePhotoPicker(MemoryPhotoPicker(), side.world.lib.visibility)) else null,
        )
      }
      val start = startOf(seed, reference.world)
      reference.clock.today = start
      engine.clock.today = start
      val random = Random(seed * 31 + 7)
      var dirty = false
      val trace = ArrayList<String>()
      repeat(16) { step ->
        val roll = random.nextInt(100)
        val pick = random.nextInt(1_000_000)
        val what: String
        val action: suspend (Side) -> Any? = when {
          roll < 24 -> {
            what = "refresh"
            dirty = false
            op { side -> side.service.refresh(RefreshReason.NIGHTLY, 60.seconds) }
          }
          roll < 36 -> {
            what = "avanza"
            val days = 1L + random.nextInt(2)
            op { side -> side.clock.today = side.clock.today.plusDays(days) }
          }
          roll < 44 -> {
            what = "arrivi"
            val day = reference.clock.today.minusYears(1L + random.nextInt(3))
            val count = 1 + random.nextInt(4)
            op { side -> side.world.lib.photos(day, count, start = java.time.LocalTime.of(21, 0)) }
          }
          roll < 52 -> {
            what = "nascondi"
            dirty = true
            op { side -> side.world.allMedia().let { all -> side.world.lib.hide(all[pick % all.size]) } }
          }
          roll < 56 -> {
            what = "mostra"
            op { side -> side.world.allMedia().let { all -> side.world.lib.unhide(all[pick % all.size]) } }
          }
          roll < 60 -> {
            what = "elimina"
            dirty = true
            op { side -> side.world.allMedia().let { all -> side.world.lib.delete(all[pick % all.size]) } }
          }
          roll < 66 -> {
            what = "prune"
            dirty = false
            op { side -> side.service.prune() }
          }
          roll < 72 -> {
            what = "visto"
            op { side -> ids(side).let { ids -> if (ids.isNotEmpty()) side.service.markSeen(ids[pick % ids.size]) } }
          }
          roll < 78 -> {
            what = "scarta"
            val undo = random.nextInt(3) == 0
            op { side -> ids(side).let { ids -> if (ids.isNotEmpty()) side.service.dismiss(ids[pick % ids.size], !undo) } }
          }
          roll < 82 -> {
            what = "tablet"
            op { side ->
              ids(side).let { ids ->
                if (ids.isNotEmpty()) {
                  if (pick % 2 == 0) side.tablet.markSeen(ids[pick % ids.size].value, 20270101) else side.tablet.setDismissed(ids[pick % ids.size].value, true)
                }
              }
              side.phone.pull()
            }
          }
          roll < 87 -> {
            what = "mai-periodo"
            dirty = false
            val from = reference.clock.today.minusYears(1L + random.nextInt(3)).minusDays(random.nextInt(3).toLong())
            val range = DayRange(from, from.plusDays(random.nextInt(4).toLong()))
            val level = if (random.nextInt(4) == 0) null else LessLevel.NEVER
            op { side -> side.service.setLess(LessTarget.Days(range), level) }
          }
          roll < 91 -> {
            what = "persona"
            dirty = false
            val level = listOf(LessLevel.LESS, LessLevel.NEVER, null)[random.nextInt(3)]
            op { side -> side.service.setLess(LessTarget.Person(side.world.personIds[pick % side.world.personIds.size]), level) }
          }
          roll < 93 -> {
            what = "persone-spente"
            dirty = false
            val on = random.nextBoolean()
            op { side -> side.service.setPeopleMemories(on) }
          }
          roll < 95 -> {
            what = "persone-assenti"
            val on = random.nextBoolean()
            op { side -> side.people.available.value = on }
          }
          roll < 97 -> {
            what = "rovina"
            op { side -> side.store.corrupt() }
          }
          else -> {
            what = "muore"
            op { side -> side.store.dieDuringNextWrite() }
          }
        }
        trace += what
        val results = listOf(reference, engine).map { side -> runCatching { runBlocking { action(side) } } }
        assertWithMessage("storia $seed, passo $step ($what): uno dei due ha fallito e l'altro no")
          .that(results[0].isFailure).isEqualTo(results[1].isFailure)
        if (what == "refresh" && results[0].isSuccess) {
          assertWithMessage("storia $seed, passo $step: rapporto del giro").that(report(results[1].getOrThrow() as RefreshReport))
            .isEqualTo(report(results[0].getOrThrow() as RefreshReport))
        }
        assertWithMessage("storia $seed, passo $step ($what): scaffale su file [${trace.joinToString(",")}]").that(engine.store.peek()).isEqualTo(reference.store.peek())
        // Un passo fallito (una scrittura che muore) lascia le scelte cambiate ma non rifa' la sezione del riferimento (che si rifa' solo a fine passo): niente confronto.
        val settled = results[0].isSuccess && !dirty && what !in setOf("rovina", "muore", "persone-assenti", "arrivi", "avanza", "nascondi", "mostra", "elimina", "tablet")
        // Prima del primo giro (nessuno scaffale, o uno vuoto scritto per sostituire un file rovinato) il motore dice "sto preparando" dove il riferimento dice
        // "vuoto": e' voluto (ShelfCards); da quando un giro e' finito i due si somigliano in tutto.
        if (settled && (reference.store.peek()?.lastRunDay ?: 0) != 0) {
          val detail = trace.joinToString(",") + " | file=" + engine.store.peek()?.memories?.joinToString { m ->
            val snap = engine.world.lib.snapshot()
            "${m.kind.code}:${m.members.size}/${m.members.count { snap.indexOf(it) >= 0 }}"
          } + " | mia=" + engine.service.shelf.value.items.joinToString { "${it.kind.code}:${it.count}:${it.isNew}" } +
            " | rif=" + reference.service.shelf.value.items.joinToString { "${it.kind.code}:${it.count}:${it.isNew}" }
          assertWithMessage("storia $seed, passo $step ($what): sezione Ricordi [$detail]").that(engine.service.shelf.value).isEqualTo(reference.service.shelf.value)
          assertWithMessage("storia $seed, passo $step ($what): scelte").that(engine.service.lessChoices.value).isEqualTo(reference.service.lessChoices.value)
          assertThat(engine.service.peopleMemories.value).isEqualTo(reference.service.peopleMemories.value)
        }
        engine.store.peek()?.memories?.forEach { kinds[it.kind] = (kinds[it.kind] ?: 0) + 1 }
      }
    } finally {
      scope.cancel()
    }
  }

  @Test
  fun lo_scaffale_del_motore_e_uguale_a_quello_del_riferimento_su_500_storie() {
    val kinds = EnumMap<MemoryKind, Int>(MemoryKind::class.java)
    for (seed in 1L..500L) runStory(seed, kinds, p7 = false)
    // Il confronto non e' vuoto: ogni tipo di ricordo e' passato dallo scaffale molte volte.
    for (kind in MemoryKind.entries) assertWithMessage("ricordi di tipo $kind (visti: $kinds)").that(kinds[kind] ?: 0).isAtLeast(30)
  }

  /** Le stesse 500 storie con la scelta vera di P7 (`PickSignalsLoader` + `VisiblePhotoPicker` sopra `MemoryPhotoPicker`) al posto del riferimento. */
  @Test
  fun lo_stesso_con_la_scelta_di_P7_su_500_storie() {
    val kinds = EnumMap<MemoryKind, Int>(MemoryKind::class.java)
    for (seed in 1L..500L) runStory(seed, kinds, p7 = true)
    for (kind in MemoryKind.entries) assertWithMessage("ricordi di tipo $kind (visti: $kinds)").that(kinds[kind] ?: 0).isAtLeast(30)
  }
}
