package dev.pampa.mycloudgallery.core.designsystem.adaptive

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.RectF
import android.view.View
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlin.math.max
import kotlin.math.min

// L'OMBRA E L'AVVIO DEL TRASCINAMENTO (Fase 10, P6, D20: "l'ombra e' la miniatura locale"). L'ombra e' la miniatura che la schermata ha gia' in memoria (mai una
// lettura del file: la miniatura locale), ritagliata in un quadrato con gli angoli arrotondati; se gli elementi sono piu' d'uno, un cerchio con il numero. Senza
// miniatura un riquadro neutro. I colori arrivano dal tema (nessun colore scritto qui).

/** I colori dell'ombra, presi dal tema da chi la crea. */
internal class ShadowColors(val tile: Int, val badge: Int, val onBadge: Int)

internal object MediaDragShadows {

  /** Un quadrato di [sizePx] pixel: la miniatura (riempie il quadrato, al centro) o il riquadro neutro, e il numero se sono piu' di uno. Sempre una bitmap in software. */
  fun bitmap(shadow: MediaDragShadow, sizePx: Int, colors: ShadowColors): Bitmap {
    val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val radius = sizePx * CORNER_FRACTION
    val clip = Path().apply { addRoundRect(RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()), radius, radius, Path.Direction.CW) }
    canvas.save()
    canvas.clipPath(clip)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    paint.color = colors.tile
    canvas.drawRect(0f, 0f, sizePx.toFloat(), sizePx.toFloat(), paint)
    softwareCopy(shadow)?.let { source ->
      // Si riempie il quadrato (come una cella): si ritaglia il lato lungo attorno al centro.
      val side = min(source.width, source.height)
      val left = (source.width - side) / 2
      val top = (source.height - side) / 2
      canvas.drawBitmap(source, android.graphics.Rect(left, top, left + side, top + side), RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()), paint)
    }
    canvas.restore()
    if (shadow.count > 1) drawBadge(canvas, sizePx, shadow.count, colors)
    return out
  }

  private fun softwareCopy(shadow: MediaDragShadow): Bitmap? {
    val source = shadow.thumbnail?.asAndroidBitmap() ?: return null
    if (source.isRecycled || source.width <= 0 || source.height <= 0) return null
    // Una bitmap in hardware non si disegna su un canvas in software: se ne fa una copia.
    return if (source.config == Bitmap.Config.HARDWARE) source.copy(Bitmap.Config.ARGB_8888, false) else source
  }

  private fun drawBadge(canvas: Canvas, sizePx: Int, count: Int, colors: ShadowColors) {
    val radius = sizePx * BADGE_FRACTION
    val cx = sizePx - radius
    val cy = radius
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = colors.badge
    canvas.drawCircle(cx, cy, radius, paint)
    paint.color = colors.onBadge
    paint.textAlign = Paint.Align.CENTER
    paint.textSize = radius * (if (count >= 100) 0.9f else 1.15f)
    paint.isFakeBoldText = true
    val baseline = cy - (paint.descent() + paint.ascent()) / 2
    canvas.drawText(count.toString(), cx, baseline, paint)
  }

  private const val CORNER_FRACTION = 0.12f
  private const val BADGE_FRACTION = 0.22f
}

/** Il disegno dell'ombra per Android: una bitmap pronta, con il dito al centro. */
internal class BitmapDragShadow(view: View, private val bitmap: Bitmap) : View.DragShadowBuilder(view) {
  override fun onProvideShadowMetrics(outShadowSize: Point, outShadowTouchPoint: Point) {
    outShadowSize.set(max(1, bitmap.width), max(1, bitmap.height))
    outShadowTouchPoint.set(bitmap.width / 2, bitmap.height / 2)
  }

  override fun onDrawShadow(canvas: Canvas) {
    canvas.drawBitmap(bitmap, 0f, 0f, null)
  }
}

/** Il segno che i trascinamenti nostri portano come stato locale: un bersaglio dell'app lo riconosce e non offre la ricezione per un trascinamento che e' uscito da lei. */
object MediaDragLocalState {
  override fun toString(): String = "MediaDragLocalState"
}

/** L'avvio vero: `View.startDragAndDrop` sulla finestra, con il segno nostro come stato locale. */
internal class ViewMediaDragStarter(private val view: View, private val sizePx: Int, private val colors: ShadowColors) : MediaDragStarter {
  override fun start(drag: MediaDrag, shadow: MediaDragShadow): Boolean = try {
    view.startDragAndDrop(drag.clip, BitmapDragShadow(view, MediaDragShadows.bitmap(shadow, sizePx, colors)), MediaDragLocalState, drag.flags)
  } catch (_: IllegalStateException) {
    false
  } catch (_: IllegalArgumentException) {
    false
  }
}
