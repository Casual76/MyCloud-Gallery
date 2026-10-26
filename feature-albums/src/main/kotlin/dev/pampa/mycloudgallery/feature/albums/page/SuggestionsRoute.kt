package dev.pampa.mycloudgallery.feature.albums.page

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.albums.contract.HiddenChoice
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumActions
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumThumbnails
import dev.pampa.mycloudgallery.feature.albums.contract.ShareSheetState
import dev.pampa.mycloudgallery.feature.albums.nav.AlbumDependencies
import dev.pampa.mycloudgallery.feature.albums.nav.SuggestionsDestination
import dev.pampa.mycloudgallery.feature.albums.share.ShareSheet
import dev.pampa.mycloudgallery.feature.albums.share.ShareSheetHandlers
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumHost
import javax.inject.Inject

// LA ROTTA DI "SUGGERITE" E "DA CONDIVIDERE" (Fase 7, P10): lega il controllore, il foglio di conferma (per "Da condividere") e l'uscita verso il viewer.

@HiltViewModel
class SuggestionsViewModel @Inject constructor(savedStateHandle: SavedStateHandle, ports: AlbumDependencies) : ViewModel() {
  private val route = savedStateHandle.toRoute<SuggestionsDestination>()

  val id: AlbumId = AlbumId(route.id)

  val controller: SuggestionsController = SuggestionsController(
    id, if (route.toShare) SuggestionKind.TO_SHARE else SuggestionKind.SUGGESTED, ports.albums, ports.choices, ports.runner, ports.clock, viewModelScope,
  )
}

@Composable
fun SuggestionsRoute(
  actions: AlbumActions,
  thumbnails: AlbumThumbnails,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: SuggestionsViewModel = hiltViewModel(),
) {
  val controller = viewModel.controller
  val ui by controller.ui.collectAsState()
  val shareState by controller.share.state.collectAsState()
  LaunchedEffect(shareState is ShareSheetState.Started) { if (shareState is ShareSheetState.Started) controller.share.dismiss() }
  val handlers = remember(controller, actions) {
    object : SuggestionsHandlers {
      override fun onBack() = onBack()

      override fun onToggleSelecting() = controller.toggleSelecting()

      override fun onToggle(media: MediaId) = controller.toggle(media)

      override fun onOpen(media: MediaId) {
        val view = controller.ui.value.view ?: return
        actions.openViewer(view, media)
      }

      override fun onSelectAll() = controller.selectAll()

      override fun onClear() = controller.clearSelection()

      override fun onAccept() = controller.accept()

      override fun onExclude() = controller.exclude()

      override fun onShare() = controller.shareSelected()

      override fun onDismissMessage() = controller.dismissMessage()
    }
  }
  AlbumHost {
    SuggestionsScreen(ui = ui, thumbnails = thumbnails, handlers = handlers, modifier = modifier)
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
