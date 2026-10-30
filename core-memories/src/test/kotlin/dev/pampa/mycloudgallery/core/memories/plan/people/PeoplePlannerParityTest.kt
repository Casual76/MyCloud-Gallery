package dev.pampa.mycloudgallery.core.memories.plan.people

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKey
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.PersonWindow
import dev.pampa.mycloudgallery.core.memories.contract.PlanHistory
import dev.pampa.mycloudgallery.core.memories.contract.PlanInput
import dev.pampa.mycloudgallery.core.memories.plan.IndexedPlanner
import dev.pampa.mycloudgallery.core.memories.plan.shapeOf
import dev.pampa.mycloudgallery.core.memories.testing.ReferencePlanner
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesChoices
import java.time.LocalDate
import java.util.Random
import org.junit.Test

/**
 * Il generatore delle persone contro il riferimento a forza bruta (docs/fase9-progetto.md §5.2, P8): **stessi candidati, stesse chiavi, stesse
 * celle, stesse priorita'** su 2.000 giorni casuali (venti mondi, cento giorni ciascuno) con otto persone di forme diverse, scelte "Mostra meno"
 * e "Mai", periodi "Mai", chiavi viste o scartate, storia dello scaffale e "Ricordi con le persone" a volte spento. Provato anche dentro
 * `IndexedPlanner`, contro il riferimento intero: ordine e priorita' compresi.
 */
class PeoplePlannerParityTest {

  private val real = PeoplePlanner()
  private val reference = ReferencePlanner()

  @Test
  fun stessiCandidatiDelRiferimentoSu2000Giorni() {
    var compared = 0
    var years = 0
    var recents = 0
    var silent = 0
    for (seed in 1L..20L) {
      val world = PeopleWorld(seed)
      val random = Random(seed * 7919)
      for (today in world.days(random, 100)) {
        val subset = if (random.nextInt(3) == 0) world.recentOnly else world.digests
        val input = PlanInput(
          world.table, emptyList(), subset, peopleAvailable = random.nextInt(10) != 0,
          choices = world.choices(random, today), history = world.history(random, today),
        )
        val expected = reference.plan(input, today).filter { it.key.kind == MemoryKind.PERSON }.map { shapeOf(it) }
        val actual = real.plan(input, today)
        assertWithMessage("mondo $seed, giorno $today").that(actual.map { shapeOf(it) }).isEqualTo(expected)
        for (c in actual) {
          assertThat(c.key.kind).isEqualTo(MemoryKind.PERSON)
          when ((c.key as MemoryKey.Person).window) {
            is PersonWindow.Year -> years++
            PersonWindow.Recent -> recents++
          }
        }
        if (actual.isEmpty()) silent++
        compared++
      }
    }
    assertThat(compared).isEqualTo(2_000)
    // Il confronto non e' vuoto: tutte e due le finestre sono comparse, e molti giorni sono rimasti muti.
    assertWithMessage("anni=$years, recenti=$recents, muti=$silent").that(years).isAtLeast(150)
    assertThat(recents).isAtLeast(40)
    assertThat(silent).isAtLeast(200)
  }

  /** Dentro `IndexedPlanner` l'insieme (anni fa, viaggi, persone, mese) e l'ordine sono quelli del riferimento intero. */
  @Test
  fun dentroIndexedPlannerStessoRisultatoDelRiferimentoIntero() {
    val planner = IndexedPlanner(people = real)
    var compared = 0
    var withPerson = 0
    for (seed in 21L..30L) {
      val world = PeopleWorld(seed)
      val random = Random(seed * 104_729)
      for (today in world.days(random, 60)) {
        val input = PlanInput(
          world.table, world.world.trips, world.digests, peopleAvailable = true,
          choices = world.choices(random, today), history = world.history(random, today),
        )
        val expected = reference.plan(input, today).map { shapeOf(it) }
        val actual = planner.plan(input, today).map { shapeOf(it) }
        assertWithMessage("mondo $seed, giorno $today").that(actual).isEqualTo(expected)
        if (actual.any { it.person }) withPerson++
        compared++
      }
    }
    assertThat(compared).isEqualTo(600)
    assertThat(withPerson).isAtLeast(30)
  }

  /** Puro e deterministico: due corse sullo stesso ingresso danno lo stesso risultato, in qualunque ordine arrivino le persone. */
  @Test
  fun stessoIngressoStessoRisultatoInQualunqueOrdine() {
    val world = PeopleWorld(99)
    val today = LocalDate.of(2026, 11, 3)
    val input = PlanInput(world.table, emptyList(), world.digests, true, MemoriesChoices.NONE, PlanHistory())
    val first = real.plan(input, today).map { shapeOf(it) }
    assertThat(first).isNotEmpty()
    assertThat(real.plan(input, today).map { shapeOf(it) }).isEqualTo(first)
    val shuffled = PlanInput(world.table, emptyList(), world.digests.reversed(), true, MemoriesChoices.NONE, PlanHistory())
    assertThat(real.plan(shuffled, today).map { shapeOf(it) }).isEqualTo(first)
  }
}
