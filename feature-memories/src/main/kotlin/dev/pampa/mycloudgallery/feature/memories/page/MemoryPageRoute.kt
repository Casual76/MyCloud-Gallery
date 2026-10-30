package dev.pampa.mycloudgallery.feature.memories.page

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
import dev.pampa.mycloudgallery.core.memories.contract.LessLevel
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.memories.contract.MemoryIds
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesActions
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesThumbnails
import dev.pampa.mycloudgallery.feature.memories.contract.MemoryAction
import dev.pampa.mycloudgallery.feature.memories.contract.MemoryPageState
import dev.pampa.mycloudgallery.feature.memories.contract.MemoryPageUi
import dev.pampa.mycloudgallery.feature.memories.nav.MemoriesDependencies
import dev.pampa.mycloudgallery.feature.memories.nav.MemoryDestination
import dev.pampa.mycloudgallery.feature.memories.ui.MemoriesHost
import javax.inject.Inject

// LA ROTTA DELLA PAGINA DEL RICORDO (Fase 9, P9): lega il controllore, i fogli ("Mostra meno", "Non ricordarmi questo periodo") e le uscite verso
// `:app` (il viewer su una vista del ricordo, "Apri l'album", la presentazione). Nessuna logica propria: cio' che la pagina offre lo decide
// `MemoryPageController`.

/** L'id di un ricordo da una rotta: null se non e' un id (un dato ostile o vecchio: la pagina dice "non trovo questo ricordo"). */
internal fun parseMemoryId(text: String): MemoryId? = if (MemoryIds.isCanonical(text)) MemoryId(text) else null

@HiltViewModel
class MemoryPageViewModel @Inject constructor(savedStateHandle: SavedStateHandle, deps: MemoriesDependencies) : ViewModel() {
  val id: MemoryId? = parseMemoryId(savedStateHandle.toRoute<MemoryDestination>().id)

  val controller: MemoryPageController? = id?.let { MemoryPageController(it, deps.memories, deps.names, deps.filter, viewModelScope) }

  override fun onCleared() {
    controller?.close()
  }
}

/** Le uscite della pagina: la navigazione dentro i ricordi. */
class MemoryPageExits(
  val onBack: () -> Unit,
  val onPlay: (MemoryId) -> Unit,
)

@Composable
fun MemoryPageRoute(
  actions: MemoriesActions,
  thumbnails: MemoriesThumbnails,
  exits: MemoryPageExits,
  modifier: Modifier = Modifier,
  viewModel: MemoryPageViewModel = hiltViewModel(),
) {
  val controller = viewModel.controller
  if (controller == null) {
    MemoriesHost { MemoryPageScreen(MemoryPageUi(MemoryPageState.NotFound), thumbnails, NoMemoryPageHandlers.withBack(exits.onBack), modifier) }
    return
  }
  val ui by controller.ui.collectAsState()
  val id = checkNotNull(viewModel.id)
  val handlers = remember(controller, actions, exits) {
    object : MemoryPageHandlers {
      override fun onBack() = exits.onBack()

      override fun onPlay() = exits.onPlay(id)

      override fun onAction(action: MemoryAction) {
        when (action) {
          MemoryAction.PLAY -> exits.onPlay(id)
          MemoryAction.LESS_PERSON -> controller.lessPerson()
          MemoryAction.NEVER_DAYS -> controller.neverDays()
          MemoryAction.DISMISS -> controller.dismissMemory()
          MemoryAction.OPEN_ALBUM -> controller.album()?.let(actions::openAlbum)
        }
      }

      override fun onOpenPhoto(media: MediaId) {
        val ready = controller.ui.value.page as? MemoryPageState.Ready ?: return
        actions.openViewer(ready.view, media)
      }

      override fun onUndo() = controller.undo()

      override fun onDismissMessage() = controller.dismissMessage()

      override fun onChooseLess(level: LessLevel?) = controller.chooseLess(level)

      override fun onAdjustDays(fromDelta: Int, toDelta: Int) = controller.adjustDays(fromDelta, toDelta)

      override fun onOnlyFirstDay() = controller.onlyFirstDay()

      override fun onWholePeriod() = controller.resetDays()

      override fun onConfirmDays() = controller.confirmDays()

      override fun onDismissSheet() = controller.dismissSheet()
    }
  }
  MemoriesHost { MemoryPageScreen(ui = ui, thumbnails = thumbnails, handlers = handlers, modifier = modifier) }
}

/** Gli stessi non-fare di [NoMemoryPageHandlers], ma con "indietro" che torna davvero (la pagina "non trovo" si lascia). */
private fun MemoryPageHandlers.withBack(back: () -> Unit): MemoryPageHandlers = object : MemoryPageHandlers by this {
  override fun onBack() = back()
}
