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

  /** CS-1: l'icona sulla piastrella di una famiglia (26 dp: la proporzione dell'icona della piastrella di serie, 19 su 32). */
  val RowArtGlyph: Dp = 26.dp

  /** CS-1: un volto quando sono due o tre nella stessa piastrella, e il filo che lo stacca dal vicino. */
  val FaceDisc: Dp = 26.dp
  val FaceRing: Dp = 1.5.dp

  /** CU-15: la fessura fra i quadrati del mosaico della Mappa, e il segno della mappa al centro (il disco e il suo glifo). */
  val MosaicGap: Dp = 2.dp
  val MapBadge: Dp = 18.dp
  val MapBadgeGlyph: Dp = 12.dp

  // I1 (fase I, 11/11): le Raccolte come una galleria. Le schede si leggono a colpo d'occhio sul telefono da 360 dp: due persone e mezza, un ricordo e
  // un pezzo del successivo, due luoghi e mezzo per riga (il pezzo che sporge dice "scorri di lato").

  /** Lo spazio fra due schede di un carosello, e fra due piastrelle. */
  val CarouselGap: Dp = 10.dp

  /** La scheda di una persona: alta, il volto a tutta scheda (3:4 circa). */
  val PersonCardWidth: Dp = 116.dp
  val PersonCardHeight: Dp = 156.dp

  /** La scheda larga di un ricordo o di un viaggio (3:2). */
  val WideCardWidth: Dp = 264.dp
  val WideCardHeight: Dp = 176.dp

  /** La scheda di un luogo: quadrata, la foto e il nome. */
  val PlaceCardSide: Dp = 136.dp

  /** Il margine del testo dentro una scheda, sopra la sfumatura. */
  val CardTextInset: Dp = 12.dp

  /** Il lato dell'icona grande al posto della foto che non c'e' (una persona senza volto pronto, la scheda "tutti"). */
  val CardGlyph: Dp = 40.dp

  /** Il tondo della freccia della scheda "tutti". */
  val MoreDisc: Dp = 44.dp

  /** Il filo della scheda scelta sul tablet. */
  val SelectedRing: Dp = 3.dp

  /** Il punto "nuovo" su un ricordo, e il suo filo. */
  val NewDot: Dp = 10.dp
  val NewDotRing: Dp = 2.dp

  /** L'altezza minima del titolo di una sezione (il bersaglio da 48 dp quando porta a "tutti"), e lo spazio fra il nome e la freccia. */
  val SectionTitleMin: Dp = 48.dp
  val SectionTitleGap: Dp = 4.dp

  /** La mappa in cima ai Luoghi: larga quanto la pagina, alta la meta' (al piu' [MapCardMaxHeight]). */
  val MapCardMaxHeight: Dp = 220.dp

  /** Una foto appuntata sulla mappa, il suo filo, e la distanza sotto cui due foto non si appuntano una sull'altra. */
  val MapPin: Dp = 46.dp
  val MapPinRing: Dp = 2.5.dp
  val MapPinMinGap: Dp = 40.dp

  /** Il segno della mappa grande (quando la carta non c'e'), e la capsula "Mappa" in basso a sinistra. */
  val MapBadgeLarge: Dp = 44.dp
  val MapChipPadding: Dp = 10.dp
  val MapChipGlyph: Dp = 18.dp
  val MapChipHeight: Dp = 32.dp

  /** Le piastrelle compatte (Utility, NAS, Nascosti): la larghezza minima (due per riga sul telefono), l'altezza minima, il margine e l'icona. */
  val TileMinWidth: Dp = 150.dp
  val TileMinHeight: Dp = 64.dp
  val TilePadding: Dp = 10.dp

  /** Il numero su una piastrella (le operazioni in corso): il margine della capsula. */
  val TileBadgeHorizontal: Dp = 8.dp
  val TileBadgeVertical: Dp = 2.dp

  /** L'invito di una sezione che non ha ancora niente da mostrare: alto quanto una scheda bassa. */
  val InviteHeight: Dp = 104.dp

  /** Un segno di stato sotto un carosello (Altri volti, Senza posizione): la capsula, il suo margine e l'icona. */
  val ChipHeight: Dp = 40.dp
  val ChipPadding: Dp = 14.dp
  val ChipGlyph: Dp = 18.dp
}
