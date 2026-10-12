package dev.pampa.mycloudgallery.feature.diagnostics

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidStatusBadge
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.feature.diagnostics.ui.icon
import dev.pampa.mycloudgallery.feature.diagnostics.ui.titleRes
import dev.pampa.mycloudgallery.feature.diagnostics.ui.tone

/**
 * La pagina d'ingresso della Diagnostica (docs/fase2-progetto.md §2.6): il riepilogo, le nove sezioni con quante
 * voci hanno e quante sono da guardare, e l'esportazione.
 *
 * Le firme dello scheletro P0 (`state`, `onBack`, `onRefresh`) restano com'erano: gli screenshot di `:app` le
 * usano. Le uscite verso le sezioni e l'esportazione hanno un valore di partenza che non fa niente.
 */
@Composable
fun DiagnosticsScreen(
  state: DiagnosticsUiState,
  onBack: () -> Unit,
  onRefresh: () -> Unit,
  onOpenSection: (DiagSection) -> Unit = {},
  onExport: () -> Unit = {},
) {
  FluidScreen(
    title = stringResource(R.string.diagnostics_title),
    subtitle = stringResource(R.string.diagnostics_subtitle),
    onBack = onBack,
    isRefreshing = state.loading,
    onRefresh = onRefresh,
  ) {
    item { Summary(state) }
    item { FluidSectionHeader(title = stringResource(R.string.diagnostics_section_overview)) }
    item {
      FluidListGroup {
        DiagSection.entries.forEachIndexed { index, section ->
          if (index > 0) FluidListDivider()
          SectionRow(section, state, onClick = { onOpenSection(section) })
        }
      }
    }
    item { FluidSectionHeader(title = stringResource(R.string.diagnostics_export_section_share)) }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.diagnostics_export_row_title),
          subtitle = stringResource(R.string.diagnostics_export_row_subtitle),
          leading = { Icon(imageVector = Icons.Rounded.IosShare, contentDescription = null) },
          tone = FluidTone.Primary,
          onClick = onExport,
        )
      }
    }
    item {
      FluidSectionFootnote(
        text = pluralStringResource(R.plurals.diagnostics_recent_events, state.recentEvents, state.recentEvents),
      )
    }
  }
}

@Composable
private fun Summary(state: DiagnosticsUiState) {
  val attention = state.attentionCount
  when {
    state.loading && state.sections.isEmpty() -> FluidInlineMessage(
      title = stringResource(R.string.diagnostics_summary_loading_title),
      message = stringResource(R.string.diagnostics_summary_loading_message),
      tone = FluidTone.Info,
    )
    attention == 0 -> FluidInlineMessage(
      title = stringResource(R.string.diagnostics_summary_ok_title),
      message = stringResource(R.string.diagnostics_summary_ok_message),
      tone = FluidTone.Success,
    )
    else -> FluidInlineMessage(
      title = pluralStringResource(R.plurals.diagnostics_summary_attention_title, attention, attention),
      message = stringResource(R.string.diagnostics_summary_attention_message),
      tone = if (state.sections.values.any { items -> items.any { it.severity == DiagSeverity.ERROR } }) {
        FluidTone.Danger
      } else {
        FluidTone.Warning
      },
    )
  }
}

@Composable
private fun SectionRow(section: DiagSection, state: DiagnosticsUiState, onClick: () -> Unit) {
  val count = state.sections[section]?.size ?: 0
  val attention = state.attentionIn(section)
  val worst = state.worst(section)
  FluidListRow(
    title = stringResource(section.titleRes()),
    subtitle = if (count == 0) {
      stringResource(R.string.diagnostics_no_items)
    } else {
      pluralStringResource(R.plurals.diagnostics_items, count, count)
    },
    tone = worst?.tone() ?: FluidTone.Neutral,
    leading = { Icon(imageVector = section.icon(), contentDescription = null) },
    badge = if (attention > 0) {
      { FluidStatusBadge(label = pluralStringResource(R.plurals.diagnostics_section_attention, attention, attention), tone = worst?.tone() ?: FluidTone.Warning) }
    } else {
      null
    },
    onClick = onClick,
  )
}
