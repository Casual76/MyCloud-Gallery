package dev.pampa.mycloudgallery.debugtools

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.mycloudgallery.R
import dev.pampa.mycloudgallery.core.designsystem.catalog.ComponentCatalogPlaceholder
import dev.pampa.mycloudgallery.feature.library.library.LibraryTabState
import dev.pampa.mycloudgallery.feature.library.testing.syntheticLibraryTabState
import kotlinx.serialization.Serializable

/** SOLO debug: il catalogo dei componenti di core-designsystem. */
@Serializable
data object CatalogRoute

/**
 * SOLO build debug: l'ingresso al **Catalogo componenti** (in Impostazioni → Sviluppo), come nella Fase 1.
 *
 * Dal P9 la build debug usa lo stesso grafo vero della release (niente piu' stato finto del dispositivo): la verifica
 * sul campo (docs/fase2-progetto.md §5.4) si fa con l'APK debug. La versione release di questo file (src/release) e'
 * vuota: niente di qui e' referenziato in release, quindi R8 non lo mette nell'APK (tools/check.ps1 cerca il catalogo
 * nel dex di release).
 */
internal object DeveloperTools {

  /**
   * La Libreria sintetica (docs/fase3-progetto.md §1.4, §5.2): in debug la scheda Libreria gira su 2.000 elementi
   * deterministici con miniature a gradiente e un viewer finto, finche' P11 non collega il grafo vero (e il suo
   * database SEPARATO, `gallery-synthetic.db`: la Libreria sintetica non tocca mai quello vero). In release e' `null`:
   * la Libreria resta quella vuota, sui null-object, e le classi dei fake non entrano nell'APK.
   */
  @Composable
  fun libraryTabState(): LibraryTabState? = remember { syntheticLibraryTabState() }

  fun NavGraphBuilder.destinations(nav: NavController) {
    composable<CatalogRoute> {
      ComponentCatalogPlaceholder(onBack = { nav.popBackStack() })
    }
  }

  fun settingsSection(nav: NavController): (@Composable () -> Unit)? = {
    FluidListGroup {
      FluidListRow(
        title = stringResource(R.string.debug_catalog_title),
        subtitle = stringResource(R.string.debug_catalog_detail),
        onClick = { nav.navigate(CatalogRoute) },
      )
    }
  }
}
