package dev.pampa.mycloudgallery.core.metadata.format

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * ThumbHash: un'anteprima sfocata in circa 25 byte (algoritmo pubblico di Evan Wallace, https://evanw.github.io/thumbhash/,
 * licenza MIT). Questa e' un'implementazione propria del formato descritto da quella pagina, non una copia di codice:
 * i byte prodotti sono quelli del formato pubblico (si leggono con qualunque altro decodificatore ThumbHash), ma la
 * struttura del programma e' nostra.
 *
 * L'idea: l'immagine, composta sul suo colore medio dove e' trasparente, si converte in luminanza L e due assi di
 * colore P (giallo-blu) e Q (rosso-verde), e di ognuno si tiene la media (DC) e pochi coefficienti DCT di bassa
 * frequenza (AC), triangolari, a 4 bit. Il rapporto fra i lati viene dal numero di coefficienti orizzontali e verticali
 * della luminanza.
 */
internal object ThumbHashAlgorithm {

  const val MAX_INPUT_SIDE: Int = 100

  /** Un'immagine decodificata: ARGB non premoltiplicato, riga per riga. */
  class Decoded(val width: Int, val height: Int, val argb: IntArray)

  // ---- codifica ------------------------------------------------------------------------------------------------

  /** Codifica [w] x [h] pixel RGBA (4 byte per pixel, riga per riga). Oltre 100 pixel per lato riduce prima. */
  fun encode(w: Int, h: Int, rgba: ByteArray): ByteArray {
    require(w >= 1 && h >= 1 && rgba.size >= w * h * 4) { "Immagine non valida per il segnaposto." }
    if (w > MAX_INPUT_SIDE || h > MAX_INPUT_SIDE) {
      val scale = MAX_INPUT_SIDE.toDouble() / max(w, h)
      val nw = max(1, (w * scale).toInt())
      val nh = max(1, (h * scale).toInt())
      return encode(nw, nh, boxDownscale(w, h, rgba, nw, nh))
    }
    val count = w * h

    // Colore medio, pesato dall'opacita'.
    var avgR = 0.0
    var avgG = 0.0
    var avgB = 0.0
    var avgA = 0.0
    for (i in 0 until count) {
      val alpha = (rgba[i * 4 + 3].toInt() and 0xFF) / 255.0
      avgR += alpha * (rgba[i * 4].toInt() and 0xFF) / 255.0
      avgG += alpha * (rgba[i * 4 + 1].toInt() and 0xFF) / 255.0
      avgB += alpha * (rgba[i * 4 + 2].toInt() and 0xFF) / 255.0
      avgA += alpha
    }
    if (avgA > 0) {
      avgR /= avgA
      avgG /= avgA
      avgB /= avgA
    }
    val hasAlpha = avgA < count

    // Quanti coefficienti di luminanza: il lato lungo ne prende il massimo (7, o 5 con la trasparenza).
    val limit = if (hasAlpha) 5 else 7
    val longest = max(w, h)
    val lx = max(1, Math.round(limit.toDouble() * w / longest).toInt())
    val ly = max(1, Math.round(limit.toDouble() * h / longest).toInt())

    val l = DoubleArray(count)
    val p = DoubleArray(count)
    val q = DoubleArray(count)
    val a = DoubleArray(count)
    for (i in 0 until count) {
      val alpha = (rgba[i * 4 + 3].toInt() and 0xFF) / 255.0
      val r = avgR * (1 - alpha) + alpha * (rgba[i * 4].toInt() and 0xFF) / 255.0
      val g = avgG * (1 - alpha) + alpha * (rgba[i * 4 + 1].toInt() and 0xFF) / 255.0
      val b = avgB * (1 - alpha) + alpha * (rgba[i * 4 + 2].toInt() and 0xFF) / 255.0
      l[i] = (r + g + b) / 3
      p[i] = (r + g) / 2 - b
      q[i] = r - g
      a[i] = alpha
    }

    val lc = transform(l, w, h, max(3, lx), max(3, ly))
    val pc = transform(p, w, h, 3, 3)
    val qc = transform(q, w, h, 3, 3)
    val ac = if (hasAlpha) transform(a, w, h, 5, 5) else null

    val landscape = w > h
    val header24 = field(63 * lc.dc, 63) or
      (field(31.5 + 31.5 * pc.dc, 63) shl 6) or
      (field(31.5 + 31.5 * qc.dc, 63) shl 12) or
      (field(31 * lc.scale, 31) shl 18) or
      ((if (hasAlpha) 1 else 0) shl 23)
    val header16 = (if (landscape) ly else lx) or
      (field(63 * pc.scale, 63) shl 3) or
      (field(63 * qc.scale, 63) shl 9) or
      ((if (landscape) 1 else 0) shl 15)

    val acStart = if (hasAlpha) 6 else 5
    val nibbles = ArrayList<Int>()
    for (channel in listOfNotNull(lc, pc, qc, ac)) for (f in channel.ac) nibbles += field(15 * f, 15)
    val out = ByteArray(acStart + (nibbles.size + 1) / 2)
    out[0] = header24.toByte()
    out[1] = (header24 shr 8).toByte()
    out[2] = (header24 shr 16).toByte()
    out[3] = header16.toByte()
    out[4] = (header16 shr 8).toByte()
    if (ac != null) out[5] = (field(15 * ac.dc, 15) or (field(15 * ac.scale, 15) shl 4)).toByte()
    for ((k, nibble) in nibbles.withIndex()) {
      val at = acStart + (k shr 1)
      out[at] = (out[at].toInt() or (nibble shl ((k and 1) shl 2))).toByte()
    }
    return out
  }

  /** Un valore reale arrotondato e portato nel campo `0..max` (gli errori di virgola mobile non escono dai bit). */
  private fun field(value: Double, max: Int): Int = Math.round(value).toInt().coerceIn(0, max)

  private class Channel(val dc: Double, val ac: DoubleArray, val scale: Double)

  /** DC e coefficienti AC (nel triangolo `cx * ny < nx * (ny - cy)`) di un canale, gli AC normalizzati in 0..1. */
  private fun transform(channel: DoubleArray, w: Int, h: Int, nx: Int, ny: Int): Channel {
    var dc = 0.0
    val acs = ArrayList<Double>()
    var scale = 0.0
    val fx = DoubleArray(w)
    for (cy in 0 until ny) {
      var cx = 0
      while (cx * ny < nx * (ny - cy)) {
        for (x in 0 until w) fx[x] = cos(PI / w * cx * (x + 0.5))
        var sum = 0.0
        for (y in 0 until h) {
          val fy = cos(PI / h * cy * (y + 0.5))
          var row = 0.0
          for (x in 0 until w) row += channel[x + y * w] * fx[x]
          sum += row * fy
        }
        sum /= (w * h)
        if (cx > 0 || cy > 0) {
          acs += sum
          scale = max(scale, abs(sum))
        } else {
          dc = sum
        }
        cx++
      }
    }
    val normalized = DoubleArray(acs.size) { i -> if (scale > 0) 0.5 + 0.5 / scale * acs[i] else 0.5 }
    return Channel(dc, normalized, scale)
  }

  /** Riduce con la media dell'area di ogni cella (solo per immagini piu' grandi di 100 pixel per lato). */
  private fun boxDownscale(w: Int, h: Int, rgba: ByteArray, nw: Int, nh: Int): ByteArray {
    val out = ByteArray(nw * nh * 4)
    for (y in 0 until nh) {
      val y0 = y * h / nh
      val y1 = max(y0 + 1, (y + 1) * h / nh)
      for (x in 0 until nw) {
        val x0 = x * w / nw
        val x1 = max(x0 + 1, (x + 1) * w / nw)
        var r = 0L
        var g = 0L
        var b = 0L
        var a = 0L
        var n = 0
        for (yy in y0 until min(y1, h)) for (xx in x0 until min(x1, w)) {
          val at = (yy * w + xx) * 4
          r += rgba[at].toInt() and 0xFF
          g += rgba[at + 1].toInt() and 0xFF
          b += rgba[at + 2].toInt() and 0xFF
          a += rgba[at + 3].toInt() and 0xFF
          n++
        }
        val o = (y * nw + x) * 4
        if (n > 0) {
          out[o] = (r / n).toByte()
          out[o + 1] = (g / n).toByte()
          out[o + 2] = (b / n).toByte()
          out[o + 3] = (a / n).toByte()
        }
      }
    }
    return out
  }

  // ---- decodifica ----------------------------------------------------------------------------------------------

  /** Il rapporto larghezza / altezza approssimato, dall'intestazione (senza il minimo di 3 coefficienti). */
  fun aspectRatio(hash: ByteArray): Double {
    if (hash.size < 5) return 1.0
    val hasAlpha = hash.u8(2) and 0x80 != 0
    val landscape = hash.u8(4) and 0x80 != 0
    val shortAxis = hash.u8(3) and 7
    val lx = if (landscape) (if (hasAlpha) 5 else 7) else shortAxis
    val ly = if (landscape) shortAxis else (if (hasAlpha) 5 else 7)
    if (lx <= 0 || ly <= 0) return 1.0
    return lx.toDouble() / ly
  }

  /** Decodifica a [w] x [h] pixel. Un hash troppo corto si legge con i coefficienti mancanti a zero: non lancia mai. */
  fun decode(hash: ByteArray, w: Int, h: Int): IntArray {
    val width = max(1, w)
    val height = max(1, h)
    if (hash.size < 5) return IntArray(width * height) { GRAY }
    val header24 = hash.u8(0) or (hash.u8(1) shl 8) or (hash.u8(2) shl 16)
    val header16 = hash.u8(3) or (hash.u8(4) shl 8)
    val lDc = (header24 and 63) / 63.0
    val pDc = ((header24 shr 6) and 63) / 31.5 - 1
    val qDc = ((header24 shr 12) and 63) / 31.5 - 1
    val lScale = ((header24 shr 18) and 31) / 31.0
    val hasAlpha = (header24 shr 23) != 0
    val pScale = ((header16 shr 3) and 63) / 63.0
    val qScale = ((header16 shr 9) and 63) / 63.0
    val landscape = (header16 shr 15) != 0
    val lx = max(3, if (landscape) (if (hasAlpha) 5 else 7) else header16 and 7)
    val ly = max(3, if (landscape) header16 and 7 else (if (hasAlpha) 5 else 7))
    val aDc = if (hasAlpha && hash.size > 5) (hash.u8(5) and 15) / 15.0 else 1.0
    val aScale = if (hasAlpha && hash.size > 5) (hash.u8(5) shr 4) / 15.0 else 0.0

    var nibbleIndex = 0
    val acStart = if (hasAlpha) 6 else 5
    fun readAc(nx: Int, ny: Int, scale: Double): DoubleArray {
      val list = ArrayList<Double>()
      for (cy in 0 until ny) {
        var cx = if (cy > 0) 0 else 1
        while (cx * ny < nx * (ny - cy)) {
          val byteAt = acStart + (nibbleIndex shr 1)
          val value = if (byteAt < hash.size) {
            val nibble = (hash.u8(byteAt) shr ((nibbleIndex and 1) shl 2)) and 15
            (nibble / 7.5 - 1) * scale
          } else {
            0.0
          }
          nibbleIndex++
          list += value
          cx++
        }
      }
      return list.toDoubleArray()
    }
    val lAc = readAc(lx, ly, lScale)
    val pAc = readAc(3, 3, pScale * 1.25)
    val qAc = readAc(3, 3, qScale * 1.25)
    val aAc = if (hasAlpha) readAc(5, 5, aScale) else DoubleArray(0)

    val out = IntArray(width * height)
    val fx = DoubleArray(max(lx, 5))
    val fy = DoubleArray(max(ly, 5))
    for (y in 0 until height) {
      for (cy in fy.indices) fy[cy] = cos(PI / height * (y + 0.5) * cy)
      for (x in 0 until width) {
        for (cx in fx.indices) fx[cx] = cos(PI / width * (x + 0.5) * cx)
        var l = lDc
        var p = pDc
        var q = qDc
        var a = aDc

        var j = 0
        for (cy in 0 until ly) {
          val fy2 = fy[cy] * 2
          var cx = if (cy > 0) 0 else 1
          while (cx * ly < lx * (ly - cy)) {
            l += lAc[j] * fx[cx] * fy2
            cx++
            j++
          }
        }
        j = 0
        for (cy in 0 until 3) {
          val fy2 = fy[cy] * 2
          var cx = if (cy > 0) 0 else 1
          while (cx < 3 - cy) {
            val f = fx[cx] * fy2
            p += pAc[j] * f
            q += qAc[j] * f
            cx++
            j++
          }
        }
        if (hasAlpha) {
          j = 0
          for (cy in 0 until 5) {
            val fy2 = fy[cy] * 2
            var cx = if (cy > 0) 0 else 1
            while (cx < 5 - cy) {
              a += aAc[j] * fx[cx] * fy2
              cx++
              j++
            }
          }
        }

        val b = l - 2.0 / 3.0 * p
        val r = (3.0 * l - b + q) / 2.0
        val g = r - q
        out[y * width + x] = (channel(a) shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
      }
    }
    return out
  }

  private fun channel(value: Double): Int = Math.round(255.0 * min(1.0, max(0.0, value))).toInt()

  private const val GRAY: Int = 0xFF808080.toInt()
}
