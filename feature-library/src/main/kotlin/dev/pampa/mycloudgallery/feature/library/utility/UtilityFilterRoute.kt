package dev.pampa.mycloudgallery.feature.library.utility

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.pampa.mycloudgallery.core.library.contract.ViewerItems
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.library.ActionNotice
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEmptyState
import dev.pampa.mycloudgallery.feature.library.collections.CollectionGridPage
import dev.pampa.mycloudgallery.feature.library.collections.GridEnvironment
import dev.pampa.mycloudgallery.feature.library.collections.collectionSelectionActions
import dev.pampa.mycloudgallery.feature.library.trash.RecentlyDeletedRoute

// Fase 7, P8 (D24): le pagine-filtro dell'Utility. Stessa griglia, stesso viewer e stessa chrome della Libreria e delle Raccolte (`CollectionGridPage`):
// Screenshot, Video, Modificate e WhatsApp sono quattro filtri dell'indice. Doppioni ed Eliminati di recente hanno le loro pagine.

/**
 * La pagina di una pagina-filtro dell'Utility. Vale per le rotte a schermo intero ([onBack] presente) e per il pannello di dettaglio del tablet
 * ([onBack] nullo).
 */
@Composable
fun UtilityFilterRoute(
  page: UtilityPage,
  onOpenViewer: (ViewerRequest) -> Unit,
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  contentPadding: PaddingValues = PaddingValues(),
  backdropOut: ((GlassBackdropState) -> Unit)? = null,
  viewModel: UtilityViewModel = hiltViewModel(),
) {
  // Doppioni ed Eliminati di recente non sono filtri: hanno le loro rotte (`UtilityPageRoute` le smista).
  val items = remember(page, viewModel) { viewModel.items(page) } ?: return
  val notice by viewModel.notice.collectAsState()
  UtilityFilterScreen(
    page = page,
    items = items,
    environment = viewModel.environment,
    onFavorite = viewModel::setFavorite,
    onHide = viewModel::hide,
    onOpenViewer = onOpenViewer,
    modifier = modifier,
    onBack = onBack,
    contentPadding = contentPadding,
    backdropOut = backdropOut,
    notice = notice,
    onDismissNotice = viewModel::dismissNotice,
  )
}

/** La stessa pagina senza Hilt: gli elementi e le azioni arrivano da chi la chiama. La provano i test e gli screenshot. */
@Composable
internal fun UtilityFilterScreen(
  page: UtilityPage,
  items: ViewerItems,
  environment: GridEnvironment,
  onFavorite: (Set<MediaId>, Boolean) -> Unit,
  onHide: (Set<MediaId>) -> Unit,
  onOpenViewer: (ViewerRequest) -> Unit,
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  contentPadding: PaddingValues = PaddingValues(),
  backdropOut: ((GlassBackdropState) -> Unit)? = null,
  notice: ActionNotice? = null,
  onDismissNotice: () -> Unit = {},
) {
  CollectionGridPage(
    title = stringResource(page.titleRes),
    items = items,
    environment = environment,
    secure = false,
    onOpenViewer = onOpenViewer,
    empty = {
      CollectionEmptyState(
        icon = page.icon,
        title = stringResource(page.emptyTitleRes),
        detail = stringResource(page.emptyDetailRes),
      )
    },
    modifier = modifier,
    onBack = onBack,
    contentPadding = contentPadding,
    backdropOut = backdropOut,
    actionsFor = { view, ids -> collectionSelectionActions(view, ids, onFavorite, onHide) },
    notice = notice,
    onDismissNotice = onDismissNotice,
  )
}

/**
 * Qualunque pagina dell'Utility, smistata: il dettaglio del tablet e le rotte a schermo intero chiamano questa e basta. Le pagine-filtro usano la
 * griglia, Doppioni ed Eliminati di recente le loro pagine.
 */
@Composable
fun UtilityPageRoute(
  page: UtilityPage,
  onOpenViewer: (ViewerRequest) -> Unit,
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  contentPadding: PaddingValues = PaddingValues(),
  backdropOut: ((GlassBackdropState) -> Unit)? = null,
) {
  when (page) {
    UtilityPage.DUPLICATES -> DuplicatesRoute(modifier = modifier, onBack = onBack, contentPadding = contentPadding, backdropOut = backdropOut)
    UtilityPage.RECENTLY_DELETED -> RecentlyDeletedRoute(modifier = modifier, onBack = onBack, contentPadding = contentPadding, backdropOut = backdropOut)
    else -> UtilityFilterRoute(
      page = page,
      onOpenViewer = onOpenViewer,
      modifier = modifier,
      onBack = onBack,
      contentPadding = contentPadding,
      backdropOut = backdropOut,
    )
  }
}
