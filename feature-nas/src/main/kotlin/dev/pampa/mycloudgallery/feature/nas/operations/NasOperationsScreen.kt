package dev.pampa.mycloudgallery.feature.nas.operations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryFooterNote
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryFooterPill
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryFooterTone
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperationState
import dev.pampa.mycloudgallery.core.nasops.contract.OpId
import dev.pampa.mycloudgallery.core.nasops.contract.OpKind
import dev.pampa.mycloudgallery.feature.nas.NasDimens
import dev.pampa.mycloudgallery.feature.nas.R
import dev.pampa.mycloudgallery.feature.nas.ui.NasEmptyState
import dev.pampa.mycloudgallery.feature.nas.ui.NasLabel
import dev.pampa.mycloudgallery.feature.nas.ui.NasPage
import dev.pampa.mycloudgallery.feature.nas.ui.NasProgressBar
import dev.pampa.mycloudgallery.feature.nas.ui.NasSectionTitle
import dev.pampa.mycloudgallery.feature.nas.ui.NasText
import dev.pampa.mycloudgallery.feature.nas.ui.opErrorText
import dev.pampa.mycloudgallery.feature.nas.ui.pauseReasonRes

/**
 * **Operazioni in corso**: cosa sta facendo il diario sul NAS, con l'avanzamento e "Ferma". Sotto, le ultime finite con l'esito.
 * Stesso linguaggio delle altre pagine (G2): la riga di chrome in alto, le schede che scorrono sotto.
 */
@Composable
fun NasOperationsRoute(
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  hostPadding: PaddingValues = PaddingValues(),
  backdropOut: ((GlassBackdropState) -> Unit)? = null,
  viewModel: NasOperationsViewModel = hiltViewModel(),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  NasOperationsScreen(
    state = state,
    onStop = viewModel::stop,
    modifier = modifier,
    onBack = onBack,
    hostPadding = hostPadding,
    backdropOut = backdropOut,
  )
}

/** La pagina per uno [state]: nessun effetto oltre a "Ferma". La provano i test e gli screenshot. */
@Composable
internal fun NasOperationsScreen(
  state: OperationsUi,
  onStop: (OpId) -> Unit,
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  hostPadding: PaddingValues = PaddingValues(),
  backdropOut: ((GlassBackdropState) -> Unit)? = null,
) {
  NasPage(
    label = NasLabel(
      title = stringResource(R.string.nas_ops_title),
      subtitle = if (state.active.isEmpty()) {
        stringResource(R.string.nas_ops_subtitle_none)
      } else {
        pluralStringResource(R.plurals.nas_ops_subtitle_active, state.active.size, GalleryNumbers.format(state.active.size))
      },
    ),
    modifier = modifier,
    onBack = onBack,
    backdropOut = backdropOut,
    hostPadding = hostPadding,
  ) { insets ->
    Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
      LazyColumn(
        modifier = Modifier.fillMaxHeight().widthIn(max = NasDimens.DashboardMaxWidth).fillMaxWidth(),
        contentPadding = PaddingValues(start = GalleryDimens.Gutter, end = GalleryDimens.Gutter, top = insets.top, bottom = insets.bottom),
        verticalArrangement = Arrangement.spacedBy(NasDimens.BlockGap),
      ) {
        if (state.isEmpty) {
          item(key = "empty") {
            NasEmptyState(
              icon = Icons.Rounded.TaskAlt,
              title = stringResource(R.string.nas_ops_empty_title),
              detail = stringResource(R.string.nas_ops_empty_detail),
            )
          }
        }
        if (state.active.isNotEmpty()) {
          item(key = "active-title") { NasSectionTitle(stringResource(R.string.nas_ops_section_active)) }
          state.active.forEach { op -> item(key = "op-${op.id.value}") { ActiveCard(op, onStop) } }
        }
        if (state.closed.isNotEmpty()) {
          item(key = "closed-title") { NasSectionTitle(stringResource(R.string.nas_ops_section_closed)) }
          item(key = "closed") {
            FluidListGroup {
              state.closed.forEachIndexed { index, op ->
                if (index > 0) FluidListDivider()
                ClosedRow(op)
              }
            }
          }
        }
      }
    }
  }
}

// --- Una operazione in corso -----------------------------------------------------------------------------------------------

@Composable
private fun ActiveCard(op: NasOperationState, onStop: (OpId) -> Unit) {
  val title = operationTitle(op)
  FluidListGroup {
    FluidListRow(
      title = title,
      subtitle = "",
      tone = kindTone(op.kind),
      leading = { Icon(imageVector = kindIcon(op.kind), contentDescription = null) },
      disclosure = false,
      titleMaxLines = 2,
      labels = { GalleryFooterPill(note = statusNote(op), onClick = null) },
    )
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(start = NasDimens.CardPadding, end = NasDimens.CardPadding, bottom = NasDimens.CardPadding),
      verticalArrangement = Arrangement.spacedBy(NasDimens.CardGap),
    ) {
      progressOf(op)?.let { progress ->
        NasProgressBar(progress = progress)
      }
      Text(
        text = detailOf(op),
        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      reasonOf(op)?.let { reason ->
        Text(text = reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
      }
      val stopDescription = stringResource(R.string.nas_ops_stop_for, title)
      FluidButton(
        text = stringResource(R.string.nas_ops_stop),
        onClick = { onStop(op.id) },
        style = FluidButtonStyle.Tinted,
        size = FluidButtonSize.Large,
        fillWidth = true,
        modifier = Modifier.semantics { contentDescription = stopDescription },
      )
      Text(
        text = stringResource(R.string.nas_ops_stop_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun ClosedRow(op: NasOperationState) {
  val title = operationTitle(op)
  val detail = detailOf(op)
  val reason = reasonOf(op)
  FluidListRow(
    title = title,
    subtitle = listOfNotNull(detail, reason).joinToString(" "),
    tone = when (op) {
      is NasOperationState.Done -> if (op.summary.failed > 0) FluidTone.Danger else FluidTone.Success
      is NasOperationState.Failed -> FluidTone.Danger
      else -> FluidTone.Neutral
    },
    leading = { Icon(imageVector = kindIcon(op.kind), contentDescription = null) },
    disclosure = false,
    titleMaxLines = 2,
    labels = { GalleryFooterPill(note = statusNote(op), onClick = null) },
  )
}

// --- Le parole di un\'operazione ---------------------------------------------------------------------------------------------

@Composable
internal fun operationTitle(op: NasOperationState): String {
  val total = when (op) {
    is NasOperationState.Planned -> op.summary.itemCount
    is NasOperationState.Running -> op.total
    is NasOperationState.Paused -> op.total
    is NasOperationState.WaitingLocked -> op.total
    is NasOperationState.Failed -> op.total
    is NasOperationState.Cancelled -> op.total
    is NasOperationState.Done -> op.summary.done
  }
  val number = GalleryNumbers.format(total)
  val finished = op is NasOperationState.Done
  return when (op.kind) {
    OpKind.CREATE_FOLDER -> stringResource(if (finished) R.string.nas_ops_done_create_folder else R.string.nas_ops_title_create_folder)
    OpKind.RENAME -> pluralStringResource(if (finished) R.plurals.nas_ops_done_rename else R.plurals.nas_ops_title_rename, total, number)
    OpKind.MOVE -> pluralStringResource(if (finished) R.plurals.nas_ops_done_move else R.plurals.nas_ops_title_move, total, number)
    OpKind.TRASH -> pluralStringResource(if (finished) R.plurals.nas_ops_done_trash else R.plurals.nas_ops_title_trash, total, number)
    OpKind.RESTORE -> pluralStringResource(if (finished) R.plurals.nas_ops_done_restore else R.plurals.nas_ops_title_restore, total, number)
    OpKind.PURGE_NOW -> pluralStringResource(if (finished) R.plurals.nas_ops_done_purge else R.plurals.nas_ops_title_purge, total, number)
  }
}

/** L'avanzamento da 0 a 1 se si sa: per i byte quando ci sono, altrimenti per gli elementi. */
internal fun progressOf(op: NasOperationState): Float? = when (op) {
  is NasOperationState.Running -> when {
    op.bytesTotal > 0 -> (op.bytesDone.toDouble() / op.bytesTotal).toFloat()
    op.total > 0 -> op.done.toFloat() / op.total
    else -> null
  }
  is NasOperationState.Paused -> if (op.total > 0) op.done.toFloat() / op.total else null
  is NasOperationState.WaitingLocked -> if (op.total > 0) op.done.toFloat() / op.total else null
  is NasOperationState.Planned -> 0f
  else -> null
}

@Composable
private fun detailOf(op: NasOperationState): String = when (op) {
  is NasOperationState.Planned -> stringResource(
    R.string.nas_ops_detail_planned,
    pluralStringResource(R.plurals.nas_items_count, op.summary.itemCount, GalleryNumbers.format(op.summary.itemCount)),
    NasText.size(op.summary.totalBytes),
  )
  is NasOperationState.Running -> if (op.bytesTotal > 0) {
    stringResource(
      R.string.nas_ops_detail_elements_bytes,
      NasText.count(op.done), NasText.count(op.total), NasText.size(op.bytesDone), NasText.size(op.bytesTotal),
    )
  } else {
    stringResource(R.string.nas_ops_detail_elements, NasText.count(op.done), NasText.count(op.total))
  }
  is NasOperationState.Paused -> stringResource(R.string.nas_ops_detail_elements, NasText.count(op.done), NasText.count(op.total))
  is NasOperationState.WaitingLocked -> stringResource(R.string.nas_ops_detail_elements, NasText.count(op.done), NasText.count(op.total))
  is NasOperationState.Done -> stringResource(
    R.string.nas_ops_detail_done,
    pluralStringResource(R.plurals.nas_items_count, op.summary.done, GalleryNumbers.format(op.summary.done)),
    NasText.size(op.summary.bytes),
  )
  is NasOperationState.Failed -> stringResource(R.string.nas_ops_detail_elements, NasText.count(op.done), NasText.count(op.total))
  is NasOperationState.Cancelled -> stringResource(R.string.nas_ops_detail_cancelled, NasText.count(op.done), NasText.count(op.total))
}

/** La frase in piu': perche' e' in pausa o ferma, cosa e' rimasto indietro. Null se non c'e' niente da dire. */
@Composable
private fun reasonOf(op: NasOperationState): String? = when (op) {
  is NasOperationState.Paused -> stringResource(pauseReasonRes(op.reason))
  is NasOperationState.WaitingLocked -> pluralStringResource(R.plurals.nas_ops_detail_locked, op.lockedCount, GalleryNumbers.format(op.lockedCount))
  is NasOperationState.Failed -> opErrorText(op.code)
  is NasOperationState.Done -> listOfNotNull(
    op.summary.failed.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.nas_ops_detail_failed, it, GalleryNumbers.format(it)) },
    op.summary.skipped.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.nas_ops_detail_skipped, it, GalleryNumbers.format(it)) },
  ).joinToString(" ").takeIf { it.isNotEmpty() }
  else -> null
}

/** La pillola di stato: il tono dice quanto chiede attenzione, la frase dice cosa succede (mai il colore da solo). */
@Composable
private fun statusNote(op: NasOperationState): GalleryFooterNote = when (op) {
  is NasOperationState.Planned -> GalleryFooterNote(GalleryFooterTone.Waiting, stringResource(R.string.nas_ops_status_planned))
  is NasOperationState.Running -> {
    val progress = progressOf(op)
    GalleryFooterNote(
      tone = GalleryFooterTone.Working,
      label = if (progress != null) stringResource(R.string.nas_ops_status_running, NasText.count((progress * PERCENT).toInt())) else stringResource(R.string.nas_ops_status_running_unknown),
      busy = true,
    )
  }
  is NasOperationState.Paused -> GalleryFooterNote(
    tone = if (op.reason.code == "nas_unreachable") GalleryFooterTone.Attention else GalleryFooterTone.Waiting,
    label = stringResource(R.string.nas_ops_status_paused),
  )
  is NasOperationState.WaitingLocked -> GalleryFooterNote(GalleryFooterTone.Attention, stringResource(R.string.nas_ops_status_locked))
  is NasOperationState.Done -> GalleryFooterNote(
    tone = if (op.summary.failed > 0) GalleryFooterTone.Attention else GalleryFooterTone.Calm,
    label = stringResource(if (op.summary.failed > 0) R.string.nas_ops_status_done_partial else R.string.nas_ops_status_done),
  )
  is NasOperationState.Failed -> GalleryFooterNote(GalleryFooterTone.Alarm, stringResource(R.string.nas_ops_status_failed))
  is NasOperationState.Cancelled -> GalleryFooterNote(GalleryFooterTone.Waiting, stringResource(R.string.nas_ops_status_cancelled))
}

private fun kindIcon(kind: OpKind) = when (kind) {
  OpKind.CREATE_FOLDER -> Icons.Rounded.CreateNewFolder
  OpKind.RENAME -> Icons.Rounded.DriveFileRenameOutline
  OpKind.MOVE -> Icons.AutoMirrored.Rounded.DriveFileMove
  OpKind.TRASH -> Icons.Rounded.Delete
  OpKind.RESTORE -> Icons.Rounded.RestoreFromTrash
  OpKind.PURGE_NOW -> Icons.Rounded.DeleteForever
}

private fun kindTone(kind: OpKind) = when (kind) {
  OpKind.TRASH, OpKind.PURGE_NOW -> FluidTone.Danger
  OpKind.RESTORE -> FluidTone.Success
  else -> FluidTone.Primary
}

private const val PERCENT = 100
