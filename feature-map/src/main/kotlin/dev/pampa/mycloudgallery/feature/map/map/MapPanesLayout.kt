package dev.pampa.mycloudgallery.feature.map.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.pampa.mycloudgallery.core.designsystem.adaptive.GalleryPanes
import dev.pampa.mycloudgallery.core.designsystem.adaptive.PaneWindows
import dev.pampa.mycloudgallery.core.designsystem.adaptive.ProvidePaneWindow

// I DUE PANNELLI DELLA MAPPA (Fase 10, P5, D13 (2); docs/fase10-progetto.md §2.3, §1.5 `ZoneSideState`): la mappa a sinistra (60%), la griglia della zona a destra (40%) quando si tocca una bolla; chiusa, la
// mappa e' intera. Il posto della mappa nell'albero e' sempre lo stesso (una `Row` con la mappa che prende il resto): aprire o chiudere la zona non ricrea la mappa, la sua superficie vive nel ViewModel. La
// griglia entra scorrendo dal bordo con il movimento dell'engine (nessuna dissolvenza: una pagina in arrivo non sfuma mai). La **mappa cambia misura di colpo**, non animata: ridimensionare la superficie di
// MapLibre a ogni fotogramma sarebbe lavoro pesante (nota per la fase grafica: animare un margine della camera invece della misura).

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
  val docked = panes is GalleryPanes.Docked
  // Durante l'uscita la griglia tiene la larghezza di prima: la finestra che la dava non c'e' piu'.
  var zoneWidthDp by remember { mutableIntStateOf(0) }
  if (split != null) zoneWidthDp = split.grid.widthDp
  Row(modifier = modifier.fillMaxSize()) {
    Box(modifier = Modifier.weight(1f).fillMaxHeight().testTag(MapPanesTags.Map)) {
      ProvidePaneWindow(split?.other) { map(Modifier.fillMaxSize()) }
    }
    AnimatedVisibility(
      visible = docked,
      enter = slideInHorizontally(FluidMotion.intOffset()) { width -> width },
      exit = slideOutHorizontally(FluidMotion.intOffset()) { width -> width },
    ) {
      Box(modifier = Modifier.width(zoneWidthDp.dp).fillMaxHeight().testTag(MapPanesTags.Zone)) {
        ProvidePaneWindow(split?.grid) { zone(Modifier.fillMaxSize()) }
      }
    }
  }
}
