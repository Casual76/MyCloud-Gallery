package dev.pampa.mycloudgallery.core.search.engine

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.database.SearchHistoryRecord
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.search.contract.ChipAlternative
import dev.pampa.mycloudgallery.core.search.contract.ChipReadings
import dev.pampa.mycloudgallery.core.search.contract.PersonOption
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import dev.pampa.mycloudgallery.core.search.contract.SearchHistory
import dev.pampa.mycloudgallery.core.search.contract.Suggestion
import dev.pampa.mycloudgallery.core.search.history.HistoryNames
import dev.pampa.mycloudgallery.core.search.history.HistoryStore
import dev.pampa.mycloudgallery.core.search.history.LocalSearchHistory
import dev.pampa.mycloudgallery.core.search.noop.NoPeopleLookup
import dev.pampa.mycloudgallery.core.search.noop.NoSearchHistory
import dev.pampa.mycloudgallery.core.search.parse.ItalianQueryParser
import dev.pampa.mycloudgallery.core.search.parse.TestPeople
import dev.pampa.mycloudgallery.core.search.testing.FakePeopleLookup
import dev.pampa.mycloudgallery.core.search.testing.FakeSearchData
import dev.pampa.mycloudgallery.core.search.testing.InMemorySearchHistory
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Fase 8 (P10, §2.7, D28, invariante 1 del §2.8): i suggerimenti delle persone (a riposo e mentre si scrive), la cronologia ripulita dai nomi quando
 * Persone si cancella, le due mosse del foglio di un chip (E-F36).
 */
class PeopleHistoryAndSuggestionsTest {

  // --- Suggerimenti ------------------------------------------------------------------------------------------------------------------

  private fun lookup(): FakePeopleLookup {
    val lookup = FakePeopleLookup()
    // Otto persone con un nome, dalla piu' alla meno fotografata: a riposo ne escono sei.
    TestPeople.ENTRIES.take(8).forEachIndexed { index, entry ->
      lookup.name(entry.id, entry.label).sure(entry.id, List(40 - index * 3) { MediaId.random() })
    }
    // Un nome di due parole, il meno fotografato: non e' nella riga a riposo, ma si completa.
    lookup.name(TestPeople.MARIA_TERESA, "Maria Teresa").sure(TestPeople.MARIA_TERESA, List(5) { MediaId.random() })
    return lookup
  }

  private fun suggestions(lookup: FakePeopleLookup, history: SearchHistory = InMemorySearchHistory()): LibrarySuggestions {
    val library = SyntheticLibrary(seed = 3, count = 50)
    return LibrarySuggestions(FakeSearchData(library.snapshot), LibraryPlaces { null }, history, lookup)
  }

  @Test
  fun aRiposoUnaRigaDiPersoneLePiuFrequentiFinoASei() = runTest {
    val idle = suggestions(lookup()).idle()
    assertThat(idle.people.map { it.label }).containsExactly("Marco", "Anna", "Giulia", "Luca", "Sara", "Elena").inOrder()
    assertThat(idle.people.all { it.mode == PeopleMode.ANY && it.ids.size == 1 }).isTrue()
  }

  @Test
  fun senzaVoltiNienteRigaDiPersone() = runTest {
    val lookup = lookup()
    lookup.available.value = false
    assertThat(suggestions(lookup).idle().people).isEmpty()
    assertThat(suggestions(lookup).complete("ma")).isEmpty()
    assertThat(LibrarySuggestions(FakeSearchData(SyntheticLibrary(seed = 3, count = 5).snapshot), LibraryPlaces { null }, NoSearchHistory, NoPeopleLookup).idle().people).isEmpty()
  }

  @Test
  fun mentreSiScriveISuggerimentiSonoINomiCheCominciano() = runTest {
    val s = suggestions(lookup())
    val byPrefix = s.complete("ma").filterIsInstance<Suggestion.PersonSuggestion>().map { it.label }
    assertThat(byPrefix).containsExactly("Marco", "Maria Teresa").inOrder()
    // Anche la seconda parola di un nome di piu' parole ("Teresa") e il maiuscolo.
    assertThat(s.complete("TERE").filterIsInstance<Suggestion.PersonSuggestion>().map { it.label }).containsExactly("Maria Teresa")
    assertThat(s.complete("zz")).isEmpty()
    // Mai nomi nei log: il toString non li mostra.
    assertThat(Suggestion.PersonSuggestion(PersonOption(listOf(TestPeople.MARCO), PeopleMode.ANY, "Marco")).toString()).doesNotContain("Marco")
  }

  @Test
  fun unaPortaCheSbagliaNonRompeISuggerimenti() = runTest {
    val failing = object : dev.pampa.mycloudgallery.core.search.contract.PeopleLookup {
      override val available = MutableStateFlow(true)

      override suspend fun dictionary() = error("guasto")

      override suspend fun cells(option: PersonOption, includeLikely: Boolean): Set<MediaId> = emptySet()
    }
    val library = SyntheticLibrary(seed = 3, count = 20)
    val s = LibrarySuggestions(FakeSearchData(library.snapshot), LibraryPlaces { null }, InMemorySearchHistory(), failing)
    assertThat(s.idle().people).isEmpty()
    assertThat(s.complete("ma")).isEmpty()
  }

  // --- Cronologia ----------------------------------------------------------------------------------------------------------------------

  private class Store : HistoryStore {
    val rows = MutableStateFlow<List<SearchHistoryRecord>>(emptyList())
    var next = 1L
    var oneShot: Boolean = true

    override suspend fun record(text: String, chipLabels: List<String>) {
      rows.value = listOf(SearchHistoryRecord(next++, text, chipLabels)) + rows.value.filter { it.text != text }
    }

    override suspend fun delete(id: Long) {
      rows.value = rows.value.filter { it.id != id }
    }

    override suspend fun clear() {
      rows.value = emptyList()
    }

    override fun changes(limit: Int): Flow<List<SearchHistoryRecord>> = rows

    override suspend fun records(limit: Int): List<SearchHistoryRecord>? = if (oneShot) rows.value.take(limit) else null
  }

  private fun chip(label: String) = ChipReadings.ofOption(PersonOption(listOf(TestPeople.MARCO), PeopleMode.ANY, label))

  @Test
  fun lePurgheTolgonoLeVociConUnNomeNelTestoOInUnChip() = runTest {
    for (oneShot in listOf(true, false)) {
      val store = Store().also { it.oneShot = oneShot }
      val history = LocalSearchHistory(store, backgroundScope)
      history.record("Marco al mare", listOf(chip("Marco")))
      history.record("mare ad agosto", listOf(SearchChip.Word("mare", false)))
      history.record("le foto di marko", listOf(chip("Marco")))
      history.record("cena con Anna Maria", emptyList())
      history.record("Giovanna in montagna", emptyList())
      history.record("compleanno", listOf(chip("Elena")))
      history.entries.first { it.size == 6 }
      val removed = history.purge(listOf("Marco", "Elena", "Anna"))
      assertWithMessage("voci tolte (lettura sola: $oneShot)").that(removed).isEqualTo(4)
      assertThat(store.rows.value.map { it.text }).containsExactly("mare ad agosto", "Giovanna in montagna")
      // Idempotente: una seconda volta non c'e' piu' niente da togliere.
      history.entries.first { it.size == 2 }
      assertThat(history.purge(listOf("Marco", "Elena", "Anna"))).isEqualTo(0)
    }
  }

  @Test
  fun iNomiSiRiconosconoAParoleIntereSenzaMaiuscoleNeAccentiNeApostrofi() {
    val wanted = HistoryNames.wanted(listOf("Nicolò D'Amico", "  Anna  ", ""))
    assertThat(wanted).containsExactly(listOf("nicolo", "d", "amico"), listOf("anna"))
    assertThat(HistoryNames.contains("foto di NICOLO d'amico a Roma", emptyList(), wanted)).isTrue()
    assertThat(HistoryNames.contains("a Roma", listOf("Anna e Marco"), wanted)).isTrue()
    assertThat(HistoryNames.contains("Giovanna", emptyList(), wanted)).isFalse()
    assertThat(HistoryNames.contains("Anna", emptyList(), emptyList())).isFalse()
    assertThat(HistoryNames.contains("nicolo", emptyList(), wanted)).isFalse()
  }

  @Test
  fun lePurgheDelRiferimentoEDelNullObject() = runTest {
    val history = InMemorySearchHistory()
    history.record("Marco al mare", emptyList())
    history.record("mare", emptyList())
    assertThat(history.purge(listOf("Marco"))).isEqualTo(1)
    assertThat(history.entries.value.map { it.text }).containsExactly("mare")
    assertThat(NoSearchHistory.purge(listOf("Marco"))).isEqualTo(0)
  }

  @Test
  fun nessunNomeNellaCronologiaDopoLaPurga_proprietaSuRicerchePiuVarie() = runTest {
    val store = Store()
    val history = LocalSearchHistory(store, backgroundScope)
    val parser = ItalianQueryParser()
    val queries = listOf(
      "Marco", "Marco e Anna", "Marco o Anna a Natale", "foto di Giulia", "Maria Teresa al mare", "Anna Maria", "Gian Luca e Luca",
      "Nicolò D'Amico", "mare 2024", "Firenze", "Marina", "Zio Piero a Natale", "video di Nonna Rosa", "screenshot",
    )
    for (q in queries) {
      val parsed = parser.parse(q, LocalDate.of(2026, 10, 21), TestPeople.PLACES, TestPeople.DICTIONARY)
      history.record(q, parsed.chips)
    }
    val names = TestPeople.ENTRIES.map { it.label }
    history.purge(names)
    val left = store.rows.value
    val wanted = HistoryNames.wanted(names)
    assertWithMessage("voci con un nome rimaste").that(left.filter { HistoryNames.contains(it.text, it.chipLabels, wanted) }).isEmpty()
    // Le voci senza nomi restano.
    assertThat(left.map { it.text }).containsAtLeast("mare 2024", "Firenze", "screenshot")
  }

  // --- Le due mosse del foglio di un chip (E-F36) -------------------------------------------------------------------------------

  @Test
  fun dalPersonaAllaFestaEIndietro() {
    val parsed = ItalianQueryParser().parse("Maggio", LocalDate.of(2026, 10, 21), TestPeople.PLACES, TestPeople.DICTIONARY)
    val person = parsed.chips.single() as SearchChip.Person
    val month = person.alternatives.first { it.chip is SearchChip.DateRange }
    val switched = ChipReadings.switchTo(person, month) as SearchChip.DateRange
    assertThat(switched.person).isEqualTo(person.option)
    assertThat(ChipReadings.personOf(switched)).isEqualTo(person.option)
    val back = ChipReadings.backToPerson(switched)!!
    assertThat(back.option).isEqualTo(person.option)
    assertThat(back.alternatives.map { it.chip }).contains((month.chip as SearchChip.DateRange))
    // "Cerca come parola" non ha ritorno (come per i luoghi).
    val word = ChipReadings.switchTo(person, ChipAlternative(SearchChip.Word("maggio", exact = false)))
    assertThat(word).isEqualTo(SearchChip.Word("maggio", exact = false))
    assertThat(ChipReadings.backToPerson(word)).isNull()
  }

  @Test
  fun dalLuogoAllaPersonaEIndietro() {
    val parsed = ItalianQueryParser().parse("Roma", LocalDate.of(2026, 10, 21), TestPeople.PLACES, TestPeople.DICTIONARY)
    val place = parsed.chips.single() as SearchChip.Place
    assertThat(place.person?.label).isEqualTo("Roma")
    val person = ChipReadings.backToPerson(place)!!
    assertThat(person.alternatives.map { it.chip }).contains(place.copy(person = null))
    val again = ChipReadings.switchTo(person, person.alternatives.first { it.chip is SearchChip.Place })
    assertThat(again).isEqualTo(place)
  }
}
