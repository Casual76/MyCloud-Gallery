package dev.pampa.mycloudgallery.feature.memories.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Le misure di layout delle schermate dei ricordi, in un posto solo (e' l'unico file del modulo che scrive dei `dp`: `NoHandWrittenStyleTest` lo
 * verifica). Colori, raggi, dimensioni di testo e movimento vengono dall'engine (`MaterialTheme`, `FluidRadius`, `FluidMotion`); i margini di
 * pagina e il bersaglio da 48 dp da `GalleryDimens`.
 */
internal object MemoriesDimens {

  /** L'altezza minima di una riga o di un controllo che si tocca (il bersaglio da 48 dp). */
  val Touch: Dp = 48.dp

  /** Il lato minimo di una cella della griglia di una pagina. */
  val CellMin: Dp = 92.dp

  /** La colonna di lettura e la pagina con una griglia sul tablet. */
  val SheetWidth: Dp = 560.dp
  val PageWidth: Dp = 1120.dp

  /** Lo spazio fra due blocchi di una pagina e lo spazio stretto fra due elementi. */
  val BlockGap: Dp = 12.dp
  val TightGap: Dp = 8.dp

  /** Il margine interno di una striscia o di una scheda. */
  val Inset: Dp = 16.dp

  /** I1 (fase I): la scheda piu' stretta di "Tutti i ricordi" (due per riga sul telefono da 360 dp), alta 3:4. Il margine del titolo e il punto "nuovo" sono della
   * scheda comune (`GalleryCoverCardDefaults`, core-designsystem). */
  val CardMin: Dp = 140.dp
  const val CardAspect: Float = 3f / 4f

  /** Il lato dell'icona dentro un riquadro vuoto e quello di un glifo accanto a un testo. */
  val EmptyGlyph: Dp = 40.dp
  val Glyph: Dp = 24.dp

  /** Il pallino di una scelta nei fogli. */
  val Radio: Dp = 24.dp
  val RadioCheck: Dp = 16.dp
  val RadioRing: Dp = 1.5.dp

  /** Lo spazio sotto l'ultima riga di una lista, perche' la barra delle schede non copra niente. */
  val ListBottom: Dp = 96.dp

  // --- La presentazione -------------------------------------------------------------------------------------------------------------

  /** Il margine dei comandi dai bordi dello schermo e il lato di un tasto tondo della presentazione (sopra il bersaglio da 48 dp). */
  val PlayerInset: Dp = 16.dp
  val PlayerButton: Dp = 56.dp

  /** La miniatura nella carta finale. */
  val EndThumbMin: Dp = 72.dp

  /** La striscia di avanzamento sotto la presentazione. */
  val ProgressHeight: Dp = 4.dp

  /** Il lato del glifo "video" sopra un fotogramma. */
  val VideoGlyph: Dp = 48.dp
}
