package dev.pampa.mycloudgallery.feature.memories.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesThumbnails
import dev.pampa.mycloudgallery.feature.memories.page.thumbRequestOf

/**
 * La copertina di un ricordo: la miniatura locale di [media] (o, finche' non c'e', il segnaposto sfocato dell'indice, o un fondo liscio). **Mai testo
 * sopra la foto**: titolo e conto stanno accanto, su una superficie piena. Il caricamento lo chiede chi disegna la lista (`loader.window`): qui si
 * osserva soltanto. Una copertina che l'istantanea non ha piu' (nascosta, eliminata) e' un fondo liscio.
 */
@Composable
internal fun MemoryCover(
  thumbnails: MemoriesThumbnails,
  snapshot: TimelineSnapshot,
  media: MediaId,
  modifier: Modifier = Modifier,
  bucket: ThumbBucket = ThumbBucket.M,
) {
  val index = remember(snapshot, media) { snapshot.indexOf(media) }
  val request = remember(snapshot, index, bucket) { if (index >= 0) thumbRequestOf(thumbnails, snapshot, index, bucket) else null }
  val image = request?.let { thumbnails.loader.image(it.key, it.ref).value }
  val placeholder = remember(snapshot, index) { if (index >= 0) snapshot.thumbHashAt(index)?.let { thumbnails.placeholders.image(it) } else null }
  Box(modifier = modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
    val shown = image ?: placeholder
    if (shown != null) Image(bitmap = shown, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
  }
}

/** La richiesta di miniatura della copertina [media] nell'istantanea (per `loader.window`), o null se non c'e' una sorgente locale. */
internal fun coverRequest(thumbnails: MemoriesThumbnails, snapshot: TimelineSnapshot, media: MediaId, bucket: ThumbBucket = ThumbBucket.M): ThumbRequest? {
  val index = snapshot.indexOf(media)
  return if (index >= 0) thumbRequestOf(thumbnails, snapshot, index, bucket) else null
}
