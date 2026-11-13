package dev.pampa.mycloudgallery.feature.backup.settings

import dev.pampa.mycloudgallery.core.designsystem.design.GalleryBatteryGuard
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryBatteryGuardNote
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryInfoRow
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryWarningNote
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.FluidSpinner
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconStatus
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconTile
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIcons
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.feature.backup.BackupDimens
import dev.pampa.mycloudgallery.core.designsystem.R as DesignR
import dev.pampa.mycloudgallery.feature.backup.R
import dev.pampa.mycloudgallery.feature.backup.page.SourceCover
import dev.pampa.mycloudgallery.feature.backup.ui.BackupCoverWindow
import dev.pampa.mycloudgallery.feature.backup.ui.BackupLabel
import dev.pampa.mycloudgallery.feature.backup.ui.BackupNote
import dev.pampa.mycloudgallery.feature.backup.ui.BackupPage
import dev.pampa.mycloudgallery.feature.backup.ui.BackupSectionTitle
import dev.pampa.mycloudgallery.feature.backup.ui.BackupText
import dev.pampa.mycloudgallery.feature.backup.ui.LocalBackupCovers
import dev.pampa.mycloudgallery.feature.backup.ui.sourceName

// Impostazioni -> Backup (docs/fase5-progetto.md §2.1, D7, D34; G2, D30; I3 della fase I). Prima: righe con la stessa icona grigia di cartella e
// note lunghe sotto ogni gruppo (la schermata 3 dell'utente). Ora: in «Cosa salvare» ogni cartella mostra **la sua foto piu' recente** (stessa cache e
// stessi cancelli della griglia), i numeri soli e l'interruttore; «Dove va sul NAS» e' una **figura** (il percorso delle copie, a pezzi con le icone);
// «Quando» ha l'interruttore e i tre segni che decidono il backup. Le spiegazioni stanno dietro la «i». Un interruttore e' una riga da 48 dp con il
// suo stato per TalkBack.

/** Le uscite delle impostazioni: tutte partono da un tocco. */
internal class SettingsActions(
  val setSource: (BackupSource, Boolean) -> Unit = { _, _ -> },
  val setProtectNewPhotos: (Boolean) -> Unit = {},
  val openDeviceFolder: () -> Unit = {},
  val reloadFolders: () -> Unit = {},
  val setBatteryLevel: (Int) -> Unit = {},
  val setBatteryForBackup: (Boolean) -> Unit = {},
  val setBatteryForAnalysis: (Boolean) -> Unit = {},
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
      setBatteryLevel = viewModel::setBatteryLevel,
      setBatteryForBackup = viewModel::setBatteryForBackup,
      setBatteryForAnalysis = viewModel::setBatteryForAnalysis,
    )
  }
  // I3: le copertine delle cartelle; senza la porta, le piastrelle.
  CompositionLocalProvider(LocalBackupCovers provides viewModel.covers) {
    BackupSettingsScreen(state = state, actions = actions, modifier = modifier, onBack = onBack, hostPadding = hostPadding, backdropOut = backdropOut)
  }
}

/** La pagina per uno [state]: nessun effetto (se non la preparazione delle copertine). La provano i test e gli screenshot. */
@Composable
internal fun BackupSettingsScreen(
  state: SettingsUi,
  actions: SettingsActions,
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  hostPadding: PaddingValues = PaddingValues(),
  backdropOut: ((GlassBackdropState) -> Unit)? = null,
) {
  val sources = remember(state.choices) { state.choices.map { it.source } }
  BackupCoverWindow(LocalBackupCovers.current, sources)
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
          leadingTile = false,
          disclosure = false,
        )
      }
    }
  }
  // Una nota resta in pagina solo quando dice uno stato (la scelta non salvata, le cartelle illeggibili, il backup non pronto); la rassicurazione di
  // sempre («spegnere non cancella») sta dietro la «i».
  item(key = "sources-note") {
    val problem = when {
      state.saveFailed -> R.string.backup_settings_save_failed
      state.folders is FoldersState.Unavailable -> R.string.backup_settings_folders_unavailable
      !state.editable -> R.string.backup_settings_not_ready
      else -> null
    }
    if (problem != null) {
      GalleryWarningNote(text = stringResource(problem), modifier = Modifier.padding(horizontal = BackupDimens.BlockGap))
    } else {
      GalleryInfoRow(label = stringResource(R.string.backup_settings_sources_info), detail = stringResource(R.string.backup_settings_sources_note), modifier = Modifier.padding(horizontal = BackupDimens.BlockGap))
    }
  }
}

/**
 * Una sorgente con il suo interruttore: la copertina (la foto piu' recente della cartella, o la piastrella con il glifo), il nome, i numeri soli
 * (quanti media e quanto pesano) e l'interruttore. La riga intera si tocca, e per TalkBack e' un interruttore con il suo stato.
 */
@Composable
private fun SourceSwitchRow(choice: SourceChoice, enabled: Boolean, onChange: (Boolean) -> Unit) {
  val name = sourceName(choice.source)
  val numbers = if (choice.media != null && choice.bytes != null) {
    stringResource(R.string.backup_settings_source_numbers, BackupText.count(choice.media), BackupText.size(choice.bytes))
  } else {
    ""
  }
  val state = stringResource(if (choice.chosen) R.string.backup_state_on else R.string.backup_state_off)
  FluidListRow(
    title = name,
    subtitle = numbers,
    leading = { SourceCover(source = choice.source, cell = BackupDimens.RowCover, mark = null, dim = !choice.chosen) },
    leadingTile = false,
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
    val canChoose = folder == null && state.editable
    FluidListGroup {
      FluidListRow(
        title = stringResource(R.string.backup_device_folder_title),
        subtitle = "",
        leading = { GalleryIconTile(spec = GalleryIcons.Nas, status = if (folder == null) GalleryIconStatus.Attention else null) },
        leadingTile = false,
        badge = if (folder != null) {
          {
            Text(
              text = folder.name,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.widthIn(max = BackupDimens.ValueMaxWidth),
            )
          }
        } else {
          null
        },
        disclosure = canChoose,
        onClick = if (canChoose) actions.openDeviceFolder else null,
      )
      if (folder != null) {
        FluidListDivider()
        FolderPath(device = folder.name)
      }
    }
  }
  item(key = "folder-note") {
    GalleryInfoRow(label = stringResource(R.string.backup_settings_folder_info), detail = stringResource(R.string.backup_settings_folder_note), modifier = Modifier.padding(horizontal = BackupDimens.BlockGap))
  }
}

/**
 * La figura del percorso delle copie: l'area privata, la cartella del dispositivo, l'anno, il mese, ognuno con il suo segno e separati da una
 * freccia. Prima era una frase di due righe. Per TalkBack e' una frase sola.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FolderPath(device: String) {
  val description = stringResource(R.string.backup_settings_folder_path_description, device)
  val steps = listOf(
    PathStep(Icons.Rounded.Lock, stringResource(R.string.backup_settings_folder_path_private)),
    PathStep(Icons.Rounded.PhoneAndroid, device),
    PathStep(Icons.Rounded.CalendarMonth, stringResource(R.string.backup_settings_folder_path_year)),
    PathStep(Icons.Rounded.CalendarMonth, stringResource(R.string.backup_settings_folder_path_month)),
  )
  androidx.compose.foundation.layout.FlowRow(
    modifier = Modifier
      .fillMaxWidth()
      .padding(BackupDimens.CardPadding)
      .clearAndSetSemantics { contentDescription = description },
    horizontalArrangement = Arrangement.spacedBy(BackupDimens.LineGap),
    verticalArrangement = Arrangement.spacedBy(BackupDimens.LineGap),
  ) {
    steps.forEachIndexed { index, step ->
      if (index > 0) {
        Icon(
          imageVector = Icons.Rounded.ChevronRight,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(BackupDimens.CountGlyph).align(Alignment.CenterVertically),
        )
      }
      PathChip(step, Modifier.align(Alignment.CenterVertically))
    }
  }
}

private class PathStep(val icon: ImageVector, val label: String)

@Composable
private fun PathChip(step: PathStep, modifier: Modifier) {
  Row(
    modifier = modifier
      .clip(ContinuousCornerShape(FluidRadius.Control))
      .background(MaterialTheme.colorScheme.surfaceContainerHighest)
      .padding(horizontal = BackupDimens.CardGap, vertical = BackupDimens.LineGap),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(BackupDimens.LineGap),
  ) {
    Icon(imageVector = step.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(BackupDimens.CountGlyph))
    Text(text = step.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
}

// --- Quando ------------------------------------------------------------------------------------------------------------------------

private fun LazyListScope.rulesSection(state: SettingsUi, actions: SettingsActions) {
  item(key = "rules-title") { BackupSectionTitle(stringResource(R.string.backup_settings_section_rules)) }
  item(key = "rules") {
    FluidListGroup {
      if (state.rulesAvailable) {
        val on = state.protectNewPhotos
        val stateText = stringResource(if (on) R.string.backup_state_on else R.string.backup_state_off)
        FluidListRow(
          title = stringResource(R.string.backup_settings_protect_title),
          subtitle = "",
          leading = { GalleryIconTile(icon = Icons.Rounded.Bolt, family = GalleryIconFamily.Backup) },
          leadingTile = false,
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
      // Con il controllo della batteria qui sotto la sua figura non si ripete fra i segni: restano la notte e il Wi-Fi di casa, e «in carica» **finche' il backup normale lavora solo in carica** (BU-3).
      WhenRow(guardBound = state.battery != null || state.batteryLoading, needsPower = state.battery?.backupNeedsPower == true)
    }
  }
  // «Che cosa protegge» spiega «Proteggi subito le foto nuove», sopra: sta subito sotto la sua scheda (BU-6).
  item(key = "rules-note") {
    GalleryInfoRow(label = stringResource(R.string.backup_settings_rules_info), detail = stringResource(R.string.backup_settings_rules_note), modifier = Modifier.padding(horizontal = BackupDimens.BlockGap))
  }
  // Pacchetto B (seconda beta): il livello di batteria sotto cui il backup e l'analisi non partono, un controllo solo (lo stesso, identico, nelle impostazioni dell'analisi), con il suo titolo.
  if (state.battery != null || state.batteryLoading) {
    item(key = "battery-title") { BackupSectionTitle(stringResource(DesignR.string.battery_guard_title)) }
    item(key = "battery") {
      GalleryBatteryGuard(
        value = state.battery,
        onLevelChange = actions.setBatteryLevel,
        onForBackupChange = actions.setBatteryForBackup,
        onForAnalysisChange = actions.setBatteryForAnalysis,
        saveResult = state.batterySaveResult,
      )
    }
    item(key = "battery-note") { GalleryBatteryGuardNote(modifier = Modifier.padding(horizontal = BackupDimens.BlockGap)) }
  }
}

/**
 * «Quando fa il backup»: i tre segni che lo decidono (la notte, il Wi-Fi di casa, la batteria), ognuno con la sua piastrella e un'etichetta sotto.
 * Prima era la frase «Lo decidono le regole delle attivita'...». Le regole si cambiano in Impostazioni, «Quando lavora l'app».
 */
@Composable
private fun WhenRow(guardBound: Boolean = false, needsPower: Boolean = false) {
  val title = stringResource(R.string.backup_settings_when_title)
  val factors = listOfNotNull(
    Triple(Icons.Rounded.Bedtime, GalleryIconFamily.Search, stringResource(R.string.backup_settings_when_night)),
    Triple(Icons.Rounded.Wifi, GalleryIconFamily.Network, stringResource(R.string.backup_settings_when_wifi)),
    // Senza il controllo il segno della batteria di sempre; con il controllo «in carica», se il backup normale lavora solo in carica (con «Notte», di serie).
    when {
      !guardBound -> Triple(Icons.Rounded.BatteryChargingFull, GalleryIconFamily.Places, stringResource(R.string.backup_settings_when_battery))
      needsPower -> Triple(Icons.Rounded.BatteryChargingFull, GalleryIconFamily.Places, stringResource(R.string.backup_settings_when_charging))
      else -> null
    },
  )
  val description = title + ": " + factors.joinToString(", ") { it.third }
  Column(
    modifier = Modifier.fillMaxWidth().padding(BackupDimens.CardPadding).clearAndSetSemantics { contentDescription = description },
    verticalArrangement = Arrangement.spacedBy(BackupDimens.CardGap),
  ) {
    Text(text = title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
      factors.forEach { (icon, family, label) ->
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(BackupDimens.LineGap)) {
          GalleryIconTile(icon = icon, family = family, size = BackupDimens.RowCover)
          Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
      }
    }
  }
}
