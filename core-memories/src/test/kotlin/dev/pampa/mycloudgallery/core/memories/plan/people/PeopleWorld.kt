package dev.pampa.mycloudgallery.core.memories.plan.people

import dev.pampa.mycloudgallery.core.memories.contract.CellTable
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.memories.contract.MemoryIds
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKey
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.PersonDigest
import dev.pampa.mycloudgallery.core.memories.contract.PersonWindow
import dev.pampa.mycloudgallery.core.memories.contract.PlanHistory
import dev.pampa.mycloudgallery.core.memories.plan.RandomWorld
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesChoices
import dev.pampa.mycloudgallery.core.personal.contract.MemoryDays
import dev.pampa.mycloudgallery.core.personal.contract.MemoryLessLevel
import java.time.LocalDate
import java.util.Random
import java.util.UUID

/**
 * Un mondo casuale e sintetico per le persone (P8, docs/fase9-progetto.md §5.2): la Libreria di [RandomWorld] con otto persone di forme
 * diverse: una fotografatissima, una concentrata in un anno passato, una dei mesi pieni (la finestra "negli ultimi mesi"), due piccole, una con
 * tutto in due giorni, una con media che non ci sono piu' e doppioni, una media. Stesso seme, stesso mondo. Nessun dato vero: nessun nome (i
 * nomi di prova stanno nelle prove che li cercano), date e id inventati.
 */
internal class PeopleWorld(val seed: Long) {

  val world = RandomWorld(seed)
  val table: CellTable = CellTable.of(world.lib.snapshot(), world.cutouts)
  val persons: List<PersonId> = List(PERSONS) { PersonId.fromBytes(ByteArray(16) { b -> (seed * 17 + it * 29 + b + 1).toByte() }) }
  val digests: List<PersonDigest>

  /** Le persone che non hanno un anno passato abbastanza ricco: da sole arrivano alla finestra "negli ultimi mesi". */
  val recentOnly: List<PersonDigest>

  private val random = Random(seed * 104_729)

  init {
    val all = world.allMedia()
    fun dateOf(id: MediaId): LocalDate? = table.indexOf(id).takeIf { it >= 0 }?.let { table.dateAt(it) }
    val list = ArrayList<PersonDigest>()
    list += PersonDigest(persons[0], all.filter { random.nextInt(100) < 55 })
    val busyYear = 2020 + random.nextInt(5)
    list += PersonDigest(persons[1], all.filter { dateOf(it)?.year == busyYear && random.nextInt(100) < 70 } + all.take(4))
    val months = world.monthClusters.toSet()
    list += PersonDigest(persons[2], all.filter { id -> dateOf(id)?.let { (it.year to it.monthValue) in months } == true })
    list += PersonDigest(persons[3], all.shuffled(random).take(5 + random.nextInt(10)))
    list += PersonDigest(persons[4], twoDays(all, ::dateOf))
    val stale = all.shuffled(random).take(60) + List(8) { MediaId(UUID(random.nextLong(), random.nextLong()).toString()) }
    list += PersonDigest(persons[5], stale + stale.take(15))
    list += PersonDigest(persons[6], all.filter { random.nextInt(100) < 25 })
    list += PersonDigest(persons[7], all.filter { random.nextInt(100) < 35 })
    digests = list.shuffled(random)
    recentOnly = digests.filter { d -> d.person in persons.slice(2..5) }
  }

  /** Venticinque media di due soli giorni: abbastanza foto, ma non abbastanza giorni (D3). */
  private fun twoDays(all: List<MediaId>, dateOf: (MediaId) -> LocalDate?): List<MediaId> {
    val byDay = all.groupBy { dateOf(it) }.filterKeys { it != null }.entries.sortedByDescending { it.value.size }
    return byDay.take(2).flatMap { it.value }.take(25)
  }

  /** Le scelte di un giorno qualunque: "Mostra meno" e "Mai" su qualche persona, periodi "Mai", chiavi di persona viste o scartate. */
  fun choices(random: Random, today: LocalDate): MemoriesChoices {
    val people = HashMap<PersonId, MemoryLessLevel>()
    for (person in persons) if (random.nextInt(100) < 18) people[person] = MemoryLessLevel.entries[random.nextInt(2)]
    val never = HashSet<MemoryDays>()
    repeat(random.nextInt(3)) {
      val from = today.minusDays(random.nextInt(2000).toLong())
      never += MemoryDays.of(from, from.plusDays(random.nextInt(60).toLong()))
    }
    val seen = HashMap<String, Int>()
    val dismissed = HashSet<String>()
    for (person in persons) for (year in 2019..2026) if (random.nextInt(100) < 12) {
      val id = MemoryIds.of(MemoryKey.Person(person, PersonWindow.Year(year))).value
      if (random.nextBoolean()) seen[id] = MemoryDays.dayOf(today.minusDays(random.nextInt(700).toLong())) else dismissed += id
    }
    for (person in persons) if (random.nextInt(100) < 10) {
      val id = MemoryIds.of(MemoryKey.Person(person, PersonWindow.Recent)).value
      if (random.nextBoolean()) seen[id] = MemoryDays.dayOf(today.minusDays(random.nextInt(400).toLong())) else dismissed += id
    }
    return MemoriesChoices(seen, dismissed, people, never, peopleMemories = random.nextInt(100) < 92)
  }

  fun history(random: Random, today: LocalDate): PlanHistory {
    val onShelf = HashSet<MemoryId>()
    for (person in persons) for (year in 2019..2026) if (random.nextInt(100) < 6) onShelf += MemoryIds.of(MemoryKey.Person(person, PersonWindow.Year(year)))
    val last = HashMap<MemoryKind, Int>()
    if (random.nextInt(100) < 45) last[MemoryKind.PERSON] = MemoryDays.dayOf(today.minusDays(random.nextInt(14).toLong()))
    return PlanHistory(onShelf, last, null)
  }

  /** I giorni da provare: dopo i mesi pieni (finestra recente), l'anno intero, i primi del mese, a caso. */
  fun days(random: Random, count: Int): List<LocalDate> {
    val out = ArrayList<LocalDate>()
    repeat(count) {
      out += when (random.nextInt(4)) {
        0 -> world.monthClusters[random.nextInt(world.monthClusters.size)].let { (y, m) -> LocalDate.of(y, m, 1).plusMonths(1).plusDays(random.nextInt(120).toLong()) }
        1 -> LocalDate.of(2026, 1, 1).plusDays(random.nextInt(400).toLong())
        2 -> LocalDate.of(2027, 1, 1).plusDays(random.nextInt(60).toLong())
        else -> LocalDate.of(2024, 1, 1).plusDays(random.nextInt(1_200).toLong())
      }
    }
    return out
  }

  private companion object {
    const val PERSONS = 8
  }
}
