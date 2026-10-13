package dev.pampa.mycloudgallery.feature.library.trash

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidLoading
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryBarButton
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryNavigationIcon
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTopBar
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryMediaCell
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryIllustrationTone
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryOnPhoto
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.security.lock.SystemCredentialAuthenticator
import dev.pampa.mycloudgallery.feature.library.R
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEmptyState
import dev.pampa.mycloudgallery.feature.library.collections.CollectionsDimens
import dev.pampa.mycloudgallery.feature.library.hidden.GatedWindow
import dev.pampa.mycloudgallery.feature.library.hidden.HiddenClosedPage
import dev.pampa.mycloudgallery.feature.library.hidden.HiddenUiState
import dev.pampa.mycloudgallery.feature.library.hidden.TrashGateTexts
import dev.pampa.mycloudgallery.feature.library.hidden.rememberHiddenAuthenticator
import dev.pampa.mycloudgallery.feature.library.hidden.rememberOpenSecuritySettings
import kotlin.math.min
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

/**
 * La pagina degli **Eliminati di recente** (D14, D17): il cestino di sistema di Android (`MATCH_TRASHED`) con i giorni
 * rimasti su ogni cella e "Ripristina", che apre il dialogo di sistema. Una frase in cima chiarisce che **non** e' il
 * cestino di Samsung Gallery.
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
 * ingresso e uscita, sblocco automatico) e il dialogo di sistema del ripristino.
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
) {
  val state by controller.state.collectAsState()
  val selection by controller.selection.collectAsState()
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

  RecentlyDeletedScreen(
    state = state,
    selection = selection,
    loader = loader,
    onToggle = controller::toggle,
    onSelectAll = controller::selectAll,
    onClearSelection = controller::clearSelection,
    onRestore = {
      controller.restoreRequest()?.let { restoreDialog.launch(IntentSenderRequest.Builder(it).build()) }
    },
    onRetry = controller::refresh,
    onUnlock = unlock,
    onOpenSecuritySettings = rememberOpenSecuritySettings(),
    modifier = modifier,
    onBack = onBack,
    contentPadding = contentPadding,
    backdropOut = backdropOut,
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
  val density = LocalDensity.current
  var barHeightPx by remember { mutableIntStateOf(0) }
  val barHeight = with(density) { barHeightPx.toDp() }
  val bottomPadding = contentPadding.calculateBottomPadding()
  val loaded = state as? TrashUiState.Loaded
  val selecting = selection.isNotEmpty()

  Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    Box(modifier = Modifier.fillMaxSize().glassBackdropSource(backdrop)) {
      when (state) {
        is TrashUiState.Loaded -> if (state.entries.isEmpty()) {
          Column(modifier = Modifier.fillMaxSize().padding(top = barHeight, bottom = bottomPadding)) {
            Explainer(hiddenNote = false)
            CollectionEmptyState(
              icon = Icons.Rounded.Delete,
              title = stringResource(R.string.trash_empty_title),
              detail = stringResource(R.string.trash_empty_detail),
            )
          }
        } else {
          TrashGrid(
            entries = state.entries,
            selection = selection,
            loader = loader,
            onToggle = onToggle,
            topPadding = barHeight,
            bottomPadding = bottomPadding + if (selecting) CollectionsDimens.ActionBarSlot else CollectionsDimens.None,
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
    }

    GalleryTopBar(
      title = if (selecting) pluralStringResource(R.plurals.selection_count, selection.size, selection.size) else stringResource(R.string.trash_title),
      backdrop = backdrop,
      modifier = Modifier.align(Alignment.TopCenter).onSizeChanged { barHeightPx = it.height },
      subtitle = if (selecting) null else loaded?.entries?.takeIf { it.isNotEmpty() }?.let { pluralStringResource(R.plurals.collection_count, it.size, it.size) },
      // Selezionando, il tasto in alto a sinistra e' "Chiudi": esce dalla selezione, non dalla pagina.
      onNavigate = if (selecting) onClearSelection else onBack,
      navigationIcon = if (selecting) GalleryNavigationIcon.Close else GalleryNavigationIcon.Back,
      actions = {
        if (loaded != null && loaded.entries.isNotEmpty() && selection.size < loaded.entries.size) {
          GalleryBarButton(
            icon = Icons.Rounded.SelectAll,
            contentDescription = stringResource(R.string.trash_select_all),
            onClick = onSelectAll,
          )
        }
      },
    )

    if (selecting) {
      FluidButton(
        text = pluralStringResource(R.plurals.trash_restore, selection.size, selection.size),
        onClick = onRestore,
        size = FluidButtonSize.Large,
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .navigationBarsPadding()
          .padding(bottom = bottomPadding + CollectionsDimens.ActionBarGap),
      )
    }
  }
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

/** La frase che distingue questo cestino da quello della Galleria di Samsung (E-P11). */
@Composable
private fun Explainer(hiddenNote: Boolean) {
  FluidInlineMessage(
    message = stringResource(R.string.trash_explainer) + if (hiddenNote) " " + stringResource(R.string.trash_hidden_note) else "",
    title = stringResource(R.string.trash_explainer_title),
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = GalleryDimens.Gutter, vertical = CollectionsDimens.ExplainerGap),
    tone = FluidTone.Neutral,
  )
}

@Composable
private fun TrashGrid(
  entries: List<TrashEntry>,
  selection: Set<MediaId>,
  loader: ThumbnailLoader,
  onToggle: (MediaId) -> Unit,
  topPadding: Dp,
  bottomPadding: Dp,
) {
  val gridState = rememberLazyGridState()
  val selecting = selection.isNotEmpty()

  // Come la griglia della Libreria: le miniature le chiede la finestra visibile, non la cella (D5).
  LaunchedEffect(entries, loader, gridState) {
    val byKey = entries.associateBy { it.id.value }
    snapshotFlow { gridState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String } }
      .distinctUntilChanged()
      .collect { keys ->
        val visible = keys.mapNotNull { byKey[it] }
        val last = visible.lastOrNull()?.let { entries.indexOf(it) } ?: -1
        val ahead = entries.subList(min(entries.size, last + 1), min(entries.size, last + 1 + AHEAD))
        loader.window(visible.map { it.request() }, ahead.map { it.request() })
      }
  }

  LazyVerticalGrid(
    columns = GridCells.Adaptive(CollectionsDimens.TrashCellMin),
    modifier = Modifier.fillMaxSize(),
    state = gridState,
    contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding),
    verticalArrangement = Arrangement.spacedBy(GalleryDimens.GridGap),
    horizontalArrangement = Arrangement.spacedBy(GalleryDimens.GridGap),
  ) {
    item(key = "explainer", span = { GridItemSpan(maxLineSpan) }) { Explainer(hiddenNote = entries.any { it.hidden }) }
    items(entries, key = { it.id.value }) { entry ->
      TrashCell(entry = entry, loader = loader, selecting = selecting, selected = entry.id in selection, onToggle = onToggle)
    }
  }
}

@Composable
private fun TrashCell(
  entry: TrashEntry,
  loader: ThumbnailLoader,
  selecting: Boolean,
  selected: Boolean,
  onToggle: (MediaId) -> Unit,
) {
  val image by loader.image(entry.request().key, entry.item.ref)
  val base = when {
    entry.daysLeft <= 0 -> stringResource(if (entry.item.isVideo) R.string.trash_cell_video_today else R.string.trash_cell_photo_today)
    entry.item.isVideo -> pluralStringResource(R.plurals.trash_cell_video, entry.daysLeft, entry.daysLeft)
    else -> pluralStringResource(R.plurals.trash_cell_photo, entry.daysLeft, entry.daysLeft)
  }
  val description = if (entry.hidden) {
    base + ", " + stringResource(if (entry.item.isVideo) R.string.trash_hidden_video else R.string.trash_hidden_photo)
  } else {
    base
  }
  GalleryMediaCell(
    contentDescription = description,
    loaded = image != null,
    selectionMode = selecting,
    selected = selected,
    onClick = { onToggle(entry.id) },
    onLongClick = { onToggle(entry.id) },
    topEnd = if (entry.hidden) ({ HiddenPill() }) else null,
    bottomEnd = { DaysLeftPill(entry.daysLeft) },
  ) {
    image?.let { Image(bitmap = it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
  }
}

/** I giorni che restano, in una pillola scura sopra la foto (la coppia fissa "sopra una foto" del design system). */
@Composable
private fun DaysLeftPill(daysLeft: Int) {
  Text(
    text = if (daysLeft <= 0) stringResource(R.string.trash_days_left_today) else pluralStringResource(R.plurals.trash_days_left, daysLeft, daysLeft),
    modifier = Modifier
      .background(GalleryOnPhoto.scrim(), FluidCapsuleShape)
      .padding(horizontal = CollectionsDimens.DaysPillHorizontal, vertical = CollectionsDimens.DaysPillVertical),
    style = MaterialTheme.typography.labelSmall,
    color = GalleryOnPhoto.Content,
    maxLines = 1,
  )
}

/** Il segno "Nascosto" di un elemento nascosto eliminato: un lucchetto e la parola, sulla stessa coppia fissa sopra la foto. */
@Composable
private fun HiddenPill() {
  Row(
    modifier = Modifier
      .background(GalleryOnPhoto.scrim(), FluidCapsuleShape)
      .padding(horizontal = CollectionsDimens.DaysPillHorizontal, vertical = CollectionsDimens.DaysPillVertical),
    horizontalArrangement = Arrangement.spacedBy(CollectionsDimens.PillGap),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = Icons.Rounded.Lock,
      contentDescription = null,
      modifier = Modifier.size(CollectionsDimens.PillGlyph),
      tint = GalleryOnPhoto.Content,
    )
    Text(
      text = stringResource(R.string.trash_hidden_badge),
      style = MaterialTheme.typography.labelSmall,
      color = GalleryOnPhoto.Content,
      maxLines = 1,
    )
  }
}

private fun TrashEntry.request(): ThumbRequest =
  ThumbRequest(ThumbKey(mediaId = id, versionKey = item.trashedAtMs, bucket = ThumbBucket.M), item.ref)

/** Quante miniature si chiedono in anticipo oltre quelle visibili. */
private const val AHEAD = 12
