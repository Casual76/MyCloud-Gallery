package dev.pampa.mycloudgallery.core.designsystem.grid

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.pampa.mycloudgallery.core.designsystem.motion.GalleryGridLevel
import dev.pampa.mycloudgallery.core.designsystem.motion.GalleryGridLevels
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Le misure dei quattro livelli della griglia (docs/fase3-progetto.md §2.4): il lato di cella a cui ogni livello mira e
 * quante colonne puo' avere al massimo. Sul telefono (412 dp) danno 7, 5, 4 e 3 colonne; sul tablet (1280 dp) 16, 12, 10
 * e 8. Sono le sole misure di questo file e vivono qui, accanto a chi le usa.
 */
object GalleryGridLevelSpec {

  /** Il lato di cella a cui mira ogni livello, da Anni a Tutte. */
  val TargetCell: List<Dp> = listOf(56.dp, 80.dp, 100.dp, 130.dp)

  /** Le colonne massime di ogni livello, da Anni a Tutte (oltre, le celle diventerebbero francobolli su un tablet). */
  val MaxColumns: List<Int> = listOf(16, 12, 10, 8)
}

/**
 * I livelli della griglia **per la larghezza data** (§2.4, E-P18): le stesse quattro etichette di
 * [GalleryGridLevels.Default], con il numero di colonne che riempie [widthDp] con celle vicine al lato del livello.
 *
 * Le colonne scendono sempre strettamente da Anni a Tutte, anche in una finestra stretta (split screen, DeX): il pinch
 * (`GalleryPinchSpec`) ragiona sul rapporto fra le colonne di due livelli, e due livelli con le stesse colonne sarebbero
 * lo stesso livello due volte.
 */
fun GalleryGridLevels.forWidth(widthDp: Float): List<GalleryGridLevel> {
  val width = max(1f, widthDp)
  val natural = IntArray(GalleryGridLevelSpec.TargetCell.size) { index ->
    min(GalleryGridLevelSpec.MaxColumns[index], max(1, floor(width / GalleryGridLevelSpec.TargetCell[index].value).toInt()))
  }
  // Da Tutte (le celle piu' grandi) verso Anni: ogni livello ha almeno una colonna in piu' del successivo.
  for (index in natural.lastIndex - 1 downTo 0) natural[index] = max(natural[index], natural[index + 1] + 1)
  return Default.mapIndexed { index, level -> GalleryGridLevel(level.label, natural[index]) }
}
