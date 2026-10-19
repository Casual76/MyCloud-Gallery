package dev.pampa.mycloudgallery.feature.integrations.picker

import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.FluidSpinner
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeIconButton
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryBadge
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryBadgeView
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryMediaCell
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryIllustration
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryIllustrationTone
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.imaging.contract.PlaceholderCache
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.library.index.ColumnarTimelineSnapshot
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.feature.integrations.R
import dev.pampa.mycloudgallery.feature.integrations.provider.DocumentPolicy
import dev.pampa.mycloudgallery.feature.integrations.provider.NasUnreachableReason
import kotlin.math.max
import kotlin.math.min

// Fase 5B (P10, docs/fase5-progetto.md §1.5 `PickerState`, §2.7, D23, D30; E-X1, E-X6, E-X7): la schermata del selettore. Funzionale, solo con
// i componenti che il design system ha gia' (D30): `GalleryMediaCell` per le celle, `GalleryChromeIconButton`, `GalleryIllustration`, i bottoni
// di Fluid, i colori e le forme del tema. **Niente vetro e niente sovrapposizioni**: la riga in alto e la barra in basso sono in colonna con la
// griglia, non sopra: i controlli non coprono mai una foto e le foto non passano mai sotto i controlli (il filone grafico rifinisce).
//
// **Con l'app bloccata la griglia non e' nemmeno in composizione** (E-X7): lo stato [PickerScreenState.LOCKED] mostra solo lo sblocco.
// Nessuna cella porta un nome di file: la descrizione per TalkBack dice solo "Foto"/"Video" e dove sta.

/** Le dimensioni della schermata. */
private object PickerDimens {
  val CellMin = 104.dp
  val BarPadding = 16.dp
  val BarGap = 12.dp
  val MessageMaxWidth = 420.dp
  val NoticeRadius = FluidRadius.Card
}

/** Cio' che serve a disegnare le celle: le miniature, i segnaposto e come arrivare al riferimento di un media. */
@Immutable
class PickerEnv(
  val loader: ThumbnailLoader,
  val placeholders: PlaceholderCache,
  val refOf: (MediaId) -> MediaRef?,
)

/** Le azioni della schermata. */
class PickerActions(
  val onTap: (MediaId) -> Unit = {},
  val onConfirm: () -> Unit = {},
  val onCancel: () -> Unit = {},
  val onUnlock: () -> Unit = {},
  val onDismissNotice: () -> Unit = {},
)

@Composable
internal fun PickerScreen(
  ui: PickerUi,
  request: PickerRequest,
  env: PickerEnv,
  actions: PickerActions,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      .windowInsetsPadding(WindowInsets.safeDrawing),
  ) {
    PickerTopBar(subtitle = subtitleOf(request), onCancel = actions.onCancel)
    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
      when (ui.screen) {
        PickerScreenState.CHECKING -> Unit
        PickerScreenState.LOCKED -> PickerMessage(
          icon = Icons.Rounded.Lock,
          tone = GalleryIllustrationTone.Brand,
          title = stringResource(R.string.integrations_picker_locked_title),
          detail = stringResource(R.string.integrations_picker_locked_detail),
          action = stringResource(R.string.integrations_picker_unlock),
          onAction = actions.onUnlock,
          loading = ui.unlocking,
          footnote = if (ui.unlockFailed) stringResource(R.string.integrations_picker_unlock_failed) else null,
        )
        PickerScreenState.NOT_READY -> PickerMessage(
          icon = Icons.Rounded.Settings,
          tone = GalleryIllustrationTone.Caution,
          title = stringResource(R.string.integrations_picker_not_ready_title),
          detail = stringResource(R.string.integrations_picker_not_ready_detail),
          action = stringResource(R.string.integrations_picker_close),
          onAction = actions.onCancel,
        )
        PickerScreenState.DELIVERING -> PickerBusy()
        PickerScreenState.GRID -> PickerBody(ui, request, env, actions)
      }
    }
    if (ui.screen == PickerScreenState.GRID && request.allowMultiple) {
      PickerBottomBar(selected = ui.selected.size, onConfirm = actions.onConfirm)
    }
  }
}

// --- Riga in alto ---------------------------------------------------------------------------------------------------------

@Composable
private fun PickerTopBar(subtitle: String, onCancel: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    GalleryChromeIconButton(
      icon = Icons.Rounded.Close,
      contentDescription = stringResource(R.string.integrations_picker_cancel),
      onClick = onCancel,
    )
    Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp).semantics(mergeDescendants = true) { heading() }) {
      Text(
        text = stringResource(R.string.integrations_picker_title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
      )
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
      )
    }
  }
}

@Composable
private fun subtitleOf(request: PickerRequest): String = stringResource(
  when {
    request.images && request.videos -> R.string.integrations_picker_subtitle_both
    request.videos -> R.string.integrations_picker_subtitle_videos
    else -> R.string.integrations_picker_subtitle_photos
  },
)

// --- Barra in basso (solo scelta multipla) --------------------------------------------------------------------------------

@Composable
private fun PickerBottomBar(selected: Int, onConfirm: () -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(MaterialTheme.colorScheme.surfaceContainer)
      .padding(PickerDimens.BarPadding),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PickerDimens.BarGap),
  ) {
    Text(
      text = if (selected == 0) {
        stringResource(R.string.integrations_picker_nothing_selected)
      } else {
        pluralStringResource(R.plurals.integrations_picker_selected_count, selected, selected)
      },
      modifier = Modifier.weight(1f),
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.onSurface,
    )
    FluidButton(
      text = if (selected == 0) {
        stringResource(R.string.integrations_picker_choose)
      } else {
        pluralStringResource(R.plurals.integrations_picker_choose_count, selected, selected)
      },
      onClick = onConfirm,
      size = FluidButtonSize.Large,
      enabled = selected > 0,
    )
  }
}

// --- Corpo: griglia, vuoto, avviso ----------------------------------------------------------------------------------------

@Composable
private fun PickerBody(ui: PickerUi, request: PickerRequest, env: PickerEnv, actions: PickerActions) {
  Column(modifier = Modifier.fillMaxSize()) {
    val notice = ui.notice
    if (notice != null) PickerNoticeBanner(notice, actions.onDismissNotice)
    if (ui.items.size == 0) {
      PickerMessage(
        icon = Icons.Rounded.PhotoLibrary,
        tone = GalleryIllustrationTone.Brand,
        title = stringResource(R.string.integrations_picker_empty_title),
        detail = stringResource(R.string.integrations_picker_empty_detail),
        action = null,
        onAction = null,
      )
    } else {
      PickerGrid(ui, request, env, actions, Modifier.weight(1f).fillMaxWidth())
    }
  }
}

@Composable
private fun PickerGrid(ui: PickerUi, request: PickerRequest, env: PickerEnv, actions: PickerActions, modifier: Modifier) {
  val items = ui.items
  val state = rememberLazyGridState()
  ThumbnailWindow(state, items, env)
  LazyVerticalGrid(
    columns = GridCells.Adaptive(PickerDimens.CellMin),
    state = state,
    modifier = modifier,
    contentPadding = PaddingValues(GalleryDimens.GridGap),
    horizontalArrangement = Arrangement.spacedBy(GalleryDimens.GridGap),
    verticalArrangement = Arrangement.spacedBy(GalleryDimens.GridGap),
  ) {
    items(count = items.size, key = { items.idAt(it).value }) { position ->
      PickerCell(items, position, selected = items.idAt(position) in ui.selected, request, env, actions)
    }
  }
}

@Composable
private fun PickerCell(
  items: PickerItems,
  position: Int,
  selected: Boolean,
  request: PickerRequest,
  env: PickerEnv,
  actions: PickerActions,
) {
  val snapshot = items.snapshot
  val index = items.snapshotIndex(position)
  val id = remember(items, position) { items.idAt(position) }
  val kind = snapshot.kindAt(index)
  val nasOnly = !snapshot.hasFlag(index, CellFlags.ON_PHONE) && snapshot.hasFlag(index, CellFlags.ON_NAS)
  val ref = remember(id) { env.refOf(id) }
  val thumbnail: ImageBitmap? = if (ref != null) env.loader.image(thumbKey(items, position, id), ref).value else null
  val placeholder = remember(items, position) { snapshot.thumbHashAt(index)?.let { env.placeholders.image(it) } }
  val shown = thumbnail ?: placeholder
  val photo = stringResource(R.string.integrations_picker_cell_photo)
  val video = stringResource(R.string.integrations_picker_cell_video)
  val onNas = stringResource(R.string.integrations_picker_cell_nas_only)
  val description = buildString {
    append(if (kind == MediaKind.VIDEO) video else photo)
    if (nasOnly) append(", ").append(onNas)
  }
  GalleryMediaCell(
    contentDescription = description,
    selectionMode = request.allowMultiple,
    selected = selected,
    loaded = shown != null,
    onClick = { actions.onTap(id) },
    topEnd = if (nasOnly) ({ GalleryBadgeView(GalleryBadge.NasOnly) }) else null,
    bottomEnd = if (kind == MediaKind.VIDEO) ({ GalleryBadgeView(GalleryBadge.VideoDuration(duration(snapshot.durationMsAt(index)))) }) else null,
  ) {
    shown?.let { Image(bitmap = it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
  }
}

private fun thumbKey(items: PickerItems, position: Int, id: MediaId): ThumbKey {
  val snapshot = items.snapshot
  val version = (snapshot as? ColumnarTimelineSnapshot)?.versionKeyAt(items.snapshotIndex(position)) ?: DEFAULT_VERSION_KEY
  return ThumbKey(id, version, ThumbBucket.M)
}

/** Le miniature guidate dalla finestra (D5): quelle sullo schermo con la precedenza, poi una schermata in arrivo. Alla chiusura la finestra si svuota. */
@Composable
private fun ThumbnailWindow(state: LazyGridState, items: PickerItems, env: PickerEnv) {
  LaunchedEffect(state, items, env) {
    snapshotFlow {
      val visible = state.layoutInfo.visibleItemsInfo
      if (visible.isEmpty()) -1 to -1 else visible.first().index to visible.last().index
    }.collect { (first, last) ->
      if (first < 0) return@collect
      fun request(position: Int): ThumbRequest? {
        val id = items.idAt(position)
        val ref = env.refOf(id) ?: return null
        return ThumbRequest(thumbKey(items, position, id), ref)
      }
      val visible = (first..min(last, items.size - 1)).mapNotNull(::request)
      val span = max(last - first + 1, 1)
      val ahead = ((last + 1)..min(last + span, items.size - 1)).mapNotNull(::request) +
        (max(first - span / 2, 0) until first).mapNotNull(::request)
      env.loader.window(visible, ahead)
    }
  }
  DisposableEffect(env) { onDispose { env.loader.window(emptyList(), emptyList()) } }
}

private fun duration(ms: Long): String {
  val seconds = (ms / 1000).coerceAtLeast(0)
  val h = seconds / 3600
  val m = (seconds % 3600) / 60
  val s = seconds % 60
  return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

// --- Avviso ---------------------------------------------------------------------------------------------------------------

@Composable
private fun PickerNoticeBanner(notice: PickerNotice, onDismiss: () -> Unit) {
  val scheme = MaterialTheme.colorScheme
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 12.dp, vertical = 8.dp)
      .background(scheme.tertiaryContainer, ContinuousCornerShape(PickerDimens.NoticeRadius))
      .padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = noticeText(notice),
      modifier = Modifier.weight(1f).padding(vertical = 8.dp),
      style = MaterialTheme.typography.bodyMedium,
      color = scheme.onTertiaryContainer,
    )
    GalleryChromeIconButton(
      icon = Icons.Rounded.Close,
      contentDescription = stringResource(R.string.integrations_picker_notice_dismiss),
      onClick = onDismiss,
      tint = scheme.onTertiaryContainer,
    )
  }
}

@Composable
internal fun noticeText(notice: PickerNotice): String {
  val context = LocalContext.current
  return when (notice) {
    is PickerNotice.NasUnreachable -> stringResource(
      when (notice.reason) {
        NasUnreachableReason.OFFLINE -> R.string.integrations_picker_notice_nas_offline
        NasUnreachableReason.METERED -> R.string.integrations_picker_notice_nas_metered
        NasUnreachableReason.UNAVAILABLE -> R.string.integrations_picker_notice_nas_unavailable
      },
    )
    is PickerNotice.TooManyNasOnly ->
      if (notice.bytes > 0) {
        stringResource(
          R.string.integrations_picker_notice_too_many_nas_size,
          notice.count,
          Formatter.formatShortFileSize(context, notice.bytes),
          DocumentPolicy.MAX_NAS_ONLY_ITEMS,
          Formatter.formatShortFileSize(context, DocumentPolicy.MAX_NAS_ONLY_BYTES),
        )
      } else {
        stringResource(
          R.string.integrations_picker_notice_too_many_nas,
          notice.count,
          DocumentPolicy.MAX_NAS_ONLY_ITEMS,
          Formatter.formatShortFileSize(context, DocumentPolicy.MAX_NAS_ONLY_BYTES),
        )
      }
    is PickerNotice.TooMany -> stringResource(R.string.integrations_picker_notice_too_many, notice.max)
    PickerNotice.NothingAvailable -> stringResource(R.string.integrations_picker_notice_nothing)
  }
}

// --- Stati senza griglia --------------------------------------------------------------------------------------------------

@Composable
private fun PickerBusy() {
  Column(
    modifier = Modifier.fillMaxSize().padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    FluidSpinner(size = 32.dp)
    Text(
      text = stringResource(R.string.integrations_picker_loading_title),
      modifier = Modifier.padding(top = 16.dp),
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurface,
      textAlign = TextAlign.Center,
    )
    Text(
      text = stringResource(R.string.integrations_picker_loading_detail),
      modifier = Modifier.padding(top = 4.dp),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
  }
}

@Composable
private fun PickerMessage(
  icon: ImageVector,
  tone: GalleryIllustrationTone,
  title: String,
  detail: String,
  action: String?,
  onAction: (() -> Unit)?,
  loading: Boolean = false,
  footnote: String? = null,
) {
  Column(
    modifier = Modifier.fillMaxSize().padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
  ) {
    GalleryIllustration(icon = icon, tone = tone)
    Text(
      text = title,
      modifier = Modifier.widthIn(max = PickerDimens.MessageMaxWidth),
      style = MaterialTheme.typography.headlineSmall,
      color = MaterialTheme.colorScheme.onSurface,
      textAlign = TextAlign.Center,
    )
    Text(
      text = detail,
      modifier = Modifier.widthIn(max = PickerDimens.MessageMaxWidth),
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
    if (action != null && onAction != null) {
      FluidButton(text = action, onClick = onAction, size = FluidButtonSize.Large, style = FluidButtonStyle.Filled, loading = loading, enabled = !loading)
    }
    if (footnote != null) {
      Text(
        text = footnote,
        modifier = Modifier.widthIn(max = PickerDimens.MessageMaxWidth),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
      )
    }
  }
}

private const val DEFAULT_VERSION_KEY: Long = 1L
