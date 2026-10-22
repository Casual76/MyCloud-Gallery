package dev.pampa.mycloudgallery.core.ai.cutout

import dev.pampa.mycloudgallery.core.ai.contract.CutoutResult
import dev.pampa.mycloudgallery.core.ai.contract.PixelBox
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// LA POST-ELABORAZIONE DELLA MASCHERA (docs/fase6-progetto.md D44, §2.10): dalla mappa di probabilita' del modello (512 o 768 px) alla
// maschera alfa alla risoluzione dell'immagine. Codice puro (nessuna classe di Android, nessuna libreria nativa): si prova sulla JVM
// con mappe scritte a mano.
//
//   1. [SubjectRules]: c'e' un soggetto chiaro? Il punto toccato dentro la maschera, la componente connessa del punto (e quelle non
//      trascurabili), la copertura fra l'1% e il 90% (E-V10), la confidenza. Toglie le macchie staccate.
//   2. [MaskRefiner]: filtro guidato (veloce: i coefficienti su una griglia di al piu' 1024 px, applicati alla guida a piena
//      risoluzione), soglia morbida e sfumatura di 1-2 px; il filtro lavora solo vicino al bordo, il resto e' 0 o 255.

/** La probabilita' (0..1) che ogni punto dell'immagine intera sia il soggetto, [width]x[height] (la risoluzione del modello). */
internal class ProbabilityMap(val width: Int, val height: Int, val values: FloatArray) {
  init {
    require(width > 0 && height > 0 && values.size == width * height) { "Mappa non valida." }
  }

  /** Il valore bilineare in un punto normalizzato. */
  fun at(x: Float, y: Float): Float {
    val fx = (x * width - 0.5f).coerceIn(0f, width - 1f)
    val fy = (y * height - 0.5f).coerceIn(0f, height - 1f)
    val x0 = fx.toInt()
    val y0 = fy.toInt()
    val x1 = min(x0 + 1, width - 1)
    val y1 = min(y0 + 1, height - 1)
    val wx = fx - x0
    val wy = fy - y0
    val top = values[y0 * width + x0] * (1 - wx) + values[y0 * width + x1] * wx
    val bottom = values[y1 * width + x0] * (1 - wx) + values[y1 * width + x1] * wx
    return top * (1 - wy) + bottom * wy
  }
}

/** Le regole che decidono se c'e' un soggetto chiaro (E-V10) e puliscono la mappa dalle macchie staccate. */
internal object SubjectRules {

  /** La probabilita' sopra la quale un punto e' soggetto. */
  const val THRESHOLD: Float = 0.5f

  /** Quanto puo' distare il dito dal soggetto (in frazione del lato piu' corto) perche' lo si consideri toccato. */
  const val TOUCH_TOLERANCE: Float = 0.012f

  /** Una componente staccata resta solo se e' almeno questa parte di quella toccata. */
  const val KEEP_RATIO: Float = 0.02f

  /** La probabilita' media dei punti del soggetto sotto la quale il modello non e' sicuro (E-V10, "confidenza bassa"). */
  const val MIN_CONFIDENCE: Float = 0.72f

  /**
   * Il contrasto minimo del bordo (mediana sul contorno, [ContourContrast]): MagicTouch, toccato su un cielo, un muro o uno sfondo
   * sfocato, disegna comunque una macchia sicura di se' intorno al dito; un oggetto vero ha un bordo che si vede. Una macchia il cui
   * contorno non si distingue dal resto dell'immagine non e' un soggetto (E-V10).
   */
  const val MIN_CONTRAST: Float = 0.06f

  /** Perche' non c'e' un soggetto chiaro (E-V10). */
  enum class Reason { POINT, COVERAGE, CONFIDENCE, CONTRAST }

  /** Cio' che si e' misurato (le misure di P13 le scrivono nel log delle prove, mai in quello dell'app). */
  class Stats(val coverage: Float, val confidence: Float, val contrast: Float)

  sealed interface Verdict {
    /** Il soggetto toccato: la mappa ripulita e le misure alla risoluzione del modello. */
    class Subject(val map: ProbabilityMap, val stats: Stats) : Verdict {
      val coverage: Float get() = stats.coverage
      val confidence: Float get() = stats.confidence
    }

    class None(val reason: Reason, val stats: Stats? = null) : Verdict
  }

  /**
   * @param rgb i colori dell'immagine alla risoluzione della mappa (R, G, B in 0..255, `width * height * 3` valori): servono a
   *   vedere se il bordo della maschera e' un bordo dell'immagine ([ContourContrast]). Null = non si controlla (prove della sola mappa).
   */
  fun decide(map: ProbabilityMap, x: Float, y: Float, rgb: FloatArray? = null): Verdict {
    val w = map.width
    val h = map.height
    val v = map.values
    val seed = seedNear(map, x, y) ?: return Verdict.None(Reason.POINT)
    // Componenti connesse (4 vicini) dei punti sopra la soglia.
    val label = IntArray(w * h)
    val areas = ArrayList<Int>()
    val stack = IntArray(w * h)
    var next = 0
    fun flood(start: Int): Int {
      next++
      var top = 0
      stack[top++] = start
      label[start] = next
      var area = 0
      while (top > 0) {
        val p = stack[--top]
        area++
        val px = p % w
        val py = p / w
        if (px > 0 && label[p - 1] == 0 && v[p - 1] >= THRESHOLD) { label[p - 1] = next; stack[top++] = p - 1 }
        if (px < w - 1 && label[p + 1] == 0 && v[p + 1] >= THRESHOLD) { label[p + 1] = next; stack[top++] = p + 1 }
        if (py > 0 && label[p - w] == 0 && v[p - w] >= THRESHOLD) { label[p - w] = next; stack[top++] = p - w }
        if (py < h - 1 && label[p + w] == 0 && v[p + w] >= THRESHOLD) { label[p + w] = next; stack[top++] = p + w }
      }
      areas += area
      return area
    }
    val mainArea = flood(seed)
    val mainLabel = next
    for (p in v.indices) if (label[p] == 0 && v[p] >= THRESHOLD) flood(p)
    val keep = BooleanArray(areas.size + 1)
    var keptArea = 0
    for (l in 1..areas.size) {
      if (l == mainLabel || areas[l - 1] >= mainArea * KEEP_RATIO) {
        keep[l] = true
        keptArea += areas[l - 1]
      }
    }
    val coverage = keptArea.toFloat() / (w * h)
    if (coverage < CutoutResult.MIN_COVERAGE || coverage > CutoutResult.MAX_COVERAGE) return Verdict.None(Reason.COVERAGE, Stats(coverage, 0f, 0f))
    var sum = 0.0
    val cleaned = FloatArray(w * h)
    for (p in v.indices) {
      val l = label[p]
      if (l != 0 && keep[l]) {
        cleaned[p] = v[p]
        sum += v[p]
      } else {
        // Fuori dal soggetto: nessun punto sopra la soglia (le macchie staccate spariscono); sotto la soglia resta com'e'.
        cleaned[p] = if (v[p] >= THRESHOLD) 0f else v[p]
      }
    }
    val confidence = (sum / keptArea).toFloat()
    if (confidence < MIN_CONFIDENCE) return Verdict.None(Reason.CONFIDENCE, Stats(coverage, confidence, 0f))
    val contrast = if (rgb == null) Float.MAX_VALUE else ContourContrast.median(cleaned, w, h, rgb)
    val stats = Stats(coverage, confidence, contrast)
    if (contrast < MIN_CONTRAST) return Verdict.None(Reason.CONTRAST, stats)
    return Verdict.Subject(ProbabilityMap(w, h, cleaned), stats)
  }

  /** Il punto del soggetto piu' vicino al dito (dentro la tolleranza), o null se il dito non e' su un soggetto. */
  private fun seedNear(map: ProbabilityMap, x: Float, y: Float): Int? {
    val w = map.width
    val h = map.height
    val cx = (x * w).toInt().coerceIn(0, w - 1)
    val cy = (y * h).toInt().coerceIn(0, h - 1)
    if (map.values[cy * w + cx] >= THRESHOLD) return cy * w + cx
    val reach = max(1, (TOUCH_TOLERANCE * min(w, h)).roundToInt())
    var best: Int? = null
    var bestDistance = Int.MAX_VALUE
    for (yy in max(0, cy - reach)..min(h - 1, cy + reach)) {
      for (xx in max(0, cx - reach)..min(w - 1, cx + reach)) {
        if (map.values[yy * w + xx] < THRESHOLD) continue
        val d = (xx - cx) * (xx - cx) + (yy - cy) * (yy - cy)
        if (d < bestDistance) {
          bestDistance = d
          best = yy * w + xx
        }
      }
    }
    return best
  }
}

/**
 * Il contrasto lungo il contorno della maschera: per ogni punto del bordo si confronta il colore appena DENTRO (a 3 px, mediato su una
 * finestra 5x5) con quello appena FUORI (a 4 px) lungo la normale, e si prende la MEDIANA delle differenze (distanza dei colori RGB
 * 0..1, al massimo 1,73). La mediana dice "la maggior parte del bordo si vede": un'ombra sotto l'oggetto o un tratto di bordo dello
 * stesso colore dello sfondo non la fanno scendere; un contorno disegnato dal modello nel vuoto (cielo, muro, sfocato) si': il colore
 * dentro e fuori e' lo stesso. Puro.
 */
internal object ContourContrast {

  private const val BLUR_RADIUS = 2
  private const val INSIDE = 3
  private const val OUTSIDE = 4

  fun median(p: FloatArray, w: Int, h: Int, rgb: FloatArray): Float {
    val planes = Array(3) { c -> FloatArray(w * h) { rgb[it * 3 + c] * (1f / 255f) } }
    val blurred = Array(3) { GuidedFilter.box(planes[it], w, h, BLUR_RADIUS) }
    val deltas = ArrayList<Float>()
    for (y in 0 until h) {
      for (x in 0 until w) {
        val i = y * w + x
        if (p[i] < SubjectRules.THRESHOLD) continue
        // La normale verso l'esterno: la somma delle direzioni dei vicini fuori dalla maschera.
        var nx = 0
        var ny = 0
        if (x == 0 || p[i - 1] < SubjectRules.THRESHOLD) nx -= 1
        if (x == w - 1 || p[i + 1] < SubjectRules.THRESHOLD) nx += 1
        if (y == 0 || p[i - w] < SubjectRules.THRESHOLD) ny -= 1
        if (y == h - 1 || p[i + w] < SubjectRules.THRESHOLD) ny += 1
        if (nx == 0 && ny == 0) continue
        // Un bordo che coincide con quello dell'immagine non ha un "fuori" da confrontare: non conta.
        val onFrame = (x == 0 && nx < 0) || (x == w - 1 && nx > 0) || (y == 0 && ny < 0) || (y == h - 1 && ny > 0)
        if (onFrame) continue
        val len = Math.sqrt((nx * nx + ny * ny).toDouble()).toFloat()
        val ux = nx / len
        val uy = ny / len
        val ix = (x - INSIDE * ux).toInt().coerceIn(0, w - 1)
        val iy = (y - INSIDE * uy).toInt().coerceIn(0, h - 1)
        val ox = (x + OUTSIDE * ux).toInt().coerceIn(0, w - 1)
        val oy = (y + OUTSIDE * uy).toInt().coerceIn(0, h - 1)
        val a = iy * w + ix
        val b = oy * w + ox
        val dr = blurred[0][a] - blurred[0][b]
        val dg = blurred[1][a] - blurred[1][b]
        val db = blurred[2][a] - blurred[2][b]
        deltas += Math.sqrt((dr * dr + dg * dg + db * db).toDouble()).toFloat()
      }
    }
    // Un soggetto che riempie la cornice da ogni lato non ha un contorno da misurare: la copertura (<= 90%) lo ha gia' giudicato.
    if (deltas.isEmpty()) return Float.MAX_VALUE
    deltas.sort()
    return deltas[deltas.size / 2]
  }
}

/** Dalla mappa del modello alla maschera alla risoluzione dell'immagine. */
internal object MaskRefiner {

  /** Il lato lungo massimo della griglia su cui si calcola il filtro guidato. */
  const val GUIDE_MAX_SIDE: Int = 1024

  /** Regolarizzazione del filtro (varianza della luminanza 0..1). */
  const val EPS: Float = 1e-3f

  /** La mezza larghezza, in probabilita', della transizione fra 0 e 255: la sfumatura di 1-2 px (D44). */
  const val EDGE_HALF_WIDTH: Float = 0.18f

  /** L'esito: l'alfa [width]x[height], il rettangolo del soggetto (alfa >= 128) e la sua copertura. */
  class Refined(val alpha: ByteArray, val bounds: PixelBox?, val coverage: Float)

  /**
   * @param pixels i pixel ARGB dell'immagine ([width]x[height]), la guida.
   * @param map la mappa gia' ripulita da [SubjectRules.decide].
   */
  fun refine(pixels: IntArray, width: Int, height: Int, map: ProbabilityMap): Refined {
    val step = max(1, ceil(max(width, height).toDouble() / GUIDE_MAX_SIDE).toInt())
    val gw = (width + step - 1) / step
    val gh = (height + step - 1) / step
    val guide = Resampler.lumaBlocks(pixels, width, height, step)
    val coarse = Resampler.resizeMap(map.values, map.width, map.height, gw, gh)
    // Raggio della finestra: due pixel del modello, alla risoluzione della griglia.
    val radius = max(2, (2f * max(gw, gh) / max(map.width, map.height)).roundToInt()).coerceAtMost(max(1, (min(gw, gh) - 1) / 2))
    val (a, b) = GuidedFilter.coefficients(guide, coarse, gw, gh, radius, EPS)

    // Il filtro serve solo dove la probabilita' non e' decisa, e nei dintorni (la finestra): il resto e' 0 o 255.
    val uncertain = FloatArray(gw * gh) { if (abs(coarse[it] - 0.5f) < 0.45f) 1f else 0f }
    val near = GuidedFilter.box(uncertain, gw, gh, radius + 1)

    val alpha = ByteArray(width * height)
    val gx0 = IntArray(width)
    val gx1 = IntArray(width)
    val wx = FloatArray(width)
    for (x in 0 until width) {
      val f = ((x + 0.5f) / step - 0.5f).coerceIn(0f, gw - 1f)
      gx0[x] = f.toInt()
      gx1[x] = min(gx0[x] + 1, gw - 1)
      wx[x] = f - gx0[x]
    }
    val scale = 0.5f / EDGE_HALF_WIDTH
    var left = width
    var top = height
    var right = -1
    var bottom = -1
    var covered = 0L
    for (y in 0 until height) {
      val fy = ((y + 0.5f) / step - 0.5f).coerceIn(0f, gh - 1f)
      val gy0 = fy.toInt()
      val gy1 = min(gy0 + 1, gh - 1)
      val wy = fy - gy0
      val row0 = gy0 * gw
      val row1 = gy1 * gw
      val rowPixels = y * width
      for (x in 0 until width) {
        val i0 = row0 + gx0[x]
        val value: Int
        if (near[i0] <= 1e-4f) {
          value = if (coarse[i0] >= 0.5f) 255 else 0
        } else {
          val i1 = row0 + gx1[x]
          val i2 = row1 + gx0[x]
          val i3 = row1 + gx1[x]
          val w = wx[x]
          val aa = (a[i0] * (1 - w) + a[i1] * w) * (1 - wy) + (a[i2] * (1 - w) + a[i3] * w) * wy
          val bb = (b[i0] * (1 - w) + b[i1] * w) * (1 - wy) + (b[i2] * (1 - w) + b[i3] * w) * wy
          val q = aa * Resampler.luma(pixels[rowPixels + x]) + bb
          val t = (q - 0.5f) * scale + 0.5f
          value = if (t <= 0f) 0 else if (t >= 1f) 255 else (t * 255f + 0.5f).toInt()
        }
        if (value != 0) {
          alpha[rowPixels + x] = value.toByte()
          if (value >= 128) {
            covered++
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            bottom = y
          }
        }
      }
    }
    val bounds = if (right >= left && bottom >= top) PixelBox(left, top, right + 1, bottom + 1) else null
    return Refined(alpha, bounds, covered.toFloat() / (width.toLong() * height))
  }
}
