package dev.pampa.mycloudgallery.core.designsystem.adaptive

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidListDetailSizes

// LE MISURE DI ELENCO E DETTAGLIO PER L'ENGINE (Fase 10, P5, D12; docs/fase10-progetto.md §2.3). **File nuovo** in `adaptive/`. `FluidListDetailScaffold` decide da solo, dalla larghezza che gli resta, se mostra uno o due
// pannelli; qui la decisione la prende [GalleryPaneRules] (la finestra comune, che dentro un pannello e' quella del pannello) e all'engine si da' il solo modo di eseguirla: due pannelli sempre
// (senza altro minimo del dettaglio) o uno solo. Cosi' Raccolte e Impostazioni cambiano pannelli alla stessa soglia di tutte le altre schermate, anche nei test con una finestra imposta.

object FluidPaneSizes {
  /** Un dettaglio che non sta mai: l'engine mostra l'elenco da solo. */
  private val NeverFits: Dp = Dp.Infinity

  /** Il minimo del dettaglio quando [GalleryPaneRules] ha deciso per due pannelli: l'elenco prende comunque il suo 40% (fra 360 e 500 dp) e il dettaglio il resto. */
  private val AlwaysFits: Dp = 1.dp

  /** Le misure di una pagina elenco e dettaglio per [window]. */
  fun listDetail(window: GalleryWindow): FluidListDetailSizes =
    if (GalleryPaneRules.twoPanes(window)) FluidListDetailSizes(detailMin = AlwaysFits) else FluidListDetailSizes(detailMin = NeverFits)
}
