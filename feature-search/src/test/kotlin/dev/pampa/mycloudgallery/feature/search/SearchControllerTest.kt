package dev.pampa.mycloudgallery.feature.search

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.noop.EmptyTimelineSnapshot
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.ModelSourceKind
import dev.pampa.mycloudgallery.core.models.contract.ModelState
import dev.pampa.mycloudgallery.core.models.contract.ModelTrigger
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
import dev.pampa.mycloudgallery.core.search.testing.FixedToday
import dev.pampa.mycloudgallery.core.search.testing.InMemorySearchHistory
import dev.pampa.mycloudgallery.feature.search.contract.SearchScreenState
import dev.pampa.mycloudgallery.feature.search.contract.SearchUnavailableReason
import dev.pampa.mycloudgallery.feature.search.noop.MemorySearchOrderStore
import java.time.LocalDate
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Il cervello di Cerca (Fase 6, P8, §1.5, §2.6): gli stati, i tocchi e le risposte del motore. */
class SearchControllerTest {

  private val lib: SyntheticLibrary = library()
  private val engine = ScriptedEngine { lib.results(chips = listOf(SearchChip.MediaType(SearchMediaType.VIDEO, "Video"))) }
  private val suggestions = FakeSuggestions()
  private val history = InMemorySearchHistory()
  private val data = FakeSearchData(lib.snapshot)
  private val orderStore = MemorySearchOrderStore()
  private val models = FakeModelManager()
  private val indexer = FakeIndexerControl()
  private val today = FixedToday(LocalDate.of(2026, 10, 21))

  private fun TestScope.controller(): SearchController =
    SearchController(engine, suggestions, history, data, today, orderStore, models, indexer, backgroundScope, debounceMillis = 100).also { runCurrent() }

  private val SearchController.results get() = state.value as SearchScreenState.Results

  @Test
  fun aRiposoCeLaCronologiaFinoADieciConLeRicercheRecenti() = runTest {
    suggestions.idle = IdleSuggestions((1L..12L).map { recent(it, "ricerca $it") }, emptyList(), listOf(SearchMediaType.VIDEO))
    val c = controller()
    val idle = c.state.value as SearchScreenState.Idle
    assertThat(idle.recents).hasSize(10)
    assertThat(idle.suggestions.types).containsExactly(SearchMediaType.VIDEO)
  }

  @Test
  fun mentreSiScriveSiAspettaLaPausaEPoiArrivanoICompletamenti() = runTest {
    suggestions.completions = { listOf(Suggestion.Type(SearchMediaType.SCREENSHOT, "Screenshot")) }
    val c = controller()
    c.onQueryChange("scr")
    runCurrent()
    assertThat((c.state.value as SearchScreenState.Typing).completions).isEmpty()
    advanceTimeBy(150)
    runCurrent()
    val typing = c.state.value as SearchScreenState.Typing
    assertThat(typing.text).isEqualTo("scr")
    assertThat(typing.completions.map { it.label }).containsExactly("Screenshot")
    assertThat(suggestions.completeCalls).containsExactly("scr")
  }

  @Test
  fun iCompletamentiHannoMesiEFesteMaMaiParoleDelTestoLetto() = runTest {
    // Il motore (per errore, o con un vocabolario che contiene anche i nascosti, D29) propone una parola del testo letto: non arriva allo schermo.
    suggestions.completions = { listOf(Suggestion.Term("segretissimo"), Suggestion.Term("agosto-da-motore")) }
    val c = controller()
    c.onQueryChange("ag")
    advanceTimeBy(150)
    runCurrent()
    val labels = (c.state.value as SearchScreenState.Typing).completions.map { it.label }
    assertThat(labels).containsExactly("Agosto")
    c.onQueryChange("fe")
    advanceTimeBy(150)
    runCurrent()
    assertThat((c.state.value as SearchScreenState.Typing).completions.map { it.label }).containsExactly("Febbraio", "Ferragosto").inOrder()
  }

  @Test
  fun cancellandoITestoSiTornaARiposo() = runTest {
    val c = controller()
    c.onQueryChange("mare")
    runCurrent()
    c.onQueryChange("")
    runCurrent()
    assertThat(c.state.value).isInstanceOf(SearchScreenState.Idle::class.java)
  }

  @Test
  fun unaLetteraSolaNonEUnaRicerca_E_Q1() = runTest {
    val c = controller()
    c.onQueryChange("m")
    c.onSubmit()
    runCurrent()
    assertThat(engine.requests).isEmpty()
    assertThat(c.state.value).isInstanceOf(SearchScreenState.Typing::class.java)
  }

  @Test
  fun inviandoSiCercaESiRicordaLaRicercaUnaVoltaSola() = runTest {
    val c = controller()
    c.onQueryChange("video di agosto")
    c.onSubmit()
    runCurrent()
    assertThat(engine.requests.single().text).isEqualTo("video di agosto")
    assertThat(c.results.confident.size).isEqualTo(5)
    assertThat(history.entries.value.map { it.text }).containsExactly("video di agosto")
    // Cambiare l'ordine rifa' la ricerca ma non la ricorda un'altra volta.
    c.onSetOrder(ResultOrder.RELEVANT)
    runCurrent()
    assertThat(history.entries.value).hasSize(1)
  }

  @Test
  fun lOrdineSiRicordaESiApplicaSubito_D48() = runTest {
    engine.script = { request -> lib.results(order = request.order) }
    val c = controller()
    c.onQueryChange("mare")
    c.onSubmit()
    runCurrent()
    assertThat(c.results.order).isEqualTo(ResultOrder.RECENT)
    c.onSetOrder(ResultOrder.RELEVANT)
    runCurrent()
    assertThat(orderStore.order.value).isEqualTo(ResultOrder.RELEVANT)
    assertThat(engine.requests.last().order).isEqualTo(ResultOrder.RELEVANT)
    assertThat(c.results.order).isEqualTo(ResultOrder.RELEVANT)
    // Una ricerca nuova parte gia' con l'ordine ricordato.
    c.onQueryChange("monti")
    c.onSubmit()
    runCurrent()
    assertThat(engine.requests.last().order).isEqualTo(ResultOrder.RELEVANT)
  }

  @Test
  fun nessunRisultatoSicuroMaQualcheMenoProbabile_E_Q12() = runTest {
    engine.script = { lib.results(confident = IntRange.EMPTY, likely = 10..14, chips = listOf(SearchChip.MediaType(SearchMediaType.VIDEO, "Video"))) }
    val c = controller()
    c.onQueryChange("zebre")
    c.onSubmit()
    runCurrent()
    val none = c.state.value as SearchScreenState.NoResults
    assertThat(none.likelyCount).isEqualTo(5)
    c.onShowLikely()
    val shown = c.results
    assertThat(shown.showLikely).isTrue()
    assertThat(shown.confident.size).isEqualTo(0)
    assertThat(shown.likely.size).isEqualTo(5)
  }

  @Test
  fun nessunRisultatoPropostaDiTogliereUnFiltro_E_Q3() = runTest {
    val chip = SearchChip.MediaType(SearchMediaType.VIDEO, "Video")
    engine.script = { lib.results(confident = IntRange.EMPTY, chips = listOf(chip, SearchChip.Word("zebre", exact = false))) }
    val c = controller()
    c.onQueryChange("zebre video")
    c.onSubmit()
    runCurrent()
    val none = c.state.value as SearchScreenState.NoResults
    assertThat(none.likelyCount).isEqualTo(0)
    assertThat(none.alternatives).containsExactly(chip)
    engine.script = { lib.results(chips = emptyList()) }
    c.onRemoveChip(chip)
    runCurrent()
    assertThat(engine.requests.last().editedChips).isEqualTo(listOf(SearchChip.Word("zebre", exact = false)))
  }

  @Test
  fun togliereLUltimoChipRiportaARiposo() = runTest {
    val chip = SearchChip.MediaType(SearchMediaType.VIDEO, "Video")
    engine.script = { lib.results(chips = listOf(chip)) }
    val c = controller()
    c.onQueryChange("video")
    c.onSubmit()
    runCurrent()
    c.onRemoveChip(chip)
    runCurrent()
    assertThat(c.state.value).isInstanceOf(SearchScreenState.Idle::class.java)
    assertThat(c.query.value).isEmpty()
  }

  @Test
  fun unLuogoSiCambiaConUnAlternativaOSiCercaComeParola_E_Q10() = runTest {
    val roma = PlaceOption(setOf(1), "Roma", PlaceLevel.PLACE)
    val roma2 = PlaceOption(setOf(2), "Roma (Texas)", PlaceLevel.PLACE)
    val place = SearchChip.Place(roma, listOf(roma2), "roma")
    engine.script = { lib.results(chips = listOf(place)) }
    val c = controller()
    c.onQueryChange("roma")
    c.onSubmit()
    runCurrent()
    c.onChooseAlternative(place, roma2)
    runCurrent()
    val swapped = engine.requests.last().editedChips!!.single() as SearchChip.Place
    assertThat(swapped.option).isEqualTo(roma2)
    assertThat(swapped.alternatives).containsExactly(roma)
    c.onPlaceAsWord(place)
    runCurrent()
    assertThat(engine.requests.last().editedChips).containsExactly(SearchChip.Word("roma", exact = false))
  }

  @Test
  fun unErroreInternoSiDiceESiRiprova() = runTest {
    engine.failure = IllegalStateException("indice rotto")
    val c = controller()
    c.onQueryChange("mare")
    c.onSubmit()
    runCurrent()
    assertThat(c.state.value).isEqualTo(SearchScreenState.Unavailable(SearchUnavailableReason.INTERNAL_ERROR))
    engine.failure = null
    c.onRetry()
    runCurrent()
    assertThat(c.state.value).isInstanceOf(SearchScreenState.Results::class.java)
    assertThat(engine.requests.map { it.text }).containsExactly("mare", "mare")
  }

  @Test
  fun unaLibreriaVuotaOSenzaPermessiNonSiCerca_E_Poi_SiRiprende() = runTest {
    val empty = FakeSearchData(null)
    val c = SearchController(engine, suggestions, history, empty, today, orderStore, models, indexer, backgroundScope, 100)
    runCurrent()
    assertThat(c.state.value).isInstanceOf(SearchScreenState.Idle::class.java)
    empty.snapshot.value = EmptyTimelineSnapshot
    runCurrent()
    assertThat(c.state.value).isEqualTo(SearchScreenState.Unavailable(SearchUnavailableReason.EMPTY_LIBRARY))
    empty.snapshot.value = lib.snapshot
    runCurrent()
    assertThat(c.state.value).isInstanceOf(SearchScreenState.Idle::class.java)
  }

  @Test
  fun unMediaNascostoAltroveEsceDaiRisultatiAperti_E_Q13() = runTest {
    engine.script = { lib.results(confident = 0..4) }
    val c = controller()
    c.onQueryChange("mare")
    c.onSubmit()
    runCurrent()
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
    engine.script = { lib.results(notices = listOf(SearchNotice.TextReadingOff)) }
    val c = controller()
    c.onQueryChange("scontrino")
    c.onSubmit()
    runCurrent()
    assertThat(c.results.notices).containsExactly(SearchNotice.TextReadingOff)
    c.onQueryChange("fattura")
    c.onSubmit()
    runCurrent()
    assertThat(c.results.notices).isEmpty()
  }

  @Test
  fun trovaSimiliPartePerIlMediaEMostraIlChip() = runTest {
    val similar = SearchChip.Similar(lib.id(3), "Simili")
    engine.similarScript = { _, order -> lib.results(chips = listOf(similar), order = order) }
    val c = controller()
    c.findSimilar(lib.id(3))
    runCurrent()
    assertThat(engine.similarCalls).containsExactly(lib.id(3) to ResultOrder.RECENT)
    assertThat(c.results.chips).containsExactly(similar)
    // Una ricerca "simili" non entra nella cronologia.
    assertThat(history.entries.value).isEmpty()
    // Togliere il chip "Simili a" riporta a riposo.
    c.onRemoveChip(similar)
    runCurrent()
    assertThat(c.state.value).isInstanceOf(SearchScreenState.Idle::class.java)
  }

  @Test
  fun scaricareIlModelloEUnaRichiestaDellUtente_E_Q11() = runTest {
    val c = controller()
    c.onDownloadModel()
    runCurrent()
    assertThat(models.ensured).containsExactly(ModelCatalog.QUERY_270M.id to ModelTrigger.USER_NOW)
  }

  @Test
  fun attivareLIndicizzatoreDaCercaEUnaRichiesta_E_I12() = runTest {
    val c = controller()
    c.onEnableIndexerHere()
    runCurrent()
    assertThat(indexer.changes).containsExactly(true)
  }

  @Test
  fun leRicercheRecentiSiCancellanoUnaAlla_VoltaOTutte_Q5() = runTest {
    history.record("mare", emptyList())
    history.record("monti", emptyList())
    suggestions.idle = IdleSuggestions(history.entries.value, emptyList(), emptyList())
    val c = controller()
    val first = history.entries.value.first()
    c.onDeleteRecent(first)
    runCurrent()
    assertThat(history.entries.value.map { it.text }).containsExactly("mare")
    c.onClearRecents()
    runCurrent()
    assertThat(history.entries.value).isEmpty()
  }

  @Test
  fun unaRicercaRecenteSiRifaSenzaRicordarlaDiNuovo() = runTest {
    history.record("mare", listOf())
    val c = controller()
    c.onRecent(history.entries.value.single())
    runCurrent()
    assertThat(engine.requests.single().text).isEqualTo("mare")
    assertThat(history.entries.value).hasSize(1)
  }

  // --- Correzioni dopo le revisioni, pacchetto C -----------------------------------------------------------------------------------

  private val yesterday = SearchChip.DateRange(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 20), everyYear = false, label = "Ieri")

  /**
   * CE-2: "scontrino ieri": tolto l'unico chip la ricerca continua con le parole libere (il testo resta nel campo, il motore riceve la query con zero
   * chip). Prima Cerca tornava a riposo e la query spariva.
   */
  @Test
  fun togliereUnChipConParoleLibereContinuaLaRicerca_CE2() = runTest {
    engine.script = { request -> lib.results(chips = request.editedChips ?: listOf(yesterday), freeText = true) }
    val c = controller()
    c.onQueryChange("scontrino ieri")
    c.onSubmit()
    runCurrent()
    c.onRemoveChip(yesterday)
    runCurrent()
    assertThat(c.query.value).isEqualTo("scontrino ieri")
    assertThat(c.state.value).isInstanceOf(SearchScreenState.Results::class.java)
    assertThat(engine.requests.last().text).isEqualTo("scontrino ieri")
    assertThat(engine.requests.last().editedChips).isEmpty()
  }

  /** CE-11: un'eccezione di cancellazione che viene dal motore (motore fermo, indice non aperto) non lascia "Sto cercando…" per sempre. */
  @Test
  fun unaCancellazioneDelMotoreDiventaUnErrore_CE11() = runTest {
    engine.failure = kotlinx.coroutines.CancellationException("Ricerca ferma.")
    val c = controller()
    c.onQueryChange("mare")
    c.onSubmit()
    runCurrent()
    assertThat(c.state.value).isEqualTo(SearchScreenState.Unavailable(SearchUnavailableReason.INTERNAL_ERROR))
    // Una ricerca annullata davvero da una nuova (la scrittura continua) non e' un errore.
    engine.failure = null
    c.onQueryChange("mare")
    c.onSubmit()
    c.onQueryChange("mare e monti")
    c.onSubmit()
    runCurrent()
    assertThat(c.state.value).isInstanceOf(SearchScreenState.Results::class.java)
  }

  /**
   * CE-8: finito lo scaricamento del modello la ricerca che era uscita "solo filtri e testo" si rifa' da sola, senza passare da "Sto cercando…". Prima
   * l'avviso "serve il modello" restava e la ricerca non ripartiva.
   */
  @Test
  fun quandoIlModelloDiventaProntoLaRicercaRipartePerConto_CE8() = runTest {
    val model = ModelCatalog.QUERY_270M.id
    engine.script = {
      val ready = models.states.value[model] is ModelState.Ready
      lib.results(notices = if (ready) emptyList() else listOf(SearchNotice.OnlyFiltersAndText))
    }
    val c = controller()
    c.onQueryChange("mare")
    c.onSubmit()
    runCurrent()
    assertThat(c.results.notices).containsExactly(SearchNotice.OnlyFiltersAndText)

    models.set(model, ModelState.Fetching(ModelSourceKind.HTTPS, 10, 100))
    runCurrent()
    assertThat(engine.requests).hasSize(1)

    models.set(model, ModelState.Ready(100, ModelSourceKind.HTTPS, 1))
    runCurrent()
    assertThat(engine.requests).hasSize(2)
    assertThat(c.results.notices).isEmpty()
    // Senza avvisi di contenuto non c'e' niente da rifare.
    models.set(model, ModelState.Absent)
    models.set(model, ModelState.Ready(100, ModelSourceKind.HTTPS, 2))
    runCurrent()
    assertThat(engine.requests).hasSize(2)
  }

  /** CE-9: il motore delle query si scalda all'apertura di Cerca, e a motore pronto la ricerca uscita senza contenuto si rifa'. */
  @Test
  fun alleAperturaDiCercaIlMotoreSiScaldaEAMotoreProntoLaRicercaSiRifa_CE9() = runTest {
    engine.readiness.value = SearchReadiness.PreparingContent
    engine.script = {
      lib.results(notices = if (engine.readiness.value == SearchReadiness.Ready) emptyList() else listOf(SearchNotice.PreparingContentSearch))
    }
    val c = controller()
    c.onOpened()
    assertThat(engine.warmUps).isEqualTo(1)
    c.onQueryChange("mare")
    c.onSubmit()
    runCurrent()
    assertThat(c.results.notices).containsExactly(SearchNotice.PreparingContentSearch)

    engine.readiness.value = SearchReadiness.Ready
    runCurrent()
    assertThat(engine.requests).hasSize(2)
    assertThat(c.results.notices).isEmpty()
  }
}
