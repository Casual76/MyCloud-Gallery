package dev.pampa.mycloudgallery.core.ai.cutout

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Il ridimensionamento delle immagini del ritaglio (docs/fase6-progetto.md D44: "su un'immagine a 512 o 768 px"). Puro, senza classi di
 * Android: provato sulla JVM.
 *
 * In riduzione fa la **media sull'area** (un filtro a scatola con pesi frazionari: nessun pixel sorgente e' saltato, quindi niente
 * aliasing sulle foto da 12 o 200 megapixel); in ingrandimento l'interpolazione bilineare. I due assi sono indipendenti (una
 * panoramica compressa in orizzontale e allungata in verticale funziona). Memoria: una riga intermedia per ogni riga di destinazione
 * (qualche riga in cache), mai l'immagine intera in virgola mobile.
 */
internal object Resampler {

  /** Un asse: per ogni indice di destinazione, i pixel sorgente che vi contribuiscono e il loro peso (somma 1). */
  internal class Taps(val first: IntArray, val count: IntArray, val offset: IntArray, val weight: FloatArray)

  internal fun taps(srcLen: Int, dstLen: Int): Taps {
    require(srcLen > 0 && dstLen > 0) { "Lunghezze non valide." }
    val scale = srcLen.toDouble() / dstLen
    val first = IntArray(dstLen)
    val count = IntArray(dstLen)
    val offset = IntArray(dstLen)
    val weights = ArrayList<Float>()
    for (i in 0 until dstLen) {
      offset[i] = weights.size
      if (scale > 1.0) {
        val start = i * scale
        val end = min((i + 1) * scale, srcLen.toDouble())
        val k0 = floor(start).toInt()
        val k1 = min(srcLen, ceil(end).toInt())
        first[i] = k0
        count[i] = k1 - k0
        var sum = 0.0
        val local = DoubleArray(k1 - k0)
        for (k in k0 until k1) {
          local[k - k0] = min(k + 1.0, end) - max(k.toDouble(), start)
          sum += local[k - k0]
        }
        for (v in local) weights += (v / sum).toFloat()
      } else {
        val c = (i + 0.5) * scale - 0.5
        val k0 = floor(c).toInt()
        val f = (c - k0).toFloat()
        val a = k0.coerceIn(0, srcLen - 1)
        val b = (k0 + 1).coerceIn(0, srcLen - 1)
        if (a == b) {
          first[i] = a
          count[i] = 1
          weights += 1f
        } else {
          first[i] = a
          count[i] = 2
          weights += (1f - f)
          weights += f
        }
      }
    }
    return Taps(first, count, offset, weights.toFloatArray())
  }

  /**
   * L'immagine ARGB [src] ([sw]x[sh]) a [dw]x[dh]; il risultato sono i canali R, G, B in 0..255 (virgola mobile), riga per riga,
   * `dw * dh * 3` valori. L'alfa e' ignorato (le immagini del ritaglio sono opache).
   */
  fun resize(src: IntArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
    require(src.size >= sw * sh) { "Pixel insufficienti." }
    val tx = taps(sw, dw)
    val ty = taps(sh, dh)
    val out = FloatArray(dw * dh * 3)
    val cache = Array(CACHE_ROWS) { FloatArray(dw * 3) }
    val cached = IntArray(CACHE_ROWS) { -1 }
    var next = 0
    for (dy in 0 until dh) {
      val base = dy * dw * 3
      for (t in 0 until ty.count[dy]) {
        val sy = ty.first[dy] + t
        val wy = ty.weight[ty.offset[dy] + t]
        var slot = cached.indexOf(sy)
        if (slot < 0) {
          slot = next
          next = (next + 1) % CACHE_ROWS
          horizontal(src, sy * sw, tx, cache[slot])
          cached[slot] = sy
        }
        val row = cache[slot]
        for (i in 0 until dw * 3) out[base + i] += row[i] * wy
      }
    }
    return out
  }

  /** Una riga sorgente (da [rowStart] in [src]) ridotta alla larghezza di destinazione. */
  private fun horizontal(src: IntArray, rowStart: Int, tx: Taps, into: FloatArray) {
    var o = 0
    for (dx in 0 until tx.first.size) {
      var r = 0f
      var g = 0f
      var b = 0f
      val first = rowStart + tx.first[dx]
      val w0 = tx.offset[dx]
      for (t in 0 until tx.count[dx]) {
        val p = src[first + t]
        val w = tx.weight[w0 + t]
        r += ((p shr 16) and 0xff) * w
        g += ((p shr 8) and 0xff) * w
        b += (p and 0xff) * w
      }
      into[o] = r
      into[o + 1] = g
      into[o + 2] = b
      o += 3
    }
  }

  /** La luminanza (0..1, Rec. 601) di ogni blocco [step]x[step] dell'immagine, media sull'area: la guida del filtro guidato. */
  fun lumaBlocks(src: IntArray, sw: Int, sh: Int, step: Int): FloatArray {
    val gw = (sw + step - 1) / step
    val gh = (sh + step - 1) / step
    val out = FloatArray(gw * gh)
    for (gy in 0 until gh) {
      val y0 = gy * step
      val y1 = min(sh, y0 + step)
      for (gx in 0 until gw) {
        val x0 = gx * step
        val x1 = min(sw, x0 + step)
        var sum = 0f
        for (y in y0 until y1) {
          val rowStart = y * sw
          for (x in x0 until x1) sum += luma(src[rowStart + x])
        }
        out[gy * gw + gx] = sum / ((y1 - y0) * (x1 - x0))
      }
    }
    return out
  }

  /** La luminanza (0..1) di un pixel ARGB. */
  fun luma(p: Int): Float = (0.299f * ((p shr 16) and 0xff) + 0.587f * ((p shr 8) and 0xff) + 0.114f * (p and 0xff)) * (1f / 255f)

  /** Una mappa di valori [src] ([sw]x[sh]) a [dw]x[dh], interpolazione bilineare (le mappe di probabilita' del modello). */
  fun resizeMap(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
    if (sw == dw && sh == dh) return src.copyOf()
    val out = FloatArray(dw * dh)
    val sx = sw.toFloat() / dw
    val sy = sh.toFloat() / dh
    for (y in 0 until dh) {
      val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, sh - 1f)
      val y0 = fy.toInt()
      val y1 = min(y0 + 1, sh - 1)
      val wy = fy - y0
      for (x in 0 until dw) {
        val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, sw - 1f)
        val x0 = fx.toInt()
        val x1 = min(x0 + 1, sw - 1)
        val wx = fx - x0
        val top = src[y0 * sw + x0] * (1 - wx) + src[y0 * sw + x1] * wx
        val bottom = src[y1 * sw + x0] * (1 - wx) + src[y1 * sw + x1] * wx
        out[y * dw + x] = top * (1 - wy) + bottom * wy
      }
    }
    return out
  }

  private const val CACHE_ROWS = 4
}
