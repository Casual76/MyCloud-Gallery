package dev.pampa.mycloudgallery.feature.albums.choose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.HiddenChoice
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.feature.albums.contract.AddToAlbumRequest
import dev.pampa.mycloudgallery.feature.albums.contract.ShareSheetState
import dev.pampa.mycloudgallery.feature.albums.nav.AlbumDependencies
import dev.pampa.mycloudgallery.feature.albums.share.ShareSheet
import dev.pampa.mycloudgallery.feature.albums.share.ShareSheetHandlers
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumHost
import javax.inject.Inject

// "AGGIUNGI AD ALBUM" DA OGNI PUNTO D'INGRESSO (Fase 7, P10; D35): `:app` mostra [AddToAlbumHost] sopra la schermata da cui l'utente ha scelto "Aggiungi ad
// album" (selezione in Libreria, menu ⋮ del viewer, Cerca, condivisione in arrivo) con la richiesta ([AddToAlbumRequest]): non e' una rotta, perche' i
// media da aggiungere possono essere migliaia e una rotta non li porta. Quando l'utente ha finito (o rinuncia) chiama [onDismiss].

@HiltViewModel
class AddToAlbumViewModel @Inject constructor(private val ports: AlbumDependencies) : ViewModel() {
  private var current: Pair<AddToAlbumRequest, AddToAlbumController>? = null

  /** Il controllore della richiesta: la stessa richiesta ritrova lo stesso (la rotazione dello schermo non lo rifa). */
  fun controllerFor(request: AddToAlbumRequest): AddToAlbumController {
    current?.let { (known, controller) -> if (known === request) return controller }
    val controller = AddToAlbumController(request, ports.albums, ports.shared, ports.visible, ports.runner, ports.clock, viewModelScope)
    current = request to controller
    return controller
  }
}

@Composable
fun AddToAlbumHost(
  request: AddToAlbumRequest,
  onDismiss: () -> Unit,
  onOpenAlbum: (AlbumId) -> Unit,
  viewModel: AddToAlbumViewModel = hiltViewModel(),
) {
  val controller = viewModel.controllerFor(request)
  LaunchedEffect(controller) { controller.start() }
  val ui by controller.ui.collectAsState()
  val shareState by controller.share.state.collectAsState()
  // Il foglio di conferma ha avviato la condivisione: si chiude e questo foglio dice che e' partita.
  LaunchedEffect(shareState is ShareSheetState.Started) {
    if (shareState is ShareSheetState.Started) {
      val album = controller.share.startedAlbum
      controller.share.dismiss()
      album?.let(controller::onShareStarted)
    }
  }
  val handlers = remember(controller) {
    object : AddToAlbumHandlers {
      override fun onChoose(album: Album) = controller.add(album)

      override fun onNewAlbum(title: String) = controller.addToNewAlbum(title)

      override fun onNewSharedAlbum(title: String) = controller.addToNewSharedAlbum(title)

      override fun onOpenAlbum(album: AlbumId) {
        onDismiss()
        onOpenAlbum(album)
      }

      override fun onDismiss() = onDismiss()

      override fun onDismissMessage() = controller.dismissMessage()
    }
  }
  AlbumHost {
    AddToAlbumSheet(ui = ui, hidden = shareState != null, handlers = handlers)
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
