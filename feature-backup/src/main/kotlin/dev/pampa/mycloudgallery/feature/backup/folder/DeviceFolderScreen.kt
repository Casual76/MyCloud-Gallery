package dev.pampa.mycloudgallery.feature.backup.folder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.pampa.mycloudgallery.core.backup.contract.DeviceFolder
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.feature.backup.BackupDimens
import dev.pampa.mycloudgallery.feature.backup.R
import dev.pampa.mycloudgallery.feature.backup.ui.BackupButton
import dev.pampa.mycloudgallery.feature.backup.ui.BackupCard
import dev.pampa.mycloudgallery.feature.backup.ui.BackupLabel
import dev.pampa.mycloudgallery.feature.backup.ui.BackupNote
import dev.pampa.mycloudgallery.feature.backup.ui.BackupPage
import dev.pampa.mycloudgallery.feature.backup.ui.BackupText
import dev.pampa.mycloudgallery.feature.backup.ui.BackupTone

// La scelta della cartella del dispositivo (docs/fase5-progetto.md D7, E-B6, E-B16; G2, D30): una pagina di una scheda e due tasti. Il nome
// della cartella e' un dato dell'utente: si mostra, mai nei log.

/** Le uscite della pagina. */
internal class FolderActions(
  val choose: (DeviceFolder) -> Unit = {},
  val reload: () -> Unit = {},
)

/** La scelta della cartella del dispositivo con il suo ViewModel. */
@Composable
fun DeviceFolderRoute(
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  hostPadding: PaddingValues = PaddingValues(),
  backdropOut: ((GlassBackdropState) -> Unit)? = null,
  viewModel: DeviceFolderViewModel = hiltViewModel(),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  DeviceFolderScreen(
    state = state,
    actions = FolderActions(choose = viewModel::choose, reload = viewModel::load),
    modifier = modifier,
    onBack = onBack,
    hostPadding = hostPadding,
    backdropOut = backdropOut,
  )
}

/** La pagina per uno [state]: nessun effetto. La provano i test e gli screenshot. */
@Composable
internal fun DeviceFolderScreen(
  state: FolderUi,
  actions: FolderActions,
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  hostPadding: PaddingValues = PaddingValues(),
  backdropOut: ((GlassBackdropState) -> Unit)? = null,
) {
  BackupPage(
    label = BackupLabel(title = stringResource(R.string.backup_folder_title)),
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
        item(key = "folder") { FolderCard(state, actions) }
        item(key = "note") { BackupNote(stringResource(R.string.backup_folder_note)) }
      }
    }
  }
}

@Composable
private fun FolderCard(state: FolderUi, actions: FolderActions) {
  when (state) {
    FolderUi.Loading -> BackupCard(
      tone = BackupTone.Working,
      glyph = Icons.Rounded.Folder,
      title = stringResource(R.string.backup_folder_loading),
      busy = true,
    )
    FolderUi.Unavailable -> BackupCard(
      tone = BackupTone.Attention,
      glyph = Icons.Rounded.FolderOff,
      title = stringResource(R.string.backup_folder_unavailable_title),
      detail = stringResource(R.string.backup_folder_unavailable_detail),
      actions = { BackupButton(label = stringResource(R.string.backup_action_retry), onClick = actions.reload, style = FluidButtonStyle.Tinted) },
    )
    is FolderUi.Chosen -> BackupCard(
      tone = BackupTone.Calm,
      glyph = Icons.Rounded.Folder,
      title = stringResource(R.string.backup_folder_chosen_title, state.folder.name),
      detail = stringResource(R.string.backup_folder_chosen_detail),
    )
    is FolderUi.Choose -> {
      val existing = state.existing
      if (existing == null) {
        BackupCard(
          tone = BackupTone.Attention,
          glyph = Icons.Rounded.Folder,
          title = stringResource(R.string.backup_folder_new_title, state.suggested.name),
          detail = stringResource(R.string.backup_folder_new_detail),
          actions = { BackupButton(label = stringResource(R.string.backup_folder_use), onClick = { actions.choose(state.suggested) }) },
        )
      } else {
        // E-B6, E-B16: "Backup/S25 di Mario" c'e' gia' con 1.234 file: "Continua li'" o "Nuova cartella".
        BackupCard(
          tone = BackupTone.Attention,
          glyph = Icons.Rounded.Folder,
          title = stringResource(R.string.backup_folder_exists_title, state.suggested.name),
          detail = pluralStringResource(R.plurals.backup_folder_exists_detail, existing.files, BackupText.count(existing.files)),
          actions = {
            BackupButton(label = stringResource(R.string.backup_folder_continue), onClick = { actions.choose(state.suggested) })
            state.alternative?.let { other ->
              BackupButton(
                label = stringResource(R.string.backup_folder_new, other.name),
                onClick = { actions.choose(other) },
                style = FluidButtonStyle.Tinted,
              )
            }
          },
        )
      }
    }
  }
}
