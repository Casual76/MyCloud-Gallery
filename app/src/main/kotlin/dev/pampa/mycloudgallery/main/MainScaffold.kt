package dev.pampa.mycloudgallery.main

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidFloatingTabBarDefaults
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.pampa.mycloudgallery.R
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryBarButton
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTab
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTabBar
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTabBarDefaults
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryStateKind
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryStateView
import dev.pampa.mycloudgallery.status.GlobalStatus
import dev.pampa.mycloudgallery.status.GlobalStatusIndicator

/**
 * Lo scaffold della galleria (scheletro P0): le tre schede **Libreria · Raccolte · Cerca** nella pillola di
 * vetro del design system, l'indicatore di stato globale in cima alla pagina e l'accesso alle Impostazioni
 * nella barra. Il contenuto delle schede e' un segnaposto: arriva con le fasi 3, 4, 6 e 7.
 *
 * Una navigazione sola su ogni formato (telefono e tablet): lo spazio in piu' del tablet si usa dentro le
 * pagine. La pillola sta nell'`overlay` di `FluidScreen`, cosi' rifrange la pagina senza farne parte.
 */
@Composable
fun MainScaffold(
  status: GlobalStatus,
  selectedTab: GalleryTab,
  onSelectTab: (GalleryTab) -> Unit,
  onStatusClick: () -> Unit,
  onOpenSettings: () -> Unit,
) {
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
    overlay = { backdrop ->
      GalleryTabBar(
        selected = selectedTab,
        onSelect = onSelectTab,
        backdrop = backdrop,
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .navigationBarsPadding()
          .padding(horizontal = FluidFloatingTabBarDefaults.HorizontalMargin)
          .padding(bottom = FluidFloatingTabBarDefaults.BottomMargin),
      )
    },
  ) {
    item(key = "status") { GlobalStatusIndicator(status = status, onClick = onStatusClick) }
    item(key = "tab-${selectedTab.route}") { TabPlaceholder(selectedTab) }
  }
}

@Composable
private fun TabPlaceholder(tab: GalleryTab) {
  when (tab) {
    GalleryTab.Library -> GalleryStateView(kind = GalleryStateKind.LibraryEmpty)
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
