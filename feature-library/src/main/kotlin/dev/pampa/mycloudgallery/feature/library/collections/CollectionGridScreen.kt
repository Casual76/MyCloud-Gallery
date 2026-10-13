package dev.pampa.mycloudgallery.feature.library.collections

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.contract.ViewerItems
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.library.R

/**
 * La pagina di una raccolta-filtro (Preferiti, Video, Screenshot, WhatsApp): la griglia della Libreria con quel filtro,
 * lo stesso viewer. Vale per le rotte a schermo intero ([onBack] presente) e per il pannello di dettaglio del tablet
 * ([onBack] nullo).
 *
 * Per i Nascosti e gli Eliminati di recente, che non sono filtri, ci sono [dev.pampa.mycloudgallery.feature.library.hidden.HiddenRoute]
 * e [dev.pampa.mycloudgallery.feature.library.trash.RecentlyDeletedRoute].
 */
@Composable
fun CollectionGridRoute(
  kind: CollectionKind,
  onOpenViewer: (ViewerRequest) -> Unit,
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  contentPadding: PaddingValues = PaddingValues(),
  backdropOut: ((GlassBackdropState) -> Unit)? = null,
  viewModel: CollectionsViewModel = hiltViewModel(),
) {
  // Nascosti ed Eliminati di recente non hanno una vista dell'indice: qui non ci si arriva (le rotte sono separate).
  val items = remember(kind, viewModel) { viewModel.items(kind) } ?: return
  CollectionGridScreen(
    kind = kind,
    items = items,
    environment = viewModel.environment,
    onFavorite = viewModel::setFavorite,
    onHide = viewModel::hide,
    onOpenViewer = onOpenViewer,
    modifier = modifier,
    onBack = onBack,
    contentPadding = contentPadding,
    backdropOut = backdropOut,
  )
}

/** La stessa pagina senza Hilt: gli elementi e le azioni arrivano da chi la chiama. La provano i test e gli screenshot. */
@Composable
internal fun CollectionGridScreen(
  kind: CollectionKind,
  items: ViewerItems,
  environment: GridEnvironment,
  onFavorite: (Set<MediaId>, Boolean) -> Unit,
  onHide: (Set<MediaId>) -> Unit,
  onOpenViewer: (ViewerRequest) -> Unit,
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  contentPadding: PaddingValues = PaddingValues(),
  backdropOut: ((GlassBackdropState) -> Unit)? = null,
) {
  CollectionGridPage(
    title = stringResource(kind.titleRes),
    items = items,
    environment = environment,
    secure = false,
    onOpenViewer = onOpenViewer,
    empty = {
      CollectionEmptyState(
        icon = kind.icon,
        title = stringResource(kind.emptyTitleRes),
        detail = stringResource(kind.emptyDetailRes),
      )
    },
    modifier = modifier,
    onBack = onBack,
    contentPadding = contentPadding,
    backdropOut = backdropOut,
    actionsFor = { view, ids -> collectionSelectionActions(view, ids, onFavorite, onHide) },
  )
}

/**
 * Le azioni della barra sulla selezione di una raccolta-filtro: **Preferito** (aggiunge, o toglie se sono tutti gia'
 * preferiti: nei Preferiti e' sempre "togli") e **Nascondi**. Condividi ed Elimina restano nel viewer (D13): questa
 * barra offre solo azioni che funzionano senza una schermata di sistema.
 */
internal fun collectionSelectionActions(
  view: TimelineView,
  selected: Set<MediaId>,
  onFavorite: (Set<MediaId>, Boolean) -> Unit,
  onHide: (Set<MediaId>) -> Unit,
): List<SelectionAction> {
  val allFavorite = selected.isNotEmpty() && selected.all { isFavorite(view, it) }
  val favorite = if (allFavorite) {
    SelectionAction(Icons.Rounded.FavoriteBorder, R.string.selection_unfavorite) { onFavorite(it, false) }
  } else {
    SelectionAction(Icons.Rounded.Favorite, R.string.selection_favorite) { onFavorite(it, true) }
  }
  return listOf(favorite, SelectionAction(Icons.Rounded.VisibilityOff, R.string.selection_hide) { onHide(it) })
}
