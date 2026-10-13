package dev.pampa.mycloudgallery.feature.library.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryBarButton
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTopBar
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryStateKind
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryStateView
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import dev.pampa.mycloudgallery.feature.library.R
import dev.pampa.mycloudgallery.feature.library.grid.MediaGrid

/**
 * La scheda **Libreria**: la griglia del telefono **a tutto schermo** (§1.4: la griglia e' la sua lista, niente
 * `FluidScreen`), con la barra di vetro in cima e il riepilogo e lo stato globale in fondo, sotto i piu' recenti.
 *
 * - [contentPadding]: lo spazio che la pillola delle schede occupa in basso (il contenuto scorre sotto, rifratto);
 * - [backdropOut]: la Libreria consegna a `:app` il fondale di vetro che disegna (la griglia), cosi' la pillola lo
 *   rifrange senza far parte della scheda;
 * - [onOpenViewer]: il viewer e' una sovrapposizione ospitata da `:app` sopra la pillola (D12): la griglia non lo
 *   conosce, manda la richiesta con la geometria della miniatura (`ViewerAnchor`);
 * - [statusFooter]: l'indicatore globale della Fase 2, in fondo.
 *
 * **Stub di P0**: gira sullo stato che riceve (vedi [LibraryTabState]). P7 la rende vera: ViewModel, pinch, scrubber,
 * filtro, selezione, permessi, banner di stato.
 */
@Composable
fun LibraryTab(
  contentPadding: PaddingValues,
  backdropOut: (GlassBackdropState) -> Unit,
  onOpenViewer: (ViewerRequest) -> Unit,
  onOpenSettings: () -> Unit,
  statusFooter: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  state: LibraryTabState = rememberEmptyLibraryTabState(),
) {
  val backdrop = rememberGlassBackdrop()
  SideEffect { backdropOut(backdrop) }
  val density = LocalDensity.current
  var barHeightPx by remember { mutableIntStateOf(0) }
  val barHeight = with(density) { barHeightPx.toDp() }

  Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    Box(modifier = Modifier.fillMaxSize().glassBackdropSource(backdrop)) {
      if (state.grid.view.size == 0) {
        Column(
          modifier = Modifier.fillMaxSize().padding(top = barHeight, bottom = contentPadding.calculateBottomPadding()),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          GalleryStateView(kind = GalleryStateKind.LibraryEmpty, modifier = Modifier.weight(1f, fill = false))
          statusFooter()
        }
      } else {
        MediaGrid(
          state = state.grid,
          contentPadding = PaddingValues(top = barHeight, bottom = contentPadding.calculateBottomPadding()),
          onOpen = { id ->
            onOpenViewer(ViewerRequest(items = state.viewerItems, start = id, anchor = state.grid.viewerAnchor, secure = false))
          },
          footer = {
            Column(
              modifier = Modifier.fillMaxWidth().padding(horizontal = GalleryDimens.Gutter, vertical = 16.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              Text(
                text = stringResource(R.string.library_summary, state.photoCount, state.videoCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              statusFooter()
            }
          },
        )
      }
    }
    GalleryTopBar(
      title = stringResource(R.string.library_title),
      backdrop = backdrop,
      modifier = Modifier.align(Alignment.TopCenter).onSizeChanged { barHeightPx = it.height },
      actions = {
        GalleryBarButton(
          icon = Icons.Rounded.Settings,
          contentDescription = stringResource(R.string.library_open_settings),
          onClick = onOpenSettings,
        )
      },
    )
  }
}
