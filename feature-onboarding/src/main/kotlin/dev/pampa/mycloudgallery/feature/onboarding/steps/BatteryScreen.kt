package dev.pampa.mycloudgallery.feature.onboarding.steps

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidSpinner
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidStatusBadge
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.rules.platform.health.BackgroundHealth
import dev.pampa.mycloudgallery.core.rules.platform.health.StandbyBucket
import dev.pampa.mycloudgallery.core.rules.platform.health.UnusedAppRestrictions
import dev.pampa.mycloudgallery.feature.onboarding.R
import dev.pampa.mycloudgallery.feature.onboarding.model.BatteryUi
import dev.pampa.mycloudgallery.feature.onboarding.ui.CardButton
import dev.pampa.mycloudgallery.feature.onboarding.ui.DockAction
import dev.pampa.mycloudgallery.feature.onboarding.ui.NoticeCard
import dev.pampa.mycloudgallery.feature.onboarding.ui.NoticeTone
import dev.pampa.mycloudgallery.feature.onboarding.ui.ProblemAction
import dev.pampa.mycloudgallery.feature.onboarding.ui.ProblemPanel
import dev.pampa.mycloudgallery.feature.onboarding.ui.StepChrome
import dev.pampa.mycloudgallery.feature.onboarding.ui.StepScaffold
import dev.pampa.mycloudgallery.feature.onboarding.ui.ToggleRow
import dev.pampa.mycloudgallery.feature.onboarding.ui.stepAmbient

/** Le tre cose da fare per non far fermare il backup notturno a Samsung (§2.1 passo 10, E-P2, E-P15). */
internal enum class BatteryAction { UNRESTRICTED, NEVER_SLEEP, UNUSED_PAUSE }

/** Cosa si sa di una delle tre: verificato da Android, da fare, o non verificabile (lo conferma l'utente). */
internal enum class Verification { CHECKING, VERIFIED, TODO, UNKNOWN }

/**
 * Cosa Android dice di ciascuna impostazione. Nessun valore e' indovinato: un'impostazione che Android non lascia
 * leggere e' [Verification.UNKNOWN], e un avviso sbagliato insegna a ignorare gli avvisi.
 *
 *  - **Batteria "Illimitata"**: `isIgnoringBatteryOptimizations`.
 *  - **"Mai in sospensione"** (One UI): non si legge. Si legge solo se Android sta limitando l'app (restrizione in
 *    background, bucket "raro" o "limitato"): in quel caso e' da fare di sicuro; altrimenti non e' verificabile.
 *  - **"Pausa se inutilizzata"**: `getUnusedAppRestrictionsStatus` (spenta, o non esiste su questo telefono = a posto).
 */
internal fun BackgroundHealth?.verification(action: BatteryAction): Verification {
  if (this == null) return Verification.CHECKING
  return when (action) {
    BatteryAction.UNRESTRICTED -> when (ignoringBatteryOpt) {
      true -> Verification.VERIFIED
      false -> Verification.TODO
      null -> Verification.UNKNOWN
    }
    BatteryAction.NEVER_SLEEP -> when {
      backgroundRestricted == true || standbyBucket == StandbyBucket.RESTRICTED || standbyBucket == StandbyBucket.RARE -> Verification.TODO
      else -> Verification.UNKNOWN
    }
    BatteryAction.UNUSED_PAUSE -> when (unusedAppRestrictions) {
      UnusedAppRestrictions.DISABLED, UnusedAppRestrictions.NOT_AVAILABLE -> Verification.VERIFIED
      UnusedAppRestrictions.ENABLED -> Verification.TODO
      UnusedAppRestrictions.UNKNOWN -> Verification.UNKNOWN
    }
  }
}

/** Le conferme a mano per cio' che Android non lascia verificare. */
@Stable
internal class BatteryForm(confirmed: Set<BatteryAction> = emptySet()) {
  var confirmed: Set<BatteryAction> by mutableStateOf(confirmed)

  fun toggle(action: BatteryAction) {
    confirmed = if (action in confirmed) confirmed - action else confirmed + action
  }
}

internal class BatteryActions(
  val onOpen: (BatteryAction) -> Unit = {},
  val onRecheck: () -> Unit = {},
  val onContinue: () -> Unit = {},
  val onSkip: () -> Unit = {},
  val onProblemAction: (ProblemAction) -> Unit = {},
)

private fun BatteryAction.icon(): ImageVector = when (this) {
  BatteryAction.UNRESTRICTED -> Icons.Rounded.BatteryChargingFull
  BatteryAction.NEVER_SLEEP -> Icons.Rounded.Bedtime
  BatteryAction.UNUSED_PAUSE -> Icons.Rounded.PauseCircle
}

private fun BatteryAction.title(): Int = when (this) {
  BatteryAction.UNRESTRICTED -> R.string.battery_unrestricted
  BatteryAction.NEVER_SLEEP -> R.string.battery_never_sleep
  BatteryAction.UNUSED_PAUSE -> R.string.battery_unused_pause
}

private fun BatteryAction.detail(): Int = when (this) {
  BatteryAction.UNRESTRICTED -> R.string.battery_unrestricted_detail
  BatteryAction.NEVER_SLEEP -> R.string.battery_never_sleep_detail
  BatteryAction.UNUSED_PAUSE -> R.string.battery_unused_pause_detail
}

/**
 * Passo 10: la salute del background (§2.1, E-P2, E-P15). Tre azioni, ognuna con la sua verifica letta da
 * Android quando si torna in questa pagina (E-O9: andare nelle impostazioni e tornare e' il caso normale).
 * Cio' che Android non lascia verificare si conferma a mano ("L'ho fatto"). Il passo si puo' saltare.
 */
@Composable
internal fun BatteryScreen(
  chrome: StepChrome,
  ui: BatteryUi,
  form: BatteryForm,
  actions: BatteryActions,
  modifier: Modifier = Modifier,
) {
  StepScaffold(
    title = stringResource(R.string.battery_title),
    subtitle = stringResource(R.string.battery_subtitle),
    chrome = chrome,
    ambient = stepAmbient(OnboardingStep.BATTERY),
    modifier = modifier,
    primary = DockAction(
      label = stringResource(R.string.action_continue),
      onClick = actions.onContinue,
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
        icon = Icons.Rounded.Info,
        title = stringResource(R.string.battery_why_title),
        body = stringResource(R.string.battery_why_detail),
      )
    }
    item(key = "header") { GallerySectionHeader(title = stringResource(R.string.battery_section)) }
    item(key = "list") {
      FluidListGroup {
        BatteryAction.entries.forEachIndexed { index, action ->
          if (index > 0) FluidListDivider()
          BatteryRow(
            action = action,
            verification = ui.health.verification(action),
            confirmed = action in form.confirmed,
            onOpen = { actions.onOpen(action) },
          )
        }
      }
    }
    val manual = BatteryAction.entries.filter { ui.health.verification(it).let { v -> v == Verification.UNKNOWN || v == Verification.TODO && it == BatteryAction.NEVER_SLEEP } }
    if (manual.isNotEmpty()) {
      item(key = "manual-header") {
        GallerySectionHeader(
          title = stringResource(R.string.battery_manual_section),
          detail = stringResource(R.string.battery_manual_section_detail),
        )
      }
      item(key = "manual") {
        FluidListGroup {
          manual.forEachIndexed { index, action ->
            if (index > 0) FluidListDivider()
            ToggleRow(
              title = stringResource(R.string.battery_manual_row, stringResource(action.title())),
              subtitle = "",
              icon = action.icon(),
              checked = action in form.confirmed,
              onCheckedChange = { form.toggle(action) },
            )
          }
        }
      }
    }
    item(key = "recheck") {
      CardButton(
        text = stringResource(R.string.battery_recheck),
        onClick = actions.onRecheck,
        primary = false,
      )
    }
    ui.problem?.let { problem ->
      item(key = "problem") { ProblemPanel(problem = problem, onAction = actions.onProblemAction) }
    }
    item(key = "footnote") { FluidSectionFootnote(text = stringResource(R.string.battery_footnote)) }
  }
}

@Composable
private fun BatteryRow(
  action: BatteryAction,
  verification: Verification,
  confirmed: Boolean,
  onOpen: () -> Unit,
) {
  val done = verification == Verification.VERIFIED || (verification == Verification.UNKNOWN && confirmed)
  FluidListRow(
    title = stringResource(action.title()),
    subtitle = stringResource(action.detail()),
    tone = if (done) FluidTone.Success else FluidTone.Warning,
    leading = { Icon(action.icon(), contentDescription = null) },
    labels = {
      when (verification) {
        Verification.CHECKING -> FluidSpinner()
        Verification.VERIFIED -> FluidStatusBadge(label = stringResource(R.string.battery_state_verified), tone = FluidTone.Success)
        Verification.TODO -> FluidStatusBadge(label = stringResource(R.string.battery_state_todo), tone = FluidTone.Warning)
        Verification.UNKNOWN ->
          if (confirmed) {
            FluidStatusBadge(label = stringResource(R.string.battery_state_confirmed), tone = FluidTone.Success)
          } else {
            FluidStatusBadge(label = stringResource(R.string.battery_state_unknown), tone = FluidTone.Neutral)
          }
      }
    },
    onClick = onOpen,
  )
}
