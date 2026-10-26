package dev.pampa.mycloudgallery.feature.albums.list

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.AlbumListSort
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumNotice
import dev.pampa.mycloudgallery.core.albums.contract.TitleProblem
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumPeople
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumThumbnails
import dev.pampa.mycloudgallery.feature.albums.nav.AlbumDependencies
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumHost
import javax.inject.Inject
import kotlinx.coroutines.launch

// LA ROTTA DELL'ELENCO DEGLI ALBUM (Fase 7, P10): Raccolte -> Album e "Tutti gli album". Lega il controllore, il foglio "Nuovo album" e le uscite verso
// `:app`: aprire un album, creare uno smart (l'editor delle regole parte dal nome).

@HiltViewModel
class AlbumListViewModel @Inject constructor(ports: AlbumDependencies) : ViewModel() {
  val controller: AlbumListController = AlbumListController(ports.albums, ports.shared, viewModelScope)

  val people: AlbumPeople = ports.people
}

@Composable
fun AlbumListRoute(
  thumbnails: AlbumThumbnails,
  onOpenAlbum: (AlbumId) -> Unit,
  onNewSmart: (title: String) -> Unit,
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  viewModel: AlbumListViewModel = hiltViewModel(),
) {
  val controller = viewModel.controller
  val ui by controller.ui.collectAsState()
  var creating by remember { mutableStateOf(false) }
  var problem by remember { mutableStateOf<TitleProblem?>(null) }
  var unavailable by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  val handlers = remember(controller) {
    object : AlbumListHandlers {
      override fun onOpen(album: Album) = onOpenAlbum(album.id)

      override fun onCreate() {
        problem = null
        unavailable = false
        creating = true
      }

      override fun onSort(sort: AlbumListSort) = controller.setSort(sort)

      override fun onMove(id: AlbumId, move: ListMove) = controller.move(id, move)

      override fun onRejoin(id: AlbumId) = controller.rejoin(id)

      override fun onDismissNotice(notice: SharedAlbumNotice) = controller.dismissNotice(notice)

      override fun onOpenCopy(id: AlbumId) = onOpenAlbum(id)
    }
  }
  AlbumHost {
    AlbumListScreen(ui = ui, thumbnails = thumbnails, people = viewModel.people, handlers = handlers, modifier = modifier, onBack = onBack)
    CreateAlbumSheet(
      visible = creating, problem = problem, unavailable = unavailable,
      onCreate = { title, kind ->
        if (kind == AlbumKind.SMART) {
          // L'editor parte dal nome: il titolo si controlla li' quando si crea (stesse regole), qui basta non lasciarlo vuoto.
          creating = false
          onNewSmart(title.trim())
        } else {
          scope.launch {
            when (val result = controller.createManual(title)) {
              is CreateAlbumResult.Created -> {
                creating = false
                onOpenAlbum(result.id)
              }
              is CreateAlbumResult.InvalidTitle -> problem = result.problem
              CreateAlbumResult.Unavailable -> unavailable = true
            }
          }
        }
      },
      onDismiss = { creating = false },
    )
  }
}
