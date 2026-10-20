package dev.pampa.mycloudgallery.feature.onboarding.steps

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.feature.onboarding.R
import dev.pampa.mycloudgallery.feature.onboarding.model.RemovalReason
import dev.pampa.mycloudgallery.feature.onboarding.model.RemovalUi
import dev.pampa.mycloudgallery.feature.onboarding.ui.DockAction
import dev.pampa.mycloudgallery.feature.onboarding.ui.NoticeCard
import dev.pampa.mycloudgallery.feature.onboarding.ui.NoticeTone
import dev.pampa.mycloudgallery.feature.onboarding.ui.ProblemAction
import dev.pampa.mycloudgallery.feature.onboarding.ui.ProblemPanel
import dev.pampa.mycloudgallery.feature.onboarding.ui.StepChrome
import dev.pampa.mycloudgallery.feature.onboarding.ui.OnboardingSheetHost
import dev.pampa.mycloudgallery.feature.onboarding.ui.StartOverSheet
import dev.pampa.mycloudgallery.feature.onboarding.ui.StepScaffold
import dev.pampa.mycloudgallery.feature.onboarding.ui.stepAmbient

/**
 * Il dispositivo non fa piu' parte della famiglia (verifica funzionale, ON-4): l'admin l'ha revocato o dismesso, oppure
 * lo stato della famiglia salvato non c'e' piu' (per esempio dopo un reset). Prima qui restava il vecchio riepilogo con
 * "Vai alla Libreria", che non faceva niente. Revocato e dismesso hanno la stessa uscita (decisione del 19/10, ON-4): la
 * Libreria del NAS non c'e' piu' e il dispositivo si configura di nuovo, con una chiave nuova e una nuova approvazione.
 * "Configura di nuovo" dimentica account, password e chiavi (non le foto). Il tasto non parte da solo: apre un foglio di
 * conferma (R7 della revisione 5B), che per un admin dice cosa si perde e come si recupera.
 */
@Composable
internal fun RemovedScreen(
  chrome: StepChrome,
  ui: RemovalUi,
  onAskStartOver: () -> Unit,
  onCancelStartOver: () -> Unit,
  onStartOver: () -> Unit,
  onProblemAction: (ProblemAction) -> Unit,
  modifier: Modifier = Modifier,
) {
  // Il foglio di conferma ha bisogno di un host dei pop-up: in onboarding la radice non ne ha (la Libreria non c'e' ancora).
  OnboardingSheetHost {
    RemovedContent(chrome, ui, onAskStartOver, onProblemAction, modifier)
    StartOverSheet(
      visible = ui.confirming,
      holdsKey = ui.holdsKey,
      unknownCause = ui.reason == RemovalReason.OTHER,
      onDismiss = onCancelStartOver,
      onConfirm = onStartOver,
    )
  }
}

@Composable
private fun RemovedContent(
  chrome: StepChrome,
  ui: RemovalUi,
  onAskStartOver: () -> Unit,
  onProblemAction: (ProblemAction) -> Unit,
  modifier: Modifier,
) {
  StepScaffold(
    title = stringResource(
      when (ui.reason) {
        RemovalReason.REVOKED -> R.string.removed_title_revoked
        RemovalReason.RETIRED -> R.string.removed_title_retired
        RemovalReason.OTHER -> R.string.removed_title_other
      },
    ),
    subtitle = stringResource(R.string.removed_subtitle),
    chrome = chrome,
    ambient = stepAmbient(OnboardingStep.WELCOME),
    modifier = modifier,
    primary = DockAction(
      label = stringResource(R.string.removed_action),
      onClick = onAskStartOver,
      enabled = !ui.busy,
      loading = ui.busy,
    ),
  ) {
    item(key = "why") {
      NoticeCard(
        tone = NoticeTone.Caution,
        icon = Icons.Rounded.Block,
        title = stringResource(R.string.removed_why_title),
        body = stringResource(
          when (ui.reason) {
            RemovalReason.REVOKED -> R.string.removed_detail_revoked
            RemovalReason.RETIRED -> R.string.removed_detail_retired
            RemovalReason.OTHER -> R.string.removed_detail_other
          },
        ),
      )
    }
    item(key = "again-header") { FluidSectionHeader(title = stringResource(R.string.removed_again_section)) }
    item(key = "again") {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.removed_again_forget),
          subtitle = stringResource(R.string.removed_again_forget_detail),
          tone = FluidTone.Warning,
          leading = { Icon(Icons.Rounded.Key, contentDescription = null) },
          disclosure = false,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.removed_again_join),
          subtitle = stringResource(R.string.removed_again_join_detail),
          tone = FluidTone.Primary,
          leading = { Icon(Icons.Rounded.QrCodeScanner, contentDescription = null) },
          disclosure = false,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.removed_again_keep),
          subtitle = stringResource(R.string.removed_again_keep_detail),
          tone = FluidTone.Success,
          leading = { Icon(Icons.Rounded.PhotoLibrary, contentDescription = null) },
          disclosure = false,
        )
      }
    }
    ui.problem?.let { problem ->
      item(key = "problem") { ProblemPanel(problem = problem, onAction = onProblemAction) }
    }
    item(key = "footnote") { FluidSectionFootnote(text = stringResource(R.string.removed_footnote)) }
  }
}
