package dev.pampa.mycloudgallery.feature.people.review

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
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.people.contract.FaceReviewState
import dev.pampa.mycloudgallery.feature.people.contract.PeopleCrops
import dev.pampa.mycloudgallery.feature.people.contract.ReviewPurpose
import dev.pampa.mycloudgallery.feature.people.nav.PeopleDependencies
import dev.pampa.mycloudgallery.feature.people.nav.ReviewDestination
import dev.pampa.mycloudgallery.feature.people.ui.PeopleHost
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

// LA ROTTA DI "CONTROLLA LE FOTO" E "DIVIDI" (Fase 8, P9): lega il controllore alla griglia dei volti. Finita l'azione torna alla pagina della persona;
// dopo "Dividi" apre la persona nuova (dove si da' il nome, perche' con meno di tre foto non compare in nessun elenco).

@HiltViewModel
class FaceReviewViewModel @Inject constructor(savedStateHandle: SavedStateHandle, ports: PeopleDependencies) : ViewModel() {
  private val route = savedStateHandle.toRoute<ReviewDestination>()

  val person: PersonId? = PersonId.parse(route.id)

  val controller: FaceReviewController? = person?.let {
    FaceReviewController(it, if (route.split) ReviewPurpose.SPLIT else ReviewPurpose.CHECK, ports.people, ports.faces, viewModelScope)
  }

  /** Il nome della persona ("Non e' Anna"): quello di adesso, null se non ce l'ha. */
  val name: StateFlow<String?> = (person?.let { ports.people.person(it).map { view -> view?.card?.name } } ?: flowOf(null))
    .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

@Composable
fun FaceReviewRoute(
  crops: PeopleCrops,
  onBack: () -> Unit,
  onOpenPerson: (PersonId) -> Unit,
  modifier: Modifier = Modifier,
  viewModel: FaceReviewViewModel = hiltViewModel(),
) {
  val controller = viewModel.controller
  val name by viewModel.name.collectAsState()
  if (controller == null) {
    LaunchedEffect(Unit) { onBack() }
    return
  }
  val ui by controller.ui.collectAsState()
  // Finita l'azione: dopo "Dividi" si apre la persona nuova, dopo "Controlla le foto" si torna indietro.
  LaunchedEffect(ui.state is FaceReviewState.Done, ui.openNext) {
    if (ui.state is FaceReviewState.Done) {
      val next = ui.openNext
      if (next != null) onOpenPerson(next) else onBack()
    }
  }
  val handlers = remember(controller, onBack) {
    val goBack = onBack
    object : FaceReviewHandlers {
      override fun onBack() = goBack()

      override fun onToggle(face: FaceLocator) = controller.toggle(face)

      override fun onReject() = controller.rejectSelected()

      override fun onRejectOne(face: FaceLocator) = controller.rejectOne(face)

      override fun onIgnore() = controller.ignoreSelected()

      override fun onSplit() = controller.split()

      override fun onDismissMessage() = controller.dismissMessage()
    }
  }
  PeopleHost { FaceReviewScreen(ui = ui, personName = name, crops = crops, handlers = handlers, modifier = modifier) }
}
