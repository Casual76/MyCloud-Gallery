package dev.pampa.mycloudgallery.feature.albums.share

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.albums.contract.CloseMode
import dev.pampa.mycloudgallery.core.albums.contract.OthersAfterClose
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryConfirmSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetScaffold
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.feature.albums.R
import dev.pampa.mycloudgallery.feature.albums.page.CancelButton
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumText
import dev.pampa.mycloudgallery.feature.albums.ui.OptionRow
import dev.pampa.mycloudgallery.feature.albums.ui.SectionNote

// I FOGLI DI CHIUSURA (Fase 7, P10; docs/fase7-progetto.md D31 adeguata alla risposta Q5 del 22/10, §1.5 `CloseSheetState`, E-SH7): "Smetti di
// condividere" e "Elimina l'album". Il creatore sceglie **cosa succede agli altri**, ogni volta e **senza una scelta di partenza**: le due righe sono
// entrambe vuote all'apertura e il bottone che chiude resta spento finche' non ne tocca una ("Sparisce per gli altri" / "Ognuno tiene una copia").
// In piu' "Riporta nelle mie cartelle (N miei)", un'opzione a parte, spenta all'apertura; con le copie per gli altri il foglio avvisa che i media
// riportati spariscono anche dalle loro copie. In ogni caso i file restano nell'area comune: lo dice la prima frase.

interface CloseSheetHandlers {
  fun onChooseOthers(choice: OthersAfterClose)

  fun onBringBack(on: Boolean)

  fun onConfirm()

  fun onDismiss()
}

/**
 * Il foglio di chiusura per [ui]. [hidden] lo nasconde senza perderne lo stato: mentre il foglio di "Riporta" e' aperto sopra, questo aspetta, e se
 * l'utente rinuncia al secondo ritrova il primo com'era.
 */
@Composable
fun CloseSheet(ui: CloseSheetUi?, hidden: Boolean, handlers: CloseSheetHandlers) {
  var last by remember { mutableStateOf<CloseSheetUi?>(null) }
  if (ui != null) last = ui
  val shown = ui ?: last
  val sheet = shown?.sheet
  val title = if (sheet == null) "" else stringResource(if (sheet.mode == CloseMode.STOP) R.string.close_title_stop else R.string.close_title_delete, sheet.title)
  GalleryConfirmSheet(visible = ui != null && !hidden, onDismissRequest = handlers::onDismiss, paneTitle = title) {
    val current = shown ?: return@GalleryConfirmSheet
    val state = current.sheet
    val resources = LocalContext.current.resources
    GallerySheetScaffold(
      icon = Icons.Rounded.Group,
      tone = if (state.mode == CloseMode.DELETE) GallerySheetTone.Destructive else GallerySheetTone.Brand,
      title = title,
      message = stringResource(if (state.mode == CloseMode.STOP) R.string.close_message_stop else R.string.close_message_delete) + " " + stringResource(R.string.close_files_stay),
      body = {
        if (current.otherMembers > 0) {
          Text(resources.getQuantityString(R.plurals.close_members, current.otherMembers, current.otherMembers), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Q5: la domanda e le due risposte. Nessuna e' scelta all'apertura.
        Text(stringResource(R.string.close_others_header), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        FluidListGroup {
          OptionRow(
            title = stringResource(R.string.close_others_remove), detail = stringResource(R.string.close_others_remove_detail),
            selected = state.forOthers == OthersAfterClose.REMOVE, onSelect = { handlers.onChooseOthers(OthersAfterClose.REMOVE) },
          )
          FluidListDivider()
          OptionRow(
            title = stringResource(R.string.close_others_keep), detail = stringResource(R.string.close_others_keep_detail),
            selected = state.forOthers == OthersAfterClose.KEEP_COPY, onSelect = { handlers.onChooseOthers(OthersAfterClose.KEEP_COPY) },
          )
          if (state.forOthers == null) {
            FluidListDivider()
            SectionNote(stringResource(R.string.close_others_hint))
          }
        }
        if (state.bringBackCount > 0) {
          FluidListGroup {
            FluidListRow(
              title = resources.getQuantityString(R.plurals.close_bring_back, state.bringBackCount, state.bringBackCount),
              subtitle = stringResource(R.string.close_bring_back_detail),
              tone = FluidTone.Warning,
              leading = { androidx.compose.material3.Icon(Icons.Rounded.Undo, contentDescription = null) },
              disclosure = false,
              badge = { FluidSwitch(checked = state.bringBack, onCheckedChange = null) },
              onClick = { handlers.onBringBack(!state.bringBack) },
            )
          }
          if (state.warnCopiesLoseBroughtBack) {
            FluidInlineMessage(message = stringResource(R.string.close_warn_copies), title = stringResource(R.string.close_others_header), tone = FluidTone.Warning)
          }
        }
        current.problem?.let { why ->
          FluidInlineMessage(message = stringResource(AlbumText.blocked(why)), title = title, tone = FluidTone.Danger)
        }
      },
      actions = {
        FluidButton(
          text = stringResource(
            when {
              current.busy -> R.string.close_working
              state.mode == CloseMode.STOP -> R.string.close_confirm_stop
              else -> R.string.close_confirm_delete
            },
          ),
          onClick = handlers::onConfirm,
          style = if (state.mode == CloseMode.DELETE) FluidButtonStyle.Destructive else FluidButtonStyle.Filled,
          size = FluidButtonSize.Large,
          enabled = state.canConfirm && !current.busy,
          loading = current.busy,
          fillWidth = true,
        )
        CancelButton(handlers::onDismiss, label = stringResource(R.string.close_cancel))
      },
    )
  }
}
