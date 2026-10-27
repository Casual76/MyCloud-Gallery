package dev.pampa.mycloudgallery.feature.search

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.models.testing.FakeModelManager
import dev.pampa.mycloudgallery.core.search.contract.ChipAlternative
import dev.pampa.mycloudgallery.core.search.contract.ChipReadings
import dev.pampa.mycloudgallery.core.search.contract.IdleSuggestions
import dev.pampa.mycloudgallery.core.search.contract.PersonOption
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import dev.pampa.mycloudgallery.core.search.contract.SearchMediaType
import dev.pampa.mycloudgallery.core.search.contract.SearchNotice
import dev.pampa.mycloudgallery.core.search.contract.Suggestion
import dev.pampa.mycloudgallery.core.search.testing.FakeSearchData
import dev.pampa.mycloudgallery.core.search.testing.FixedToday
import dev.pampa.mycloudgallery.core.search.testing.InMemorySearchHistory
import dev.pampa.mycloudgallery.feature.search.contract.SearchScreenState
import dev.pampa.mycloudgallery.feature.search.noop.MemorySearchOrderStore
import java.time.LocalDate
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Fase 8 (P10, D26, §2.7, E-F36): il cervello di Cerca con le persone. La riga "Persone" a riposo e i nomi che si completano cercano con un chip
 * "Persona" gia' fatto (un nome che e' anche un luogo non cambia lettura); le altre letture della stessa parola prendono il posto del chip; la ricerca
 * si ricorda con il nome come testo (la cronologia lo toglie con "Disattiva e cancella"). Solo nomi inventati.
 */
class PeopleSearchControllerTest {

  private val lib: SyntheticLibrary = library()
  private val marco = PersonOption(listOf(PersonId.fromBytes(ByteArray(PersonId.BYTES) { 1 })), PeopleMode.ANY, "Marco")
  private val anna = PersonOption(listOf(PersonId.fromBytes(ByteArray(PersonId.BYTES) { 2 })), PeopleMode.ANY, "Anna")
  private val engine = ScriptedEngine { request -> lib.results(chips = request.editedChips ?: emptyList()) }
  private val suggestions = FakeSuggestions()
  private val history = InMemorySearchHistory()
  private val data = FakeSearchData(lib.snapshot)
  private val today = FixedToday(LocalDate.of(2026, 10, 21))

  private fun TestScope.controller(): SearchController =
    SearchController(engine, suggestions, history, data, today, MemorySearchOrderStore(), FakeModelManager(), FakeIndexerControl(), backgroundScope, debounceMillis = 100)
      .also { runCurrent() }

  @Test
  fun laRigaDellePersoneARiposoEnellIdle() = runTest {
    suggestions.idle = IdleSuggestions(emptyList(), emptyList(), listOf(SearchMediaType.VIDEO), people = listOf(marco, anna))
    val idle = controller().state.value as SearchScreenState.Idle
    assertThat(idle.suggestions.people.map { it.label }).containsExactly("Marco", "Anna").inOrder()
  }

  @Test
  fun unaPersonaDellaRigaCercaConIlChipGiaFattoESiRicordaComeTesto() = runTest {
    val c = controller()
    c.onPerson(marco)
    runCurrent()
    val request = engine.requests.single()
    assertThat(request.text).isEqualTo("Marco")
    val chip = request.editedChips!!.single() as SearchChip.Person
    assertThat(chip.option).isEqualTo(marco)
    // Il nome resta comunque cercabile come parola, e la ricerca e' nella cronologia con il nome (che "Disattiva e cancella" toglie).
    assertThat(chip.alternatives.map { it.chip }).containsExactly(SearchChip.Word("marco", exact = false))
    assertThat(c.query.value).isEqualTo("Marco")
    assertThat(history.entries.value.map { it.text }).containsExactly("Marco")
    assertThat(history.entries.value.single().chipLabels).containsExactly("Marco")
  }

  @Test
  fun unNomeCompletatoMentreSiScriveCercaLaPersona() = runTest {
    val c = controller()
    c.onCompletion(Suggestion.PersonSuggestion(anna))
    runCurrent()
    assertThat((engine.requests.single().editedChips!!.single() as SearchChip.Person).option).isEqualTo(anna)
    assertThat(history.entries.value.map { it.text }).containsExactly("Anna")
  }

  @Test
  fun lAltraLetturaDiUnNomePrendeIlPostoDelChip_E_F36() = runTest {
    val festa = SearchChip.DateRange(LocalDate.of(2000, 12, 24), LocalDate.of(2000, 12, 26), everyYear = true, label = "Natale (24–26 dicembre, tutti gli anni)")
    val natale = PersonOption(listOf(PersonId.fromBytes(ByteArray(PersonId.BYTES) { 3 })), PeopleMode.ANY, "Natale")
    val person = SearchChip.Person(natale, listOf(ChipAlternative(festa), ChipAlternative(SearchChip.Word("natale", false))), "natale")
    engine.script = { lib.results(chips = request(it, person)) }
    val c = controller()
    c.onQueryChange("Natale")
    c.onSubmit()
    runCurrent()
    // "Cerca la festa": la festa prende il posto della persona e ricorda la persona per tornare indietro.
    c.onSwitchReading(person, ChipReadings.switchTo(person, person.alternatives.first()))
    runCurrent()
    val asFestival = engine.requests.last().editedChips!!.single() as SearchChip.DateRange
    assertThat(asFestival.person).isEqualTo(natale)
    // "Cerca la persona": dalla festa si torna al chip "Persona".
    c.onSwitchReading(asFestival, ChipReadings.backToPerson(asFestival)!!)
    runCurrent()
    assertThat((engine.requests.last().editedChips!!.single() as SearchChip.Person).option).isEqualTo(natale)
    // "Cerca come parola".
    c.onSwitchReading(engine.requests.last().editedChips!!.single(), person.alternatives.last().chip)
    runCurrent()
    assertThat(engine.requests.last().editedChips).containsExactly(SearchChip.Word("natale", exact = false))
  }

  private fun request(request: dev.pampa.mycloudgallery.core.search.contract.SearchRequest, first: SearchChip): List<SearchChip> =
    request.editedChips ?: listOf(first)

  @Test
  fun scegliereUnAltroLuogoNonPerdeLaPersona() = runTest {
    val roma = dev.pampa.mycloudgallery.core.search.contract.PlaceOption(setOf(1), "Roma", dev.pampa.mycloudgallery.core.search.contract.PlaceLevel.PLACE)
    val texas = dev.pampa.mycloudgallery.core.search.contract.PlaceOption(setOf(2), "Roma (Texas)", dev.pampa.mycloudgallery.core.search.contract.PlaceLevel.PLACE)
    val place = SearchChip.Place(roma, listOf(texas), "roma", marco)
    engine.script = { lib.results(chips = listOf(place)) }
    val c = controller()
    c.onQueryChange("roma")
    c.onSubmit()
    runCurrent()
    c.onChooseAlternative(place, texas)
    runCurrent()
    val swapped = engine.requests.last().editedChips!!.single() as SearchChip.Place
    assertThat(swapped.person).isEqualTo(marco)
  }

  @Test
  fun inAttesaDelPrimoCalcoloLAvvisoPassaAlloSchermo() = runTest {
    engine.script = { lib.results(chips = listOf(ChipReadings.ofOption(marco)), notices = listOf(SearchNotice.PeopleRecognizing(60))) }
    val c = controller()
    c.onQueryChange("Marco")
    c.onSubmit()
    runCurrent()
    val shown = c.state.value as SearchScreenState.Results
    assertThat(shown.notices).containsExactly(SearchNotice.PeopleRecognizing(60))
    advanceTimeBy(1)
  }
}
