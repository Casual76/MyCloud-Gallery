package dev.pampa.mycloudgallery.feature.people.page

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
import dev.pampa.mycloudgallery.core.faces.contract.MergeProposal
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.people.contract.PeopleActions
import dev.pampa.mycloudgallery.feature.people.contract.PeopleCrops
import dev.pampa.mycloudgallery.feature.people.contract.PeopleThumbnails
import dev.pampa.mycloudgallery.feature.people.contract.PersonAction
import dev.pampa.mycloudgallery.feature.people.contract.PersonPageState
import dev.pampa.mycloudgallery.feature.people.nav.PeopleDependencies
import dev.pampa.mycloudgallery.feature.people.nav.PersonDestination
import dev.pampa.mycloudgallery.feature.people.ui.PeopleHost
import javax.inject.Inject

// LA ROTTA DELLA PAGINA DI UNA PERSONA (Fase 8, P9): lega il controllore, i fogli (nome, unione, "Dimentica", copertina) e le uscite verso `:app`
// (il viewer su una vista di persona, "Controlla le foto" e "Dividi", "Crea album smart"). Nessuna logica propria: cio' che la pagina offre lo
// decide `PersonPageController`.

@HiltViewModel
class PersonPageViewModel @Inject constructor(savedStateHandle: SavedStateHandle, ports: PeopleDependencies) : ViewModel() {
  /** Null se l'id della rotta non e' un id di persona (un dato ostile o vecchio): la pagina dice "non c'e'". */
  val id: PersonId? = PersonId.parse(savedStateHandle.toRoute<PersonDestination>().id)

  val controller: PersonPageController? = id?.let { PersonPageController(it, ports.people, ports.faces, viewModelScope) }
}

/** Le uscite della pagina: la navigazione dentro le persone. */
class PersonPageExits(
  val onBack: () -> Unit,
  val onReview: (person: PersonId, split: Boolean) -> Unit,
)

@Composable
fun PersonPageRoute(
  actions: PeopleActions,
  thumbnails: PeopleThumbnails,
  crops: PeopleCrops,
  exits: PersonPageExits,
  modifier: Modifier = Modifier,
  viewModel: PersonPageViewModel = hiltViewModel(),
) {
  val controller = viewModel.controller
  if (controller == null) {
    PeopleHost { PersonPageScreen(PersonPageUiNotFound, thumbnails, crops, NoPersonPageHandlers.withBack(exits.onBack), modifier) }
    return
  }
  val ui by controller.ui.collectAsState()
  val handlers = remember(controller, actions, exits) {
    val goBack = exits.onBack
    object : PersonPageHandlers {
      override fun onBack() = goBack()

      override fun onAction(action: PersonAction) {
        val page = controller.ui.value.page
        val header = (page as? PersonPageState.Ready)?.header ?: (page as? PersonPageState.Hidden)?.header
        when (action) {
          PersonAction.RENAME -> controller.startNaming()
          PersonAction.MERGE -> controller.startMerge()
          PersonAction.REVIEW -> header?.let { exits.onReview(it.id, false) }
          PersonAction.SPLIT -> header?.let { exits.onReview(it.id, true) }
          PersonAction.SET_COVER -> controller.openCovers()
          PersonAction.HIDE -> controller.hide()
          PersonAction.UNHIDE -> controller.unhide()
          PersonAction.FORGET -> controller.askForget()
          PersonAction.SMART_ALBUM -> header?.let { actions.createSmartAlbum(it.id, it.name) }
        }
      }

      override fun onOpenPhoto(media: MediaId) {
        val ready = controller.ui.value.page as? PersonPageState.Ready ?: return
        actions.openViewer(ready.view, media)
      }

      override fun onOpenProposal(proposal: MergeProposal) = controller.openProposal(proposal)

      override fun onSaveName(text: String) = controller.saveName(text)

      override fun onRemoveName() = controller.removeName()

      override fun onDismissName() = controller.dismissNaming()

      override fun onConfirmForget() = controller.confirmForget()

      override fun onDismissForget() = controller.dismissForget()

      override fun onPickCover(face: FaceLocator?) = controller.pickCover(face)

      override fun onDismissCovers() = controller.dismissCovers()

      override fun onDismissMessage() = controller.dismissMessage()

      override fun onToggleCandidate(id: PersonId) = controller.toggleCandidate(id)

      override fun onContinueFromChoose() = controller.continueFromChoose()

      override fun onPick(id: PersonId) = controller.pickName(id)

      override fun onContinueFromName() = controller.continueFromName()

      override fun onConfirmMerge() = controller.confirmMerge()

      override fun onRejectProposal() = controller.rejectProposal()

      override fun onDismissMerge() = controller.dismissMerge()
    }
  }
  PeopleHost { PersonPageScreen(ui = ui, thumbnails = thumbnails, crops = crops, handlers = handlers, modifier = modifier) }
}

/** La pagina di un id che non e' di una persona: "non c'e'". */
private val PersonPageUiNotFound = dev.pampa.mycloudgallery.feature.people.contract.PersonPageUi(PersonPageState.NotFound)

/** Gli stessi non-fare di [NoPersonPageHandlers], ma con "indietro" che torna davvero (la pagina "non c'e'" si lascia). */
private fun PersonPageHandlers.withBack(back: () -> Unit): PersonPageHandlers = object : PersonPageHandlers by this {
  override fun onBack() = back()
}
