package dev.pampa.mycloudgallery.feature.people.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Le misure di layout delle schermate delle persone, in un posto solo (e' l'unico file del modulo che scrive dei `dp`:
 * `NoHandWrittenStyleTest` lo verifica). Colori, raggi, dimensioni di testo e movimento vengono dall'engine (`MaterialTheme`, `FluidRadius`,
 * `FluidMotion`); i margini di pagina e il bersaglio da 48 dp da `GalleryDimens`.
 */
internal object PeopleDimens {

  /** La tessera piu' stretta di una persona nelle griglie: sul telefono da 360 dp tre colonne (come la Galleria di Samsung), sul tablet tante quante ne entrano. */
  val TileMin: Dp = 96.dp

  /** I1 (fase I): la scheda di una persona e' alta (3:4), il volto la riempie. */
  const val CardAspect: Float = 3f / 4f

  /** I1: il lato del ritaglio chiesto per una scheda (il volto riempie l'altezza): sul telefono arriva al tetto [CropMaxPx], come le schede delle Raccolte. */
  val CardCrop: Dp = 144.dp

  /** I1: il margine del nome sopra la sfumatura in basso, e del segno della scelta in alto. */
  val CardInset: Dp = 10.dp

  /** Il lato dell'avatar grande in testa alla pagina di una persona. */
  val AvatarHeader: Dp = 112.dp

  /** Il lato dell'avatar in una riga di un gruppo lista (proposte, persone nascoste, "Forse e' anche…"). */
  val AvatarRow: Dp = 44.dp

  /** Il lato di ognuno dei due avatar affiancati nella riga di una proposta. */
  val AvatarPair: Dp = 32.dp

  /** Il lato dell'avatar dei due volti di un foglio di unione. */
  val AvatarSheet: Dp = 72.dp

  /** Lo spessore dell'anello di una tessera scelta. */
  val Ring: Dp = 3.dp

  /** Lo spazio fra due blocchi di una pagina. */
  val BlockGap: Dp = 12.dp

  /** Lo spazio stretto fra due elementi della stessa riga. */
  val TightGap: Dp = 8.dp

  /** Il margine interno di una striscia o di una scheda. */
  val Inset: Dp = 16.dp

  /** Lo spazio sotto l'ultima riga di una lista, perche' la barra delle schede non copra niente. */
  val ListBottom: Dp = 96.dp

  /** Il lato dell'icona dentro un avatar vuoto. */
  val AvatarGlyph: Dp = 28.dp

  /** La larghezza massima di un foglio o di una pagina di lettura sul tablet. */
  val SheetWidth: Dp = 560.dp

  /** La larghezza massima di una pagina con una griglia: si allarga piu' di una colonna di testo. */
  val PageWidth: Dp = 1120.dp

  /** L'altezza minima di una riga che si tocca (il bersaglio da 48 dp, di serie). */
  val Touch: Dp = 48.dp

  /** Il lato minimo di una cella della griglia delle foto di una persona. */
  val CellMin: Dp = 92.dp

  /** Il lato minimo di una tessera della griglia dei volti ("Controlla le foto", "Dividi", "Altri volti"). */
  val FaceMin: Dp = 88.dp

  /** Il lato del pallino di una scelta. */
  val Radio: Dp = 24.dp

  /** La spunta dentro il pallino. */
  val RadioCheck: Dp = 16.dp

  /** Lo spessore dell'anello di una scelta non fatta. */
  val RadioRing: Dp = 1.5.dp

  /** La spunta accanto al nome in una tessera scelta. */
  val CheckGlyph: Dp = 18.dp

  /** L'icona grande di uno stato vuoto. */
  val EmptyGlyph: Dp = 40.dp

  /** Quanti volti al massimo si offrono nel foglio "Scegli copertina". */
  const val CoverChoices: Int = 60

  /** I pixel minimo e massimo di un ritaglio (qualunque sia la densita'): un avatar non chiede un'immagine enorme. */
  const val CropMinPx: Int = 96
  const val CropMaxPx: Int = 384
}
