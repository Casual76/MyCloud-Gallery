package dev.pampa.mycloudgallery.feature.map.regions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.map.contract.PlaceHit
import dev.pampa.mycloudgallery.core.map.contract.RegionId
import dev.pampa.mycloudgallery.core.map.contract.RegionSpec
import dev.pampa.mycloudgallery.feature.map.nav.MapDependencies
import javax.inject.Inject

// LA ROTTA DI "ZONE SENZA INTERNET" (Fase 9, P5): lega il controllore e la pagina. Nessuna logica propria. Il nome del file non comincia con `RegionDownload`: la conferma dello scaricamento
// non si costruisce qui (solo in `RegionDownloadSheet.kt`, dal tocco sul foglio).

@HiltViewModel
class RegionsViewModel @Inject constructor(deps: MapDependencies) : ViewModel() {
  internal val controller: RegionsController = RegionsController(deps.regions, deps.placeSearch, viewModelScope)

  /** Vero se la ricerca dei luoghi e' legata (altrimenti la pagina "attorno a un luogo" dice che l'elenco non c'e'). */
  internal val placeSearchAvailable: Boolean = deps.hasPlaceSearch
}

@Composable
fun RegionsRoute(
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: RegionsViewModel = hiltViewModel(),
) {
  val controller = viewModel.controller
  val ui by controller.ui.collectAsState()
  val available = viewModel.placeSearchAvailable
  val handlers = remember(controller, onBack, available) {
    object : RegionsHandlers {
      override fun onBack() = onBack()
      override fun onCatalog(spec: RegionSpec) = controller.onCatalog(spec)
      override fun onDownloaded(id: RegionId) = controller.onDownloaded(id)
      override fun onOpenAround() = controller.onOpenAround(available)
      override fun onCloseAround() = controller.onCloseAround()
      override fun onQuery(text: String) = controller.onQuery(text)
      override fun onPick(hit: PlaceHit) = controller.onPick(hit)
      override fun onChangePlace() = controller.onChangePlace()
      override fun onRadius(offer: RadiusOffer) = controller.onRadius(offer)
      override fun onDismissMessage() = controller.onDismissMessage()
      override fun onConfirm() = controller.onConfirm()
      override fun onDismiss() = controller.onDismiss()
      override fun onResume() = controller.onResume()
      override fun onUpdate() = controller.onUpdate()
      override fun onAskDelete() = controller.onAskDelete()
      override fun onDelete() = controller.onDelete()
    }
  }
  RegionsScreen(ui, handlers, modifier)
}
