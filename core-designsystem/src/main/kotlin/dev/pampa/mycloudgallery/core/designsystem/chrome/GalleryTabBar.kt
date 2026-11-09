package dev.pampa.mycloudgallery.core.designsystem.chrome

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesomeMosaic
import androidx.compose.material.icons.rounded.PhotoAlbum
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import dev.antigravity.fluidengine.ui.fluid.FluidFloatingTabBar
import dev.antigravity.fluidengine.ui.fluid.FluidFloatingTabBarDefaults
import dev.antigravity.fluidengine.ui.fluid.FluidFloatingTabBarScrollConnection
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.rememberFluidFloatingTabBarScrollConnection
import dev.pampa.mycloudgallery.core.designsystem.R

/**
 * Le tre destinazioni della navigazione principale (G2, come in Foto di Apple): **Libreria · Album · Raccolte**. Cerca non e' piu' una
 * scheda: e' il cerchio di vetro accanto alla pillola ([GalleryTabBar] con `onOpenSearch`).
 *
 * [route] e' la chiave della scheda nella pillola: non e' una rotta di navigazione e non si salva fuori dal processo.
 */
enum class GalleryTab(
  val route: String,
  @StringRes val label: Int,
  val icon: ImageVector,
) {
  Library("library", R.string.tab_library, Icons.Rounded.PhotoLibrary),
  Albums("albums", R.string.tab_albums, Icons.Rounded.PhotoAlbum),
  // Un mosaico e non una pila di foto: con tre schede l'icona di prima (`Collections`) era quasi uguale a quella della Libreria.
  Collections("collections", R.string.tab_collections, Icons.Rounded.AutoAwesomeMosaic),
}

object GalleryTabBarDefaults {

  /** Lo spazio che una pagina deve lasciare in fondo perche' il contenuto non finisca sotto la barra. */
  val ContentInset = FluidFloatingTabBarDefaults.ContentInset

  /** La chiave del cerchio di Cerca nella pillola: non e' una delle [GalleryTab]. */
  const val SearchKey: String = "search"
}

/**
 * La barra delle schede **Libreria · Album · Raccolte** e, accanto, il **cerchio di Cerca** (G2, come in Foto di Apple): la pillola di
 * vetro dell'engine (`FluidFloatingTabBar`) nella forma a DSL, perche' solo quella ha il cerchio separato (`standaloneTab`). Una
 * navigazione sola su ogni formato, telefono e tablet.
 *
 * Il cerchio e' della barra, non un pezzo posato accanto: la barra lo mette alla destra del gruppo centrato, gli lascia il suo spazio
 * quando stringe le schede, e ripiegata dallo scorrimento della Libreria mostra la scheda scelta e il cerchio (stesso vetro, stessa
 * aptica, stessa trasformazione). Non c'e' vetro nuovo qui: questo componente fornisce le voci e basta. Per la piega, la pagina passa
 * [scrollConnection] a `Modifier.nestedScroll`.
 *
 * Con Cerca aperta ([searchOpen]) la pillola resta sulla scheda da cui si e' partiti (Indietro riporta li') e il cerchio si colora
 * d'accento; il tocco su una scheda, anche quella sotto la ricerca, chiama [onSelect] (chi la ospita chiude la ricerca).
 *
 * Con [backdrop] nullo la barra disegna i colori piatti (un'anteprima, un dispositivo senza `RenderEffect`).
 *
 * @param onOpenSearch il tocco sul cerchio; null = niente cerchio (un'anteprima della sola pillola).
 */
@Composable
fun GalleryTabBar(
  selected: GalleryTab,
  onSelect: (GalleryTab) -> Unit,
  modifier: Modifier = Modifier,
  backdrop: GlassBackdropState? = null,
  onReselect: (GalleryTab) -> Unit = {},
  scrollConnection: FluidFloatingTabBarScrollConnection = rememberFluidFloatingTabBarScrollConnection(),
  searchOpen: Boolean = false,
  onOpenSearch: (() -> Unit)? = null,
) {
  val labels = GalleryTab.entries.associateWith { stringResource(it.label) }
  val searchLabel = stringResource(R.string.tab_search)
  val accent = MaterialTheme.colorScheme.primary
  // Le etichette delle schede sono `Text` senza colore: prendono `LocalContentColor`, che fuori da un `Surface` Material e' nero anche in
  // tema scuro. La barra se lo da' da sola, cosi' non dipende da dove la si mette.
  CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
    FluidFloatingTabBar(
      selectedTabKey = selected.route,
      scrollConnection = scrollConnection,
      modifier = modifier,
      backdrop = backdrop,
    ) {
      GalleryTab.entries.forEach { item ->
        val chosen = item == selected && !searchOpen
        tab(
          key = item.route,
          title = { Text(labels.getValue(item)) },
          // "Selezionata" sul glifo: la scheda e' un nodo che la barra costruisce da se' e che unisce la semantica dei figli alla propria
          // (come fa la forma a elenco dell'engine). Con Cerca aperta nessuna scheda e' quella in vista.
          icon = { Icon(item.icon, contentDescription = null, modifier = Modifier.semantics { this.selected = chosen }) },
          onClick = { if (chosen) onReselect(item) else onSelect(item) },
        )
      }
      if (onOpenSearch != null) {
        standaloneTab(
          key = GalleryTabBarDefaults.SearchKey,
          icon = {
            Icon(
              Icons.Rounded.Search,
              contentDescription = searchLabel,
              tint = if (searchOpen) accent else LocalContentColor.current,
              modifier = Modifier.semantics { this.selected = searchOpen },
            )
          },
          onClick = onOpenSearch,
        )
      }
    }
  }
}
