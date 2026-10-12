package dev.pampa.mycloudgallery.feature.diagnostics.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidScreenDefaults
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagAction
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.feature.diagnostics.ConnectionTestUiState
import dev.pampa.mycloudgallery.feature.diagnostics.DiagnosticsUiState
import dev.pampa.mycloudgallery.feature.diagnostics.R
import dev.pampa.mycloudgallery.feature.diagnostics.connection.ConnectionTestOutcome

/**
 * Una sezione della Diagnostica: le sue voci dalla piu' grave, ciascuna col «cosa fare» e, dove c'e', il tasto
 * dell'azione suggerita. Per il «Test di connessione» in cima c'e' il pannello che lo lancia.
 *
 * @param onRunTest lancia il test di connessione (solo nella sezione `CONNECTION_TEST`).
 * @param onAction esegue un'azione suggerita; quali azioni sono disponibili lo dice [actions].
 */
@Composable
fun DiagnosticsSectionScreen(
  section: DiagSection,
  state: DiagnosticsUiState,
  onBack: () -> Unit,
  onRefresh: () -> Unit,
  onRunTest: () -> Unit,
  onAction: (DiagAction) -> Unit,
  actions: DiagActionPolicy = DiagActionPolicy(wireGuardAvailable = state.wireGuardAvailable),
) {
  val items = state.sections[section].orEmpty()
  FluidScreen(
    title = stringResource(section.titleRes()),
    subtitle = stringResource(section.hintRes()),
    onBack = onBack,
    isRefreshing = state.loading,
    onRefresh = onRefresh,
  ) {
    if (section == DiagSection.CONNECTION_TEST) {
      item { ConnectionTestPanel(state.connectionTest, onRunTest) }
    }
    if (items.isNotEmpty()) {
      item {
        FluidListGroup {
          DiagItemRows(items, actions, onAction)
        }
      }
    } else if (section != DiagSection.CONNECTION_TEST) {
      item {
        FluidEmptyState(
          title = stringResource(R.string.diagnostics_no_items),
          detail = stringResource(R.string.diagnostics_no_items_detail),
        )
      }
    }
  }
}

/** Il pulsante del test, il suo stato e la nota su cosa fa. Parte solo quando l'utente lo chiede (E-R15). */
@Composable
private fun ConnectionTestPanel(test: ConnectionTestUiState, onRunTest: () -> Unit) {
  val running = test is ConnectionTestUiState.Running
  // Piu' elementi dentro un solo `item` si sovrapporrebbero: li impila una colonna con l'interlinea della pagina.
  Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FluidScreenDefaults.ItemSpacing)) {
    ConnectionTestPanelContent(test, running, onRunTest)
  }
}

@Composable
private fun ConnectionTestPanelContent(test: ConnectionTestUiState, running: Boolean, onRunTest: () -> Unit) {
  FluidButton(
    text = stringResource(if (running) R.string.diagnostics_test_running else R.string.diagnostics_test_run),
    onClick = onRunTest,
    size = FluidButtonSize.Large,
    loading = running,
    fillWidth = true,
    leading = { Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = null) },
  )
  FluidSectionFootnote(text = stringResource(R.string.diagnostics_test_hint))
  val outcome = (test as? ConnectionTestUiState.Finished)?.outcome
  when {
    outcome == ConnectionTestOutcome.NotConfigured -> FluidInlineMessage(
      title = stringResource(R.string.diagnostics_test_not_configured_title),
      message = stringResource(R.string.diagnostics_test_not_configured_message),
      tone = FluidTone.Info,
    )
    outcome == ConnectionTestOutcome.TimedOut -> FluidInlineMessage(
      title = stringResource(R.string.diagnostics_test_timed_out_title),
      message = stringResource(R.string.diagnostics_test_timed_out_message),
      tone = FluidTone.Danger,
    )
    outcome is ConnectionTestOutcome.Done && outcome.authSkipped && outcome.firstFailure == null -> FluidInlineMessage(
      title = stringResource(R.string.diagnostics_test_breaker_title),
      message = stringResource(R.string.diagnostics_test_breaker_message),
      tone = FluidTone.Warning,
    )
  }
}
