package dev.pampa.mycloudgallery.core.memories.contract

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.memories.testing.FakeMemoryPeople
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * La suite di contratto della porta delle persone per i ricordi (Fase 9, P0; `docs/fase9-progetto.md` D12, E-MEM10, E-H10, §5.2). Gira sul fake
 * (`FakeMemoryPeople`) e, da P8 (dopo P13 della Fase 8), sull'adattatore vero sopra `PeopleIndex`/`PeopleLookup`/`PeopleVisibility`.
 */
abstract class MemoryPeopleContract {

  /** Un mondo con [named] (persona -> media, con nome), [hidden] persone nascoste o dimenticate, [unnamed] gruppi senza nome, [merged]. */
  interface World {
    val subject: MemoryPeople
    val named: Map<PersonId, List<MediaId>>
    val hidden: PersonId
    val unnamed: PersonId
    /** La persona unita ad un'altra (la vecchia) e quella che resta. */
    val merged: Pair<PersonId, PersonId>

    fun turnOff()
  }

  protected abstract fun world(): World

  /** D12, E-H10: solo persone con un nome, mai nascoste ne' dimenticate, mai senza nome. */
  @Test
  fun soloPersoneConNomeENonNascoste() = runTest {
    val world = world()
    val people = world.subject.named().map { it.person }
    assertThat(people).containsAtLeastElementsIn(world.named.keys.filter { it != world.merged.first })
    assertThat(people).containsNoneOf(world.hidden, world.unnamed, world.merged.first)
    val everyone = world.named.values.flatten()
    val inMedia = world.subject.peopleIn(everyone).values.flatten().toSet()
    assertThat(inMedia).containsNoneOf(world.hidden, world.unnamed)
    assertThat(world.subject.label(world.hidden)).isNull()
  }

  /** E-MEM10: una persona unita segue l'alias; una nascosta non si risolve. */
  @Test
  fun gliAliasSiRisolvono() = runTest {
    val world = world()
    assertThat(world.subject.resolve(world.merged.first)).isEqualTo(world.merged.second)
    assertThat(world.subject.resolve(world.hidden)).isNull()
  }

  /** Persone spenta qui: niente persone, niente nei media, nessuna risoluzione. */
  @Test
  fun spentaNonDaNiente() = runTest {
    val world = world()
    world.turnOff()
    assertThat(world.subject.available.value).isFalse()
    assertThat(world.subject.named()).isEmpty()
    assertThat(world.subject.peopleIn(world.named.values.flatten())).isEmpty()
  }
}

/** La suite sul fake (§5.2). */
class FakeMemoryPeopleContractTest : MemoryPeopleContract() {
  override fun world(): World {
    val fake = FakeMemoryPeople()
    val anna = PersonId.fromBytes(ByteArray(16) { 1 })
    val marco = PersonId.fromBytes(ByteArray(16) { 2 })
    val old = PersonId.fromBytes(ByteArray(16) { 3 })
    val hidden = PersonId.fromBytes(ByteArray(16) { 4 })
    val unnamed = PersonId.fromBytes(ByteArray(16) { 5 })
    val media = List(10) { MediaId.random() }
    fake.person(anna, "Anna", media.take(4)).person(marco, "Marco", media.subList(3, 7)).person(old, "Vecchio", media.subList(6, 8))
    fake.person(hidden, "Nascosta", media.subList(8, 10))
    fake.people[unnamed] = media.toMutableSet()
    fake.hidden += hidden
    fake.aliases[old] = marco
    return object : World {
      override val subject: MemoryPeople = fake
      override val named: Map<PersonId, List<MediaId>> = mapOf(anna to media.take(4), marco to media.subList(3, 7), old to media.subList(6, 8))
      override val hidden: PersonId = hidden
      override val unnamed: PersonId = unnamed
      override val merged: Pair<PersonId, PersonId> = old to marco

      override fun turnOff() {
        fake.available.value = false
      }
    }
  }
}
