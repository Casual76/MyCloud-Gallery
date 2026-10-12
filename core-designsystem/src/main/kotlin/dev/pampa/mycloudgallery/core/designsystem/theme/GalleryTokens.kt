package dev.pampa.mycloudgallery.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidScreenDefaults

/**
 * Le misure di layout della galleria, in un posto solo.
 *
 * Raggi, colori e dimensioni di testo NON stanno qui: vengono dall'engine (`FluidRadius`,
 * `MaterialTheme.colorScheme`, `MaterialTheme.typography`). Questi sono solo i passi di spaziatura e
 * le taglie dei piccoli elementi (badge, indicatore di selezione) che l'engine non ha.
 */
object GalleryDimens {

  /** Il bersaglio di tocco minimo (accessibilita'): nessun controllo interattivo scende sotto. */
  val MinTouchTarget: Dp = 48.dp

  /** Il margine laterale di una pagina: quello di `FluidScreen`, cosi' la galleria non ne ha uno suo. */
  val Gutter: Dp = FluidScreenDefaults.HorizontalPadding

  /** Lo spazio fra due elementi di una lista di sezioni. */
  val ItemSpacing: Dp = FluidScreenDefaults.ItemSpacing

  /** L'altezza di un badge compatto sopra una cella. */
  val BadgeHeight: Dp = 22.dp

  /** La glifo dentro un badge. */
  val BadgeGlyph: Dp = 14.dp

  /** Un glifo secondario dentro un badge tondo (la freccia nell'anello di avanzamento). */
  val BadgeMiniGlyph: Dp = 10.dp

  /** Quanto un badge sta lontano dal bordo della cella. */
  val BadgeInset: Dp = 6.dp

  /** Il diametro del cerchio di selezione di una cella. */
  val SelectionIndicator: Dp = 24.dp

  /** Lo spazio fra due celle della griglia: sottile, e' la fessura che separa le foto. */
  val GridGap: Dp = 2.dp

  /** Il lato della piastrella con l'icona in testa a un foglio di conferma. */
  val SheetIconTile: Dp = 44.dp

  /** Lo spazio interno di un foglio di conferma. */
  val SheetPadding: Dp = 24.dp
}

/**
 * Cio' che sta sopra una foto.
 *
 * Una foto non segue il tema: puo' essere bianca o nera sia in chiaro che in scuro. Quindi i badge che
 * ci stanno sopra non possono prendere il colore dalla pagina: usano una **coppia fissa**, la stessa
 * regola che l'engine applica a `FluidTone.Success` e `Warning` (nessun ruolo Material li copre).
 *
 *  - lo **scrim** e' il ruolo `scrim` dello schema (nero) al [ScrimAlpha];
 *  - il **contenuto** e' bianco.
 *
 * Il [ScrimAlpha] non e' un gusto: e' il piu' basso per cui il bianco sul peggior caso (una foto tutta
 * bianca, che porta lo scrim a un grigio medio) resta sopra i 4,5:1 del testo piccolo. Lo verifica
 * `GalleryContrastTest` su foto bianche e nere, in chiaro e scuro, con piu' accenti.
 *
 * Eccezione consapevole alla regola "nessun colore scritto a mano": e' l'unico `Color.White` di tutto
 * il design system della galleria, e vive qui.
 */
object GalleryOnPhoto {

  /** L'opacita' dello scrim: 5,7:1 col bianco sopra una foto bianca. */
  const val ScrimAlpha: Float = 0.60f

  /** Il colore di glifi e testo sopra lo scrim. */
  val Content: Color = Color.White

  /** Lo scrim, risolto da uno schema (funzione pura, cosi' si puo' verificare fuori da una composizione). */
  fun scrim(scheme: ColorScheme): Color = scheme.scrim.copy(alpha = ScrimAlpha)

  /** Lo scrim del tema corrente. */
  @Composable
  fun scrim(): Color = scrim(MaterialTheme.colorScheme)
}
