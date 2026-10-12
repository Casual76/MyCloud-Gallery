package dev.pampa.mycloudgallery.feature.diagnostics

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection

@Composable
internal fun DiagnosticsRoute(onBack: () -> Unit) {
  val viewModel: DiagnosticsViewModel = hiltViewModel()
  val state by viewModel.state.collectAsStateWithLifecycle()
  DiagnosticsScreen(state = state, onBack = onBack, onRefresh = viewModel::refresh)
}

/**
 * Scheletro P0 della Diagnostica: le sezioni del §2.6 con il numero di voci di ciascuna, e gli eventi
 * ricordati. Nessuna voce e nessun dato personale a schermo finche' P8 non disegna il dettaglio.
 */
@Composable
fun DiagnosticsScreen(state: DiagnosticsUiState, onBack: () -> Unit, onRefresh: () -> Unit) {
  FluidScreen(
    title = stringResource(R.string.diagnostics_title),
    onBack = onBack,
    isRefreshing = state.loading,
    onRefresh = onRefresh,
  ) {
    item { FluidSectionHeader(title = stringResource(R.string.diagnostics_section_overview)) }
    item {
      FluidListGroup {
        DiagSection.entries.forEachIndexed { index, section ->
          if (index > 0) FluidListDivider()
          val count = state.sections[section]?.size ?: 0
          FluidListRow(
            title = stringResource(section.titleRes()),
            subtitle = if (count == 0) {
              stringResource(R.string.diagnostics_no_items)
            } else {
              pluralStringResource(R.plurals.diagnostics_items, count, count)
            },
            disclosure = false,
          )
        }
      }
    }
    item {
      FluidSectionFootnote(
        text = pluralStringResource(R.plurals.diagnostics_recent_events, state.recentEvents, state.recentEvents),
      )
    }
  }
}

@StringRes
private fun DiagSection.titleRes(): Int = when (this) {
  DiagSection.NETWORK_AND_NAS -> R.string.diagnostics_section_network
  DiagSection.CONNECTION_TEST -> R.string.diagnostics_section_connection_test
  DiagSection.ACTIVITIES -> R.string.diagnostics_section_activities
  DiagSection.RECENT_EVENTS -> R.string.diagnostics_section_recent
  DiagSection.STORAGE -> R.string.diagnostics_section_storage
  DiagSection.MONTHLY_TRAFFIC -> R.string.diagnostics_section_traffic
  DiagSection.ANDROID_WARNINGS -> R.string.diagnostics_section_android
  DiagSection.TRUST -> R.string.diagnostics_section_trust
  DiagSection.PROFILES -> R.string.diagnostics_section_profiles
}
