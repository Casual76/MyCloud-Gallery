package dev.pampa.mycloudgallery.feature.search

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.models.testing.FakeModelManager
import dev.pampa.mycloudgallery.core.search.contract.ChipReadings
import dev.pampa.mycloudgallery.core.search.contract.PersonOption
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import dev.pampa.mycloudgallery.core.search.contract.SearchNotice
import dev.pampa.mycloudgallery.core.search.contract.SearchReadiness
import dev.pampa.mycloudgallery.core.search.contract.Suggestion
import dev.pampa.mycloudgallery.core.search.testing.FakeSearchData
import dev.pampa.mycloudgallery.core.search.testing.InMemorySearchHistory
import dev.pampa.mycloudgallery.feature.search.contract.SearchScreenState
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Fase 8 (P10, D26, §2.7) dentro la Libreria (H4): i nomi delle persone arrivano fra i suggerimenti della barra in alto mentre si scrive; sceglierne uno
 * cerca con il chip "Persona" gia' fatto (un nome che e' anche un luogo non cambia lettura) e ricorda la ricerca con il nome come testo (la cronologia lo
 * toglie con "Disattiva e cancella"); l'avviso del primo calcolo dei volti va nella riga sopra la barra. Solo nomi inventati.
 */
class PeopleSearchControllerTest {

  private val lib: SyntheticLibrary = library()
  private val marco = PersonOption(listOf(PersonId.fromBytes(ByteArray(PersonId.BYTES) { 1 })), PeopleMode.ANY, "Marco")
  private val anna = PersonOption(listOf(PersonId.fromBytes(ByteArray(PersonId.BYTES) { 2 })), PeopleMode.ANY, "Anna")
  private val engine = ScriptedEngine { request -> lib.results(chips = request.editedChips ?: emptyList()) }
  private val suggestions = FakeSuggestions()
  private val history = InMemorySearchHistory()
  private val data = FakeSearchData(lib.snapshot)

  private fun TestScope.controller(): SearchController =
    SearchController(engine, suggestions, history, data, FakeModelManager(), backgroundScope, completionDebounceMillis = 100, searchDebounceMillis = 200)
      .also { runCurrent() }

  @Test
  fun unNomeCheComincia_ComeSiScriveEFraISuggerimentiInAlto() = runTest {
    suggestions.completions = { prefix -> listOf(marco, anna).filter { it.label.lowercase().startsWith(prefix.lowercase()) }.map { Suggestion.PersonSuggestion(it) } }
    val c = controller()
    c.onQueryChange("ma")
    advanceTimeBy(150)
    runCurrent()
    assertThat(c.topSuggestions.value.map { it.label }).containsExactly("Marco")
  }

  @Test
  fun unaPersonaSceltaCercaConIlChipGiaFattoESiRicordaComeTesto() = runTest {
    val c = controller()
    c.onSuggestion(Suggestion.PersonSuggestion(marco))
    runCurrent()
    val request = engine.requests.single()
    assertThat(request.text).isEqualTo("Marco")
    val chip = request.editedChips!!.single() as SearchChip.Person
    assertThat(chip).isEqualTo(ChipReadings.ofOption(marco))
    // Il nome resta comunque cercabile come parola, e la ricerca e' nella cronologia con il nome (che "Disattiva e cancella" toglie).
    assertThat(chip.alternatives.map { it.chip }).containsExactly(SearchChip.Word("marco", exact = false))
    assertThat(c.query.value).isEqualTo("Marco")
    assertThat(history.entries.value.map { it.text }).containsExactly("Marco")
    assertThat(history.entries.value.single().chipLabels).containsExactly("Marco")
  }

  @Test
  fun inAttesaDelPrimoCalcoloLAvvisoVaNellaRigaSopraLaBarra() = runTest {
    engine.script = { lib.results(chips = listOf(ChipReadings.ofOption(marco)), notices = listOf(SearchNotice.PeopleRecognizing(60))) }
    val c = controller()
    c.onSuggestion(Suggestion.PersonSuggestion(marco))
    runCurrent()
    val shown = c.state.value as SearchScreenState.Results
    assertThat(shown.notices).containsExactly(SearchNotice.PeopleRecognizing(60))
    assertThat(SearchLines.of(shown, SearchReadiness.Ready, null)).isEqualTo(SearchLine.Notice(NoticeItem.PeopleProgress(60)))
  }
}
