package com.mycloudgallery.presentation.viewer.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage

/**
 * Immagine zoomabile con pinch-to-zoom (1x–10x) e double-tap (1x <-> 3x).
 * Pan abilitato quando zoom > 1x, con limiti ai bordi.
 */
@Composable
fun ZoomableImage(
    model: Any?,
    contentDescription: String?,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val maxScale = 10f
    val minScale = 1f
    val doubleTapScale = 3f

    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(minScale, maxScale)
                    if (newScale > minScale) {
                        val maxOffsetX = (size.width * (newScale - 1)) / 2
                        val maxOffsetY = (size.height * (newScale - 1)) / 2
                        offset = Offset(
                            x = (offset.x + pan.x).coerceIn(-maxOffsetX, maxOffsetX),
                            y = (offset.y + pan.y).coerceIn(-maxOffsetY, maxOffsetY),
                        )
                    } else {
                        offset = Offset.Zero
                    }
                    scale = newScale
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > minScale) {
                            scale = minScale
                            offset = Offset.Zero
                        } else {
                            scale = doubleTapScale
                        }
                    },
                    onTap = { onTap() },
                )
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    )
}
