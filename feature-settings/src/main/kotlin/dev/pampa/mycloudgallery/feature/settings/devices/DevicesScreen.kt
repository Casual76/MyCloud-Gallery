package dev.pampa.mycloudgallery.feature.settings.devices

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.NotificationImportant
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidAmbient
import dev.antigravity.fluidengine.ui.fluid.FluidHero
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMetric
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.family.contract.StaleReason
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.ui.FeedbackNote
import dev.pampa.mycloudgallery.feature.settings.ui.Note
import dev.pampa.mycloudgallery.feature.settings.ui.RowGlyph
import androidx.compose.runtime.getValue

@Composable
internal fun DevicesRoute(
  onBack: () -> Unit,
  openRequest: (String) -> Unit,
  openDevice: (String) -> Unit,
) {
  val viewModel: DevicesViewModel = hiltViewModel()
  val state by viewModel.state.collectAsStateWithLifecycle()
  LaunchedEffect(Unit) { viewModel.refresh() }
  DevicesScreen(
    state = state,
    onBack = onBack,
    onOpenRequest = openRequest,
    onOpenDevice = openDevice,
    onConvertReports = viewModel::convertReports,
  )
}

/**
 * Impostazioni → Dispositivi (piano 2.4, L11): nome, modello, profilo, ruoli, ultima attivita' e versione
 * dell'app di ogni dispositivo, piu' le richieste d'ingresso da approvare (solo admin).
 *
 * Tre gruppi: chi chiede di entrare, i dispositivi che lavorano (questo per primo) e quelli che non lavorano
 * piu'. Una lista che non dice niente e' una lista rotta: senza famiglia o senza dispositivi dice perche'.
 */
@Composable
internal fun DevicesScreen(
  state: DevicesUiState,
  onBack: () -> Unit,
  onOpenRequest: (String) -> Unit,
  onOpenDevice: (String) -> Unit,
  onConvertReports: () -> Unit,
) {
  FluidScreen(
    title = stringResource(R.string.devices_title),
    onBack = onBack,
    isRefreshing = state.refreshing,
    ambient = FluidAmbient(tone = FluidHeroTone.Primary, motif = FluidHeroMotif.Glow),
  ) {
    if (state.trust is TrustStatus.NotConfigured) {
      item {
        FluidEmptyState(
          title = stringResource(R.string.devices_empty_title),
          detail = stringResource(R.string.devices_empty_detail),
        )
      }
      return@FluidScreen
    }

    item {
      FluidHero(
        tone = FluidHeroTone.PrimaryToSecondary,
        motif = FluidHeroMotif.Figures,
        eyebrow = stringResource(R.string.devices_hero_eyebrow),
        value = pluralStringResource(R.plurals.devices_active_value, state.activeCount, state.activeCount),
        title = stringResource(R.string.devices_hero_title),
        description = stringResource(if (state.isAdmin) R.string.devices_hero_detail_admin else R.string.devices_hero_detail),
        icon = Icons.Rounded.Devices,
        metrics = listOf(
          FluidHeroMetric(stringResource(R.string.devices_metric_waiting), state.requests.count { it.windowOpen }.toString()),
          FluidHeroMetric(stringResource(R.string.devices_metric_inactive), state.inactive.size.toString()),
        ),
      )
    }

    // R7: il Keystore non risponde: l'elenco resta (ultima lettura), ma si dice che da qui non si cambia niente.
    if (state.trust is TrustStatus.KeystoreUnavailable) {
      item {
        Note(
          title = stringResource(R.string.devices_keystore_title),
          message = stringResource(R.string.devices_keystore_message),
          tone = FluidTone.Warning,
        )
      }
    }
    (state.trust as? TrustStatus.Stale)?.let { stale ->
      item {
        Note(
          title = stringResource(R.string.devices_stale_title),
          message = stringResource(staleMessage(stale.reason)),
          tone = FluidTone.Warning,
        )
      }
    }
    state.feedback?.let { item { FeedbackNote(it) } }

    if (state.requests.isNotEmpty()) {
      item {
        FluidSectionHeader(
          title = stringResource(R.string.devices_section_requests),
          detail = stringResource(R.string.devices_section_requests_detail),
        )
      }
      item {
        FluidListGroup {
          state.requests.forEachIndexed { index, request ->
            if (index > 0) FluidListDivider()
            RequestRow(request = request, onClick = { onOpenRequest(request.id) })
          }
        }
      }
    }

    state.thisDevice?.let { device ->
      item { FluidSectionHeader(title = stringResource(R.string.devices_section_this)) }
      item { FluidListGroup { DeviceRow(device = device, onClick = { onOpenDevice(device.id) }) } }
    }

    if (state.others.isNotEmpty()) {
      item { FluidSectionHeader(title = stringResource(R.string.devices_section_others)) }
      item {
        FluidListGroup {
          state.others.forEachIndexed { index, device ->
            if (index > 0) FluidListDivider()
            DeviceRow(device = device, onClick = { onOpenDevice(device.id) })
          }
        }
      }
    }

    if (state.inactive.isNotEmpty()) {
      item {
        FluidSectionHeader(
          title = stringResource(R.string.devices_section_inactive),
          detail = stringResource(R.string.devices_section_inactive_detail),
        )
      }
      item {
        FluidListGroup {
          state.inactive.forEachIndexed { index, device ->
            if (index > 0) FluidListDivider()
            DeviceRow(device = device, onClick = { onOpenDevice(device.id) })
          }
        }
      }
    }

    if (state.isAdmin) {
      item { FluidSectionHeader(title = stringResource(R.string.devices_section_reports)) }
      item {
        FluidListGroup {
          FluidListRow(
            title = stringResource(R.string.devices_convert_reports),
            subtitle = stringResource(R.string.devices_convert_reports_detail),
            tone = FluidTone.Warning,
            leading = { RowGlyph(Icons.Rounded.NotificationImportant) },
            onClick = onConvertReports,
          )
        }
      }
    }

    item { FluidSectionFootnote(text = stringResource(R.string.devices_footnote)) }
  }
}

private fun staleMessage(reason: StaleReason): Int = when (reason) {
  StaleReason.NOT_REFRESHED -> R.string.stale_not_refreshed
  StaleReason.NEWER_UNVERIFIED -> R.string.stale_newer_unverified
  StaleReason.LISTING_TRUNCATED -> R.string.stale_listing_truncated
  StaleReason.NEEDS_APP_UPDATE -> R.string.stale_needs_update
  StaleReason.EQUIVOCATION -> R.string.stale_equivocation
}
