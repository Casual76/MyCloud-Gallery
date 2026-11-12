package dev.pampa.mycloudgallery.feature.update.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconTileDefaults
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryProgressRingDefaults

/** Le misure dell'aggiornamento: l'unico file con dei `dp` (come `SettingsDimens`). Le figure stanno sulla scala comune (UU-9). */
internal object UpdateDimens {
  /** La figura della testata della pagina: l'icona dell'app grande (la taglia grande della scala, 64) o l'anello della testata. */
  val HeroIcon: Dp = GalleryIconTileDefaults.Large
  val HeroRing: Dp = GalleryProgressRingDefaults.Hero

  /** La figura dell'avviso: l'icona e l'anello di una scheda. */
  val PromptIcon: Dp = GalleryIconTileDefaults.Medium
  val PromptRing: Dp = GalleryProgressRingDefaults.Card

  /** Il glifo al centro dell'anello. */
  val HeroGlyph: Dp = 48.dp
  val PromptGlyph: Dp = 22.dp

  val HeroPadding: Dp = 20.dp
  val Gap: Dp = 12.dp
  val TightGap: Dp = 6.dp
  val CardPadding: Dp = 16.dp

  /** Il punto davanti a una riga delle note, e lo spazio fra punto e testo (le righe a capo rientrano sotto il testo, UU-13). */
  val BulletGap: Dp = 8.dp

  /** L'avviso: largo al piu' come una scheda di lettura, staccato dai bordi. */
  val PromptMaxWidth: Dp = 520.dp
  val PromptMargin: Dp = 12.dp

  /** Quanto l'avviso entra dal basso quando compare (se le animazioni non sono ridotte). */
  val PromptEnter: Dp = 24.dp
}
