package dev.pampa.mycloudgallery.feature.map.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Le misure di layout delle schermate della mappa, in un posto solo (e' l'unico file del modulo che scrive dei `dp`: `NoHandWrittenStyleTest` lo verifica).
 * Colori, raggi, dimensioni di testo e movimento vengono dall'engine (`MaterialTheme`, `FluidRadius`, `FluidMotion`); i margini di pagina e il bersaglio da
 * 48 dp da `GalleryDimens`.
 */
internal object MapDimens {

  /** L'altezza minima di una riga o di un controllo che si tocca (il bersaglio da 48 dp). */
  val Touch: Dp = 48.dp

  /** Il lato di una bolla (la miniatura tonda): sopra il bersaglio da 48 dp, cosi' si prende bene anche con un dito grosso. */
  val Bubble: Dp = 56.dp

  /** Il bordo della miniatura di una bolla e il lato del segno del conto. */
  val BubbleRing: Dp = 3.dp
  val CountBadgeMin: Dp = 24.dp

  /** Lo spazio fra la bolla e il suo nome, e la larghezza massima di un nome (a testo grande va a capo, non si taglia). */
  val LabelGap: Dp = 2.dp
  val LabelMaxWidth: Dp = 136.dp

  /** La larghezza della colonna di lettura (Impostazioni -> Mappa) e della pagina con una griglia sul tablet. */
  val SheetWidth: Dp = 560.dp
  val PageWidth: Dp = 1120.dp

  /** Lo spazio fra due blocchi e lo spazio stretto fra due elementi. */
  val BlockGap: Dp = 12.dp
  val TightGap: Dp = 8.dp

  /** Il margine interno di una striscia o di una scheda, e dei controlli dai bordi dello schermo. */
  val Inset: Dp = 16.dp

  /** Il lato minimo di una cella della griglia di una zona. */
  val CellMin: Dp = 92.dp

  /** Il lato dell'icona dentro un controllo e quello di un glifo accanto a un testo. */
  val Glyph: Dp = 24.dp
  val EmptyGlyph: Dp = 40.dp

  /** Lo spazio sotto l'ultima riga di una lista, perche' la barra delle schede non copra niente. */
  val ListBottom: Dp = 96.dp

  /** Il riquadro vuoto sotto la mappa finta degli screenshot: una griglia di linee ogni tanto. */
  val FakeGrid: Dp = 64.dp
  val FakeLine: Dp = 1.dp

  /** La mini-mappa delle info: 320 x 160 dp (§2.10). */
  val MiniMapWidth: Dp = 320.dp
  val MiniMapHeight: Dp = 160.dp
}
