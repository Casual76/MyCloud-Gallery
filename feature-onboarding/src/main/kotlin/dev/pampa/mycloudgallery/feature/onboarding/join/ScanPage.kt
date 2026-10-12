package dev.pampa.mycloudgallery.feature.onboarding.join

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.qr.contract.ScanRejection
import dev.pampa.mycloudgallery.core.qr.contract.ScanVerdict
import dev.pampa.mycloudgallery.feature.onboarding.R
import dev.pampa.mycloudgallery.feature.onboarding.ui.DockAction
import dev.pampa.mycloudgallery.feature.onboarding.ui.NoticeCard
import dev.pampa.mycloudgallery.feature.onboarding.ui.NoticeTone
import dev.pampa.mycloudgallery.feature.onboarding.ui.OnboardingDimens
import dev.pampa.mycloudgallery.feature.onboarding.ui.StepChrome
import dev.pampa.mycloudgallery.feature.onboarding.ui.StepScaffold
import androidx.compose.material3.Icon
import androidx.compose.material.icons.rounded.CameraAlt
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.feature.onboarding.ui.stepAmbient

/** Cosa si sta scansionando: l'invito dell'admin, oppure lo "stato della famiglia" (M7). */
internal enum class ScanMode { Invite, FamilyState }

/**
 * Il riquadro in cui sta lo scanner. E' un parametro delle pagine che lo usano: in produzione e' `QrScanner` di
 * `core-qr` (fotocamera dentro l'app, nessun ML Kit), nei test e negli screenshot un finto, perche' una fotocamera
 * non gira su Robolectric. Riceve il verdetto da dare a ogni testo letto e il ripiego "a mano".
 */
internal typealias ScannerSlot = @Composable (accept: (String) -> ScanVerdict, onCancel: () -> Unit, modifier: Modifier) -> Unit

private fun ScanRejection.icon(): ImageVector = when (this) {
  ScanRejection.NOT_OURS -> Icons.Rounded.QrCodeScanner
  ScanRejection.WIREGUARD_SECRET -> Icons.Rounded.Warning
  ScanRejection.OTHER_FAMILY -> Icons.Rounded.GppBad
  ScanRejection.EXPIRED -> Icons.Rounded.Timer
  ScanRejection.MALFORMED -> Icons.Rounded.Block
}

private fun ScanRejection.title(): Int = when (this) {
  ScanRejection.NOT_OURS -> R.string.scan_reject_not_ours_title
  ScanRejection.WIREGUARD_SECRET -> R.string.scan_reject_wireguard_title
  ScanRejection.OTHER_FAMILY -> R.string.scan_reject_other_title
  ScanRejection.EXPIRED -> R.string.scan_reject_expired_title
  ScanRejection.MALFORMED -> R.string.scan_reject_malformed_title
}

private fun ScanRejection.detail(): Int = when (this) {
  ScanRejection.NOT_OURS -> R.string.scan_reject_not_ours_detail
  ScanRejection.WIREGUARD_SECRET -> R.string.scan_reject_wireguard_detail
  ScanRejection.OTHER_FAMILY -> R.string.scan_reject_other_detail
  ScanRejection.EXPIRED -> R.string.scan_reject_expired_detail
  ScanRejection.MALFORMED -> R.string.scan_reject_malformed_detail
}

/** Il QR di WireGuard e' una password (E-S15): e' l'unico rifiuto tinto da errore invece che da avviso. */
private fun ScanRejection.tone(): NoticeTone =
  if (this == ScanRejection.WIREGUARD_SECRET || this == ScanRejection.OTHER_FAMILY) NoticeTone.Danger else NoticeTone.Caution

/**
 * La pagina della fotocamera ("Entra con il QR", e "stato della famiglia" per chi entra senza QR o recupera
 * l'admin). Sotto il mirino, il motivo dell'ultimo QR rifiutato: ogni motivo ha il suo testo (non e' un QR della
 * famiglia, e' di WireGuard e va trattato come una password, e' di un'altra famiglia, e' scaduto, e' rovinato).
 * Il ripiego "Inserisci a mano" e' sempre a un tocco.
 */
@Composable
internal fun ScanPage(
  chrome: StepChrome,
  mode: ScanMode,
  rejection: ScanRejection?,
  scanner: ScannerSlot,
  onScanned: (String) -> ScanVerdict,
  onManual: (() -> Unit)?,
  onCancel: () -> Unit,
  modifier: Modifier = Modifier,
) {
  StepScaffold(
    title = stringResource(if (mode == ScanMode.Invite) R.string.scan_title else R.string.scan_state_title),
    subtitle = stringResource(if (mode == ScanMode.Invite) R.string.scan_subtitle else R.string.scan_state_subtitle),
    chrome = chrome,
    ambient = stepAmbient(OnboardingStep.NAS_ADDRESS),
    modifier = modifier,
    primary = if (onManual != null) {
      DockAction(
        label = stringResource(R.string.action_enter_manually),
        onClick = onManual,
        style = FluidButtonStyle.Tinted,
      )
    } else {
      DockAction(label = stringResource(R.string.action_cancel), onClick = onCancel, style = FluidButtonStyle.Tinted)
    },
  ) {
    item(key = "scanner") {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(OnboardingDimens.ScannerHeight)
          .clip(ContinuousCornerShape(FluidRadius.Group))
          .background(MaterialTheme.colorScheme.surfaceContainerHigh),
      ) {
        scanner(onScanned, onManual ?: onCancel, Modifier.fillMaxWidth().height(OnboardingDimens.ScannerHeight))
      }
    }
    rejection?.let { reason ->
      item(key = "rejection") {
        NoticeCard(
          tone = reason.tone(),
          icon = reason.icon(),
          title = stringResource(reason.title()),
          body = stringResource(reason.detail()),
        )
      }
    }
    item(key = "tips") {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.scan_tip_title),
          subtitle = stringResource(if (mode == ScanMode.Invite) R.string.scan_tip_invite else R.string.scan_tip_state),
          tone = FluidTone.Info,
          leading = { Icon(Icons.Rounded.CameraAlt, contentDescription = null) },
          disclosure = false,
        )
      }
    }
    item(key = "footnote") {
      FluidSectionFootnote(text = stringResource(R.string.scan_footnote))
    }
  }
}
