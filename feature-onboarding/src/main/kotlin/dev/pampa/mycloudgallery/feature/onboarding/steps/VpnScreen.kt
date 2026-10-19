package dev.pampa.mycloudgallery.feature.onboarding.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidSpinner
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.network.contract.ControlPermissionOutcome
import dev.pampa.mycloudgallery.core.network.contract.VpnCapability
import dev.pampa.mycloudgallery.core.network.contract.VpnCommandResult
import dev.pampa.mycloudgallery.feature.onboarding.R
import dev.pampa.mycloudgallery.feature.onboarding.model.VpnTestUi
import dev.pampa.mycloudgallery.feature.onboarding.model.VpnUi
import dev.pampa.mycloudgallery.feature.onboarding.ui.CardButton
import dev.pampa.mycloudgallery.feature.onboarding.ui.DockAction
import dev.pampa.mycloudgallery.feature.onboarding.ui.NoticeCard
import dev.pampa.mycloudgallery.feature.onboarding.ui.NoticeTone
import dev.pampa.mycloudgallery.feature.onboarding.ui.OnboardingDimens
import dev.pampa.mycloudgallery.feature.onboarding.ui.ProblemAction
import dev.pampa.mycloudgallery.feature.onboarding.ui.ProblemPanel
import dev.pampa.mycloudgallery.feature.onboarding.ui.StepChrome
import dev.pampa.mycloudgallery.feature.onboarding.ui.StepScaffold
import dev.pampa.mycloudgallery.feature.onboarding.ui.ToggleRow
import dev.pampa.mycloudgallery.feature.onboarding.ui.stepAmbient

@Stable
internal class VpnForm(remoteControl: Boolean = false, tunnelName: String = "") {
  var remoteControl: Boolean by mutableStateOf(remoteControl)
  var tunnelName: String by mutableStateOf(tunnelName)
}

internal class VpnActions(
  /** "Consenti il controllo": il dialogo di sistema del permesso di WireGuard (RE-1). */
  val onGrantControl: () -> Unit = {},
  /** Dopo un rifiuto definitivo: la scheda dell'app in Android. */
  val onOpenAppSettings: () -> Unit = {},
  val onOpenWireGuard: () -> Unit = {},
  val onRunTest: (tunnelName: String) -> Unit = {},
  val onContinue: (remoteControl: Boolean, tunnelName: String?) -> Unit = { _, _ -> },
  val onSkip: () -> Unit = {},
  val onProblemAction: (ProblemAction) -> Unit = {},
)

/**
 * Passo 11: la VPN WireGuard (§2.1, E-S15, E-R3, E-R11, E-P20).
 *
 * E' una guida, non un modulo: dice cosa fare sul FRITZ!Box (accesso per questo telefono, IPv6 per il CGNAT,
 * indirizzi consentiti) e **che il QR di WireGuard e' una password**, che non si fotografa e non si manda
 * (E-S15). In fondo, facoltativo, il controllo automatico: l'app accende la VPN fuori casa e la spegne a casa,
 * con la verifica osservata di accensione e spegnimento (mai "accesa" solo perche' il comando e' partito).
 * Il passo si puo' saltare.
 */
@Composable
internal fun VpnScreen(
  chrome: StepChrome,
  ui: VpnUi,
  form: VpnForm,
  actions: VpnActions,
  modifier: Modifier = Modifier,
) {
  StepScaffold(
    title = stringResource(R.string.vpn_title),
    subtitle = stringResource(R.string.vpn_subtitle),
    chrome = chrome,
    ambient = stepAmbient(OnboardingStep.VPN),
    modifier = modifier,
    primary = DockAction(
      label = stringResource(R.string.action_continue),
      onClick = { actions.onContinue(form.remoteControl, form.tunnelName.trim().ifBlank { null }) },
      enabled = !ui.submitting,
      loading = ui.submitting,
    ),
    secondary = DockAction(
      label = stringResource(R.string.action_skip_step),
      onClick = actions.onSkip,
      enabled = !ui.submitting,
      style = FluidButtonStyle.Plain,
    ),
  ) {
    item(key = "why") {
      NoticeCard(
        tone = NoticeTone.Info,
        icon = Icons.Rounded.Home,
        title = stringResource(R.string.vpn_why_title),
        body = stringResource(R.string.vpn_why_detail),
      )
    }
    item(key = "guide-header") { FluidSectionHeader(title = stringResource(R.string.vpn_guide_section)) }
    item(key = "guide") {
      FluidListGroup {
        GuideRow(Icons.Rounded.Router, R.string.vpn_guide_peer, R.string.vpn_guide_peer_detail)
        FluidListDivider()
        GuideRow(Icons.Rounded.Dns, R.string.vpn_guide_ipv6, R.string.vpn_guide_ipv6_detail)
        FluidListDivider()
        GuideRow(Icons.Rounded.VpnKey, R.string.vpn_guide_allowed, R.string.vpn_guide_allowed_detail)
        FluidListDivider()
        GuideRow(Icons.Rounded.QrCode2, R.string.vpn_guide_import, R.string.vpn_guide_import_detail)
      }
    }
    item(key = "qr-secret") {
      NoticeCard(
        tone = NoticeTone.Caution,
        icon = Icons.Rounded.Warning,
        title = stringResource(R.string.vpn_qr_secret_title),
        body = stringResource(R.string.vpn_qr_secret_detail),
      ) {
        CardButton(text = stringResource(R.string.action_open_wireguard), onClick = actions.onOpenWireGuard, primary = false)
      }
    }
    item(key = "control-header") {
      FluidSectionHeader(
        title = stringResource(R.string.vpn_control_section),
        detail = stringResource(R.string.vpn_control_section_detail),
      )
    }
    item(key = "control") {
      FluidListGroup {
        ToggleRow(
          title = stringResource(R.string.vpn_control_toggle),
          subtitle = stringResource(R.string.vpn_control_toggle_detail),
          icon = Icons.Rounded.SettingsRemote,
          checked = form.remoteControl,
          onCheckedChange = { form.remoteControl = it },
        )
      }
    }
    if (form.remoteControl) {
      item(key = "capability") { CapabilityBlock(ui = ui, form = form, actions = actions) }
    }
    ui.problem?.let { problem ->
      item(key = "problem") { ProblemPanel(problem = problem, onAction = actions.onProblemAction) }
    }
    item(key = "footnote") { FluidSectionFootnote(text = stringResource(R.string.vpn_footnote)) }
  }
}

@Composable
private fun GuideRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: Int, detail: Int) {
  FluidListRow(
    title = stringResource(title),
    subtitle = stringResource(detail),
    tone = FluidTone.Info,
    leading = { Icon(icon, contentDescription = null) },
    disclosure = false,
  )
}

@Composable
private fun CapabilityBlock(ui: VpnUi, form: VpnForm, actions: VpnActions) {
  Column(verticalArrangement = Arrangement.spacedBy(OnboardingDimens.CardGap)) {
    when (ui.capability) {
      null -> FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.vpn_cap_checking),
          subtitle = "",
          tone = FluidTone.Neutral,
          leading = { Icon(Icons.Rounded.Info, contentDescription = null) },
          badge = { FluidSpinner() },
          disclosure = false,
        )
      }
      VpnCapability.WIREGUARD_MISSING -> NoticeCard(
        tone = NoticeTone.Caution,
        icon = Icons.Rounded.Warning,
        title = stringResource(R.string.vpn_cap_missing_title),
        body = stringResource(R.string.vpn_cap_missing_detail),
      )
      VpnCapability.PERMISSION_MISSING -> NoticeCard(
        tone = NoticeTone.Caution,
        icon = Icons.Rounded.Warning,
        title = stringResource(
          when (ui.permission) {
            null -> R.string.vpn_cap_permission_title
            ControlPermissionOutcome.DENIED -> R.string.vpn_cap_denied_title
            else -> R.string.vpn_cap_blocked_title
          },
        ),
        body = stringResource(
          when (ui.permission) {
            null -> R.string.vpn_cap_permission_detail
            ControlPermissionOutcome.DENIED -> R.string.vpn_cap_denied_detail
            else -> R.string.vpn_cap_blocked_detail
          },
        ),
      ) {
        // Il permesso si chiede con il dialogo di sistema (RE-1); dopo un rifiuto si puo' riprovare, o aprire la scheda dell'app
        // quando Android non lo mostra piu'. "Apri WireGuard" resta: serve anche «Consenti il controllo da altre app».
        if (ui.permission == ControlPermissionOutcome.DENIED_FOR_GOOD) {
          CardButton(text = stringResource(R.string.vpn_open_app_settings), onClick = actions.onOpenAppSettings, primary = true)
        } else {
          CardButton(
            text = stringResource(if (ui.permission == null) R.string.vpn_grant_control else R.string.vpn_grant_control_retry),
            onClick = actions.onGrantControl,
            primary = true,
          )
        }
        CardButton(text = stringResource(R.string.action_open_wireguard), onClick = actions.onOpenWireGuard, primary = false)
      }
      VpnCapability.NOT_CONFIGURED, VpnCapability.READY -> Unit
    }
    if (ui.capability == VpnCapability.NOT_CONFIGURED || ui.capability == VpnCapability.READY) {
      FluidTextField(
        value = form.tunnelName,
        onValueChange = { form.tunnelName = it },
        label = stringResource(R.string.vpn_tunnel_label),
        placeholder = stringResource(R.string.vpn_tunnel_placeholder),
        supportingText = stringResource(R.string.vpn_tunnel_hint),
        keyboardOptions = KeyboardOptions(
          capitalization = KeyboardCapitalization.None,
          autoCorrectEnabled = false,
          imeAction = ImeAction.Done,
        ),
      )
      CardButton(
        text = stringResource(if (ui.test is VpnTestUi.Running) R.string.vpn_test_running else R.string.vpn_test_run),
        onClick = { actions.onRunTest(form.tunnelName.trim()) },
        primary = ui.capability == VpnCapability.READY,
        enabled = form.tunnelName.isNotBlank() && ui.test !is VpnTestUi.Running,
        loading = ui.test is VpnTestUi.Running,
      )
      TestResult(ui.test, onOpenWireGuard = actions.onOpenWireGuard)
    }
  }
}

@Composable
private fun TestResult(test: VpnTestUi, onOpenWireGuard: () -> Unit) {
  when (test) {
    VpnTestUi.Idle -> Unit
    is VpnTestUi.Running -> NoticeCard(
      tone = NoticeTone.Info,
      icon = Icons.Rounded.VpnKey,
      title = stringResource(if (test.up) R.string.vpn_test_waiting_up else R.string.vpn_test_waiting_down),
      body = stringResource(R.string.vpn_test_waiting_detail),
    )
    is VpnTestUi.Done -> {
      val upLine = test.up.describe(up = true)
      val downLine = test.down?.describe(up = false)
      val ok = test.up is VpnCommandResult.Confirmed && test.down is VpnCommandResult.Confirmed
      NoticeCard(
        tone = if (ok) NoticeTone.Success else NoticeTone.Caution,
        icon = if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
        title = stringResource(if (ok) R.string.vpn_test_ok_title else R.string.vpn_test_issue_title),
        body = listOfNotNull(upLine, downLine).joinToString("\n"),
      ) {
        if (!ok) {
          CardButton(text = stringResource(R.string.action_open_wireguard), onClick = onOpenWireGuard, primary = false)
        }
      }
    }
  }
}

/** L'esito osservato di un comando, in parole: mai "acceso" solo perche' il comando e' partito. */
@Composable
internal fun VpnCommandResult.describe(up: Boolean): String = stringResource(
  when (this) {
    VpnCommandResult.Confirmed -> if (up) R.string.vpn_result_up_confirmed else R.string.vpn_result_down_confirmed
    VpnCommandResult.NotObserved -> R.string.vpn_result_not_observed
    VpnCommandResult.LostConnectivity -> R.string.vpn_result_lost_connectivity
    is VpnCommandResult.Skipped -> R.string.vpn_result_skipped
  },
)
