package dev.pampa.mycloudgallery.feature.nas.folders

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeActionBar
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeIconButton
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeTextButton
import dev.pampa.mycloudgallery.core.designsystem.design.galleryChromeTag
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryBarAction
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryFooterEntry
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryFooterNote
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryFooterTone
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryIllustrationTone
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.nasops.contract.NasFolderEntry
import dev.pampa.mycloudgallery.core.nasops.contract.NasFolderState
import dev.pampa.mycloudgallery.core.nasops.contract.OpPlan
import dev.pampa.mycloudgallery.core.nasops.contract.UserConfirmation
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.nas.NasDimens
import dev.pampa.mycloudgallery.feature.nas.R
import dev.pampa.mycloudgallery.feature.nas.confirm.NasConfirmSheet
import dev.pampa.mycloudgallery.feature.nas.confirm.NasNameSheet
import dev.pampa.mycloudgallery.feature.nas.confirm.areaLabel
import dev.pampa.mycloudgallery.feature.nas.contract.NasArea
import dev.pampa.mycloudgallery.feature.nas.contract.NasAreaKind
import dev.pampa.mycloudgallery.feature.nas.ui.FileKind
import dev.pampa.mycloudgallery.feature.nas.ui.NasActionCapsule
import dev.pampa.mycloudgallery.feature.nas.ui.NasBarButton
import dev.pampa.mycloudgallery.feature.nas.ui.NasEmptyState
import dev.pampa.mycloudgallery.feature.nas.ui.NasFade
import dev.pampa.mycloudgallery.feature.nas.ui.NasInsets
import dev.pampa.mycloudgallery.feature.nas.ui.NasLabel
import dev.pampa.mycloudgallery.feature.nas.ui.NasLoadingBlock
import dev.pampa.mycloudgallery.feature.nas.ui.NasNotice
import dev.pampa.mycloudgallery.feature.nas.ui.NasNoticeLine
import dev.pampa.mycloudgallery.feature.nas.ui.NasPage
import dev.pampa.mycloudgallery.feature.nas.ui.NasSectionTitle
import dev.pampa.mycloudgallery.feature.nas.ui.NasTrailingSlot
import dev.pampa.mycloudgallery.feature.nas.ui.NasText
import dev.pampa.mycloudgallery.feature.nas.ui.NasTone
import dev.pampa.mycloudgallery.feature.nas.ui.countText
import dev.pampa.mycloudgallery.feature.nas.ui.groupedRows

/**
 * **Cartelle NAS**: l'elenco delle aree e, dentro un'area, le sue cartelle e i suoi file; crea, rinomina, sposta ed elimina. Il
 * linguaggio grafico e' quello della Libreria (G2, vetro adattivo): la riga in alto con indietro, etichetta e comandi, la lista che
 * scorre sotto, le azioni della selezione nel posto della pillola. Su uno schermo largo ha due pannelli: le aree e il percorso a
 * sinistra, la cartella a destra.
 *
 * @param onBack il tasto indietro quando non si puo' salire piu'; null dove la pagina e' il pannello di dettaglio di un tablet.
 * @param hostPadding l'ingombro della pillola delle schede quando la pagina sta nel pannello di dettaglio.
 * @param onOpenOperations "Vedi" nell'avviso dopo un'operazione e nella striscia delle operazioni in corso.
 * @param onOpenFile cosa fare toccando un file (di solito aprire il viewer, che e' di `:app`); null = il tocco lo seleziona.
 */
@Composable
fun NasFoldersRoute(
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  hostPadding: PaddingValues = PaddingValues(),
  backdropOut: ((GlassBackdropState) -> Unit)? = null,
  onOpenOperations: () -> Unit = {},
  onOpenFile: ((share: String, path: SharePath) -> Unit)? = null,
  viewModel: NasFoldersViewModel = hiltViewModel(),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val actions = remember(viewModel, onOpenOperations, onOpenFile) {
    FoldersActions(
      onOpenArea = viewModel::openArea,
      onOpenFolder = viewModel::openFolder,
      onOpenFile = { entry -> viewModel.state.value.where?.let { onOpenFile?.invoke(it.share, entry.path) } },
      onGoTo = viewModel::goTo,
      onToggle = viewModel::toggle,
      onSelectAll = viewModel::selectAll,
      onClearSelection = viewModel::clearSelection,
      onStartSelecting = viewModel::startSelecting,
      onNewFolder = viewModel::openNewFolder,
      onRename = viewModel::openRename,
      onMove = viewModel::startMove,
      onTrash = viewModel::requestTrash,
      onMoveHere = viewModel::moveHere,
      onCancelMove = viewModel::cancelMove,
      onRetry = viewModel::refresh,
      onOpenOperations = onOpenOperations,
      onDismissNotice = viewModel::dismissNotice,
      onConfirmed = viewModel::confirmed,
      onDismissConfirm = viewModel::dismissConfirm,
      onNameChanged = viewModel::onNameChanged,
      onUseSuggestion = viewModel::onNameChanged,
      onDismissName = viewModel::dismissName,
      opensFiles = onOpenFile != null,
    )
  }
  BackHandler(enabled = state.where != null || state.selecting || state.picking) {
    if (!viewModel.up()) onBack?.invoke()
  }
  NasFoldersScreen(
    state = state,
    actions = actions,
    monotonicMs = viewModel::monotonicMs,
    modifier = modifier,
    onBack = if (state.where != null || onBack != null) ({ if (!viewModel.up()) onBack?.invoke() }) else null,
    hostPadding = hostPadding,
    backdropOut = backdropOut,
  )
}

/** Tutto quello che la schermata puo' chiedere al ViewModel. Con i default vuoti la provano i test e gli screenshot. */
internal class FoldersActions(
  val onOpenArea: (NasArea) -> Unit = {},
  val onOpenFolder: (NasFolderEntry) -> Unit = {},
  val onOpenFile: (NasFolderEntry) -> Unit = {},
  val onGoTo: (FolderWhere?) -> Unit = {},
  val onToggle: (SharePath) -> Unit = {},
  val onSelectAll: () -> Unit = {},
  val onClearSelection: () -> Unit = {},
  val onStartSelecting: () -> Unit = {},
  val onNewFolder: () -> Unit = {},
  val onRename: () -> Unit = {},
  val onMove: () -> Unit = {},
  val onTrash: () -> Unit = {},
  val onMoveHere: () -> Unit = {},
  val onCancelMove: () -> Unit = {},
  val onRetry: () -> Unit = {},
  val onOpenOperations: () -> Unit = {},
  val onDismissNotice: () -> Unit = {},
  val onConfirmed: (OpPlan, UserConfirmation) -> Unit = { _, _ -> },
  val onDismissConfirm: () -> Unit = {},
  val onNameChanged: (String) -> Unit = {},
  val onUseSuggestion: (String) -> Unit = {},
  val onDismissName: () -> Unit = {},
  /** Toccare un file lo apre (la callback di `:app`) invece di selezionarlo. */
  val opensFiles: Boolean = false,
)

/** La pagina per uno [state]: nessun effetto oltre a quello che chiede alle [actions]. La provano i test e gli screenshot. */
@Composable
internal fun NasFoldersScreen(
  state: FoldersUiState,
  actions: FoldersActions,
  monotonicMs: () -> Long,
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  hostPadding: PaddingValues = PaddingValues(),
  backdropOut: ((GlassBackdropState) -> Unit)? = null,
) {
  NasPage(
    label = foldersLabel(state),
    modifier = modifier,
    onBack = onBack,
    backdropOut = backdropOut,
    commands = foldersCommands(state, actions),
    hostPadding = hostPadding,
    reserveBottomBar = true,
    bottomBar = { backdrop, style ->
      NasFade(visible = state.selecting) {
        GalleryChromeActionBar(
          actions = selectionActions(state, actions),
          style = style,
          backdrop = backdrop,
          modifier = Modifier.galleryChromeTag("azioni"),
        )
      }
      NasFade(visible = state.picking) {
        NasActionCapsule(style = style, backdrop = backdrop, modifier = Modifier.galleryChromeTag("azioni")) {
          NasBarButton(label = stringResource(R.string.nas_folders_pick_cancel), onClick = actions.onCancelMove, ink = style.ink)
          NasBarButton(
            label = stringResource(R.string.nas_folders_pick_here),
            onClick = actions.onMoveHere,
            ink = style.ink,
            icon = Icons.AutoMirrored.Rounded.DriveFileMove,
            enabled = state.canMoveHere,
          )
        }
      }
    },
  ) { insets ->
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
      if (maxWidth >= NasDimens.TwoPaneMinWidth) {
        Row(modifier = Modifier.fillMaxSize()) {
          Sidebar(state, actions, insets, modifier = Modifier.width(NasDimens.SidebarWidth).fillMaxHeight())
          FolderPane(state, actions, insets, modifier = Modifier.weight(1f).fillMaxHeight())
        }
      } else {
        FolderPane(state, actions, insets, modifier = Modifier.fillMaxSize())
      }
    }
  }
  NasConfirmSheet(
    plan = state.confirm,
    areas = state.areas,
    monotonicMs = monotonicMs,
    onConfirmed = actions.onConfirmed,
    onDismiss = actions.onDismissConfirm,
    hidden = state.confirmHidden,
    leavePlan = state.confirmLeave,
  )
  NasNameSheet(
    draft = state.name,
    areas = state.areas,
    monotonicMs = monotonicMs,
    onTextChange = actions.onNameChanged,
    onUseSuggestion = actions.onUseSuggestion,
    onConfirmed = actions.onConfirmed,
    onDismiss = actions.onDismissName,
  )
}

// --- Chrome ----------------------------------------------------------------------------------------------------------------

@Composable
private fun foldersLabel(state: FoldersUiState): NasLabel {
  val mode = state.mode
  return when {
    state.selecting -> NasLabel(
      title = if (state.selection.isEmpty()) stringResource(R.string.nas_select_items) else countText(R.plurals.nas_selected_count, state.selection.size),
      subtitle = null,
    )
    mode is FoldersMode.PickDestination -> NasLabel(
      title = countText(R.plurals.nas_folders_pick_title, mode.items.size),
      subtitle = state.where?.let { stringResource(R.string.nas_folders_pick_into, NasText.path(listOf(areaLabel(it.share, state.areas)) + it.dir?.components.orEmpty())) }
        ?: stringResource(R.string.nas_folders_pick_subtitle),
    )
    state.where == null -> NasLabel(stringResource(R.string.nas_folders_title), stringResource(R.string.nas_folders_pick_area))
    else -> {
      val where = state.where
      val area = areaLabel(where.share, state.areas)
      val count = (state.listing as? NasFolderState.Listing)?.entries?.size?.let { countText(R.plurals.nas_items_count, it) }
      NasLabel(
        title = where.dir?.name ?: area,
        subtitle = when {
          where.dir == null -> count
          count == null -> area
          else -> stringResource(R.string.nas_folders_subtitle_in_area, area, count)
        },
      )
    }
  }
}

@Composable
private fun foldersCommands(state: FoldersUiState, actions: FoldersActions): (@Composable RowScope.() -> Unit)? = when {
  state.selecting -> {
    {
      GalleryChromeIconButton(icon = Icons.Rounded.SelectAll, contentDescription = stringResource(R.string.nas_select_all), onClick = actions.onSelectAll)
      GalleryChromeTextButton(label = stringResource(R.string.nas_done), onClick = actions.onClearSelection)
    }
  }
  state.where == null -> null
  else -> {
    {
      GalleryChromeIconButton(
        icon = Icons.Rounded.CreateNewFolder,
        contentDescription = stringResource(R.string.nas_folders_new_folder),
        onClick = actions.onNewFolder,
        enabled = state.canModify,
      )
      if (!state.picking) {
        GalleryChromeIconButton(
          icon = Icons.Rounded.Checklist,
          contentDescription = stringResource(R.string.nas_folders_start_select),
          onClick = actions.onStartSelecting,
          enabled = state.canModify && state.entries.isNotEmpty(),
        )
      }
    }
  }
}

@Composable
private fun selectionActions(state: FoldersUiState, actions: FoldersActions): List<GalleryBarAction> {
  val any = state.selection.isNotEmpty()
  return listOf(
    GalleryBarAction(Icons.Rounded.DriveFileRenameOutline, stringResource(R.string.nas_folders_action_rename), actions.onRename, enabled = state.selection.size == 1),
    GalleryBarAction(Icons.AutoMirrored.Rounded.DriveFileMove, stringResource(R.string.nas_folders_action_move), actions.onMove, enabled = any),
    GalleryBarAction(Icons.Rounded.Delete, stringResource(R.string.nas_folders_action_trash), actions.onTrash, destructive = true, enabled = any),
  )
}

// --- Il pannello della cartella --------------------------------------------------------------------------------------------

@Composable
private fun FolderPane(state: FoldersUiState, actions: FoldersActions, insets: NasInsets, modifier: Modifier) {
  val where = state.where
  Box(modifier = modifier, contentAlignment = Alignment.TopCenter) {
    when {
      where == null -> AreaList(state, actions, insets)
      else -> when (val listing = state.listing) {
        NasFolderState.Loading -> Box(Modifier.fillMaxSize().padding(top = insets.top, bottom = insets.bottom), contentAlignment = Alignment.Center) {
          NasLoadingBlock(stringResource(R.string.nas_loading))
        }
        NasFolderState.Denied -> StateBox(insets) {
          NasEmptyState(
            icon = Icons.Rounded.Lock,
            title = stringResource(R.string.nas_denied_title),
            detail = stringResource(R.string.nas_denied_detail),
            tone = GalleryIllustrationTone.Danger,
          )
        }
        is NasFolderState.Error -> StateBox(insets) {
          NasEmptyState(
            icon = Icons.Rounded.FolderOff,
            title = stringResource(R.string.nas_error_title),
            detail = stringResource(R.string.nas_error_detail),
            tone = GalleryIllustrationTone.Caution,
            actionLabel = stringResource(R.string.nas_retry),
            onAction = actions.onRetry,
          )
        }
        is NasFolderState.Listing, is NasFolderState.Offline -> EntryList(state, actions, insets)
      }
    }
  }
}

@Composable
private fun StateBox(insets: NasInsets, content: @Composable () -> Unit) {
  Box(Modifier.fillMaxSize().padding(top = insets.top, bottom = insets.bottom), contentAlignment = Alignment.Center) { content() }
}

private fun listPadding(insets: NasInsets): PaddingValues =
  PaddingValues(start = GalleryDimens.Gutter, end = GalleryDimens.Gutter, top = insets.top, bottom = insets.bottom)

@Composable
private fun EntryList(state: FoldersUiState, actions: FoldersActions, insets: NasInsets) {
  val entries = state.entries
  val listing = state.listing
  LazyColumn(
    modifier = Modifier.fillMaxHeight().widthIn(max = NasDimens.ListMaxWidth).fillMaxWidth(),
    contentPadding = listPadding(insets),
  ) {
    statusItems(state, actions)
    if (entries.isEmpty()) {
      item(key = "empty") {
        NasEmptyState(
          icon = Icons.Rounded.Folder,
          title = stringResource(if (listing is NasFolderState.Offline) R.string.nas_folders_empty_offline_title else R.string.nas_folders_empty_title),
          detail = stringResource(
            when {
              listing is NasFolderState.Offline -> R.string.nas_offline_detail_none
              state.readOnly -> R.string.nas_folders_empty_detail_read_only
              else -> R.string.nas_folders_empty_detail
            },
          ),
        )
      }
    } else {
      val moving = (state.mode as? FoldersMode.PickDestination)?.items.orEmpty()
      groupedRows(entries, "entries") { entry ->
        val blocked = state.picking && moving.any { it.share.equals(state.where?.share, ignoreCase = true) && it.path == entry.path }
        EntryRow(
          entry = entry,
          selecting = state.selecting,
          selected = entry.path in state.selection,
          dimmed = blocked || (state.picking && !entry.isFolder),
          blocked = blocked,
          onClick = {
            when {
              state.selecting -> actions.onToggle(entry.path)
              entry.isFolder -> if (!blocked) actions.onOpenFolder(entry)
              state.picking -> Unit
              actions.opensFiles -> actions.onOpenFile(entry)
              state.canModify -> actions.onToggle(entry.path)
            }
          },
          onLongClick = if (state.canModify && !state.picking) ({ actions.onToggle(entry.path) }) else null,
        )
      }
    }
  }
}

/** Le frasi in cima alla lista: l'esito dell'ultimo gesto, le operazioni in corso, la sola lettura, il NAS che non risponde. */
private fun LazyListScope.statusItems(state: FoldersUiState, actions: FoldersActions) {
  state.notice?.let { notice ->
    item(key = "notice") {
      NasNoticeLine(
        notice = notice,
        onDismiss = actions.onDismissNotice,
        modifier = Modifier.padding(bottom = NasDimens.BlockGap),
        onOpenOperations = actions.onOpenOperations,
      )
    }
  }
  if (state.activeOperations > 0) {
    item(key = "operations") { OperationsStrip(state.activeOperations, actions.onOpenOperations) }
  }
  if (state.readOnly) {
    item(key = "read-only") {
      NasNotice(
        tone = NasTone.Waiting,
        glyph = Icons.Rounded.Lock,
        title = stringResource(R.string.nas_readonly_title),
        detail = stringResource(R.string.nas_readonly_detail),
        modifier = Modifier.padding(bottom = NasDimens.BlockGap),
      )
    }
  }
  (state.listing as? NasFolderState.Offline)?.let { offline ->
    item(key = "offline") {
      NasNotice(
        tone = NasTone.Attention,
        glyph = Icons.Rounded.CloudOff,
        title = stringResource(R.string.nas_offline_title),
        detail = offline.cachedAt?.let { stringResource(R.string.nas_offline_detail_saved, NasText.date(it), NasText.time(it)) } ?: stringResource(R.string.nas_offline_detail),
        modifier = Modifier.padding(bottom = NasDimens.BlockGap),
        actionLabel = stringResource(R.string.nas_retry),
        onAction = actions.onRetry,
      )
    }
  }
}

@Composable
private fun OperationsStrip(count: Int, onOpen: () -> Unit) {
  GalleryFooterEntry(
    note = GalleryFooterNote(
      tone = GalleryFooterTone.Working,
      label = pluralStringResource(R.plurals.nas_folders_ops_strip, count, NasText.count(count)),
      busy = true,
      detail = stringResource(R.string.nas_folders_ops_strip_detail),
    ),
    onClick = onOpen,
    modifier = Modifier.padding(bottom = NasDimens.BlockGap),
    alignment = Alignment.Start,
  )
}

// --- Una riga --------------------------------------------------------------------------------------------------------------

@Composable
private fun EntryRow(
  entry: NasFolderEntry,
  selecting: Boolean,
  selected: Boolean,
  dimmed: Boolean,
  blocked: Boolean,
  onClick: () -> Unit,
  onLongClick: (() -> Unit)?,
) {
  val kind = if (entry.isFolder) null else NasText.kindOf(entry.name)
  val icon = when {
    entry.isFolder -> Icons.Rounded.Folder
    kind == FileKind.IMAGE -> Icons.Rounded.Image
    kind == FileKind.VIDEO -> Icons.Rounded.Movie
    else -> Icons.Rounded.InsertDriveFile
  }
  val tone = when {
    entry.isFolder -> FluidTone.Primary
    kind == FileKind.IMAGE -> FluidTone.Info
    kind == FileKind.VIDEO -> FluidTone.Success
    else -> FluidTone.Neutral
  }
  val countLine = entry.fileCount?.let { countText(R.plurals.nas_files_count, it) }
  val subtitle = when {
    blocked -> stringResource(R.string.nas_folders_moving)
    entry.isFolder -> countLine ?: stringResource(R.string.nas_folders_row_folder)
    else -> stringResource(R.string.nas_folders_row_file, NasText.size(entry.size), NasText.date(entry.modifiedAtNas))
  }
  val description = when {
    entry.isFolder && countLine != null -> stringResource(R.string.nas_folders_desc_folder_count, entry.name, countLine)
    entry.isFolder -> stringResource(R.string.nas_folders_desc_folder, entry.name)
    kind == FileKind.IMAGE -> stringResource(R.string.nas_folders_desc_photo, entry.name, NasText.size(entry.size), NasText.date(entry.modifiedAtNas))
    kind == FileKind.VIDEO -> stringResource(R.string.nas_folders_desc_video, entry.name, NasText.size(entry.size), NasText.date(entry.modifiedAtNas))
    else -> stringResource(R.string.nas_folders_desc_file, entry.name, NasText.size(entry.size), NasText.date(entry.modifiedAtNas))
  }
  val stateText = stringResource(if (selected) R.string.nas_folders_state_selected else R.string.nas_folders_state_not_selected)
  FluidListRow(
    title = entry.name,
    subtitle = subtitle,
    tone = tone,
    leading = { Icon(imageVector = icon, contentDescription = null) },
    badge = { NasTrailingSlot(selecting = selecting, selected = selected, opens = entry.isFolder && !blocked) },
    disclosure = false,
    titleMaxLines = 2,
    onClick = onClick,
    onLongClick = onLongClick,
    modifier = Modifier
      .semantics(mergeDescendants = true) {
        contentDescription = description
        if (selecting) {
          this.selected = selected
          stateDescription = stateText
        }
      }
      .dimmed(dimmed),
  )
}

private fun Modifier.dimmed(on: Boolean): Modifier = if (on) this.alpha(DimmedAlpha) else this

private const val DimmedAlpha = 0.45f

// --- L'elenco delle aree e il pannello di sinistra --------------------------------------------------------------------------

@Composable
private fun AreaList(state: FoldersUiState, actions: FoldersActions, insets: NasInsets) {
  LazyColumn(
    modifier = Modifier.fillMaxHeight().widthIn(max = NasDimens.ListMaxWidth).fillMaxWidth(),
    contentPadding = listPadding(insets),
  ) {
    statusItems(state, actions)
    if (state.areas.isEmpty()) {
      item(key = "no-areas") {
        NasEmptyState(
          icon = Icons.Rounded.FolderOff,
          title = stringResource(R.string.nas_folders_no_areas_title),
          detail = stringResource(R.string.nas_folders_no_areas_detail),
          tone = GalleryIllustrationTone.Caution,
        )
      }
    } else {
      item(key = "areas-title") { NasSectionTitle(stringResource(R.string.nas_folders_areas_header), Modifier.padding(bottom = NasDimens.LineGap)) }
      item(key = "areas") { AreaGroup(state, actions, selectedShare = null, disclosure = true) }
      item(key = "footnote") {
        Text(
          text = stringResource(R.string.nas_folders_areas_footnote),
          modifier = Modifier.padding(NasDimens.BlockGap),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun AreaGroup(state: FoldersUiState, actions: FoldersActions, selectedShare: String?, disclosure: Boolean) {
  FluidListGroup {
    state.areas.forEachIndexed { index, area ->
      if (index > 0) FluidListDivider()
      FluidListRow(
        title = areaLabel(area.share, state.areas),
        subtitle = stringResource(if (area.kind == NasAreaKind.PRIVATE) R.string.nas_area_private_hint else R.string.nas_area_common_hint),
        tone = if (area.kind == NasAreaKind.PRIVATE) FluidTone.Primary else FluidTone.Info,
        leading = { Icon(imageVector = if (area.kind == NasAreaKind.PRIVATE) Icons.Rounded.Person else Icons.Rounded.Group, contentDescription = null) },
        selected = selectedShare != null && area.share.equals(selectedShare, ignoreCase = true),
        disclosure = disclosure,
        onClick = { actions.onOpenArea(area) },
      )
    }
  }
}

/** Il pannello di sinistra su due pannelli: le aree e, sotto, il percorso fino alla cartella aperta (ogni livello si tocca). */
@Composable
private fun Sidebar(state: FoldersUiState, actions: FoldersActions, insets: NasInsets, modifier: Modifier) {
  val where = state.where
  LazyColumn(
    modifier = modifier,
    contentPadding = PaddingValues(start = GalleryDimens.Gutter, end = NasDimens.BlockGap, top = insets.top, bottom = insets.bottom),
    verticalArrangement = Arrangement.spacedBy(NasDimens.BlockGap),
  ) {
    item(key = "sidebar-areas-title") { NasSectionTitle(stringResource(R.string.nas_folders_areas_header)) }
    item(key = "sidebar-areas") { AreaGroup(state, actions, selectedShare = where?.share, disclosure = false) }
    val dir = where?.dir
    if (where != null && dir != null) {
      item(key = "sidebar-path-title") { NasSectionTitle(stringResource(R.string.nas_folders_path_header)) }
      item(key = "sidebar-path") {
        FluidListGroup {
          dir.components.forEachIndexed { depth, name ->
            if (depth > 0) FluidListDivider()
            val here = depth == dir.components.lastIndex
            val target = dir.components.take(depth + 1).let { parts -> SharePath.of(*parts.toTypedArray()) }
            FluidListRow(
              title = name,
              subtitle = "",
              tone = FluidTone.Primary,
              leading = { Icon(imageVector = Icons.Rounded.Folder, contentDescription = null) },
              selected = here,
              disclosure = false,
              onClick = { actions.onGoTo(FolderWhere(where.share, target)) },
              modifier = Modifier.padding(start = NasDimens.PathIndent * depth),
            )
          }
        }
      }
    }
  }
}
