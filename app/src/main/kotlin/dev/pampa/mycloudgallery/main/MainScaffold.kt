package dev.pampa.mycloudgallery.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.pampa.mycloudgallery.R
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryBarButton
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTab
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTabBar
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTabBarDefaults
import dev.pampa.mycloudgallery.feature.library.library.LibraryTab
import dev.pampa.mycloudgallery.feature.library.library.LibraryTabState
import dev.pampa.mycloudgallery.feature.library.library.rememberEmptyLibraryTabState
import dev.pampa.mycloudgallery.feature.viewer.ViewerServices
import dev.pampa.mycloudgallery.status.GlobalStatus
import dev.pampa.mycloudgallery.status.GlobalStatusIndicator
import kotlinx.coroutines.launch

/** Cio' che lo scaffold passa alla scheda Raccolte: lo spazio della pillola, il fondale da rifrangere, l'indicatore. */
@Immutable
class CollectionsSlot(
  val contentPadding: PaddingValues,
  val backdropOut: (GlassBackdropState) -> Unit,
  val statusHeader: @Composable () -> Unit,
)

/**
 * Lo scaffold della galleria: le tre schede **Libreria · Raccolte · Cerca** nella pillola di vetro del design system,
 * l'indicatore di stato globale e l'accesso alle Impostazioni.
 *
 * **Fase 3: la Libreria e' a tutto schermo e il viewer sta sopra la pillola** (docs/fase3-progetto.md §1.4, D12).
 * - La scheda Libreria non usa `FluidScreen`: la griglia e' la sua lista, con la propria barra di vetro e lo stato
 *   globale in fondo. Le Raccolte ([collections], P10) sono un `FluidScreen` (o le due colonne sul tablet); Cerca resta
 *   un segnaposto fino alla Fase 6.
 * - La pillola e' **una sola** e sta fuori dalle schede, cosi' la lente che scivola sotto la scheda scelta non si
 *   ricrea passando dalla Libreria alle altre. Il fondale che rifrange arriva dalla scheda in primo piano.
 * - **In selezione la pillola si nasconde** (nota di P7, §2.4: "barra delle azioni in vetro al posto della pillola") e
 *   la barra delle azioni scende al suo posto.
 * - Il viewer (`ViewerOverlay`) e' una sovrapposizione: lo ospita [GalleryRoot] sopra la navigazione (con l'host dei
 *   pop-up); senza (i test della sola scheda) lo scaffold ne ospita uno suo. Toccare di nuovo "Libreria" riporta in
 *   fondo, sui piu' recenti (D1).
 *
 * @param libraryState cio' che la Libreria mostra: dal `LibraryViewModel` (grafo vero, P11); nei test i fake.
 * @param libraryViewer i servizi del viewer per la Libreria (dettagli, azioni, intent); null = solo i pixel dello stato.
 * @param collections la scheda Raccolte; null = il segnaposto (gli screenshot dello scaffold, senza Hilt).
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
  libraryViewer: ViewerServices? = null,
  collections: (@Composable (CollectionsSlot) -> Unit)? = null,
) {
  var backdrop by remember { mutableStateOf<GlassBackdropState?>(null) }
  val rootViewer = LocalViewerHost.current
  val ownViewer = if (rootViewer == null) remember { ViewerHostState(null) } else null
  val viewer = rootViewer ?: checkNotNull(ownViewer)
  val scope = rememberCoroutineScope()

  val navigationBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
  val selecting = selectedTab == GalleryTab.Library && libraryState.selectionActive
  // Lo spazio della pillola, che sta sopra la barra di navigazione; in selezione la pillola non c'e' e la barra delle
  // azioni prende il suo posto.
  val pillPadding = PaddingValues(
    bottom = navigationBottom + if (selecting) FluidFloatingTabBarDefaults.BottomMargin - FluidFloatingTabBarDefaults.Spacing else GalleryTabBarDefaults.ContentInset,
  )
  val services = libraryViewer ?: remember(libraryState) {
    ViewerServices(
      images = libraryState.viewerImages,
      thumbnails = libraryState.grid.loader,
      placeholders = libraryState.grid.placeholders,
      places = libraryState.grid.placeNames,
    )
  }

  Box(modifier = Modifier.fillMaxSize()) {
    when {
      selectedTab == GalleryTab.Library -> LibraryTab(
        contentPadding = pillPadding,
        backdropOut = { backdrop = it },
        onOpenViewer = { viewer.open(it, services, fromLibrary = true) },
        onOpenSettings = onOpenSettings,
        statusFooter = { GlobalStatusIndicator(status = status, onClick = onStatusClick) },
        state = libraryState,
      )
      selectedTab == GalleryTab.Collections && collections != null -> collections(
        CollectionsSlot(
          contentPadding = PaddingValues(bottom = GalleryTabBarDefaults.ContentInset),
          backdropOut = { backdrop = it },
          statusHeader = { GlobalStatusIndicator(status = status, onClick = onStatusClick) },
        ),
      )
      else -> PlaceholderTab(selectedTab, status, onStatusClick, onOpenSettings) { backdrop = it }
    }

    AnimatedVisibility(
      visible = !selecting,
      enter = fadeIn(FluidMotion.fadeIn()),
      exit = fadeOut(FluidMotion.fadeOut()),
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .navigationBarsPadding()
        .padding(horizontal = FluidFloatingTabBarDefaults.HorizontalMargin)
        .padding(bottom = FluidFloatingTabBarDefaults.BottomMargin),
    ) {
      GalleryTabBar(
        selected = selectedTab,
        onSelect = onSelectTab,
        backdrop = backdrop,
        onReselect = { tab -> if (tab == GalleryTab.Library) scope.launch { libraryState.grid.scrollToNewest() } },
      )
    }

    if (ownViewer != null) ViewerLayer(ownViewer)
  }
}

@Composable
private fun PlaceholderTab(
  tab: GalleryTab,
  status: GlobalStatus,
  onStatusClick: () -> Unit,
  onOpenSettings: () -> Unit,
  backdropOut: (GlassBackdropState) -> Unit,
) {
  FluidScreen(
    title = stringResource(tab.label),
    actions = {
      GalleryBarButton(
        icon = Icons.Rounded.Settings,
        contentDescription = stringResource(R.string.main_open_settings),
        onClick = onOpenSettings,
      )
    },
    extraBottomPadding = GalleryTabBarDefaults.ContentInset,
    // La pillola non e' qui dentro (e' una sola per tutte le schede): questa pagina le consegna solo il suo fondale.
    overlay = { pageBackdrop -> SideEffect { backdropOut(pageBackdrop) } },
  ) {
    item(key = "status") { GlobalStatusIndicator(status = status, onClick = onStatusClick) }
    item(key = "tab-${tab.route}") { TabPlaceholder(tab) }
  }
}

@Composable
private fun TabPlaceholder(tab: GalleryTab) {
  when (tab) {
    // La Libreria non e' un segnaposto (Fase 3): non passa di qui.
    GalleryTab.Library -> Unit
    // Senza la scheda vera (gli screenshot dello scaffold) le Raccolte restano il segnaposto della Fase 2.
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
