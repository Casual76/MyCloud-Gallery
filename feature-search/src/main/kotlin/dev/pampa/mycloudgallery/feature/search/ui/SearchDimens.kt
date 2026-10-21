package dev.pampa.mycloudgallery.feature.search.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Le misure di layout di Cerca e della pagina Ricerca e modelli, in un posto solo (e' l'unico file del modulo che scrive dei `dp`:
 * `NoHandWrittenStyleTest` lo verifica). Colori, raggi, dimensioni di testo e movimento vengono dall'engine (`MaterialTheme`, `FluidRadius`,
 * `FluidMotion`); i margini di pagina e i bersagli da 48 dp da `GalleryDimens`.
 */
internal object SearchDimens {

  /** Il lato minimo di una cella dei risultati: sul telefono 4 colonne, sul tablet tante quante ne entrano. */
  val CellMin: Dp = 92.dp

  /** Lo spazio fra due blocchi di una pagina (suggerimenti, chip, sezioni). */
  val BlockGap: Dp = 12.dp

  /** Lo spazio stretto fra due elementi della stessa riga (chip, pillole, pulsanti). */
  val TightGap: Dp = 8.dp

  /** Il margine sopra e sotto un avviso o uno stato vuoto. */
  val NoticeInset: Dp = 8.dp

  /** Il lato della miniatura nel chip "Simili a". */
  val SimilarThumb: Dp = 24.dp

  /** Lo spazio sotto l'ultima riga di una lista, perche' la barra delle schede non copra niente. */
  val ListBottom: Dp = 96.dp

  /** Il lato dell'icona grande di uno stato vuoto. */
  val EmptyGlyph: Dp = 40.dp

  /** La larghezza massima del testo di una pagina di lettura (attivazione): righe corte anche sul tablet. */
  val ReadingWidth: Dp = 560.dp

  /** La larghezza massima di una pagina di impostazioni sul tablet. */
  val PageWidth: Dp = 640.dp

  /** La barra di avanzamento di un modello. */
  val ProgressHeight: Dp = 6.dp
}
