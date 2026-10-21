package dev.pampa.mycloudgallery.core.ai.runtime

import dev.pampa.mycloudgallery.core.trust.data.AiLimits
import kotlin.math.sqrt

/**
 * I controlli sui vettori che escono dai motori (docs/fase6-progetto.md §2.2 "Controlli", E-A12): la dimensione dello spazio, ogni
 * componente finita, norma ricalcolata in float32 fra 0,99 e 1,01 (gli stessi limiti con cui il codec `vec` rifiuta un corpo, cosi'
 * un vettore che passa di qui non viene poi scartato da chi lo legge). Un vettore che non li passa non si salva ne' si pubblica: sulla
 * GPU si ripete su CPU (ripiego per la sessione), sulla CPU e' un errore.
 *
 * Codice puro: nessuna classe di Android.
 */
object VectorChecks {

  enum class Verdict { OK, WRONG_DIMENSION, NOT_FINITE, BAD_NORM }

  /** Il verdetto per [v] in uno spazio da [dimension] componenti. */
  fun verdict(v: FloatArray, dimension: Int): Verdict {
    if (v.size != dimension) return Verdict.WRONG_DIMENSION
    var sum = 0.0
    for (x in v) {
      if (!x.isFinite()) return Verdict.NOT_FINITE
      sum += x.toDouble() * x
    }
    val norm = sqrt(sum).toFloat()
    return if (norm in AiLimits.MIN_NORM..AiLimits.MAX_NORM) Verdict.OK else Verdict.BAD_NORM
  }

  fun isValid(v: FloatArray, dimension: Int): Boolean = verdict(v, dimension) == Verdict.OK

  /** Una norma entro [SLACK_MIN, SLACK_MAX] si riporta a 1 (la GPU lavora in float16 e puo' sbagliare di qualche millesimo). */
  private const val SLACK_MIN: Float = 0.90f
  private const val SLACK_MAX: Float = 1.10f

  /**
   * Il vettore pronto per essere salvato: se e' valido com'e', lo stesso; se e' finito, della dimensione giusta e con una norma
   * vicina a 1 (fra 0,90 e 1,10, per esempio l'errore di un'attivazione float16 sulla GPU) lo rinormalizza in float32 (la direzione
   * non cambia, e' l'unica cosa che il coseno guarda); altrimenti null: un valore non finito, la dimensione sbagliata o una norma
   * lontana da 1 non sono arrotondamenti ma un guasto, e sulla GPU fanno ripetere l'immagine su CPU (E-A12).
   */
  fun normalizedOrNull(v: FloatArray, dimension: Int): FloatArray? {
    if (v.size != dimension) return null
    var sum = 0.0
    for (x in v) {
      if (!x.isFinite()) return null
      sum += x.toDouble() * x
    }
    val norm = sqrt(sum).toFloat()
    if (norm in AiLimits.MIN_NORM..AiLimits.MAX_NORM) return v
    if (norm !in SLACK_MIN..SLACK_MAX) return null
    return FloatArray(v.size) { v[it] / norm }
  }

  /**
   * Il coseno fra due vettori normalizzati (o no: divide per le norme). 0 se uno dei due e' nullo o se le dimensioni sono diverse; i
   * test dei motori lo usano per il confronto GPU-CPU e con i vettori di riferimento.
   */
  fun cosine(a: FloatArray, b: FloatArray): Float {
    if (a.size != b.size || a.isEmpty()) return 0f
    var dot = 0.0
    var na = 0.0
    var nb = 0.0
    for (i in a.indices) {
      dot += a[i].toDouble() * b[i]
      na += a[i].toDouble() * a[i]
      nb += b[i].toDouble() * b[i]
    }
    if (na == 0.0 || nb == 0.0) return 0f
    return (dot / (sqrt(na) * sqrt(nb))).toFloat()
  }
}
