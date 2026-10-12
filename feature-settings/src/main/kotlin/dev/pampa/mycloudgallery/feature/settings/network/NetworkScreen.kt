package dev.pampa.mycloudgallery.feature.settings.network

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.SignalWifiOff
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidAmbient
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidHero
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.antigravity.fluidengine.ui.fluid.FluidLoadingBlock
import dev.antigravity.fluidengine.ui.fluid.FluidRowValue
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.core.network.contract.HomeMatch
import dev.pampa.mycloudgallery.core.network.contract.LocalNetworkAccess
import dev.pampa.mycloudgallery.core.network.contract.NetPlace
import dev.pampa.mycloudgallery.core.network.contract.Via
import dev.pampa.mycloudgallery.core.network.contract.VpnAutomationStatus
import dev.pampa.mycloudgallery.core.network.contract.VpnCapability
import dev.pampa.mycloudgallery.core.network.contract.VpnCommandResult
import dev.pampa.mycloudgallery.core.network.contract.VpnObservation
import dev.pampa.mycloudgallery.core.network.contract.VpnSkipReason
import dev.pampa.mycloudgallery.core.network.contract.VpnSuspendReason
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.ui.ConfirmSheet
import dev.pampa.mycloudgallery.feature.settings.ui.Note
import dev.pampa.mycloudgallery.feature.settings.ui.RowGlyph
import dev.pampa.mycloudgallery.feature.settings.ui.StatusPill
import dev.pampa.mycloudgallery.feature.settings.ui.messageRes
import dev.antigravity.fluidengine.ui.theme.FluidCard
import java.text.DateFormat
import java.util.Date
import androidx.compose.runtime.getValue

internal class NetworkActions(
  val onBack: () -> Unit,
  val onCapture: () -> Unit,
  val onConfirmCapture: () -> Unit,
  val onCancelCapture: () -> Unit,
  val onTunnel: (Boolean) -> Unit,
  val onOpenWireGuard: () -> Unit,
  val onConfigureVpn: () -> Unit,
) {
  companion object {
    val None = NetworkActions({}, {}, {}, {}, {}, {}, {})
  }
}

@Composable
internal fun NetworkRoute(onBack: () -> Unit, configureVpn: () -> Unit) {
  val viewModel: NetworkViewModel = hiltViewModel()
  val state by viewModel.state.collectAsStateWithLifecycle()
  val context = LocalContext.current
  NetworkScreen(
    state = state,
    actions = NetworkActions(
      onBack = onBack,
      onCapture = viewModel::startCapture,
      onConfirmCapture = viewModel::confirmCapture,
      onCancelCapture = viewModel::cancelCapture,
      onTunnel = viewModel::tryTunnel,
      onOpenWireGuard = {
        viewModel.wireGuardIntent()?.let { intent ->
          try {
            context.startActivity(intent)
          } catch (_: android.content.ActivityNotFoundException) {
            // WireGuard sparito fra la lettura dello stato e il tocco: non c'e' altro da fare.
          }
        }
      },
      onConfigureVpn = configureVpn,
    ),
  )
}

/**
 * Impostazioni → Rete e VPN (piano 2.1 e 2.5). In cima, dove si trova il telefono rispetto al NAS e se il
 * NAS si raggiunge (le stesse parole dell'indicatore globale, §3.5); poi l'impronta di casa e il controllo
 * di WireGuard.
 */
@Composable
internal fun NetworkScreen(state: NetworkUiState, actions: NetworkActions) {
  val hero = placeHero(state)
  FluidScreen(
    title = stringResource(R.string.network_title),
    onBack = actions.onBack,
    ambient = FluidAmbient(tone = hero.tone, motif = FluidHeroMotif.Glow),
  ) {
    item {
      FluidHero(
        tone = hero.tone,
        motif = FluidHeroMotif.Ripples,
        eyebrow = stringResource(R.string.network_eyebrow),
        value = stringResource(hero.value),
        title = stringResource(hero.title),
        description = stringResource(hero.detail(state)),
        icon = hero.icon,
      )
    }

    if (state.snapshot.localNetwork == LocalNetworkAccess.DENIED) {
      item {
        Note(
          title = stringResource(R.string.network_local_denied_title),
          message = stringResource(R.string.nas_error_local_network),
          tone = FluidTone.Danger,
        )
      }
    }
    if (!state.snapshot.ipv6Global && state.snapshot.place in IPV6_MATTERS) {
      item {
        Note(
          title = stringResource(R.string.network_ipv4_title),
          message = stringResource(R.string.network_ipv4_message),
          tone = FluidTone.Warning,
        )
      }
    }

    item {
      FluidSectionHeader(
        title = stringResource(R.string.network_section_home),
        detail = stringResource(R.string.network_section_home_detail),
      )
    }
    item {
      FluidListGroup {
        val fingerprint = state.fingerprint
        FluidListRow(
          title = stringResource(R.string.network_fingerprint),
          subtitle = if (fingerprint == null) {
            stringResource(R.string.network_fingerprint_none)
          } else {
            stringResource(
              R.string.network_fingerprint_detail,
              pluralStringResource(R.plurals.network_ula_count, fingerprint.ulaPrefixes.size, fingerprint.ulaPrefixes.size),
              DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(fingerprint.capturedAt)),
            )
          },
          tone = if (fingerprint == null) FluidTone.Warning else FluidTone.Primary,
          leading = { RowGlyph(Icons.Rounded.Fingerprint) },
          disclosure = false,
          labels = {
            StatusPill(
              stringResource(if (fingerprint == null) R.string.network_pill_missing else R.string.network_pill_known),
              if (fingerprint == null) FluidTone.Warning else FluidTone.Success,
            )
          },
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.network_current),
          subtitle = stringResource(state.snapshot.home.labelRes()),
          tone = if (state.snapshot.home == HomeMatch.Match) FluidTone.Success else FluidTone.Neutral,
          leading = { RowGlyph(Icons.Rounded.Home) },
          disclosure = false,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.network_ipv6),
          subtitle = stringResource(R.string.network_ipv6_detail),
          tone = FluidTone.Neutral,
          leading = { RowGlyph(Icons.Rounded.Public) },
          disclosure = false,
          badge = { FluidRowValue(stringResource(if (state.snapshot.ipv6Global) R.string.value_yes else R.string.value_no)) },
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.network_recapture),
          subtitle = stringResource(R.string.network_recapture_detail),
          tone = FluidTone.Info,
          leading = { RowGlyph(Icons.Rounded.Sync) },
          onClick = actions.onCapture,
        )
      }
    }
    item { CaptureStatus(state.capture, actions) }

    item {
      FluidSectionHeader(
        title = stringResource(R.string.network_section_vpn),
        detail = stringResource(R.string.network_section_vpn_detail),
      )
    }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.network_wg),
          subtitle = stringResource(state.vpnCapability.detailRes()),
          tone = if (state.vpnCapability == VpnCapability.READY) FluidTone.Success else FluidTone.Warning,
          leading = { RowGlyph(Icons.Rounded.VpnKey) },
          disclosure = false,
          labels = {
            StatusPill(
              stringResource(state.vpnCapability.pillRes()),
              if (state.vpnCapability == VpnCapability.READY) FluidTone.Success else FluidTone.Warning,
            )
          },
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.network_automation),
          subtitle = automationText(state.automation),
          tone = when (state.automation) {
            is VpnAutomationStatus.Suspended -> FluidTone.Warning
            VpnAutomationStatus.Off -> FluidTone.Neutral
            else -> FluidTone.Info
          },
          leading = { RowGlyph(Icons.Rounded.Tune) },
          disclosure = false,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.network_vpn_now),
          subtitle = stringResource(state.snapshot.vpn.labelRes()),
          tone = if (state.snapshot.vpn == VpnObservation.FOREIGN_VPN) FluidTone.Danger else FluidTone.Neutral,
          leading = { RowGlyph(Icons.Rounded.VpnLock) },
          disclosure = false,
        )
      }
    }

    item { CommandStatus(state.command) }

    if (state.vpnCapability == VpnCapability.READY) {
      item {
        FluidButton(
          text = stringResource(R.string.network_try_up),
          onClick = { actions.onTunnel(true) },
          style = FluidButtonStyle.Tinted,
          size = FluidButtonSize.Large,
          fillWidth = true,
          enabled = state.command !is VpnCommandUi.Running,
        )
      }
      item {
        FluidButton(
          text = stringResource(R.string.network_try_down),
          onClick = { actions.onTunnel(false) },
          style = FluidButtonStyle.Tinted,
          size = FluidButtonSize.Large,
          fillWidth = true,
          enabled = state.command !is VpnCommandUi.Running,
        )
      }
    }
    if (state.canOpenWireGuard) {
      item {
        FluidButton(
          text = stringResource(R.string.network_open_wg),
          onClick = actions.onOpenWireGuard,
          style = if (state.vpnCapability == VpnCapability.READY) FluidButtonStyle.Plain else FluidButtonStyle.Filled,
          size = FluidButtonSize.Large,
          fillWidth = true,
        )
      }
    }
    if (state.vpnCapability != VpnCapability.READY) {
      item {
        FluidButton(
          text = stringResource(R.string.network_configure),
          onClick = actions.onConfigureVpn,
          style = FluidButtonStyle.Tinted,
          size = FluidButtonSize.Large,
          fillWidth = true,
        )
      }
    }
    item { FluidSectionFootnote(text = stringResource(R.string.network_footnote)) }
  }

  ConfirmSheet(
    visible = state.capture is CaptureUi.Ready,
    onDismiss = actions.onCancelCapture,
    icon = Icons.Rounded.Home,
    tone = GallerySheetTone.Caution,
    title = stringResource(R.string.capture_sheet_title),
    message = stringResource(R.string.capture_sheet_message),
    confirmLabel = stringResource(R.string.capture_sheet_confirm),
    confirmStyle = FluidButtonStyle.Filled,
    onConfirm = actions.onConfirmCapture,
  )
}

private val IPV6_MATTERS = setOf(NetPlace.AWAY_VPN_WIFI, NetPlace.AWAY_VPN_MOBILE, NetPlace.VPN_OFF)

/** L'apertura della pagina: come ci si chiama in questa rete e cosa succede al NAS. */
private class PlaceHero(
  val tone: FluidHeroTone,
  val icon: ImageVector,
  val value: Int,
  val title: Int,
  val detail: (NetworkUiState) -> Int,
)

private fun placeHero(state: NetworkUiState): PlaceHero {
  val detail: (NetworkUiState) -> Int = { s ->
    when (val route = s.route) {
      is RouteUi.Allowed -> when (route.via) {
        Via.HOME_LAN -> R.string.network_route_home
        Via.HOME_LAN_THROUGH_VPN -> R.string.network_route_home_vpn
        Via.VPN -> R.string.network_route_vpn
      }
      is RouteUi.Denied -> route.reason.messageRes()
    }
  }
  if (state.snapshot.vpn == VpnObservation.FOREIGN_VPN) {
    return PlaceHero(FluidHeroTone.Alert, Icons.Rounded.VpnLock, R.string.place_foreign_value, R.string.place_foreign_title, detail)
  }
  return when (state.snapshot.place) {
    NetPlace.HOME -> PlaceHero(FluidHeroTone.PrimaryToSecondary, Icons.Rounded.Home, R.string.place_home_value, R.string.place_home_title, detail)
    NetPlace.AWAY_VPN_WIFI -> PlaceHero(FluidHeroTone.Secondary, Icons.Rounded.Wifi, R.string.place_away_value, R.string.place_away_wifi_title, detail)
    NetPlace.AWAY_VPN_MOBILE -> PlaceHero(FluidHeroTone.Secondary, Icons.Rounded.CellTower, R.string.place_away_value, R.string.place_away_mobile_title, detail)
    NetPlace.ROAMING -> PlaceHero(FluidHeroTone.Tertiary, Icons.Rounded.Language, R.string.place_roaming_value, R.string.place_roaming_title, detail)
    NetPlace.VPN_OFF -> PlaceHero(FluidHeroTone.Tertiary, Icons.Rounded.VpnLock, R.string.place_vpn_off_value, R.string.place_vpn_off_title, detail)
    NetPlace.UNTRUSTED -> PlaceHero(FluidHeroTone.Alert, Icons.Rounded.Lan, R.string.place_untrusted_value, R.string.place_untrusted_title, detail)
    NetPlace.OFFLINE -> PlaceHero(FluidHeroTone.Secondary, Icons.Rounded.SignalWifiOff, R.string.place_offline_value, R.string.place_offline_title, detail)
  }
}

private fun HomeMatch.labelRes(): Int = when (this) {
  HomeMatch.Match -> R.string.match_match
  is HomeMatch.Weak -> R.string.match_weak
  HomeMatch.NoMatch -> R.string.match_none
  HomeMatch.Unknown -> R.string.match_unknown
}

private fun VpnObservation.labelRes(): Int = when (this) {
  VpnObservation.NONE -> R.string.vpn_obs_none
  VpnObservation.HOME_TUNNEL -> R.string.vpn_obs_home
  VpnObservation.FOREIGN_VPN -> R.string.vpn_obs_foreign
  VpnObservation.UNKNOWN -> R.string.vpn_obs_unknown
}

private fun VpnCapability.pillRes(): Int = when (this) {
  VpnCapability.READY -> R.string.wg_pill_ready
  VpnCapability.WIREGUARD_MISSING -> R.string.wg_pill_missing
  VpnCapability.PERMISSION_MISSING -> R.string.wg_pill_permission
  VpnCapability.NOT_CONFIGURED -> R.string.wg_pill_not_configured
}

private fun VpnCapability.detailRes(): Int = when (this) {
  VpnCapability.READY -> R.string.wg_ready
  VpnCapability.WIREGUARD_MISSING -> R.string.wg_missing
  VpnCapability.PERMISSION_MISSING -> R.string.wg_permission
  VpnCapability.NOT_CONFIGURED -> R.string.wg_not_configured
}

@Composable
private fun automationText(status: VpnAutomationStatus): String = when (status) {
  VpnAutomationStatus.Off -> stringResource(R.string.auto_off)
  VpnAutomationStatus.Idle -> stringResource(R.string.auto_idle)
  is VpnAutomationStatus.WaitingForIdleSession -> stringResource(R.string.auto_waiting)
  is VpnAutomationStatus.Commanding -> stringResource(if (status.up) R.string.auto_commanding_up else R.string.auto_commanding_down)
  is VpnAutomationStatus.Done -> stringResource(
    R.string.auto_done,
    stringResource(if (status.up) R.string.auto_word_on else R.string.auto_word_off),
    stringResource(status.result.shortRes()),
  )
  is VpnAutomationStatus.Suspended -> stringResource(
    when (status.reason) {
      VpnSuspendReason.LOCKDOWN_SUSPECTED -> R.string.auto_suspended_lockdown
      VpnSuspendReason.NOT_CONTROLLABLE -> R.string.auto_suspended_not_controllable
    },
  )
}

private fun VpnCommandResult.shortRes(): Int = when (this) {
  VpnCommandResult.Confirmed -> R.string.cmd_short_confirmed
  VpnCommandResult.NotObserved -> R.string.cmd_short_not_observed
  VpnCommandResult.LostConnectivity -> R.string.cmd_short_lost
  is VpnCommandResult.Skipped -> R.string.cmd_short_skipped
}

/** L'esito dell'ultima prova a mano, nelle parole del §2.4. */
@Composable
private fun CommandStatus(command: VpnCommandUi) {
  when (command) {
    VpnCommandUi.Idle -> Unit
    is VpnCommandUi.Running -> FluidLoadingBlock()
    is VpnCommandUi.Finished -> {
      val (title, message, tone) = when (val result = command.result) {
        VpnCommandResult.Confirmed -> Triple(R.string.cmd_ok_title, if (command.up) R.string.cmd_ok_up else R.string.cmd_ok_down, FluidTone.Success)
        VpnCommandResult.NotObserved -> Triple(R.string.cmd_none_title, R.string.cmd_none, FluidTone.Warning)
        VpnCommandResult.LostConnectivity -> Triple(R.string.cmd_lost_title, R.string.cmd_lost, FluidTone.Danger)
        is VpnCommandResult.Skipped -> Triple(R.string.cmd_skipped_title, result.reason.messageRes(), FluidTone.Info)
      }
      Note(title = stringResource(title), message = stringResource(message), tone = tone)
    }
  }
}

private fun VpnSkipReason.messageRes(): Int = when (this) {
  VpnSkipReason.WIREGUARD_MISSING -> R.string.wg_missing
  VpnSkipReason.PERMISSION_MISSING -> R.string.wg_permission
  VpnSkipReason.NOT_CONFIGURED -> R.string.wg_not_configured
  VpnSkipReason.AUTOMATION_DISABLED -> R.string.skip_disabled
  VpnSkipReason.ALREADY_IN_STATE -> R.string.skip_already
  VpnSkipReason.RATE_LIMITED -> R.string.skip_rate_limited
  VpnSkipReason.BACKGROUND_LIMIT -> R.string.skip_background
}

/** La nuova raccolta dell'impronta (E-R16): cosa sta succedendo, o perche' non si puo'. */
@Composable
private fun CaptureStatus(capture: CaptureUi, actions: NetworkActions) {
  when (capture) {
    CaptureUi.Idle -> Unit
    CaptureUi.Capturing, CaptureUi.Verifying -> FluidCard {
      FluidLoadingBlock()
      androidx.compose.material3.Text(
        text = stringResource(if (capture == CaptureUi.Verifying) R.string.capture_verifying else R.string.capture_reading),
        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    CaptureUi.NoWifi -> Note(stringResource(R.string.capture_no_wifi_title), stringResource(R.string.capture_no_wifi), tone = FluidTone.Warning)
    CaptureUi.NoUla -> Note(stringResource(R.string.capture_no_ula_title), stringResource(R.string.capture_no_ula), tone = FluidTone.Warning)
    CaptureUi.CannotVerify -> Note(stringResource(R.string.capture_cannot_title), stringResource(R.string.capture_cannot), tone = FluidTone.Info)
    is CaptureUi.Ready -> Note(
      title = stringResource(R.string.capture_ready_title),
      message = stringResource(if (capture.sameAsSaved) R.string.capture_ready_same else R.string.capture_ready_new),
      tone = FluidTone.Success,
    )
    is CaptureUi.Rejected -> Note(
      title = stringResource(if (capture.identityMismatch) R.string.capture_mismatch_title else R.string.capture_failed_title),
      message = stringResource(
        if (capture.identityMismatch) R.string.capture_mismatch else capture.error?.messageRes() ?: R.string.problem_generic,
      ),
      tone = FluidTone.Danger,
    )
    CaptureUi.Saved -> Note(stringResource(R.string.capture_saved_title), stringResource(R.string.capture_saved), tone = FluidTone.Success)
  }
}
