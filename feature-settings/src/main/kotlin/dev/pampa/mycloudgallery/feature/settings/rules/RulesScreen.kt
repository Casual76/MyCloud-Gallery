package dev.pampa.mycloudgallery.feature.settings.rules

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.Balance
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.DataUsage
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
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.rules.ByteFormat
import dev.pampa.mycloudgallery.core.rules.Preset
import dev.pampa.mycloudgallery.core.rules.PresetCatalog
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.ui.RowGlyph

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
 * Impostazioni → "Quando lavora l'app" (verifica funzionale, RE-8): il preset, il tetto dei dati mobili e il roaming. Le stesse scelte
 * dell'onboarding, cambiabili quando si vuole; il motore delle regole le rilegge da solo.
 */
@Composable
internal fun RulesScreen(state: RulesUiState?, actions: RulesActions) {
  FluidScreen(title = stringResource(R.string.rules_title), onBack = actions.onBack) {
    if (state == null) return@FluidScreen

    item {
      GallerySectionHeader(
        title = stringResource(R.string.rules_section_preset),
        detail = stringResource(R.string.rules_section_preset_detail),
      )
    }
    item {
      FluidListGroup {
        PresetChoices.forEachIndexed { index, preset ->
          if (index > 0) FluidListDivider()
          val info = PresetCatalog.info(preset)
          FluidListRow(
            title = info.title,
            subtitle = info.summary,
            tone = if (preset == state.preset) FluidTone.Primary else FluidTone.Neutral,
            leading = { RowGlyph(preset.icon()) },
            badge = { FluidSwitch(checked = preset == state.preset, onCheckedChange = null) },
            selected = preset == state.preset,
            onClick = { if (preset != state.preset) actions.onPreset(preset) },
          )
        }
      }
    }

    item {
      GallerySectionHeader(
        title = stringResource(R.string.rules_section_mobile),
        detail = stringResource(R.string.rules_section_mobile_detail),
      )
    }
    item {
      FluidListGroup {
        CapRow(
          title = stringResource(R.string.rules_cap_preset),
          subtitle = state.presetCapBytes?.let { stringResource(R.string.rules_cap_preset_detail, ByteFormat.size(it)) }
            ?: stringResource(R.string.rules_cap_none_detail),
          selected = state.cap == CapChoice.FromPreset,
          onSelect = { actions.onCap(CapChoice.FromPreset) },
        )
        for (bytes in MobileCapOptions) {
          FluidListDivider()
          CapRow(
            title = ByteFormat.size(bytes),
            subtitle = stringResource(R.string.rules_cap_limit_detail),
            selected = state.cap == CapChoice.Limit(bytes),
            onSelect = { actions.onCap(CapChoice.Limit(bytes)) },
          )
        }
        FluidListDivider()
        CapRow(
          title = stringResource(R.string.rules_cap_none),
          subtitle = stringResource(R.string.rules_cap_none_detail),
          selected = state.cap == CapChoice.Unlimited,
          onSelect = { actions.onCap(CapChoice.Unlimited) },
        )
      }
    }
    item { FluidSectionFootnote(text = stringResource(R.string.rules_cap_footnote)) }

    item { GallerySectionHeader(title = stringResource(R.string.rules_section_roaming)) }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.rules_roaming),
          subtitle = stringResource(R.string.rules_roaming_detail),
          tone = if (state.allowRoaming) FluidTone.Warning else FluidTone.Neutral,
          leading = { RowGlyph(Icons.Rounded.Language) },
          badge = { FluidSwitch(checked = state.allowRoaming, onCheckedChange = null) },
          onClick = { actions.onRoaming(!state.allowRoaming) },
        )
      }
    }

    // FAB-4: l'interruttore previsto da docs/regole-attivita.md (§6, punto 7), legato a `pauseOnPowerSaver`: prima non compariva in nessuna schermata.
    item { GallerySectionHeader(title = stringResource(R.string.rules_section_power_saver)) }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.rules_power_saver),
          subtitle = stringResource(R.string.rules_power_saver_detail),
          tone = if (state.pauseOnPowerSaver) FluidTone.Primary else FluidTone.Neutral,
          leading = { RowGlyph(Icons.Rounded.BatterySaver) },
          badge = { FluidSwitch(checked = state.pauseOnPowerSaver, onCheckedChange = null) },
          onClick = { actions.onPauseOnPowerSaver(!state.pauseOnPowerSaver) },
        )
      }
    }
    item { FluidSectionFootnote(text = stringResource(R.string.rules_footnote)) }
  }
}

@Composable
private fun CapRow(title: String, subtitle: String, selected: Boolean, onSelect: () -> Unit) {
  FluidListRow(
    title = title,
    subtitle = subtitle,
    tone = if (selected) FluidTone.Primary else FluidTone.Neutral,
    leading = { RowGlyph(Icons.Rounded.DataUsage) },
    badge = { FluidSwitch(checked = selected, onCheckedChange = null) },
    selected = selected,
    onClick = onSelect,
  )
}

private fun Preset.icon(): ImageVector = when (this) {
  Preset.NIGHT -> Icons.Rounded.Nightlight
  Preset.BALANCED -> Icons.Rounded.Balance
  Preset.WIFI_ALWAYS -> Icons.Rounded.Wifi
  Preset.MAXIMUM -> Icons.Rounded.AllInclusive
  Preset.CUSTOM -> Icons.Rounded.Balance
}
