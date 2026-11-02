package dev.pampa.mycloudgallery.feature.onboarding.recovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.feature.onboarding.R
import dev.pampa.mycloudgallery.feature.onboarding.ui.DockAction
import dev.pampa.mycloudgallery.feature.onboarding.ui.NoticeCard
import dev.pampa.mycloudgallery.feature.onboarding.ui.NoticeTone
import dev.pampa.mycloudgallery.feature.onboarding.ui.OnboardingDimens
import dev.pampa.mycloudgallery.feature.onboarding.ui.StepChrome
import dev.pampa.mycloudgallery.feature.onboarding.ui.StepScaffold
import dev.pampa.mycloudgallery.feature.onboarding.ui.stepAmbient

/**
 * "Ricominciare da zero" (verifica funzionale, ON-6; scelta dell'utente del 19/10: "via dal PC"). L'admin ha perso il
 * telefono e non ha piu' ne' kit ne' codice: nessun familiare puo' azzerare la famiglia dall'app (resta un potere
 * dell'admin), quindi la pagina spiega come farlo dal PC: cancellare la cartella `family` dell'area comune, poi rifare la
 * configurazione con "Crea la famiglia". [familyFolder] e' il percorso esatto come lo scrive Esplora file
 * (`\\192.168.178.58\NAS\.mycloudgallery\family`), se si conoscono indirizzo e area comune; senza, la pagina lo descrive.
 *
 * Niente di cio' che la pagina fa tocca il NAS: [onStartFamily] sceglie solo il percorso "Crea la famiglia", che al passo
 * 4 si ferma su "Qui c'e' gia' una famiglia" se la cartella non e' stata cancellata.
 */
@Composable
internal fun RecoveryStartOverPage(
  chrome: StepChrome,
  familyFolder: String?,
  onStartFamily: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val parent = familyFolder?.substringBeforeLast('\\')
  StepScaffold(
    title = stringResource(R.string.startover_title),
    subtitle = stringResource(R.string.startover_subtitle),
    chrome = chrome,
    ambient = stepAmbient(OnboardingStep.FAMILY_KEY, urgent = true),
    modifier = modifier,
    primary = DockAction(label = stringResource(R.string.startover_action), onClick = onStartFamily),
  ) {
    item(key = "loss") {
      NoticeCard(
        tone = NoticeTone.Danger,
        icon = Icons.Rounded.Warning,
        title = stringResource(R.string.startover_loss_title),
        body = stringResource(R.string.startover_loss_detail),
      )
    }
    item(key = "why") {
      NoticeCard(
        tone = NoticeTone.Info,
        icon = Icons.Rounded.Info,
        title = stringResource(R.string.startover_why_title),
        body = stringResource(R.string.startover_why_detail),
        compact = true,
      )
    }
    item(key = "steps-header") { GallerySectionHeader(title = stringResource(R.string.startover_steps_section)) }
    item(key = "open") {
      Column(verticalArrangement = Arrangement.spacedBy(OnboardingDimens.TextGap), modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.startover_step_open), style = MaterialTheme.typography.titleSmall)
        Text(
          text = stringResource(R.string.startover_step_open_detail),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PathText(parent ?: stringResource(R.string.startover_path_unknown))
      }
    }
    item(key = "steps") {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.startover_step_hidden),
          subtitle = stringResource(R.string.startover_step_hidden_detail),
          tone = FluidTone.Neutral,
          leading = { Icon(Icons.Rounded.Visibility, contentDescription = null) },
          disclosure = false,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.startover_step_delete),
          subtitle = familyFolder?.let { stringResource(R.string.startover_step_delete_detail, it) }
            ?: stringResource(R.string.startover_step_delete_detail_unknown),
          tone = FluidTone.Danger,
          leading = { Icon(Icons.Rounded.DeleteForever, contentDescription = null) },
          disclosure = false,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.startover_step_found),
          subtitle = stringResource(R.string.startover_step_found_detail),
          tone = FluidTone.Primary,
          leading = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) },
          disclosure = false,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.startover_step_others),
          subtitle = stringResource(R.string.startover_step_others_detail),
          tone = FluidTone.Neutral,
          leading = { Icon(Icons.Rounded.PhoneAndroid, contentDescription = null) },
          disclosure = false,
        )
      }
    }
    item(key = "footnote") { FluidSectionFootnote(text = stringResource(R.string.startover_footnote)) }
  }
}

/** Un percorso da copiare in Esplora file: carattere a spaziatura fissa, selezionabile. */
@Composable
private fun PathText(path: String) {
  SelectionContainer {
    Text(text = path, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
  }
}
