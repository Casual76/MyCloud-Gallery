package dev.pampa.mycloudgallery.core.designsystem.instant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.pampa.mycloudgallery.core.designsystem.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lo **scheletro dimensionato** di una pagina (Avvio istantaneo, 28/10): al primo ingresso, finche' i dati non ci sono, la pagina
 * disegna i suoi blocchi vuoti con le dimensioni di [shape] (quelle dell'ultima volta, da `InstantShapes`, o quelle di serie)
 * invece di una rotella. Quando arrivano i dati veri prendono lo stesso posto: niente salta.
 *
 * Fermo, senza animazioni (un luccichio costerebbe un fotogramma ogni 8 ms per una cosa che resta a schermo un istante) e senza
 * niente da leggere: TalkBack sente una frase sola ("Sto caricando") per tutta la pagina.
 *
 * E' fatto per i `FluidScreen` (una `LazyColumn`): [instantSkeleton] aggiunge un elemento per blocco. Lo usano Raccolte, la
 * pagina di un album, Persone e Cerca a riposo; le pagine della Fase 9 (ricordi, mappa) lo usano allo stesso modo.
 */
fun LazyListScope.instantSkeleton(shape: InstantShape, keyPrefix: String = "instant") {
  shape.blocks.forEachIndexed { index, block ->
    item(key = "$keyPrefix-$index", contentType = "instant-${block::class.simpleName}") { InstantBlockView(block) }
  }
}

/** Lo stesso scheletro fuori da una lista (una colonna). */
@Composable
fun InstantSkeleton(shape: InstantShape, modifier: Modifier = Modifier) {
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(InstantDimens.BlockGap)) {
    shape.blocks.forEach { InstantBlockView(it) }
  }
}

/** Un blocco dello scheletro. */
@Composable
fun InstantBlockView(block: InstantBlock, modifier: Modifier = Modifier) {
  val description = stringResource(R.string.instant_skeleton_description)
  Box(modifier = modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description }) {
    when (block) {
      InstantBlock.Header -> SkeletonBar(Modifier.padding(start = InstantDimens.HeaderInset, top = InstantDimens.HeaderTop).width(InstantDimens.HeaderWidth))
      is InstantBlock.Rows -> SkeletonRows(block.count)
      is InstantBlock.Tiles -> SkeletonTiles(block.count, block.columns)
    }
  }
}

@Composable
private fun SkeletonRows(count: Int) {
  FluidListGroup {
    repeat(count) { index ->
      if (index > 0) FluidListDivider()
      Row(
        modifier = Modifier.fillMaxWidth().height(InstantDimens.RowHeight).padding(horizontal = InstantDimens.RowInset),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(Modifier.size(InstantDimens.RowTile).skeletonFill(FluidRadius.Small))
        Spacer(Modifier.width(InstantDimens.RowInset))
        Column(verticalArrangement = Arrangement.spacedBy(InstantDimens.LineGap)) {
          SkeletonBar(Modifier.width(InstantDimens.TitleWidth))
          SkeletonBar(Modifier.width(InstantDimens.SubtitleWidth))
        }
      }
    }
  }
}

@Composable
private fun SkeletonTiles(count: Int, columns: Int) {
  val rows = remember(count, columns) { (count + columns - 1) / columns }
  Column(verticalArrangement = Arrangement.spacedBy(InstantDimens.TileGap)) {
    repeat(rows) { row ->
      Row(horizontalArrangement = Arrangement.spacedBy(InstantDimens.TileGap)) {
        repeat(columns) { column ->
          val shown = row * columns + column < count
          Box(Modifier.weight(1f).aspectRatio(1f).then(if (shown) Modifier.skeletonFill(FluidRadius.Card) else Modifier))
        }
      }
    }
  }
}

@Composable
private fun SkeletonBar(modifier: Modifier) {
  Box(modifier.height(InstantDimens.LineHeight).skeletonFill(FluidRadius.Small))
}

@Composable
private fun Modifier.skeletonFill(radius: androidx.compose.ui.unit.Dp): Modifier =
  background(MaterialTheme.colorScheme.surfaceContainerHighest, ContinuousCornerShape(radius))

/**
 * Chiama [onDrawn] una volta sola, al primo disegno di cio' a cui e' attaccato (lo scheletro o i dati veri): chiude la sezione
 * di traccia dell'apertura della pagina (`AvvioTrace.pageDrawn`, in core-common, che questo modulo non vede).
 */
fun Modifier.onFirstDraw(onDrawn: () -> Unit): Modifier {
  val done = AtomicBoolean(false)
  return drawWithContent {
    drawContent()
    if (done.compareAndSet(false, true)) onDrawn()
  }
}

/** Le misure dello scheletro: quelle di una riga di `FluidListRow` con titolo e sottotitolo, di un'intestazione, di una piastrella. */
object InstantDimens {
  val RowHeight = 60.dp
  val RowInset = 16.dp
  val RowTile = 32.dp
  val LineHeight = 12.dp
  val LineGap = 8.dp
  val TitleWidth = 140.dp
  val SubtitleWidth = 96.dp
  val HeaderInset = 4.dp
  val HeaderTop = 12.dp
  val HeaderWidth = 120.dp
  val TileGap = 8.dp
  val BlockGap = 12.dp
}
