package dev.pampa.mycloudgallery.feature.viewer.cutout

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// IL DISEGNO DEL SOGGETTO (docs/fase6-progetto.md D44, §2.10): dalla maschera (grande come l'immagine, fino a 4096 px) alle tre figure che il
// viewer disegna sopra la foto, tutte a risoluzione di schermo (al piu' [CutoutArtMath.DISPLAY_MAX_SIDE] px):
//   - la **maschera** (ALPHA_8): il soggetto ritagliato dalla bitmap che si vede;
//   - il **bagliore** ("il soggetto si solleva con un contorno luminoso"): un alone morbido intorno al soggetto, che si accende e si spegne;
//   - il **contorno** (interruttore "Contorno", l'adesivo con il bordo bianco): lo stesso bordo che finira' nel PNG, con lo stesso raggio in
//     proporzione al soggetto (circa l'1,2% del suo lato lungo, almeno 3 px di immagine), cosi' quello che si vede e' quello che si copia.
// Bianco con l'alfa (ARGB_8888, 4 MB a 1024 px): la tinta e il taglio funzionano su ogni renderer. Le figure non dipendono dallo zoom: scalano con la foto. La matematica e' pura (si prova sulla JVM); le bitmap nascono in [CutoutArt.build].

/** La maschera a risoluzione di schermo: [alpha] da 0 a 255, [width]x[height]. */
internal class DisplayMask(val width: Int, val height: Int, val alpha: ByteArray) {
  init {
    require(width > 0 && height > 0 && alpha.size == width * height) { "Maschera non valida." }
  }
}

internal object CutoutArtMath {

  /** Il lato lungo massimo delle figure di schermo. */
  const val DISPLAY_MAX_SIDE: Int = 1024

  /** Come nel PNG (`CutoutRender`): il raggio del contorno e' questa frazione del lato lungo del soggetto... */
  const val OUTLINE_FRACTION: Float = 0.012f

  /** ... ma almeno tanti pixel dell'immagine intera. */
  const val OUTLINE_MIN_RADIUS: Float = 3f

  /** Il bagliore e' piu' largo del contorno di questo fattore. */
  const val HALO_FACTOR: Float = 3.5f

  /** La maschera ridotta con la media sull'area: un bordo antialias resta tale. */
  fun display(mask: ViewerMask, maxSide: Int = DISPLAY_MAX_SIDE): DisplayMask {
    val step = max(1, ceil(max(mask.width, mask.height).toDouble() / maxSide).toInt())
    if (step == 1) return DisplayMask(mask.width, mask.height, mask.alpha())
    val w = (mask.width + step - 1) / step
    val h = (mask.height + step - 1) / step
    val source = mask.raw()
    val out = ByteArray(w * h)
    for (y in 0 until h) {
      val y0 = y * step
      val y1 = min(mask.height, y0 + step)
      for (x in 0 until w) {
        val x0 = x * step
        val x1 = min(mask.width, x0 + step)
        var sum = 0
        for (yy in y0 until y1) {
          val row = yy * mask.width
          for (xx in x0 until x1) sum += source[row + xx].toInt() and 0xff
        }
        out[y * w + x] = (sum / ((y1 - y0) * (x1 - x0))).toByte()
      }
    }
    return DisplayMask(w, h, out)
  }

  /** Il raggio del contorno in pixel della maschera a schermo, dal rettangolo del soggetto ([bounds], in frazioni dell'immagine). */
  fun outlineRadius(mask: ViewerMask, bounds: ViewerRect, display: DisplayMask): Float {
    val boxW = (bounds.right - bounds.left) * mask.width
    val boxH = (bounds.bottom - bounds.top) * mask.height
    val full = max(OUTLINE_MIN_RADIUS, OUTLINE_FRACTION * max(boxW, boxH))
    // Da pixel dell'immagine a pixel della maschera di schermo; mai meno di un pixel e mezzo, o a schermo non si vedrebbe.
    return max(1.5f, full * display.width / mask.width)
  }

  /** La distanza (in pixel) di ogni punto dal soggetto piu' vicino (alfa >= 128): 0 dentro il soggetto. Distanza euclidea esatta. */
  fun distances(display: DisplayMask): FloatArray {
    val w = display.width
    val h = display.height
    val squared = FloatArray(w * h) { if ((display.alpha[it].toInt() and 0xff) >= 128) 0f else INF }
    val n = max(w, h)
    val line = FloatArray(n)
    val out = FloatArray(n)
    val v = IntArray(n)
    val z = FloatArray(n + 1)
    for (x in 0 until w) {
      for (y in 0 until h) line[y] = squared[y * w + x]
      transform1d(line, out, h, v, z)
      for (y in 0 until h) squared[y * w + x] = out[y]
    }
    for (y in 0 until h) {
      for (x in 0 until w) line[x] = squared[y * w + x]
      transform1d(line, out, w, v, z)
      for (x in 0 until w) squared[y * w + x] = out[x]
    }
    for (i in squared.indices) squared[i] = sqrt(squared[i])
    return squared
  }

  /** Il contorno: opaco dove la distanza e' al piu' [radius], con un bordo antialias di un pixel (come `CutoutRender`). */
  fun ring(distance: FloatArray, radius: Float): ByteArray = ByteArray(distance.size) { i ->
    val c = radius + 0.5f - distance[i]
    if (c <= 0f) 0 else if (c >= 1f) 0xff.toByte() else (c * 255f + 0.5f).toInt().toByte()
  }

  /** Il bagliore: da pieno sul soggetto a zero a [radius], con la caduta di un'ombra (il quadrato della distanza residua). */
  fun halo(distance: FloatArray, radius: Float): ByteArray = ByteArray(distance.size) { i ->
    val t = 1f - distance[i] / radius
    if (t <= 0f) 0 else ((t * t).coerceAtMost(1f) * 255f + 0.5f).toInt().toByte()
  }

  private const val INF = 1e12f

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

/**
 * Le tre figure del soggetto pronte da disegnare (bianco con l'alfa, [width]x[height] a risoluzione di schermo, stessa proporzione dell'immagine).
 * Si costruiscono fuori dal thread principale ([build]) e si rilasciano con [release].
 */
internal class CutoutArt private constructor(
  val width: Int,
  val height: Int,
  private val maskBitmap: Bitmap,
  private val haloBitmap: Bitmap,
  private val outlineBitmap: Bitmap,
) {
  val mask: ImageBitmap = maskBitmap.asImageBitmap()
  val halo: ImageBitmap = haloBitmap.asImageBitmap()
  val outline: ImageBitmap = outlineBitmap.asImageBitmap()

  fun release() {
    maskBitmap.recycle()
    haloBitmap.recycle()
    outlineBitmap.recycle()
  }

  companion object {
    fun build(cutout: ViewerCutout.Subject): CutoutArt {
      val display = CutoutArtMath.display(cutout.mask)
      val radius = CutoutArtMath.outlineRadius(cutout.mask, cutout.bounds, display)
      val distance = CutoutArtMath.distances(display)
      return CutoutArt(
        display.width,
        display.height,
        white(display.width, display.height, display.alpha),
        white(display.width, display.height, CutoutArtMath.halo(distance, radius * CutoutArtMath.HALO_FACTOR)),
        white(display.width, display.height, CutoutArtMath.ring(distance, radius)),
      )
    }

    /**
     * Bianco con l'alfa dato (ARGB_8888): `DstIn` e la tinta funzionano su qualunque renderer, senza dipendere dal trattamento delle
     * bitmap ALPHA_8 (che alcuni renderer disegnano come nere e opache).
     */
    private fun white(w: Int, h: Int, bytes: ByteArray): Bitmap =
      Bitmap.createBitmap(IntArray(w * h) { ((bytes[it].toInt() and 0xff) shl 24) or 0x00FFFFFF }, w, h, Bitmap.Config.ARGB_8888)
  }
}
