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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
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
import dev.pampa.mycloudgallery.core.designsystem.design.ActionBarHeight
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeDefaults
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeIconButton
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeTextButton
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryWall
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryMediaCell
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryIllustrationTone
import dev.pampa.mycloudgallery.core.designsystem.state.phoneTrashText
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryOnPhoto
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.security.lock.SystemCredentialAuthenticator
import dev.pampa.mycloudgallery.feature.library.R
import dev.pampa.mycloudgallery.feature.library.countText
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEmptyState
import dev.pampa.mycloudgallery.feature.library.collections.CollectionsDimens
import dev.pampa.mycloudgallery.feature.library.collections.collectionActionSlot
import dev.pampa.mycloudgallery.feature.library.grid.gridTopChromeHeight
import dev.pampa.mycloudgallery.feature.library.grid.ChromeLabelText
import dev.pampa.mycloudgallery.feature.library.grid.GridTopChrome
import dev.pampa.mycloudgallery.feature.library.grid.GridTopEdgeScrim
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
        is TrashUiState.Loaded -> if (state.entries.isEmpty()) {
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
            selection = selection,
            loader = loader,
            onToggle = onToggle,
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
    }

    // La chrome delle griglie (G2): l'etichetta (il nome e quanti sono, o in selezione quanti sono scelti) e la capsula con
    // "Seleziona tutto" e, selezionando, "Fine". Le miniature del cestino non hanno ThumbHash: il vetro adattivo vale per
    // un contenuto qualunque.
    val canSelectAll = loaded != null && loaded.entries.isNotEmpty() && selection.size < loaded.entries.size
    GridTopChrome(
      grid = null,
      backdrop = backdrop,
      label = if (selecting) {
        ChromeLabelText(countText(R.plurals.selection_count, selection.size), null)
      } else {
        ChromeLabelText(stringResource(R.string.trash_title), loaded?.entries?.takeIf { it.isNotEmpty() }?.let { countText(R.plurals.collection_count, it.size) })
      },
      topChrome = barHeight,
      modifier = Modifier.align(Alignment.TopStart),
      onBack = if (selecting) null else onBack,
      showCommands = canSelectAll || selecting,
    ) {
      if (canSelectAll) {
        GalleryChromeIconButton(icon = Icons.Rounded.SelectAll, contentDescription = stringResource(R.string.trash_select_all), onClick = onSelectAll)
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

/**
 * La frase che distingue questo cestino da quello della Galleria di Samsung (E-P11). D-3 (prove sul campo): su One UI quello che l'app
 * elimina va nel Cestino di Galleria e qui non compare: la pagina lo dice e manda a Galleria → Cestino, invece di promettere un ripristino.
 */
@Composable
private fun Explainer(hiddenNote: Boolean) {
  FluidInlineMessage(
    message = stringResource(phoneTrashText(R.string.trash_explainer, R.string.trash_explainer_gallery)) +
      if (hiddenNote) " " + stringResource(R.string.trash_hidden_note) else "",
    title = stringResource(phoneTrashText(R.string.trash_explainer_title, R.string.trash_explainer_title_gallery)),
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

  // La parete di foto delle altre griglie (G2): celle squadrate, fessura di 1 dp.
  LazyVerticalGrid(
    columns = GridCells.Adaptive(CollectionsDimens.TrashCellMin),
    modifier = Modifier.fillMaxSize(),
    state = gridState,
    contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding),
    verticalArrangement = Arrangement.spacedBy(GalleryWall.Gap),
    horizontalArrangement = Arrangement.spacedBy(GalleryWall.Gap),
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
    shape = RectangleShape,
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
