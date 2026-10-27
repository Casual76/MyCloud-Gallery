package dev.pampa.mycloudgallery.feature.albums.trips

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.albums.contract.TripId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.feature.albums.R
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumThumbnails
import dev.pampa.mycloudgallery.feature.albums.nav.AlbumDependencies
import javax.inject.Inject

// LA ROTTA DELLE PROPOSTE DI VIAGGIO (Fase 7, P10): "Crea album" apre l'album nato (uno smart con periodo e luogo del viaggio, regole gia' compilate).

@HiltViewModel
class TripsViewModel @Inject constructor(ports: AlbumDependencies) : ViewModel() {
  val controller: TripsController = TripsController(ports.trips, ports.clock, viewModelScope)
}

@Composable
fun TripsRoute(
  thumbnails: AlbumThumbnails,
  onBack: () -> Unit,
  onOpenAlbum: (AlbumId) -> Unit,
  modifier: Modifier = Modifier,
  viewModel: TripsViewModel = hiltViewModel(),
) {
  val controller = viewModel.controller
  val state by controller.state.collectAsState()
  var creating by remember { mutableStateOf<TripId?>(null) }
  var notice by remember { mutableStateOf<String?>(null) }
  val notFound = stringResource(R.string.trips_not_found)
  val handlers = remember(controller, notFound) {
    object : TripsHandlers {
      override fun onBack() = onBack()

      override fun onCreate(id: TripId) {
        creating = id
        controller.accept(id) { result ->
          creating = null
          when (result) {
            is TripAccepted.Created -> onOpenAlbum(result.album)
            is TripAccepted.AlreadyAlbum -> onOpenAlbum(result.album)
            // VG-2: prima il tocco non faceva e non diceva niente.
            TripAccepted.NotFound -> notice = notFound
          }
        }
      }

      override fun onDismiss(id: TripId) = controller.dismiss(id)

      override fun onShowOlder() = controller.showOlder()
    }
  }
  TripsScreen(state = state, thumbnails = thumbnails, handlers = handlers, modifier = modifier, creating = creating, notice = notice, onDismissNotice = { notice = null })
}
