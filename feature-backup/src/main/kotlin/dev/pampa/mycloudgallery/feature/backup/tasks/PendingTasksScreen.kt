package dev.pampa.mycloudgallery.feature.backup.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.pampa.mycloudgallery.core.backup.contract.PendingTask
import dev.pampa.mycloudgallery.core.backup.contract.PendingTaskKind
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.library.text.GalleryDates
import dev.pampa.mycloudgallery.feature.backup.BackupDimens
import dev.pampa.mycloudgallery.feature.backup.R
import dev.pampa.mycloudgallery.feature.backup.ui.BackupButton
import dev.pampa.mycloudgallery.feature.backup.ui.BackupCard
import dev.pampa.mycloudgallery.feature.backup.ui.BackupLabel
import dev.pampa.mycloudgallery.feature.backup.ui.BackupNote
import dev.pampa.mycloudgallery.feature.backup.ui.BackupPage
import dev.pampa.mycloudgallery.feature.backup.ui.BackupText
import dev.pampa.mycloudgallery.feature.backup.ui.BackupTone
import java.time.Instant
import java.time.ZoneId

// Fase 5B (P12, docs/fase5-progetto.md §1.5 `PendingTasksState`, §2.6, D20, D30): la lista "Da decidere" (pagina Backup → Da decidere). Una
// scheda per attivita': che cosa e' successo, quanto pesa, da quando, e i due tasti. Solo componenti G2 (la chrome e le schede della pagina
// Backup), solo numeri e date: mai il nome di un file. La data e' quella del NAS (D31: l'orologio del telefono non entra), mostrata nel fuso
// di casa.

/** Le uscite della lista: tutte partono da un tocco. */
@Immutable
internal class PendingTasksActions(
  val deleteOriginal: (PendingTask) -> Unit = {},
  val keep: (PendingTask) -> Unit = {},
)

/** La lista "Da decidere" con il suo ViewModel. [onBack] e' null nel pannello di dettaglio del tablet. */
@Composable
fun PendingTasksRoute(
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  hostPadding: PaddingValues = PaddingValues(),
  backdropOut: ((GlassBackdropState) -> Unit)? = null,
  viewModel: PendingTasksViewModel = hiltViewModel(),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  PendingTasksScreen(
    state = state,
    actions = PendingTasksActions(deleteOriginal = viewModel::deleteOriginal, keep = viewModel::keep),
    modifier = modifier,
    onBack = onBack,
    hostPadding = hostPadding,
    backdropOut = backdropOut,
  )
}

/** La lista per uno [state]: nessun effetto. La provano i test e gli screenshot. */
@Composable
internal fun PendingTasksScreen(
  state: PendingTasksUi,
  actions: PendingTasksActions,
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  hostPadding: PaddingValues = PaddingValues(),
  backdropOut: ((GlassBackdropState) -> Unit)? = null,
) {
  BackupPage(
    label = BackupLabel(
      title = stringResource(R.string.backup_tasks_title),
      subtitle = if (state.tasks.isEmpty()) null else pluralStringResource(R.plurals.backup_tasks_summary, state.tasks.size, BackupText.count(state.tasks.size)),
    ),
    modifier = modifier,
    onBack = onBack,
    backdropOut = backdropOut,
    hostPadding = hostPadding,
  ) { insets ->
    Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
      LazyColumn(
        modifier = Modifier.fillMaxHeight().widthIn(max = BackupDimens.PageMaxWidth).fillMaxWidth(),
        contentPadding = PaddingValues(start = GalleryDimens.Gutter, end = GalleryDimens.Gutter, top = insets.top, bottom = insets.bottom),
        verticalArrangement = Arrangement.spacedBy(BackupDimens.BlockGap),
      ) {
        if (state.tasks.isEmpty()) {
          item(key = "empty") {
            BackupCard(
              tone = BackupTone.Waiting,
              glyph = Icons.Rounded.Checklist,
              title = stringResource(R.string.backup_tasks_empty_title),
              detail = stringResource(R.string.backup_tasks_empty_detail),
            )
          }
        } else {
          state.tasks.forEach { task ->
            item(key = "task-${task.id.value}") { PendingTaskCard(task, state.canDeleteOriginal, actions) }
          }
          item(key = "note") { BackupNote(stringResource(R.string.backup_tasks_note)) }
        }
      }
    }
  }
}

/** Una scheda per attivita': un `when` esaustivo sul tipo, cosi' un tipo nuovo non compila finche' non ha il suo testo. */
@Composable
private fun PendingTaskCard(task: PendingTask, canDeleteOriginal: Boolean, actions: PendingTasksActions) {
  when (task.kind) {
    PendingTaskKind.EDITED_VIDEO_ORIGINAL -> BackupCard(
      tone = BackupTone.Attention,
      glyph = Icons.Rounded.Videocam,
      title = stringResource(R.string.backup_tasks_video_title),
      detail = stringResource(R.string.backup_tasks_video_detail, BackupText.size(task.bytes), sinceText(task.createdAtNasMillis)),
      actions = {
        // "Tienilo" e' la scelta che non toglie niente: il tasto pieno. "Elimina l'originale" porta al foglio con la sua conferma.
        BackupButton(label = stringResource(R.string.backup_tasks_keep), onClick = { actions.keep(task) })
        if (canDeleteOriginal) {
          BackupButton(label = stringResource(R.string.backup_tasks_delete), onClick = { actions.deleteOriginal(task) }, style = FluidButtonStyle.Tinted)
        }
      },
    )
  }
}

/** "12 ottobre 2026": la data del NAS nel fuso di casa. */
private fun sinceText(atNasMillis: Long): String =
  GalleryDates.date(Instant.ofEpochMilli(atNasMillis).atZone(ZoneId.systemDefault()).toLocalDateTime())
