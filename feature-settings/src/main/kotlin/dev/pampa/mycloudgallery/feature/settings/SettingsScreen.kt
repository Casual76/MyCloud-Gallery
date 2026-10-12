package dev.pampa.mycloudgallery.feature.settings

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
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus

@Composable
internal fun SettingsRoute(
  onBack: () -> Unit,
  openDiagnostics: () -> Unit,
  resumeStep: (OnboardingStep) -> Unit,
  developerSection: (@Composable () -> Unit)?,
) {
  val viewModel: SettingsViewModel = hiltViewModel()
  val state by viewModel.state.collectAsStateWithLifecycle()
  SettingsScreen(
    state = state,
    onBack = onBack,
    openDiagnostics = openDiagnostics,
    resumeStep = resumeStep,
    developerSection = developerSection,
  )
}

/**
 * Scheletro P0 delle Impostazioni: le voci che P7 riempira', la sezione "Da completare" (passi saltati) e la
 * Diagnostica. Le voci senza pagina non sono ancora toccabili.
 */
@Composable
fun SettingsScreen(
  state: SettingsUiState,
  onBack: () -> Unit,
  openDiagnostics: () -> Unit,
  resumeStep: (OnboardingStep) -> Unit,
  developerSection: (@Composable () -> Unit)? = null,
) {
  FluidScreen(title = stringResource(R.string.settings_title), onBack = onBack) {
    item { FluidSectionHeader(title = stringResource(R.string.settings_section_family)) }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.settings_devices),
          subtitle = devicesSubtitle(state),
          disclosure = false,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.settings_family),
          subtitle = stringResource(R.string.settings_family_detail),
          disclosure = false,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.settings_nas_account),
          subtitle = stringResource(R.string.settings_nas_account_detail),
          disclosure = false,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.settings_network),
          subtitle = stringResource(R.string.settings_network_detail),
          disclosure = false,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.settings_security),
          subtitle = stringResource(R.string.settings_security_detail),
          disclosure = false,
        )
      }
    }
    if (state.pendingSteps.isNotEmpty()) {
      item { FluidSectionHeader(title = stringResource(R.string.settings_section_pending)) }
      item {
        FluidListGroup {
          state.pendingSteps.forEachIndexed { index, step ->
            if (index > 0) FluidListDivider()
            FluidListRow(
              title = stringResource(R.string.settings_pending_step, step.number),
              subtitle = stringResource(R.string.settings_pending_detail),
              onClick = { resumeStep(step) },
            )
          }
        }
      }
    }
    item { FluidSectionHeader(title = stringResource(R.string.settings_section_support)) }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.settings_diagnostics),
          subtitle = stringResource(R.string.settings_diagnostics_detail),
          onClick = openDiagnostics,
        )
      }
    }
    item { FluidSectionFootnote(text = stringResource(R.string.settings_placeholder_note)) }
    if (developerSection != null) {
      item { FluidSectionHeader(title = stringResource(R.string.settings_section_developer)) }
      item { developerSection() }
    }
  }
}

@Composable
private fun devicesSubtitle(state: SettingsUiState): String = when {
  state.trust is TrustStatus.NotConfigured -> stringResource(R.string.settings_devices_none)
  state.pendingRequests > 0 -> pluralStringResource(R.plurals.settings_devices_pending, state.pendingRequests, state.pendingRequests)
  else -> pluralStringResource(R.plurals.settings_devices_count, state.deviceCount, state.deviceCount)
}
