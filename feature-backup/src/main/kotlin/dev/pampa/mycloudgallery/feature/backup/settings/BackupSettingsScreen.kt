package dev.pampa.mycloudgallery.feature.backup.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidSpinner
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.feature.backup.BackupDimens
import dev.pampa.mycloudgallery.feature.backup.R
import dev.pampa.mycloudgallery.feature.backup.ui.BackupLabel
import dev.pampa.mycloudgallery.feature.backup.ui.BackupNote
import dev.pampa.mycloudgallery.feature.backup.ui.BackupPage
import dev.pampa.mycloudgallery.feature.backup.ui.BackupSectionTitle
import dev.pampa.mycloudgallery.feature.backup.ui.BackupText
import dev.pampa.mycloudgallery.feature.backup.ui.sourceName

// Impostazioni -> Backup (docs/fase5-progetto.md §2.1, D7, D34; G2, D30): cosa si salva (la fotocamera e gli screenshot che il passo 6
// dell'onboarding propone gia', e le altre cartelle del telefono con numero e GB), dove va sul NAS (la cartella del dispositivo) e le
// regole che si toccano da qui. Solo componenti G2 e righe di Fluid; un interruttore e' una riga da 48 dp con il suo stato per TalkBack.

/** Le uscite delle impostazioni: tutte partono da un tocco. */
internal class SettingsActions(
  val setSource: (BackupSource, Boolean) -> Unit = { _, _ -> },
  val setProtectNewPhotos: (Boolean) -> Unit = {},
  val openDeviceFolder: () -> Unit = {},
  val reloadFolders: () -> Unit = {},
)

/** Le impostazioni del backup con il loro ViewModel. */
@Composable
fun BackupSettingsRoute(
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  hostPadding: PaddingValues = PaddingValues(),
  backdropOut: ((GlassBackdropState) -> Unit)? = null,
  viewModel: BackupSettingsViewModel = hiltViewModel(),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val actions = remember(viewModel) {
    SettingsActions(
      setSource = viewModel::setSource,
      setProtectNewPhotos = viewModel::setProtectNewPhotos,
      openDeviceFolder = viewModel::openDeviceFolder,
      reloadFolders = viewModel::reloadFolders,
    )
  }
  BackupSettingsScreen(state = state, actions = actions, modifier = modifier, onBack = onBack, hostPadding = hostPadding, backdropOut = backdropOut)
}

/** La pagina per uno [state]: nessun effetto. La provano i test e gli screenshot. */
@Composable
internal fun BackupSettingsScreen(
  state: SettingsUi,
  actions: SettingsActions,
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  hostPadding: PaddingValues = PaddingValues(),
  backdropOut: ((GlassBackdropState) -> Unit)? = null,
) {
  BackupPage(
    label = BackupLabel(title = stringResource(R.string.backup_settings_title)),
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
        sourcesSection(state, actions)
        folderSection(state, actions)
        rulesSection(state, actions)
      }
    }
  }
}

// --- Cosa si salva ---------------------------------------------------------------------------------------------------------------

private fun LazyListScope.sourcesSection(state: SettingsUi, actions: SettingsActions) {
  item(key = "sources-title") { BackupSectionTitle(stringResource(R.string.backup_settings_section_sources)) }
  item(key = "sources") {
    FluidListGroup {
      state.choices.forEachIndexed { index, choice ->
        if (index > 0) FluidListDivider()
        SourceSwitchRow(choice = choice, enabled = state.editable, onChange = { on -> actions.setSource(choice.source, on) })
      }
      if (state.folders is FoldersState.Loading) {
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.backup_settings_folders_loading),
          subtitle = "",
          leading = { FluidSpinner(size = BackupDimens.RowGlyph) },
          disclosure = false,
        )
      }
    }
  }
  item(key = "sources-note") {
    BackupNote(
      stringResource(
        when {
          state.folders is FoldersState.Unavailable -> R.string.backup_settings_folders_unavailable
          !state.editable -> R.string.backup_settings_not_ready
          else -> R.string.backup_settings_sources_note
        },
      ),
    )
  }
}

/** Una sorgente con il suo interruttore: la riga intera si tocca, e per TalkBack e' un interruttore con il suo stato. */
@Composable
private fun SourceSwitchRow(choice: SourceChoice, enabled: Boolean, onChange: (Boolean) -> Unit) {
  val name = sourceName(choice.source)
  val numbers = if (choice.media != null && choice.bytes != null) {
    stringResource(R.string.backup_settings_source_numbers, BackupText.count(choice.media), BackupText.size(choice.bytes))
  } else {
    stringResource(R.string.backup_settings_source_numbers_unknown)
  }
  val state = stringResource(if (choice.chosen) R.string.backup_state_on else R.string.backup_state_off)
  FluidListRow(
    title = name,
    subtitle = numbers,
    tone = if (choice.chosen) FluidTone.Primary else FluidTone.Neutral,
    leading = {
      Icon(
        imageVector = when (choice.source) {
          BackupSource.Camera -> Icons.Rounded.PhotoCamera
          BackupSource.Screenshots -> Icons.Rounded.Image
          is BackupSource.Folder -> Icons.Rounded.Folder
        },
        contentDescription = null,
      )
    },
    badge = { FluidSwitch(checked = choice.chosen, onCheckedChange = null, enabled = enabled) },
    disclosure = false,
    titleMaxLines = 2,
    onClick = if (enabled) ({ onChange(!choice.chosen) }) else null,
    modifier = Modifier.semantics(mergeDescendants = true) {
      role = Role.Switch
      stateDescription = state
    },
  )
}

// --- Dove va sul NAS -------------------------------------------------------------------------------------------------------------

private fun LazyListScope.folderSection(state: SettingsUi, actions: SettingsActions) {
  item(key = "folder-title") { BackupSectionTitle(stringResource(R.string.backup_settings_section_folder)) }
  item(key = "folder") {
    val folder = state.deviceFolder
    FluidListGroup {
      FluidListRow(
        title = stringResource(R.string.backup_device_folder_title),
        subtitle = if (folder != null) {
          stringResource(R.string.backup_device_folder_chosen, folder.name)
        } else {
          stringResource(R.string.backup_device_folder_not_chosen)
        },
        tone = FluidTone.Info,
        leading = { Icon(imageVector = Icons.Rounded.Folder, contentDescription = null) },
        disclosure = folder == null && state.editable,
        onClick = if (folder == null && state.editable) actions.openDeviceFolder else null,
      )
    }
  }
  item(key = "folder-note") { BackupNote(stringResource(R.string.backup_settings_folder_note)) }
}

// --- Regole ----------------------------------------------------------------------------------------------------------------------

private fun LazyListScope.rulesSection(state: SettingsUi, actions: SettingsActions) {
  item(key = "rules-title") { BackupSectionTitle(stringResource(R.string.backup_settings_section_rules)) }
  item(key = "rules") {
    FluidListGroup {
      if (state.rulesAvailable) {
        val on = state.protectNewPhotos
        val stateText = stringResource(if (on) R.string.backup_state_on else R.string.backup_state_off)
        FluidListRow(
          title = stringResource(R.string.backup_settings_protect_title),
          subtitle = stringResource(R.string.backup_settings_protect_detail),
          tone = if (on) FluidTone.Primary else FluidTone.Neutral,
          leading = { Icon(imageVector = Icons.Rounded.Bolt, contentDescription = null) },
          badge = { FluidSwitch(checked = on, onCheckedChange = null) },
          disclosure = false,
          onClick = { actions.setProtectNewPhotos(!on) },
          modifier = Modifier.semantics(mergeDescendants = true) {
            role = Role.Switch
            stateDescription = stateText
          },
        )
        FluidListDivider()
      }
      FluidListRow(
        title = stringResource(R.string.backup_settings_when_title),
        subtitle = stringResource(R.string.backup_settings_when_detail),
        tone = FluidTone.Neutral,
        leading = { Icon(imageVector = Icons.Rounded.Schedule, contentDescription = null) },
        disclosure = false,
      )
    }
  }
  item(key = "rules-note") { BackupNote(stringResource(R.string.backup_settings_rules_note)) }
}
