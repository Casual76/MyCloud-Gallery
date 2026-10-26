package dev.pampa.mycloudgallery.feature.library.trash

import android.app.Activity
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.antigravity.fluidengine.ui.theme.FluidLoading
import dev.pampa.mycloudgallery.core.designsystem.design.ActionBarHeight
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeDefaults
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeIconButton
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeTextButton
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryIllustrationTone
import dev.pampa.mycloudgallery.core.designsystem.state.phoneTrashText
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.security.lock.SystemCredentialAuthenticator
import dev.pampa.mycloudgallery.feature.library.R
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEmptyState
import dev.pampa.mycloudgallery.feature.library.collections.CollectionsDimens
import dev.pampa.mycloudgallery.feature.library.collections.collectionActionSlot
import dev.pampa.mycloudgallery.feature.library.countText
import dev.pampa.mycloudgallery.feature.library.grid.ChromeLabelText
import dev.pampa.mycloudgallery.feature.library.grid.GridTopChrome
import dev.pampa.mycloudgallery.feature.library.grid.GridTopEdgeScrim
import dev.pampa.mycloudgallery.feature.library.grid.gridTopChromeHeight
import dev.pampa.mycloudgallery.feature.library.hidden.GatedWindow
import dev.pampa.mycloudgallery.feature.library.hidden.HiddenClosedPage
import dev.pampa.mycloudgallery.feature.library.hidden.HiddenUiState
import dev.pampa.mycloudgallery.feature.library.hidden.TrashGateTexts
import dev.pampa.mycloudgallery.feature.library.hidden.rememberHiddenAuthenticator
import dev.pampa.mycloudgallery.feature.library.hidden.rememberOpenSecuritySettings
import kotlinx.coroutines.launch
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

/**
 * La pagina degli **Eliminati di recente** (D14, D17, Fase 7 D26): i **due cestini insieme**. Il cestino di sistema di Android (`MATCH_TRASHED`) con
 * i giorni rimasti su ogni cella e "Ripristina", che apre il dialogo di sistema; il cestino del NAS (30 giorni) in righe, con "Ripristina" e
 * "Elimina subito" (solo le voci proprie, doppio tocco). Una frase in cima chiarisce che **non** e' il cestino di Samsung Gallery (D-3).
 *
 * @param onBack il tasto indietro; null nel pannello di dettaglio del tablet.
 * @param backdropOut il fondale per la pillola di `:app`; null dove la pillola non c'e'.
 */
@Composable
fun RecentlyDeletedRoute(
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  contentPadding: PaddingValues = PaddingValues(),
  backdropOut: ((GlassBackdropState) -> Unit)? = null,
  viewModel: RecentlyDeletedViewModel = hiltViewModel(),
) {
  RecentlyDeletedHost(
    controller = viewModel.controller,
    loader = viewModel.environment.loader,
    keepRecentsHidden = viewModel::keepRecentsHidden,
    authenticator = rememberHiddenAuthenticator(),
    modifier = modifier,
    onBack = onBack,
    contentPadding = contentPadding,
    backdropOut = backdropOut,
  )
}

/**
 * La pagina con tutto cio' che fa con la finestra e con il ciclo di vita ([GatedWindow]: `SecureScreen`, recenti oscurati,
 * ingresso e uscita, sblocco automatico), il dialogo di sistema del ripristino delle foto del telefono e i gesti sul cestino del NAS.
 *
 * @param monotonicMs l'orologio monotono dei tocchi del foglio di "Elimina subito" (mai l'ora del telefono).
 */
@Composable
internal fun RecentlyDeletedHost(
  controller: RecentlyDeletedController,
  loader: ThumbnailLoader,
  keepRecentsHidden: () -> Boolean,
  authenticator: SystemCredentialAuthenticator?,
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  contentPadding: PaddingValues = PaddingValues(),
  backdropOut: ((GlassBackdropState) -> Unit)? = null,
  monotonicMs: () -> Long = SystemClock::elapsedRealtime,
) {
  val state by controller.state.collectAsState()
  val selection by controller.selection.collectAsState()
  val scope = rememberCoroutineScope()
  var notice by remember { mutableStateOf<TrashNotice?>(null) }
  var purging by remember { mutableStateOf(false) }
  val unlock = GatedWindow(
    texts = TrashGateTexts,
    authenticator = authenticator,
    keepRecentsHidden = keepRecentsHidden,
    locked = state == TrashUiState.Locked,
    authenticating = state == TrashUiState.Authenticating,
    onShown = controller::shown,
    onHidden = controller::hidden,
    onUnlock = controller::unlock,
    onUnlockUnavailable = controller::unlockUnavailable,
  )
  val restoreDialog = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
    // Confermato: quello che era scelto e' tornato al suo posto. Annullato: la selezione resta, si puo' riprovare.
    if (result.resultCode == Activity.RESULT_OK) controller.clearSelection()
    controller.refresh()
  }
  BackHandler(enabled = selection.isNotEmpty()) { controller.clearSelection() }
  val canPurge = remember(state, selection) { controller.canPurge(selection) }
  val purgeBlocked = remember(state, selection) { selection.isNotEmpty() && !canPurge && controller.nasEntriesOf(selection).isNotEmpty() }
  val purge = remember(state, selection) { controller.purgeSummary(selection) }

  RecentlyDeletedScreen(
    state = state,
    selection = selection,
    loader = loader,
    onToggle = controller::toggle,
    onSelectAll = controller::selectAll,
    onClearSelection = controller::clearSelection,
    onRestore = {
      // Le foto del telefono: il dialogo di sistema. Le voci del NAS: la porta, dopo questo tocco.
      val phoneRequest = controller.restoreRequest()
      scope.launch { controller.restoreNas()?.let { notice = TrashNotice.of(it, purge = false) } }
      phoneRequest?.let { restoreDialog.launch(IntentSenderRequest.Builder(it).build()) }
    },
    onRetry = controller::refresh,
    modifier = modifier,
    onUnlock = unlock,
    onOpenSecuritySettings = rememberOpenSecuritySettings(),
    onBack = onBack,
    contentPadding = contentPadding,
    backdropOut = backdropOut,
    canPurge = canPurge,
    onPurge = { purging = true },
    purgeBlocked = purgeBlocked,
    notice = notice,
    onDismissNotice = { notice = null },
  )
  PurgeNowSheet(
    visible = purging && canPurge,
    count = purge.first,
    bytes = purge.second,
    monotonicMs = monotonicMs,
    onConfirmed = { proof ->
      purging = false
      scope.launch { controller.purgeNas(proof)?.let { notice = TrashNotice.of(it, purge = true) } }
    },
    onDismiss = { purging = false },
  )
}

/** La pagina per uno [state] e una [selection]: nessun effetto oltre alle richieste di miniature. La provano i test. */
@Composable
internal fun RecentlyDeletedScreen(
  state: TrashUiState,
  selection: Set<MediaId>,
  loader: ThumbnailLoader,
  onToggle: (MediaId) -> Unit,
  onSelectAll: () -> Unit,
  onClearSelection: () -> Unit,
  onRestore: () -> Unit,
  onRetry: () -> Unit,
  modifier: Modifier = Modifier,
  onUnlock: () -> Unit = {},
  onOpenSecuritySettings: () -> Unit = {},
  onBack: (() -> Unit)? = null,
  contentPadding: PaddingValues = PaddingValues(),
  backdropOut: ((GlassBackdropState) -> Unit)? = null,
  canPurge: Boolean = false,
  onPurge: () -> Unit = {},
  purgeBlocked: Boolean = false,
  notice: TrashNotice? = null,
  onDismissNotice: () -> Unit = {},
) {
  // Chiusa: la stessa schermata dei Nascosti, con le parole del cestino. Nessun elenco, nessuna miniatura.
  val closed = when (state) {
    TrashUiState.Locked -> HiddenUiState.Locked
    TrashUiState.Authenticating -> HiddenUiState.Authenticating
    is TrashUiState.Denied -> HiddenUiState.Denied(state.reason)
    else -> null
  }
  if (closed != null) {
    HiddenClosedPage(
      title = stringResource(R.string.trash_title),
      texts = TrashGateTexts,
      state = closed,
      onUnlock = onUnlock,
      onRetry = onRetry,
      onOpenSecuritySettings = onOpenSecuritySettings,
      modifier = modifier,
      onBack = onBack,
      contentPadding = contentPadding,
      backdropOut = backdropOut,
    )
    return
  }
  val backdrop = rememberGlassBackdrop()
  if (backdropOut != null) SideEffect { backdropOut(backdrop) }
  val barHeight = gridTopChromeHeight()
  val bottomPadding = contentPadding.calculateBottomPadding()
  // "Ripristina" sta nel posto della barra delle azioni delle altre griglie, e la griglia non si sposta quando arriva.
  val slotBottom = collectionActionSlot(contentPadding)
  val gridBottom = slotBottom + ActionBarHeight + GalleryChromeDefaults.Gap
  val loaded = state as? TrashUiState.Loaded
  val selecting = selection.isNotEmpty()

  Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    Box(modifier = Modifier.fillMaxSize().glassBackdropSource(backdrop)) {
      when (state) {
        is TrashUiState.Loaded -> if (state.showsEmptyPage()) {
          Column(modifier = Modifier.fillMaxSize().padding(top = barHeight, bottom = bottomPadding)) {
            Explainer(hiddenNote = false)
            CollectionEmptyState(
              icon = Icons.Rounded.Delete,
              title = stringResource(R.string.trash_empty_title),
              detail = stringResource(phoneTrashText(R.string.trash_empty_detail, R.string.trash_empty_detail_gallery)),
            )
          }
        } else {
          TrashGrid(
            entries = state.entries,
            nas = state.nas,
            selection = selection,
            loader = loader,
            onToggle = onToggle,
            onRetry = onRetry,
            purgeBlocked = purgeBlocked,
            topPadding = barHeight,
            bottomPadding = gridBottom,
          )
        }
        TrashUiState.Locked, TrashUiState.Authenticating, is TrashUiState.Denied -> Unit
        TrashUiState.Unsupported -> Centered(barHeight, bottomPadding) {
          CollectionEmptyState(
            icon = Icons.Rounded.Delete,
            title = stringResource(R.string.trash_unsupported_title),
            detail = stringResource(R.string.trash_unsupported_detail),
            tone = GalleryIllustrationTone.Caution,
          )
        }
        TrashUiState.Failed -> Centered(barHeight, bottomPadding) {
          CollectionEmptyState(
            icon = Icons.Rounded.Delete,
            title = stringResource(R.string.trash_error_title),
            detail = stringResource(R.string.trash_error_detail),
            tone = GalleryIllustrationTone.Caution,
            actionLabel = stringResource(R.string.trash_retry),
            onAction = onRetry,
          )
        }
        TrashUiState.Loading -> Centered(barHeight, bottomPadding) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CollectionsDimens.StateGap),
          ) {
            FluidLoading()
            Text(
              text = stringResource(R.string.trash_loading),
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
      GridTopEdgeScrim(barHeight, Modifier.align(Alignment.TopCenter))
      // L'esito dell'ultimo gesto sul NAS sta fermo sotto la barra, non nella lista: si vede anche scorrendo, e si chiude con la X.
      if (notice != null) {
        TrashNoticeRow(notice = notice, onDismiss = onDismissNotice, modifier = Modifier.align(Alignment.TopCenter).padding(top = barHeight))
      }
    }

    // La chrome delle griglie (G2): l'etichetta (il nome e quanti sono nei due cestini, o in selezione quanti sono scelti) e la capsula con
    // "Seleziona tutto", "Elimina subito" (solo se la selezione lo permette) e, selezionando, "Fine". Le miniature del cestino non hanno
    // ThumbHash: il vetro adattivo vale per un contenuto qualunque.
    val canSelectAll = loaded != null && !loaded.isEmpty && selection.size < loaded.total
    GridTopChrome(
      grid = null,
      backdrop = backdrop,
      label = if (selecting) {
        ChromeLabelText(countText(R.plurals.selection_count, selection.size), null)
      } else {
        ChromeLabelText(stringResource(R.string.trash_title), loaded?.takeIf { !it.isEmpty }?.let { countText(R.plurals.collection_count, it.total) })
      },
      topChrome = barHeight,
      modifier = Modifier.align(Alignment.TopStart),
      onBack = if (selecting) null else onBack,
      showCommands = canSelectAll || selecting,
    ) {
      if (canSelectAll) {
        GalleryChromeIconButton(icon = Icons.Rounded.SelectAll, contentDescription = stringResource(R.string.trash_select_all), onClick = onSelectAll)
      }
      if (canPurge) {
        GalleryChromeIconButton(icon = Icons.Rounded.DeleteForever, contentDescription = stringResource(R.string.trash_purge), onClick = onPurge)
      }
      if (selecting) GalleryChromeTextButton(label = stringResource(R.string.library_done), onClick = onClearSelection)
    }

    if (selecting) {
      FluidButton(
        text = countText(R.plurals.trash_restore, selection.size),
        onClick = onRestore,
        size = FluidButtonSize.Large,
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .padding(bottom = slotBottom),
      )
    }
  }
}

/** Non c'e' niente da mostrare nei due cestini (e il NAS, se c'e', ha risposto): lo stato vuoto di sempre. */
internal fun TrashUiState.Loaded.showsEmptyPage(): Boolean = entries.isEmpty() && when (nas) {
  NasTrashSection.None -> true
  is NasTrashSection.Rows -> nas.entries.isEmpty() && !nas.offline
  else -> false
}

@Composable
private fun Centered(topPadding: Dp, bottomPadding: Dp, content: @Composable () -> Unit) {
  Column(
    modifier = Modifier.fillMaxSize().padding(top = topPadding, bottom = bottomPadding),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    content()
  }
}
