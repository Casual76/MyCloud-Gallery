package dev.pampa.mycloudgallery.feature.onboarding.steps

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidStatusBadge
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.security.device.ScreenLockStatus
import dev.pampa.mycloudgallery.feature.onboarding.R
import dev.pampa.mycloudgallery.feature.onboarding.model.LocalStepUi
import dev.pampa.mycloudgallery.feature.onboarding.platform.PermissionKind
import dev.pampa.mycloudgallery.feature.onboarding.platform.PermissionState
import dev.pampa.mycloudgallery.feature.onboarding.platform.PermissionsSnapshot
import dev.pampa.mycloudgallery.feature.onboarding.ui.CardButton
import dev.pampa.mycloudgallery.feature.onboarding.ui.DockAction
import dev.pampa.mycloudgallery.feature.onboarding.ui.NoticeCard
import dev.pampa.mycloudgallery.feature.onboarding.ui.NoticeTone
import dev.pampa.mycloudgallery.feature.onboarding.ui.ProblemAction
import dev.pampa.mycloudgallery.feature.onboarding.ui.ProblemPanel
import dev.pampa.mycloudgallery.feature.onboarding.ui.StepChrome
import dev.pampa.mycloudgallery.feature.onboarding.ui.StepScaffold
import dev.pampa.mycloudgallery.feature.onboarding.ui.stepAmbient

internal class PermissionsActions(
  val onRequest: (PermissionKind) -> Unit = {},
  val onOpenAppSettings: () -> Unit = {},
  val onOpenSecuritySettings: () -> Unit = {},
  val onContinue: () -> Unit = {},
  val onProblemAction: (ProblemAction) -> Unit = {},
)

private fun PermissionKind.icon(): ImageVector = when (this) {
  PermissionKind.NOTIFICATIONS -> Icons.Rounded.Notifications
  PermissionKind.MEDIA -> Icons.Rounded.PhotoLibrary
  PermissionKind.MEDIA_LOCATION -> Icons.Rounded.LocationOn
  PermissionKind.LOCAL_NETWORK -> Icons.Rounded.Lan
}

private fun PermissionKind.title(): Int = when (this) {
  PermissionKind.NOTIFICATIONS -> R.string.permissions_notifications
  PermissionKind.MEDIA -> R.string.permissions_media
  PermissionKind.MEDIA_LOCATION -> R.string.permissions_media_location
  PermissionKind.LOCAL_NETWORK -> R.string.permissions_local_network
}

private fun PermissionKind.detail(): Int = when (this) {
  PermissionKind.NOTIFICATIONS -> R.string.permissions_notifications_detail
  PermissionKind.MEDIA -> R.string.permissions_media_detail
  PermissionKind.MEDIA_LOCATION -> R.string.permissions_media_location_detail
  PermissionKind.LOCAL_NETWORK -> R.string.permissions_local_network_detail
}

/**
 * Passo 9: i permessi di Android (§2.1, E-O4, E-S3).
 *
 * Notifiche, accesso ai media (con la modalita' "foto selezionate" riconosciuta, E-P1), posizione dei media e,
 * dove serve, la rete locale. Ogni riga dice cosa e' e perche', e il suo stato e' riletto da Android a ogni
 * ritorno in questa pagina. Un permesso negato non ferma niente: una scheda spiega cosa funziona lo stesso e
 * cosa no (modalita' ridotta). Se il telefono non ha un blocco schermo, una scheda lo dice con il tasto per
 * impostarlo (E-S3).
 */
@Composable
internal fun PermissionsScreen(
  chrome: StepChrome,
  ui: LocalStepUi,
  snapshot: PermissionsSnapshot,
  actions: PermissionsActions,
  modifier: Modifier = Modifier,
) {
  val kinds = PermissionKind.entries.filter {
    // La rete locale compare solo se serve (ramo per il target 37): altrimenti e' rumore.
    it != PermissionKind.LOCAL_NETWORK || snapshot.state(it) == PermissionState.DENIED
  }
  StepScaffold(
    title = stringResource(R.string.permissions_title),
    subtitle = stringResource(R.string.permissions_subtitle),
    chrome = chrome,
    ambient = stepAmbient(OnboardingStep.PERMISSIONS),
    modifier = modifier,
    primary = DockAction(
      label = stringResource(R.string.action_continue),
      onClick = actions.onContinue,
      enabled = !ui.submitting,
      loading = ui.submitting,
    ),
  ) {
    item(key = "header") { GallerySectionHeader(title = stringResource(R.string.permissions_section)) }
    item(key = "list") {
      FluidListGroup {
        kinds.forEachIndexed { index, kind ->
          if (index > 0) FluidListDivider()
          PermissionRow(kind = kind, snapshot = snapshot, onRequest = actions.onRequest, onOpenSettings = actions.onOpenAppSettings)
        }
      }
    }
    if (snapshot.reducedMode) {
      item(key = "reduced") {
        NoticeCard(
          tone = NoticeTone.Caution,
          icon = Icons.Rounded.Tune,
          title = stringResource(R.string.permissions_reduced_title),
          body = stringResource(reducedDetail(snapshot)),
        ) {
          CardButton(
            text = stringResource(R.string.action_open_app_settings),
            onClick = actions.onOpenAppSettings,
            primary = false,
          )
        }
      }
    }
    if (snapshot.screenLock == ScreenLockStatus.NOT_SECURE) {
      item(key = "screen-lock") {
        NoticeCard(
          tone = NoticeTone.Caution,
          icon = Icons.Rounded.Lock,
          title = stringResource(R.string.warning_no_screen_lock_title),
          body = stringResource(R.string.warning_no_screen_lock_detail),
        ) {
          CardButton(
            text = stringResource(R.string.permissions_set_screen_lock),
            onClick = actions.onOpenSecuritySettings,
            primary = true,
          )
        }
      }
    }
    ui.problem?.let { problem ->
      item(key = "problem") { ProblemPanel(problem = problem, onAction = actions.onProblemAction) }
    }
    item(key = "footnote") { FluidSectionFootnote(text = stringResource(R.string.permissions_footnote)) }
  }
}

/** Il testo della modalita' ridotta, in base a cosa manca davvero. */
private fun reducedDetail(snapshot: PermissionsSnapshot): Int = when {
  snapshot.state(PermissionKind.MEDIA) == PermissionState.PARTIAL -> R.string.permissions_reduced_partial
  snapshot.state(PermissionKind.MEDIA) == PermissionState.DENIED -> R.string.permissions_reduced_media
  else -> R.string.permissions_reduced_notifications
}

@Composable
private fun PermissionRow(
  kind: PermissionKind,
  snapshot: PermissionsSnapshot,
  onRequest: (PermissionKind) -> Unit,
  onOpenSettings: () -> Unit,
) {
  val state = snapshot.state(kind)
  val asked = kind in snapshot.requested
  val needsAction = state == PermissionState.DENIED || state == PermissionState.PARTIAL
  val (label, tone) = when (state) {
    PermissionState.GRANTED -> R.string.permissions_state_granted to FluidTone.Success
    PermissionState.PARTIAL -> R.string.permissions_state_partial to FluidTone.Warning
    PermissionState.DENIED ->
      if (asked) R.string.permissions_state_denied to FluidTone.Danger else R.string.permissions_state_todo to FluidTone.Warning
    PermissionState.NOT_NEEDED -> R.string.permissions_state_not_needed to FluidTone.Neutral
  }
  FluidListRow(
    title = stringResource(kind.title()),
    subtitle = stringResource(kind.detail()),
    tone = FluidTone.Primary,
    leading = { Icon(kind.icon(), contentDescription = null) },
    labels = { FluidStatusBadge(label = stringResource(label), tone = tone) },
    onClick = if (needsAction) ({ if (asked && state == PermissionState.DENIED) onOpenSettings() else onRequest(kind) }) else null,
    disclosure = false,
  )
}
