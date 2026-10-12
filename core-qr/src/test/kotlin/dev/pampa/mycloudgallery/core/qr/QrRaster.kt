package dev.pampa.mycloudgallery.core.qr

import dev.pampa.mycloudgallery.core.qr.contract.QrBits
import dev.pampa.mycloudgallery.core.qr.render.QrGeometry
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Un'immagine in scala di grigi, come la consegna la fotocamera allo scanner: un byte per pixel
 * (0 = nero), righe una dopo l'altra.
 */
internal class Luma(val width: Int, val height: Int, val data: ByteArray) {

  init {
    require(data.size == width * height)
  }

  operator fun get(x: Int, y: Int): Int = data[y * width + x].toInt() and 0xFF

  /** Il valore in (x, y) con interpolazione bilineare; fuori dall'immagine, [outside]. */
  fun sample(x: Double, y: Double, outside: Int): Double {
    if (x < 0.0 || y < 0.0 || x > width - 1.0 || y > height - 1.0) return outside.toDouble()
    val x0 = floor(x).toInt()
    val y0 = floor(y).toInt()
    val x1 = min(x0 + 1, width - 1)
    val y1 = min(y0 + 1, height - 1)
    val fx = x - x0
    val fy = y - y0
    val top = this[x0, y0] * (1 - fx) + this[x1, y0] * fx
    val bottom = this[x0, y1] * (1 - fx) + this[x1, y1] * fx
    return top * (1 - fy) + bottom * fy
  }
}

/**
 * Il banco di prova su JVM: dalla matrice di un QR a un'immagine come la vedrebbe una fotocamera
 * (disegno, rotazione, scala, rumore, sfocatura, gradiente di luce), senza nessuna delle due API di Android.
 *
 * Il disegno usa la stessa [QrGeometry] dello schermo, cosi' la prova "andata e ritorno" passa dal
 * calcolo vero dei margini e della misura del modulo.
 */
internal object QrRaster {

  /** Il QR disegnato in un'area di [side] x [side] pixel, come fa `QrCode` (modulo intero, centrato). */
  fun render(bits: QrBits, side: Int, dark: Int = 0, light: Int = 255): Luma {
    val data = ByteArray(side * side) { light.toByte() }
    val g = QrGeometry.of(side.toFloat(), side.toFloat(), bits.size)
    val module = g.module
    for (my in 0 until bits.size) for (mx in 0 until bits.size) {
      if (!bits[mx, my]) continue
      val x0 = (g.originX + mx * module).roundToInt()
      val x1 = (g.originX + (mx + 1) * module).roundToInt()
      val y0 = (g.originY + my * module).roundToInt()
      val y1 = (g.originY + (my + 1) * module).roundToInt()
      for (y in y0 until min(y1, side)) for (x in x0 until min(x1, side)) data[y * side + x] = dark.toByte()
    }
    return Luma(side, side, data)
  }

  /** Il QR con [modulePx] pixel per modulo e la zona di rispetto: il lato e' scelto di conseguenza. */
  fun renderWithModulePx(bits: QrBits, modulePx: Int, dark: Int = 0, light: Int = 255): Luma =
    render(bits, side = (bits.size + 2 * dev.pampa.mycloudgallery.core.qr.render.QUIET_ZONE_MODULES) * modulePx, dark, light)

  fun Luma.rotate90(): Luma {
    // senso orario: il pixel (x, y) va in (height - 1 - y, x)
    val out = ByteArray(data.size)
    for (y in 0 until height) for (x in 0 until width) out[x * height + (height - 1 - y)] = data[y * width + x]
    return Luma(height, width, out)
  }

  fun Luma.rotate180(): Luma = Luma(width, height, data.reversedArray())

  fun Luma.rotate270(): Luma = rotate90().rotate90().rotate90()

  /** Ruota di [degrees] gradi attorno al centro, su una tela che contiene tutta l'immagine; il fondo e' [fill]. */
  fun Luma.rotateBy(degrees: Double, fill: Int = 255): Luma {
    val rad = degrees * PI / 180.0
    val c = cos(rad)
    val s = sin(rad)
    val newW = (abs(width * c) + abs(height * s)).toInt() + 1
    val newH = (abs(width * s) + abs(height * c)).toInt() + 1
    val out = ByteArray(newW * newH)
    val cx = (width - 1) / 2.0
    val cy = (height - 1) / 2.0
    val ncx = (newW - 1) / 2.0
    val ncy = (newH - 1) / 2.0
    for (y in 0 until newH) for (x in 0 until newW) {
      // mappa inversa: dal pixel di arrivo a quello di partenza
      val dx = x - ncx
      val dy = y - ncy
      val sx = dx * c + dy * s + cx
      val sy = -dx * s + dy * c + cy
      out[y * newW + x] = sample(sx, sy, fill).roundToInt().coerceIn(0, 255).toByte()
    }
    return Luma(newW, newH, out)
  }

  /** Ridimensiona di [factor] con interpolazione bilineare (con riduzione, una media: come un sensore piu' piccolo). */
  fun Luma.scaled(factor: Double): Luma {
    val newW = max(1, (width * factor).roundToInt())
    val newH = max(1, (height * factor).roundToInt())
    val out = ByteArray(newW * newH)
    if (factor < 1.0) {
      // riduzione: media dell'area coperta, altrimenti si perdono i moduli sottili
      for (y in 0 until newH) for (x in 0 until newW) {
        val sx0 = (x / factor).toInt()
        val sx1 = max(sx0 + 1, min(width, ((x + 1) / factor).toInt()))
        val sy0 = (y / factor).toInt()
        val sy1 = max(sy0 + 1, min(height, ((y + 1) / factor).toInt()))
        var sum = 0
        var n = 0
        for (yy in sy0 until min(sy1, height)) for (xx in sx0 until min(sx1, width)) {
          sum += this[xx, yy]
          n++
        }
        out[y * newW + x] = (sum / max(1, n)).toByte()
      }
    } else {
      for (y in 0 until newH) for (x in 0 until newW) {
        out[y * newW + x] = sample(x / factor, y / factor, 255).roundToInt().coerceIn(0, 255).toByte()
      }
    }
    return Luma(newW, newH, out)
  }

  /** Rumore gaussiano di deviazione [sigma] su ogni pixel, piu' una frazione [saltPepper] di pixel bianchi o neri. */
  fun Luma.noisy(sigma: Double, saltPepper: Double = 0.0, seed: Int = 7): Luma {
    val random = Random(seed)
    val out = ByteArray(data.size)
    for (i in data.indices) {
      var v = (data[i].toInt() and 0xFF) + gaussian(random) * sigma
      if (saltPepper > 0.0 && random.nextDouble() < saltPepper) v = if (random.nextBoolean()) 255.0 else 0.0
      out[i] = v.roundToInt().coerceIn(0, 255).toByte()
    }
    return Luma(width, height, out)
  }

  /** Sfocatura a scatola [radius] pixel: la fotocamera fuori fuoco. */
  fun Luma.blurred(radius: Int): Luma {
    val out = ByteArray(data.size)
    for (y in 0 until height) for (x in 0 until width) {
      var sum = 0
      var n = 0
      for (yy in max(0, y - radius)..min(height - 1, y + radius)) {
        for (xx in max(0, x - radius)..min(width - 1, x + radius)) {
          sum += this[xx, yy]
          n++
        }
      }
      out[y * width + x] = (sum / n).toByte()
    }
    return Luma(width, height, out)
  }

  /** Contrasto ridotto e luce non uniforme: il nero sale e il bianco scende, con un gradiente da sinistra a destra. */
  fun Luma.unevenLight(darkest: Int, lightest: Int, gradient: Int): Luma {
    val out = ByteArray(data.size)
    for (y in 0 until height) for (x in 0 until width) {
      val base = darkest + (this[x, y] / 255.0) * (lightest - darkest)
      val shade = -gradient * (x.toDouble() / max(1, width - 1))
      out[y * width + x] = (base + shade).roundToInt().coerceIn(0, 255).toByte()
    }
    return Luma(width, height, out)
  }

  /** Aggiunge [border] pixel di sfondo [fill] su ogni lato: il QR non riempie il fotogramma. */
  fun Luma.padded(border: Int, fill: Int = 128): Luma {
    val newW = width + 2 * border
    val newH = height + 2 * border
    val out = ByteArray(newW * newH) { fill.toByte() }
    for (y in 0 until height) System.arraycopy(data, y * width, out, (y + border) * newW + border, width)
    return Luma(newW, newH, out)
  }

  private fun gaussian(random: Random): Double {
    // Box-Muller
    val u1 = max(random.nextDouble(), 1e-12)
    val u2 = random.nextDouble()
    return sqrt(-2.0 * kotlin.math.ln(u1)) * cos(2.0 * PI * u2)
  }
}
