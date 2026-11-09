package dev.pampa.mycloudgallery.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.pampa.mycloudgallery.feature.albums.nav.openAlbum
import dev.pampa.mycloudgallery.feature.albums.nav.openAlbums
import dev.pampa.mycloudgallery.feature.albums.nav.openSmartEditor
import dev.pampa.mycloudgallery.feature.albums.tab.AlbumsTabExits
import dev.pampa.mycloudgallery.feature.albums.tab.AlbumsTabRoute
import dev.pampa.mycloudgallery.feature.library.collections.CollectionKind
import dev.pampa.mycloudgallery.feature.library.collections.openCollection

// LA SCHEDA ALBUM NELLA NAVIGAZIONE DELL'APP (fase grafica, G2b). feature-albums e feature-library non si conoscono: le uscite della scheda verso le
// pagine di sempre le scrive questo file. Un album e un album smart aprono la loro pagina; Preferiti, Fotocamera, Video, Screenshot e Messaggistica la
// griglia della raccolta-filtro (`CollectionGridDestination`, come dalle Raccolte); "Nuovo album" smart l'editor che parte dal nome; "Tutti gli album"
// l'elenco di sempre, con l'ordine e il riordino. Sul tablet le stesse rotte (le pagine degli album sono rotte anche li').

/**
 * La scheda Album dentro lo scaffold: la chiama l'ospite della scheda (`AlbumsTabHost` di G2) con lo spazio della pillola e il consegnatario del fondale
 * dello slot. Le miniature sono quelle della Libreria (`AlbumsHostViewModel.thumbnails`: solo cio' che e' gia' sul dispositivo, mai i nascosti).
 */
@Composable
fun AppAlbumsTab(
  nav: NavController,
  contentPadding: PaddingValues,
  backdropOut: (GlassBackdropState) -> Unit,
  modifier: Modifier = Modifier,
) {
  val host: AlbumsHostViewModel = hiltViewModel()
  val exits = remember(nav) {
    AlbumsTabExits(
      onOpenAlbum = { id -> nav.openAlbum(id) },
      // Il codice della voce e' quello della raccolta: una versione che non la conosce (non succede: sono nello stesso APK) non apre niente.
      onOpenSpecial = { special -> CollectionKind.fromCode(special.code)?.takeIf { it.isFilter }?.let { nav.openCollection(it) } },
      onNewSmart = { title -> nav.openSmartEditor(title) },
      onOpenAllAlbums = { nav.openAlbums() },
    )
  }
  AlbumsTabRoute(
    thumbnails = host.thumbnails,
    exits = exits,
    modifier = modifier,
    contentPadding = contentPadding,
    backdropOut = backdropOut,
  )
}
