package dev.pampa.mycloudgallery.core.library.similar

import java.util.Random
import java.util.stream.IntStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// Fase 5A, P15: le IMMAGINI DI PROVA del §2.12, generate (nessun file nel repository). Una scena e' una funzione continua
// `colore(x, y)` nel quadrato unitario, con la statistica di una foto vera (rumore frattale 1/f: cielo con nuvole, montagne, prato con
// trama fine, case con finestre, alberi, un soggetto in primo piano), quindi si puo' disegnare a qualunque risoluzione e con
// qualunque inquadratura SENZA errori di ricampionamento: il ritaglio del 3%, il raddrizzamento di 1° e la specchiatura sono
// trasformazioni esatte della scena, cioe' i casi "non devono unirsi" piu' vicini possibile all'originale (il caso peggiore per le
// soglie). Le ricompressioni, le riduzioni e gli altri degradi sono invece operazioni vere sui pixel (JPEG vero, filtri veri).

/** Un colore in valori sRGB codificati (0..1). */
internal data class Rgb(val r: Double, val g: Double, val b: Double) {
  fun mix(o: Rgb, t: Double): Rgb = Rgb(r + (o.r - r) * t, g + (o.g - g) * t, b + (o.b - b) * t)

  fun scale(k: Double): Rgb = Rgb(r * k, g * k, b * k)
}

/** Rumore di valore con interpolazione liscia, deterministico (nessun `Random` condiviso: ogni punto del reticolo e' un hash). */
internal class ValueNoise(private val seed: Long) {

  private fun hash(ix: Int, iy: Int): Double {
    var h = seed * 0x9E3779B97F4A7C15uL.toLong() + ix * 0x632BE59BD9B4E019L + iy * 0x85157AF5L
    h = (h xor (h ushr 30)) * -0x40a7b892e31b1a47L
    h = (h xor (h ushr 27)) * -0x6b2fb644ecceee15L
    h = h xor (h ushr 31)
    return (h ushr 11).toDouble() / (1L shl 53).toDouble()
  }

  fun noise(x: Double, y: Double): Double {
    val ix = floor(x).toInt()
    val iy = floor(y).toInt()
    val fx = x - ix
    val fy = y - iy
    val sx = fx * fx * (3 - 2 * fx)
    val sy = fy * fy * (3 - 2 * fy)
    val a = hash(ix, iy)
    val b = hash(ix + 1, iy)
    val c = hash(ix, iy + 1)
    val d = hash(ix + 1, iy + 1)
    return (a + (b - a) * sx) + ((c + (d - c) * sx) - (a + (b - a) * sx)) * sy
  }

  /** Rumore frattale: la somma di [octaves] ottave, ognuna a frequenza doppia e ampiezza dimezzata, in 0..1. */
  fun fbm(x: Double, y: Double, octaves: Int): Double {
    var sum = 0.0
    var amp = 0.5
    var freq = 1.0
    var norm = 0.0
    for (o in 0 until octaves) {
      sum += amp * noise(x * freq, y * freq)
      norm += amp
      amp *= 0.5
      freq *= 2.0
    }
    return sum / norm
  }
}

/** Dove sta e com'e' fatto il soggetto in primo piano (cio' che si muove fra i fotogrammi di una raffica). */
internal data class Subject(val x: Double, val y: Double)

/**
 * Una scena (la foto che il fotografo ha davanti). Tutto deriva da [seed]: scene diverse hanno orizzonte, colori, case e alberi
 * diversi, e nessuna e' simmetrica (la specchiatura deve cambiare l'immagine).
 */
internal class Scene(val seed: Long, val subject: Subject? = null, private val dark: Double = 1.0, private val busy: Boolean = false) {

  private val rnd = Random(seed)
  private val noise = ValueNoise(seed)
  private val horizon = 0.38 + rnd.nextDouble() * 0.14
  private val skyTop = Rgb(0.12 + rnd.nextDouble() * 0.2, 0.30 + rnd.nextDouble() * 0.25, 0.65 + rnd.nextDouble() * 0.3)
  private val skyLow = Rgb(0.72 + rnd.nextDouble() * 0.2, 0.74 + rnd.nextDouble() * 0.2, 0.80 + rnd.nextDouble() * 0.15)
  private val rock = Rgb(0.30 + rnd.nextDouble() * 0.25, 0.32 + rnd.nextDouble() * 0.2, 0.40 + rnd.nextDouble() * 0.25)
  private val grass = Rgb(0.18 + rnd.nextDouble() * 0.25, 0.38 + rnd.nextDouble() * 0.3, 0.12 + rnd.nextDouble() * 0.15)
  private val road = Rgb(0.45 + rnd.nextDouble() * 0.2, 0.40 + rnd.nextDouble() * 0.2, 0.33 + rnd.nextDouble() * 0.2)
  private val roadAngle = 0.4 + rnd.nextDouble() * 1.2
  private val roadOffset = rnd.nextDouble() * 0.5

  private class Building(val x0: Double, val x1: Double, val top: Double, val color: Rgb, val cols: Int, val rows: Int)

  private val buildings = List(3 + rnd.nextInt(3)) {
    val x0 = 0.04 + rnd.nextDouble() * 0.8
    Building(
      x0 = x0,
      x1 = min(0.98, x0 + 0.06 + rnd.nextDouble() * 0.12),
      top = horizon - 0.02 - rnd.nextDouble() * 0.20,
      color = Rgb(0.35 + rnd.nextDouble() * 0.55, 0.30 + rnd.nextDouble() * 0.5, 0.28 + rnd.nextDouble() * 0.5),
      cols = 2 + rnd.nextInt(5),
      rows = 2 + rnd.nextInt(6),
    )
  }

  private class Tree(val x: Double, val y: Double, val rx: Double, val ry: Double, val color: Rgb)

  private val trees = List(2 + rnd.nextInt(4)) {
    Tree(
      x = 0.05 + rnd.nextDouble() * 0.9, y = horizon + 0.02 + rnd.nextDouble() * 0.25, rx = 0.04 + rnd.nextDouble() * 0.06, ry = 0.06 + rnd.nextDouble() * 0.08,
      color = Rgb(0.08 + rnd.nextDouble() * 0.2, 0.25 + rnd.nextDouble() * 0.3, 0.06 + rnd.nextDouble() * 0.15),
    )
  }

  private val jacket = Rgb(0.7 + rnd.nextDouble() * 0.3, 0.1 + rnd.nextDouble() * 0.5, 0.1 + rnd.nextDouble() * 0.4)

  /** Il colore della scena nel punto ([x], [y]) del quadrato unitario (x e' largo 4/3 di y: le trame hanno la stessa scala nei due versi). */
  fun colorAt(x: Double, y: Double): Rgb {
    val px = x * ASPECT
    var c: Rgb
    if (busy) {
      // Una macchia di fogliame: dettaglio fine in tutto il fotogramma (il caso peggiore per aliasing e rumore del ricampionamento).
      val leaves = noise.fbm(px * 150.0, y * 150.0, 5)
      val clump = noise.fbm(px * 6.0 + 5.0, y * 6.0 + 9.0, 3)
      c = grass.scale(0.35 + 1.1 * leaves).mix(road.scale(0.6 + 0.6 * leaves), smoothstep(0.45, 0.6, clump) * 0.7)
    } else if (y < horizon) {
      val t = (y / horizon).pow(1.4)
      c = skyTop.mix(skyLow, t)
      val cloud = noise.fbm(px * 2.6 + 11.0, y * 5.5 + 3.0, 6)
      val k = smoothstep(0.50, 0.72, cloud)
      c = c.mix(Rgb(0.97, 0.97, 0.98), k * 0.85)
      // La catena di montagne.
      val ridge = horizon - 0.10 - 0.12 * noise.fbm(px * 1.7 + 40.0, 0.5, 6)
      if (y > ridge) {
        val tex = 0.75 + 0.5 * noise.fbm(px * 25.0, y * 25.0, 5)
        val snow = smoothstep(0.0, 0.03, ridge + 0.03 - y)
        c = rock.scale(tex).mix(Rgb(0.95, 0.95, 0.97), snow * 0.8)
        c = c.mix(skyLow, smoothstep(horizon - 0.06, horizon, y) * 0.5)
      }
    } else {
      val depth = (y - horizon) / (1 - horizon)
      val tex = 0.55 + 0.9 * noise.fbm(px * (30.0 + 80.0 * depth), y * (30.0 + 80.0 * depth), 7)
      c = grass.scale(tex)
      // Una strada diagonale.
      val d = abs((px - 0.6) * sin(roadAngle) - (y - horizon - roadOffset * 0.3) * cos(roadAngle))
      if (d < 0.05 + 0.1 * depth) c = road.scale(0.8 + 0.4 * noise.fbm(px * 90.0, y * 90.0, 4))
    }
    for (b in buildings) {
      if (x in b.x0..b.x1 && y in b.top..(horizon + 0.05)) {
        val u = (x - b.x0) / (b.x1 - b.x0)
        val v = (y - b.top) / (horizon + 0.05 - b.top)
        var wall = b.color.scale(0.85 + 0.3 * noise.fbm(px * 60.0, y * 60.0, 3))
        val cu = (u * b.cols) % 1.0
        val cv = (v * b.rows) % 1.0
        if (cu in 0.25..0.75 && cv in 0.2..0.7 && v < 0.85) {
          wall = Rgb(0.15, 0.2, 0.3).mix(Rgb(0.9, 0.85, 0.6), if (((u * b.cols).toInt() + (v * b.rows).toInt()) % 3 == 0) 0.9 else 0.0)
        }
        c = wall
      }
    }
    for (t in trees) {
      val dx = (x - t.x) / t.rx
      val dy = (y - t.y) / t.ry
      if (dx * dx + dy * dy < 1.0) {
        c = t.color.scale(0.5 + 1.0 * noise.fbm(px * 110.0, y * 110.0, 4))
      }
    }
    if (subject != null) {
      val hx = (x - subject.x) / 0.035
      val hy = (y - (subject.y - 0.13)) / 0.045
      val bx = abs(x - subject.x) / 0.05
      val by = (y - subject.y) / 0.13
      if (hx * hx + hy * hy < 1.0) c = Rgb(0.85, 0.65, 0.55)
      else if (bx < 1.0 && by in -1.0..1.0) c = jacket.scale(0.85 + 0.3 * noise.fbm(px * 70.0, y * 70.0, 3))
    }
    // Vignettatura leggera, come un obiettivo vero.
    val rx = x - 0.5
    val ry = y - 0.5
    val vignette = 1.0 - 0.18 * (rx * rx + ry * ry) * 4.0
    return Rgb(c.r * vignette * dark, c.g * vignette * dark, c.b * vignette * dark)
  }

  private fun smoothstep(a: Double, b: Double, x: Double): Double {
    val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
    return t * t * (3 - 2 * t)
  }

  companion object {
    const val ASPECT: Double = 4.0 / 3.0
  }
}

/** Come si inquadra la scena: ritaglio, rotazione, specchio, e di quanto la camera si e' mossa. */
internal data class Framing(
  /** Frazione della scena che entra nell'inquadratura (1,0 = tutta, 0,97 = ritaglio del 3%). */
  val zoom: Double = 1.0,
  val rotationDegrees: Double = 0.0,
  val mirrored: Boolean = false,
  val shiftX: Double = 0.0,
  val shiftY: Double = 0.0,
)

internal object SceneRenderer {

  /** Disegna [scene] in [width] x [height] con [framing], con [samples] x [samples] campioni per pixel (antialiasing). */
  fun render(scene: Scene, width: Int, height: Int, framing: Framing = Framing(), samples: Int = 2): Raster {
    val out = IntArray(width * height)
    val cosA = cos(framing.rotationDegrees * PI / 180.0)
    val sinA = sin(framing.rotationDegrees * PI / 180.0)
    val aspect = width.toDouble() / height
    IntStream.range(0, height).parallel().forEach { py ->
      for (px in 0 until width) {
        var r = 0.0
        var g = 0.0
        var b = 0.0
        for (sy in 0 until samples) {
          for (sx in 0 until samples) {
            var u = (px + (sx + 0.5) / samples) / width - 0.5
            val v = (py + (sy + 0.5) / samples) / height - 0.5
            if (framing.mirrored) u = -u
            // La rotazione e' rigida: si fa in unita' quadrate.
            val ux = u * aspect
            val rx = ux * cosA - v * sinA
            val ry = ux * sinA + v * cosA
            val x = 0.5 + rx / aspect * framing.zoom + framing.shiftX
            val y = 0.5 + ry * framing.zoom + framing.shiftY
            val c = scene.colorAt(x, y)
            r += c.r
            g += c.g
            b += c.b
          }
        }
        val n = (samples * samples).toDouble()
        out[py * width + px] = pack(r / n, g / n, b / n)
      }
    }
    return Raster(width, height, out)
  }

  /** Lo zoom con cui un raddrizzamento di [degrees] gradi non lascia bordi vuoti (ritaglia l'inquadratura). */
  fun straightenZoom(degrees: Double, aspect: Double = Scene.ASPECT): Double {
    val a = abs(degrees) * PI / 180.0
    return 1.0 / (cos(a) + max(aspect, 1.0 / aspect) * sin(a))
  }

  fun pack(r: Double, g: Double, b: Double): Int =
    (to8(r) shl 16) or (to8(g) shl 8) or to8(b)

  fun to8(v: Double): Int = (v * 255.0 + 0.5).toInt().coerceIn(0, 255)
}

/** Operazioni sui pixel: i degradi veri delle copie (ricompressione, riduzione, esposizione, filtri, rumore). */
internal object RasterOps {

  fun map(src: Raster, f: (Double, Double, Double) -> Triple<Double, Double, Double>): Raster {
    val out = IntArray(src.width * src.height)
    for (i in out.indices) {
      val p = src.pixel(i)
      val (r, g, b) = f(((p shr 16) and 0xFF) / 255.0, ((p shr 8) and 0xFF) / 255.0, (p and 0xFF) / 255.0)
      out[i] = SceneRenderer.pack(r, g, b)
    }
    return Raster(src.width, src.height, out)
  }

  private fun toLinear(v: Double) = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)

  private fun toGamma(v: Double) = if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1 / 2.4) - 0.055

  /** Esposizione di [ev] stop, in luce lineare (come fa il sensore), poi di nuovo codificata. */
  fun exposure(src: Raster, ev: Double): Raster {
    val k = 2.0.pow(ev)
    return map(src) { r, g, b -> Triple(toGamma(toLinear(r) * k), toGamma(toLinear(g) * k), toGamma(toLinear(b) * k)) }
  }

  /** Bianco e nero (luminanza Rec. 709 in luce lineare). */
  fun grayscale(src: Raster): Raster = map(src) { r, g, b ->
    val y = toGamma(0.2126 * toLinear(r) + 0.7152 * toLinear(g) + 0.0722 * toLinear(b))
    Triple(y, y, y)
  }

  /** Un filtro di colore (moltiplica i canali, per esempio caldo = (1.1, 1.0, 0.85)). */
  fun tint(src: Raster, kr: Double, kg: Double, kb: Double): Raster = map(src) { r, g, b -> Triple(r * kr, g * kg, b * kb) }

  /** Riduce la saturazione di [amount] (0 = niente, 1 = grigio). */
  fun desaturate(src: Raster, amount: Double): Raster = map(src) { r, g, b ->
    val y = 0.299 * r + 0.587 * g + 0.114 * b
    Triple(r + (y - r) * amount, g + (y - g) * amount, b + (y - b) * amount)
  }

  /** Rumore gaussiano del sensore, con deviazione [sigma] (0..1). */
  fun noise(src: Raster, sigma: Double, seed: Long): Raster {
    val rnd = Random(seed)
    return map(src) { r, g, b ->
      Triple(r + rnd.nextGaussian() * sigma, g + rnd.nextGaussian() * sigma, b + rnd.nextGaussian() * sigma)
    }
  }

  /** Sfocatura gaussiana separabile. */
  fun blur(src: Raster, sigma: Double): Raster {
    val radius = (sigma * 3).toInt().coerceAtLeast(1)
    val kernel = DoubleArray(radius * 2 + 1) { Math.exp(-((it - radius) * (it - radius)) / (2 * sigma * sigma)) }
    val sum = kernel.sum()
    for (i in kernel.indices) kernel[i] /= sum
    val w = src.width
    val h = src.height
    val tmp = DoubleArray(w * h * 3)
    for (y in 0 until h) for (x in 0 until w) {
      var r = 0.0
      var g = 0.0
      var b = 0.0
      for (k in -radius..radius) {
        val p = src.at((x + k).coerceIn(0, w - 1), y)
        val wk = kernel[k + radius]
        r += wk * ((p shr 16) and 0xFF)
        g += wk * ((p shr 8) and 0xFF)
        b += wk * (p and 0xFF)
      }
      val o = (y * w + x) * 3
      tmp[o] = r
      tmp[o + 1] = g
      tmp[o + 2] = b
    }
    val out = IntArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
      var r = 0.0
      var g = 0.0
      var b = 0.0
      for (k in -radius..radius) {
        val o = ((y + k).coerceIn(0, h - 1) * w + x) * 3
        val wk = kernel[k + radius]
        r += wk * tmp[o]
        g += wk * tmp[o + 1]
        b += wk * tmp[o + 2]
      }
      out[y * w + x] = SceneRenderer.pack(r / 255.0, g / 255.0, b / 255.0)
    }
    return Raster(w, h, out)
  }

  /** Riduzione bilineare **senza** prefiltro (la peggiore: il ricampionamento ingenuo che crea aliasing). */
  fun resizeBilinear(src: Raster, width: Int, height: Int): Raster {
    val out = IntArray(width * height)
    for (y in 0 until height) {
      val sy = ((y + 0.5) * src.height / height - 0.5).coerceIn(0.0, src.height - 1.0)
      val y0 = floor(sy).toInt()
      val y1 = min(y0 + 1, src.height - 1)
      val fy = sy - y0
      for (x in 0 until width) {
        val sx = ((x + 0.5) * src.width / width - 0.5).coerceIn(0.0, src.width - 1.0)
        val x0 = floor(sx).toInt()
        val x1 = min(x0 + 1, src.width - 1)
        val fx = sx - x0
        var packed = 0
        for (shift in intArrayOf(16, 8, 0)) {
          fun ch(xx: Int, yy: Int) = ((src.at(xx, yy) shr shift) and 0xFF).toDouble()
          val v = (ch(x0, y0) * (1 - fx) + ch(x1, y0) * fx) * (1 - fy) + (ch(x0, y1) * (1 - fx) + ch(x1, y1) * fx) * fy
          packed = packed or (v.toInt().coerceIn(0, 255) shl shift)
        }
        out[y * width + x] = packed
      }
    }
    return Raster(width, height, out)
  }

  /** Riduzione a medie d'area (quella buona: il filtro di quasi tutte le app). */
  fun resizeArea(src: Raster, width: Int, height: Int): Raster {
    val out = IntArray(width * height)
    val xs = DoubleArray(width + 1) { it.toDouble() * src.width / width }
    val ys = DoubleArray(height + 1) { it.toDouble() * src.height / height }
    for (y in 0 until height) {
      for (x in 0 until width) {
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var total = 0.0
        for (yy in floor(ys[y]).toInt() until min(src.height, kotlin.math.ceil(ys[y + 1]).toInt())) {
          val wy = min(yy + 1.0, ys[y + 1]) - max(yy.toDouble(), ys[y])
          for (xx in floor(xs[x]).toInt() until min(src.width, kotlin.math.ceil(xs[x + 1]).toInt())) {
            val wx = min(xx + 1.0, xs[x + 1]) - max(xx.toDouble(), xs[x])
            val wgt = wx * wy
            val p = src.at(xx, yy)
            r += wgt * ((p shr 16) and 0xFF)
            g += wgt * ((p shr 8) and 0xFF)
            b += wgt * (p and 0xFF)
            total += wgt
          }
        }
        out[y * width + x] = SceneRenderer.pack(r / total / 255.0, g / total / 255.0, b / total / 255.0)
      }
    }
    return Raster(width, height, out)
  }

  /** Una distanza media assoluta fra due immagini di uguale misura, per i controlli dei fixture (0..255). */
  fun meanAbsDiff(a: Raster, b: Raster): Double {
    require(a.width == b.width && a.height == b.height)
    var sum = 0.0
    for (i in 0 until a.width * a.height) {
      val p = a.pixel(i)
      val q = b.pixel(i)
      sum += abs(((p shr 16) and 0xFF) - ((q shr 16) and 0xFF)) + abs(((p shr 8) and 0xFF) - ((q shr 8) and 0xFF)) + abs((p and 0xFF) - (q and 0xFF))
    }
    return sum / (3.0 * a.width * a.height)
  }

  fun lumaStd(src: Raster): Double {
    var s = 0.0
    var s2 = 0.0
    val n = src.width * src.height
    for (i in 0 until n) {
      val p = src.pixel(i)
      val y = 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
      s += y
      s2 += y * y
    }
    return sqrt((s2 / n - (s / n) * (s / n)).coerceAtLeast(0.0))
  }
}
