package dev.pampa.mycloudgallery.feature.settings.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Le misure di layout delle Impostazioni, in un posto solo (e' l'unico file del modulo che scrive dei `dp`:
 * `NoHandWrittenStyleTest` lo verifica).
 *
 * Colori, raggi, dimensioni di testo e movimento NON stanno qui: vengono dall'engine
 * (`MaterialTheme.colorScheme`, `FluidRadius`, `MaterialTheme.typography`, `FluidMotion`). Gli
 * spazi fra le sezioni di una pagina sono quelli di `FluidScreenDefaults`, i margini dei fogli quelli di
 * `GalleryDimens`. Qui restano solo le taglie dei pochi elementi propri di queste pagine.
 */
internal object SettingsDimens {

  /** Il lato massimo di un QR mostrato a schermo: abbastanza grande da essere letto a un braccio di distanza. */
  val QrMaxWidth: Dp = 300.dp

  /** Lo spazio fra due elementi dentro la stessa scheda. */
  val CardGap: Dp = 12.dp

  /** Il margine interno di una scheda. */
  val CardPadding: Dp = 16.dp

  /** Il lato della piastrella con l'icona grande nell'identita' di un dispositivo. */
  val IdentityTile: Dp = 56.dp

  /** L'icona dentro quella piastrella. */
  val IdentityGlyph: Dp = 28.dp

  /** Lo spazio minimo fra due righe di testo di una stessa scheda. */
  val TightGap: Dp = 4.dp

  /** Lo spazio fra due pillole di stato affiancate. */
  val PillGap: Dp = 6.dp

  /** La sfumatura sopra una barra di tasti fermi in fondo a una pagina ([PinnedActionDock]): da qui il contenuto che scorre sotto sparisce nel fondo. */
  val DockFade: Dp = 16.dp

  /** Il respiro sotto i tasti di quella barra, sopra la barra di navigazione di sistema. */
  val DockPadding: Dp = 16.dp

  // --- I3 (fase I) ---------------------------------------------------------------------------------------------------------------

  /** Il glifo della «i» davanti a una spiegazione dietro un tocco. */
  val InfoGlyph: Dp = 18.dp

  /** La piastrella grande di una scelta a scheda (i preset di «Quando lavora l'app», i passi). */
  val ChoiceTile: Dp = 44.dp

  /** Un glifo di condizione sotto una scelta (la notte, la corrente, il Wi-Fi). */
  val ConditionGlyph: Dp = 16.dp

  /** Lo spazio fra due schede di scelta. */
  val ChoiceGap: Dp = 10.dp

  /** Lo spessore del bordo della scheda scelta. */
  val ChoiceBorder: Dp = 2.dp

  /** Il lato del segno di stato a destra di una riga. */
  val RowMark: Dp = 22.dp

  /** La larghezza massima di un valore a destra di una riga: il titolo resta leggibile. */
  val ValueMaxWidth: Dp = 120.dp

  // --- I5 (icona dell'app a scelta) -------------------------------------------------------------------------------------------------

  /** Il lato di un'anteprima grande dell'icona dell'app: tre in fila stanno a 360 dp con il loro anello. */
  val AppIconPreview: Dp = 80.dp

  /** L'angolo di quell'anteprima, come le icone del launcher di One UI (circa un terzo del lato). */
  val AppIconRadius: Dp = 24.dp

  /** Il lato dell'anteprima piccola nella riga «Icona dell'app» (quello delle piastrelle delle righe). */
  val AppIconRow: Dp = 32.dp

  /** L'angolo dell'anteprima piccola. */
  val AppIconRowRadius: Dp = 9.dp

  /** Lo spessore dell'anello attorno all'icona scelta. */
  val AppIconRing: Dp = 3.dp

  /** Lo spazio fra l'icona e il suo anello. */
  val AppIconRingGap: Dp = 4.dp

  /** Il segno di spunta sull'angolo dell'icona scelta. */
  val AppIconMark: Dp = 24.dp

  /** Il filo attorno a un'anteprima: la «Scura» sul fondo scuro e la «Chiara» sul fondo chiaro hanno ancora il loro bordo. */
  val AppIconOutline: Dp = 1.dp

  // --- Giro 1 della fase I ----------------------------------------------------------------------------------------------------------

  /** Il margine interno della testata di stato di una sottopagina. */
  val HeaderPadding: Dp = 20.dp

  /** Lo spazio fra la piastrella grande e il valore nella testata. */
  val HeaderGap: Dp = 16.dp

  /** Il segno di una scelta fra piu' opzioni (il tondo, la spunta). */
  val ChoiceMark: Dp = 24.dp
}
