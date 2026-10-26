package dev.pampa.mycloudgallery.feature.library.trash

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryConfirmSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetScaffold
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.feature.library.R
import dev.pampa.mycloudgallery.feature.library.utility.UtilityText

// Fase 7, P8 (D26, F4 Q8): "Elimina subito" a DOPPIO TOCCO. Non e' un cestino: le voci proprie scelte vanno via per sempre dal NAS. Per questo servono
// due gesti distinti: il primo sul pannello che dice cosa succede, il secondo su un pannello nuovo che dice che senza un backup esterno non c'e'
// modo di riaverle. Un tocco arrivato meno di mezzo secondo dopo il precedente (un doppio tocco, un tocco registrato due volte) non conta
// (`DoubleTapConfirm`, la stessa regola del foglio di conferma della 4B). La prova dei due tocchi ([UserTap]) nasce solo da qui.

/**
 * Il foglio di "Elimina subito" per [count] elementi di [bytes] byte. [monotonicMs] e' l'orologio monotono dei tocchi (mai l'ora del telefono);
 * [onConfirmed] riceve la prova dei due tocchi **una volta sola**, [onDismiss] chiude.
 *
 * Il foglio ricomincia da capo a ogni apertura: nessuna conferma sopravvive alla chiusura.
 */
@Composable
internal fun PurgeNowSheet(
  visible: Boolean,
  count: Int,
  bytes: Long,
  monotonicMs: () -> Long,
  onConfirmed: (UserTap) -> Unit,
  onDismiss: () -> Unit,
) {
  val title = pluralStringResource(R.plurals.trash_purge_title, count, GalleryNumbers.format(count))
  // Una macchina nuova a ogni apertura: la sua "comparsa" e' l'istante in cui il foglio si apre, e nessun tocco sopravvive alla chiusura.
  val confirm = remember(visible) { DoubleTapConfirm(monotonicMs()) }
  var step by remember(visible) { mutableStateOf(0) }
  GalleryConfirmSheet(visible = visible, onDismissRequest = onDismiss, paneTitle = title) {
    if (step == 0) {
      GallerySheetScaffold(
        icon = Icons.Rounded.DeleteForever,
        tone = GallerySheetTone.Destructive,
        title = title,
        message = stringResource(R.string.trash_purge_message, UtilityText.size(bytes)),
        actions = {
          FluidButton(
            text = stringResource(R.string.trash_purge_confirm),
            onClick = {
              // Il primo tocco contato porta al pannello nuovo; uno ignorato (troppo vicino alla comparsa) non cambia niente.
              val before = confirm.taps
              confirm.tap(monotonicMs())
              if (confirm.taps > before) step = confirm.taps
            },
            style = FluidButtonStyle.Destructive,
            size = FluidButtonSize.Large,
            fillWidth = true,
          )
          FluidButton(
            text = stringResource(R.string.trash_cancel),
            onClick = onDismiss,
            style = FluidButtonStyle.Tinted,
            size = FluidButtonSize.Large,
            fillWidth = true,
          )
        },
      )
    } else {
      GallerySheetScaffold(
        icon = Icons.Rounded.DeleteForever,
        tone = GallerySheetTone.Destructive,
        title = stringResource(R.string.trash_purge_final_title),
        message = stringResource(R.string.trash_purge_final_message),
        actions = {
          FluidButton(
            text = stringResource(R.string.trash_purge_final_confirm),
            onClick = {
              // Il secondo tocco, contato solo se e' un secondo gesto: da' la prova dei due tocchi, una volta sola.
              if (confirm.tap(monotonicMs())) confirm.proof()?.let(onConfirmed)
            },
            style = FluidButtonStyle.Destructive,
            size = FluidButtonSize.Large,
            fillWidth = true,
          )
          FluidButton(
            text = stringResource(R.string.trash_cancel),
            onClick = onDismiss,
            style = FluidButtonStyle.Tinted,
            size = FluidButtonSize.Large,
            fillWidth = true,
          )
        },
      )
    }
  }
}
