package dev.pampa.mycloudgallery.feature.map.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.pampa.mycloudgallery.core.designsystem.adaptive.GalleryPanes
import dev.pampa.mycloudgallery.core.designsystem.adaptive.PaneWindows
import dev.pampa.mycloudgallery.core.designsystem.adaptive.ProvidePaneWindow

// I DUE PANNELLI DELLA MAPPA (Fase 10, P5, D13 (2); docs/fase10-progetto.md §2.3, §1.5 `ZoneSideState`): la mappa a sinistra (60%), la griglia della zona a destra (40%) quando si tocca una bolla; chiusa, la
// mappa e' intera. Il posto della mappa nell'albero e' sempre lo stesso (una `Row` con la mappa che prende il resto): aprire o chiudere la zona non ricrea la mappa, la sua superficie vive nel ViewModel. La
// griglia compare di colpo e la **mappa cambia misura di colpo**: le schermate della mappa non hanno animazioni (regola di `NoHandWrittenStyleTest`) e ridimensionare la superficie di MapLibre a ogni
// fotogramma sarebbe lavoro pesante. Nota per la fase grafica: far entrare la griglia con `FluidMotion` e animare un margine della camera (`padding` di MapLibre) invece della misura.

internal object MapPanesTags {
  const val Map = "mappa_pannello"
  const val Zone = "zona_pannello"
}

@Composable
internal fun MapPanesLayout(
  panes: GalleryPanes,
  split: PaneWindows?,
  modifier: Modifier = Modifier,
  map: @Composable (Modifier) -> Unit,
  zone: @Composable (Modifier) -> Unit,
) {
  Row(modifier = modifier.fillMaxSize()) {
    Box(modifier = Modifier.weight(1f).fillMaxHeight().testTag(MapPanesTags.Map)) {
      ProvidePaneWindow(split?.other) { map(Modifier.fillMaxSize()) }
    }
    if (panes is GalleryPanes.Docked && split != null) {
      Box(modifier = Modifier.width(split.grid.widthDp.dp).fillMaxHeight().testTag(MapPanesTags.Zone)) {
        ProvidePaneWindow(split.grid) { zone(Modifier.fillMaxSize()) }
      }
    }
  }
}
