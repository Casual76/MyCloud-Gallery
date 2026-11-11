package dev.pampa.mycloudgallery.feature.search.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Le misure di layout della ricerca e della pagina Ricerca e modelli, in un posto solo (e' l'unico file del modulo che scrive dei `dp`:
 * `NoHandWrittenStyleTest` lo verifica). Colori, raggi, dimensioni di testo e movimento vengono dall'engine (`MaterialTheme`, `FluidRadius`,
 * `FluidMotion`); i margini di pagina e i bersagli da 48 dp da `GalleryDimens`.
 */
internal object SearchDimens {

  /** Il lato minimo di una cella dei risultati: sul telefono 3 colonne, sul tablet tante quante ne entrano. */
  val CellMin: Dp = 92.dp

  /** Lo spazio fra due blocchi di una pagina. */
  val BlockGap: Dp = 12.dp

  /** Lo spazio stretto fra due elementi della stessa riga (pillole, pulsanti). */
  val TightGap: Dp = 8.dp

  /** Il margine sopra e sotto un avviso o un blocco di testo. */
  val NoticeInset: Dp = 8.dp

  /** La larghezza massima del testo di una pagina di lettura (attivazione): righe corte anche sul tablet. */
  val ReadingWidth: Dp = 560.dp

  /** L'intestazione dei meno probabili, sopra le loro celle nella griglia dei risultati (H4, CU-3). */
  val DividerHeight: Dp = 40.dp

  /** Il filo fra i sicuri e i meno probabili: lo spazio che occupa nella griglia e lo spessore della linea (CU-3). */
  val BoundaryHeight: Dp = 16.dp
  val BoundaryLine: Dp = 1.dp
}
