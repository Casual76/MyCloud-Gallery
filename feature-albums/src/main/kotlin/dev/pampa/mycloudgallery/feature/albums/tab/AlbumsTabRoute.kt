package dev.pampa.mycloudgallery.feature.albums.tab

import androidx.compose.foundation.layout.PaddingValues
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
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumNotice
import dev.pampa.mycloudgallery.core.albums.contract.TitleProblem
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumThumbnails
import dev.pampa.mycloudgallery.feature.albums.contract.SpecialAlbum
import dev.pampa.mycloudgallery.feature.albums.list.CreateAlbumResult
import dev.pampa.mycloudgallery.feature.albums.list.CreateAlbumSheet
import dev.pampa.mycloudgallery.feature.albums.nav.AlbumDependencies
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumHost
import javax.inject.Inject
import kotlinx.coroutines.launch

// LA ROTTA DELLA SCHEDA ALBUM (fase grafica, G2b): il punto d'ingresso che `:app` ospita nella scheda Album della pillola (`AlbumsTabHost`, G2). Lega il
// controllore, il foglio "Nuovo album" e le uscite verso `:app`: aprire un album, una voce speciale (la griglia della raccolta), l'editor di uno smart
// nuovo, "Tutti gli album". Non ha una barra sua oltre al titolo della scheda, e lascia in fondo lo spazio della pillola.

@HiltViewModel
class AlbumsTabViewModel @Inject constructor(ports: AlbumDependencies, tab: AlbumsTabDependencies) : ViewModel() {
  val controller: AlbumsTabController = AlbumsTabController(ports.albums, ports.shared, tab.specials, tab.openings, viewModelScope)
}

/**
 * Le uscite della scheda verso `:app`. [onOpenSpecial] riceve la voce speciale: il suo [SpecialAlbum.code] e' il codice della raccolta-filtro
 * (`CollectionKind.fromCode(code)` e `NavController.openCollection` in `:app`).
 */
class AlbumsTabExits(
  val onOpenAlbum: (AlbumId) -> Unit,
  val onOpenSpecial: (SpecialAlbum) -> Unit,
  val onNewSmart: (title: String) -> Unit,
  val onOpenAllAlbums: () -> Unit,
)

/**
 * La scheda Album.
 *
 * @param contentPadding lo spazio della pillola delle schede in fondo (`CollectionsSlot.contentPadding` di `:app`).
 * @param backdropOut consegna il fondale della pagina alla pillola, che lo rifrange (`CollectionsSlot.backdropOut`).
 */
@Composable
fun AlbumsTabRoute(
  thumbnails: AlbumThumbnails,
  exits: AlbumsTabExits,
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues = PaddingValues(),
  backdropOut: (GlassBackdropState) -> Unit = {},
  viewModel: AlbumsTabViewModel = hiltViewModel(),
) {
  val controller = viewModel.controller
  val state by controller.state.collectAsState()
  var creating by remember { mutableStateOf(false) }
  var problem by remember { mutableStateOf<TitleProblem?>(null) }
  var unavailable by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  val handlers = remember(controller, exits) {
    object : AlbumsTabHandlers {
      override fun onOpen(entry: AlbumsTabEntry) {
        controller.opened(entry)
        when (entry) {
          is AlbumsTabEntry.Item -> exits.onOpenAlbum(entry.album.id)
          is AlbumsTabEntry.Special -> exits.onOpenSpecial(entry.kind)
        }
      }

      override fun onCreate() {
        problem = null
        unavailable = false
        creating = true
      }

      override fun onOpenAllAlbums() = exits.onOpenAllAlbums()

      override fun onDismissNotice(notice: SharedAlbumNotice) = controller.dismissNotice(notice)

      override fun onOpenCopy(id: AlbumId) = exits.onOpenAlbum(id)
    }
  }
  AlbumHost {
    AlbumsTabScreen(
      state = state,
      thumbnails = thumbnails,
      handlers = handlers,
      modifier = modifier,
      contentPadding = contentPadding,
      backdropOut = backdropOut,
    )
    CreateAlbumSheet(
      visible = creating,
      problem = problem,
      unavailable = unavailable,
      onCreate = { title, kind ->
        if (kind == AlbumKind.SMART) {
          // L'editor parte dal nome: il titolo si controlla li' quando si crea (stesse regole dell'elenco).
          creating = false
          exits.onNewSmart(title.trim())
        } else {
          scope.launch {
            when (val result = controller.createManual(title)) {
              is CreateAlbumResult.Created -> {
                creating = false
                exits.onOpenAlbum(result.id)
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
