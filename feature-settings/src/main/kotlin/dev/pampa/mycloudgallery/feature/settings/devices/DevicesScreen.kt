package dev.pampa.mycloudgallery.feature.settings.devices

import dev.pampa.mycloudgallery.core.designsystem.design.GalleryInfoRow
import androidx.compose.ui.Modifier
import dev.pampa.mycloudgallery.feature.settings.ui.SettingsDimens
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryFigureState
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryIllustrationTone
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.NotificationImportant
import androidx.compose.runtime.Composable
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconStatus
import dev.pampa.mycloudgallery.feature.settings.ui.DescribedRow
import dev.pampa.mycloudgallery.feature.settings.ui.SettingsStatusHeader
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidAmbient
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.family.contract.StaleReason
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.ui.FeedbackNote
import dev.pampa.mycloudgallery.feature.settings.ui.Note
import dev.pampa.mycloudgallery.feature.settings.ui.RowTile
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
    onReissue = viewModel::reissue,
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
  onReissue: () -> Unit = {},
) {
  FluidScreen(
    title = stringResource(R.string.devices_title),
    onBack = onBack,
    isRefreshing = state.refreshing,
    ambient = FluidAmbient(tone = FluidHeroTone.Primary, motif = FluidHeroMotif.Glow),
  ) {
    if (state.trust is TrustStatus.NotConfigured) {
      item {
        // Giro 2 della fase I (CS-17): la forma unica dello stato vuoto (figura e titolo); la frase dietro la «i» del componente.
        GalleryFigureState(
          icon = Icons.Rounded.Devices,
          title = stringResource(R.string.devices_empty_title),
          tone = GalleryIllustrationTone.Brand,
          fill = false,
          why = stringResource(R.string.devices_empty_detail),
        )
      }
      return@FluidScreen
    }

    // Giro 1 della fase I (CU-5): in cima quanti dispositivi lavorano, con il segno se qualcuno aspetta; i numeri di prima (da approvare, non piu'
    // attivi) li dicono i gruppi qui sotto.
    item {
      SettingsStatusHeader(
        icon = Icons.Rounded.Devices,
        family = GalleryIconFamily.People,
        value = pluralStringResource(R.plurals.devices_active_value, state.activeCount, state.activeCount),
        status = if (state.requests.any { it.windowOpen }) GalleryIconStatus.Attention else null,
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

    // N3 (Fase 10, M3): la versione fidata e' stata sostituita sul NAS. La ripubblicazione non e' piu' automatica: un tasto, con il
    // foglio dell'impronta o del PIN.
    if (state.reissueDue) {
      item {
        FluidListGroup {
          // L'avvertimento sul registro della famiglia resta in pagina (CG-4: le chiavi).
          FluidListRow(
            title = stringResource(R.string.devices_reissue),
            subtitle = stringResource(R.string.devices_reissue_detail),
            tone = FluidTone.Danger,
            leading = { RowTile(Icons.Rounded.NotificationImportant, status = GalleryIconStatus.Problem, family = GalleryIconFamily.Danger) },
            leadingTile = false,
            onClick = onReissue,
          )
        }
      }
    }

    if (state.requests.isNotEmpty()) {
      item {
        GallerySectionHeader(title = stringResource(R.string.devices_section_requests))
      }
      item {
        FluidListGroup {
          state.requests.forEachIndexed { index, request ->
            if (index > 0) FluidListDivider()
            RequestRow(request = request, onClick = { onOpenRequest(request.id) })
          }
        }
      }
      item { GalleryInfoRow(label = stringResource(R.string.devices_requests_info), detail = stringResource(R.string.devices_section_requests_detail), modifier = Modifier.fillMaxWidth().padding(horizontal = SettingsDimens.CardPadding)) }
    }

    state.thisDevice?.let { device ->
      item { GallerySectionHeader(title = stringResource(R.string.devices_section_this)) }
      item { FluidListGroup { DeviceRow(device = device, onClick = { onOpenDevice(device.id) }) } }
    }

    if (state.others.isNotEmpty()) {
      item { GallerySectionHeader(title = stringResource(R.string.devices_section_others)) }
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
        GallerySectionHeader(title = stringResource(R.string.devices_section_inactive))
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
      item { GallerySectionHeader(title = stringResource(R.string.devices_section_reports)) }
      item {
        FluidListGroup {
          DescribedRow(
            title = stringResource(R.string.devices_convert_reports),
            detail = stringResource(R.string.devices_convert_reports_detail),
            icon = Icons.Rounded.NotificationImportant,
            tone = FluidTone.Warning,
            onClick = onConvertReports,
          )
        }
      }
    }

    item {
      GalleryInfoRow(label = stringResource(R.string.devices_info), detail = stringResource(R.string.devices_section_inactive_detail) + " " + stringResource(R.string.devices_footnote), modifier = Modifier.fillMaxWidth().padding(horizontal = SettingsDimens.CardPadding))
    }
  }
}

private fun staleMessage(reason: StaleReason): Int = when (reason) {
  StaleReason.NOT_REFRESHED -> R.string.stale_not_refreshed
  StaleReason.NEWER_UNVERIFIED -> R.string.stale_newer_unverified
  StaleReason.LISTING_TRUNCATED -> R.string.stale_listing_truncated
  StaleReason.NEEDS_APP_UPDATE -> R.string.stale_needs_update
  StaleReason.EQUIVOCATION -> R.string.stale_equivocation
}
