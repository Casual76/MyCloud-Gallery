package dev.pampa.mycloudgallery.feature.search

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.noop.EmptyTimelineSnapshot
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.ModelSourceKind
import dev.pampa.mycloudgallery.core.models.contract.ModelState
import dev.pampa.mycloudgallery.core.models.testing.FakeModelManager
import dev.pampa.mycloudgallery.core.search.contract.IdleSuggestions
import dev.pampa.mycloudgallery.core.search.contract.PlaceLevel
import dev.pampa.mycloudgallery.core.search.contract.PlaceOption
import dev.pampa.mycloudgallery.core.search.contract.ResultOrder
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import dev.pampa.mycloudgallery.core.search.contract.SearchMediaType
import dev.pampa.mycloudgallery.core.search.contract.SearchNotice
import dev.pampa.mycloudgallery.core.search.contract.SearchReadiness
import dev.pampa.mycloudgallery.core.search.contract.Suggestion
import dev.pampa.mycloudgallery.core.search.testing.FakeSearchData
import dev.pampa.mycloudgallery.core.search.testing.InMemorySearchHistory
import dev.pampa.mycloudgallery.feature.search.contract.SearchScreenState
import dev.pampa.mycloudgallery.feature.search.contract.SearchUnavailableReason
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Il cervello della ricerca dentro la Libreria (H4 della fase H, 11/11; prima la schermata Cerca della Fase 6, P8, §1.5, §2.6): scrivere e' cercare (dopo
 * una pausa), i tre suggerimenti della barra in alto, l'ordine solo per pertinenza, niente lampi di vuoto mentre il motore lavora, la cronologia, "Trova
 * simili", gli errori e il contenuto che arriva dopo.
 */
class SearchControllerTest {

  private val lib: SyntheticLibrary = library()
  private val engine = ScriptedEngine { lib.results(chips = listOf(SearchChip.MediaType(SearchMediaType.VIDEO, "Video"))) }
  private val suggestions = FakeSuggestions()
  private val history = InMemorySearchHistory()
  private val data = FakeSearchData(lib.snapshot)
  private val models = FakeModelManager()

  private fun TestScope.controller(): SearchController =
    SearchController(engine, suggestions, history, data, models, backgroundScope, completionDebounceMillis = COMPLETION, searchDebounceMillis = SEARCH)
      .also { runCurrent() }

  private val SearchController.results get() = state.value as SearchScreenState.Results

  private fun TestScope.type(c: SearchController, text: String) {
    c.onQueryChange(text)
    advanceTimeBy(SEARCH + 10)
    runCurrent()
  }

  private val roma = PlaceOption(setOf(1), "Roma", PlaceLevel.PLACE)
  private val sardegna = PlaceOption(setOf(3), "Sardegna", PlaceLevel.REGION)

  // --- I suggerimenti della barra in alto ---------------------------------------------------------------------------------------------

  @Test
  fun aCampoVuotoITreSuggerimentiSonoLeRecentiPoiILuoghiPoiITipi() = runTest {
    suggestions.idle = IdleSuggestions(listOf(recent(1, "mare ad agosto")), listOf(roma, sardegna), listOf(SearchMediaType.VIDEO, SearchMediaType.SCREENSHOT))
    val c = controller()
    assertThat(c.topSuggestions.value.map { it.label }).containsExactly("mare ad agosto", "Roma", "Sardegna").inOrder()

    suggestions.idle = IdleSuggestions(emptyList(), emptyList(), listOf(SearchMediaType.VIDEO, SearchMediaType.SCREENSHOT))
    c.onOpened()
    runCurrent()
    // I tipi hanno le parole che l'analizzatore riconosce.
    assertThat(c.topSuggestions.value.map { it.label }).containsExactly("Video", "Screenshot").inOrder()
  }

  @Test
  fun mentreSiScriveDopoLaPausaArrivanoITreCompletamentiMigliori() = runTest {
    suggestions.completions = {
      listOf(Suggestion.Recent(recent(1, "agosto al mare")), Suggestion.PlaceSuggestion(PlaceOption(setOf(6), "Agrigento", PlaceLevel.PLACE)), Suggestion.Type(SearchMediaType.PHOTO, "Foto"), Suggestion.Term("agenda"))
    }
    val c = controller()
    c.onQueryChange("ag")
    runCurrent()
    assertThat(suggestions.completeCalls).isEmpty()
    advanceTimeBy(COMPLETION + 10)
    runCurrent()
    assertThat(c.topSuggestions.value.map { it.label }).containsExactly("agosto al mare", "Agrigento", "Foto").inOrder()
    assertThat(suggestions.completeCalls).containsExactly("ag")
  }

  @Test
  fun iCompletamentiHannoMesiEFesteMaMaiParoleDelTestoLetto() = runTest {
    // Il motore (per errore, o con un vocabolario che contiene anche i nascosti, D29) propone una parola del testo letto: non arriva in alto.
    suggestions.completions = { listOf(Suggestion.Term("segretissimo"), Suggestion.Term("agosto-da-motore")) }
    val c = controller()
    c.onQueryChange("fe")
    advanceTimeBy(COMPLETION + 10)
    runCurrent()
    assertThat(c.topSuggestions.value.map { it.label }).containsExactly("Febbraio", "Ferragosto").inOrder()
  }

  @Test
  fun senzaCompletamentiLaBarraInAltoSiSvuota() = runTest {
    suggestions.idle = IdleSuggestions(listOf(recent(1, "mare")), emptyList(), emptyList())
    val c = controller()
    assertThat(c.topSuggestions.value).isNotEmpty()
    c.onQueryChange("zzz")
    advanceTimeBy(COMPLETION + 10)
    runCurrent()
    assertThat(c.topSuggestions.value).isEmpty()
    // Svuotando il campo tornano quelli a riposo.
    c.onQueryChange("")
    runCurrent()
    assertThat(c.topSuggestions.value.map { it.label }).containsExactly("mare")
  }

  // --- Scrivere e' cercare -----------------------------------------------------------------------------------------------------------

  @Test
  fun scrivendoSiCercaDopoLaPausaSenzaInviareESoloPerPertinenza() = runTest {
    val c = controller()
    c.onQueryChange("video di agosto")
    runCurrent()
    assertThat(engine.requests).isEmpty()
    assertThat(c.state.value).isEqualTo(SearchScreenState.Idle)
    advanceTimeBy(SEARCH + 10)
    runCurrent()
    assertThat(engine.requests.single().text).isEqualTo("video di agosto")
    assertThat(engine.requests.single().order).isEqualTo(ResultOrder.RELEVANT)
    assertThat(c.results.confident.size).isEqualTo(5)
    // Una ricerca mentre si scrive non entra fra le recenti: lo fanno «Cerca», un suggerimento, un risultato aperto.
    assertThat(history.entries.value).isEmpty()
  }

  @Test
  fun scrivendoVeloceSiCercaUnaVoltaSolaAllaFine() = runTest {
    val c = controller()
    for (text in listOf("m", "ma", "mar", "mare")) {
      c.onQueryChange(text)
      advanceTimeBy(SEARCH / 4)
      runCurrent()
    }
    advanceTimeBy(SEARCH + 10)
    runCurrent()
    assertThat(engine.requests.map { it.text }).containsExactly("mare")
  }

  @Test
  fun unaLetteraSolaNonCercaEDietroTornaLaLibreria_E_Q1() = runTest {
    val c = controller()
    type(c, "mare")
    assertThat(c.state.value).isInstanceOf(SearchScreenState.Results::class.java)
    type(c, "m")
    assertThat(c.state.value).isEqualTo(SearchScreenState.Idle)
    c.onSubmit()
    runCurrent()
    assertThat(engine.requests.map { it.text }).containsExactly("mare")
  }

  @Test
  fun mentreIlMotoreLavoraRestaCioCheCeraFinoAlNuovo() = runTest {
    engine.script = { request -> if (request.text == "mare") lib.results(confident = 0..4) else lib.results(confident = 10..12) }
    val c = controller()
    type(c, "mare")
    val first = c.results
    c.onQueryChange("mare d")
    advanceTimeBy(SEARCH / 2)
    runCurrent()
    // Nessuno stato "sto cercando": in vista restano i risultati di prima.
    assertThat(c.state.value).isSameInstanceAs(first)
    advanceTimeBy(SEARCH)
    runCurrent()
    assertThat(c.results.confident.size).isEqualTo(3)
  }

  @Test
  fun gliStessiRisultatiNonSiRipubblicano() = runTest {
    engine.script = { lib.results(confident = 0..4) }
    val c = controller()
    type(c, "mare")
    val first = c.state.value
    type(c, "mare ")
    type(c, "mare e")
    // La griglia non si ricompone: lo stato e' lo stesso oggetto.
    assertThat(c.state.value).isSameInstanceAs(first)
  }

  @Test
  fun cancellandoIlTestoDietroTornaLaLibreria() = runTest {
    val c = controller()
    type(c, "mare")
    c.onQueryChange("")
    runCurrent()
    assertThat(c.state.value).isEqualTo(SearchScreenState.Idle)
  }

  @Test
  fun nessunRisultatoEUnaRigaSolaConIlTestoCercato_E_Q3() = runTest {
    engine.script = { lib.results(confident = IntRange.EMPTY) }
    val c = controller()
    type(c, "zebre")
    assertThat(c.state.value).isEqualTo(SearchScreenState.NoResults("zebre", emptyList()))
  }

  @Test
  fun iMenoProbabiliArrivanoInCodaSenzaChiederli_E_Q12() = runTest {
    engine.script = { lib.results(confident = 0..2, likely = 10..13) }
    val c = controller()
    type(c, "mare")
    assertThat(c.results.likely.size).isEqualTo(4)
    // Solo sicuri e meno probabili: il motore li da' da se'.
    assertThat(engine.requests.single().showLikely).isFalse()
  }

  // --- Cronologia, suggerimenti scelti, risultati aperti ------------------------------------------------------------------------------

  @Test
  fun cercaSullaTastieraRicordaLaRicercaUnaVoltaSola() = runTest {
    val c = controller()
    type(c, "video di agosto")
    c.onSubmit()
    runCurrent()
    // La ricerca era gia' quella in vista: non si rifa', si ricorda.
    assertThat(engine.requests).hasSize(1)
    assertThat(history.entries.value.map { it.text }).containsExactly("video di agosto")
    c.onSubmit()
    c.onResultOpened()
    runCurrent()
    assertThat(history.entries.value).hasSize(1)
  }

  @Test
  fun unSuggerimentoDiventaIlTestoCercatoSubitoESiRicorda() = runTest {
    val c = controller()
    c.onSuggestion(Suggestion.PlaceSuggestion(roma))
    runCurrent()
    assertThat(c.query.value).isEqualTo("Roma")
    assertThat(engine.requests.single().text).isEqualTo("Roma")
    assertThat(history.entries.value.map { it.text }).containsExactly("Roma")
  }

  @Test
  fun aprireUnRisultatoRicordaLaRicercaCheLHaTrovato() = runTest {
    val c = controller()
    type(c, "mare")
    c.onResultOpened()
    runCurrent()
    assertThat(history.entries.value.map { it.text }).containsExactly("mare")
  }

  @Test
  fun chiudendoIlCampoSiSvuotaEDietroTornaLaLibreria() = runTest {
    suggestions.idle = IdleSuggestions(listOf(recent(1, "mare")), emptyList(), emptyList())
    val c = controller()
    type(c, "montagna")
    c.clear()
    runCurrent()
    assertThat(c.query.value).isEmpty()
    assertThat(c.state.value).isEqualTo(SearchScreenState.Idle)
    assertThat(c.topSuggestions.value.map { it.label }).containsExactly("mare")
  }

  // --- Errori, Libreria vuota, risultati vivi -----------------------------------------------------------------------------------------

  @Test
  fun unErroreInternoSiDiceESiRiprova() = runTest {
    engine.failure = IllegalStateException("indice rotto")
    val c = controller()
    type(c, "mare")
    assertThat(c.state.value).isEqualTo(SearchScreenState.Unavailable(SearchUnavailableReason.INTERNAL_ERROR))
    engine.failure = null
    c.onRetry()
    runCurrent()
    assertThat(c.state.value).isInstanceOf(SearchScreenState.Results::class.java)
    assertThat(engine.requests.map { it.text }).containsExactly("mare", "mare")
  }

  /** CE-11: un'eccezione di cancellazione che viene dal motore (motore fermo, indice non aperto) non lascia la ricerca appesa. */
  @Test
  fun unaCancellazioneDelMotoreDiventaUnErrore_CE11() = runTest {
    engine.failure = kotlinx.coroutines.CancellationException("Ricerca ferma.")
    val c = controller()
    type(c, "mare")
    assertThat(c.state.value).isEqualTo(SearchScreenState.Unavailable(SearchUnavailableReason.INTERNAL_ERROR))
    engine.failure = null
    type(c, "mare e monti")
    assertThat(c.state.value).isInstanceOf(SearchScreenState.Results::class.java)
  }

  @Test
  fun unaLibreriaVuotaOSenzaPermessiNonSiCerca_E_Poi_SiRiprende() = runTest {
    val empty = FakeSearchData(null)
    val c = SearchController(engine, suggestions, history, empty, models, backgroundScope, COMPLETION, SEARCH)
    runCurrent()
    assertThat(c.state.value).isEqualTo(SearchScreenState.Idle)
    empty.snapshot.value = EmptyTimelineSnapshot
    runCurrent()
    assertThat(c.state.value).isEqualTo(SearchScreenState.Unavailable(SearchUnavailableReason.EMPTY_LIBRARY))
    empty.snapshot.value = lib.snapshot
    runCurrent()
    assertThat(c.state.value).isEqualTo(SearchScreenState.Idle)
  }

  @Test
  fun unMediaNascostoAltroveEsceDaiRisultatiInVista_E_Q13() = runTest {
    engine.script = { lib.results(confident = 0..4) }
    val c = controller()
    type(c, "mare")
    assertThat(c.results.confident.size).isEqualTo(5)
    data.hiddenIds += lib.id(2)
    data.snapshot.value = lib.snapshot.withFlags(0, lib.snapshot.flagsAt(0))
    runCurrent()
    val ids = (0 until c.results.confident.size).map { c.results.confident.snapshot.idAt(c.results.confident.snapshotIndex(it)) }
    assertThat(ids).doesNotContain(lib.id(2))
    assertThat(ids).hasSize(4)
  }

  @Test
  fun leParoleNelleFotoSpenteSiDiconoUnaVoltaSola_D31() = runTest {
    engine.script = { request -> lib.results(confident = if (request.text == "scontrino") 0..4 else 5..9, notices = listOf(SearchNotice.TextReadingOff)) }
    val c = controller()
    type(c, "scontrino")
    assertThat(c.results.notices).containsExactly(SearchNotice.TextReadingOff)
    type(c, "fattura")
    assertThat(c.results.notices).isEmpty()
  }

  // --- Trova simili -------------------------------------------------------------------------------------------------------------------

  @Test
  fun trovaSimiliCercaPerIlMediaColCampoVuotoENonEntraFraLeRecenti() = runTest {
    val c = controller()
    type(c, "mare")
    c.findSimilar(lib.id(3))
    runCurrent()
    assertThat(engine.similarCalls.single()).isEqualTo(lib.id(3) to ResultOrder.RELEVANT)
    assertThat(c.query.value).isEmpty()
    assertThat(c.results.similar).isEqualTo(1)
    c.onResultOpened()
    runCurrent()
    assertThat(history.entries.value).isEmpty()
  }

  @Test
  fun trovaSimiliDallaSelezioneFaUnaRichiestaSolaConTuttaLaScelta_TS4() = runTest {
    val c = controller()
    val chosen = listOf(lib.id(1), lib.id(4), lib.id(7))
    c.findSimilar(chosen)
    runCurrent()
    assertThat(engine.similarSetCalls.single()).isEqualTo(chosen to ResultOrder.RELEVANT)
    assertThat(engine.similarCalls).isEmpty()
    assertThat(c.results.similar).isEqualTo(3)
    // Una selezione vuota non cerca niente.
    c.findSimilar(emptyList())
    runCurrent()
    assertThat(engine.similarSetCalls).hasSize(1)
  }

  @Test
  fun scrivendoDopoITrovaSimiliSiCercaIlTesto() = runTest {
    val c = controller()
    c.findSimilar(lib.id(3))
    runCurrent()
    type(c, "cane")
    assertThat(engine.requests.single().text).isEqualTo("cane")
    assertThat(c.results.similar).isNull()
  }

  // --- Il contenuto che arriva dopo --------------------------------------------------------------------------------------------------

  /** CE-8: finito lo scaricamento del modello la ricerca che era uscita "solo filtri e testo" si rifa' da sola, senza far sparire cio' che si vede. */
  @Test
  fun quandoIlModelloDiventaProntoLaRicercaRipartePerConto_CE8() = runTest {
    val model = ModelCatalog.QUERY_270M.id
    engine.script = {
      val ready = models.states.value[model] is ModelState.Ready
      lib.results(notices = if (ready) emptyList() else listOf(SearchNotice.OnlyFiltersAndText))
    }
    val c = controller()
    type(c, "mare")
    assertThat(c.results.notices).containsExactly(SearchNotice.OnlyFiltersAndText)
    models.set(model, ModelState.Fetching(ModelSourceKind.HTTPS, 10, 100))
    runCurrent()
    assertThat(engine.requests).hasSize(1)
    models.set(model, ModelState.Ready(100, ModelSourceKind.HTTPS, 1))
    runCurrent()
    assertThat(engine.requests).hasSize(2)
    assertThat(c.results.notices).isEmpty()
  }

  /** CE-9: il motore delle query si scalda all'apertura della ricerca, e a motore pronto la ricerca uscita senza contenuto si rifa'. */
  @Test
  fun allAperturaIlMotoreSiScaldaEAMotoreProntoLaRicercaSiRifa_CE9() = runTest {
    engine.readiness.value = SearchReadiness.PreparingContent
    engine.script = {
      lib.results(notices = if (engine.readiness.value == SearchReadiness.Ready) emptyList() else listOf(SearchNotice.PreparingContentSearch))
    }
    val c = controller()
    c.onOpened()
    assertThat(engine.warmUps).isEqualTo(1)
    type(c, "mare")
    assertThat(c.results.notices).containsExactly(SearchNotice.PreparingContentSearch)
    engine.readiness.value = SearchReadiness.Ready
    runCurrent()
    assertThat(engine.requests).hasSize(2)
    assertThat(c.results.notices).isEmpty()
  }

  private companion object {
    const val COMPLETION = 100L
    const val SEARCH = 200L
  }
}
