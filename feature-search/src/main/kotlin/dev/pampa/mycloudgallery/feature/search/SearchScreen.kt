package dev.pampa.mycloudgallery.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidChip
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.models.contract.ModelState
import dev.pampa.mycloudgallery.core.search.contract.ResultOrder
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import dev.pampa.mycloudgallery.core.search.contract.SearchReadiness
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.feature.search.contract.SearchActions
import dev.pampa.mycloudgallery.feature.search.contract.SearchScreenState
import dev.pampa.mycloudgallery.feature.search.contract.SearchThumbnails
import dev.pampa.mycloudgallery.feature.search.ui.SearchDimens
import dev.pampa.mycloudgallery.feature.search.ui.SearchHost
import java.time.LocalDate

// LA SCHERMATA CERCA (Fase 6, P8, docs/fase6-progetto.md §1.5, §2.6; D28-D35, D36, D48). Composable solo con i componenti G2 (campo, pillole, righe e
// bottoni di Fluid, cella e intestazioni del design system); il filone grafico rifinisce senza toccare la logica (§12.12).
//
// Il tocco su un risultato apre il viewer **sui risultati nell'ordine mostrato**; il ritorno riporta alla stessa posizione (lo stato della griglia
// sopravvive alla navigazione). Nessun NAS in Cerca: le miniature sono solo locali (D28, D33).

/** La schermata per lo stato dato: nessun effetto, la usano la rotta, i test e gli screenshot. */
@Composable
fun SearchScreen(
  state: SearchScreenState,
  query: String,
  readiness: SearchReadiness,
  queryModel: ModelState?,
  today: LocalDate,
  thumbnails: SearchThumbnails,
  handlers: SearchHandlers,
  modifier: Modifier = Modifier,
  gridState: LazyGridState = rememberLazyGridState(),
) {
  // Lo sfondo e' della schermata (non di chi la ospita): chiaro e scuro si vedono uguali nella scheda, nei test e negli screenshot.
  Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
      SearchField(query = query, handlers = handlers)
      Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        when (state) {
          is SearchScreenState.Idle -> SearchIdleContent(state, readiness, queryModel, handlers)
          is SearchScreenState.Typing -> SearchTypingContent(state, handlers)
          is SearchScreenState.Searching -> SearchingContent(state.chips, thumbnails, handlers)
          is SearchScreenState.Results -> SearchResultsGrid(
            results = state,
            today = today,
            thumbnails = thumbnails,
            onOpen = { media: MediaId -> handlers.onOpen(SearchGridEntries.openingView(state), media) },
            onShowLikely = handlers::onShowLikely,
            gridState = gridState,
          ) {
            ResultsHeader(state, readiness, queryModel, thumbnails, handlers)
          }
          is SearchScreenState.NoResults -> Column(verticalArrangement = Arrangement.spacedBy(SearchDimens.BlockGap)) {
            SearchChipBar(state.chips, null, thumbnails, handlers)
            SearchNoResultsState(query = query.trim(), state = state, handlers = handlers)
          }
          is SearchScreenState.Unavailable -> SearchUnavailableState(state.reason, handlers)
        }
      }
    }
  }
}

@Composable
private fun SearchField(query: String, handlers: SearchHandlers) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = GalleryDimens.Gutter, vertical = SearchDimens.TightGap),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    FluidTextField(
      value = query,
      onValueChange = handlers::onQueryChange,
      modifier = Modifier.weight(1f),
      placeholder = stringResource(R.string.search_field_hint),
      minHeight = GalleryDimens.MinTouchTarget,
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
      keyboardActions = KeyboardActions(onSearch = { handlers.onSubmit() }),
      // Il tasto "cancella" del campo e' piccolo (18-28 dp): qui ce n'e' uno proprio da 48 dp, accanto al campo.
      showClearButton = false,
      leading = { Icon(Icons.Rounded.Search, contentDescription = null) },
    )
    if (query.isNotEmpty()) {
      IconButton(onClick = { handlers.onQueryChange("") }, modifier = Modifier.size(GalleryDimens.MinTouchTarget)) {
        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.search_field_clear))
      }
    }
  }
}

@Composable
private fun SearchingContent(chips: List<SearchChip>, thumbnails: SearchThumbnails, handlers: SearchHandlers) {
  Column(verticalArrangement = Arrangement.spacedBy(SearchDimens.BlockGap)) {
    SearchChipBar(chips, null, thumbnails, handlers)
    Text(
      text = stringResource(R.string.search_searching),
      modifier = Modifier.padding(horizontal = GalleryDimens.Gutter),
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/** In testa ai risultati: la barra dei chip, il selettore "Recenti · Pertinenti" e gli avvisi (copertura, modello, refusi). */
@Composable
private fun ResultsHeader(
  results: SearchScreenState.Results,
  readiness: SearchReadiness,
  queryModel: ModelState?,
  thumbnails: SearchThumbnails,
  handlers: SearchHandlers,
) {
  val notices = remember(results, readiness, queryModel) { SearchNoticeItems.of(results.notices, results.coverage, readiness, queryModel) }
  Column(verticalArrangement = Arrangement.spacedBy(SearchDimens.BlockGap)) {
    SearchChipBar(results.chips, results.confident.snapshot, thumbnails, handlers)
    SearchOrderSelector(results.order, handlers::onSetOrder)
    SearchNotices(notices, handlers)
  }
}

/** Il selettore dell'ordine (D48, Q10): "Recenti" e "Pertinenti", ricordato e sincronizzato dal chiamante. */
@Composable
internal fun SearchOrderSelector(order: ResultOrder, onSelect: (ResultOrder) -> Unit, modifier: Modifier = Modifier) {
  val recent = stringResource(R.string.search_order_recent)
  val relevant = stringResource(R.string.search_order_relevant)
  val group = stringResource(R.string.search_order_group)
  val description = stringResource(if (order == ResultOrder.RECENT) R.string.search_order_recent_description else R.string.search_order_relevant_description)
  // Due pillole di Fluid (48 dp l'una) al posto del controllo segmentato, che e' alto 32 dp: il selettore resta un bersaglio da 48 dp (§2.6, §12.12).
  Row(
    modifier = modifier
      .padding(horizontal = GalleryDimens.Gutter)
      .fillMaxWidth()
      .semantics { contentDescription = "$group. $description" },
    horizontalArrangement = Arrangement.spacedBy(SearchDimens.TightGap),
  ) {
    for ((label, value) in listOf(recent to ResultOrder.RECENT, relevant to ResultOrder.RELEVANT)) {
      FluidChip(
        label = label,
        selected = order == value,
        onClick = { onSelect(value) },
        modifier = Modifier.semantics { role = Role.RadioButton },
      )
    }
  }
}

/** Le azioni di navigazione di Cerca: quelle del [controller] e, per le uscite, le [SearchActions] di `:app` (i feature non si conoscono). */
internal class ActionSearchHandlers(
  private val controller: SearchController,
  private val actions: SearchActions,
) : SearchHandlers by controller {
  override fun onOpen(view: OrderedTimelineView, media: MediaId) = actions.openResults(view, media)

  override fun onWhyCoverage() = actions.openSearchSettings()

  override fun onLearnTextReading() = actions.openTextReadingActivation()

  override fun onAllowAccess() = actions.requestMediaAccess()

  override fun onDiagnostics() = actions.openDiagnostics()
}

/**
 * La rotta di Cerca per `:app` (P11): il ViewModel con il motore vero, le uscite in [actions] e le miniature locali in [thumbnails]. "Trova simili" dal
 * viewer arriva con [findSimilar] (un media): la schermata parte dai suoi simili, con il chip "Simili a".
 */
@Composable
fun SearchRoute(
  actions: SearchActions,
  thumbnails: SearchThumbnails,
  modifier: Modifier = Modifier,
  findSimilar: MediaId? = null,
  viewModel: SearchViewModel = hiltViewModel(),
) {
  val controller = viewModel.controller
  val state by controller.state.collectAsStateWithLifecycle()
  val query by controller.query.collectAsStateWithLifecycle()
  val readiness by controller.readiness.collectAsStateWithLifecycle()
  val queryModel by controller.queryModel.collectAsStateWithLifecycle()
  val handlers = remember(controller, actions) { ActionSearchHandlers(controller, actions) }
  LaunchedEffect(findSimilar) { if (findSimilar != null) controller.findSimilar(findSimilar) }
  SearchHost {
    SearchScreen(
      state = state,
      query = query,
      readiness = readiness,
      queryModel = queryModel,
      today = remember(state) { controller.todayDate() },
      thumbnails = thumbnails,
      handlers = handlers,
      modifier = modifier,
    )
  }
}
