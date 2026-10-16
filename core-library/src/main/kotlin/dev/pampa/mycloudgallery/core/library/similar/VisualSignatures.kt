package dev.pampa.mycloudgallery.core.library.similar

import dev.pampa.mycloudgallery.core.model.VisualSignature
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.roundToInt

// Fase 5A, P15 (docs/fase5-progetto.md §2.12, D36): la FIRMA VISIVA, parte pura (pixel -> firma). Nessun Android, nessun log, nessun
// accesso al disco: si prova sulla JVM con immagini generate. Il confronto e le soglie sono in `SignatureComparison.kt`.

/**
 * L'algoritmo 1 della firma visiva (il numero sta in `VisualSignature.algorithm`; una firma di un altro algoritmo non si confronta,
 * si ricalcola).
 *
 * **Cosa e'.** L'immagine dritta ridotta a una griglia di celle, **a medie d'area** (ogni pixel pesa per la parte che cade nella cella:
 * indipendente dalla taglia della sorgente, dal filtro con cui un'altra app l'ha ridotta e dal rumore della ricompressione, che nelle
 * medie di centinaia di pixel si annulla), poi in **OKLab** (uno spazio di colore percettivo: la distanza fra due colori somiglia a
 * quanto li vede diversi l'occhio):
 * - la luminosita' `L` su una griglia di [LUMA_SIDE] x [LUMA_SIDE] celle (un byte a cella, 0..255);
 * - il colore `a` e `b` su una griglia di [CHROMA_SIDE] x [CHROMA_SIDE] (media di 2 x 2 celle: il colore ha meno dettaglio della
 *   luce, come nel JPEG).
 *
 * Un fotogramma pesa [FRAME_BYTES] byte; una foto ne ha uno, un video due (due istanti, §2.12): 12 KB, sotto il tetto di
 * `VisualSignature.MAX_BYTES`.
 *
 * **Cosa non e'.** Non e' normalizzata: la luminosita' e il colore assoluti contano (l'esposizione di mezzo punto, un filtro
 * bianco e nero o un filtro di colore devono tenere separate due foto). Non e' invariante a ritaglio, rotazione e specchio: sono
 * foto diverse (il pixel e' dritto, non si "capisce" cosa c'e' dentro).
 */
object VisualSignatures {

  /** Il numero dell'algoritmo salvato in `visual_signature.algorithm`. */
  const val ALGORITHM: Int = 1

  /** Lato della griglia della luminosita'. */
  const val LUMA_SIDE: Int = 64

  /** Lato della griglia del colore. */
  const val CHROMA_SIDE: Int = LUMA_SIDE / 2

  /** Byte di un fotogramma: la luminosita', poi `a`, poi `b`. */
  const val FRAME_BYTES: Int = LUMA_SIDE * LUMA_SIDE + 2 * CHROMA_SIDE * CHROMA_SIDE

  /** Fotogrammi di un video (§2.12: due istanti). */
  const val VIDEO_FRAMES: Int = 2

  /** Passi per unita' di `a` e `b` di OKLab (il colore di una foto sta in circa -0,32..+0,28): 320 passi stanno in un byte attorno a 128. */
  internal const val CHROMA_STEPS: Float = 320f
  internal const val CHROMA_ZERO: Int = 128
  internal const val LUMA_STEPS: Float = 255f

  /** La firma di una foto. */
  fun of(photo: Raster): VisualSignature = of(listOf(photo))

  /** La firma di [frames] (una foto: un fotogramma; un video: [VIDEO_FRAMES], agli stessi istanti). */
  fun of(frames: List<Raster>): VisualSignature {
    require(frames.size in 1..VisualSignature.MAX_FRAMES) { "Numero di fotogrammi non valido." }
    val out = ByteArray(FRAME_BYTES * frames.size)
    frames.forEachIndexed { index, frame -> writeFrame(frame, out, index * FRAME_BYTES) }
    return VisualSignature.of(ALGORITHM, frames.size, out)
  }

  /** La firma letta in numeri, per il confronto. Null se non e' dell'algoritmo [ALGORITHM] o la forma non torna. */
  internal fun unpack(signature: VisualSignature): List<SignatureFrame>? {
    if (signature.algorithm != ALGORITHM || signature.size != FRAME_BYTES * signature.frames) return null
    val bytes = signature.bytes()
    return List(signature.frames) { f ->
      val base = f * FRAME_BYTES
      SignatureFrame(
        luma = bytes.copyOfRange(base, base + LUMA_SIDE * LUMA_SIDE),
        a = bytes.copyOfRange(base + LUMA_SIDE * LUMA_SIDE, base + LUMA_SIDE * LUMA_SIDE + CHROMA_SIDE * CHROMA_SIDE),
        b = bytes.copyOfRange(base + LUMA_SIDE * LUMA_SIDE + CHROMA_SIDE * CHROMA_SIDE, base + FRAME_BYTES),
      )
    }
  }

  // --- Dalla bitmap alla firma -------------------------------------------------------------------------------------------

  private fun writeFrame(raster: Raster, out: ByteArray, offset: Int) {
    val means = cellMeans(raster, LUMA_SIDE)
    val cells = LUMA_SIDE * LUMA_SIDE
    val lab = FloatArray(cells * 3)
    for (i in 0 until cells) {
      val r = srgbToLinear(means[i] / 255f)
      val g = srgbToLinear(means[cells + i] / 255f)
      val b = srgbToLinear(means[2 * cells + i] / 255f)
      oklab(r, g, b, lab, i * 3)
    }
    for (i in 0 until cells) out[offset + i] = (lab[i * 3] * LUMA_STEPS).roundToInt().coerceIn(0, 255).toByte()
    val chromaBase = offset + cells
    val chromaCells = CHROMA_SIDE * CHROMA_SIDE
    for (cy in 0 until CHROMA_SIDE) {
      for (cx in 0 until CHROMA_SIDE) {
        var sumA = 0f
        var sumB = 0f
        for (dy in 0..1) {
          for (dx in 0..1) {
            val i = (cy * 2 + dy) * LUMA_SIDE + (cx * 2 + dx)
            sumA += lab[i * 3 + 1]
            sumB += lab[i * 3 + 2]
          }
        }
        val c = cy * CHROMA_SIDE + cx
        out[chromaBase + c] = (sumA / 4f * CHROMA_STEPS).roundToInt().plus(CHROMA_ZERO).coerceIn(0, 255).toByte()
        out[chromaBase + chromaCells + c] = (sumB / 4f * CHROMA_STEPS).roundToInt().plus(CHROMA_ZERO).coerceIn(0, 255).toByte()
      }
    }
  }

  /**
   * Le medie d'area dei tre canali (0..255) su una griglia [side] x [side], come tre piani uno dopo l'altro (rosso, verde, blu). Ogni
   * cella copre una frazione esatta dell'immagine; i pixel sul bordo pesano per la parte che ci cade. Due passate separabili: righe,
   * poi colonne.
   */
  internal fun cellMeans(raster: Raster, side: Int): FloatArray {
    val w = raster.width
    val h = raster.height
    val xs = CellSpans(w, side)
    val ys = CellSpans(h, side)
    // Prima passata: ogni riga ridotta a `side` colonne.
    val rows = FloatArray(h * side * 3)
    for (y in 0 until h) {
      val rowBase = y * w
      for (i in 0 until side) {
        var r = 0f
        var g = 0f
        var b = 0f
        var x = xs.first[i]
        val last = xs.last[i]
        var k = xs.offset[i]
        while (x <= last) {
          val wgt = xs.weights[k++]
          val p = raster.pixel(rowBase + x)
          r += wgt * ((p shr 16) and 0xFF)
          g += wgt * ((p shr 8) and 0xFF)
          b += wgt * (p and 0xFF)
          x++
        }
        val norm = xs.total[i]
        val o = (y * side + i) * 3
        rows[o] = r / norm
        rows[o + 1] = g / norm
        rows[o + 2] = b / norm
      }
    }
    // Seconda passata: le colonne ridotte a `side` righe.
    val cells = side * side
    val out = FloatArray(cells * 3)
    for (j in 0 until side) {
      val norm = ys.total[j]
      for (i in 0 until side) {
        var r = 0f
        var g = 0f
        var b = 0f
        var y = ys.first[j]
        val last = ys.last[j]
        var k = ys.offset[j]
        while (y <= last) {
          val wgt = ys.weights[k++]
          val o = (y * side + i) * 3
          r += wgt * rows[o]
          g += wgt * rows[o + 1]
          b += wgt * rows[o + 2]
          y++
        }
        val cell = j * side + i
        out[cell] = r / norm
        out[cells + cell] = g / norm
        out[2 * cells + cell] = b / norm
      }
    }
    return out
  }

  /** Per ogni cella di una dimensione: i pixel che copre e il peso di ciascuno (la parte che ci cade). */
  private class CellSpans(length: Int, cells: Int) {
    val first = IntArray(cells)
    val last = IntArray(cells)
    val offset = IntArray(cells)
    val total = FloatArray(cells)
    val weights: FloatArray

    init {
      val list = ArrayList<Float>(length + cells)
      for (c in 0 until cells) {
        val from = c.toDouble() * length / cells
        val to = (c + 1).toDouble() * length / cells
        val a = from.toInt().coerceAtMost(length - 1)
        val b = (kotlin.math.ceil(to).toInt() - 1).coerceIn(a, length - 1)
        first[c] = a
        last[c] = b
        offset[c] = list.size
        var sum = 0.0
        for (p in a..b) {
          val overlap = (minOf(p + 1.0, to) - maxOf(p.toDouble(), from)).coerceAtLeast(0.0)
          sum += overlap
          list += overlap.toFloat()
        }
        total[c] = sum.toFloat().coerceAtLeast(Float.MIN_VALUE)
      }
      weights = list.toFloatArray()
    }
  }

  private fun srgbToLinear(v: Float): Float =
    if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()

  private fun oklab(r: Float, g: Float, b: Float, out: FloatArray, at: Int) {
    val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
    val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
    val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
    out[at] = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s
    out[at + 1] = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s
    out[at + 2] = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s
  }
}

/** Un fotogramma di una firma letto in byte (luminosita' 64 x 64, colore `a` e `b` 32 x 32). */
internal class SignatureFrame(val luma: ByteArray, val a: ByteArray, val b: ByteArray)
