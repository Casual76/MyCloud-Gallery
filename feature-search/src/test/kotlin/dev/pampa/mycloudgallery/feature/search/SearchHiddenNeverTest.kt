package dev.pampa.mycloudgallery.feature.search

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.search.contract.PlaceName
import dev.pampa.mycloudgallery.core.search.engine.LibraryPlaces
import dev.pampa.mycloudgallery.core.search.engine.LibrarySuggestions
import dev.pampa.mycloudgallery.core.search.testing.FakeSearchData
import dev.pampa.mycloudgallery.core.search.testing.FixedToday
import dev.pampa.mycloudgallery.core.search.testing.InMemorySearchEngine
import dev.pampa.mycloudgallery.core.search.testing.InMemorySearchHistory
import dev.pampa.mycloudgallery.core.search.testing.SimpleQueryParser
import dev.pampa.mycloudgallery.feature.search.contract.SearchScreenState
import kotlinx.coroutines.test.TestScope
import dev.pampa.mycloudgallery.core.search.contract.Suggestion
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * NASCOSTI MAI (D29, E-Q7, §4.3), a livello di schermata: con il motore, i suggerimenti e la cronologia veri (di riferimento) sotto il controller, un nascosto
 * **perfetto** (testo, vettore, luogo) non esce da nessuna superficie della ricerca (H4: dentro la Libreria): risultati, meno probabili, suggerimenti
 * della barra in alto (completamenti, luoghi, ricerche recenti), "Trova simili". Un nascosto appena nascosto altrove (ancora nell'istantanea) resta fuori
 * dal filtro finale.
 */
class SearchHiddenNeverTest {

  private val lib: SyntheticLibrary = SyntheticLibrary(seed = 21L, count = 200, placeIds = intArrayOf(1, 2, 3))
  private val data = FakeSearchData(lib.snapshot)
  private val history = InMemorySearchHistory()
  private val today = FixedToday()
  private val stale: MediaId = lib.id(5)
  private val secrets: Set<MediaId> = lib.hidden.map { it.id }.toSet() + stale

  init {
    lib.hidden.forEach { data.texts[it.id] = "tazza segretissima" }
    data.texts[stale] = "tazza segretissima"
    data.texts[lib.id(0)] = "tazza blu"
    data.texts[lib.id(1)] = "tazza rossa"
    data.hiddenIds += secrets
  }

  private fun TestScope.controller(): SearchController {
    val places = LibraryPlaces { id -> PlaceName("Luogo $id", null, "Italia") }
    val engine = InMemorySearchEngine(data, embedder = null, parser = SimpleQueryParser(), today = today, places = { places.dictionary(lib.snapshot) })
    val suggestions = LibrarySuggestions(data, places, history)
    return SearchController(engine, suggestions, history, data, null, backgroundScope, completionDebounceMillis = 100, searchDebounceMillis = 200).also { runCurrent() }
  }

  private fun ids(view: OrderedTimelineView): List<MediaId> = (0 until view.size).map { view.snapshot.idAt(view.snapshotIndex(it)) }

  private fun shown(state: SearchScreenState): List<MediaId> = when (state) {
    is SearchScreenState.Results -> ids(state.confident) + ids(state.likely)
    else -> emptyList()
  }

  @Test
  fun leParoleDelTestoNascostoNonTrovanoNiente_ELaRicercaNormaleNonLoMostra() = runTest {
    val c = controller()
    c.onQueryChange("tazza")
    c.onSubmit()
    runCurrent()
    val results = c.state.value as SearchScreenState.Results
    assertThat(shown(results)).containsAtLeast(lib.id(0), lib.id(1))
    assertThat(shown(results)).containsNoneIn(secrets)
    // La parola che c'e' solo nei nascosti, esatta o con un refuso: niente da mostrare.
    for (text in listOf("segretissima", "\"segretissima\"")) {
      c.onQueryChange(text)
      c.onSubmit()
      runCurrent()
      assertThat(shown(c.state.value)).containsNoneIn(secrets)
      assertThat(shown(c.state.value)).isEmpty()
    }
  }

  @Test
  fun scrivendoENelleDueSezioniIlNascostoNonCe() = runTest {
    val c = controller()
    for (text in listOf("ta", "tazz", "tazza")) {
      c.onQueryChange(text)
      advanceTimeBy(300)
      runCurrent()
      assertThat(shown(c.state.value)).containsNoneIn(secrets)
    }
  }

  @Test
  fun iCompletamentiNonProponganoParoleDelTestoLettoNeIlLuogoDeiNascosti() = runTest {
    val c = controller()
    for (prefix in listOf("seg", "segret", "tazz", "lu")) {
      c.onQueryChange(prefix)
      advanceTimeBy(200)
      runCurrent()
      val labels = c.topSuggestions.value.map { it.label.lowercase() }
      assertThat(labels.none { "segretissim" in it }).isTrue()
    }
  }

  @Test
  fun aRiposoLeRicercheRecentiEILuoghiSonoQuelliDellaLibreriaVisibile() = runTest {
    history.record("tazza blu", emptyList())
    val c = controller()
    val top = c.topSuggestions.value
    assertThat(top.first().label).isEqualTo("tazza blu")
    // I luoghi proposti vengono dall'istantanea (che non ha i nascosti).
    val inSnapshot = (0 until lib.snapshot.size).map { lib.snapshot.placeAt(it) }.toSet()
    assertThat(top.filterIsInstance<Suggestion.PlaceSuggestion>().flatMap { it.option.ids }.toSet() - inSnapshot).isEmpty()
  }

  @Test
  fun trovaSimiliSuUnNascostoNonDaNiente_E_Q14() = runTest {
    val c = controller()
    data.vectors[stale] = listOf(FloatArray(768) { 1f / 27.7f })
    c.findSimilar(stale)
    runCurrent()
    assertThat(shown(c.state.value)).isEmpty()
    assertThat(c.state.value).isNotInstanceOf(SearchScreenState.Results::class.java)
  }
}
