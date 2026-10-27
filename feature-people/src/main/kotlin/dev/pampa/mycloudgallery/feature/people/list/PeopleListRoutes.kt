package dev.pampa.mycloudgallery.feature.people.list

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.faces.contract.MergeProposal
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.people.contract.PeopleCrops
import dev.pampa.mycloudgallery.feature.people.nav.PeopleDependencies
import dev.pampa.mycloudgallery.feature.people.ui.PeopleHost
import javax.inject.Inject

// LE ROTTE DI "TUTTE LE PERSONE" E DI "ALTRI VOLTI" (Fase 8, P9): legano il controllore alla schermata e le uscite alla navigazione di `:app`
// (la pagina di una persona, "Altri volti", le persone nascoste). Nessuna logica propria.

@HiltViewModel
class PeopleListViewModel @Inject constructor(ports: PeopleDependencies) : ViewModel() {
  val controller: PeopleListController = PeopleListController(ports.people, viewModelScope)
}

@Composable
fun PeopleListRoute(
  crops: PeopleCrops,
  onOpenPerson: (PersonId) -> Unit,
  onOpenOthers: () -> Unit,
  onOpenHidden: () -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: PeopleListViewModel = hiltViewModel(),
) {
  val controller = viewModel.controller
  val ui by controller.ui.collectAsState()
  val handlers = remember(controller, onOpenPerson, onOpenOthers, onOpenHidden, onBack) {
    // Nel corpo dell'oggetto `onBack()` sarebbe il suo stesso metodo: si chiamano le uscite con altri nomi.
    val goBack = onBack
    val goPerson = onOpenPerson
    val goOthers = onOpenOthers
    val goHidden = onOpenHidden
    object : PeopleListHandlers {
      override fun onBack() = goBack()

      override fun onOpenPerson(id: PersonId) = goPerson(id)

      override fun onOpenOthers() = goOthers()

      override fun onOpenHidden() = goHidden()

      override fun onSetSelecting(on: Boolean) = controller.setSelecting(on)

      override fun onStartWith(id: PersonId) = controller.startWith(id)

      override fun onToggle(id: PersonId) = controller.toggle(id)

      override fun onMergeSelected() = controller.mergeSelected()

      override fun onOpenProposal(proposal: MergeProposal) = controller.openProposal(proposal)

      override fun onDismissMessage() = controller.dismissMessage()

      override fun onToggleCandidate(id: PersonId) = Unit

      override fun onContinueFromChoose() = Unit

      override fun onPick(id: PersonId) = controller.pickName(id)

      override fun onContinueFromName() = controller.continueFromName()

      override fun onConfirmMerge() = controller.confirmMerge()

      override fun onRejectProposal() = controller.rejectProposal()

      override fun onDismissMerge() = controller.dismissMerge()
    }
  }
  PeopleHost { PeopleListScreen(ui = ui, crops = crops, handlers = handlers, modifier = modifier) }
}

@HiltViewModel
class OthersViewModel @Inject constructor(ports: PeopleDependencies) : ViewModel() {
  val controller: OthersController = OthersController(ports.people, ports.faces, viewModelScope)
}

@Composable
fun OthersRoute(
  crops: PeopleCrops,
  onOpenPerson: (PersonId) -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: OthersViewModel = hiltViewModel(),
) {
  val controller = viewModel.controller
  val ui by controller.ui.collectAsState()
  val handlers = remember(controller, onOpenPerson, onBack) {
    val goBack = onBack
    val goPerson = onOpenPerson
    object : OthersHandlers {
      override fun onBack() = goBack()

      override fun onOpenPerson(id: PersonId) = goPerson(id)

      override fun onSetSelecting(on: Boolean) = controller.setSelecting(on)

      override fun onStartWith(id: PersonId) = controller.startWith(id)

      override fun onToggle(id: PersonId) = controller.toggle(id)

      override fun onMergeSelected() = controller.mergeSelected()

      override fun onAskForget() = controller.askForget()

      override fun onConfirmForget() = controller.confirmForget()

      override fun onDismissForget() = controller.dismissForget()

      override fun onDismissMessage() = controller.dismissMessage()

      override fun onToggleCandidate(id: PersonId) = Unit

      override fun onContinueFromChoose() = Unit

      override fun onPick(id: PersonId) = controller.pickName(id)

      override fun onContinueFromName() = controller.continueFromName()

      override fun onConfirmMerge() = controller.confirmMerge()

      override fun onRejectProposal() = Unit

      override fun onDismissMerge() = controller.dismissMerge()
    }
  }
  PeopleHost { OthersScreen(ui = ui, crops = crops, handlers = handlers, modifier = modifier) }
}
