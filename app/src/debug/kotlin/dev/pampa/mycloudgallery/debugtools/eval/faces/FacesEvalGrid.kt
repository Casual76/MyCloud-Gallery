package dev.pampa.mycloudgallery.debugtools.eval.faces

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
import dev.pampa.mycloudgallery.core.model.FaceLocator

/** Un volto della griglia: il posto e la sua posizione (da 1) fra quelli mostrati. */
internal class FaceCell(val locator: FaceLocator, val position: Int, val total: Int) {
  override fun toString(): String = "FaceCell($position/$total)"
}

/**
 * SOLO debug (Fase 8, P11): la griglia dei volti di un gruppo, a quattro colonne (come `evalGrid` della ricerca e `albumsGrid` degli album, ma i volti sono piccoli). Il
 * ritaglio lo da' [FacesEvalEnvironment.crop] (locale, mai salvato, mai dal NAS); senza, un riquadro grigio col numero. Un volto ha due stati: giusto (non toccato) e
 * segnato come non suo (bordo rosso e croce). La descrizione per TalkBack dice "Volto 5 di 70, giusto": mai l'id, il posto o un nome. Bersagli ben oltre i 48 dp.
 */
internal fun LazyListScope.facesGrid(
  cells: List<FaceCell>,
  env: FacesEvalEnvironment,
  isWrong: (FaceCell) -> Boolean,
  onToggle: (FaceCell) -> Unit,
  enabled: Boolean = true,
) {
  items(cells.chunked(COLUMNS)) { row ->
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      for (cell in row) FaceThumb(cell, env, isWrong(cell), enabled, onClick = { onToggle(cell) }, modifier = Modifier.weight(1f))
      // Una riga corta riempie lo spazio con celle vuote, perche' le colonne restino larghe uguali.
      repeat(COLUMNS - row.size) { Box(modifier = Modifier.weight(1f)) }
    }
  }
}

@Composable
private fun FaceThumb(cell: FaceCell, env: FacesEvalEnvironment, wrong: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
  val bitmap by produceState<ImageBitmap?>(initialValue = null, cell.locator) { value = env.crop(cell.locator) }
  val scheme = MaterialTheme.colorScheme
  Box(
    modifier = modifier
      .aspectRatio(1f)
      .background(if (wrong) scheme.error.copy(alpha = 0.40f) else scheme.onSurface.copy(alpha = 0.08f))
      .clickable(enabled = enabled, onClick = onClick)
      .semantics { contentDescription = FacesEvalStrings.faceDescription(cell.position, cell.total, wrong); selected = wrong },
    contentAlignment = Alignment.Center,
  ) {
    val image = bitmap
    if (image != null) {
      Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
    } else {
      Text(text = cell.position.toString(), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
    }
    if (wrong) {
      // Il segno si vede anche con un ritaglio chiaro: un velo rosso su tutta la cella, oltre alla croce.
      Box(modifier = Modifier.matchParentSize().background(scheme.error.copy(alpha = 0.35f)))
      Text(
        text = "✗",
        style = MaterialTheme.typography.titleMedium,
        color = scheme.onError,
        modifier = Modifier.align(Alignment.TopEnd).background(scheme.error).padding(horizontal = 6.dp),
      )
    }
  }
}

private const val COLUMNS = 4
