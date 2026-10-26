package dev.pampa.mycloudgallery.core.library.duplicates

import dev.pampa.mycloudgallery.core.library.similar.SignatureComparison
import dev.pampa.mycloudgallery.core.library.similar.SignatureFrame
import dev.pampa.mycloudgallery.core.library.similar.VisualSignatures
import dev.pampa.mycloudgallery.core.model.VisualSignature
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// Fase 7, P8 (docs/fase7-progetto.md D25, §2.1.1): la firma visiva LETTA A GRANDE. La firma della Fase 5 (64x64 celle di luce, 32x32 di colore) e'
// fatta per dire "e' lo stesso scatto": le sue tre statistiche (media, 98esimo e 99,5esimo percentile delle distanze delle celle) hanno soglie
// strette, e chiedono che nessuna cella sia cambiata. Due scatti **diversi** della stessa scena (il soggetto che si e' mosso, la camera spostata
// di un dito) cambiano poche celle, di molto: per quelle statistiche sono "diversi" quanto una scena nuova. Lo ha misurato il set sintetico
// (`SimilarShotsCalibrationTest`): le soglie delle copie simili riconoscono 0 scatti su 130 dello stesso momento, e il 98esimo percentile di
// "soggetto +5%" e di "altra scena" si sovrappongono.
//
// Quello che separa uno scatto "della stessa scena" da uno "di un'altra" e' la **disposizione**: le stesse zone chiare e scure, gli stessi colori
// nelle stesse zone. Si legge riducendo la firma a una griglia 16x16 (blocchi di 4x4 celle di luce e di 2x2 di colore): un soggetto piccolo che
// si sposta sparisce nella media del blocco, un'esposizione o un filtro di colore no. E' la stessa firma, letta con meno risoluzione.

/** La distanza di disposizione fra due firme, in unita' di OKLab. [structure]: quanta struttura hanno (sotto soglia una firma non prova niente). */
internal class LayoutDistance(val mean: Float, val changed: Float, val structure: Float, private val sorted: FloatArray = FloatArray(0)) {

  /** La quota delle celle (0..1) la cui distanza supera [epsilon]: serve ai resoconti della calibrazione. */
  fun shareAbove(epsilon: Float): Float {
    if (sorted.isEmpty()) return 0f
    var above = 0
    for (d in sorted) if (d > epsilon) above++
    return above.toFloat() / sorted.size
  }

  override fun toString(): String = "LayoutDistance(mean=$mean, changed=$changed, structure=$structure)"
}

internal object SignatureLayout {

  /** Lato della griglia ridotta: 64 / 4 per la luce, 32 / 2 per il colore. */
  const val SIDE: Int = 16

  /**
   * Una cella e' "cambiata" se la sua distanza supera questo valore (OKLab): circa due volte la differenza minima che l'occhio nota fra due
   * colori affiancati. Un soggetto che si sposta cambia poche celle, una scena nuova o un'esposizione diversa quasi tutte.
   */
  const val CHANGED_EPSILON: Float = 0.05f

  /**
   * La distanza di disposizione fra [a] e [b], o null se non sono confrontabili (algoritmo, forma o numero di fotogrammi diversi). Per piu'
   * fotogrammi vale il peggiore (come `SignatureComparison`).
   */
  fun distance(a: VisualSignature, b: VisualSignature): LayoutDistance? {
    if (!a.comparableWith(b)) return null
    val fa = VisualSignatures.unpack(a) ?: return null
    val fb = VisualSignatures.unpack(b) ?: return null
    var mean = 0f
    var changed = 0f
    var structure = Float.MAX_VALUE
    var worst = FloatArray(0)
    for (f in fa.indices) {
      val frame = frameDistance(fa[f], fb[f])
      if (frame.mean >= mean) worst = frame.sorted
      mean = max(mean, frame.mean)
      changed = max(changed, frame.changed)
      structure = min(structure, min(SignatureComparison.lumaSpread(fa[f]), SignatureComparison.lumaSpread(fb[f])))
    }
    return LayoutDistance(mean, changed, structure, worst)
  }

  private class Frame(val mean: Float, val changed: Float, val sorted: FloatArray)

  private fun frameDistance(a: SignatureFrame, b: SignatureFrame): Frame {
    val la = coarseLuma(a.luma)
    val lb = coarseLuma(b.luma)
    val aa = coarseChroma(a.a)
    val ab = coarseChroma(b.a)
    val ba = coarseChroma(a.b)
    val bb = coarseChroma(b.b)
    val distances = FloatArray(SIDE * SIDE)
    var sum = 0f
    for (i in distances.indices) {
      val dl = la[i] - lb[i]
      val da = aa[i] - ab[i]
      val db = ba[i] - bb[i]
      val e = sqrt(dl * dl + da * da + db * db)
      distances[i] = e
      sum += e
    }
    distances.sort()
    return Frame(sum / distances.size, distances.count { it > CHANGED_EPSILON }.toFloat() / distances.size, distances)
  }

  /** La luce 64x64 (byte) come griglia 16x16 di medie, in 0..1. */
  private fun coarseLuma(luma: ByteArray): FloatArray = blockMeans(luma, VisualSignatures.LUMA_SIDE, VisualSignatures.LUMA_STEPS, 0)

  /** Il colore 32x32 (byte attorno a 128) come griglia 16x16 di medie, in unita' di OKLab. */
  private fun coarseChroma(chroma: ByteArray): FloatArray =
    blockMeans(chroma, VisualSignatures.CHROMA_SIDE, VisualSignatures.CHROMA_STEPS, VisualSignatures.CHROMA_ZERO)

  private fun blockMeans(plane: ByteArray, side: Int, steps: Float, zero: Int): FloatArray {
    val block = side / SIDE
    val out = FloatArray(SIDE * SIDE)
    for (gy in 0 until SIDE) {
      for (gx in 0 until SIDE) {
        var sum = 0
        for (y in 0 until block) {
          for (x in 0 until block) sum += (plane[(gy * block + y) * side + gx * block + x].toInt() and 0xFF) - zero
        }
        out[gy * SIDE + gx] = sum.toFloat() / (block * block) / steps
      }
    }
    return out
  }
}
