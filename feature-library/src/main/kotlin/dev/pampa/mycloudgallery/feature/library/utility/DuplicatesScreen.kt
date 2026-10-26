package dev.pampa.mycloudgallery.feature.library.utility

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.library.duplicates.GroupKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.feature.library.R
import dev.pampa.mycloudgallery.feature.library.grid.ChromeLabelText
import dev.pampa.mycloudgallery.feature.library.grid.GridTopChrome
import dev.pampa.mycloudgallery.feature.library.grid.GridTopEdgeScrim
import dev.pampa.mycloudgallery.feature.library.grid.gridTopChromeHeight
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

// Fase 7, P8 (D25, D27, §2.1.1): la pagina DOPPIONI. Due schede ("Identici", "Simili"), la pulizia guidata che passa dal foglio di eliminazione
// esistente, e l'interruttore dell'hash completo di notte. Niente qui cancella da solo: ogni eliminazione e' un tocco su "Elimina" del foglio.

/** Le azioni della pagina Doppioni. */
internal class DuplicatesActions(
  val onTab: (DuplicatesTab) -> Unit = {},
  val identical: IdenticalActions = IdenticalActions(),
  val similar: SimilarActions = SimilarActions(),
  val onNightly: (Boolean) -> Unit = {},
  val onDismissNotice: () -> Unit = {},
)

/**
 * La pagina dei Doppioni. Vale per la rotta a schermo intero ([onBack] presente) e per il pannello di dettaglio del tablet ([onBack] nullo).
 *
 * @param backdropOut il fondale per la pillola di `:app`; null dove la pillola non c'e'.
 */
@Composable
fun DuplicatesRoute(
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  contentPadding: PaddingValues = PaddingValues(),
  backdropOut: ((GlassBackdropState) -> Unit)? = null,
  viewModel: DuplicatesViewModel = hiltViewModel(),
) {
  DuplicatesHost(
    controller = viewModel.controller,
    loader = viewModel.environment.loader,
    refOf = { viewModel.items.refOrNull(it) },
    familyName = viewModel.familyName,
    modifier = modifier,
    onBack = onBack,
    contentPadding = contentPadding,
    backdropOut = backdropOut,
  )
}

/** La pagina con il dialogo di sistema delle copie del telefono e il foglio di eliminazione. La provano i test. */
@Composable
internal fun DuplicatesHost(
  controller: DuplicatesController,
  loader: ThumbnailLoader,
  refOf: (MediaId) -> MediaRef?,
  familyName: (String) -> String?,
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  contentPadding: PaddingValues = PaddingValues(),
  backdropOut: ((GlassBackdropState) -> Unit)? = null,
) {
  val state by controller.state.collectAsState()
  val scope = rememberCoroutineScope()
  LaunchedEffect(controller) { controller.start() }
  // Il sistema ha risposto alla richiesta di mandare nel cestino le copie del telefono: se l'utente ha confermato, la pagina si rilegge.
  val phoneDialog = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
    controller.onPhoneResult(result.resultCode == Activity.RESULT_OK)
  }
  val actions = remember(controller) {
    DuplicatesActions(
      onTab = controller::selectTab,
      identical = IdenticalActions(
        onCleanup = controller::prepareIdentical,
        onRetry = controller::refreshIdentical,
        onKeep = controller::keepCopy,
        onReset = controller::resetKeep,
      ),
      similar = SimilarActions(
        onClean = controller::prepareSimilar,
        onRetry = controller::scanSimilar,
        onToggle = controller::toggleShot,
        onReset = controller::resetCluster,
      ),
      onNightly = controller::setNightly,
      onDismissNotice = controller::dismissNotice,
    )
  }
  DuplicatesScreen(
    state = state,
    actions = actions,
    loader = loader,
    refOf = refOf,
    familyName = familyName,
    modifier = modifier,
    onBack = onBack,
    contentPadding = contentPadding,
    backdropOut = backdropOut,
  )
  CleanupSheetUi(
    sheet = state.sheet,
    familyName = familyName,
    onScope = controller::chooseScope,
    onConfirm = {
      scope.launch { controller.confirm()?.let { phoneDialog.launch(IntentSenderRequest.Builder(it).build()) } }
    },
    onDismiss = controller::dismissSheet,
  )
}

/** La pagina per uno [state]: nessun effetto oltre alle richieste di miniature. La provano i test e gli screenshot. */
@Composable
internal fun DuplicatesScreen(
  state: DuplicatesState,
  actions: DuplicatesActions,
  loader: ThumbnailLoader,
  refOf: (MediaId) -> MediaRef?,
  familyName: (String) -> String?,
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  contentPadding: PaddingValues = PaddingValues(),
  backdropOut: ((GlassBackdropState) -> Unit)? = null,
) {
  val backdrop = rememberGlassBackdrop()
  if (backdropOut != null) SideEffect { backdropOut(backdrop) }
  val barHeight = gridTopChromeHeight()
  val listState = rememberLazyListState()
  var expanded by remember { mutableStateOf(emptySet<GroupKey>()) }
  val identicalActions = remember(actions) {
    IdenticalActions(
      onCleanup = actions.identical.onCleanup,
      onRetry = actions.identical.onRetry,
      onToggleReview = { key -> expanded = if (key in expanded) expanded - key else expanded + key },
      onKeep = actions.identical.onKeep,
      onReset = actions.identical.onReset,
    )
  }
  val requests = remember(state.tab, state.identical, state.similar) { windowRequests(state, refOf) }
  DuplicatesWindow(requests, listState, loader)

  Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    Box(modifier = Modifier.fillMaxSize().glassBackdropSource(backdrop), contentAlignment = Alignment.TopCenter) {
      LazyColumn(
        modifier = Modifier.widthIn(max = UtilityDimens.ListMaxWidth).fillMaxHeight().fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(top = barHeight, bottom = contentPadding.calculateBottomPadding() + UtilityDimens.ActionBarClearance),
      ) {
        item(key = "tabs") {
          DuplicatesTabs(
            selected = state.tab,
            onSelect = actions.onTab,
            modifier = Modifier.padding(horizontal = GalleryDimens.Gutter, vertical = UtilityDimens.RowGap),
          )
        }
        when (state.tab) {
          DuplicatesTab.IDENTICAL -> identicalItems(state.identical, expanded, state.busy, identicalActions, loader, refOf, familyName)
          DuplicatesTab.SIMILAR -> similarItems(state.similar, state.busy, actions.similar, loader, refOf)
        }
        if (state.nightly.available) item(key = "nightly") { NightlyRow(state.nightly, actions.onNightly) }
      }
      GridTopEdgeScrim(barHeight, Modifier.align(Alignment.TopCenter))
      state.notice?.let { notice ->
        DuplicatesNoticeRow(notice, actions.onDismissNotice, Modifier.align(Alignment.TopCenter).padding(top = barHeight))
      }
    }
    GridTopChrome(
      grid = null,
      backdrop = backdrop,
      label = ChromeLabelText(stringResource(R.string.duplicates_title), null),
      topChrome = barHeight,
      modifier = Modifier.align(Alignment.TopStart),
      onBack = onBack,
      showCommands = false,
    ) {}
  }
}

/** L'interruttore "Controlla di notte i video del NAS" (D27): la riga si tocca tutta, TalkBack la legge come un interruttore. */
@Composable
internal fun NightlyRow(view: NightlyView, onChange: (Boolean) -> Unit) {
  val stateText = stringResource(if (view.on) R.string.duplicates_nightly_on else R.string.duplicates_nightly_off)
  Box(modifier = Modifier.padding(horizontal = GalleryDimens.Gutter, vertical = UtilityDimens.SectionTop)) {
    FluidListGroup {
      FluidListRow(
        title = stringResource(R.string.duplicates_nightly_title),
        subtitle = stringResource(R.string.duplicates_nightly_detail),
        tone = FluidTone.Neutral,
        disclosure = false,
        badge = { FluidSwitch(checked = view.on, onCheckedChange = null) },
        modifier = Modifier
          .toggleable(value = view.on, role = Role.Switch, onValueChange = onChange)
          .semantics { stateDescription = stateText },
      )
    }
  }
}

/** Le miniature che la pagina chiede alla finestra del caricatore: quelle della scheda aperta, in ordine di lista. */
internal fun windowRequests(state: DuplicatesState, refOf: (MediaId) -> MediaRef?): Map<String, List<ThumbRequest>> {
  val out = LinkedHashMap<String, List<ThumbRequest>>()
  when (state.tab) {
    DuplicatesTab.IDENTICAL -> (state.identical.cleanable + state.identical.settled + state.identical.toVerify).forEach { proposal ->
      thumbRequest(proposal.group.media, refOf)?.let { out[proposal.listKey()] = listOf(it) }
    }
    DuplicatesTab.SIMILAR -> state.similar.clusters.forEach { view ->
      out[view.listKey()] = view.cluster.media.mapNotNull { thumbRequest(it, refOf) }
    }
  }
  return out
}

/** Le richieste di miniature seguono la lista: quelle delle voci a vista ora, e un poco di anticipo. Come la griglia della Libreria. */
@Composable
private fun DuplicatesWindow(
  requests: Map<String, List<ThumbRequest>>,
  listState: LazyListState,
  loader: ThumbnailLoader,
) {
  LaunchedEffect(requests, loader, listState) {
    val order = requests.keys.toList()
    snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String }.filter { it in requests } }
      .distinctUntilChanged()
      .collect { keys ->
        val visible = keys.flatMap { requests.getValue(it) }
        val last = keys.lastOrNull()?.let { order.indexOf(it) } ?: -1
        val ahead = order.drop(last + 1).take(AHEAD_ITEMS).flatMap { requests.getValue(it) }
        loader.window(visible, ahead)
      }
  }
}

/** Quante voci in anticipo si chiedono oltre quelle a vista. */
private const val AHEAD_ITEMS = 6
