package dev.pampa.mycloudgallery.debugtools.eval.albums

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
import dev.pampa.mycloudgallery.core.model.MediaId

/**
 * SOLO debug (Fase 7, P12): una griglia di miniature **locali** dove si tocca per segnare, a tre colonne (come `evalGrid` della ricerca, Fase 6). La miniatura la da'
 * [AlbumsEvalEnvironment.thumbnail] (mai dal NAS); senza, un riquadro con il giorno. Una foto ha tre stati: da giudicare, del viaggio (spunta) e non del viaggio
 * (croce); la descrizione per TalkBack lo dice ("12 marzo, del viaggio"): mai l'id ne' il nome del file. Bersagli ben oltre i 48 dp (le celle sono grandi).
 */
internal fun LazyListScope.albumsGrid(
  cells: List<AlbumsEvalCell>,
  env: AlbumsEvalEnvironment,
  verdictOf: (AlbumsEvalCell) -> Verdict,
  onTap: (AlbumsEvalCell) -> Unit,
) {
  items(cells.chunked(COLUMNS)) { row ->
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      for (cell in row) AlbumsThumb(cell, env, verdictOf(cell), onClick = { onTap(cell) }, modifier = Modifier.weight(1f))
      // Una riga corta riempie lo spazio con celle vuote, perche' le colonne restino larghe uguali.
      repeat(COLUMNS - row.size) { Box(modifier = Modifier.weight(1f)) }
    }
  }
}

@Composable
private fun AlbumsThumb(cell: AlbumsEvalCell, env: AlbumsEvalEnvironment, verdict: Verdict, onClick: () -> Unit, modifier: Modifier) {
  val bitmap by produceState<ImageBitmap?>(initialValue = null, cell.id) { value = env.thumbnail(MediaId(cell.id)) }
  val scheme = MaterialTheme.colorScheme
  val state = when (verdict) {
    Verdict.NONE -> AlbumsEvalStrings.STATE_NONE
    Verdict.RIGHT -> AlbumsEvalStrings.STATE_RIGHT
    Verdict.WRONG -> AlbumsEvalStrings.STATE_WRONG
  }
  val background = when (verdict) {
    Verdict.NONE -> scheme.onSurface.copy(alpha = 0.08f)
    Verdict.RIGHT -> scheme.primary.copy(alpha = 0.35f)
    Verdict.WRONG -> scheme.error.copy(alpha = 0.35f)
  }
  Box(
    modifier = modifier
      .aspectRatio(1f)
      .background(background)
      .clickable(onClick = onClick)
      .semantics { contentDescription = "${cell.day}, $state"; selected = verdict != Verdict.NONE },
    contentAlignment = Alignment.Center,
  ) {
    val image = bitmap
    if (image != null) {
      Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
    } else {
      Text(text = cell.day.toString(), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
    }
    if (verdict != Verdict.NONE) {
      Text(
        text = if (verdict == Verdict.RIGHT) "✓" else "✗",
        style = MaterialTheme.typography.titleLarge,
        color = if (verdict == Verdict.RIGHT) scheme.onPrimary else scheme.onError,
        modifier = Modifier.align(Alignment.TopEnd).background(if (verdict == Verdict.RIGHT) scheme.primary else scheme.error).padding(horizontal = 6.dp),
      )
    }
  }
}

private const val COLUMNS = 3
