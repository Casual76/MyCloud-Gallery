package dev.pampa.mycloudgallery.core.ai.cutout

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// LE SESSIONI DEL MODELLO (docs/fase6-progetto.md D44): una sessione e' MagicTouch caricato e pronto. Questo file e' puro: tiene
// l'interfaccia ([MaskSession]), la taglia del quadro di ingresso ([ModelFrame]) e la mappa del punto toccato ([PointMap]). La sola
// classe che nomina LiteRT e' `LiteRtSessions.kt`; le prove JVM usano sessioni finte.

/**
 * L'immagine pronta per il modello: [side]x[side] pixel RGB in 0..255 (virgola mobile), con l'immagine nel rettangolo
 * ([contentLeft], [contentTop], [contentWidth], [contentHeight]) - tutto il quadro se senza bordi (il modello deforma, `stretch`) o
 * al centro con bordi grigi (`letterbox`, per le proporzioni estreme).
 */
internal class ModelFrame(
  val side: Int,
  val rgb: FloatArray,
  val contentLeft: Int,
  val contentTop: Int,
  val contentWidth: Int,
  val contentHeight: Int,
) {
  init {
    require(rgb.size == side * side * 3) { "Quadro non valido." }
  }

  /** Un punto normalizzato sull'immagine, nello spazio normalizzato del quadro. */
  fun frameX(x: Float): Float = (contentLeft + x * contentWidth) / side

  fun frameY(y: Float): Float = (contentTop + y * contentHeight) / side

  companion object {
    /** Il grigio dei bordi del letterbox (il valore medio dell'ingresso). */
    const val PAD: Float = 128f
  }
}

/** Come si prepara il quadro. */
internal object Framing {

  /** Sopra questa proporzione (lato lungo / lato corto) il modello non deforma piu': bordi. */
  const val LETTERBOX_ABOVE: Float = 1.9f

  fun frame(pixels: IntArray, width: Int, height: Int, side: Int, letterbox: Boolean): ModelFrame {
    if (!letterbox) {
      return ModelFrame(side, Resampler.resize(pixels, width, height, side, side), 0, 0, side, side)
    }
    val scale = side.toDouble() / max(width, height)
    val cw = max(1, (width * scale).roundToInt()).coerceAtMost(side)
    val ch = max(1, (height * scale).roundToInt()).coerceAtMost(side)
    val content = Resampler.resize(pixels, width, height, cw, ch)
    val left = (side - cw) / 2
    val top = (side - ch) / 2
    val rgb = FloatArray(side * side * 3) { ModelFrame.PAD }
    for (y in 0 until ch) {
      System.arraycopy(content, y * cw * 3, rgb, ((top + y) * side + left) * 3, cw * 3)
    }
    return ModelFrame(side, rgb, left, top, cw, ch)
  }

  /** La proporzione (lato lungo / lato corto) della finestra intorno al dito per le immagini molto allungate. */
  const val WINDOW_ASPECT: Float = 1.5f

  /** Una finestra dell'immagine, in pixel. */
  class Window(val left: Int, val top: Int, val width: Int, val height: Int)

  /**
   * Per un'immagine molto allungata (un panorama: sopra [LETTERBOX_ABOVE]) la finestra di proporzione [WINDOW_ASPECT] centrata sul punto
   * toccato (tenuta dentro l'immagine); null per le proporzioni normali e quando l'impostazione sceglie un adattamento fisso. Un panorama
   * da 4096x572 ridotto per intero a 512 pixel dava un quadro di 512x71: la finestra (858x572) arriva al modello con sei volte i dettagli.
   * Un soggetto piu' largo della finestra viene tagliato: un soggetto largo piu' di tre volte la foto non e' un soggetto.
   */
  fun window(width: Int, height: Int, x: Float, y: Float, tuning: MagicTouchTuning): Window? {
    if (tuning.fit != MagicTouchTuning.Fit.AUTO || max(width, height).toFloat() / min(width, height) <= LETTERBOX_ABOVE) return null
    return if (width > height) {
      val w = min(width, (height * WINDOW_ASPECT).roundToInt())
      Window((x * width - w / 2f).roundToInt().coerceIn(0, width - w), 0, w, height)
    } else {
      val h = min(height, (width * WINDOW_ASPECT).roundToInt())
      Window(0, (y * height - h / 2f).roundToInt().coerceIn(0, height - h), width, h)
    }
  }

  /** Letterbox solo per le proporzioni estreme (panorami, schermate lunghe): altrimenti il modello vedrebbe un'immagine schiacciata. */
  fun useLetterbox(width: Int, height: Int, tuning: MagicTouchTuning): Boolean =
    when (tuning.fit) {
      MagicTouchTuning.Fit.STRETCH -> false
      MagicTouchTuning.Fit.LETTERBOX -> true
      MagicTouchTuning.Fit.AUTO -> max(width, height).toFloat() / min(width, height) > LETTERBOX_ABOVE
    }
}

/** La manopola del punto toccato e dell'ingresso: i valori scelti con le misure (docs/decisioni.md, P13) e i modi per provarne altri. */
internal data class MagicTouchTuning(
  val fit: Fit = Fit.AUTO,
  val norm: Norm = Norm.UNIT,
  val shape: Shape = Shape.DISC,
  /** Raggio del disco o sigma della gaussiana, in frazione del lato del quadro. */
  val radius: Float = 0.015f,
  /** Il valore del punto (il massimo della mappa). */
  val peak: Float = 1f,
  /** Dove si mette il punto nell'ingresso della v2 (decoder, 3 canali). */
  val v2Prompt: V2Prompt = V2Prompt.CHANNEL0,
) {
  enum class Fit { STRETCH, LETTERBOX, AUTO }

  enum class Norm { UNIT, SIGNED, RAW }

  enum class Shape { DISC, GAUSS }

  enum class V2Prompt { CHANNEL0, CHANNEL1, CHANNEL2, ALL, NONE, IMAGE }

  fun scale(v: Float): Float = when (norm) {
    Norm.UNIT -> v / 255f
    Norm.SIGNED -> v / 127.5f - 1f
    Norm.RAW -> v
  }

  companion object {
    val DEFAULT = MagicTouchTuning()

    /** Da "fit=stretch,norm=unit,shape=gauss,r=0.04,peak=1,v2=all" (o con il punto e virgola) (le prove strumentate). Chiavi sconosciute ignorate. */
    fun parse(text: String): MagicTouchTuning {
      var t = DEFAULT
      for (part in text.split(';', ',')) {
        val kv = part.split('=', limit = 2)
        if (kv.size != 2) continue
        val v = kv[1].trim()
        t = when (kv[0].trim()) {
          "fit" -> t.copy(fit = Fit.entries.firstOrNull { it.name.equals(v, true) } ?: t.fit)
          "norm" -> t.copy(norm = Norm.entries.firstOrNull { it.name.equals(v, true) } ?: t.norm)
          "shape" -> t.copy(shape = Shape.entries.firstOrNull { it.name.equals(v, true) } ?: t.shape)
          "r" -> t.copy(radius = v.toFloatOrNull() ?: t.radius)
          "peak" -> t.copy(peak = v.toFloatOrNull() ?: t.peak)
          "v2" -> t.copy(v2Prompt = V2Prompt.entries.firstOrNull { it.name.equals(v, true) } ?: t.v2Prompt)
          else -> t
        }
      }
      return t
    }
  }
}

/** La mappa del punto: [side]x[side] valori, il picco [MagicTouchTuning.peak] sul punto, 0 lontano. */
internal object PointMap {

  fun fill(side: Int, nx: Float, ny: Float, tuning: MagicTouchTuning, into: FloatArray, stride: Int, channel: Int) {
    val cx = nx * side
    val cy = ny * side
    val r = max(0.5f, tuning.radius * side)
    val lo = max(0, (cy - 4f * r).toInt() - 1)
    val hi = min(side - 1, (cy + 4f * r).toInt() + 1)
    val xl = max(0, (cx - 4f * r).toInt() - 1)
    val xh = min(side - 1, (cx + 4f * r).toInt() + 1)
    for (y in lo..hi) {
      for (x in xl..xh) {
        val dx = x + 0.5f - cx
        val dy = y + 0.5f - cy
        val d2 = dx * dx + dy * dy
        val v = when (tuning.shape) {
          MagicTouchTuning.Shape.DISC -> if (d2 <= r * r) tuning.peak else 0f
          MagicTouchTuning.Shape.GAUSS -> tuning.peak * exp(-d2 / (2f * r * r))
        }
        if (v != 0f) into[(y * side + x) * stride + channel] = v
      }
    }
  }
}

/** Un modello caricato. Una chiamata alla volta, sul thread del motore. */
internal interface MaskSession : AutoCloseable {

  /** La versione del modello, per i resoconti. */
  val label: String

  /** Il lato dell'ingresso (512 per la v1, 768 per la v2). */
  val side: Int

  /**
   * La probabilita' che ogni punto del quadro sia il soggetto toccato in ([nx], [ny]) (normalizzati sul quadro): [ModelFrame.side] al
   * quadrato valori in 0..1. [identity] dice che il quadro e' lo stesso della chiamata precedente (la v2 riusa la codifica
   * dell'immagine: il costo grosso e' li').
   */
  fun run(frame: ModelFrame, nx: Float, ny: Float, identity: Any?): FloatArray
}

/** L'uscita del modello come probabilita': la v1 ha gia' una sigmoide in fondo, un'uscita fuori da 0..1 e' un logit. */
internal object Probabilities {

  fun of(raw: FloatArray): FloatArray {
    var lo = Float.MAX_VALUE
    var hi = -Float.MAX_VALUE
    for (v in raw) {
      if (v < lo) lo = v
      if (v > hi) hi = v
    }
    if (lo >= 0f && hi <= 1f) return raw
    for (i in raw.indices) raw[i] = 1f / (1f + exp(-raw[i]))
    return raw
  }
}
