package dev.pampa.mycloudgallery.core.search

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.noop.EmptyTimelineSnapshot
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.search.contract.ChipAlternative
import dev.pampa.mycloudgallery.core.search.contract.ChipMatching
import dev.pampa.mycloudgallery.core.search.contract.PeopleDictionary
import dev.pampa.mycloudgallery.core.search.contract.PersonEntry
import dev.pampa.mycloudgallery.core.search.contract.PersonOption
import dev.pampa.mycloudgallery.core.search.contract.PlaceDictionary
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import dev.pampa.mycloudgallery.core.search.noop.NoPeopleLookup
import dev.pampa.mycloudgallery.core.search.parse.ItalianQueryParser
import dev.pampa.mycloudgallery.core.search.testing.FakePeopleLookup
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Fase 8 (P0, D26): le firme della ricerca per persona. Finche' P10 non scrive l'analizzatore con il dizionario, un nome resta testo (la
 * firma nuova di `QueryParser` ignora le persone); il chip "Persona" e' un filtro che sull'istantanea da sola non allarga mai i risultati;
 * nomi e id non escono nei log.
 */
class PeopleSignaturesTest {

  private val marco = PersonId.random()
  private val anna = PersonId.random()

  @Test
  fun unaSceltaDiPersoneHaRegoleEMaiNomiNeiLog() {
    val option = PersonOption(listOf(marco, anna), PeopleMode.ALL, "Marco e Anna")
    assertThat(option.toString()).doesNotContain("Marco")
    assertThrows(IllegalArgumentException::class.java) { PersonOption(emptyList(), PeopleMode.ANY, "") }
    assertThrows(IllegalArgumentException::class.java) { PersonOption(listOf(marco, marco), PeopleMode.ANY, "Marco") }
    assertThrows(IllegalArgumentException::class.java) { PersonOption(List(33) { PersonId.random() }, PeopleMode.ANY, "Tanti") }
    val chip = SearchChip.Person(option, listOf(ChipAlternative(SearchChip.Word("marco", exact = false))), "marco e anna")
    assertThat(chip.isFilter).isTrue()
    assertThat(chip.label).isEqualTo("Marco e Anna")
    assertThat(chip.toString()).doesNotContain("Marco")
    assertThat(chip.alternatives.single().label).isEqualTo("marco")
    assertThrows(IllegalArgumentException::class.java) { ChipAlternative(chip) }
  }

  @Test
  fun ilDizionarioNormalizzaComeILuoghiEScegliePerFrequenza() {
    assertThat(PeopleDictionary.normalize("  Nicolò D'Amico ")).isEqualTo("nicolo d amico")
    val dictionary = PeopleDictionary(
      listOf(
        PersonEntry("marco", marco, "Marco", 3),
        PersonEntry("marco", anna, "Marco", 12),
      ),
    )
    assertThat(dictionary.lookup("marco").map { it.id }).containsExactly(anna, marco).inOrder()
    assertThat(dictionary.lookup("anna")).isEmpty()
    assertThrows(IllegalArgumentException::class.java) { PersonEntry("Marco", marco, "Marco", 1) }
    assertThrows(IllegalArgumentException::class.java) { PersonEntry("marco", marco, "Marco", 0) }
    assertThat(PeopleDictionary.EMPTY.isEmpty).isTrue()
  }

  /** Fino a P10 la firma con le persone e' quella di prima: "Marco" e' testo per il contenuto, nessun chip. */
  @Test
  fun senzaLAnalizzatoreDiP10UnNomeRestaTesto() {
    val parser = ItalianQueryParser()
    val today = LocalDate.of(2026, 10, 27)
    val people = PeopleDictionary(listOf(PersonEntry("marco", marco, "Marco", 5)))
    val with = parser.parse("Marco al mare", today, PlaceDictionary.EMPTY, people)
    val without = parser.parse("Marco al mare", today, PlaceDictionary.EMPTY)
    assertThat(with).isEqualTo(without)
    assertThat(with.chips.none { it is SearchChip.Person }).isTrue()
  }

  /** Il chip "Persona" non si giudica sulla cella: sull'istantanea da sola esclude (non mostra mai altre persone). */
  @Test
  fun ilChipPersonaSullaSolaIstantaneaEscludeTutto() {
    val chip = SearchChip.Person(PersonOption(listOf(marco), PeopleMode.ANY, "Marco"), emptyList(), "marco")
    assertThat(ChipMatching.accepts(chip, EmptyTimelineSnapshot, 0)).isFalse()
  }

  @Test
  fun lePorteDellePersone() = runTest {
    assertThat(NoPeopleLookup.available.value).isFalse()
    assertThat(NoPeopleLookup.dictionary().isEmpty).isTrue()
    val a = MediaId.random()
    val b = MediaId.random()
    val c = MediaId.random()
    val fake = FakePeopleLookup().name(marco, "Marco").name(anna, "Anna").sure(marco, listOf(a, b)).sure(anna, listOf(b)).likely(anna, listOf(c))
    assertThat(NoPeopleLookup.cells(PersonOption(listOf(marco), PeopleMode.ANY, "Marco"))).isEmpty()
    assertThat(fake.cells(PersonOption(listOf(marco, anna), PeopleMode.ALL, "Marco e Anna"))).containsExactly(b)
    assertThat(fake.cells(PersonOption(listOf(marco, anna), PeopleMode.ANY, "Marco o Anna"))).containsExactly(a, b)
    assertThat(fake.cells(PersonOption(listOf(anna), PeopleMode.ANY, "Anna"), includeLikely = true)).containsExactly(b, c)
    assertThat(fake.dictionary().lookup("marco").single().mediaCount).isEqualTo(2)
    fake.available.value = false
    assertThat(fake.dictionary().isEmpty).isTrue()
  }
}
