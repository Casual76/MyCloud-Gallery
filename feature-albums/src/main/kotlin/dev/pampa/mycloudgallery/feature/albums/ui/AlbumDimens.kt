package dev.pampa.mycloudgallery.feature.albums.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Le misure di layout delle schermate degli album, in un posto solo (e' l'unico file del modulo che scrive dei `dp`:
 * `NoHandWrittenStyleTest` lo verifica). Colori, raggi, dimensioni di testo e movimento vengono dall'engine (`MaterialTheme`, `FluidRadius`,
 * `FluidMotion`); i margini di pagina e i bersagli da 48 dp da `GalleryDimens`.
 */
internal object AlbumDimens {

  /** Il lato minimo di una cella della griglia dell'album: sul telefono 4 colonne, sul tablet tante quante ne entrano. */
  val CellMin: Dp = 92.dp

  /** La scheda piu' stretta di un album nell'elenco: la copertina quadrata, con sotto il nome e il conto. 2 colonne sul telefono. */
  val CardMin: Dp = 152.dp

  /** Il lato dell'icona dentro una copertina vuota. */
  val CoverGlyph: Dp = 24.dp

  /** Lo spazio fra due blocchi di una pagina. */
  val BlockGap: Dp = 12.dp

  /** Lo spazio stretto fra due elementi della stessa riga. */
  val TightGap: Dp = 8.dp

  /** Il margine interno di una striscia o di una scheda. */
  val Inset: Dp = 16.dp

  /** Lo spazio sotto l'ultima riga di una lista, perche' la barra delle schede non copra niente. */
  val ListBottom: Dp = 96.dp

  /** Il lato dell'icona grande di uno stato vuoto. */
  val EmptyGlyph: Dp = 40.dp

  /** Il lato di una miniatura dell'anteprima delle regole. */
  val SampleThumb: Dp = 64.dp

  /** Il lato di una foto d'esempio. */
  val ExampleThumb: Dp = 72.dp

  /** L'altezza della copertina di una proposta di viaggio. */
  val TripCoverHeight: Dp = 168.dp

  /** La larghezza massima di un foglio o di una pagina di lettura sul tablet. */
  val SheetWidth: Dp = 560.dp

  /** La larghezza massima del contenuto di una pagina d'album: la griglia si allarga di piu' di una colonna di testo. */
  val PageWidth: Dp = 1120.dp

  /** L'altezza minima di una riga che si tocca (il bersaglio da 48 dp, di serie). */
  val Touch: Dp = 48.dp

  /** Il lato del pallino di un livello scelto (il cerchio con la spunta). */
  val Radio: Dp = 24.dp

  /** Lo spessore dell'anello di un'opzione non scelta. */
  val RadioRing: Dp = 1.5.dp

  /** La spunta dentro il pallino. */
  val RadioCheck: Dp = 16.dp

  /** Il lato della piastrella con l'icona in una riga di striscia. */
  val Tile: Dp = 36.dp

  /** Il lato dell'icona dentro la piastrella. */
  val TileGlyph: Dp = 20.dp

  /** Fase 8: l'altezza massima dell'elenco di persone nel foglio di scelta; oltre, l'elenco scorre dentro il foglio (e il foglio resta sul telefono). */
  val PickerMaxHeight: Dp = 360.dp

  /**
   * G2b: la copertina piu' stretta della scheda Album. Tre colonne sul telefono (412 dp), come Galleria Samsung; due col testo grande (la misura cresce
   * con il carattere); sul tablet tante quante ne entrano, al piu' [TabMaxColumns].
   */
  val TabCardMin: Dp = 104.dp

  /** G2b: lo spazio fra due copertine della stessa riga della scheda Album. */
  val TabGap: Dp = 12.dp

  /** G2b: lo spazio fra il nome e il numero sotto una copertina: stretto, sono la stessa voce. */
  val TabCaptionGap: Dp = 2.dp

  /** G2b: lo spazio fra la copertina e il nome. */
  val TabCoverGap: Dp = 6.dp

  /** G2b: l'altezza della riga ferma sotto una copertina dello scheletro (quella del nome). */
  val TabSkeletonLine: Dp = 12.dp

  /** G2b: le colonne della scheda Album sul tablet: oltre, le copertine diventerebbero francobolli su uno schermo che ha spazio. */
  const val TabMaxColumns: Int = 7
}
