package dev.pampa.mycloudgallery.root

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.pampa.mycloudgallery.R
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryFigureState
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryIllustrationTone
import dev.pampa.mycloudgallery.core.family.contract.BlockReason
import dev.pampa.mycloudgallery.feature.onboarding.ui.OnboardingSheetHost
import dev.pampa.mycloudgallery.feature.onboarding.ui.StartOverSheet

// Le schermate degli stati del dispositivo che non sono ne' l'onboarding ne' la galleria. L'attesa dell'approvazione
// e' quella di feature-onboarding (codice e QR della chiave, P6); qui "azzerato" e "fermo".

/**
 * Il dispositivo si e' azzerato dopo la segnalazione di smarrimento (E-S2). "Configura di nuovo" dimentica il diario
 * dell'azzeramento e riporta al benvenuto: il dispositivo rientra con una chiave nuova e va riapprovato.
 */
@Composable
fun WipedScreen(onReconfigure: () -> Unit) {
  FluidScreen(
    title = stringResource(R.string.wiped_title),
    subtitle = stringResource(R.string.wiped_subtitle),
  ) {
    item {
      Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = ScreenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ScreenGap),
      ) {
        GalleryFigureState(icon = Icons.Rounded.RestartAlt, title = stringResource(R.string.wiped_reason_title), fill = false) {
          BlockingText(stringResource(R.string.wiped_detail))
          FluidButton(
            text = stringResource(R.string.wiped_reconfigure),
            onClick = onReconfigure,
            size = FluidButtonSize.Large,
          )
        }
      }
    }
  }
}

/**
 * Fermo per un motivo che l'utente deve conoscere. Il Keystore che non risponde ha la sua pagina, con "Riprova"
 * ([KeystoreUnavailableScreen]): la radice la sceglie prima di arrivare qui; questo testo e' solo la rete di sicurezza.
 */
@Composable
fun BlockedScreen(reason: BlockReason) {
  val detail = when (reason) {
    BlockReason.NEEDS_APP_UPDATE -> R.string.blocked_update
    BlockReason.REGISTRY_FORK -> R.string.blocked_fork
    BlockReason.WIPE_IN_PROGRESS -> R.string.blocked_wipe
    BlockReason.STORAGE_LOCKED -> R.string.blocked_storage
    BlockReason.KEYSTORE_UNAVAILABLE -> R.string.blocked_keystore
  }
  FluidScreen(title = stringResource(R.string.blocked_title)) {
    item {
      GalleryFigureState(icon = Icons.Rounded.Lock, title = stringResource(R.string.blocked_title), fill = false, tone = GalleryIllustrationTone.Caution) {
        BlockingText(stringResource(detail))
      }
    }
  }
}

/**
 * Il Keystore non risponde (R7 della revisione 5B, `BlockReason.KEYSTORE_UNAVAILABLE`): lo stato della famiglia c'e', ma il telefono
 * non riesce a usare le chiavi. La pagina lo dice per quello che e' (prima compariva "fuori dalla famiglia"), spiega cosa resta fermo
 * e offre **"Riprova"**, che ricalcola lo stato chiedendo di nuovo la chiave. "Configura di nuovo" **non** e' l'azione principale: e'
 * un'uscita in fondo per chi vuole davvero ricominciare, dietro lo stesso foglio di conferma della pagina "fuori dalla famiglia"
 * (`StartOverSheet`, con l'avviso dell'admin che perde la chiave di famiglia e il ricordo che puo' essere un guasto del telefono).
 */
@Composable
fun KeystoreUnavailableScreen(
  ui: KeystoreUi,
  onRetry: () -> Unit,
  onAskStartOver: () -> Unit,
  onCancelStartOver: () -> Unit,
  onConfirmStartOver: () -> Unit,
) {
  // Il foglio di conferma ha bisogno di un host dei pop-up: fuori dalla Libreria la radice non ne ha uno.
  OnboardingSheetHost {
    FluidScreen(
      title = stringResource(R.string.keystore_title),
      subtitle = stringResource(R.string.keystore_subtitle),
    ) {
      item(key = "reason") {
        Column(
          modifier = Modifier.fillMaxWidth().padding(vertical = ScreenPadding),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(ScreenGap),
        ) {
          GalleryFigureState(icon = Icons.Rounded.Key, title = stringResource(R.string.keystore_reason_title), fill = false, tone = GalleryIllustrationTone.Caution) {
            BlockingText(stringResource(R.string.keystore_detail))
          }
          FluidButton(
            text = stringResource(R.string.keystore_retry),
            onClick = onRetry,
            size = FluidButtonSize.Large,
            enabled = !ui.busy,
            loading = ui.retrying,
          )
          if (ui.stillUnavailable && !ui.retrying) NoteText(stringResource(R.string.keystore_still))
        }
      }
      item(key = "why-header") { FluidSectionHeader(title = stringResource(R.string.keystore_why_title)) }
      item(key = "why") { FluidSectionFootnote(text = stringResource(R.string.keystore_why_detail)) }
      item(key = "again-header") { FluidSectionHeader(title = stringResource(R.string.keystore_start_over_section)) }
      item(key = "again") {
        Column(
          modifier = Modifier.fillMaxWidth().padding(vertical = ScreenGap / 2),
          verticalArrangement = Arrangement.spacedBy(ScreenGap / 2),
        ) {
          FluidSectionFootnote(text = stringResource(R.string.keystore_start_over_detail))
          FluidButton(
            text = stringResource(R.string.keystore_start_over),
            onClick = onAskStartOver,
            style = FluidButtonStyle.Plain,
            enabled = !ui.busy && !ui.retrying,
            loading = ui.busy,
          )
          ui.problem?.let { problem ->
            NoteText(
              stringResource(
                when (problem) {
                  KeystoreProblem.KEYS_NOT_REMOVED -> R.string.keystore_problem_keys
                  KeystoreProblem.NOT_DONE -> R.string.keystore_problem_other
                },
              ),
            )
          }
        }
      }
    }
    StartOverSheet(
      visible = ui.confirming,
      holdsKey = ui.holdsKey,
      unknownCause = true,
      onDismiss = onCancelStartOver,
      onConfirm = onConfirmStartOver,
    )
  }
}

@Composable
private fun NoteText(text: String) {
  Text(
    text = text,
    modifier = Modifier.fillMaxWidth(),
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.error,
    textAlign = TextAlign.Center,
  )
}

private val ScreenPadding = 24.dp
private val ScreenGap = 16.dp

/**
 * CS-17 (fase I): la frase aperta sotto il titolo di una pagina che blocca (la versione da aggiornare, il dispositivo azzerato, il Keystore): e'
 * l'avvertimento che serve, quindi non sta dietro la «i». Al centro, nel colore della pagina.
 */
@Composable
internal fun BlockingText(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.bodyLarge,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    textAlign = TextAlign.Center,
  )
}
