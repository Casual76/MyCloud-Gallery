package dev.pampa.mycloudgallery.feature.viewer.page

import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * Dove sta il contenuto del viewer (il pager con la foto o il video) in questo fotogramma: **un solo punto** (H1).
 *
 * Il contenuto, a riposo, occupa la scena nelle sue coordinate ("locali": la foto intera nello schermo e' `fitRect`). Lo schermo lo vede
 * trasformato cosi': `schermo = scale * locale + translation` (origine della trasformazione in alto a sinistra), ritagliato a [clip] (in
 * coordinate locali; null = nessun ritaglio) e con l'opacita' [alpha]. Niente misure che cambiano: solo il `graphicsLayer` (traslazione,
 * scala, ritaglio, alfa), che non ri-misura niente e non rialloca i layer del vetro (lezione P1/P2 sull'S25).
 *
 * Chi la scrive: la chiusura (`ViewerDismissState.layer`, H1: il trascinamento, il pizzico, l'Indietro predittivo e il volo alla cella), applicata
 * a tutto il pager, e il pannello informazioni (`InfoGeometry`, H2: il contenuto che sale attaccato al bordo del pannello), applicato a ogni
 * pagina con le sue proporzioni (`infoPageLayer` in `ViewerOverlay.kt`). Non agiscono mai insieme: il pannello si chiude prima del viewer.
 */
@Immutable
internal data class ContentLayer(
  val scale: Float = 1f,
  val translation: Offset = Offset.Zero,
  /** Il ritaglio, in coordinate locali (prima della trasformazione); null = niente ritaglio. */
  val clip: Rect? = null,
  val alpha: Float = 1f,
) {

  /** Dove finisce sullo schermo il punto locale [local]. */
  fun map(local: Offset): Offset = Offset(local.x * scale + translation.x, local.y * scale + translation.y)

  /** Dove finisce sullo schermo il rettangolo locale [local]. */
  fun map(local: Rect): Rect = Rect(map(local.topLeft), map(local.bottomRight))

  /**
   * Questa trasformazione **dentro** [outer]: prima questa, poi [outer] (`schermo = outer(questa(locale))`). Il ritaglio resta quello in
   * coordinate locali di chi ce l'ha: la chiusura lo usa solo nel volo, quando il pannello e' gia' chiuso (identita').
   */
  infix fun then(outer: ContentLayer): ContentLayer = ContentLayer(
    scale = outer.scale * scale,
    translation = Offset(outer.scale * translation.x + outer.translation.x, outer.scale * translation.y + outer.translation.y),
    clip = clip ?: outer.clip,
    alpha = alpha * outer.alpha,
  )

  val isIdentity: Boolean get() = scale == 1f && translation == Offset.Zero && clip == null && alpha == 1f

  companion object {
    val Identity: ContentLayer = ContentLayer()

    /** La scala [scale] intorno al punto [pivot] dello schermo, poi lo spostamento [shift]. */
    fun scaledAround(scale: Float, pivot: Offset, shift: Offset = Offset.Zero): ContentLayer = ContentLayer(
      scale = scale,
      translation = Offset(pivot.x * (1f - scale) + shift.x, pivot.y * (1f - scale) + shift.y),
    )
  }
}

/**
 * Applica [layer] al contenuto, **letto nel disegno** (lambda del `graphicsLayer`): un fotogramma di trascinamento non ricompone niente.
 * [visible] falso nasconde il contenuto (durante l'espansione si vede la foto che cresce, non il pager).
 */
internal fun Modifier.viewerContentLayer(visible: () -> Boolean, layer: () -> ContentLayer): Modifier = graphicsLayer {
  if (!visible()) {
    alpha = 0f
    return@graphicsLayer
  }
  val current = layer()
  if (current.isIdentity) return@graphicsLayer
  transformOrigin = TransformOrigin(0f, 0f)
  scaleX = current.scale
  scaleY = current.scale
  translationX = current.translation.x
  translationY = current.translation.y
  alpha = current.alpha
  val window = current.clip
  if (window != null) {
    clip = true
    shape = WindowShape(window)
  }
}

/**
 * Il ritaglio del volo: un rettangolo nelle coordinate locali. Le celle della parete sono squadrate (`GalleryWall.CornerScale` = 0), quindi
 * il contenuto si posa con gli angoli vivi, come la miniatura: niente raggio da animare (e niente `RoundedCornerShape`).
 */
private class WindowShape(private val rect: Rect) : Shape {
  override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline = Outline.Rectangle(rect)

  override fun equals(other: Any?): Boolean = other is WindowShape && other.rect == rect

  override fun hashCode(): Int = rect.hashCode()
}
