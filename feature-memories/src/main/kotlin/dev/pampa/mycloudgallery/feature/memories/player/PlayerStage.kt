package dev.pampa.mycloudgallery.feature.memories.player

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.memories.contract.ClipSource
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesThumbnails
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerClipSurface
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerImages
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerItem
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerState
import dev.pampa.mycloudgallery.feature.memories.page.thumbRequestOf
import dev.pampa.mycloudgallery.feature.memories.ui.MemoriesDimens

// IL PALCO DELLA PRESENTAZIONE (Fase 9, P9; docs/fase9-progetto.md §2.4, D13): l'immagine di adesso, il suo movimento lento e la dissolvenza fra due
// elementi. **Solo `graphicsLayer`** (trasformazioni della GPU): nessuna nuova misura del layout a ogni fotogramma; il movimento e le dissolvenze
// vengono dai token di `FluidMotion` (`NoHandWrittenStyleTest`). Con le animazioni ridotte non c'e' nessun movimento (solo una dissolvenza breve);
// in pausa il movimento si ferma dov'e'. L'immagine e' la migliore che c'e' adesso (anteprima locale, miniatura grande, segnaposto): non si aspetta.

/** Quanto cresce l'immagine nei 4 secondi (dal 100% al 108%) e quanto si sposta verso il centro, in frazione della sua misura. */
private const val ZOOM: Float = 0.08f
private const val PAN: Float = 0.015f

/** L'immagine migliore che c'e' adesso per [media]: l'anteprima locale, o la miniatura grande, o il segnaposto sfocato; niente altrimenti (fondo scuro). */
@Composable
private fun BestImage(snapshot: TimelineSnapshot, media: MediaId, thumbnails: MemoriesThumbnails, images: PlayerImages, modifier: Modifier) {
  val index = remember(snapshot, media) { snapshot.indexOf(media) }
  val preview = images.previewOf(media)
  val request = remember(snapshot, index) { if (index >= 0) thumbRequestOf(thumbnails, snapshot, index, ThumbBucket.L) else null }
  val thumb = request?.let { thumbnails.loader.image(it.key, it.ref).value }
  val placeholder = remember(snapshot, index) { if (index >= 0) snapshot.thumbHashAt(index)?.let { thumbnails.placeholders.image(it) } else null }
  val shown = preview ?: thumb ?: placeholder
  if (shown != null) Image(bitmap = shown, contentDescription = null, contentScale = ContentScale.Fit, modifier = modifier)
}

/**
 * Il palco: [item] con il suo movimento ([moving] = si muove, falso in pausa e con le animazioni ridotte), e quando [item] cambia la dissolvenza dal
 * precedente. [durationMs] e' il tempo che l'elemento resta in vista (il movimento lo riempie). Il clip muto di un video del telefono sta sopra il
 * fotogramma di copertina ([clips]); il glifo "video" c'e' sempre su un video.
 */
@Composable
internal fun PlayerStage(
  snapshot: TimelineSnapshot,
  item: PlayerItem,
  index: Int,
  moving: Boolean,
  reducedMotion: Boolean,
  durationMs: Long,
  thumbnails: MemoriesThumbnails,
  images: PlayerImages,
  clips: PlayerClipSurface,
  modifier: Modifier = Modifier,
) {
  // La dissolvenza: l'elemento che esce resta sotto finche' quello nuovo non e' tutto visibile.
  var shown by remember { mutableStateOf(item.media) }
  var leaving by remember { mutableStateOf<MediaId?>(null) }
  val fade = remember { Animatable(1f) }
  LaunchedEffect(item.media) {
    if (item.media == shown) return@LaunchedEffect
    leaving = shown
    shown = item.media
    fade.snapTo(0f)
    fade.animateTo(1f, FluidMotion.crossFade<Float>(if (reducedMotion) PlayerTimings.REDUCED_FADE_MS else PlayerTimings.FADE_MS))
    leaving = null
  }
  // Il movimento lento dell'elemento di adesso: da 0 a 1 nei suoi secondi, fermo in pausa e con le animazioni ridotte.
  val motion = remember(index) { Animatable(0f) }
  LaunchedEffect(index, moving, reducedMotion, durationMs) {
    if (!moving || reducedMotion) return@LaunchedEffect
    val remaining = 1f - motion.value
    if (remaining <= 0f) return@LaunchedEffect
    motion.animateTo(1f, FluidMotion.crossFade<Float>((remaining * durationMs).toInt().coerceAtLeast(1)))
  }
  val direction = if (index % 2 == 0) 1f else -1f
  Box(modifier = modifier.fillMaxSize()) {
    leaving?.let { media ->
      BestImage(snapshot, media, thumbnails, images, Modifier.fillMaxSize().graphicsLayer { alpha = 1f - fade.value })
    }
    BestImage(
      snapshot, shown, thumbnails, images,
      Modifier.fillMaxSize().graphicsLayer {
        val progress = if (reducedMotion) 0f else motion.value
        val scale = 1f + ZOOM * progress
        scaleX = scale
        scaleY = scale
        translationX = size.width * PAN * progress * direction
        translationY = size.height * PAN * progress * direction
        alpha = if (leaving != null) fade.value else 1f
      },
    )
    if (item is PlayerItem.Video) {
      Icon(
        imageVector = Icons.Rounded.PlayCircle,
        contentDescription = null,
        modifier = Modifier.align(Alignment.Center).size(MemoriesDimens.VideoGlyph),
        tint = MaterialTheme.colorScheme.onSurface,
      )
      val clip = item.clip
      if (clip != null && moving) clips.Show(clip, Modifier.fillMaxSize())
    }
  }
}

/** Quanto resta in vista un elemento (il movimento lo riempie): come lo conta il controllore. */
internal fun durationOf(item: PlayerItem): Long =
  if (item is PlayerItem.Video && item.clip != null) item.clip.durationMs.coerceIn(PlayerTimings.PHOTO_MS, ClipSource.MAX_MS) else PlayerTimings.PHOTO_MS

/** Lo stato come la schermata lo legge per il palco: l'indice e l'elemento. */
internal fun PlayerState.itemOrNull(): PlayerItem? = when (this) {
  is PlayerState.Playing -> item
  is PlayerState.Paused -> item
  is PlayerState.Ended -> null
}

internal fun PlayerState.indexOrNull(): Int? = when (this) {
  is PlayerState.Playing -> index
  is PlayerState.Paused -> index
  is PlayerState.Ended -> null
}
