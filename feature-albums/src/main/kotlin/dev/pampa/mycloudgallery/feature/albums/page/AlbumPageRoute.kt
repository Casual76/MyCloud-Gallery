package dev.pampa.mycloudgallery.feature.albums.page

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.CloseMode
import dev.pampa.mycloudgallery.core.albums.contract.OthersAfterClose
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumSort
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.albums.R
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumAction
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumActions
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumPageState
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumThumbnails
import dev.pampa.mycloudgallery.feature.albums.nav.AlbumPageDestination
import dev.pampa.mycloudgallery.feature.albums.nav.AlbumDependencies
import dev.pampa.mycloudgallery.feature.albums.share.CloseSheet
import dev.pampa.mycloudgallery.feature.albums.share.CloseSheetHandlers
import dev.pampa.mycloudgallery.feature.albums.share.ShareSheet
import dev.pampa.mycloudgallery.feature.albums.share.ShareSheetHandlers
import dev.pampa.mycloudgallery.feature.albums.contract.ShareSheetState
import dev.pampa.mycloudgallery.core.albums.contract.HiddenChoice
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumHost
import javax.inject.Inject

// LA ROTTA DELLA PAGINA DI UN ALBUM (Fase 7, P10): lega il controllore, i fogli (condivisione, chiusura, permessi, ordine, nome, conferme) e le uscite
// verso `:app` (viewer, selettore dei media, editor delle regole, pagina dei dubbi). Nessuna logica sua: quello che la pagina offre lo decide
// `AlbumPageController`, e cio' che e' permesso la tabella.

@HiltViewModel
class AlbumPageViewModel @Inject constructor(savedStateHandle: SavedStateHandle, ports: AlbumDependencies) : ViewModel() {
  val id: AlbumId = AlbumId(savedStateHandle.toRoute<AlbumPageDestination>().id)

  val controller: AlbumPageController = AlbumPageController(
    id, ports.albums, ports.shared, ports.choices, ports.runner, ports.people, ports.members, ports.pending, ports.clock, viewModelScope,
  )

  val people = ports.people
}

/** I fogli che la pagina apre da se' (non hanno un controllore: sono solo scelte o conferme). */
private enum class PageDialog { RENAME, SORT, DELETE, LEAVE, INHERIT, PERMISSIONS }

/** Le uscite della pagina verso `:app`: quello che questo modulo non sa fare da solo. */
class AlbumPageExits(
  val onBack: () -> Unit,
  val onBackToList: () -> Unit,
  val onOpenAlbum: (AlbumId) -> Unit,
  val onOpenRules: (AlbumId) -> Unit,
  val onOpenSuggestions: (AlbumId, SuggestionKind) -> Unit,
)

@Composable
fun AlbumPageRoute(
  actions: AlbumActions,
  thumbnails: AlbumThumbnails,
  exits: AlbumPageExits,
  modifier: Modifier = Modifier,
  fallbackTitle: String = "",
  viewModel: AlbumPageViewModel = hiltViewModel(),
) {
  val controller = viewModel.controller
  val id = viewModel.id
  val ui by controller.ui.collectAsState()
  val shareState by controller.share.state.collectAsState()
  val closeUi by controller.close.ui.collectAsState()
  val closed by controller.close.closed.collectAsState()
  val deleted by controller.deleted.collectAsState()
  var dialog by rememberSaveable { mutableStateOf<PageDialog?>(null) }

  // Una condivisione partita: il foglio ha finito, la pagina mostra la striscia di avanzamento.
  LaunchedEffect(shareState is ShareSheetState.Started) { if (shareState is ShareSheetState.Started) controller.share.dismiss() }
  // L'album eliminato (personale, o condiviso chiuso con "Elimina") non c'e' piu': si torna indietro.
  LaunchedEffect(deleted) { if (deleted) exits.onBack() }
  LaunchedEffect(closed) { if (closed?.second == CloseMode.DELETE) exits.onBack() }

  val handlers = remember(controller, actions, exits) {
    object : AlbumPageHandlers {
      override fun onBack() = exits.onBack()

      override fun onAction(action: AlbumAction) {
        when (action) {
          AlbumAction.ADD_MEDIA -> actions.pickMedia(id, Int.MAX_VALUE) { picked -> controller.add(picked) }
          AlbumAction.ADOPT_FOLDER_FILES -> controller.adoptFolderFiles()
          AlbumAction.RENAME -> dialog = PageDialog.RENAME
          AlbumAction.SET_COVER -> controller.startPickingCover()
          AlbumAction.SET_SORT -> dialog = PageDialog.SORT
          AlbumAction.SHOW_PERMISSIONS, AlbumAction.CHANGE_LEVEL -> dialog = PageDialog.PERMISSIONS
          AlbumAction.STOP_SHARING -> controller.close.open(id, CloseMode.STOP)
          AlbumAction.DELETE_ALBUM -> controller.close.open(id, CloseMode.DELETE)
          AlbumAction.LEAVE -> dialog = PageDialog.LEAVE
          AlbumAction.INHERIT -> dialog = PageDialog.INHERIT
          AlbumAction.SHARE -> controller.shareThisAlbum()
          AlbumAction.EDIT_RULES -> exits.onOpenRules(id)
          AlbumAction.FREEZE -> controller.freeze()
          AlbumAction.UNFREEZE -> controller.unfreeze()
          AlbumAction.DELETE_PERSONAL -> dialog = PageDialog.DELETE
        }
      }

      override fun onOpenItem(media: MediaId) {
        val content = (ui.page as? AlbumPageState.Ready)?.content ?: return
        actions.openViewer(content.items, media)
      }

      override fun onItemMenu(media: MediaId) = controller.openItemMenu(media)

      override fun onPickCover(media: MediaId) = controller.setCover(media)

      override fun onCancelPickCover() = controller.stopPickingCover()

      override fun onStrip(action: StripAction) {
        when (action) {
          StripAction.NONE -> Unit
          StripAction.OPEN_TO_SHARE -> exits.onOpenSuggestions(id, SuggestionKind.TO_SHARE)
          StripAction.OPEN_SUGGESTED -> exits.onOpenSuggestions(id, SuggestionKind.SUGGESTED)
          StripAction.ADOPT_FOLDER_FILES -> controller.adoptFolderFiles()
          StripAction.BRING_BACK_REJECTED -> controller.bringBackRejected()
          StripAction.OPEN_PERMISSIONS -> dialog = PageDialog.PERMISSIONS
          StripAction.USE_NEW_FOLDER -> controller.useNewFolder()
          StripAction.RESTORE_REVOKED -> controller.restoreRevoked()
        }
      }

      override fun onDismissMessage() = controller.dismissMessage()

      override fun onOpenCopy(album: AlbumId) = exits.onOpenAlbum(album)

      override fun onBackToList() = exits.onBackToList()
    }
  }
  val album = (ui.page as? AlbumPageState.Ready)?.content?.album
  val permissions = (ui.page as? AlbumPageState.Ready)?.permissions

  AlbumHost {
    AlbumPageScreen(ui = ui, fallbackTitle = fallbackTitle, thumbnails = thumbnails, handlers = handlers, modifier = modifier)
    ItemMenuSheet(
      menu = ui.itemMenu,
      creatorName = viewModel.people::nameOf,
      onSetCover = controller::setCover,
      onMoveToStart = controller::moveToStart,
      onMoveToEnd = controller::moveToEnd,
      onRemove = controller::remove,
      onBringBack = controller::bringBack,
      onDismiss = controller::closeItemMenu,
    )
    SortSheet(
      visible = dialog == PageDialog.SORT, current = (ui.page as? AlbumPageState.Ready)?.sort ?: AlbumSort.NEWEST_FIRST,
      onSelect = { sort ->
        controller.setSort(sort)
        dialog = null
      },
      onDismiss = { dialog = null },
    )
    RenameSheet(
      visible = dialog == PageDialog.RENAME, current = album?.title.orEmpty(),
      onRename = { title ->
        controller.rename(title)
        dialog = null
      },
      onDismiss = { dialog = null },
    )
    PermissionsSheet(
      visible = dialog == PageDialog.PERMISSIONS, info = permissions, creatorName = ui.creatorName,
      onSetLevel = controller::setLevel, onDismiss = { dialog = null },
    )
    ConfirmSheet(
      visible = dialog == PageDialog.DELETE, icon = Icons.Rounded.Delete, tone = GallerySheetTone.Destructive,
      title = stringResource(R.string.album_delete_title), message = stringResource(R.string.album_delete_message), confirm = stringResource(R.string.album_delete_confirm), destructive = true,
      onConfirm = {
        controller.deletePersonal()
        dialog = null
      },
      onDismiss = { dialog = null },
    )
    ConfirmSheet(
      visible = dialog == PageDialog.LEAVE, icon = Icons.Rounded.VisibilityOff, tone = GallerySheetTone.Caution,
      title = stringResource(R.string.album_leave_title, album?.title.orEmpty()), message = stringResource(R.string.album_leave_message),
      confirm = stringResource(R.string.album_leave_confirm), destructive = false,
      onConfirm = {
        controller.leave()
        dialog = null
        exits.onBackToList()
      },
      onDismiss = { dialog = null },
    )
    ConfirmSheet(
      visible = dialog == PageDialog.INHERIT, icon = Icons.Rounded.Verified, tone = GallerySheetTone.Brand,
      title = stringResource(R.string.album_inherit_title), message = stringResource(R.string.album_inherit_message, album?.title.orEmpty()),
      confirm = stringResource(R.string.album_inherit_confirm), destructive = false,
      onConfirm = {
        controller.inherit()
        dialog = null
      },
      onDismiss = { dialog = null },
    )
    CloseSheet(
      ui = closeUi, hidden = shareState != null,
      handlers = object : CloseSheetHandlers {
        override fun onChooseOthers(choice: OthersAfterClose) = controller.close.chooseOthers(choice)

        override fun onBringBack(on: Boolean) = controller.close.setBringBack(on)

        override fun onConfirm() = controller.close.confirm()

        override fun onDismiss() = controller.close.dismiss()
      },
    )
    ShareSheet(
      state = shareState,
      handlers = object : ShareSheetHandlers {
        override fun onConfirm() = controller.share.confirm()

        override fun onDismiss() = controller.share.dismiss()

        override fun onHiddenChoice(choice: HiddenChoice) = controller.share.setHiddenChoice(choice)

        override fun onLevel(level: ShareLevel) = controller.share.setLevel(level)
      },
    )
  }
}
