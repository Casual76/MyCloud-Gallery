package dev.pampa.mycloudgallery.core.designsystem.chrome

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidFloatingTabBar
import dev.antigravity.fluidengine.ui.fluid.FluidFloatingTabBarDefaults
import dev.antigravity.fluidengine.ui.fluid.FluidFloatingTabBarScrollConnection
import dev.antigravity.fluidengine.ui.fluid.FluidTabItem
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.rememberFluidFloatingTabBarScrollConnection
import dev.pampa.mycloudgallery.core.designsystem.R

/** Le tre destinazioni della navigazione principale: Libreria, Raccolte, Cerca (piano, sezione 2). */
enum class GalleryTab(
  val route: String,
  @StringRes val label: Int,
  val icon: ImageVector,
) {
  Library("library", R.string.tab_library, Icons.Rounded.PhotoLibrary),
  Collections("collections", R.string.tab_collections, Icons.Rounded.Collections),
  Search("search", R.string.tab_search, Icons.Rounded.Search),
}

object GalleryTabBarDefaults {

  /** Lo spazio che una pagina deve lasciare in fondo perche' il contenuto non finisca sotto la barra. */
  val ContentInset = FluidFloatingTabBarDefaults.ContentInset
}

/**
 * La barra delle schede **Libreria · Raccolte · Cerca**: la pillola di vetro dell'engine
 * (`FluidFloatingTabBar`) con le tre destinazioni della galleria. Una navigazione sola su ogni
 * formato, telefono e tablet: lo spazio in piu' si usa dentro le pagine, non con una seconda
 * navigazione.
 *
 * Non c'e' vetro nuovo qui: la pillola, la lente che scivola sotto la scheda scelta, il trascinamento
 * e la piega allo scorrimento sono dell'engine. Questo componente fornisce le tre voci e basta.
 * Per la piega, la pagina passa il suo [scrollConnection] a `Modifier.nestedScroll`.
 *
 * Con [backdrop] nullo la barra disegna i colori piatti (una anteprima, un dispositivo senza
 * `RenderEffect`).
 */
@Composable
fun GalleryTabBar(
  selected: GalleryTab,
  onSelect: (GalleryTab) -> Unit,
  modifier: Modifier = Modifier,
  backdrop: GlassBackdropState? = null,
  onReselect: (GalleryTab) -> Unit = {},
  scrollConnection: FluidFloatingTabBarScrollConnection = rememberFluidFloatingTabBarScrollConnection(),
) {
  val items = GalleryTab.entries.map { tab ->
    FluidTabItem(route = tab.route, label = stringResource(tab.label), icon = tab.icon)
  }
  // Le etichette delle schede sono `Text` senza colore: prendono `LocalContentColor`, che fuori da un
  // `Surface` Material e' nero anche in tema scuro. La barra se lo da' da sola, cosi' non dipende da
  // dove la si mette.
  CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
    FluidFloatingTabBar(
      items = items,
      selectedRoute = selected.route,
      onSelect = { item -> onSelect(GalleryTab.entries.first { it.route == item.route }) },
      scrollConnection = scrollConnection,
      backdrop = backdrop,
      modifier = modifier,
      onReselect = { item -> onReselect(GalleryTab.entries.first { it.route == item.route }) },
    )
  }
}
