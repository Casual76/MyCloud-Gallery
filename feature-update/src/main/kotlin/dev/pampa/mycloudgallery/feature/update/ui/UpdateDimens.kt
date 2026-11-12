package dev.pampa.mycloudgallery.feature.update.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconTileDefaults
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryProgressRingDefaults

/** Le misure dell'aggiornamento: l'unico file con dei `dp` (come `SettingsDimens`). */
internal object UpdateDimens {
  /** La figura della testata della pagina: la piastrella grande o l'anello della testata. */
  val HeroTile: Dp = 96.dp
  val HeroRing: Dp = GalleryProgressRingDefaults.Hero

  /** La figura dell'avviso: la piastrella e l'anello di una scheda. */
  val PromptTile: Dp = GalleryIconTileDefaults.Medium
  val PromptRing: Dp = GalleryProgressRingDefaults.Card

  /** Il glifo al centro dell'anello. */
  val HeroGlyph: Dp = 48.dp
  val PromptGlyph: Dp = 22.dp

  val HeroPadding: Dp = 20.dp
  val Gap: Dp = 12.dp
  val TightGap: Dp = 6.dp
  val CardPadding: Dp = 16.dp

  /** L'avviso: largo al piu' come una scheda di lettura, staccato dai bordi e dalla barra di navigazione. */
  val PromptMaxWidth: Dp = 520.dp
  val PromptMargin: Dp = 12.dp

  /** Il segno della scelta del canale. */
  val ChoiceMark: Dp = 24.dp
}
