package dev.pampa.mycloudgallery.core.ai.cutout

import dev.pampa.mycloudgallery.core.ai.contract.AlphaMask
import dev.pampa.mycloudgallery.core.ai.contract.CutoutImage
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Il PNG del ritaglio (docs/fase6-progetto.md D44, D47, §2.10): il soggetto con la sua maschera come trasparenza, ritagliato sul suo
 * rettangolo, con il **contorno** bianco dell'adesivo se richiesto. Il file porta solo `IHDR`, `IDAT` e `IEND` ([PngEncoder]): nessuna
 * posizione, nessuna data, nessun programma. I pixel completamente trasparenti sono a zero (RGBA 0,0,0,0): sotto lo sfondo tolto non
 * resta niente della foto.
 *
 * Il contorno e' la maschera dilatata di un raggio proporzionale al soggetto (circa l'1,2% del lato lungo, almeno 3 px), calcolata con
 * la distanza euclidea esatta su una griglia di al piu' 1024 px e interpolata: un bordo liscio senza una distanza a piena risoluzione
 * (50 MB di virgola mobile per una foto da 12 megapixel).
 */
object CutoutRender {

  /** Il lato lungo, in frazione, del raggio del contorno. */
  const val OUTLINE_FRACTION: Float = 0.012f

  /** Il raggio minimo del contorno, in pixel. */
  const val OUTLINE_MIN_RADIUS: Int = 3

  /** Sotto questo valore di alfa un punto non conta per il rettangolo del soggetto (un'aureola quasi invisibile non allarga il file). */
  private const val BOUNDS_ALPHA = 8

  /** La grandezza del file prima di scriverlo: larghezza, altezza e raggio del contorno. */
  class Layout internal constructor(val width: Int, val height: Int, val outlineRadius: Int, internal val left: Int, internal val top: Int, internal val pad: Int)

  /** Il PNG (RGBA, straight alpha) del soggetto [mask] di [image]; [outline] aggiunge il bordo bianco dell'adesivo. */
  fun png(image: CutoutImage, mask: AlphaMask, outline: Boolean): ByteArray {
    require(mask.width == image.width && mask.height == image.height) { "Maschera e immagine di taglie diverse." }
    val alpha = mask.raw()
    val pixels = image.raw()
    val layout = layout(alpha, image.width, image.height, outline)
    val rows = rows(pixels, alpha, image.width, image.height, layout, outline)
    return PngEncoder.encode(layout.width, layout.height, rows, level = PNG_LEVEL)
  }

  /** La taglia del file e dove sta il soggetto, senza scrivere niente (le prove). */
  fun layoutOf(image: CutoutImage, mask: AlphaMask, outline: Boolean): Layout = layout(mask.raw(), image.width, image.height, outline)

  private fun layout(alpha: ByteArray, w: Int, h: Int, outline: Boolean): Layout {
    var left = w
    var top = h
    var right = -1
    var bottom = -1
    for (y in 0 until h) {
      val row = y * w
      for (x in 0 until w) {
        if ((alpha[row + x].toInt() and 0xff) >= BOUNDS_ALPHA) {
          if (x < left) left = x
          if (x > right) right = x
          if (y < top) top = y
          bottom = y
        }
      }
    }
    if (right < left) {
      // Nessun soggetto: l'immagine intera, trasparente.
      return Layout(w, h, 0, 0, 0, 0)
    }
    val boxW = right - left + 1
    val boxH = bottom - top + 1
    val radius = if (outline) max(OUTLINE_MIN_RADIUS, (OUTLINE_FRACTION * max(boxW, boxH)).roundToInt()) else 0
    val pad = if (outline) radius + 2 else 1
    return Layout(boxW + 2 * pad, boxH + 2 * pad, radius, left, top, pad)
  }

  private fun rows(pixels: IntArray, alpha: ByteArray, w: Int, h: Int, layout: Layout, outline: Boolean): PngEncoder.Rows {
    val originX = layout.left - layout.pad
    val originY = layout.top - layout.pad
    val field = if (outline && layout.outlineRadius > 0) OutlineField.build(alpha, w, h, layout, originX, originY) else null
    return PngEncoder.Rows { y, into ->
      val iy = originY + y
      val rowInside = iy in 0 until h
      for (x in 0 until layout.width) {
        val ix = originX + x
        var a = 0f
        var r = 0
        var g = 0
        var b = 0
        if (rowInside && ix in 0 until w) {
          val idx = iy * w + ix
          val av = alpha[idx].toInt() and 0xff
          if (av != 0) {
            a = av / 255f
            val p = pixels[idx]
            r = (p shr 16) and 0xff
            g = (p shr 8) and 0xff
            b = p and 0xff
          }
        }
        val o = field?.outlineAt(x, y, layout.outlineRadius) ?: 0f
        val total = a + o * (1f - a)
        val k = x * 4
        if (total <= 0f) {
          into[k] = 0
          into[k + 1] = 0
          into[k + 2] = 0
          into[k + 3] = 0
        } else {
          // Il soggetto sopra il bianco del contorno: colore dritto (non premoltiplicato), come vuole il PNG.
          val white = o * (1f - a)
          into[k] = ((r * a + 255f * white) / total + 0.5f).toInt().coerceIn(0, 255).toByte()
          into[k + 1] = ((g * a + 255f * white) / total + 0.5f).toInt().coerceIn(0, 255).toByte()
          into[k + 2] = ((b * a + 255f * white) / total + 0.5f).toInt().coerceIn(0, 255).toByte()
          into[k + 3] = (total * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
        }
      }
    }
  }

  private const val PNG_LEVEL = 4
}

/** La distanza dal soggetto su una griglia grossolana della tela del file, per il contorno. */
internal class OutlineField private constructor(
  private val distance: FloatArray,
  private val gw: Int,
  private val gh: Int,
  private val step: Int,
) {

  /** La copertura (0..1) del contorno nel punto ([x], [y]) della tela: 1 dentro il raggio, bordo antialias di un pixel. */
  fun outlineAt(x: Int, y: Int, radius: Int): Float {
    val fx = ((x + 0.5f) / step - 0.5f).coerceIn(0f, gw - 1f)
    val fy = ((y + 0.5f) / step - 0.5f).coerceIn(0f, gh - 1f)
    val x0 = fx.toInt()
    val y0 = fy.toInt()
    val x1 = min(x0 + 1, gw - 1)
    val y1 = min(y0 + 1, gh - 1)
    val wx = fx - x0
    val wy = fy - y0
    val top = distance[y0 * gw + x0] * (1 - wx) + distance[y0 * gw + x1] * wx
    val bottom = distance[y1 * gw + x0] * (1 - wx) + distance[y1 * gw + x1] * wx
    val d = (top * (1 - wy) + bottom * wy) * step
    val c = radius + 0.5f - d
    return if (c <= 0f) 0f else if (c >= 1f) 1f else c
  }

  companion object {
    private const val INF = 1e12f
    private const val MAX_SIDE = 1024

    fun build(alpha: ByteArray, w: Int, h: Int, layout: CutoutRender.Layout, originX: Int, originY: Int): OutlineField {
      val step = max(1, ceil(max(layout.width, layout.height).toDouble() / MAX_SIDE).toInt())
      val gw = (layout.width + step - 1) / step
      val gh = (layout.height + step - 1) / step
      val squared = FloatArray(gw * gh) { INF }
      // I blocchi che contengono un punto del soggetto (alfa >= 128).
      val y0 = max(0, originY)
      val y1 = min(h, originY + layout.height)
      val x0 = max(0, originX)
      val x1 = min(w, originX + layout.width)
      for (y in y0 until y1) {
        val gy = (y - originY) / step
        val row = y * w
        for (x in x0 until x1) {
          if ((alpha[row + x].toInt() and 0xff) >= 128) squared[gy * gw + (x - originX) / step] = 0f
        }
      }
      squaredDistance(squared, gw, gh)
      for (i in squared.indices) squared[i] = sqrt(squared[i])
      return OutlineField(squared, gw, gh, step)
    }

    /** La trasformata della distanza euclidea esatta (Felzenszwalb e Huttenlocher), sul posto: 0 nei semi, [INF] altrove, al quadrato. */
    private fun squaredDistance(f: FloatArray, w: Int, h: Int) {
      val n = max(w, h)
      val col = FloatArray(n)
      val out = FloatArray(n)
      val v = IntArray(n)
      val z = FloatArray(n + 1)
      for (x in 0 until w) {
        for (y in 0 until h) col[y] = f[y * w + x]
        transform1d(col, out, h, v, z)
        for (y in 0 until h) f[y * w + x] = out[y]
      }
      for (y in 0 until h) {
        for (x in 0 until w) col[x] = f[y * w + x]
        transform1d(col, out, w, v, z)
        for (x in 0 until w) f[y * w + x] = out[x]
      }
    }

    private fun transform1d(f: FloatArray, d: FloatArray, n: Int, v: IntArray, z: FloatArray) {
      var k = 0
      v[0] = 0
      z[0] = -INF
      z[1] = INF
      for (q in 1 until n) {
        var s: Float
        while (true) {
          val p = v[k]
          s = ((f[q] + q.toFloat() * q) - (f[p] + p.toFloat() * p)) / (2f * q - 2f * p)
          if (s <= z[k] && k > 0) k-- else break
        }
        k++
        v[k] = q
        z[k] = s
        z[k + 1] = INF
      }
      k = 0
      for (q in 0 until n) {
        while (z[k + 1] < q) k++
        val dq = (q - v[k]).toFloat()
        d[q] = dq * dq + f[v[k]]
      }
    }
  }
}
