package dev.pampa.mycloudgallery.core.search.noop

import dev.pampa.mycloudgallery.core.library.index.IndexOrderedTimelineView
import dev.pampa.mycloudgallery.core.library.noop.EmptyTimelineSnapshot
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.search.contract.HistoryEntry
import dev.pampa.mycloudgallery.core.search.contract.IdleSuggestions
import dev.pampa.mycloudgallery.core.search.contract.MediaText
import dev.pampa.mycloudgallery.core.search.contract.ResultOrder
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import dev.pampa.mycloudgallery.core.search.contract.SearchCoverage
import dev.pampa.mycloudgallery.core.search.contract.SearchEngine
import dev.pampa.mycloudgallery.core.search.contract.SearchHistory
import dev.pampa.mycloudgallery.core.search.contract.SearchReadiness
import dev.pampa.mycloudgallery.core.search.contract.SearchRequest
import dev.pampa.mycloudgallery.core.search.contract.SearchResults
import dev.pampa.mycloudgallery.core.search.contract.Suggestion
import dev.pampa.mycloudgallery.core.search.contract.Suggestions
import dev.pampa.mycloudgallery.core.search.contract.TextLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// I null-object della ricerca (Fase 6, P0): falliscono CHIUSI. Nessun risultato (mai qualcosa che non si dovrebbe vedere), nessun
// suggerimento, nessuna cronologia salvata, nessun testo letto.

/** Nessun risultato. */
object NoSearchEngine : SearchEngine {
  private val empty = IndexOrderedTimelineView(EmptyTimelineSnapshot, IntArray(0))

  override suspend fun search(request: SearchRequest): SearchResults = SearchResults(emptyList(), empty, empty, SearchCoverage.Complete, emptyList(), request.order)

  override suspend fun similar(media: MediaId, order: ResultOrder): SearchResults =
    SearchResults(emptyList(), empty, empty, SearchCoverage.Complete, emptyList(), order)

  override val coverage: StateFlow<SearchCoverage> = MutableStateFlow(SearchCoverage.Complete)

  override val readiness: StateFlow<SearchReadiness> = MutableStateFlow(SearchReadiness.FiltersAndTextOnly(modelMissing = true))
}

object NoSearchHistory : SearchHistory {
  override val entries: StateFlow<List<HistoryEntry>> = MutableStateFlow(emptyList())

  override suspend fun record(text: String, chips: List<SearchChip>) = Unit

  override suspend fun delete(id: Long) = Unit

  override suspend fun clear() = Unit

  override suspend fun purge(names: Collection<String>): Int = 0
}

object NoSuggestions : Suggestions {
  override suspend fun idle(): IdleSuggestions = IdleSuggestions(emptyList(), emptyList(), emptyList())

  override suspend fun complete(prefix: String): List<Suggestion> = emptyList()
}

object NoMediaText : MediaText {
  override suspend fun layout(media: MediaId): TextLayout? = null
}
