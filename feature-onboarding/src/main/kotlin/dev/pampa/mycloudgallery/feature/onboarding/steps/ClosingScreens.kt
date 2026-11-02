package dev.pampa.mycloudgallery.feature.onboarding.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProgress
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.feature.onboarding.R
import dev.pampa.mycloudgallery.feature.onboarding.model.LocalStepUi
import dev.pampa.mycloudgallery.feature.onboarding.model.SummaryUi
import dev.pampa.mycloudgallery.feature.onboarding.titleRes
import dev.pampa.mycloudgallery.feature.onboarding.ui.DockAction
import dev.pampa.mycloudgallery.feature.onboarding.ui.NoticeCard
import dev.pampa.mycloudgallery.feature.onboarding.ui.NoticeTone
import dev.pampa.mycloudgallery.feature.onboarding.ui.OnboardingDimens
import dev.pampa.mycloudgallery.feature.onboarding.ui.ProblemAction
import dev.pampa.mycloudgallery.feature.onboarding.ui.ProblemPanel
import dev.pampa.mycloudgallery.feature.onboarding.ui.StepChrome
import dev.pampa.mycloudgallery.feature.onboarding.ui.StepScaffold
import dev.pampa.mycloudgallery.feature.onboarding.ui.SuccessMark
import dev.pampa.mycloudgallery.feature.onboarding.ui.ToggleRow
import dev.pampa.mycloudgallery.feature.onboarding.ui.stepAmbient

// I passi 13 e 14: l'avviso sul backup esterno e sul FRITZ!Box, e il riepilogo.

// --- Passo 13: backup esterno e FRITZ!Box -----------------------------------------------------------

/** Le cinque verifiche del FRITZ!Box di `docs/sicurezza.md` (§0.1 e §9). */
internal enum class RouterCheck { NO_PORT_FORWARDING, REMOTE_ACCESS_OFF, DHCP_RESERVATION, GUEST_NETWORK, WIFI_SECURITY }

@Stable
internal class SecurityNoticeForm(externalBackup: Boolean = false, router: Set<RouterCheck> = emptySet()) {
  var externalBackup: Boolean by mutableStateOf(externalBackup)
  var router: Set<RouterCheck> by mutableStateOf(router)

  /** La checklist e' "fatta" solo con tutte e cinque le voci. */
  val routerDone: Boolean get() = router.size == RouterCheck.entries.size

  fun toggle(check: RouterCheck, on: Boolean) {
    router = if (on) router + check else router - check
  }
}

private fun RouterCheck.icon(): ImageVector = when (this) {
  RouterCheck.NO_PORT_FORWARDING -> Icons.Rounded.Block
  RouterCheck.REMOTE_ACCESS_OFF -> Icons.Rounded.Public
  RouterCheck.DHCP_RESERVATION -> Icons.Rounded.Dns
  RouterCheck.GUEST_NETWORK -> Icons.Rounded.Wifi
  RouterCheck.WIFI_SECURITY -> Icons.Rounded.Lock
}

private fun RouterCheck.title(): Int = when (this) {
  RouterCheck.NO_PORT_FORWARDING -> R.string.notice_router_ports
  RouterCheck.REMOTE_ACCESS_OFF -> R.string.notice_router_remote
  RouterCheck.DHCP_RESERVATION -> R.string.notice_router_dhcp
  RouterCheck.GUEST_NETWORK -> R.string.notice_router_guest
  RouterCheck.WIFI_SECURITY -> R.string.notice_router_wifi
}

private fun RouterCheck.detail(): Int = when (this) {
  RouterCheck.NO_PORT_FORWARDING -> R.string.notice_router_ports_detail
  RouterCheck.REMOTE_ACCESS_OFF -> R.string.notice_router_remote_detail
  RouterCheck.DHCP_RESERVATION -> R.string.notice_router_dhcp_detail
  RouterCheck.GUEST_NETWORK -> R.string.notice_router_guest_detail
  RouterCheck.WIFI_SECURITY -> R.string.notice_router_wifi_detail
}

/**
 * Passo 13: il backup esterno e il FRITZ!Box (§2.1, C13).
 *
 * Due cose che l'app non puo' fare al posto dell'utente. Il RAID 1 del NAS non e' un backup (se cancelli un file
 * sparisce da entrambi i dischi): senza una copia esterna l'avviso resta in Impostazioni. E cinque controlli sul
 * FRITZ!Box, da spuntare come "fatto" o lasciare per "dopo". Niente e' obbligatorio e niente blocca: quello che
 * resta "dopo" si ritrova nelle Impostazioni.
 */
@Composable
internal fun SecurityNoticeScreen(
  chrome: StepChrome,
  ui: LocalStepUi,
  form: SecurityNoticeForm,
  onSubmit: (externalBackupDone: Boolean, routerChecklistDone: Boolean) -> Unit,
  onProblemAction: (ProblemAction) -> Unit,
  modifier: Modifier = Modifier,
) {
  StepScaffold(
    title = stringResource(R.string.notice_title),
    subtitle = stringResource(R.string.notice_subtitle),
    chrome = chrome,
    ambient = stepAmbient(OnboardingStep.SECURITY_NOTICE),
    modifier = modifier,
    primary = DockAction(
      label = stringResource(R.string.action_continue),
      onClick = { onSubmit(form.externalBackup, form.routerDone) },
      enabled = !ui.submitting,
      loading = ui.submitting,
    ),
  ) {
    item(key = "raid") {
      NoticeCard(
        tone = NoticeTone.Caution,
        icon = Icons.Rounded.GppMaybe,
        title = stringResource(R.string.notice_raid_title),
        body = stringResource(R.string.notice_raid_detail),
      )
    }
    item(key = "backup-header") { GallerySectionHeader(title = stringResource(R.string.notice_backup_section)) }
    item(key = "backup") {
      FluidListGroup {
        ToggleRow(
          title = stringResource(R.string.notice_backup_done),
          subtitle = stringResource(R.string.notice_backup_done_detail),
          icon = Icons.Rounded.Storage,
          checked = form.externalBackup,
          onCheckedChange = { form.externalBackup = it },
        )
      }
    }
    item(key = "router-header") {
      GallerySectionHeader(
        title = stringResource(R.string.notice_router_section),
        detail = stringResource(R.string.notice_router_section_detail),
      )
    }
    item(key = "router") {
      FluidListGroup {
        RouterCheck.entries.forEachIndexed { index, check ->
          if (index > 0) FluidListDivider()
          ToggleRow(
            title = stringResource(check.title()),
            subtitle = stringResource(check.detail()),
            icon = check.icon(),
            checked = check in form.router,
            onCheckedChange = { form.toggle(check, it) },
          )
        }
      }
    }
    ui.problem?.let { problem ->
      item(key = "problem") { ProblemPanel(problem = problem, onAction = onProblemAction) }
    }
    item(key = "footnote") { FluidSectionFootnote(text = stringResource(R.string.notice_footnote)) }
  }
}

// --- Passo 14: riepilogo ----------------------------------------------------------------------------

/** I passi che il riepilogo elenca come "fatti": quelli completati, senza il benvenuto e il riepilogo stesso. */
internal fun OnboardingProgress.doneSteps(): List<OnboardingStep> =
  plan.filter { it in completed && it != OnboardingStep.WELCOME && it != OnboardingStep.SUMMARY }

/**
 * Passo 14: il riepilogo (§2.1). Cosa e' fatto, cosa resta ("Da completare" nelle Impostazioni) e quanto ci
 * si e' messi: l'obiettivo e' sotto i cinque minuti.
 */
@Composable
internal fun SummaryScreen(
  chrome: StepChrome,
  ui: SummaryUi,
  progress: OnboardingProgress,
  elapsedSeconds: Long?,
  onFinish: () -> Unit,
  onProblemAction: (ProblemAction) -> Unit,
  modifier: Modifier = Modifier,
  onResume: (OnboardingStep) -> Unit = {},
) {
  val done = progress.doneSteps()
  val pending = progress.pending
  // E-O1: con "Continua piu' tardi" i passi del NAS (non saltabili) restano da fare. Finche' ci sono, il tasto li
  // riprende (dal primo) invece di provare a finire: "Vai alla Libreria" non si puo' ancora.
  val essentialPending = pending.firstOrNull { !it.skippable }
  StepScaffold(
    title = stringResource(R.string.summary_title),
    subtitle = stringResource(if (essentialPending != null) R.string.summary_subtitle_nas_pending else R.string.summary_subtitle),
    chrome = chrome,
    ambient = stepAmbient(OnboardingStep.SUMMARY),
    modifier = modifier,
    primary = if (essentialPending != null) {
      DockAction(
        label = stringResource(R.string.summary_resume_nas),
        onClick = { onResume(essentialPending) },
        enabled = !ui.submitting,
      )
    } else {
      DockAction(
        label = stringResource(R.string.summary_finish),
        onClick = onFinish,
        enabled = !ui.submitting,
        loading = ui.submitting,
      )
    },
  ) {
    item(key = "hero") {
      Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = OnboardingDimens.CardGap),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(OnboardingDimens.CardGap),
      ) {
        SuccessMark()
        Text(
          text = stringResource(R.string.summary_ready),
          style = MaterialTheme.typography.headlineSmall,
          color = MaterialTheme.colorScheme.onSurface,
          textAlign = TextAlign.Center,
        )
        if (elapsedSeconds != null) {
          Text(
            text = elapsedLabel(elapsedSeconds),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
          )
        }
      }
    }
    if (done.isNotEmpty()) {
      item(key = "done-header") { GallerySectionHeader(title = stringResource(R.string.summary_section_done)) }
      item(key = "done") {
        FluidListGroup {
          done.forEachIndexed { index, step ->
            if (index > 0) FluidListDivider()
            FluidListRow(
              title = stringResource(step.titleRes()),
              subtitle = "",
              tone = FluidTone.Success,
              leading = { Icon(Icons.Rounded.CheckCircle, contentDescription = null) },
              disclosure = false,
            )
          }
        }
      }
    }
    if (pending.isNotEmpty()) {
      item(key = "pending-header") {
        GallerySectionHeader(
          title = stringResource(R.string.summary_section_pending),
          detail = stringResource(R.string.summary_section_pending_detail),
        )
      }
      item(key = "pending") {
        FluidListGroup {
          pending.forEachIndexed { index, step ->
            if (index > 0) FluidListDivider()
            FluidListRow(
              title = stringResource(step.titleRes()),
              subtitle = stringResource(R.string.summary_pending_row),
              tone = FluidTone.Warning,
              leading = { Icon(Icons.Rounded.Schedule, contentDescription = null) },
              disclosure = false,
            )
          }
        }
      }
    }
    ui.problem?.let { problem ->
      item(key = "problem") { ProblemPanel(problem = problem, onAction = onProblemAction) }
    }
    item(key = "footnote") { FluidSectionFootnote(text = stringResource(R.string.summary_footnote)) }
  }
}

@Composable
private fun elapsedLabel(seconds: Long): String {
  val minutes = seconds / SECONDS_PER_MINUTE
  val rest = seconds % SECONDS_PER_MINUTE
  return if (minutes == 0L) {
    stringResource(R.string.summary_elapsed_seconds, rest)
  } else {
    stringResource(R.string.summary_elapsed, minutes, rest)
  }
}

private const val SECONDS_PER_MINUTE = 60L
