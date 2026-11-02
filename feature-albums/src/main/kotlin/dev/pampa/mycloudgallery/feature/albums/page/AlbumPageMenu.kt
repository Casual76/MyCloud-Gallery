package dev.pampa.mycloudgallery.feature.albums.page

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuEntry
import dev.pampa.mycloudgallery.feature.albums.R
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumAction

// IL MENU ⋮ DELLA PAGINA (Fase 7, P10): le azioni che la tabella dei permessi da' a me ([AlbumPolicy]), in gruppi separati da una banda:
// aggiungere, cambiare l'album, i permessi e la condivisione, e in fondo chi chiude o toglie. L'unica voce che cancella (l'album) e' l'ultima e
// ha il colore dell'errore. Il livello si cambia dal foglio "Permessi", non da qui.

private class MenuItemSpec(val label: Int, val icon: ImageVector, val group: Int, val destructive: Boolean = false)

private val Specs: Map<AlbumAction, MenuItemSpec> = mapOf(
  AlbumAction.ADD_MEDIA to MenuItemSpec(R.string.album_action_add, Icons.Rounded.AddPhotoAlternate, 0),
  AlbumAction.ADOPT_FOLDER_FILES to MenuItemSpec(R.string.album_action_adopt, Icons.Rounded.FolderOpen, 0),
  AlbumAction.RENAME to MenuItemSpec(R.string.album_action_rename, Icons.Rounded.Edit, 1),
  AlbumAction.SET_COVER to MenuItemSpec(R.string.album_action_cover, Icons.Rounded.Image, 1),
  AlbumAction.SET_SORT to MenuItemSpec(R.string.album_action_sort, Icons.Rounded.SwapVert, 1),
  AlbumAction.EDIT_RULES to MenuItemSpec(R.string.album_action_edit_rules, Icons.Rounded.Tune, 1),
  AlbumAction.FREEZE to MenuItemSpec(R.string.album_action_freeze, Icons.Rounded.Lock, 1),
  AlbumAction.UNFREEZE to MenuItemSpec(R.string.album_action_unfreeze, Icons.Rounded.LockOpen, 1),
  AlbumAction.SHOW_PERMISSIONS to MenuItemSpec(R.string.album_action_permissions, Icons.Rounded.Group, 2),
  AlbumAction.SHARE to MenuItemSpec(R.string.album_action_share, Icons.Rounded.IosShare, 2),
  AlbumAction.INHERIT to MenuItemSpec(R.string.album_action_inherit, Icons.Rounded.Verified, 2),
  AlbumAction.LEAVE to MenuItemSpec(R.string.album_action_leave, Icons.Rounded.VisibilityOff, 3),
  AlbumAction.STOP_SHARING to MenuItemSpec(R.string.album_action_stop, Icons.Rounded.LinkOff, 3),
  AlbumAction.DELETE_ALBUM to MenuItemSpec(R.string.album_action_delete_shared, Icons.Rounded.Delete, 3, destructive = true),
  AlbumAction.DELETE_PERSONAL to MenuItemSpec(R.string.album_action_delete, Icons.Rounded.Delete, 3, destructive = true),
)

/** Le voci del menu ⋮ per [actions] (nell'ordine dei gruppi), una banda fra un gruppo e l'altro. Il livello ([AlbumAction.CHANGE_LEVEL]) non e' una voce. */
@Composable
internal fun pageMenu(actions: List<AlbumAction>, onAction: (AlbumAction) -> Unit, newWindow: GalleryMenuEntry.Item? = null): List<GalleryMenuEntry> {
  val items = actions.mapNotNull { action -> Specs[action]?.let { action to it } }.sortedBy { (_, spec) -> spec.group }
  val out = ArrayList<GalleryMenuEntry>()
  var lastGroup = -1
  var windowPlaced = newWindow == null
  for ((action, spec) in items) {
    // Fase 10 (P5, D16): "Apri in una nuova finestra" non e' un'azione sull'album: sta da sola, prima di chi chiude o toglie (l'ultima voce resta quella che cancella).
    if (!windowPlaced && spec.group == LAST_GROUP) {
      if (lastGroup >= 0) out += GalleryMenuEntry.GroupBreak
      out += checkNotNull(newWindow)
      windowPlaced = true
      lastGroup = LAST_GROUP - 1
    }
    if (lastGroup >= 0 && spec.group != lastGroup) out += GalleryMenuEntry.GroupBreak
    lastGroup = spec.group
    out += GalleryMenuEntry.Item(label = stringResource(spec.label), onClick = { onAction(action) }, icon = spec.icon, destructive = spec.destructive)
  }
  if (!windowPlaced) {
    if (out.isNotEmpty()) out += GalleryMenuEntry.GroupBreak
    out += checkNotNull(newWindow)
  }
  return out
}

/** Il gruppo di chi chiude o toglie, in fondo al menu. */
private const val LAST_GROUP = 3
