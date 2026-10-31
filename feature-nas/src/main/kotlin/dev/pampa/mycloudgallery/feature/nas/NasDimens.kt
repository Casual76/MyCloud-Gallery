package dev.pampa.mycloudgallery.feature.nas

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Le misure di layout delle schermate della 4B, in un posto solo: e' l'unico file del modulo che scrive dei `dp` (`NasStyleTest`
 * lo verifica). Colori, raggi, dimensioni di testo e movimento NON stanno qui: vengono dall'engine (`MaterialTheme.colorScheme`,
 * `FluidRadius`, `MaterialTheme.typography`, `FluidMotion`). Margini di pagina e bersagli sono quelli di `GalleryDimens`; la chrome
 * (capsule, sfumatura, barra delle azioni) ha le misure di `GalleryChromeDefaults` e di `core-designsystem/design`.
 */
internal object NasDimens {

  /** Nessuno spazio. */
  val None: Dp = 0.dp

  /** Il margine interno di uno stato vuoto o di un avviso a tutta pagina. */
  val StatePadding: Dp = 32.dp

  /** Lo spazio fra l'illustrazione, il titolo, la frase e l'azione di uno stato. */
  val StateGap: Dp = 12.dp

  /** La larghezza massima del testo di uno stato: oltre, una riga non si legge piu' bene. */
  val StateTextMaxWidth: Dp = 340.dp

  /** Lo spazio fra due blocchi di una pagina (avviso, gruppo, nota). */
  val BlockGap: Dp = 12.dp

  /** Lo spazio fra le righe di un blocco (titolo e frase di un avviso). */
  val LineGap: Dp = 4.dp

  /** La larghezza massima di un elenco su uno schermo largo: oltre, una riga e' troppo lunga per essere letta. */
  val ListMaxWidth: Dp = 720.dp

  /** Quante righe di una lista lunga stanno in un gruppo (un gruppo e' un pannello: una lista di migliaia di righe si taglia). */
  const val RowsPerGroup: Int = 20

  /** La larghezza massima di una pagina "cruscotto" (Spazio NAS, Operazioni in corso) su uno schermo largo. */
  val DashboardMaxWidth: Dp = 640.dp

  /** La larghezza del pannello di sinistra su due pannelli. */
  val SidebarWidth: Dp = 300.dp

  /** Il rientro di un livello del percorso nel pannello di sinistra. */
  val PathIndent: Dp = 16.dp

  /** Il diametro del cerchio di selezione di una riga (come il cerchio delle celle della griglia). */
  val SelectDot: Dp = 24.dp

  /** Il glifo dentro il cerchio di selezione. */
  val SelectGlyph: Dp = 16.dp

  /** La freccia "si apre" di una cartella, nello stesso spazio del cerchio di selezione. */
  val ChevronGlyph: Dp = 22.dp

  /** Lo spessore dell'anello di un cerchio di selezione vuoto. */
  val SelectRing: Dp = 1.5.dp

  /** Il glifo di un'icona di riga dentro la sua piastrella. */
  val RowGlyph: Dp = 24.dp

  /** Il lato minimo di un pulsante che sta in una capsula in fondo (e' un bersaglio di tocco). */
  val BarButtonMinWidth: Dp = 48.dp

  /** Lo spazio interno ai lati di un pulsante di testo in una capsula in fondo. */
  val BarButtonPadding: Dp = 14.dp

  /** Lo spazio fra il glifo e l'etichetta di un pulsante di testo. */
  val BarButtonGap: Dp = 6.dp

  /** Lo spazio interno di una scheda con dei numeri grandi (Spazio NAS). */
  val CardPadding: Dp = 20.dp

  /** Lo spazio fra le righe di una scheda con dei numeri grandi. */
  val CardGap: Dp = 10.dp

  /** L'altezza della barra di avanzamento di una scheda (piu' spessa di quella di default di Fluid, che e' 4 dp). */
  val BarHeight: Dp = 8.dp

  /** Il lato dell'illustrazione piccola di uno stato dentro un riquadro (non a tutta pagina). */
  val SmallIllustration: Dp = 120.dp

  /** La larghezza massima dei fogli di conferma su uno schermo largo (i fogli dell'engine non vanno oltre). */
  val SheetMaxWidth: Dp = 560.dp
}
