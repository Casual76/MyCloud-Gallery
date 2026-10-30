package dev.pampa.mycloudgallery.core.memories.plan.people

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.memories.testing.FakeMemoryPeople
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Il fake delle persone fedele alla porta (docs/fase9-progetto.md D12, E-MEM10, E-H10; P8): una persona unita a un'altra non c'e' piu' con il suo id
 * ma i suoi media sono di chi resta (anche con piu' unioni di fila), una nascosta, dimenticata o senza nome non c'e' mai, ne' nei media ne'
 * nell'elenco, e `label` e' l'unico posto dove si legge un nome.
 */
class FakeMemoryPeopleTest {

  private val a = PersonId.fromBytes(ByteArray(16) { 1 })
  private val b = PersonId.fromBytes(ByteArray(16) { 2 })
  private val c = PersonId.fromBytes(ByteArray(16) { 3 })
  private val d = PersonId.fromBytes(ByteArray(16) { 4 })
  private val media = List(12) { MediaId.random() }

  private fun fake(): FakeMemoryPeople = FakeMemoryPeople()
    .person(a, "Anna", media.subList(0, 4)).person(b, "Bruno", media.subList(3, 7)).person(c, "Carla", media.subList(6, 9))
    .person(d, "Dario", media.subList(9, 12))

  @Test
  fun unaPersonaUnitaPortaIMediaAChiResta() = runTest {
    val people = fake().merge(b, a)
    assertThat(people.named().map { it.person }).containsExactly(a, c, d)
    assertThat(people.named().single { it.person == a }.members).containsExactlyElementsIn(media.subList(0, 7))
    assertThat(people.resolve(b)).isEqualTo(a)
    assertThat(people.label(b)).isEqualTo("Anna")
    val inMedia = people.peopleIn(media)
    assertThat(inMedia.getValue(media[5])).containsExactly(a)
    assertThat(inMedia.getValue(media[3])).containsExactly(a)
    assertThat(inMedia.values.flatten()).doesNotContain(b)
  }

  @Test
  fun leUnioniACatena() = runTest {
    val people = fake().merge(c, b).merge(b, a)
    assertThat(people.resolve(c)).isEqualTo(a)
    assertThat(people.named().single { it.person == a }.members).containsExactlyElementsIn(media.subList(0, 9))
    assertThat(people.peopleIn(listOf(media[7])).getValue(media[7])).containsExactly(a)
  }

  @Test
  fun unaNascostaOSenzaNomeNonCeMai() = runTest {
    val people = fake().merge(b, a).hide(a)
    // Hidden il bersaglio dell'unione: sparisce con tutti i suoi media (anche quelli della persona unita).
    assertThat(people.named().map { it.person }).containsExactly(c, d)
    assertThat(people.resolve(a)).isNull()
    assertThat(people.resolve(b)).isNull()
    assertThat(people.label(b)).isNull()
    assertThat(people.peopleIn(media.subList(0, 6))).isEmpty()
    val unnamed = PersonId.fromBytes(ByteArray(16) { 9 })
    people.people[unnamed] = media.toMutableSet()
    assertThat(people.named().map { it.person }).doesNotContain(unnamed)
    assertThat(people.peopleIn(media).values.flatten()).doesNotContain(unnamed)
  }

  @Test
  fun iNascostiDellaLibreriaNonCompaionoMai() = runTest {
    val hidden = media.take(2).toSet()
    val people = FakeMemoryPeople { ids -> ids.filter { it !in hidden }.toSet() }.person(a, "Anna", media.subList(0, 6))
    assertThat(people.named().single().members).containsExactlyElementsIn(media.subList(2, 6))
    assertThat(people.peopleIn(media.take(3)).keys).containsExactly(media[2])
  }

  @Test
  fun nonDisponibileNonDaNiente() = runTest {
    val people = fake().merge(b, a)
    people.available.value = false
    assertThat(people.named()).isEmpty()
    assertThat(people.peopleIn(media)).isEmpty()
    assertThat(people.resolve(a)).isNull()
    assertThat(people.label(a)).isNull()
  }
}
