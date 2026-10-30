package dev.pampa.mycloudgallery.debugtools.eval.memories

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.pampa.mycloudgallery.core.model.MediaId

/**
 * SOLO debug (Fase 9, P11): la griglia di un ricordo, a tre colonne, con miniature **locali** (mai dal NAS; [MemoriesEvalEnvironment.thumbnail]) e un tocco per segnare una
 * foto che non doveva esserci. Una foto ha due stati: a posto, o segnata (velo rosso e la lettera del motivo); la scelta ha un bordo. La descrizione per TalkBack dice
 * "Foto 3 di 12, doppione": mai l'id ne' il nome del file ne' il giorno. Bersagli ben oltre i 48 dp (le celle sono grandi).
 */
internal fun LazyListScope.memoriesGrid(
  memory: EvalMemory,
  env: MemoriesEvalEnvironment,
  faultOf: (String) -> EvalFault?,
  selected: String?,
  onTap: (String) -> Unit,
) {
  val numbered = memory.members.mapIndexed { index, media -> index + 1 to media }
  items(numbered.chunked(COLUMNS)) { row ->
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      for ((number, media) in row) {
        MemoriesThumb(number, memory.members.size, media, env, faultOf(media), media == selected, onClick = { onTap(media) }, modifier = Modifier.weight(1f))
      }
      // Una riga corta riempie lo spazio con celle vuote, perche' le colonne restino larghe uguali.
      repeat(COLUMNS - row.size) { Box(modifier = Modifier.weight(1f)) }
    }
  }
}

@Composable
private fun MemoriesThumb(number: Int, total: Int, media: String, env: MemoriesEvalEnvironment, fault: EvalFault?, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
  val bitmap by produceState<ImageBitmap?>(initialValue = null, media) { value = env.thumbnail(MediaId(media)) }
  val scheme = MaterialTheme.colorScheme
  val frame = if (selected) Modifier.border(3.dp, scheme.primary) else Modifier
  Box(
    modifier = modifier
      .aspectRatio(1f)
      .background(if (fault != null) scheme.error.copy(alpha = 0.35f) else scheme.onSurface.copy(alpha = 0.08f))
      .then(frame)
      .clickable(onClick = onClick)
      .semantics { contentDescription = MemoriesEvalStrings.cellDescription(number, total, fault); this.selected = selected || fault != null },
    contentAlignment = Alignment.Center,
  ) {
    val image = bitmap
    if (image != null) {
      Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
    } else {
      Text(text = number.toString(), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
    }
    if (fault != null) {
      Text(
        text = fault.code.uppercaseChar().toString(),
        style = MaterialTheme.typography.titleLarge,
        color = scheme.onError,
        modifier = Modifier.align(Alignment.TopEnd).background(scheme.error).padding(horizontal = 6.dp),
      )
    }
  }
}

private const val COLUMNS = 3
