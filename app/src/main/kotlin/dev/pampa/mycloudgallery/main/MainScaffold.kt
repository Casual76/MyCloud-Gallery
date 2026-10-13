package dev.pampa.mycloudgallery.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidFloatingTabBarDefaults
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.pampa.mycloudgallery.R
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryBarButton
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTab
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTabBar
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTabBarDefaults
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import dev.pampa.mycloudgallery.feature.library.library.LibraryTab
import dev.pampa.mycloudgallery.feature.library.library.LibraryTabState
import dev.pampa.mycloudgallery.feature.library.library.rememberEmptyLibraryTabState
import dev.pampa.mycloudgallery.feature.viewer.ViewerOverlay
import dev.pampa.mycloudgallery.status.GlobalStatus
import dev.pampa.mycloudgallery.status.GlobalStatusIndicator
import kotlinx.coroutines.launch

/**
 * Lo scaffold della galleria: le tre schede **Libreria · Raccolte · Cerca** nella pillola di vetro del design system,
 * l'indicatore di stato globale e l'accesso alle Impostazioni.
 *
 * **Fase 3 (P0): la Libreria e' a tutto schermo e il viewer sta sopra la pillola** (docs/fase3-progetto.md §1.4, D12).
 * - La scheda Libreria non usa `FluidScreen`: la griglia e' la sua lista, con la propria barra di vetro e lo stato
 *   globale in fondo. Raccolte e Cerca restano pagine `FluidScreen` con l'indicatore in cima.
 * - La pillola e' **una sola** e sta fuori dalle schede, cosi' la lente che scivola sotto la scheda scelta non si
 *   ricrea passando dalla Libreria alle altre. Il fondale che rifrange arriva dalla scheda in primo piano: la Libreria
 *   lo consegna con `backdropOut`, `FluidScreen` con il suo `overlay`.
 * - Il viewer (`ViewerOverlay`) e' una sovrapposizione disegnata per ultima, sopra la pillola; la griglia gli manda la
 *   richiesta con la geometria della miniatura. Toccare di nuovo "Libreria" riporta in fondo, sui piu' recenti (D1).
 *
 * @param libraryState cio' che la Libreria mostra. **P0: i fake** (debug: Libreria sintetica, release: Libreria vuota
 *   sui null-object). P11 lo toglie e la Libreria prende tutto dal grafo vero tramite il suo ViewModel.
 *
 * Una navigazione sola su ogni formato (telefono e tablet): lo spazio in piu' del tablet si usa dentro le pagine.
 */
@Composable
fun MainScaffold(
  status: GlobalStatus,
  selectedTab: GalleryTab,
  onSelectTab: (GalleryTab) -> Unit,
  onStatusClick: () -> Unit,
  onOpenSettings: () -> Unit,
  libraryState: LibraryTabState = rememberEmptyLibraryTabState(),
) {
  var backdrop by remember { mutableStateOf<GlassBackdropState?>(null) }
  // Il viewer e' una sovrapposizione, non una rotta: la richiesta vive qui. TODO(P11): l'id corrente nello stato salvato (E-V8).
  var viewerRequest by remember { mutableStateOf<ViewerRequest?>(null) }
  val scope = rememberCoroutineScope()

  Box(modifier = Modifier.fillMaxSize()) {
    if (selectedTab == GalleryTab.Library) {
      LibraryTab(
        contentPadding = PaddingValues(bottom = GalleryTabBarDefaults.ContentInset),
        backdropOut = { backdrop = it },
        onOpenViewer = { viewerRequest = it },
        onOpenSettings = onOpenSettings,
        statusFooter = { GlobalStatusIndicator(status = status, onClick = onStatusClick) },
        state = libraryState,
      )
    } else {
      FluidScreen(
        title = stringResource(selectedTab.label),
        actions = {
          GalleryBarButton(
            icon = Icons.Rounded.Settings,
            contentDescription = stringResource(R.string.main_open_settings),
            onClick = onOpenSettings,
          )
        },
        extraBottomPadding = GalleryTabBarDefaults.ContentInset,
        // La pillola non e' qui dentro (e' una sola per tutte le schede): questa pagina le consegna solo il suo fondale.
        overlay = { pageBackdrop -> SideEffect { backdrop = pageBackdrop } },
      ) {
        item(key = "status") { GlobalStatusIndicator(status = status, onClick = onStatusClick) }
        item(key = "tab-${selectedTab.route}") { TabPlaceholder(selectedTab) }
      }
    }

    GalleryTabBar(
      selected = selectedTab,
      onSelect = onSelectTab,
      backdrop = backdrop,
      onReselect = { tab -> if (tab == GalleryTab.Library) scope.launch { libraryState.grid.scrollToNewest() } },
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .navigationBarsPadding()
        .padding(horizontal = FluidFloatingTabBarDefaults.HorizontalMargin)
        .padding(bottom = FluidFloatingTabBarDefaults.BottomMargin),
    )

    ViewerOverlay(
      request = viewerRequest,
      onClosed = { viewerRequest = null },
      images = libraryState.viewerImages,
    )
  }
}

@Composable
private fun TabPlaceholder(tab: GalleryTab) {
  when (tab) {
    // La Libreria non e' piu' un segnaposto (Fase 3): non passa di qui.
    GalleryTab.Library -> Unit
    GalleryTab.Collections -> FluidEmptyState(
      title = stringResource(R.string.main_collections_empty_title),
      detail = stringResource(R.string.main_collections_empty_detail),
    )
    GalleryTab.Search -> FluidEmptyState(
      title = stringResource(R.string.main_search_empty_title),
      detail = stringResource(R.string.main_search_empty_detail),
    )
  }
}
