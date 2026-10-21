package dev.pampa.mycloudgallery.feature.viewer.livetext

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size

/**
 * Il passaggio fra tre spazi, a ogni livello di zoom (D45: "funziona a ogni livello di zoom"):
 *  - **immagine**: normalizzato (0..1) sull'immagine raddrizzata, lo spazio delle righe di testo;
 *  - **contenuto**: pixel della pagina con l'immagine intera nello schermo (`Fit`, dentro [fit]);
 *  - **schermo**: pixel della pagina dopo la trasformazione dello zoom e dello spostamento.
 *
 * La trasformazione e' quella che lo zoom applica al contenuto: scala intorno a [pivot], poi spostamento di [offset] (la stessa di un
 * `graphicsLayer`). Pura: l'unico pezzo che conosce telephoto sta in `ZoomableMediaState.contentMapping`.
 *
 * @param size la pagina, in pixel.
 * @param aspect larghezza/altezza dell'immagine raddrizzata, la stessa con cui la pagina la disegna.
 */
internal class ContentMapping(
  val size: Size,
  val aspect: Float,
  val scaleX: Float = 1f,
  val scaleY: Float = 1f,
  val offset: Offset = Offset.Zero,
  val pivot: Offset = Offset.Zero,
) {

  /** Il rettangolo dell'immagine intera nello schermo, prima dello zoom. */
  val fit: Rect = fitRectOf(size, aspect)

  /** Quanto e' ingrandita l'immagine rispetto a "intera nello schermo" (la scala piu' piccola dei due assi). */
  val zoom: Float get() = minOf(scaleX, scaleY)

  fun imageToContent(p: Offset): Offset = Offset(fit.left + p.x * fit.width, fit.top + p.y * fit.height)

  fun contentToImage(c: Offset): Offset =
    Offset((c.x - fit.left) / fit.width.coerceAtLeast(1e-3f), (c.y - fit.top) / fit.height.coerceAtLeast(1e-3f))

  fun contentToScreen(c: Offset): Offset = Offset(pivot.x + (c.x - pivot.x) * scaleX + offset.x, pivot.y + (c.y - pivot.y) * scaleY + offset.y)

  fun screenToContent(s: Offset): Offset = Offset(
    pivot.x + (s.x - offset.x - pivot.x) / scaleX.coerceAtLeast(1e-3f),
    pivot.y + (s.y - offset.y - pivot.y) / scaleY.coerceAtLeast(1e-3f),
  )

  fun imageToScreen(p: Offset): Offset = contentToScreen(imageToContent(p))

  fun screenToImage(s: Offset): Offset = contentToImage(screenToContent(s))

  /** Quanto e' alta in pixel dello schermo un'altezza dell'immagine (usata per tradurre le tolleranze dal dito all'immagine). */
  val screenPxPerImageHeight: Float get() = fit.height * scaleY

  /** Dalla distanza sullo schermo, in pixel, a quella in altezze dell'immagine. */
  fun pxToImageHeights(px: Float): Float = px / screenPxPerImageHeight.coerceAtLeast(1e-3f)

  companion object {
    fun identity(size: Size, aspect: Float): ContentMapping = ContentMapping(size, aspect)
  }
}

/** Il piu' grande rettangolo di proporzione [aspect] che entra in [container], al centro (come `fitRect` della pagina). */
private fun fitRectOf(container: Size, aspect: Float): Rect {
  if (container.width <= 0f || container.height <= 0f || aspect <= 0f) return Rect(Offset.Zero, container)
  val width: Float
  val height: Float
  if (aspect >= container.width / container.height) {
    width = container.width
    height = container.width / aspect
  } else {
    height = container.height
    width = container.height * aspect
  }
  return Rect(
    left = (container.width - width) / 2f,
    top = (container.height - height) / 2f,
    right = (container.width + width) / 2f,
    bottom = (container.height + height) / 2f,
  )
}
