package dev.pampa.mycloudgallery.feature.search

import androidx.compose.runtime.Composable
import dev.pampa.mycloudgallery.core.models.contract.ModelState
import dev.pampa.mycloudgallery.core.search.contract.CoverageReason
import dev.pampa.mycloudgallery.core.search.contract.IdleSuggestions
import dev.pampa.mycloudgallery.core.search.contract.PlaceLevel
import dev.pampa.mycloudgallery.core.search.contract.PlaceOption
import dev.pampa.mycloudgallery.core.search.contract.ResultOrder
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import dev.pampa.mycloudgallery.core.search.contract.SearchCoverage
import dev.pampa.mycloudgallery.core.search.contract.SearchMediaType
import dev.pampa.mycloudgallery.core.search.contract.SearchNotice
import dev.pampa.mycloudgallery.core.search.contract.SearchReadiness
import dev.pampa.mycloudgallery.core.search.contract.Suggestion
import dev.pampa.mycloudgallery.feature.search.contract.SearchScreenState
import dev.pampa.mycloudgallery.feature.search.contract.SearchUnavailableReason
import dev.pampa.mycloudgallery.feature.search.models.NoSearchSettingsHandlers
import dev.pampa.mycloudgallery.feature.search.models.SearchSettingsScreen
import dev.pampa.mycloudgallery.feature.search.models.TextReadingActivationScreen
import dev.pampa.mycloudgallery.feature.search.testing.SearchSamples
import java.time.LocalDate

// Le SCENE degli screenshot di Fase 6, P8 (docs/screenshots/6-P8): ogni schermata e ogni stato che l'utente deve poter rivedere. Solo dati finti e miniature
// sintetiche, nessun dato personale. Le scene "Cerca" stanno in una pagina da telefono, le "pagine" (impostazioni e attivazione) in una piu' alta.

/** Una scena degli screenshot: [file] e' il nome base del PNG; [tall] le vuole in una pagina alta (impostazioni, attivazione). */
internal enum class Scene(val file: String, val tall: Boolean) {
  IDLE("cerca-a-riposo", false),
  TYPING("cerca-scrivendo", false),
  RESULTS_RECENT("cerca-risultati-recenti", false),
  RESULTS_RELEVANT("cerca-risultati-pertinenti-meno-probabili", false),
  NO_RESULTS("cerca-nessun-risultato", false),
  NOTICES("cerca-avvisi-modello-lettura-aree", false),
  ERROR("cerca-errore", false),
  SETTINGS("ricerca-e-modelli", true),
  SETTINGS_PROBLEMS("ricerca-e-modelli-problemi", true),
  ACTIVATION("attivazione-lettura-del-testo", true),
}

internal object SearchScenes {
  private val lib = library(count = 400, seed = 11L)
  private val today = LocalDate.of(2026, 10, 21)
  private val thumbs = TestThumbnails(lib)
  private val roma = PlaceOption(setOf(1), "Roma", PlaceLevel.PLACE)
  private val texas = PlaceOption(setOf(2), "Roma (Texas)", PlaceLevel.PLACE)
  private val sardegna = PlaceOption(setOf(3), "Sardegna", PlaceLevel.REGION)

  private val agosto = SearchChip.DateRange(LocalDate.of(2025, 8, 1), LocalDate.of(2025, 8, 31), everyYear = false, label = "Agosto 2025")
  private val place = SearchChip.Place(sardegna, emptyList(), "sardegna")
  private val rome = SearchChip.Place(roma, listOf(texas), "roma")
  private val video = SearchChip.MediaType(SearchMediaType.VIDEO, "Video")
  private val word = SearchChip.Word("scontrino", exact = false)

  private val idle = IdleSuggestions(
    recents = listOf(recent(1, "mare ad agosto", "Agosto"), recent(2, "scontrino farmacia", "scontrino"), recent(3, "compleanno di Anna", "Foto")),
    places = listOf(roma, sardegna, PlaceOption(setOf(4), "Milano", PlaceLevel.PLACE), PlaceOption(setOf(5), "Toscana", PlaceLevel.REGION)),
    types = listOf(SearchMediaType.SCREENSHOT, SearchMediaType.VIDEO, SearchMediaType.DOCUMENT, SearchMediaType.PANORAMA),
  )

  private fun results(order: ResultOrder, chips: List<SearchChip>, showLikely: Boolean, notices: List<SearchNotice>, coverage: SearchCoverage): SearchScreenState.Results {
    val r = lib.results(confident = 0..47, likely = 120..131, chips = chips, order = order, notices = notices, coverage = coverage)
    return SearchScreenState.Results(r.chips, order, r.confident, r.likely, 12, showLikely, coverage, notices)
  }

  private fun state(scene: Scene): SearchScreenState = when (scene) {
    Scene.IDLE -> SearchScreenState.Idle(idle, idle.recents)
    Scene.TYPING -> SearchScreenState.Typing("ag", listOf(Suggestion.Term("Agosto"), Suggestion.Recent(idle.recents[0]), Suggestion.PlaceSuggestion(PlaceOption(setOf(6), "Agrigento", PlaceLevel.PLACE))))
    Scene.RESULTS_RECENT -> results(
      ResultOrder.RECENT, listOf(agosto, place), false,
      listOf(SearchNotice.TypoCorrected("sardegna", "sardegan")),
      SearchCoverage.Partial(12_300, 15_000, mapOf(CoverageReason.PREPARATION to 2_700)),
    )
    Scene.RESULTS_RELEVANT -> results(ResultOrder.RELEVANT, listOf(rome, video, word), true, emptyList(), SearchCoverage.Complete)
    Scene.NO_RESULTS -> SearchScreenState.NoResults(listOf(agosto, SearchChip.Word("zebre", exact = false)), 0, listOf(agosto))
    Scene.NOTICES -> results(
      ResultOrder.RECENT, listOf(word), false,
      listOf(SearchNotice.OnlyFiltersAndText, SearchNotice.NoIndexerForArea(commonArea = true), SearchNotice.TextReadingOff),
      SearchCoverage.ModelMissing(164_626_432L),
    )
    Scene.ERROR -> SearchScreenState.Unavailable(SearchUnavailableReason.INTERNAL_ERROR)
    Scene.SETTINGS, Scene.SETTINGS_PROBLEMS, Scene.ACTIVATION -> SearchScreenState.Idle(idle, idle.recents)
  }

  private fun query(scene: Scene): String = when (scene) {
    Scene.TYPING -> "ag"
    Scene.RESULTS_RECENT -> "sardegna ad agosto"
    Scene.RESULTS_RELEVANT -> "roma video scontrino"
    Scene.NO_RESULTS -> "zebre agosto"
    Scene.NOTICES -> "scontrino"
    else -> ""
  }

  @Composable
  fun Content(scene: Scene) {
    when (scene) {
      Scene.SETTINGS -> SearchSettingsScreen(SearchSamples.settingsIndexer, false, true, true, true, NoSearchSettingsHandlers, onBack = {})
      Scene.SETTINGS_PROBLEMS -> SearchSettingsScreen(SearchSamples.settingsProblems, false, true, true, true, NoSearchSettingsHandlers, onBack = {})
      Scene.ACTIVATION -> TextReadingActivationScreen(onActivate = {}, onNotNow = {}, onBack = {})
      else -> SearchScreen(
        state = state(scene),
        query = query(scene),
        readiness = if (scene == Scene.NOTICES) SearchReadiness.FiltersAndTextOnly(modelMissing = true) else SearchReadiness.Ready,
        queryModel = null as ModelState?,
        today = today,
        thumbnails = thumbs.thumbnails,
        handlers = NoSearchHandlers,
      )
    }
  }
}
