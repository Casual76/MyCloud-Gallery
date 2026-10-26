package dev.pampa.mycloudgallery.feature.library.utility

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Le misure di layout dell'Utility (Doppioni, pulizia guidata) e della parte del NAS degli Eliminati di recente, in un posto solo: e' l'unico file
 * di `utility/` che scrive dei `dp` (`UtilityStyleTest` lo verifica; `CollectionsStyleTest` lo vuole per i package di `trash/`, che prendono le
 * loro misure da qui o da `CollectionsDimens`).
 *
 * Colori, raggi, dimensioni di testo e movimento NON stanno qui: vengono dall'engine. I margini di pagina e la fessura fra le celle sono quelli di
 * `GalleryDimens`. Qui restano le taglie dei pochi elementi propri di queste pagine.
 */
internal object UtilityDimens {

  /** Nessuno spazio. */
  val None: Dp = 0.dp

  /** Il pallino di selezione di una riga, il suo bordo e il segno dentro. */
  val SelectDot: Dp = 24.dp
  val SelectRing: Dp = 2.dp
  val SelectGlyph: Dp = 16.dp

  /** Lo spazio fra le righe di un elenco di gruppi o voci. */
  val RowGap: Dp = 8.dp

  /** Lo spazio sopra un'intestazione di sezione e sotto di essa. */
  val SectionTop: Dp = 16.dp
  val SectionBottom: Dp = 4.dp

  /** Il lato della miniatura di una copia o di uno scatto in un gruppo, e lo spazio fra due. */
  val Thumb: Dp = 72.dp
  val ThumbLarge: Dp = 96.dp
  val ThumbGap: Dp = 6.dp

  /** Il margine interno di una scheda di gruppo. */
  val CardPadding: Dp = 12.dp

  /** La larghezza massima di un elenco: sul tablet le righe non si allungano oltre. */
  val ListMaxWidth: Dp = 640.dp

  /** La larghezza minima di una colonna dei gruppi simili. */
  val ClusterColumn: Dp = 320.dp

  /** Il lato del segno (la spunta o il cestino) sopra una miniatura, e il tondo che lo porta. */
  val BadgeGlyph: Dp = 14.dp
  val ShotBadge: Dp = 24.dp

  /** Il margine fra la pista delle due schede e le schede stesse. */
  val TabInset: Dp = 4.dp

  /** Lo spazio in fondo a una pagina con la barra delle azioni sotto. */
  val ActionBarClearance: Dp = 96.dp
}
