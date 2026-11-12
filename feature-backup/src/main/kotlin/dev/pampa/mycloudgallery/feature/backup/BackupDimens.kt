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

  /** IN-7: il lato della miniatura di un file ricevuto nell'elenco "Ricevuti in coda". */
  val ReceivedThumb: Dp = 64.dp

  /** Lo spazio interno di uno stato vuoto a tutta pagina. */
  val StatePadding: Dp = 32.dp

  /** La larghezza massima del testo di uno stato a tutta pagina. */
  val StateTextMaxWidth: Dp = 340.dp

  // --- I3 (fase I): la testata con l'anello, le copertine delle sorgenti --------------------------------------------------------

  /** Il segno di stato appoggiato sull'anello. */
  val HeroMark: Dp = 30.dp

  /** Quanto il segno rientra dall'angolo del riquadro dell'anello, per stare sull'arco (a 45 gradi). */
  val HeroMarkInset: Dp = 8.dp

  /** Il glifo al centro dell'anello quando non c'e' un numero (niente da salvare ancora). */
  val HeroCenterGlyph: Dp = 40.dp

  /** Lo spazio interno della testata. */
  val HeroPadding: Dp = 20.dp

  /** La piastrella di un passo che manca (da preparare). */
  val StepTile: Dp = 28.dp

  /** La copertina di una sorgente non scende sotto questo lato: sul telefono dell'utente (360 dp) ne stanno tre per riga. */
  val CoverMinCell: Dp = 100.dp

  /** Lo spazio fra due copertine. */
  val CoverGap: Dp = 12.dp

  /** Lo spazio fra due righe di copertine. */
  val CoverRowGap: Dp = 16.dp

  /** Il glifo di una sorgente senza foto, rispetto al lato della copertina. */
  const val CoverGlyphFraction: Float = 0.36f

  /** Il segno di stato nell'angolo di una copertina. */
  val CoverMark: Dp = 24.dp

  /** Quanto il segno sporge dall'angolo della copertina. */
  val CoverMarkOverhang: Dp = 6.dp

  /** La barra sottile di avanzamento sotto una copertina. */
  val CoverBar: Dp = 4.dp

  /** Il glifo accanto al numero che conta (le foto che mancano, la spunta). */
  val CountGlyph: Dp = 14.dp

  /** La piccola distanza fra il nome, la barra e il numero di una copertina. */
  val CoverLineGap: Dp = 6.dp

  /** La copertina di una cartella nella riga di «Cosa salvare». */
  val RowCover: Dp = 44.dp

  /** Un valore a destra di una riga (il nome della cartella sul NAS) non va oltre: il titolo resta leggibile. */
  val ValueMaxWidth: Dp = 150.dp

  // --- Giro 1 della fase I: il riepilogo di Libera spazio (CU-6) ------------------------------------------------------------------

  /** Il lato di un'anteprima di cio' che si toglie (cinque e il «+N» stanno in una riga del telefono). */
  val FreePreview: Dp = 52.dp

  /** Lo spazio fra due anteprime. */
  val FreePreviewGap: Dp = 6.dp

  /** Il glifo davanti alla garanzia (lo scudo). */
  val GuaranteeGlyph: Dp = 18.dp
}
