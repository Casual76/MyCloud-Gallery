package dev.pampa.mycloudgallery.feature.library.collections

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Le misure di layout di Raccolte, Nascosti ed Eliminati di recente, in un posto solo: e' l'unico file dei tre package
 * che scrive dei `dp` (`CollectionsStyleTest` lo verifica).
 *
 * Colori, raggi, dimensioni di testo e movimento NON stanno qui: vengono dall'engine (`MaterialTheme.colorScheme`,
 * `FluidRadius`, `MaterialTheme.typography`, `FluidMotion`). I margini di pagina e la fessura fra le celle sono quelli di
 * `GalleryDimens`. Qui restano solo le taglie dei pochi elementi propri di queste pagine.
 */
internal object CollectionsDimens {

  /** Nessuno spazio. */
  val None: Dp = 0.dp

  /** Il margine interno di uno stato vuoto o di un avviso a tutta pagina. */
  val StatePadding: Dp = 32.dp

  /** Lo spazio fra l'illustrazione, il titolo, la frase e l'azione di uno stato. */
  val StateGap: Dp = 12.dp

  /** La larghezza massima del testo di uno stato: oltre, una riga non si legge piu' bene. */
  val StateTextMaxWidth: Dp = 340.dp

  /** Lo spazio sopra e sotto il riepilogo in fondo a una griglia. */
  val FooterPadding: Dp = 16.dp

  /** Il lato minimo di una cella degli Eliminati di recente: le colonne si adattano alla larghezza. */
  val TrashCellMin: Dp = 104.dp

  /** Il margine interno della pillola "giorni rimasti" sopra una cella. */
  val DaysPillHorizontal: Dp = 6.dp
  val DaysPillVertical: Dp = 2.dp

  /** Lo spazio fra il lucchetto e la parola del segno "Nascosto", e il lato del lucchetto. */
  val PillGap: Dp = 3.dp
  val PillGlyph: Dp = 12.dp

  /** Lo spazio sotto la spiegazione del cestino, prima della griglia. */
  val ExplainerGap: Dp = 8.dp

  /** "Modifica Raccolte": il rientro e lo spazio verticale delle frasi sopra e sotto l'elenco delle sezioni. */
  val NoteInset: Dp = 4.dp
  val NoteGap: Dp = 8.dp

  /** "Modifica Raccolte": quanto si solleva la riga che il dito trascina (l'ombra la stacca dalle altre: nessuna riga ne copre un'altra). */
  val DragElevation: Dp = 8.dp

  /** H5: la piastrella delle righe delle Raccolte (icona o copertina): piu' grande di quella di serie (32 dp), una foto a 32 dp non si legge. */
  val RowArt: Dp = 44.dp
}
