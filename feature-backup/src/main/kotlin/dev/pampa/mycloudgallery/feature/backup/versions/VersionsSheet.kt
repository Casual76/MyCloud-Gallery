package dev.pampa.mycloudgallery.feature.backup.versions

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidSpinner
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidStatusBadge
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.backup.contract.NasVersionPlan
import dev.pampa.mycloudgallery.core.backup.contract.RestoreAvailability
import dev.pampa.mycloudgallery.core.backup.contract.VersionArea
import dev.pampa.mycloudgallery.core.backup.contract.VersionEntry
import dev.pampa.mycloudgallery.core.backup.contract.VersionPlace
import dev.pampa.mycloudgallery.core.backup.contract.VersionRole
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryConfirmSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetScaffold
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.feature.backup.BackupDimens
import dev.pampa.mycloudgallery.feature.backup.R
import dev.pampa.mycloudgallery.feature.backup.free.BindDialogBridge
import dev.pampa.mycloudgallery.feature.backup.free.ConfirmTaps
import dev.pampa.mycloudgallery.feature.backup.free.rememberConfirmTaps
import dev.pampa.mycloudgallery.feature.backup.ui.BackupButton
import dev.pampa.mycloudgallery.feature.backup.ui.BackupText
import dev.pampa.mycloudgallery.feature.backup.ui.atText

// Fase 5B (P12, docs/fase5-progetto.md §1.5 `VersionsState`, §2.4, D17, D18, D35, E-E2, E-E10; R7 della 4B): il foglio delle VERSIONI di un media
// (menu ⋮ del viewer → Versioni, e → Ripristina originale). E' un foglio di conferma del design system (`GalleryConfirmSheet`: vetro, tocchi
// oscurati scartati, sovrapposizioni delle altre app nascoste) che `:app` apre alla radice, sopra il viewer: i feature non si conoscono, quindi il
// viewer chiede (callback) e `:app` mostra questo foglio. Dice dall'originale all'attuale cosa c'e', dove sta, se e' al sicuro; "Ripristina
// originale" ha il suo riepilogo e il suo tocco: la conferma nasce **solo** da quel tocco. Solo componenti G2, solo numeri e date.

/** Il foglio delle versioni di [mediaId], aperto da `:app` sopra il viewer; `null` = chiuso. [start] sceglie lista o riepilogo del ripristino. */
@Composable
fun VersionsSheetHost(mediaId: MediaId?, onDismiss: () -> Unit, start: VersionsStart = VersionsStart.LIST) {
  // Il foglio che si chiude fa la sua uscita con il contenuto che aveva: ricorda l'ultimo media finche' l'animazione non e' finita.
  var shown by remember { mutableStateOf<MediaId?>(null) }
  if (mediaId != null) shown = mediaId
  val showing = shown ?: return
  VersionsSheetRoute(mediaId = showing, start = start, visible = mediaId != null, onDismiss = onDismiss)
}

@Composable
private fun VersionsSheetRoute(
  mediaId: MediaId,
  start: VersionsStart,
  visible: Boolean,
  onDismiss: () -> Unit,
  viewModel: VersionsViewModel = hiltViewModel(key = "versioni-${mediaId.value}"),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  BindDialogBridge(viewModel.bridge)
  LaunchedEffect(mediaId, visible, start) {
    if (visible) {
      viewModel.reset()
      viewModel.bind(mediaId, start)
    }
  }
  val plan = (state as? VersionsUi.ConfirmRestore)?.plan
  val taps = rememberConfirmTaps(plan, viewModel::monotonicMs)
  VersionsSheet(
    visible = visible,
    state = state,
    taps = taps,
    actions = VersionsSheetActions(
      askRestore = viewModel::askRestore,
      backToList = viewModel::backToList,
      confirmRestore = { confirmation -> viewModel.restore(confirmation) },
      view = viewModel::view,
      deleteIntermediates = viewModel::deleteIntermediates,
      close = onDismiss,
    ),
    onDismissRequest = onDismiss,
  )
}

/** Le uscite del foglio: tutte partono da un tocco. */
internal class VersionsSheetActions(
  val askRestore: () -> Unit = {},
  val backToList: () -> Unit = {},
  val confirmRestore: (dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmation) -> Unit = {},
  val view: (VersionId) -> Unit = {},
  val deleteIntermediates: () -> Unit = {},
  val close: () -> Unit = {},
)

/** Il foglio nel suo pop-up (con lo scudo contro le sovrapposizioni di `GalleryConfirmSheet`). */
@Composable
internal fun VersionsSheet(
  visible: Boolean,
  state: VersionsUi,
  taps: ConfirmTaps?,
  actions: VersionsSheetActions,
  onDismissRequest: () -> Unit,
) {
  GalleryConfirmSheet(visible = visible, onDismissRequest = onDismissRequest, paneTitle = stringResource(R.string.backup_versions_title)) {
    VersionsSheetContent(state = state, taps = taps, actions = actions)
  }
}

/** Il contenuto del foglio (anche per gli screenshot e i test, senza il pop-up). */
@Composable
internal fun VersionsSheetContent(state: VersionsUi, taps: ConfirmTaps?, actions: VersionsSheetActions, modifier: Modifier = Modifier) {
  when (state) {
    VersionsUi.Loading -> BusySheet(
      icon = Icons.Rounded.History,
      title = stringResource(R.string.backup_versions_loading_title),
      detail = stringResource(R.string.backup_versions_loading_detail),
      onClose = actions.close,
      modifier = modifier,
    )
    VersionsUi.Unavailable -> NoticeSheet(
      title = stringResource(R.string.backup_versions_unavailable_title),
      detail = stringResource(R.string.backup_versions_unavailable_detail),
      onClose = actions.close,
      modifier = modifier,
    )
    is VersionsUi.Listing -> ListingContent(state, actions, modifier)
    is VersionsUi.ConfirmRestore -> ConfirmContent(state, taps, actions, modifier)
    is VersionsUi.AwaitingSystemDialog -> BusySheet(
      icon = Icons.Rounded.Restore,
      title = stringResource(R.string.backup_versions_waiting_title),
      detail = stringResource(R.string.backup_versions_waiting_detail),
      onClose = null,
      modifier = modifier,
    )
    is VersionsUi.Restoring -> BusySheet(
      icon = Icons.Rounded.Restore,
      title = stringResource(R.string.backup_versions_restoring_title),
      detail = stringResource(R.string.backup_versions_restoring_detail),
      onClose = null,
      modifier = modifier,
    )
    is VersionsUi.Restored -> RestoredContent(state, actions, modifier)
  }
}

// --- La lista ---------------------------------------------------------------------------------------------------------------

@Composable
private fun ListingContent(state: VersionsUi.Listing, actions: VersionsSheetActions, modifier: Modifier) {
  val plan = state.plan
  val shown = plan.shownOrder()
  GallerySheetScaffold(
    icon = Icons.Rounded.History,
    tone = GallerySheetTone.Brand,
    title = stringResource(R.string.backup_versions_title),
    message = stringResource(R.string.backup_versions_message),
    modifier = modifier,
    body = {
      state.message?.let { MessageGroup(it) }
      if (shown.size <= 1) {
        FluidListGroup {
          FluidListRow(
            title = stringResource(R.string.backup_versions_empty_title),
            subtitle = stringResource(R.string.backup_versions_empty_detail),
            tone = FluidTone.Neutral,
            leading = { Icon(imageVector = Icons.Rounded.Layers, contentDescription = null) },
            disclosure = false,
          )
        }
      }
      if (shown.isNotEmpty()) {
        FluidListGroup {
          shown.forEachIndexed { index, entry ->
            if (index > 0) FluidListDivider()
            VersionRow(entry, canView = state.canView, onView = { actions.view(entry.versionId) })
          }
        }
      }
      AreaNote(plan.area)
      RestoreReason(plan.restore)
      if (plan.intermediates.isNotEmpty()) {
        FluidListGroup {
          FluidListRow(
            title = stringResource(R.string.backup_versions_intermediates_note),
            subtitle = "",
            tone = FluidTone.Neutral,
            leading = { Icon(imageVector = Icons.Rounded.Layers, contentDescription = null) },
            disclosure = false,
          )
        }
      }
    },
    actions = {
      if (plan.restore.isOffered()) {
        BackupButton(
          label = stringResource(R.string.backup_versions_restore),
          onClick = actions.askRestore,
          enabled = plan.restore == RestoreAvailability.AVAILABLE,
        )
      }
      if (state.canDeleteIntermediates) {
        BackupButton(label = stringResource(R.string.backup_versions_delete_intermediates), onClick = actions.deleteIntermediates, style = FluidButtonStyle.Tinted)
      }
      BackupButton(label = stringResource(R.string.backup_versions_close), onClick = actions.close, style = FluidButtonStyle.Tinted)
    },
  )
}

/** L'ordine in cui si mostrano: quella di adesso, poi le intermedie dalla piu' recente, in fondo l'originale. */
internal fun NasVersionPlan.shownOrder(): List<VersionEntry> {
  val current = versions.filter { it.isCurrent }
  val original = versions.filter { it.role == VersionRole.ORIGINAL && !it.isCurrent }
  val between = versions.filter { !it.isCurrent && it.role != VersionRole.ORIGINAL }.asReversed()
  return current + between + original
}

@Composable
private fun VersionRow(entry: VersionEntry, canView: Boolean, onView: () -> Unit) {
  val role = stringResource(roleRes(entry))
  val saved = entry.savedAtNasMillis?.let { atText(it) }
  val size = BackupText.size(entry.size)
  val whenAndSize = if (saved != null) stringResource(R.string.backup_versions_row_detail, saved, size) else size
  val where = whereText(entry.places)
  val status = stringResource(if (entry.safe) R.string.backup_versions_safe else R.string.backup_versions_unsafe)
  FluidListRow(
    title = role,
    // Quando e quanto sulla prima riga, dove sta sulla seconda: la frase non va a capo con un puntino in testa.
    subtitle = whenAndSize + "\n" + where,
    tone = if (entry.isCurrent) FluidTone.Primary else FluidTone.Neutral,
    leading = { Icon(imageVector = roleIcon(entry), contentDescription = null) },
    labels = { FluidStatusBadge(label = status, tone = if (entry.safe) FluidTone.Success else FluidTone.Warning) },
    titleMaxLines = 2,
    disclosure = canView,
    onClick = if (canView) onView else null,
    // Per TalkBack la riga e' una frase sola: che versione e', quando, dove sta e se e' al sicuro.
    modifier = Modifier.semantics(mergeDescendants = true) {
      contentDescription = stringResource0(role, whenAndSize, where, status)
    },
  )
}

private fun stringResource0(role: String, whenAndSize: String, where: String, status: String): String = "$role, $whenAndSize, $where, $status"

private fun roleRes(entry: VersionEntry): Int = when {
  entry.role == VersionRole.ORIGINAL && entry.isCurrent -> R.string.backup_versions_role_original_current
  entry.role == VersionRole.ORIGINAL -> R.string.backup_versions_role_original
  entry.isCurrent -> R.string.backup_versions_role_current
  else -> R.string.backup_versions_role_intermediate
}

private fun roleIcon(entry: VersionEntry) = when {
  entry.role == VersionRole.ORIGINAL -> Icons.Rounded.History
  entry.isCurrent -> Icons.Rounded.Photo
  else -> Icons.Rounded.Layers
}

/** "sul telefono e sul NAS", "nella cartella Versioni": dove sta una versione, in una frase. */
@Composable
private fun whereText(places: Set<VersionPlace>): String {
  val parts = buildList {
    if (VersionPlace.PHONE in places) add(stringResource(R.string.backup_versions_where_phone))
    if (VersionPlace.NAS_USUAL_PATH in places) add(stringResource(R.string.backup_versions_where_nas))
    if (VersionPlace.NAS_VERSIONS_FOLDER in places) add(stringResource(R.string.backup_versions_where_folder))
    if (VersionPlace.NAS_COMMON_ONLY in places) add(stringResource(R.string.backup_versions_where_common))
  }
  return when (parts.size) {
    0 -> stringResource(R.string.backup_versions_where_nowhere)
    1 -> parts.single()
    else -> parts.dropLast(1).joinToString(", ").let { head -> stringResource(R.string.backup_versions_where_join, head, parts.last()) }
  }
}

@Composable
private fun AreaNote(area: VersionArea) {
  when (area) {
    VersionArea.OWN_PRIVATE -> Unit
    VersionArea.COMMON_NEEDS_NOTICE -> FluidListGroup {
      FluidListRow(
        title = stringResource(R.string.backup_versions_area_common_title),
        subtitle = stringResource(R.string.backup_versions_area_common_detail),
        tone = FluidTone.Info,
        leading = { Icon(imageVector = Icons.Rounded.Warning, contentDescription = null) },
        disclosure = false,
      )
    }
    VersionArea.READ_ONLY_NEW_MEDIA -> FluidListGroup {
      FluidListRow(
        title = stringResource(R.string.backup_versions_area_read_only_title),
        subtitle = stringResource(R.string.backup_versions_area_read_only_detail),
        tone = FluidTone.Warning,
        leading = { Icon(imageVector = Icons.Rounded.Warning, contentDescription = null) },
        disclosure = false,
      )
    }
  }
}

/** Perche' "Ripristina originale" non si puo' fare adesso (se ha senso dirlo): la lista non lascia il tasto spento senza una ragione. */
@Composable
private fun RestoreReason(restore: RestoreAvailability) {
  val reason = when (restore) {
    RestoreAvailability.AVAILABLE -> return
    RestoreAvailability.ONLY_ONE_VERSION -> return
    RestoreAvailability.ALREADY_ORIGINAL -> R.string.backup_versions_why_already_original
    RestoreAvailability.ORIGINAL_NOT_SAFE -> R.string.backup_versions_why_not_safe
    RestoreAvailability.NAS_UNREACHABLE -> R.string.backup_versions_why_nas_unreachable
    RestoreAvailability.FROZEN -> R.string.backup_versions_why_frozen
    RestoreAvailability.READ_ONLY_AREA -> R.string.backup_versions_why_read_only
    RestoreAvailability.NOT_MINE -> R.string.backup_versions_why_not_mine
    RestoreAvailability.NOT_AVAILABLE -> R.string.backup_versions_why_not_available
  }
  FluidListGroup {
    FluidListRow(
      title = stringResource(reason),
      subtitle = "",
      tone = if (restore == RestoreAvailability.ALREADY_ORIGINAL) FluidTone.Neutral else FluidTone.Warning,
      leading = { Icon(imageVector = Icons.Rounded.Warning, contentDescription = null) },
      disclosure = false,
    )
  }
}

@Composable
private fun MessageGroup(message: VersionsMessage) {
  val text = when (message) {
    VersionsMessage.PLAN_CHANGED -> R.string.backup_versions_msg_plan_changed
    VersionsMessage.CONFIRMATION_EXPIRED -> R.string.backup_versions_msg_expired
    VersionsMessage.PHONE_PERMISSION_MISSING -> R.string.backup_versions_msg_permission
    VersionsMessage.NO_SYSTEM_DIALOG -> R.string.backup_versions_msg_no_dialog
  }
  FluidListGroup {
    FluidListRow(
      title = stringResource(text),
      subtitle = "",
      tone = FluidTone.Warning,
      leading = { Icon(imageVector = Icons.Rounded.Warning, contentDescription = null) },
      disclosure = false,
    )
  }
}

// --- Il riepilogo di "Ripristina originale" -----------------------------------------------------------------------------------

@Composable
private fun ConfirmContent(state: VersionsUi.ConfirmRestore, taps: ConfirmTaps?, actions: VersionsSheetActions, modifier: Modifier) {
  val plan = state.plan
  GallerySheetScaffold(
    icon = Icons.Rounded.Restore,
    tone = GallerySheetTone.Caution,
    title = stringResource(R.string.backup_versions_confirm_title),
    message = stringResource(R.string.backup_versions_confirm_message),
    modifier = modifier,
    body = {
      state.message?.let { MessageGroup(it) }
      FluidListGroup {
        plan.original?.let { original ->
          ConfirmRow(R.string.backup_versions_confirm_back_title, original)
        }
        plan.current?.takeIf { it.versionId != plan.original?.versionId }?.let { current ->
          FluidListDivider()
          ConfirmRow(R.string.backup_versions_confirm_away_title, current)
        }
      }
    },
    actions = {
      // L'unico punto dove nasce la conferma: il tocco su questo pulsante.
      BackupButton(
        label = stringResource(R.string.backup_versions_restore),
        onClick = { taps?.tap()?.let(actions.confirmRestore) },
        enabled = taps != null,
      )
      BackupButton(
        label = stringResource(R.string.backup_versions_cancel),
        onClick = {
          taps?.reset()
          actions.backToList()
        },
        style = FluidButtonStyle.Tinted,
      )
    },
  )
}

@Composable
private fun ConfirmRow(titleRes: Int, entry: VersionEntry) {
  val role = stringResource(roleRes(entry))
  val saved = entry.savedAtNasMillis?.let { atText(it) }
  val size = BackupText.size(entry.size)
  FluidListRow(
    title = stringResource(titleRes),
    subtitle = stringResource(R.string.backup_versions_row_detail, role, if (saved != null) stringResource(R.string.backup_versions_row_detail, saved, size) else size),
    tone = FluidTone.Neutral,
    leading = { Icon(imageVector = roleIcon(entry), contentDescription = null) },
    titleMaxLines = 2,
    disclosure = false,
  )
}

@Composable
private fun RestoredContent(state: VersionsUi.Restored, actions: VersionsSheetActions, modifier: Modifier) {
  GallerySheetScaffold(
    icon = Icons.Rounded.CheckCircle,
    tone = GallerySheetTone.Brand,
    title = stringResource(R.string.backup_versions_done_title),
    message = stringResource(R.string.backup_versions_done_detail),
    modifier = modifier,
    body = {
      if (state.phoneKeptEdited) {
        FluidListGroup {
          FluidListRow(
            title = stringResource(R.string.backup_versions_done_kept),
            subtitle = "",
            tone = FluidTone.Info,
            leading = { Icon(imageVector = Icons.Rounded.Warning, contentDescription = null) },
            disclosure = false,
          )
        }
      }
    },
    actions = { BackupButton(label = stringResource(R.string.backup_versions_close), onClick = actions.close, style = FluidButtonStyle.Tinted) },
  )
}

// --- Gli stati senza scelte ---------------------------------------------------------------------------------------------------

/** Un lavoro in corso (o un permesso da dare): il cerchio che gira, la frase, e "Chiudi" solo dove si puo' (non a meta' di uno scambio). */
@Composable
private fun BusySheet(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String, onClose: (() -> Unit)?, modifier: Modifier) {
  GallerySheetScaffold(
    icon = icon,
    tone = GallerySheetTone.Brand,
    title = stringResource(R.string.backup_versions_title),
    message = null,
    modifier = modifier,
    body = {
      FluidListGroup {
        FluidListRow(
          title = title,
          subtitle = detail,
          tone = FluidTone.Info,
          leading = { FluidSpinner(size = BackupDimens.RowGlyph) },
          disclosure = false,
        )
      }
    },
    actions = {
      if (onClose != null) BackupButton(label = stringResource(R.string.backup_versions_close), onClick = onClose, style = FluidButtonStyle.Tinted)
    },
  )
}

@Composable
private fun NoticeSheet(title: String, detail: String, onClose: () -> Unit, modifier: Modifier) {
  GallerySheetScaffold(
    icon = Icons.Rounded.History,
    tone = GallerySheetTone.Brand,
    title = stringResource(R.string.backup_versions_title),
    message = null,
    modifier = modifier,
    body = {
      FluidListGroup {
        FluidListRow(
          title = title,
          subtitle = detail,
          tone = FluidTone.Neutral,
          leading = { Icon(imageVector = Icons.Rounded.Layers, contentDescription = null) },
          disclosure = false,
        )
      }
    },
    actions = { BackupButton(label = stringResource(R.string.backup_versions_close), onClick = onClose, style = FluidButtonStyle.Tinted) },
  )
}
