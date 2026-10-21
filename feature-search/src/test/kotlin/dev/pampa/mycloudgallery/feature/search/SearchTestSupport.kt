package dev.pampa.mycloudgallery.feature.search

import dev.pampa.mycloudgallery.core.ai.contract.AiStatus
import dev.pampa.mycloudgallery.core.library.index.IndexOrderedTimelineView
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.search.contract.HistoryEntry
import dev.pampa.mycloudgallery.core.search.contract.IdleSuggestions
import dev.pampa.mycloudgallery.core.search.contract.ResultOrder
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import dev.pampa.mycloudgallery.core.search.contract.SearchCoverage
import dev.pampa.mycloudgallery.core.search.contract.SearchEngine
import dev.pampa.mycloudgallery.core.search.contract.SearchNotice
import dev.pampa.mycloudgallery.core.search.contract.SearchReadiness
import dev.pampa.mycloudgallery.core.search.contract.SearchRequest
import dev.pampa.mycloudgallery.core.search.contract.SearchResults
import dev.pampa.mycloudgallery.core.search.contract.Suggestion
import dev.pampa.mycloudgallery.core.search.contract.Suggestions
import dev.pampa.mycloudgallery.feature.search.contract.AreaKind
import dev.pampa.mycloudgallery.feature.search.contract.IndexSpace
import dev.pampa.mycloudgallery.feature.search.contract.IndexerBacklog
import dev.pampa.mycloudgallery.feature.search.contract.IndexerChange
import dev.pampa.mycloudgallery.feature.search.contract.IndexerControl
import kotlinx.coroutines.flow.MutableStateFlow

// Il banco di prova di Cerca (Fase 6, P8): un motore "a copione", suggerimenti e ruolo finti, una Libreria sintetica. Solo test: mai nella release.

/** Una Libreria sintetica di [count] media (solo sorgenti locali, nessun NAS): `0` e' il piu' recente. */
internal fun library(count: Int = 60, seed: Long = 7L): SyntheticLibrary = SyntheticLibrary(seed = seed, count = count)

internal fun SyntheticLibrary.id(i: Int): MediaId = snapshot.idAt(i)

internal fun SyntheticLibrary.view(vararg indices: Int): IndexOrderedTimelineView = IndexOrderedTimelineView(snapshot, indices)

internal fun SyntheticLibrary.range(from: Int, until: Int): IndexOrderedTimelineView = IndexOrderedTimelineView(snapshot, (from until until).toList().toIntArray())

/** I risultati di una ricerca finta: [confident] e [likely] sono posizioni dell'istantanea. */
internal fun SyntheticLibrary.results(
  confident: IntRange = 0..4,
  likely: IntRange = IntRange.EMPTY,
  chips: List<SearchChip> = emptyList(),
  order: ResultOrder = ResultOrder.RECENT,
  notices: List<SearchNotice> = emptyList(),
  coverage: SearchCoverage = SearchCoverage.Complete,
): SearchResults = SearchResults(
  chips = chips,
  confident = IndexOrderedTimelineView(snapshot, confident.toList().toIntArray()),
  likely = IndexOrderedTimelineView(snapshot, likely.toList().toIntArray()),
  coverage = coverage,
  notices = notices,
  order = order,
)

/** Un motore "a copione": risponde con [script], registra le richieste e puo' guastarsi (errore interno, E-Q). */
internal class ScriptedEngine(var script: (SearchRequest) -> SearchResults) : SearchEngine {
  val requests: MutableList<SearchRequest> = mutableListOf()
  val similarCalls: MutableList<Pair<MediaId, ResultOrder>> = mutableListOf()
  var failure: Exception? = null
  var similarScript: (MediaId, ResultOrder) -> SearchResults = { _, order -> script(SearchRequest("", order = order)) }

  override val coverage: MutableStateFlow<SearchCoverage> = MutableStateFlow(SearchCoverage.Complete)
  override val readiness: MutableStateFlow<SearchReadiness> = MutableStateFlow(SearchReadiness.Ready)

  override suspend fun search(request: SearchRequest): SearchResults {
    requests += request
    failure?.let { throw it }
    return script(request)
  }

  override suspend fun similar(media: MediaId, order: ResultOrder): SearchResults {
    similarCalls += media to order
    failure?.let { throw it }
    return similarScript(media, order)
  }
}

/** Suggerimenti finti: [idle] e i completamenti per prefisso, con il conteggio delle chiamate. */
internal class FakeSuggestions(
  var idle: IdleSuggestions = IdleSuggestions(emptyList(), emptyList(), emptyList()),
  var completions: (String) -> List<Suggestion> = { emptyList() },
) : Suggestions {
  val completeCalls: MutableList<String> = mutableListOf()

  override suspend fun idle(): IdleSuggestions = idle

  override suspend fun complete(prefix: String): List<Suggestion> {
    completeCalls += prefix
    return completions(prefix)
  }
}

internal fun recent(id: Long, text: String, vararg chips: String) = HistoryEntry(id, text, chips.toList())

/** Il ruolo e lo stato del calcolo finti. */
internal class FakeIndexerControl(
  indexer: Boolean = false,
  status: AiStatus = AiStatus.Off,
  backlog: IndexerBacklog = IndexerBacklog(0, emptyList<AreaKind>()),
) : IndexerControl {
  override val indexer: MutableStateFlow<Boolean> = MutableStateFlow(indexer)
  override val status: MutableStateFlow<AiStatus> = MutableStateFlow(status)
  override val backlog: MutableStateFlow<IndexerBacklog> = MutableStateFlow(backlog)
  override val space: MutableStateFlow<IndexSpace> = MutableStateFlow(IndexSpace.NONE)
  val changes: MutableList<Boolean> = mutableListOf()
  var indexNowCalls: Int = 0
    private set
  var failure: Exception? = null

  override suspend fun setIndexer(on: Boolean): IndexerChange {
    failure?.let { throw it }
    changes += on
    if (indexer.value == on) return IndexerChange.UNCHANGED
    indexer.value = on
    return IndexerChange.SAVED
  }

  override fun indexNow() {
    indexNowCalls++
  }
}
