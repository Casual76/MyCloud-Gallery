package dev.pampa.mycloudgallery.feature.onboarding.join

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidSpinner
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.qr.QrCode
import dev.pampa.mycloudgallery.core.security.screen.OverlayShield
import dev.pampa.mycloudgallery.core.security.screen.SensitiveScreen
import dev.pampa.mycloudgallery.feature.onboarding.R
import dev.pampa.mycloudgallery.feature.onboarding.ui.CodeGroups
import dev.pampa.mycloudgallery.feature.onboarding.ui.DockAction
import dev.pampa.mycloudgallery.feature.onboarding.ui.OnboardingDimens
import dev.pampa.mycloudgallery.feature.onboarding.ui.StepChrome
import dev.pampa.mycloudgallery.feature.onboarding.ui.StepScaffold
import dev.pampa.mycloudgallery.feature.onboarding.ui.stepAmbient

/**
 * In attesa dell'approvazione (§2.2, E-S14): il **codice** da far confrontare all'admin, grande, e il **QR della
 * chiave** del dispositivo (`MCGK1:`), che l'admin puo' scansionare invece di digitare il codice (approvazione
 * "stessa stanza"). Fino all'approvazione il dispositivo e' in sola lettura; il registro si rilegge ogni 10 secondi
 * solo con l'app aperta, e la pagina lo dice senza promettere piu' di cosi'.
 *
 * Il QR vive su una finestra protetta (`FLAG_SECURE`, dentro `QrCode`): non finisce in uno screenshot ne' nei recenti.
 * E' pubblica per :app, che la mostra anche quando il dispositivo e' gia' registrato e l'app viene riaperta; senza il
 * QR (lo stato salvato porta solo il codice) mostra solo il codice. Con [onContinue] (dentro l'onboarding) c'e' il tasto
 * per finire intanto i passi sul telefono.
 */
@Composable
fun AwaitingApprovalScreen(
  code: String,
  deviceKeyQr: String?,
  modifier: Modifier = Modifier,
  onContinue: (() -> Unit)? = null,
) {
  // Revisione 2, F5: il codice e' cio' che l'admin digita per approvare. Nessuna sovrapposizione di un'altra app
  // (che potrebbe coprirlo con un codice falso) e niente cattura dello schermo ne' anteprima nei recenti.
  OverlayShield()
  SensitiveScreen()
  val groups = code.split('-', ' ').filter { it.isNotBlank() }.ifEmpty { code.chunked(CODE_GROUP) }
  StepScaffold(
    title = stringResource(R.string.awaiting_title),
    subtitle = stringResource(R.string.awaiting_subtitle),
    chrome = StepChrome(),
    ambient = stepAmbient(OnboardingStep.SUMMARY),
    modifier = modifier,
    // Dentro l'onboarding (P9): mentre l'admin approva, si finiscono i passi sul telefono. Alla fine la radice mostra
    // di nuovo questa pagina, che si aggiorna da sola quando arriva l'approvazione.
    primary = onContinue?.let { DockAction(label = stringResource(R.string.awaiting_continue), onClick = it) },
  ) {
    item(key = "code-header") {
      FluidSectionHeader(
        title = stringResource(R.string.awaiting_code_section),
        detail = stringResource(R.string.awaiting_code_section_detail),
      )
    }
    item(key = "code") {
      Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = OnboardingDimens.CardGap),
        verticalArrangement = Arrangement.spacedBy(OnboardingDimens.CardGap),
      ) {
        CodeGroups(groups = groups, cellMinWidth = OnboardingDimens.ApprovalCellMinWidth, large = true)
      }
    }
    if (deviceKeyQr != null) {
      item(key = "qr-header") {
        FluidSectionHeader(
          title = stringResource(R.string.awaiting_qr_section),
          detail = stringResource(R.string.awaiting_qr_section_detail),
        )
      }
      item(key = "qr") {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
          QrCode(
            content = deviceKeyQr,
            contentDescription = stringResource(R.string.awaiting_qr_description),
            secure = true,
            modifier = Modifier.widthIn(max = OnboardingDimens.QrSide),
          )
        }
      }
    }
    item(key = "steps-header") { FluidSectionHeader(title = stringResource(R.string.awaiting_steps_section)) }
    item(key = "steps") {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.awaiting_step_request),
          subtitle = stringResource(R.string.awaiting_step_request_detail),
          tone = FluidTone.Primary,
          leading = { Icon(Icons.Rounded.Inbox, contentDescription = null) },
          disclosure = false,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.awaiting_step_compare),
          subtitle = stringResource(R.string.awaiting_step_compare_detail),
          tone = FluidTone.Primary,
          leading = { Icon(Icons.Rounded.Compare, contentDescription = null) },
          disclosure = false,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.awaiting_step_approve),
          subtitle = stringResource(R.string.awaiting_step_approve_detail),
          tone = FluidTone.Primary,
          leading = { Icon(Icons.Rounded.TaskAlt, contentDescription = null) },
          disclosure = false,
        )
      }
    }
    item(key = "waiting") {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.awaiting_waiting),
          subtitle = stringResource(R.string.awaiting_waiting_detail),
          tone = FluidTone.Neutral,
          leading = { Icon(Icons.Rounded.Lock, contentDescription = null) },
          badge = { FluidSpinner() },
          disclosure = false,
        )
      }
    }
    item(key = "footnote") { FluidSectionFootnote(text = stringResource(R.string.awaiting_note)) }
  }
}

private const val CODE_GROUP = 4
