package dev.pampa.mycloudgallery.feature.people.activation

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Le misure di layout della pagina di attivazione, dell'avviso e di Impostazioni -> Persone, in un posto solo (e' l'unico file di `activation/` che
 * scrive dei `dp`: `ActivationStyleTest` lo verifica). Colori, raggi, dimensioni di testo e movimento vengono dall'engine (`MaterialTheme`, `FluidRadius`,
 * `FluidMotion`); i margini di pagina e i bersagli da 48 dp da `GalleryDimens`.
 */
internal object ActivationDimens {

  /** Lo spazio fra due blocchi di una pagina (avvisi, pulsanti, sezioni). */
  val BlockGap: Dp = 12.dp

  /** Lo spazio stretto fra due elementi della stessa riga (pulsanti affiancati). */
  val TightGap: Dp = 8.dp

  /** Lo spazio fra due paragrafi del testo di spiegazione. */
  val ParagraphGap: Dp = 14.dp

  /** La larghezza massima del testo di una pagina di lettura: righe corte anche sul tablet. */
  val ReadingWidth: Dp = 560.dp
}
