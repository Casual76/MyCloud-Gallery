package dev.pampa.mycloudgallery.feature.map.privacy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.feature.map.contract.MapActions
import dev.pampa.mycloudgallery.feature.map.nav.MapDependencies
import javax.inject.Inject

// LA ROTTA DI IMPOSTAZIONI -> MAPPA (Fase 9, P4): lega il controllore e l'uscita verso `:app` ("Zone senza Internet", di P5). Nessuna logica propria. Il nome del file comincia con `MapPrivacy`: i file di
// questa cartella con quel nome sono i soli ammessi a costruire la scelta di riaccendere (Phase9ArchitectureTest); questo non la costruisce, passa dal controllore.

@HiltViewModel
class MapPrivacyViewModel @Inject constructor(deps: MapDependencies) : ViewModel() {
  val controller: MapPrivacyController = MapPrivacyController(deps.settings, deps.regions, viewModelScope)
}

@Composable
fun MapPrivacyRoute(
  actions: MapActions,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: MapPrivacyViewModel = hiltViewModel(),
) {
  val controller = viewModel.controller
  val state by controller.state.collectAsState()
  val handlers = remember(controller, actions, onBack) {
    object : MapPrivacyHandlers {
      override fun onBack() = onBack()

      override fun onOnline(on: Boolean) = controller.setOnline(on)

      override fun onWifiOnly(on: Boolean) = controller.setWifiOnly(on)

      override fun onOpenRegions() = actions.openRegions()
    }
  }
  MapPrivacyScreen(state, handlers, modifier)
}
