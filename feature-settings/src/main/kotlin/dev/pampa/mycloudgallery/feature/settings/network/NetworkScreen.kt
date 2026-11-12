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
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidAmbient
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.antigravity.fluidengine.ui.fluid.FluidLoadingBlock
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
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
import dev.pampa.mycloudgallery.core.network.contract.WireGuardPermission
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.ui.ConfirmSheet
import dev.pampa.mycloudgallery.feature.settings.ui.Note
import dev.pampa.mycloudgallery.feature.settings.ui.RowTile
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconStatus
import dev.pampa.mycloudgallery.feature.settings.ui.RowValue
import dev.pampa.mycloudgallery.feature.settings.ui.SettingsInfo
import dev.pampa.mycloudgallery.feature.settings.ui.messageRes
import dev.antigravity.fluidengine.ui.theme.FluidCard
import java.text.DateFormat
import java.util.Date
import androidx.compose.runtime.getValue
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.pampa.mycloudgallery.feature.settings.ui.SettingsStatusHeader

internal class NetworkActions(
  val onBack: () -> Unit,
  val onCapture: () -> Unit,
  val onConfirmCapture: () -> Unit,
  val onCancelCapture: () -> Unit,
  val onTunnel: (Boolean) -> Unit,
  val onOpenWireGuard: () -> Unit,
  val onConfigureVpn: () -> Unit,
  /** "Consenti il controllo": il dialogo di sistema del permesso di WireGuard (RE-1). */
  val onGrantControl: () -> Unit = {},
  /** Dopo un rifiuto definitivo: la scheda dell'app in Android (RE-1). */
  val onOpenAppSettings: () -> Unit = {},
  /** "Riattiva il controllo" dopo una sospensione dell'automazione (RE-2). */
  val onReactivate: () -> Unit = {},
  /** "Riprova" quando l'admin ha salvato l'impronta qui ma non nella configurazione firmata (RE-5). */
  val onRetryShare: () -> Unit = {},
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
  // Il permesso di WireGuard (RE-1): il dialogo di sistema parte da un tocco; a rifiuto avvenuto Android dice se lo mostrera' ancora.
  val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    val canAskAgain = context.findActivity()?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, WireGuardPermission.NAME) } ?: false
    viewModel.onControlPermissionResult(WireGuardPermission.outcomeOf(granted, canAskAgain))
  }
  // Tornando da WireGuard o dalla scheda dell'app il permesso puo' essere cambiato: si rilegge.
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshVpn() }
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
      onGrantControl = { permissionLauncher.launch(WireGuardPermission.NAME) },
      onOpenAppSettings = { context.openAppDetails() },
      onReactivate = viewModel::reactivateAutomation,
      onRetryShare = viewModel::retryShareCapture,
    ),
  )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.findActivity()
  else -> null
}

/** La scheda dell'app in Android, dove si concedono i permessi che il dialogo non mostra piu'. */
private fun Context.openAppDetails() {
  try {
    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
  } catch (_: ActivityNotFoundException) {
    // Se la scheda dell'app non si apre non c'e' altro da fare.
  }
}

/**
 * Impostazioni → Rete e VPN (piano 2.1 e 2.5; giro 1 della fase I, CU-5). In cima lo stato e basta: dove si trova il telefono rispetto al NAS
 * (le stesse parole dell'indicatore globale, §3.5) con il segno che dice se il NAS si raggiunge; il come dietro la «i». Poi l'impronta di casa e
 * la VPN: ogni riga dice il suo stato con un segno e un valore corto a destra, la VPN di casa e' un interruttore.
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
      SettingsStatusHeader(
        icon = hero.icon,
        family = GalleryIconFamily.Network,
        value = stringResource(hero.value),
        status = placeStatus(state),
        label = if (state.snapshot.home is HomeMatch.Weak) stringResource(R.string.match_weak) else null,
      )
    }
    item { SettingsInfo(label = stringResource(R.string.network_route_info), text = stringResource(hero.detail(state))) }

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

    item { GallerySectionHeader(title = stringResource(R.string.network_section_home)) }
    item {
      FluidListGroup {
        val fingerprint = state.fingerprint
        val fingerprintTitle = stringResource(R.string.network_fingerprint)
        val fingerprintValue = stringResource(if (fingerprint == null) R.string.network_fingerprint_missing else R.string.network_fingerprint_saved)
        val fingerprintSpoken = if (fingerprint == null) {
          stringResource(R.string.network_fingerprint_none)
        } else {
          stringResource(
            R.string.network_fingerprint_detail,
            pluralStringResource(R.plurals.network_ula_count, fingerprint.ulaPrefixes.size, fingerprint.ulaPrefixes.size),
            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(fingerprint.capturedAt)),
          )
        }
        FluidListRow(
          title = fingerprintTitle,
          subtitle = "",
          tone = if (fingerprint == null) FluidTone.Warning else FluidTone.Primary,
          leading = {
            RowTile(
              Icons.Rounded.Fingerprint,
              status = if (fingerprint == null) GalleryIconStatus.Attention else GalleryIconStatus.Done,
              family = GalleryIconFamily.Network,
            )
          },
          leadingTile = false,
          disclosure = false,
          badge = { RowValue(fingerprintValue) },
          modifier = Modifier.semantics { contentDescription = "$fingerprintTitle, $fingerprintValue. $fingerprintSpoken" },
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.network_ipv6),
          subtitle = "",
          tone = FluidTone.Neutral,
          leading = { RowTile(Icons.Rounded.Public) },
          leadingTile = false,
          disclosure = false,
          badge = { RowValue(stringResource(if (state.snapshot.ipv6Global) R.string.value_yes else R.string.value_no)) },
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.network_recapture),
          subtitle = "",
          tone = FluidTone.Info,
          leading = { RowTile(Icons.Rounded.Sync) },
          leadingTile = false,
          onClick = actions.onCapture,
        )
      }
    }
    item {
      SettingsInfo(
        label = stringResource(R.string.network_home_info),
        text = stringResource(R.string.network_section_home_detail) + " " +
          stringResource(if (state.admin) R.string.network_recapture_detail_admin else R.string.network_recapture_detail) + ".",
      )
    }
    item { CaptureStatus(state.capture, actions) }

    item { GallerySectionHeader(title = stringResource(R.string.network_section_vpn)) }
    item {
      FluidListGroup {
        val ready = state.vpnCapability == VpnCapability.READY
        val wgTitle = stringResource(R.string.network_wg)
        val wgValue = stringResource(state.vpnCapability.valueRes())
        FluidListRow(
          title = wgTitle,
          subtitle = "",
          tone = if (ready) FluidTone.Success else FluidTone.Warning,
          leading = { RowTile(Icons.Rounded.VpnKey, status = if (ready) GalleryIconStatus.Done else GalleryIconStatus.Attention) },
          leadingTile = false,
          disclosure = false,
          badge = { RowValue(wgValue) },
          modifier = Modifier.semantics { contentDescription = "$wgTitle, $wgValue" },
        )
        FluidListDivider()
        val autoTitle = stringResource(R.string.network_automation)
        val autoValue = stringResource(state.automation.valueRes())
        val autoSpoken = automationText(state.automation)
        val suspended = state.automation is VpnAutomationStatus.Suspended
        FluidListRow(
          title = autoTitle,
          subtitle = "",
          tone = when {
            suspended -> FluidTone.Warning
            state.automation == VpnAutomationStatus.Off -> FluidTone.Neutral
            else -> FluidTone.Info
          },
          leading = { RowTile(Icons.Rounded.Tune, status = if (suspended) GalleryIconStatus.Attention else null, family = GalleryIconFamily.Network) },
          leadingTile = false,
          disclosure = false,
          badge = { RowValue(autoValue) },
          modifier = Modifier.semantics { contentDescription = "$autoTitle, $autoValue. $autoSpoken" },
        )
        FluidListDivider()
        VpnSwitchRow(state = state, onTunnel = actions.onTunnel)
      }
    }

    item { SettingsInfo(label = stringResource(R.string.network_vpn_info), text = stringResource(state.vpnCapability.detailRes())) }

    if (state.vpnCapability == VpnCapability.PERMISSION_MISSING) {
      item { ControlPermissionBlock(state.permission, actions) }
    }
    if (state.automation is VpnAutomationStatus.Suspended) {
      item {
        Note(
          title = stringResource(R.string.network_reactivate_title),
          message = stringResource(R.string.network_reactivate_message),
          tone = FluidTone.Warning,
        )
      }
      item {
        FluidButton(
          text = stringResource(R.string.network_reactivate),
          onClick = actions.onReactivate,
          style = FluidButtonStyle.Filled,
          size = FluidButtonSize.Large,
          fillWidth = true,
          leading = { Icon(Icons.Rounded.Sync, contentDescription = null) },
        )
      }
    }

    item { CommandStatus(state.command) }

    if (state.canOpenWireGuard) {
      item {
        FluidButton(
          text = stringResource(R.string.network_open_wg),
          onClick = actions.onOpenWireGuard,
          style = if (state.vpnCapability == VpnCapability.READY || state.vpnCapability == VpnCapability.PERMISSION_MISSING) {
            FluidButtonStyle.Plain
          } else {
            FluidButtonStyle.Filled
          },
          size = FluidButtonSize.Large,
          fillWidth = true,
          leading = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null) },
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
          leading = { Icon(Icons.Rounded.Tune, contentDescription = null) },
        )
      }
    }
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

/**
 * La VPN di casa come un interruttore (CU-5: prima due tasti «Accendi» e «Spegni» uno sotto l'altro). Acceso = il tunnel di casa e' su; il tocco
 * prova a cambiarlo (lo stesso comando dei tasti di prima). Si tocca solo con WireGuard pronto e nessun comando in corso; un'altra VPN accesa e' un
 * problema (segno rosso) e lo dice il valore.
 */
@Composable
private fun VpnSwitchRow(state: NetworkUiState, onTunnel: (Boolean) -> Unit) {
  val title = stringResource(R.string.network_vpn_home)
  val on = state.snapshot.vpn == VpnObservation.HOME_TUNNEL
  val foreign = state.snapshot.vpn == VpnObservation.FOREIGN_VPN
  val usable = state.vpnCapability == VpnCapability.READY && state.command !is VpnCommandUi.Running && !foreign
  val value = stringResource(state.snapshot.vpn.valueRes())
  FluidListRow(
    title = title,
    subtitle = "",
    tone = when {
      foreign -> FluidTone.Danger
      on -> FluidTone.Primary
      else -> FluidTone.Neutral
    },
    leading = { RowTile(Icons.Rounded.VpnLock, status = if (foreign) GalleryIconStatus.Problem else null) },
    leadingTile = false,
    disclosure = false,
    badge = { if (usable) FluidSwitch(checked = on, onCheckedChange = null) else RowValue(value) },
    onClick = if (usable) ({ onTunnel(!on) }) else null,
    modifier = Modifier.semantics(mergeDescendants = true) {
      contentDescription = "$title, $value"
      if (usable) {
        role = Role.Switch
        toggleableState = ToggleableState(on)
      }
    },
  )
}

/** Il segno della testata: il NAS si raggiunge (spunta), no (attenzione), una rete o una VPN estranea (problema), senza rete (fermo). */
private fun placeStatus(state: NetworkUiState): GalleryIconStatus = when {
  state.snapshot.vpn == VpnObservation.FOREIGN_VPN || state.snapshot.place == NetPlace.UNTRUSTED -> GalleryIconStatus.Problem
  state.snapshot.place == NetPlace.OFFLINE -> GalleryIconStatus.Stopped
  state.route is RouteUi.Allowed -> GalleryIconStatus.Done
  else -> GalleryIconStatus.Attention
}

private fun VpnCapability.valueRes(): Int = when (this) {
  VpnCapability.READY -> R.string.wg_value_ready
  VpnCapability.WIREGUARD_MISSING -> R.string.wg_value_missing
  VpnCapability.PERMISSION_MISSING -> R.string.wg_value_permission
  VpnCapability.NOT_CONFIGURED -> R.string.wg_pill_not_configured
}

private fun VpnAutomationStatus.valueRes(): Int = when (this) {
  VpnAutomationStatus.Off -> R.string.auto_value_off
  VpnAutomationStatus.Idle, is VpnAutomationStatus.Done -> R.string.auto_value_on
  is VpnAutomationStatus.WaitingForIdleSession, is VpnAutomationStatus.Commanding -> R.string.auto_value_busy
  is VpnAutomationStatus.Suspended -> R.string.auto_value_suspended
}

private fun VpnObservation.valueRes(): Int = when (this) {
  VpnObservation.NONE -> R.string.vpn_value_off
  VpnObservation.HOME_TUNNEL -> R.string.vpn_value_on
  VpnObservation.FOREIGN_VPN -> R.string.vpn_value_foreign
  VpnObservation.UNKNOWN -> R.string.vpn_value_unknown
}

private val IPV6_MATTERS = setOf(NetPlace.AWAY_VPN_WIFI, NetPlace.AWAY_VPN_MOBILE, NetPlace.VPN_OFF)

/** L'apertura della pagina: come ci si chiama in questa rete e cosa succede al NAS. */
private class PlaceHero(
  val tone: FluidHeroTone,
  val icon: ImageVector,
  val value: Int,
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
    return PlaceHero(FluidHeroTone.Alert, Icons.Rounded.VpnLock, R.string.place_foreign_value, detail)
  }
  return when (state.snapshot.place) {
    NetPlace.HOME -> PlaceHero(FluidHeroTone.PrimaryToSecondary, Icons.Rounded.Home, R.string.place_home_value, detail)
    NetPlace.AWAY_VPN_WIFI -> PlaceHero(FluidHeroTone.Secondary, Icons.Rounded.Wifi, R.string.place_away_value, detail)
    NetPlace.AWAY_VPN_MOBILE -> PlaceHero(FluidHeroTone.Secondary, Icons.Rounded.CellTower, R.string.place_away_value, detail)
    NetPlace.ROAMING -> PlaceHero(FluidHeroTone.Tertiary, Icons.Rounded.Language, R.string.place_roaming_value, detail)
    NetPlace.VPN_OFF -> PlaceHero(FluidHeroTone.Tertiary, Icons.Rounded.VpnLock, R.string.place_vpn_off_value, detail)
    NetPlace.UNTRUSTED -> PlaceHero(FluidHeroTone.Alert, Icons.Rounded.Lan, R.string.place_untrusted_value, detail)
    NetPlace.OFFLINE -> PlaceHero(FluidHeroTone.Secondary, Icons.Rounded.SignalWifiOff, R.string.place_offline_value, detail)
  }
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

/**
 * Manca il permesso di comandare WireGuard (RE-1): la frase che dice cosa si sta per chiedere, il tasto che apre il dialogo di Android,
 * e dopo un rifiuto il modo di riprovare (il dialogo, o la scheda dell'app quando Android non lo mostra piu').
 */
@Composable
private fun ControlPermissionBlock(permission: PermissionUi, actions: NetworkActions) {
  val (title, message, tone) = when (permission) {
    PermissionUi.IDLE -> Triple(R.string.wg_grant_title, R.string.wg_grant_message, FluidTone.Info)
    PermissionUi.DENIED -> Triple(R.string.wg_grant_denied_title, R.string.wg_grant_denied, FluidTone.Warning)
    PermissionUi.DENIED_FOR_GOOD -> Triple(R.string.wg_grant_blocked_title, R.string.wg_grant_blocked, FluidTone.Warning)
  }
  Note(title = stringResource(title), message = stringResource(message), tone = tone)
  FluidButton(
    text = stringResource(
      when (permission) {
        PermissionUi.IDLE -> R.string.wg_grant
        PermissionUi.DENIED -> R.string.wg_grant_retry
        PermissionUi.DENIED_FOR_GOOD -> R.string.wg_open_app_settings
      },
    ),
    onClick = if (permission == PermissionUi.DENIED_FOR_GOOD) actions.onOpenAppSettings else actions.onGrantControl,
    style = FluidButtonStyle.Filled,
    size = FluidButtonSize.Large,
    fillWidth = true,
  )
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
      if (command.reactivated) {
        Note(title = stringResource(R.string.cmd_reactivated_title), message = stringResource(R.string.cmd_reactivated), tone = FluidTone.Success)
      }
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
    is CaptureUi.Saved -> {
      Note(
        title = stringResource(R.string.capture_saved_title),
        message = stringResource(
          when (capture.scope) {
            SaveScope.FAMILY -> R.string.capture_saved_family
            SaveScope.THIS_PHONE -> R.string.capture_saved_phone
            SaveScope.FAMILY_FAILED -> R.string.capture_saved_failed
          },
        ),
        tone = if (capture.scope == SaveScope.FAMILY_FAILED) FluidTone.Warning else FluidTone.Success,
      )
      if (capture.scope == SaveScope.FAMILY_FAILED) {
        FluidButton(
          text = stringResource(R.string.capture_retry),
          onClick = actions.onRetryShare,
          style = FluidButtonStyle.Tinted,
          size = FluidButtonSize.Large,
          fillWidth = true,
        )
      }
    }
  }
}
