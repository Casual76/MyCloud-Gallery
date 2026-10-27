package dev.pampa.mycloudgallery.feature.albums.editor

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
import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import dev.pampa.mycloudgallery.core.albums.contract.PeopleRule
import dev.pampa.mycloudgallery.core.albums.contract.PlaceAlternative
import dev.pampa.mycloudgallery.core.albums.contract.SmartLimits
import dev.pampa.mycloudgallery.core.albums.contract.SmartMediaType
import dev.pampa.mycloudgallery.core.albums.contract.SmartSource
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumActions
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumThumbnails
import dev.pampa.mycloudgallery.feature.albums.nav.AlbumDependencies
import dev.pampa.mycloudgallery.feature.albums.nav.SmartEditorDestination
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumHost
import javax.inject.Inject

// LA ROTTA DELL'EDITOR DELLE REGOLE (Fase 7, P10): crea un album smart dal titolo (`SmartEditorDestination.title`) o cambia le regole di uno che c'e'
// (`SmartEditorDestination.album`). Le foto d'esempio le sceglie il selettore di `:app` (`AlbumActions.pickMedia`).

@HiltViewModel
class SmartEditorViewModel @Inject constructor(savedStateHandle: SavedStateHandle, ports: AlbumDependencies) : ViewModel() {
  private val route = savedStateHandle.toRoute<SmartEditorDestination>()

  /** L'album di cui si cambiano le regole, o null se si sta creando. */
  val album: AlbumId? = route.album?.let(::AlbumId)

  /** Fase 8: dalla pagina persona ("Crea album smart") la regola parte con quella persona (`SmartEditorDestination.person`); un id illeggibile vale "nessuna". */
  private val startWith: PeopleRule? = route.person?.let { PersonId.fromBase32(it) }?.let { PeopleRule(listOf(it), PeopleMode.ANY) }

  val controller: SmartEditorController = SmartEditorController(
    mode = if (album != null) SmartEditorMode.Edit(album) else SmartEditorMode.Create(route.title.orEmpty(), startWith = startWith),
    interpreter = ports.interpreter,
    engine = ports.engine,
    albums = ports.albums,
    clock = ports.clock,
    scope = viewModelScope,
    people = ports.peopleChoices,
  )
}

@Composable
fun SmartEditorRoute(
  actions: AlbumActions,
  thumbnails: AlbumThumbnails,
  onBack: () -> Unit,
  onSaved: (AlbumId) -> Unit,
  modifier: Modifier = Modifier,
  viewModel: SmartEditorViewModel = hiltViewModel(),
) {
  val controller = viewModel.controller
  val ui by controller.ui.collectAsState()
  LaunchedEffect(controller) { controller.start() }
  LaunchedEffect(ui.savedAlbum) { ui.savedAlbum?.let(onSaved) }
  val handlers = remember(controller, actions) {
    object : SmartEditorHandlers {
      override fun onBack() = onBack()

      override fun onSeries(keep: Set<LocalDayRange>) = controller.setSeries(keep)

      override fun onPeriod(range: LocalDayRange) = controller.setPeriod(range)

      override fun onRemovePeriod() = controller.removePeriod()

      override fun onPlace(alternative: PlaceAlternative) = controller.choosePlace(alternative)

      override fun onRadius(km: Int) = controller.setRadius(km)

      override fun onRemovePlace() = controller.removePlace()

      override fun onTogglePerson(id: PersonId) = controller.togglePerson(id)

      override fun onRemovePerson(id: PersonId) = controller.removePerson(id)

      override fun onPeopleMode(mode: PeopleMode) = controller.setPeopleMode(mode)

      override fun onRemovePeople() = controller.removePeople()

      override fun onText(text: String) = controller.setText(text)

      override fun onAddExamples() {
        val room = SmartLimits.MAX_EXAMPLES - (controller.ui.value.rules?.examples?.size ?: 0)
        // AS-8: le foto d'esempio non sono "Aggiungi foto" a un album: `forAlbum` e' null anche in modifica (altrimenti il selettore si intitolava "Aggiungi foto").
        if (room > 0) actions.pickMedia(null, room) { picked -> controller.addExamples(picked) }
      }

      override fun onRemoveExample(media: MediaId) = controller.removeExample(media)

      override fun onSource(source: SmartSource) = controller.toggleSource(source)

      override fun onType(type: SmartMediaType) = controller.toggleType(type)

      override fun onSave() = controller.save()
    }
  }
  AlbumHost { SmartEditorScreen(ui = ui, thumbnails = thumbnails, handlers = handlers, modifier = modifier) }
}
