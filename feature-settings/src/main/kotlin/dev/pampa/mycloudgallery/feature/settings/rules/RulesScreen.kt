package dev.pampa.mycloudgallery.feature.settings.rules

import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.Power
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import dev.antigravity.fluidengine.ui.fluid.FluidChip
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.feature.settings.ui.ChoiceCard
import dev.pampa.mycloudgallery.feature.settings.ui.Condition
import dev.pampa.mycloudgallery.feature.settings.ui.SettingsDimens
import dev.pampa.mycloudgallery.feature.settings.ui.SettingsInfo
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.Balance
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Nightlight
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.mycloudgallery.core.rules.ByteFormat
import dev.pampa.mycloudgallery.core.rules.Preset
import dev.pampa.mycloudgallery.core.rules.PresetCatalog
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.ui.RowTile

internal class RulesActions(
  val onBack: () -> Unit,
  val onPreset: (Preset) -> Unit,
  val onCap: (CapChoice) -> Unit,
  val onRoaming: (Boolean) -> Unit,
  val onPauseOnPowerSaver: (Boolean) -> Unit,
) {
  companion object {
    val None = RulesActions({}, {}, {}, {}, {})
  }
}

@Composable
internal fun RulesRoute(onBack: () -> Unit, viewModel: RulesViewModel = hiltViewModel()) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  RulesScreen(
    state = state,
    actions = RulesActions(
      onBack = onBack,
      onPreset = viewModel::choosePreset,
      onCap = viewModel::chooseMobileCap,
      onRoaming = viewModel::setRoaming,
      onPauseOnPowerSaver = viewModel::setPauseOnPowerSaver,
    ),
  )
}

/**
 * Impostazioni → "Quando lavora l'app" (verifica funzionale, RE-8; I3 della fase I): il preset, il tetto dei dati mobili, il roaming e il risparmio
 * energetico. Prima ogni preset era una riga con un paragrafo di sei righe e ogni tetto una riga con la stessa frase (228 parole). Ora i preset sono
 * **quattro schede** con la piastrella e le condizioni in glifi (la notte, la corrente, il Wi-Fi, i dati mobili), il tetto e' una fila di **capsule**,
 * e le frasi stanno dietro la «i». Le stesse scelte dell'onboarding; il motore delle regole le rilegge da solo.
 */
@Composable
internal fun RulesScreen(state: RulesUiState?, actions: RulesActions) {
  FluidScreen(title = stringResource(R.string.rules_title), onBack = actions.onBack) {
    if (state == null) return@FluidScreen

    item { GallerySectionHeader(title = stringResource(R.string.rules_section_preset)) }
    item { PresetGrid(selected = state.preset, onPreset = actions.onPreset) }
    item {
      val info = PresetCatalog.info(state.preset)
      SettingsInfo(label = stringResource(R.string.rules_preset_info, info.title), text = info.summary + "\n" + stringResource(R.string.rules_footnote))
    }

    item { GallerySectionHeader(title = stringResource(R.string.rules_section_mobile)) }
    item { CapChips(state = state, onCap = actions.onCap) }
    item { SettingsInfo(label = stringResource(R.string.rules_cap_info), text = stringResource(R.string.rules_cap_footnote)) }

    item { GallerySectionHeader(title = stringResource(R.string.rules_section_roaming)) }
    item {
      FluidListGroup {
        SwitchRow(
          title = stringResource(R.string.rules_roaming),
          icon = Icons.Rounded.Language,
          on = state.allowRoaming,
          onToggle = { actions.onRoaming(!state.allowRoaming) },
        )
      }
    }
    item { SettingsInfo(label = stringResource(R.string.rules_roaming_info), text = stringResource(R.string.rules_roaming_detail)) }

    // FAB-4: l'interruttore previsto da docs/regole-attivita.md (§6, punto 7), legato a `pauseOnPowerSaver`: prima non compariva in nessuna schermata.
    item { GallerySectionHeader(title = stringResource(R.string.rules_section_power_saver)) }
    item {
      FluidListGroup {
        SwitchRow(
          title = stringResource(R.string.rules_power_saver),
          icon = Icons.Rounded.BatterySaver,
          on = state.pauseOnPowerSaver,
          onToggle = { actions.onPauseOnPowerSaver(!state.pauseOnPowerSaver) },
        )
      }
    }
    item {
      SettingsInfo(
        label = stringResource(R.string.rules_power_saver_info),
        text = stringResource(R.string.rules_power_saver_detail),
      )
    }
  }
}

/** Una riga con l'interruttore: la piastrella, il titolo, l'interruttore; per TalkBack un interruttore con il suo stato. */
@Composable
private fun SwitchRow(title: String, icon: ImageVector, on: Boolean, onToggle: () -> Unit) {
  FluidListRow(
    title = title,
    subtitle = "",
    leading = { RowTile(icon) },
    leadingTile = false,
    badge = { FluidSwitch(checked = on, onCheckedChange = null) },
    disclosure = false,
    onClick = onToggle,
    modifier = Modifier.semantics(mergeDescendants = true) {
      role = Role.Switch
      toggleableState = ToggleableState(on)
    },
  )
}

/** I quattro preset in due colonne: la scheda con la piastrella, il nome e le condizioni in glifi. */
@Composable
private fun PresetGrid(selected: Preset, onPreset: (Preset) -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(SettingsDimens.ChoiceGap)) {
    PresetChoices.chunked(2).forEach { pair ->
      Row(horizontalArrangement = Arrangement.spacedBy(SettingsDimens.ChoiceGap), modifier = Modifier.height(IntrinsicSize.Max)) {
        pair.forEach { preset ->
          ChoiceCard(
            title = PresetCatalog.info(preset).title,
            icon = preset.icon(),
            family = preset.family(),
            conditions = preset.conditions(),
            selected = preset == selected,
            onClick = { if (preset != selected) onPreset(preset) },
            modifier = Modifier.weight(1f).fillMaxHeight(),
          )
        }
      }
    }
  }
}

/** Le condizioni di un preset, in glifi (e a voce per TalkBack): quando parte, con quale alimentazione, su quale rete. */
@Composable
private fun Preset.conditions(): List<Condition> {
  val night = Condition(Icons.Rounded.Nightlight, stringResource(R.string.rules_condition_night))
  val plugged = Condition(Icons.Rounded.Power, stringResource(R.string.rules_condition_plugged))
  val battery = Condition(Icons.Rounded.BatteryStd, stringResource(R.string.rules_condition_battery))
  val wifi = Condition(Icons.Rounded.Wifi, stringResource(R.string.rules_condition_wifi))
  val mobile = Condition(Icons.Rounded.SignalCellularAlt, stringResource(R.string.rules_condition_mobile))
  val heavyPlugged = Condition(Icons.Rounded.Power, stringResource(R.string.rules_condition_heavy_plugged))
  val heavyHome = Condition(Icons.Rounded.Home, stringResource(R.string.rules_condition_heavy_home))
  return when (this) {
    Preset.NIGHT -> listOf(night, plugged, wifi)
    Preset.BALANCED -> listOf(wifi, battery, heavyPlugged)
    Preset.WIFI_ALWAYS -> listOf(wifi, battery, heavyHome)
    Preset.MAXIMUM -> listOf(wifi, mobile, battery)
    Preset.CUSTOM -> emptyList()
  }
}

/** La famiglia della piastrella di un preset: la notte nell'indaco, il Wi-Fi nella rete, il resto nel backup. */
private fun Preset.family(): GalleryIconFamily = when (this) {
  Preset.NIGHT -> GalleryIconFamily.Search
  Preset.WIFI_ALWAYS -> GalleryIconFamily.Network
  Preset.BALANCED, Preset.MAXIMUM, Preset.CUSTOM -> GalleryIconFamily.Backup
}

/**
 * Il tetto dei dati mobili come capsule: «Come il preset» (con il suo tetto), i tagli, «Nessun tetto». Una sola e' accesa. Prima erano sette righe
 * con la stessa frase.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CapChips(state: RulesUiState, onCap: (CapChoice) -> Unit) {
  FlowRow(
    modifier = Modifier.fillMaxWidth().padding(horizontal = SettingsDimens.TightGap),
    horizontalArrangement = Arrangement.spacedBy(SettingsDimens.PillGap),
    verticalArrangement = Arrangement.spacedBy(SettingsDimens.PillGap),
  ) {
    // Giro 1 della fase I (CU-20): la capsula dice il nome del preset scelto, non la parola «preset».
    val name = PresetCatalog.info(state.preset).title
    val preset = state.presetCapBytes?.let { stringResource(R.string.rules_cap_preset_chip, name, ByteFormat.size(it)) } ?: stringResource(R.string.rules_cap_preset, name)
    FluidChip(label = preset, selected = state.cap == CapChoice.FromPreset, onClick = { onCap(CapChoice.FromPreset) })
    for (bytes in MobileCapOptions) {
      FluidChip(label = ByteFormat.size(bytes), selected = state.cap == CapChoice.Limit(bytes), onClick = { onCap(CapChoice.Limit(bytes)) })
    }
    FluidChip(label = stringResource(R.string.rules_cap_none), selected = state.cap == CapChoice.Unlimited, onClick = { onCap(CapChoice.Unlimited) })
  }
}

private fun Preset.icon(): ImageVector = when (this) {
  Preset.NIGHT -> Icons.Rounded.Nightlight
  Preset.BALANCED -> Icons.Rounded.Balance
  Preset.WIFI_ALWAYS -> Icons.Rounded.Wifi
  Preset.MAXIMUM -> Icons.Rounded.AllInclusive
  Preset.CUSTOM -> Icons.Rounded.Balance
}
