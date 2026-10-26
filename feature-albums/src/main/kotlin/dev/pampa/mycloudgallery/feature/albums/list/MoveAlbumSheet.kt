package dev.pampa.mycloudgallery.feature.albums.list

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryConfirmSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetScaffold
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.feature.albums.R

// "SPOSTA" UN ALBUM (Fase 7, P10; D21): con l'ordine personalizzato dell'elenco, la pressione prolungata su una scheda apre questo foglio. Senza trascinare
// (nessun gesto da indovinare, nessun bersaglio piccolo), due righe da 48 dp: "Sposta su" e "Sposta giu'". Il foglio resta aperto, cosi' si va avanti di
// piu' posti con piu' tocchi; una riga che non si puo' fare (il primo non va piu' su) e' spenta. Sono le stesse mosse che TalkBack ha come azioni.

@Composable
internal fun MoveAlbumSheet(
  title: String?,
  canMoveUp: Boolean,
  canMoveDown: Boolean,
  onMove: (ListMove) -> Unit,
  onDismiss: () -> Unit,
) {
  GalleryConfirmSheet(visible = title != null, onDismissRequest = onDismiss, paneTitle = stringResource(R.string.albums_move_title)) {
    GallerySheetScaffold(
      icon = Icons.Rounded.SwapVert,
      tone = GallerySheetTone.Brand,
      title = stringResource(R.string.albums_move_title),
      message = title,
      body = {
        FluidListGroup {
          FluidListRow(
            title = stringResource(R.string.albums_move_up), subtitle = stringResource(R.string.albums_move_up_detail), tone = FluidTone.Primary, disclosure = false,
            onClick = if (canMoveUp) ({ onMove(ListMove.UP) }) else null,
            leading = { Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = null) },
          )
          FluidListDivider()
          FluidListRow(
            title = stringResource(R.string.albums_move_down), subtitle = stringResource(R.string.albums_move_down_detail), tone = FluidTone.Primary, disclosure = false,
            onClick = if (canMoveDown) ({ onMove(ListMove.DOWN) }) else null,
            leading = { Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null) },
          )
        }
      },
      actions = { FluidButton(text = stringResource(R.string.albums_move_done), onClick = onDismiss, size = FluidButtonSize.Large, fillWidth = true) },
    )
  }
}
