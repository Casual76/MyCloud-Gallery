package dev.pampa.mycloudgallery.feature.nas.trash

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeIconButton
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeTextButton
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryFooterNote
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryFooterPill
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryFooterTone
import dev.pampa.mycloudgallery.core.designsystem.design.galleryChromeTag
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryIllustrationTone
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.core.nasops.contract.NasTrashItem
import dev.pampa.mycloudgallery.core.nasops.contract.NasTrashState
import dev.pampa.mycloudgallery.core.nasops.contract.OpPlan
import dev.pampa.mycloudgallery.core.nasops.contract.TrashReason
import dev.pampa.mycloudgallery.core.nasops.contract.UserConfirmation
import dev.pampa.mycloudgallery.feature.nas.NasDimens
import dev.pampa.mycloudgallery.feature.nas.R
import dev.pampa.mycloudgallery.feature.nas.confirm.NasConfirmSheet
import dev.pampa.mycloudgallery.feature.nas.ui.FileKind
import dev.pampa.mycloudgallery.feature.nas.ui.NasActionCapsule
import dev.pampa.mycloudgallery.feature.nas.ui.NasBarButton
import dev.pampa.mycloudgallery.feature.nas.ui.NasEmptyState
import dev.pampa.mycloudgallery.feature.nas.ui.NasFade
import dev.pampa.mycloudgallery.feature.nas.ui.NasLabel
import dev.pampa.mycloudgallery.feature.nas.ui.NasLoadingBlock
import dev.pampa.mycloudgallery.feature.nas.ui.NasNotice
import dev.pampa.mycloudgallery.feature.nas.ui.NasNoticeLine
import dev.pampa.mycloudgallery.feature.nas.ui.NasPage
import dev.pampa.mycloudgallery.feature.nas.ui.NasTrailingSlot
import dev.pampa.mycloudgallery.feature.nas.ui.NasText
import dev.pampa.mycloudgallery.feature.nas.ui.NasTone
import dev.pampa.mycloudgallery.feature.nas.ui.countText
import dev.pampa.mycloudgallery.feature.nas.ui.groupedRows

/**
 * **Eliminati di recente del NAS**: quello che e' nel cestino del NAS (30 giorni), con i giorni rimasti, chi l'ha eliminato e da
 * dove veniva. "Ripristina" lo riporta dove era; "Elimina subito" (solo le voci proprie) lo elimina per sempre, con **doppia
 * conferma** e l'avviso sul backup esterno (Q8). Le azioni passano dal foglio di conferma.
 */
@Composable
internal fun NasTrashRoute(
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  hostPadding: PaddingValues = PaddingValues(),
  backdropOut: ((GlassBackdropState) -> Unit)? = null,
  onOpenOperations: () -> Unit = {},
  viewModel: NasTrashViewModel = hiltViewModel(),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  BackHandler(enabled = state.selecting) { viewModel.clearSelection() }
  NasTrashScreen(
    state = state,
    actions = TrashActions(
      onToggle = viewModel::toggle,
      onSelectAll = viewModel::selectAll,
      onClearSelection = viewModel::clearSelection,
      onStartSelecting = viewModel::startSelecting,
      onRestore = viewModel::requestRestore,
      onPurge = viewModel::requestPurge,
      onRetry = viewModel::refresh,
      onOpenOperations = onOpenOperations,
      onDismissNotice = viewModel::dismissNotice,
      onConfirmed = viewModel::confirmed,
      onDismissConfirm = viewModel::dismissConfirm,
    ),
    monotonicMs = viewModel::monotonicMs,
    modifier = modifier,
    onBack = onBack,
    hostPadding = hostPadding,
    backdropOut = backdropOut,
  )
}

internal class TrashActions(
  val onToggle: (NasTrashItem) -> Unit = {},
  val onSelectAll: () -> Unit = {},
  val onClearSelection: () -> Unit = {},
  val onStartSelecting: () -> Unit = {},
  val onRestore: () -> Unit = {},
  val onPurge: () -> Unit = {},
  val onRetry: () -> Unit = {},
  val onOpenOperations: () -> Unit = {},
  val onDismissNotice: () -> Unit = {},
  val onConfirmed: (OpPlan, UserConfirmation) -> Unit = { _, _ -> },
  val onDismissConfirm: () -> Unit = {},
)

/** La pagina per uno [state]: nessun effetto oltre a quello che chiede alle [actions]. La provano i test e gli screenshot. */
@Composable
internal fun NasTrashScreen(
  state: TrashUi,
  actions: TrashActions,
  monotonicMs: () -> Long,
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  hostPadding: PaddingValues = PaddingValues(),
  backdropOut: ((GlassBackdropState) -> Unit)? = null,
) {
  val count = state.items.size
  NasPage(
    label = trashLabel(state, count),
    modifier = modifier,
    onBack = if (state.selecting) null else onBack,
    backdropOut = backdropOut,
    commands = trashCommands(state, actions),
    hostPadding = hostPadding,
    reserveBottomBar = true,
    bottomBar = { backdrop, style ->
      NasFade(visible = state.selecting) {
        NasActionCapsule(style = style, backdrop = backdrop, modifier = Modifier.galleryChromeTag("azioni")) {
          NasBarButton(
            label = stringResource(R.string.nas_trash_restore),
            onClick = actions.onRestore,
            ink = style.ink,
            icon = Icons.Rounded.RestoreFromTrash,
            enabled = state.canRestore,
          )
          NasBarButton(
            label = stringResource(R.string.nas_trash_purge),
            onClick = actions.onPurge,
            ink = style.destructiveInk,
            icon = Icons.Rounded.DeleteForever,
            enabled = state.canPurge,
          )
        }
      }
    },
  ) { insets ->
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
      when (val list = state.list) {
        NasTrashState.Loading -> Box(Modifier.fillMaxSize().padding(top = insets.top, bottom = insets.bottom), contentAlignment = Alignment.Center) {
          NasLoadingBlock(stringResource(R.string.nas_loading))
        }
        NasTrashState.Denied -> Box(Modifier.fillMaxSize().padding(top = insets.top, bottom = insets.bottom), contentAlignment = Alignment.Center) {
          NasEmptyState(
            icon = Icons.Rounded.Lock,
            title = stringResource(R.string.nas_denied_title),
            detail = stringResource(R.string.nas_denied_detail),
            tone = GalleryIllustrationTone.Danger,
          )
        }
        is NasTrashState.Error -> Box(Modifier.fillMaxSize().padding(top = insets.top, bottom = insets.bottom), contentAlignment = Alignment.Center) {
          NasEmptyState(
            icon = Icons.Rounded.Delete,
            title = stringResource(R.string.nas_error_title),
            detail = stringResource(R.string.nas_error_detail),
            tone = GalleryIllustrationTone.Caution,
            actionLabel = stringResource(R.string.nas_retry),
            onAction = actions.onRetry,
          )
        }
        is NasTrashState.Ready, is NasTrashState.Offline -> {
          LazyColumn(
            modifier = Modifier.fillMaxHeight().widthIn(max = NasDimens.ListMaxWidth).fillMaxWidth(),
            contentPadding = PaddingValues(start = GalleryDimens.Gutter, end = GalleryDimens.Gutter, top = insets.top, bottom = insets.bottom),
          ) {
            trashStatusItems(state, actions)
            if (state.items.isEmpty()) {
              item(key = "empty") {
                NasEmptyState(
                  icon = Icons.Rounded.Delete,
                  title = stringResource(R.string.nas_trash_empty_title),
                  detail = stringResource(R.string.nas_trash_empty_detail),
                )
              }
            } else {
              groupedRows(state.items, "trash") { item ->
                TrashRow(item = item, selecting = state.selecting, selected = item.key() in state.selection, onClick = { actions.onToggle(item) }, canSelect = state.canModify)
              }
              item(key = "footnote") {
                Text(
                  text = stringResource(R.string.nas_trash_footnote),
                  modifier = Modifier.padding(NasDimens.BlockGap),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }
      }
    }
  }
  NasConfirmSheet(
    plan = state.confirm,
    areas = state.areas,
    monotonicMs = monotonicMs,
    onConfirmed = actions.onConfirmed,
    onDismiss = actions.onDismissConfirm,
  )
}

@Composable
private fun trashLabel(state: TrashUi, count: Int): NasLabel = when {
  state.selecting -> NasLabel(
    title = if (state.selection.isEmpty()) stringResource(R.string.nas_select_items) else countText(R.plurals.nas_selected_count, state.selection.size),
    subtitle = null,
  )
  else -> NasLabel(
    title = stringResource(R.string.nas_trash_title),
    subtitle = if (count == 0) stringResource(R.string.nas_trash_subtitle_empty) else stringResource(R.string.nas_trash_subtitle_count, countText(R.plurals.nas_items_count, count)),
  )
}

@Composable
private fun trashCommands(state: TrashUi, actions: TrashActions): (@Composable RowScope.() -> Unit)? = when {
  state.selecting -> {
    {
      GalleryChromeIconButton(icon = Icons.Rounded.SelectAll, contentDescription = stringResource(R.string.nas_select_all), onClick = actions.onSelectAll)
      GalleryChromeTextButton(label = stringResource(R.string.nas_done), onClick = actions.onClearSelection)
    }
  }
  state.items.isEmpty() -> null
  else -> {
    {
      GalleryChromeIconButton(
        icon = Icons.Rounded.Checklist,
        contentDescription = stringResource(R.string.nas_trash_select),
        onClick = actions.onStartSelecting,
        enabled = state.canModify,
      )
    }
  }
}

/** Le frasi in cima: l'esito dell'ultimo gesto, il NAS che non risponde, la sola lettura, le voci che non si possono eliminare subito. */
private fun LazyListScope.trashStatusItems(state: TrashUi, actions: TrashActions) {
  state.notice?.let { notice ->
    item(key = "notice") {
      NasNoticeLine(notice = notice, onDismiss = actions.onDismissNotice, modifier = Modifier.padding(bottom = NasDimens.BlockGap), onOpenOperations = actions.onOpenOperations)
    }
  }
  if (state.offline) {
    item(key = "offline") {
      NasNotice(
        tone = NasTone.Attention,
        glyph = Icons.Rounded.CloudOff,
        title = stringResource(R.string.nas_trash_offline_title),
        detail = stringResource(R.string.nas_trash_offline_detail),
        modifier = Modifier.padding(bottom = NasDimens.BlockGap),
        actionLabel = stringResource(R.string.nas_retry),
        onAction = actions.onRetry,
      )
    }
  } else if (state.list is NasTrashState.Ready && !state.canModify && state.items.isNotEmpty()) {
    item(key = "read-only") {
      NasNotice(
        tone = NasTone.Waiting,
        glyph = Icons.Rounded.Lock,
        title = stringResource(R.string.nas_trash_readonly_title),
        detail = stringResource(R.string.nas_trash_readonly_detail),
        modifier = Modifier.padding(bottom = NasDimens.BlockGap),
      )
    }
  }
  // Se tra le scelte ce n'e' una non propria, "Elimina subito" e' spento: la frase dice perche'.
  if (state.selecting && state.selectedItems.any { !it.deletableNow }) {
    item(key = "only-own") {
      NasNotice(
        tone = NasTone.Waiting,
        glyph = Icons.Rounded.Lock,
        title = stringResource(R.string.nas_trash_purge_only_own_title),
        detail = stringResource(R.string.nas_trash_purge_only_own),
        modifier = Modifier.padding(bottom = NasDimens.BlockGap),
      )
    }
  }
}

// --- Una voce --------------------------------------------------------------------------------------------------------------

/**
 * Una voce: il nome, da dove veniva e quanto pesa, e sotto quanti giorni restano e chi l'ha eliminata. La pillola compare **solo**
 * quando c'e' qualcosa da notare (scade presto, oggi, nessun record valido): le righe non cambiano altezza per un'informazione che
 * c'e' sempre, e il colore non e' mai l'unico segnale (la pillola ha la parola, la riga dice i giorni).
 */
@Composable
private fun TrashRow(item: NasTrashItem, selecting: Boolean, selected: Boolean, canSelect: Boolean, onClick: () -> Unit) {
  val name = item.name ?: stringResource(R.string.nas_trash_no_name)
  val kind = item.name?.takeIf { !item.isFolder }?.let(NasText::kindOf)
  val icon = when {
    item.isFolder -> Icons.Rounded.Folder
    kind == FileKind.IMAGE -> Icons.Rounded.Image
    kind == FileKind.VIDEO -> Icons.Rounded.Movie
    else -> Icons.Rounded.InsertDriveFile
  }
  val place = item.originalFolder?.let { NasText.path(it.components) }
  val fileCount = item.fileCount
  val size = if (item.isFolder && fileCount != null) {
    stringResource(R.string.nas_trash_folder_files, countText(R.plurals.nas_files_count, fileCount), NasText.size(item.size))
  } else {
    NasText.size(item.size)
  }
  val subtitle = when {
    item.withoutValidRecord -> stringResource(R.string.nas_trash_invalid_detail)
    place != null -> stringResource(R.string.nas_trash_from, place, size)
    else -> stringResource(R.string.nas_trash_from_root, size)
  }
  val author = item.author
  val by = when {
    item.withoutValidRecord -> null
    item.reason == TrashReason.MOVE -> stringResource(R.string.nas_trash_moved)
    // I4: «eliminato da questo dispositivo» e' il caso di ogni giorno: non si scrive (lo dice TalkBack); si scrive solo chi altro e' stato.
    item.deletableNow -> null
    author != null -> stringResource(R.string.nas_trash_by, author)
    else -> null
  }
  val left = daysLeftText(item.daysLeft)
  val meta = listOfNotNull(left, by).joinToString(" · ").takeIf { it.isNotEmpty() }
  val pill = urgencyNote(item)
  val description = listOfNotNull(
    name,
    if (item.isFolder) stringResource(R.string.nas_trash_desc_folder_word) else null,
    size,
    left,
    if (item.deletableNow && !item.withoutValidRecord && item.reason != TrashReason.MOVE) stringResource(R.string.nas_trash_by_me) else null,
    if (item.withoutValidRecord) stringResource(R.string.nas_trash_invalid_title) else null,
  ).joinToString(", ")
  val stateText = stringResource(if (selected) R.string.nas_folders_state_selected else R.string.nas_folders_state_not_selected)
  FluidListRow(
    title = name,
    subtitle = subtitle,
    meta = meta,
    tone = if (item.withoutValidRecord) FluidTone.Danger else FluidTone.Neutral,
    leading = { Icon(imageVector = icon, contentDescription = null) },
    labels = pill?.let { note -> { GalleryFooterPill(note = note, onClick = null) } },
    badge = { NasTrailingSlot(selecting = selecting, selected = selected, opens = false) },
    disclosure = false,
    titleMaxLines = 2,
    onClick = if (canSelect) onClick else null,
    onLongClick = if (canSelect && !selecting) onClick else null,
    modifier = Modifier.semantics(mergeDescendants = true) {
      contentDescription = description
      if (selecting) {
        this.selected = selected
        stateDescription = stateText
      }
    },
  )
}

/** "Restano 29 giorni", "Scade oggi"; null se la scadenza non si sa (ora del NAS non ancorata). */
@Composable
private fun daysLeftText(days: Int?): String? = when {
  days == null -> null
  days <= 0 -> stringResource(R.string.nas_trash_expires_today)
  else -> pluralStringResource(R.plurals.nas_trash_days_left, days, GalleryNumbers.format(days))
}

/** La pillola, solo se serve: nessun record valido, scade oggi, scade fra pochi giorni. */
@Composable
private fun urgencyNote(item: NasTrashItem): GalleryFooterNote? {
  val days = item.daysLeft
  return when {
    item.withoutValidRecord -> GalleryFooterNote(GalleryFooterTone.Alarm, stringResource(R.string.nas_trash_invalid_pill))
    days != null && days <= 0 -> GalleryFooterNote(GalleryFooterTone.Attention, stringResource(R.string.nas_trash_days_today))
    days != null && days <= SOON_DAYS -> GalleryFooterNote(GalleryFooterTone.Attention, stringResource(R.string.nas_trash_soon_pill))
    else -> null
  }
}

/** Da quanti giorni rimasti in giu' la riga chiede attenzione. */
private const val SOON_DAYS = 3
