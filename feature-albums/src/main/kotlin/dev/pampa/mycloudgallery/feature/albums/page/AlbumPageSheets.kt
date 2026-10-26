package dev.pampa.mycloudgallery.feature.albums.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidRowValue
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryConfirmSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetScaffold
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumSort
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.feature.albums.R
import dev.pampa.mycloudgallery.feature.albums.contract.PermissionInfo
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumDimens
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumText
import dev.pampa.mycloudgallery.feature.albums.ui.OptionRow
import dev.pampa.mycloudgallery.feature.albums.ui.SectionNote

// I FOGLI DELLA PAGINA DI UN ALBUM (Fase 7, P10): il menu di un elemento, l'ordine, il nome, le conferme e i permessi. Tutti sul guscio dei fogli di
// conferma del design system (vetro, tocchi oscurati scartati): un foglio dice cosa succede, ha un bottone che lo fa e uno per rinunciare, e
// i bottoni sono da 50 dp. Mai testo sopra una foto: le foto non stanno in nessun foglio.

/** Il menu di un elemento (pressione prolungata su una cella): cosa se ne puo' fare secondo la tabella, e chi l'ha aggiunto (Q6). */
@Composable
internal fun ItemMenuSheet(
  menu: ItemMenu?,
  creatorName: (AccountId) -> String,
  onSetCover: (MediaId) -> Unit,
  onMoveToStart: (MediaId) -> Unit,
  onMoveToEnd: (MediaId) -> Unit,
  onRemove: (MediaId) -> Unit,
  onBringBack: (MediaId) -> Unit,
  onDismiss: () -> Unit,
) {
  GalleryConfirmSheet(visible = menu != null, onDismissRequest = onDismiss, paneTitle = stringResource(R.string.album_item_menu_title)) {
    val current = menu ?: return@GalleryConfirmSheet
    GallerySheetScaffold(
      icon = Icons.Rounded.PhotoLibrary,
      tone = GallerySheetTone.Brand,
      title = stringResource(R.string.album_item_menu_title),
      message = current.addedBy?.let { stringResource(R.string.album_item_added_by, creatorName(it)) },
      body = {
        FluidListGroup {
          val rows = buildList<@Composable () -> Unit> {
            if (current.actions.setAsCover) {
              add {
                FluidListRow(
                  title = stringResource(if (current.isCover) R.string.album_item_is_cover else R.string.album_item_cover), subtitle = "", tone = FluidTone.Primary, disclosure = false,
                  leading = { Icon(Icons.Rounded.Image, contentDescription = null) }, onClick = if (current.isCover) null else ({ onSetCover(current.media) }),
                )
              }
            }
            if (current.actions.reorder) {
              add { ItemRow(Icons.Rounded.KeyboardArrowUp, R.string.album_item_to_start, 0) { onMoveToStart(current.media) } }
              add { ItemRow(Icons.Rounded.KeyboardArrowDown, R.string.album_item_to_end, 0) { onMoveToEnd(current.media) } }
            }
            if (current.actions.removeFromAlbum) {
              add {
                ItemRow(
                  Icons.Rounded.Undo, if (current.removeIsExclude) R.string.album_item_exclude else R.string.album_item_remove,
                  if (current.removeIsExclude) R.string.album_item_exclude_detail else R.string.album_item_remove_detail,
                ) { onRemove(current.media) }
              }
            }
            if (current.actions.bringBack) add { ItemRow(Icons.Rounded.LinkOff, R.string.album_item_bring_back, R.string.album_item_bring_back_detail, FluidTone.Warning) { onBringBack(current.media) } }
          }
          rows.forEachIndexed { index, row ->
            if (index > 0) FluidListDivider()
            row()
          }
        }
      },
      actions = { CancelButton(onDismiss) },
    )
  }
}

@Composable
private fun ItemRow(icon: ImageVector, title: Int, detail: Int, tone: FluidTone = FluidTone.Neutral, onClick: () -> Unit) {
  FluidListRow(
    title = stringResource(title), subtitle = if (detail == 0) "" else stringResource(detail), tone = tone, disclosure = false,
    leading = { Icon(icon, contentDescription = null) }, onClick = onClick,
  )
}

/** L'ordine dell'album (D21): dal piu' vecchio, dal piu' recente, personalizzato. */
@Composable
internal fun SortSheet(visible: Boolean, current: AlbumSort, onSelect: (AlbumSort) -> Unit, onDismiss: () -> Unit) {
  GalleryConfirmSheet(visible = visible, onDismissRequest = onDismiss, paneTitle = stringResource(R.string.album_sort_title)) {
    GallerySheetScaffold(
      icon = Icons.Rounded.SwapVert,
      tone = GallerySheetTone.Brand,
      title = stringResource(R.string.album_sort_title),
      message = null,
      body = {
        FluidListGroup {
          AlbumSort.entries.forEachIndexed { index, sort ->
            if (index > 0) FluidListDivider()
            OptionRow(title = stringResource(AlbumText.sort(sort)), detail = "", selected = sort == current, onSelect = { onSelect(sort) })
          }
        }
      },
      actions = { CancelButton(onDismiss) },
    )
  }
}

/** Rinomina l'album: un campo e due bottoni. Un titolo che non va bene lo dice la pagina (messaggio), il foglio si chiude. */
@Composable
internal fun RenameSheet(visible: Boolean, current: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
  GalleryConfirmSheet(visible = visible, onDismissRequest = onDismiss, paneTitle = stringResource(R.string.album_rename_title)) {
    var text by rememberSaveable(current) { mutableStateOf(current) }
    GallerySheetScaffold(
      icon = Icons.Rounded.Edit,
      tone = GallerySheetTone.Brand,
      title = stringResource(R.string.album_rename_title),
      message = null,
      body = { FluidTextField(value = text, onValueChange = { text = it }, placeholder = stringResource(R.string.albums_create_name), modifier = Modifier.fillMaxWidth()) },
      actions = {
        FluidButton(
          text = stringResource(R.string.album_rename_confirm), onClick = { onRename(text) }, size = FluidButtonSize.Large, fillWidth = true, enabled = text.isNotBlank(),
        )
        CancelButton(onDismiss)
      },
    )
  }
}

/** Una conferma semplice ("Elimina l'album", "Non mostrare piu'", "Diventa il responsabile"): cosa succede, e un bottone che lo fa. */
@Composable
internal fun ConfirmSheet(
  visible: Boolean,
  icon: ImageVector,
  tone: GallerySheetTone,
  title: String,
  message: String,
  confirm: String,
  destructive: Boolean,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
) {
  GalleryConfirmSheet(visible = visible, onDismissRequest = onDismiss, paneTitle = title) {
    GallerySheetScaffold(
      icon = icon,
      tone = tone,
      title = title,
      message = message,
      actions = {
        FluidButton(
          text = confirm, onClick = onConfirm, style = if (destructive) FluidButtonStyle.Destructive else FluidButtonStyle.Filled, size = FluidButtonSize.Large, fillWidth = true,
        )
        CancelButton(onDismiss)
      },
    )
  }
}

/**
 * Il foglio "Permessi" (§1.5 `PermissionInfo`, §2.4, D10): il livello, chi ha creato l'album, cosa posso fare io e la dichiarazione. Se la tabella
 * mi da' il cambio di livello (il creatore o l'erede) i tre livelli sono una scelta; altrimenti c'e' solo quello in vigore. Il cambio vale da subito.
 */
@Composable
internal fun PermissionsSheet(
  visible: Boolean,
  info: PermissionInfo?,
  creatorName: String?,
  onSetLevel: (ShareLevel) -> Unit,
  onDismiss: () -> Unit,
) {
  GalleryConfirmSheet(visible = visible && info != null, onDismissRequest = onDismiss, paneTitle = stringResource(R.string.album_permissions_title)) {
    val current = info ?: return@GalleryConfirmSheet
    val who = if (current.mine) stringResource(R.string.album_permissions_creator_you) else stringResource(R.string.album_permissions_creator, creatorName ?: current.creator.value)
    GallerySheetScaffold(
      icon = Icons.Rounded.Lock,
      tone = GallerySheetTone.Brand,
      title = stringResource(R.string.album_permissions_title),
      message = who,
      body = {
        FluidListGroup {
          if (PermissionSummary.canChangeLevel(current)) {
            ShareLevel.entries.forEachIndexed { index, level ->
              if (index > 0) FluidListDivider()
              OptionRow(title = stringResource(AlbumText.level(level)), detail = stringResource(AlbumText.levelDetail(level)), selected = level == current.level, onSelect = { onSetLevel(level) })
            }
            FluidListDivider()
            SectionNote(stringResource(R.string.album_level_changed_note))
          } else {
            FluidListRow(
              title = stringResource(AlbumText.level(current.level)), subtitle = stringResource(AlbumText.levelDetail(current.level)), tone = FluidTone.Info, disclosure = false,
              leading = { Icon(Icons.Rounded.Group, contentDescription = null) },
            )
          }
        }
        Text(stringResource(R.string.album_permissions_can), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        FluidListGroup {
          PermissionSummary.of(current).forEachIndexed { index, row ->
            if (index > 0) FluidListDivider()
            val value = stringResource(if (row.allowed) R.string.album_permissions_yes else R.string.album_permissions_creator_only)
            FluidListRow(title = stringResource(row.ability.label), subtitle = "", disclosure = false, badge = { FluidRowValue(text = value) })
          }
        }
      },
      actions = {
        // La dichiarazione (D10, L57) sta fissa sopra il bottone: non sotto lo scorrimento della tabella.
        Text(stringResource(R.string.album_declaration), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        FluidButton(text = stringResource(R.string.album_permissions_close), onClick = onDismiss, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large, fillWidth = true)
      },
    )
  }
}

@Composable
internal fun CancelButton(onClick: () -> Unit, label: String = stringResource(R.string.album_cancel)) {
  FluidButton(text = label, onClick = onClick, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large, fillWidth = true)
}
