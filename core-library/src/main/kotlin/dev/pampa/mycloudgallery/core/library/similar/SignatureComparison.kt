package dev.pampa.mycloudgallery.core.library.similar

import dev.pampa.mycloudgallery.core.model.VisualSignature
import kotlin.math.max
import kotlin.math.sqrt

// Fase 5A, P15 (docs/fase5-progetto.md §2.12): il CONFRONTO fra due firme visive, parte pura. Le soglie sono in `SimilarityRules`.

/**
 * La distanza fra due firme, in unita' di OKLab (la scala del colore percettivo: circa 0,02 e' la differenza minima che l'occhio
 * nota fra due colori affiancati). Si misura cella per cella: la differenza di luminosita' `dL` e, nella griglia del colore, `da` e
 * `db`; la **distanza della cella** e' `sqrt(dL^2 + da^2 + db^2)`.
 *
 * Tre statistiche sulle celle, perche' una sola non basta:
 * - [mean]: la media. Ricompressione, riduzione e rumore la alzano di poco (si annullano nelle medie d'area); un'esposizione
 *   diversa, un filtro di colore o un altro scatto la alzano di molto;
 * - [peak]: il 98esimo percentile, e [tail]: il 99,5esimo. Un ritaglio del 3%, un raddrizzamento di 1° o un soggetto che si e' mosso
 *   cambiano poche celle (i bordi) ma di molto: la media non li vede, i percentili si'. Il secondo prende anche i cambiamenti
 *   piccoli e locali (un soggetto che si sposta dello 0,5%: meno del 2% delle celle).
 *
 * Per un video valgono i **peggiori** dei due fotogrammi: devono combaciare tutti e due.
 *
 * @property structure quanta struttura hanno le due firme (la deviazione standard minore delle luminosita' delle celle): sotto
 *   [SimilarityRules.MIN_STRUCTURE] un cielo, un muro o uno scatto al buio non provano niente (qualunque altro scatto vuoto
 *   somiglierebbe), quindi non si uniscono.
 */
class SignatureDistance(val mean: Float, val peak: Float, val tail: Float, val structure: Float) {
  override fun toString(): String = "SignatureDistance(mean=$mean, peak=$peak, tail=$tail, structure=$structure)"
}

/** Il confronto di due firme. */
object SignatureComparison {

  /** Larghezza dei contenitori dell'istogramma delle distanze (0,0005 di OKLab) e loro numero: sopra 0,5 e' tutto "molto diverso". */
  private const val BIN: Float = 0.0005f
  private const val BINS: Int = 1_000

  private const val PEAK_PERCENTILE: Float = 0.98f
  private const val TAIL_PERCENTILE: Float = 0.995f

  /**
   * La distanza fra [a] e [b], o null se non sono confrontabili (algoritmo, forma o numero di fotogrammi diversi, byte che non sono di
   * questo algoritmo): "non si confronta, si ricalcola".
   */
  fun distance(a: VisualSignature, b: VisualSignature): SignatureDistance? {
    if (!a.comparableWith(b)) return null
    val fa = VisualSignatures.unpack(a) ?: return null
    val fb = VisualSignatures.unpack(b) ?: return null
    var mean = 0f
    var peak = 0f
    var tail = 0f
    var structure = Float.MAX_VALUE
    for (f in fa.indices) {
      val stats = frameDistance(fa[f], fb[f])
      mean = max(mean, stats.mean)
      peak = max(peak, stats.peak)
      tail = max(tail, stats.tail)
      structure = minOf(structure, stats.structure)
    }
    return SignatureDistance(mean, peak, tail, structure)
  }

  private fun frameDistance(a: SignatureFrame, b: SignatureFrame): SignatureDistance {
    val side = VisualSignatures.LUMA_SIDE
    val chromaSide = VisualSignatures.CHROMA_SIDE
    val histogram = IntArray(BINS + 1)
    var sum = 0.0
    for (y in 0 until side) {
      for (x in 0 until side) {
        val i = y * side + x
        val c = (y / 2) * chromaSide + (x / 2)
        val dl = ((a.luma[i].toInt() and 0xFF) - (b.luma[i].toInt() and 0xFF)) / VisualSignatures.LUMA_STEPS
        val da = ((a.a[c].toInt() and 0xFF) - (b.a[c].toInt() and 0xFF)) / VisualSignatures.CHROMA_STEPS
        val db = ((a.b[c].toInt() and 0xFF) - (b.b[c].toInt() and 0xFF)) / VisualSignatures.CHROMA_STEPS
        val e = sqrt(dl * dl + da * da + db * db)
        sum += e
        histogram[(e / BIN).toInt().coerceAtMost(BINS)]++
      }
    }
    val cells = side * side
    fun percentile(q: Float): Float {
      val target = (cells * q).toInt()
      var seen = 0
      for (bin in 0..BINS) {
        seen += histogram[bin]
        if (seen >= target) return (bin + 1) * BIN
      }
      return (BINS + 1) * BIN
    }
    return SignatureDistance(
      mean = (sum / cells).toFloat(),
      peak = percentile(PEAK_PERCENTILE),
      tail = percentile(TAIL_PERCENTILE),
      structure = minOf(lumaSpread(a), lumaSpread(b)),
    )
  }

  /** La deviazione standard delle luminosita' delle celle (0..1): quanta struttura c'e' nell'immagine. */
  internal fun lumaSpread(frame: SignatureFrame): Float {
    var sum = 0.0
    var sumSq = 0.0
    for (v in frame.luma) {
      val l = (v.toInt() and 0xFF) / VisualSignatures.LUMA_STEPS
      sum += l
      sumSq += l * l
    }
    val n = frame.luma.size
    val variance = (sumSq / n - (sum / n) * (sum / n)).coerceAtLeast(0.0)
    return sqrt(variance).toFloat()
  }
}
