package dev.pampa.mycloudgallery.core.memories.plan.people

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.memories.contract.DayRange
import dev.pampa.mycloudgallery.core.memories.contract.LessLevel
import dev.pampa.mycloudgallery.core.memories.contract.LessTarget
import dev.pampa.mycloudgallery.core.memories.contract.Memory
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.ShelfFile
import dev.pampa.mycloudgallery.core.memories.settings.less.PeoplePhase
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesChoices
import dev.pampa.mycloudgallery.core.personal.contract.MemoryDays
import dev.pampa.mycloudgallery.core.personal.contract.MemoryLessLevel
import java.time.LocalDate
import java.util.Random
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * "Mostra meno" e "Mai" con l'effetto di D11, in proprieta' (docs/fase9-progetto.md D11, D12, D31, E-MEM10, E-MEM11, E-MEM12, P8): sul **motore vero**,
 * con il generatore delle persone, la scelta delle foto di P7, le scelte su un registro, il janitor e persone finte che si nascondono, si uniscono,
 * si spengono e si accendono. Su sessanta storie casuali di ricordi che passano, foto nascoste, scelte che vanno e vengono, **dopo ogni passo**
 * lo scaffale in memoria rispetta tutto insieme:
 *  - mai una foto di una persona "Mai", mai una foto di un giorno "Mai" (E-MEM12) e mai un nascosto, ne' fra gli elementi ne' come copertina (D31);
 *  - mai un ricordo `PERSON` di una persona con "Mostra meno" o "Mai", ne' del suo alias, ne' di una nascosta o dimenticata, ne' con Persone spenta
 *    o non disponibile, ne' con "Ricordi con le persone" spento (E-MEM10);
 *  - mai come copertina una foto di una persona con "Mostra meno" o "Mai", a meno che non ne resti nessun'altra (E-MEM11).
 * Una Libreria sintetica, nessun dato vero.
 */
class PeopleEffectsPropertyTest {

  private class Stats {
    var checks = 0
    var personMemories = 0
    var memories = 0
    var lessCovers = 0
    var neverPhotos = 0
    var neverDaysPhotos = 0
    var gone = 0
  }

  private val stats = Stats()

  /** Determinismo: la stessa Libreria, le stesse persone, le stesse scelte e gli stessi giorni danno lo stesso scaffale. */
  @Test
  fun stessaStoriaStessoScaffale() = runBlocking<Unit> {
    for (seed in 101L..112L) {
      val first = story(seed)
      val second = story(seed)
      assertWithMessage("storia $seed").that(second?.memories).isEqualTo(first?.memories)
      assertThat(second?.lastPublished).isEqualTo(first?.lastPublished)
    }
  }

  @Test
  fun leSceltaDiD11ValgonoSempreSuRicordiECopertine() = runBlocking<Unit> {
    for (seed in 1L..60L) story(seed)
    assertWithMessage("controlli=${stats.checks}, ricordi=${stats.memories}, persone=${stats.personMemories}").that(stats.personMemories).isAtLeast(40)
    assertThat(stats.memories).isAtLeast(300)
    // Il confronto non e' vuoto: le scelte hanno avuto davvero qualcosa da togliere.
    assertThat(stats.lessCovers).isAtLeast(20)
    assertThat(stats.neverPhotos).isAtLeast(100)
    assertThat(stats.neverDaysPhotos).isAtLeast(50)
    assertThat(stats.gone).isAtLeast(5)
  }

  /** Una storia intera dal seme: torna lo scaffale finale (per il confronto fra due corse). */
  private suspend fun story(seed: Long): ShelfFile? {
    val random = Random(seed * 6151)
    val rig = PeopleRig()
    try {
      val persons = ArrayList<PersonId>()
      val media = HashMap<PersonId, MutableList<MediaId>>()
      repeat(5) { i -> persons += PersonId.fromBytes(ByteArray(16) { b -> (seed * 7 + i * 13 + b + 1).toByte() }) }
      rig.lib.batch {
        for (year in 2022..2025) {
          // Giorni "anni fa" del 3 novembre e giorni sparsi: i ricordi nascono ogni giorno del giro.
          val days = listOf(LocalDate.of(year, 11, 3)) + List(7) { LocalDate.of(year, 1 + random.nextInt(12), 1 + random.nextInt(28)) }
          for (day in days) for (id in rig.lib.photos(day, 7 + random.nextInt(5), place = 3_000_000 + year)) {
            // Una o due persone per foto, a volte nessuna: i gruppi mescolano le scelte.
            for (p in persons) if (random.nextInt(100) < 38) media.getOrPut(p) { ArrayList() } += id
          }
        }
      }
      persons.forEachIndexed { i, p -> rig.people.person(p, "Nome di prova $i", media[p].orEmpty()) }
      rig.refresh()
      repeat(26) {
        when (random.nextInt(100)) {
          in 0..44 -> {
            rig.clock.today = rig.clock.today.plusDays(1L + random.nextInt(9))
            rig.refresh()
          }
          in 45..64 -> {
            val p = persons[random.nextInt(persons.size)]
            val level = when (random.nextInt(4)) { 0 -> null; 1 -> LessLevel.NEVER; else -> LessLevel.LESS }
            // Le foto di questa persona che il ricordo aveva: "Mai" deve levarle (il confronto non e' vuoto).
            if (level == LessLevel.NEVER) stats.neverPhotos += rig.engine.current()?.memories.orEmpty().sumOf { m -> m.members.count { it in media[p].orEmpty() } }
            rig.engine.setLess(LessTarget.Person(p), level)
          }
          in 65..71 -> {
            val from = LocalDate.of(2022 + random.nextInt(4), 1 + random.nextInt(12), 1)
            val range = DayRange(from, from.plusDays(10L + random.nextInt(60)))
            stats.neverDaysPhotos += rig.engine.current()?.memories.orEmpty().sumOf { m -> m.members.count { id -> rig.lib.shot(id).day in range } }
            rig.engine.setLess(LessTarget.Days(range), if (random.nextInt(5) == 0) null else LessLevel.NEVER)
          }
          in 72..78 -> {
            val all = rig.lib.all.map { it.id }.filter { it !in rig.lib.visibility.hidden }
            if (all.isNotEmpty()) rig.lib.hide(all[random.nextInt(all.size)])
            rig.engine.prune()
          }
          in 79..84 -> {
            rig.people.hide(persons[random.nextInt(persons.size)])
            rig.janitor.sweep()
          }
          in 85..90 -> {
            val old = persons[random.nextInt(persons.size)]
            val into = persons[random.nextInt(persons.size)]
            if (old != into && rig.people.aliases[into] != old) rig.people.merge(old, into)
            rig.janitor.sweep()
          }
          in 91..94 -> {
            rig.people.available.value = !rig.people.available.value
            rig.janitor.sweep()
          }
          in 95..96 -> {
            rig.engine.setPeopleMemories(random.nextBoolean())
          }
          else -> {
            val phase = if (random.nextBoolean()) PeoplePhase.OFF else PeoplePhase.ERASING
            rig.switch.set(phase)
            rig.people.available.value = false
            rig.janitor.sweep()
            stats.gone++
            // Persone torna: spenta e riaccesa dall'utente.
            rig.switch.set(PeoplePhase.ON)
            rig.people.available.value = true
            rig.refresh()
          }
        }
        check(rig, persons, seed)
      }
      return rig.engine.current()
    } finally {
      rig.close()
    }
  }

  private suspend fun check(rig: PeopleRig, persons: List<PersonId>, seed: Long) {
    val shelf = rig.engine.current() ?: return
    val choices = rig.raw.current()
    val hidden = rig.lib.visibility.hidden
    val available = rig.people.available.value && !rig.switch.phase.value.gone
    val members = shelf.memories.flatMap { it.members }.distinct()
    val peopleOf = rig.people.peopleIn(members)
    stats.checks++
    for (memory in shelf.memories) {
      stats.memories++
      val where = "storia $seed, ${memory.kind.code}"
      // D31: mai un nascosto.
      assertWithMessage(where).that(memory.members.any { it in hidden }).isFalse()
      assertWithMessage(where).that(memory.cover in hidden).isFalse()
      // E-MEM12: mai un giorno "Mai".
      for (id in memory.members) {
        val day = MemoryDays.dayOf(rig.lib.shot(id).day)
        assertWithMessage(where).that(choices.neverOn(day)).isFalse()
      }
      // D11: mai una foto di una persona "Mai" (dove Persone e' disponibile: altrimenti non si sa chi c'e', L274).
      if (available) for (id in memory.members) {
        val of = peopleOf[id].orEmpty()
        assertWithMessage(where).that(of.any { choices.people[it] == MemoryLessLevel.NEVER }).isFalse()
      }
      // E-MEM11: mai in copertina una persona con "Mostra meno" o "Mai", a meno che non ne resti nessun'altra.
      if (available) {
        fun less(id: MediaId) = peopleOf[id].orEmpty().any { choices.people[it] != null }
        if (less(memory.cover)) {
          assertWithMessage("$where: copertina di una persona con \"Mostra meno\"").that(memory.members.all { less(it) }).isTrue()
          stats.lessCovers++
        } else if (memory.members.any { less(it) }) {
          stats.lessCovers++
        }
      }
      if (memory.kind == MemoryKind.PERSON) personRules(memory, rig, choices, available, where)
      assertThat(memory.id.value in choices.dismissed).isFalse()
    }
    // La sezione: nessun nascosto nelle copertine delle voci.
    for (card in rig.engine.shelf.value.items) assertThat(card.cover in hidden).isFalse()
  }

  private suspend fun personRules(memory: Memory, rig: PeopleRig, choices: MemoriesChoices, available: Boolean, where: String) {
    stats.personMemories++
    assertWithMessage("$where: ricordo di persona con Persone spenta o non disponibile").that(available).isTrue()
    assertWithMessage("$where: Ricordi con le persone spento").that(choices.peopleMemories).isTrue()
    val person = memory.title.person!!
    val resolved = rig.people.resolve(person)
    assertWithMessage("$where: persona nascosta o dimenticata").that(resolved).isNotNull()
    assertWithMessage("$where: Mostra meno o Mai").that(choices.people[person]).isNull()
    assertWithMessage("$where: Mostra meno o Mai sull'alias").that(choices.people[resolved!!]).isNull()
  }
}
