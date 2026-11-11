package dev.pampa.mycloudgallery.feature.library.library

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState

/**
 * La ricerca dentro la Libreria (H4 della fase H, 11/11): niente pagina di ricerca. Con la ricerca aperta ([active]) la Libreria resta la stessa (stessa
 * griglia, stessa posizione); in alto, al posto dell'etichetta e della capsula dei comandi, c'e' la barra dei suggerimenti ([Top]); quando la ricerca ha
 * qualcosa da mostrare ([showsResults]) i risultati ([Results]) prendono il posto della griglia **dentro lo stesso fondale** (il vetro li rifrange), con
 * una dissolvenza breve. La barra di ricerca in basso e' dello scaffold di `:app`, non della Libreria.
 *
 * In selezione (la selezione dei risultati e' quella della griglia della Libreria) in alto tornano i pezzi della Libreria, che dicono quanti sono e
 * hanno "Fine", e in basso c'e' la barra delle azioni di sempre.
 *
 * La Libreria non conosce la ricerca (i `feature-*` non si conoscono): `:app` implementa questo con i pezzi di feature-search. Le proprieta' si leggono
 * in composizione e cambiano quando la ricerca apre, chiude o risponde, mai a ogni fotogramma.
 */
@Stable
interface LibrarySearchLayer {
  /** La ricerca e' aperta. Falso mentre si richiude: la barra dei suggerimenti e i risultati escono in dissolvenza. */
  val active: Boolean

  /** Sopra la griglia va lo strato della ricerca: i risultati, oppure, senza risultati, il velo che attenua la Libreria (CU-11). */
  val showsResults: Boolean

  /**
   * I risultati coprono del tutto la Libreria, che allora esce dalla composizione. Falso senza risultati: la Libreria resta dietro, attenuata dal velo,
   * cosi' lo schermo non diventa un fondo vuoto (CU-11 del giro 1).
   */
  val coversLibrary: Boolean

  /** La barra dei suggerimenti, nella riga in alto (sotto la barra di stato). [backdrop] e' il fondale della pagina. */
  @Composable
  fun Top(backdrop: GlassBackdropState, modifier: Modifier)

  /**
   * I risultati, a tutta pagina e con il fondo della pagina. [contentPadding]: lo spazio della riga in alto e del posto della barra in basso. La
   * salita con la tastiera la fanno loro (la Libreria non la conosce).
   */
  @Composable
  fun Results(contentPadding: PaddingValues, modifier: Modifier)
}
