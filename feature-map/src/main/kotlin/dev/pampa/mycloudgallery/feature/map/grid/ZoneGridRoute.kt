package dev.pampa.mycloudgallery.feature.map.grid

import androidx.compose.runtime.Composable
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
import dev.pampa.mycloudgallery.core.map.contract.CellKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.map.contract.MapActions
import dev.pampa.mycloudgallery.feature.map.contract.MapThumbnails
import dev.pampa.mycloudgallery.feature.map.contract.ZoneGridState
import dev.pampa.mycloudgallery.feature.map.nav.MapDependencies
import dev.pampa.mycloudgallery.feature.map.nav.PlaceDestination
import dev.pampa.mycloudgallery.feature.map.nav.ZoneDestination
import javax.inject.Inject

// LE ROTTE DELLA GRIGLIA DI UNA ZONA E DI UN LUOGO (Fase 9, P4): legano il controllore e il viewer (la richiesta va a `:app`, che lo ospita: feature-map non lo conosce). Una zona e' la sua cella
// (livello e prefisso della chiave di Morton, mai una coordinata chiara); un livello o un prefisso che non stanno nella griglia (un dato vecchio o ostile) aprono una pagina "nessuna foto qui".

/** La cella di una rotta: null se livello e prefisso non sono una cella della griglia. */
internal fun cellOf(level: Int, prefix: Long): CellKey? = runCatching { CellKey(level, prefix) }.getOrNull()

@HiltViewModel
class ZoneGridViewModel @Inject constructor(savedStateHandle: SavedStateHandle, deps: MapDependencies) : ViewModel() {
  private val route = savedStateHandle.toRoute<ZoneDestination>()

  val controller: ZoneGridController? = cellOf(route.level, route.prefix)?.let {
    ZoneGridController(ZoneTarget.Cell(it), deps.points, deps.visible, deps.places, deps.filter, viewModelScope)
  }
}

@HiltViewModel
class PlaceGridViewModel @Inject constructor(savedStateHandle: SavedStateHandle, deps: MapDependencies) : ViewModel() {
  val controller: ZoneGridController = ZoneGridController(
    ZoneTarget.Place(savedStateHandle.toRoute<PlaceDestination>().placeId), deps.points, deps.visible, deps.places, deps.filter, viewModelScope,
  )
}

@Composable
fun ZoneGridRoute(
  actions: MapActions,
  thumbnails: MapThumbnails,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: ZoneGridViewModel = hiltViewModel(),
) {
  val controller = viewModel.controller
  if (controller == null) {
    ZoneGridScreen(ZoneGridState.Empty, thumbnails, backOnly(onBack), modifier)
    return
  }
  val state by controller.state.collectAsState()
  val handlers = remember(controller, actions, onBack) { handlersOf(controller, actions, onBack) }
  ZoneGridScreen(state, thumbnails, handlers, modifier)
}

@Composable
fun PlaceGridRoute(
  actions: MapActions,
  thumbnails: MapThumbnails,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: PlaceGridViewModel = hiltViewModel(),
) {
  val controller = viewModel.controller
  val state by controller.state.collectAsState()
  val handlers = remember(controller, actions, onBack) { handlersOf(controller, actions, onBack) }
  ZoneGridScreen(state, thumbnails, handlers, modifier)
}

private fun backOnly(onBack: () -> Unit): ZoneGridHandlers = object : ZoneGridHandlers {
  override fun onBack() = onBack()

  override fun onOpenPhoto(media: MediaId) = Unit

  override fun onRetry() = Unit
}

/** Il tocco su una foto apre il viewer **su questa vista**, dalla foto toccata: scorrere nel viewer e' scorrere la griglia (anche per i media solo sul NAS, E-MAP6). */
private fun handlersOf(controller: ZoneGridController, actions: MapActions, onBack: () -> Unit): ZoneGridHandlers = object : ZoneGridHandlers {
  override fun onBack() = onBack()

  override fun onOpenPhoto(media: MediaId) {
    val ready = controller.state.value as? ZoneGridState.Ready ?: return
    actions.openViewer(ready.view, media)
  }

  override fun onRetry() = controller.retry()
}
