package dev.pampa.mycloudgallery.feature.backup.free

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpacePlan
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmation
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmationRules
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryConfirmSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetScaffold
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.feature.backup.R
import dev.pampa.mycloudgallery.feature.backup.ui.BackupButton
import dev.pampa.mycloudgallery.feature.backup.ui.BackupText
import kotlinx.coroutines.delay

// Fase 5B (P12, docs/fase5-progetto.md §2.5 punto 2, D21, C13, R6 e R7 della 4B): i FOGLI DI CONFERMA di "Libera spazio". Due, e hanno in comune
// l'avviso forte in cima e lo scudo contro le sovrapposizioni (`GalleryConfirmSheet`):
//  - "Libera spazio": un tocco. Dice cosa succede (cestino di Android per 30 giorni, ricontrollo di ogni copia) e quanti sono;
//  - "Libera subito": **due gesti distinti** (R6). Il primo porta a un secondo pannello, con l'avviso sul backup esterno, in cui il pulsante
//    pericoloso sta in un altro punto (sotto "No, tienili") e si accende solo dopo 600 ms; un doppio tocco non conta nemmeno per il contratto
//    (`PhoneConfirmationSheet` scarta un tocco arrivato meno di 600 ms dopo il precedente).
// La conferma nasce **solo** dal gestore del pulsante (`ConfirmTaps.tap`): mai da una composizione, mai da un dato.

/** I fogli di conferma della pagina: si chiudono con il contenuto che avevano, non a vuoto. */
@Composable
internal fun FreeSheets(sheet: FreeSheet, taps: ConfirmTaps?, actions: FreeSpaceActions) {
  var shown by remember { mutableStateOf<FreeSheet>(FreeSheet.None) }
  if (sheet !is FreeSheet.None) shown = sheet
  val showing = shown
  GalleryConfirmSheet(
    visible = sheet !is FreeSheet.None,
    onDismissRequest = actions.closeSheet,
    paneTitle = stringResource(if (showing is FreeSheet.ConfirmNow) R.string.backup_free_now_title else R.string.backup_free_confirm_title),
  ) {
    when (showing) {
      FreeSheet.None -> Unit
      is FreeSheet.Confirm -> FreeConfirmContent(showing.plan, taps, actions.confirm, actions.closeSheet)
      is FreeSheet.ConfirmNow -> FreeConfirmNowContent(showing.plan, taps, actions.confirm, actions.closeSheet)
    }
  }
}

// --- "Libera spazio": un tocco -------------------------------------------------------------------------------------------------

/** Il contenuto del foglio "Libera spazio" (anche per gli screenshot e i test, senza il pop-up). */
@Composable
internal fun FreeConfirmContent(
  plan: FreeSpacePlan,
  taps: ConfirmTaps?,
  onConfirm: (PhoneConfirmation) -> Unit,
  onCancel: () -> Unit,
  modifier: Modifier = Modifier,
) {
  GallerySheetScaffold(
    icon = Icons.Rounded.CleaningServices,
    tone = GallerySheetTone.Caution,
    title = stringResource(R.string.backup_free_confirm_title),
    message = if (plan.itemCount > 0) {
      pluralStringResource(R.plurals.backup_free_confirm_message, plan.itemCount, BackupText.count(plan.itemCount), BackupText.size(plan.totalBytes))
    } else {
      stringResource(R.string.backup_free_confirm_empty)
    },
    modifier = modifier,
    body = {
      StrongWarning(permanent = false)
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.backup_free_confirm_where_title),
          subtitle = stringResource(R.string.backup_free_confirm_where_detail),
          tone = FluidTone.Neutral,
          leading = { Icon(imageVector = Icons.Rounded.Delete, contentDescription = null) },
          disclosure = false,
          titleMaxLines = 2,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.backup_free_confirm_check_title),
          subtitle = stringResource(R.string.backup_free_confirm_check_detail),
          tone = FluidTone.Neutral,
          leading = { Icon(imageVector = Icons.Rounded.Shield, contentDescription = null) },
          disclosure = false,
          titleMaxLines = 2,
        )
      }
    },
    actions = {
      // L'unico punto dove nasce la conferma di "Libera spazio": il tocco su questo pulsante.
      BackupButton(
        label = stringResource(R.string.backup_free_confirm_yes),
        onClick = { taps?.tap()?.let(onConfirm) },
        enabled = taps != null && plan.itemCount > 0,
      )
      BackupButton(
        label = stringResource(R.string.backup_free_confirm_no),
        onClick = {
          taps?.reset()
          onCancel()
        },
        style = FluidButtonStyle.Tinted,
      )
    },
  )
}

// --- "Libera subito": due gesti distinti -----------------------------------------------------------------------------------------

/**
 * Il contenuto del foglio "Libera subito": il pannello cambia dopo il primo tocco ([ConfirmTaps.taps]). Nel secondo pannello il pulsante che
 * elimina sta **sotto** quello che rinuncia (nel primo e' sopra) e resta spento per 600 ms: il secondo gesto e' un altro gesto, dopo aver letto.
 */
@Composable
internal fun FreeConfirmNowContent(
  plan: FreeSpacePlan,
  taps: ConfirmTaps?,
  onConfirm: (PhoneConfirmation) -> Unit,
  onCancel: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val second = (taps?.taps ?: 0) >= 1
  // Un pulsante si accende 600 ms dopo che il pannello e' comparso (e dopo ogni tocco): lo stesso tempo che il contratto chiede fra due tocchi.
  var armed by remember(taps?.taps) { mutableStateOf(false) }
  LaunchedEffect(taps?.taps) {
    armed = false
    delay(PhoneConfirmationRules.MIN_TAP_GAP_MS)
    armed = true
  }
  val title = stringResource(if (second) R.string.backup_free_now_last_title else R.string.backup_free_now_title)
  GallerySheetScaffold(
    icon = Icons.Rounded.DeleteForever,
    tone = GallerySheetTone.Destructive,
    title = title,
    message = if (second) {
      stringResource(R.string.backup_free_now_last_message)
    } else {
      pluralStringResource(R.plurals.backup_free_now_message, plan.itemCount, BackupText.count(plan.itemCount), BackupText.size(plan.totalBytes))
    },
    // TalkBack annuncia il pannello nuovo quando compare: e' un'altra domanda, non la stessa.
    modifier = modifier.semantics { paneTitle = title },
    body = { StrongWarning(permanent = true) },
    actions = {
      if (!second) {
        // Primo gesto: "Libera subito". Il pulsante che rinuncia sta sotto.
        BackupButton(
          label = stringResource(R.string.backup_free_now_continue),
          onClick = { taps?.tap()?.let(onConfirm) },
          style = FluidButtonStyle.Destructive,
          enabled = taps != null && plan.itemCount > 0 && armed,
        )
        BackupButton(
          label = stringResource(R.string.backup_free_confirm_no),
          onClick = {
            taps?.reset()
            onCancel()
          },
          style = FluidButtonStyle.Tinted,
        )
      } else {
        // Secondo gesto: rinunciare e' il pulsante in alto e pieno; eliminare sta sotto, in un altro punto, e si accende dopo un attimo.
        BackupButton(
          label = stringResource(R.string.backup_free_now_no),
          onClick = {
            taps?.reset()
            onCancel()
          },
        )
        BackupButton(
          label = stringResource(R.string.backup_free_now_yes),
          onClick = { taps?.tap()?.let(onConfirm) },
          style = FluidButtonStyle.Destructive,
          enabled = taps != null && armed,
        )
      }
    },
  )
}
