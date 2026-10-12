package dev.pampa.mycloudgallery.feature.onboarding.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidScreenDefaults
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens

/**
 * Le misure dell'onboarding, in un posto solo (stessa regola di `GalleryDimens`).
 *
 * Raggi, colori e dimensioni di testo NON stanno qui: vengono dall'engine (`FluidRadius`,
 * `MaterialTheme.colorScheme`, `MaterialTheme.typography`). Questi sono i passi di spaziatura e le taglie
 * dei pezzi che l'engine non ha (piastrelle, riquadri del codice, barra dei tasti in fondo).
 * `NoHandWrittenStyleTest` vieta un `dp` scritto fuori da questo file.
 */
internal object OnboardingDimens {

  /** Il margine laterale di una pagina: quello di `FluidScreen`. */
  val Gutter: Dp = FluidScreenDefaults.HorizontalPadding

  /** Lo spazio fra due elementi di una pagina. */
  val ItemGap: Dp = FluidScreenDefaults.ItemSpacing

  /** Il bersaglio di tocco minimo (accessibilita'). */
  val TouchTarget: Dp = GalleryDimens.MinTouchTarget

  /** Quanto una colonna di lettura puo' allargarsi su un tablet. */
  val ReadingWidth: Dp = FluidScreenDefaults.ContentMaxWidth

  /** Nessuno spazio (un margine che non c'e'). */
  val None: Dp = 0.dp

  /** La piastrella e il glifo di una scheda compatta (conferme e avvisi non bloccanti). */
  val CompactTile: Dp = 32.dp
  val CompactGlyph: Dp = 18.dp

  /** Lo spazio dentro una scheda compatta. */
  val CompactPadding: Dp = 12.dp

  /** Il filo che contorna un riquadro del codice. */
  val Hairline: Dp = 1.dp

  /** Lo spazio dentro una scheda (avvisi, problemi, riquadri). */
  val CardPadding: Dp = 16.dp

  /** Lo spazio fra gli elementi dentro una scheda. */
  val CardGap: Dp = 12.dp

  /** Lo spazio fra le righe di un testo (titolo e spiegazione). */
  val TextGap: Dp = 4.dp

  /** Lo spazio fra due riquadri di un codice. */
  val CodeGap: Dp = 10.dp

  /** Lo spazio fra due bottoni nella barra in fondo. */
  val DockGap: Dp = 8.dp

  /** Il margine della barra dei tasti in fondo, sopra e ai lati. */
  val DockPadding: Dp = 16.dp

  /** La sfumatura sopra la barra dei tasti, dove il contenuto scorre sotto. */
  val DockFade: Dp = 28.dp

  /** La piastrella con l'icona dentro una scheda o in testa a una riga grande. */
  val Tile: Dp = GalleryDimens.SheetIconTile

  /** Il glifo dentro la piastrella. */
  val TileGlyph: Dp = 24.dp

  /** La piastrella grande dei passi che aprono con un'icona. */
  val HeroTile: Dp = 72.dp

  /** Il glifo dentro la piastrella grande. */
  val HeroGlyph: Dp = 36.dp

  /** Il segno di spunta di un'opzione scelta. */
  val Mark: Dp = 24.dp

  /** Lo spessore dell'anello di un'opzione non scelta. */
  val MarkRing: Dp = 1.5.dp

  /** Il glifo dentro il segno di spunta. */
  val MarkGlyph: Dp = 16.dp

  /** Il lato di un riquadro del codice (un gruppo di quattro caratteri). */
  val CodeCellMinWidth: Dp = 80.dp

  /** Il codice di approvazione, in grande: larghezza minima di un gruppo. */
  val ApprovalCellMinWidth: Dp = 96.dp

  /** Il lato del QR della chiave del dispositivo. */
  val QrSide: Dp = 232.dp

  /** L'altezza del riquadro della fotocamera. */
  val ScannerHeight: Dp = 380.dp

  /** La barra laterale dei passi su uno schermo largo. */
  val SidebarWidth: Dp = 332.dp

  /** Da questa larghezza in su la barra laterale dei passi compare accanto alla pagina. */
  val WideBreakpoint: Dp = 840.dp

  /** Il punto-passo nella barra laterale. */
  val StepDot: Dp = 28.dp

  /** Il filo che unisce due punti-passo. */
  val StepRail: Dp = 2.dp

  /** Lo spazio fra due righe della barra laterale. */
  val SidebarGap: Dp = 20.dp

  /** Il bersaglio di un indicatore di attesa. */
  val Wait: Dp = 20.dp
}
