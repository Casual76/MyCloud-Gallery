package dev.pampa.mycloudgallery.debugtools.eval

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp

/**
 * SOLO debug (Fase 6, P9): una griglia di miniature **locali** dove si tocca per segnare. Tre colonne, 48 dp almeno di bersaglio (le celle sono molto piu'
 * grandi). La miniatura la da' [EvalEnvironment.thumbnail] (mai dal NAS); senza, un riquadro grigio con il giorno. Le celle segnate hanno un bordo e una
 * spunta, e la descrizione per TalkBack dice "segnata": mai la chiave o il nome del file.
 */
internal fun LazyListScope.evalGrid(
  cells: List<EvalCell>,
  env: EvalEnvironment,
  isMarked: (EvalCell) -> Boolean,
  onToggle: (EvalCell) -> Unit,
) {
  items(cells.chunked(COLUMNS)) { row ->
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      for (cell in row) {
        EvalThumb(cell, env, marked = isMarked(cell), onClick = { onToggle(cell) }, modifier = Modifier.weight(1f))
      }
      // Una riga corta riempie lo spazio con celle vuote, perche' le colonne restino larghe uguali.
      repeat(COLUMNS - row.size) { Box(modifier = Modifier.weight(1f)) }
    }
  }
}

@Composable
private fun EvalThumb(cell: EvalCell, env: EvalEnvironment, marked: Boolean, onClick: () -> Unit, modifier: Modifier) {
  val bitmap by produceState<ImageBitmap?>(initialValue = null, cell.id) { value = env.thumbnail(cell.id) }
  val scheme = MaterialTheme.colorScheme
  val description = (cell.day?.toString() ?: "senza data") + if (marked) ", segnata" else ""
  Box(
    modifier = modifier
      .aspectRatio(1f)
      .background(if (marked) scheme.primary.copy(alpha = 0.35f) else scheme.onSurface.copy(alpha = 0.08f))
      .clickable(onClick = onClick)
      .semantics { contentDescription = description; selected = marked },
    contentAlignment = Alignment.Center,
  ) {
    val image = bitmap
    if (image != null) {
      Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
    } else {
      Text(text = cell.day?.toString() ?: "", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
    }
    if (marked) {
      Text(
        text = "\u2713",
        style = MaterialTheme.typography.titleLarge,
        color = scheme.onPrimary,
        modifier = Modifier.align(Alignment.TopEnd).background(scheme.primary).padding(horizontal = 6.dp),
      )
    }
  }
}

private const val COLUMNS = 3
