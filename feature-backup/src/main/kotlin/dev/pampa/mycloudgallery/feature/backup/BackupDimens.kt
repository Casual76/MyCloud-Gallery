package dev.pampa.mycloudgallery.feature.backup

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Le misure di layout delle schermate del backup, in un posto solo: e' l'unico file del modulo che scrive dei `dp` (`BackupStyleTest` lo
 * verifica, come `NasStyleTest` per la 4B). Colori, raggi, dimensioni di testo e movimento NON stanno qui: vengono dall'engine
 * (`MaterialTheme.colorScheme`, `FluidRadius`, `MaterialTheme.typography`, `FluidMotion`). Margini di pagina e bersagli sono quelli di
 * `GalleryDimens`; la chrome (capsule, sfumatura) ha le misure di `GalleryChromeDefaults` e di `core-designsystem/design` (D30: nessuno
 * stile nuovo).
 */
internal object BackupDimens {

  /** Nessuno spazio. */
  val None: Dp = 0.dp

  /** Lo spazio fra due blocchi di una pagina (scheda di stato, gruppo, nota). */
  val BlockGap: Dp = 12.dp

  /** Lo spazio fra le righe di un blocco (titolo e frase di una scheda). */
  val LineGap: Dp = 4.dp

  /** Lo spazio interno di una scheda con dei numeri grandi o con dei tasti. */
  val CardPadding: Dp = 16.dp

  /** Lo spazio fra le righe di una scheda. */
  val CardGap: Dp = 10.dp

  /** La larghezza massima di una pagina "cruscotto" su uno schermo largo: oltre, una riga e' troppo lunga per essere letta. */
  val PageMaxWidth: Dp = 640.dp

  /** L'altezza della barra di avanzamento di una scheda (piu' spessa di quella di default di Fluid). */
  val BarHeight: Dp = 8.dp

  /** Il glifo di un'icona di riga dentro la sua piastrella. */
  val RowGlyph: Dp = 24.dp

  /** Il glifo in testa a una scheda di stato. */
  val CardGlyph: Dp = 24.dp

  /** Lo spazio interno di uno stato vuoto a tutta pagina. */
  val StatePadding: Dp = 32.dp

  /** La larghezza massima del testo di uno stato a tutta pagina. */
  val StateTextMaxWidth: Dp = 340.dp
}
