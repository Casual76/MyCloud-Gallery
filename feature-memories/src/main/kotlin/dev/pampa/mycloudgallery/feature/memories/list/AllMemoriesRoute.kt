package dev.pampa.mycloudgallery.feature.memories.list

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesThumbnails
import dev.pampa.mycloudgallery.feature.memories.nav.MemoriesDependencies
import dev.pampa.mycloudgallery.feature.memories.ui.MemoriesHost
import dev.pampa.mycloudgallery.feature.memories.ui.MemoryNames
import javax.inject.Inject

// LA ROTTA DI "TUTTI I RICORDI" (Fase 9, P9): lega il controllore e le uscite (la pagina di un ricordo). Nessuna logica propria.

@HiltViewModel
class AllMemoriesViewModel @Inject constructor(deps: MemoriesDependencies) : ViewModel() {
  val names: MemoryNames = deps.names
  val controller: AllMemoriesController = AllMemoriesController(deps.memories, deps.names, deps.filter, viewModelScope)
}

@Composable
fun AllMemoriesRoute(
  thumbnails: MemoriesThumbnails,
  onOpenMemory: (MemoryId) -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: AllMemoriesViewModel = hiltViewModel(),
) {
  val ui by viewModel.controller.ui.collectAsState()
  val handlers = remember(onOpenMemory, onBack) {
    object : AllMemoriesHandlers {
      override fun onBack() = onBack()

      override fun onOpen(id: MemoryId) = onOpenMemory(id)
    }
  }
  MemoriesHost { AllMemoriesScreen(ui, thumbnails, viewModel.names, handlers, modifier) }
}
