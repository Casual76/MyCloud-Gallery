package dev.pampa.mycloudgallery.feature.viewer.page

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import me.saket.telephoto.zoomable.DoubleClickToZoomListener
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Lo zoom del viewer: **telephoto avvolto** (D6). Nessun tipo di telephoto esce da questo file e dal tuo gemello
 * `TiledMedia.kt`: se un giorno si sostituisce con i tasselli propri (piano B) cambia solo questo.
 *
 * Fa quattro cose, tutte con la stessa geometria dell'immagine che c'e' sotto, cosi' niente salta:
 *  - **pizzica e trascina** con le due dita e con una; il doppio tocco ingrandisce fino a [doubleTapZoom] e richiude;
 *  - **a 1x non si prende i gesti**: il pager scorre di lato e il viewer si chiude verso il basso; ingrandita, la
 *    foto si sposta fino al bordo e *da li'* il pager prende il resto (telephoto non consuma il gesto che non puo'
 *    spendere);
 *  - **il tocco singolo** passa a [onTap] (mostra o nasconde le barre);
 *  - **il limite** dell'ingrandimento viene dalle dimensioni **dell'originale** ([contentSize]): 1 vuol dire un pixel
 *    della foto per un pixel dello schermo. Una foto da 200 MP si ingrandisce molto piu' di una da 12.
 *
 * Il contenuto ([content]) riempie tutto lo spazio e disegna la foto come `Fit` nel proprio centro: lo zoom lo
 * trasforma. Per i tasselli e' `TiledMedia`, per la bitmap [FitBitmap].
 */
@Composable
internal fun ZoomableMedia(
  state: ZoomableMediaState,
  contentSize: Size,
  modifier: Modifier = Modifier,
  gesturesEnabled: Boolean = true,
  onTap: (() -> Unit)? = null,
  locationKey: Any? = null,
  content: @Composable BoxScope.() -> Unit,
) {
  val zoomable = state.zoomable
  // [locationKey] cambia quando cambia chi disegna (bitmap, tasselli): i tasselli impostano da soli la posizione del
  // contenuto, e se poi cadono (file illeggibile) la bitmap deve riprendersela, o lo zoom resta spento.
  LaunchedEffect(zoomable, contentSize, locationKey) {
    if (contentSize.width > 0f && contentSize.height > 0f) {
      zoomable.setContentLocation(ZoomableContentLocation.scaledInsideAndCenterAligned(contentSize))
    }
  }
  Box(
    modifier = modifier
      .fillMaxSize()
      .zoomable(
        state = zoomable,
        enabled = gesturesEnabled,
        onClick = if (onTap != null) ({ _: Offset -> onTap() }) else null,
        onDoubleClick = DoubleClickToZoomListener.cycle(maxZoomFactor = state.doubleTapZoom),
      ),
    content = content,
  )
}

/**
 * Lo stato dello zoom di una pagina. [isZoomed] e' vero appena la foto e' piu' grande di come entra nello schermo: da li'
 * i gesti di una dita sono dello zoom (spostare), non del viewer (chiudere, aprire le informazioni).
 */
@Stable
internal class ZoomableMediaState(
  val zoomable: ZoomableState,
  val doubleTapZoom: Float,
) {

  /** Quanto e' ingrandita: 0 = interamente nello schermo, 1 = al massimo. */
  val zoomFraction: Float get() = zoomable.zoomFraction ?: 0f

  val isZoomed: Boolean get() = zoomFraction > ZoomedThreshold

  /** Riporta la foto interamente nello schermo; senza animazione quando la pagina e' fuori vista. */
  suspend fun reset(animated: Boolean) {
    if (isZoomed) zoomable.resetZoom(animated)
  }

  companion object {
    /** Sotto questa frazione la foto e' "ferma": il rimbalzo dell'elastico non conta come zoom. */
    const val ZoomedThreshold: Float = 0.015f
  }
}

/**
 * Lo zoom di una foto da [contentSize] pixel originali. I fattori di telephoto sono **relativi alla foto intera nello
 * schermo** (1 = interamente visibile): il massimo e' quello per cui un pixel dell'originale occupa un pixel dello
 * schermo (una foto da 200 MP arriva a ~20x, una da 12 MP a ~5x), ma mai meno di [MinMaxZoom] (una foto piccola si
 * guarda comunque da vicino) e mai piu' di [AbsoluteMaxZoom].
 */
@Composable
internal fun rememberZoomableMediaState(contentSize: Size, screen: IntSize): ZoomableMediaState {
  val maxFactor = maxZoomFactor(contentSize, screen)
  val doubleTap = doubleTapZoomFactor(contentSize, screen, maxFactor)
  val zoomable = rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = maxFactor))
  return remember(zoomable, doubleTap) { ZoomableMediaState(zoomable = zoomable, doubleTapZoom = doubleTap) }
}

/** Il fattore massimo dello zoom, relativo alla foto intera nello schermo. Funzione pura. */
internal fun maxZoomFactor(contentSize: Size, screen: IntSize): Float {
  val fit = fitScale(contentSize, screen) ?: return FallbackMaxZoom
  return max(MinMaxZoom, 1f / fit).coerceAtMost(AbsoluteMaxZoom)
}

/**
 * Il doppio tocco ingrandisce fino a **riempire lo schermo** (come in Apple Foto): una foto 4:3 su un telefono alto
 * ingrandisce di circa 3x. Mai meno di [MinDoubleTapZoom] e mai piu' del massimo: arrivare al pixel di una foto da
 * 200 MP con un tocco sarebbe troppo.
 */
internal fun doubleTapZoomFactor(contentSize: Size, screen: IntSize, maxFactor: Float): Float {
  val fit = fitScale(contentSize, screen) ?: return min(MinDoubleTapZoom, maxFactor)
  val shownWidth = contentSize.width * fit
  val shownHeight = contentSize.height * fit
  val fill = max(screen.width / shownWidth, screen.height / shownHeight)
  return fill.coerceIn(MinDoubleTapZoom, maxFactor.coerceAtLeast(MinDoubleTapZoom))
}

/** Pixel dello schermo per pixel dell'originale quando la foto entra intera; null se non si sa. */
private fun fitScale(contentSize: Size, screen: IntSize): Float? {
  if (contentSize.width <= 0f || contentSize.height <= 0f || screen.width <= 0 || screen.height <= 0) return null
  return min(screen.width / contentSize.width, screen.height / contentSize.height)
}

private const val MinMaxZoom = 4f
private const val AbsoluteMaxZoom = 40f
private const val MinDoubleTapZoom = 2f
private const val FallbackMaxZoom = 4f

/**
 * Disegna [bitmap] come `Fit` dentro l'area, **con la proporzione di [aspect]** e non con la propria: l'anteprima e la
 * versione nitida occupano lo stesso identico rettangolo anche se una e' arrotondata di un pixel (E-V1: sostituzione
 * senza spostamento).
 */
@Composable
internal fun FitBitmap(
  bitmap: ImageBitmap,
  aspect: Float,
  modifier: Modifier = Modifier,
  quality: FilterQuality = FilterQuality.High,
) {
  Canvas(modifier = modifier.fillMaxSize()) {
    val target = fitRect(size, if (aspect > 0f) aspect else bitmap.width.toFloat() / bitmap.height)
    drawFitted(bitmap, target, quality)
  }
}

internal fun DrawScope.drawFitted(bitmap: ImageBitmap, target: androidx.compose.ui.geometry.Rect, quality: FilterQuality) {
  drawImage(
    image = bitmap,
    srcOffset = IntOffset.Zero,
    srcSize = IntSize(bitmap.width, bitmap.height),
    dstOffset = IntOffset(target.left.roundToInt(), target.top.roundToInt()),
    dstSize = IntSize(target.width.roundToInt().coerceAtLeast(1), target.height.roundToInt().coerceAtLeast(1)),
    filterQuality = quality,
  )
}

/** Il piu' grande rettangolo di proporzione [aspect] (larghezza/altezza) che entra in [container], al centro. */
internal fun fitRect(container: Size, aspect: Float): androidx.compose.ui.geometry.Rect {
  if (container.width <= 0f || container.height <= 0f || aspect <= 0f) {
    return androidx.compose.ui.geometry.Rect(Offset.Zero, container)
  }
  val containerAspect = container.width / container.height
  val width: Float
  val height: Float
  if (aspect >= containerAspect) {
    width = container.width
    height = container.width / aspect
  } else {
    height = container.height
    width = container.height * aspect
  }
  return androidx.compose.ui.geometry.Rect(
    left = (container.width - width) / 2f,
    top = (container.height - height) / 2f,
    right = (container.width + width) / 2f,
    bottom = (container.height + height) / 2f,
  )
}
