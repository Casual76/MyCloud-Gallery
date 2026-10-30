package dev.pampa.mycloudgallery.core.memories.plan

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.memories.contract.CellTable
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.memories.contract.MemoryIds
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKey
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.PlanHistory
import dev.pampa.mycloudgallery.core.memories.contract.PlanInput
import dev.pampa.mycloudgallery.core.memories.testing.ReferencePlanner
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesChoices
import dev.pampa.mycloudgallery.core.personal.contract.MemoryDays
import dev.pampa.mycloudgallery.core.personal.contract.MemoryLessLevel
import java.time.LocalDate
import java.util.Random
import org.junit.Test

/**
 * Il generatore vero contro il riferimento a forza bruta (docs/fase9-progetto.md §5.2): **stessi candidati, stesse chiavi, stesse celle, stesse
 * priorita'** su 2.000 giorni casuali (venti mondi sintetici, cento giorni ciascuno: giorni di anniversario, 28 e 29 febbraio, primi giorni del
 * mese, giorni a caso), con scelte dell'utente, storia dello scaffale, viaggi, persone e ritagli casuali. I ricordi di persone li da' lo stesso
 * innesto da entrambe le parti (il generatore di P8 non c'e' ancora): qui si prova che P6 li unisce e li ordina come il riferimento.
 */
class PlannerParityTest {

  private val real = IndexedPlanner(ReferencePeople)
  private val reference = ReferencePlanner()

  private fun days(world: RandomWorld, random: Random, count: Int): List<LocalDate> {
    val out = ArrayList<LocalDate>()
    repeat(count) {
      val roll = random.nextInt(100)
      out += when {
        roll < 40 -> {
          val (month, day) = world.anchors[random.nextInt(world.anchors.size)]
          val year = 2020 + random.nextInt(8)
          runCatching { LocalDate.of(year, month, day) }.getOrElse { LocalDate.of(year, month, 28) }
        }
        roll < 55 -> {
          val year = 2025 + random.nextInt(4)
          when (random.nextInt(4)) {
            0 -> LocalDate.of(year, 2, 27)
            1 -> LocalDate.of(year, 2, 28)
            2 -> if (java.time.Year.isLeap(year.toLong())) LocalDate.of(year, 2, 29) else LocalDate.of(year, 3, 1)
            else -> LocalDate.of(year, 3, 1)
          }
        }
        roll < 70 -> {
          val (year, month) = world.monthClusters[random.nextInt(world.monthClusters.size)]
          LocalDate.of(year, month, 1).plusMonths(1).plusDays(random.nextInt(9).toLong())
        }
        roll < 85 -> world.trips[random.nextInt(world.trips.size)].days.from.plusYears(1L + random.nextInt(4)).plusDays(random.nextInt(9) - 4L)
        else -> LocalDate.of(2026, 1, 1).plusDays(random.nextInt(700).toLong())
      }
    }
    return out
  }

  private fun choicesOf(world: RandomWorld, random: Random, today: LocalDate): MemoriesChoices {
    val seen = HashMap<String, Int>()
    val dismissed = HashSet<String>()
    for ((month, day) in world.anchors) if (random.nextInt(4) == 0) {
      val origin = runCatching { LocalDate.of(2020 + random.nextInt(6), month, day) }.getOrNull() ?: continue
      val id = MemoryIds.of(MemoryKey.OnThisDay(origin)).value
      if (random.nextBoolean()) seen[id] = MemoryDays.dayOf(today.minusDays(random.nextInt(700).toLong())) else dismissed += id
    }
    for (trip in world.trips) if (random.nextInt(5) == 0) {
      val id = MemoryIds.of(trip.key()).value
      if (random.nextBoolean()) seen[id] = MemoryDays.dayOf(today.minusDays(random.nextInt(700).toLong())) else dismissed += id
    }
    val never = HashSet<MemoryDays>()
    repeat(random.nextInt(3)) {
      val from = today.minusDays(random.nextInt(2000).toLong())
      never += MemoryDays.of(from, from.plusDays(random.nextInt(40).toLong()))
    }
    val people = HashMap<dev.pampa.mycloudgallery.core.model.PersonId, MemoryLessLevel>()
    for (person in world.personIds) if (random.nextInt(4) == 0) people[person] = MemoryLessLevel.entries[random.nextInt(2)]
    return MemoriesChoices(seen, dismissed, people, never, peopleMemories = random.nextInt(100) < 85)
  }

  private fun historyOf(world: RandomWorld, random: Random, today: LocalDate): PlanHistory {
    val onShelf = HashSet<MemoryId>()
    for ((month, day) in world.anchors) if (random.nextInt(6) == 0) {
      runCatching { LocalDate.of(2020 + random.nextInt(6), month, day) }.getOrNull()?.let { onShelf += MemoryIds.of(MemoryKey.OnThisDay(it)) }
    }
    for (trip in world.trips) if (random.nextInt(6) == 0) onShelf += MemoryIds.of(trip.key())
    val lastPublished = HashMap<MemoryKind, Int>()
    if (random.nextBoolean()) lastPublished[MemoryKind.PERSON] = MemoryDays.dayOf(today.minusDays(random.nextInt(14).toLong()))
    val lastRediscovery = if (random.nextBoolean()) MemoryDays.dayOf(today.minusDays(random.nextInt(14).toLong())) else null
    return PlanHistory(onShelf, lastPublished, lastRediscovery)
  }

  @Test
  fun stessiCandidatiDelRiferimentoSu2000Giorni() {
    val seen = HashMap<String, Int>()
    var compared = 0
    for (seed in 1L..20L) {
      val world = RandomWorld(seed)
      val random = Random(seed * 7919)
      val snapshot = world.lib.snapshot()
      for (today in days(world, random, 100)) {
        val table = CellTable.of(snapshot, world.cutouts)
        val input = PlanInput(
          table, world.trips, world.people, peopleAvailable = random.nextInt(10) != 0,
          choices = choicesOf(world, random, today), history = historyOf(world, random, today),
        )
        val expected = reference.plan(input, today).map { shapeOf(it) }
        val actual = real.plan(input, today).map { shapeOf(it) }
        assertWithMessage("mondo $seed, giorno $today").that(actual).isEqualTo(expected)
        for (c in actual) {
          val kind = if (c.rediscovery) "rediscovery" else c.key.substringBefore(':')
          seen[kind] = (seen[kind] ?: 0) + 1
        }
        compared++
      }
    }
    assertThat(compared).isEqualTo(2_000)
    // Il confronto non e' vuoto: ogni tipo di candidato e' comparso un buon numero di volte.
    for ((kind, atLeast) in mapOf("otd" to 300, "trip" to 40, "event" to 8, "month" to 20, "person" to 20, "rediscovery" to 10)) {
      assertWithMessage("candidati di tipo $kind (visti: $seen)").that(seen[kind] ?: 0).isAtLeast(atLeast)
    }
  }

  /** Puro e deterministico: due corse sullo stesso ingresso danno lo stesso risultato, e il generatore non cambia l'ingresso. */
  @Test
  fun stessoIngressoStessoRisultato() {
    val world = RandomWorld(99)
    val table = CellTable.of(world.lib.snapshot(), world.cutouts)
    val input = PlanInput(table, world.trips, world.people, true, MemoriesChoices.NONE, PlanHistory())
    for (day in listOf(LocalDate.of(2026, 11, 3), LocalDate.of(2026, 2, 28), LocalDate.of(2027, 3, 4))) {
      val first = real.plan(input, day).map { shapeOf(it) }
      assertThat(real.plan(input, day).map { shapeOf(it) }).isEqualTo(first)
      assertThat(IndexedPlanner(ReferencePeople).plan(input, day).map { shapeOf(it) }).isEqualTo(first)
    }
  }
}
