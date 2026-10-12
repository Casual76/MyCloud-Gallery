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
}
