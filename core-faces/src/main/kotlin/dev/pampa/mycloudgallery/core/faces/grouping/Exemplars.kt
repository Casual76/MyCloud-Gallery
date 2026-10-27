package dev.pampa.mycloudgallery.core.faces.grouping

import dev.pampa.mycloudgallery.core.faces.contract.GroupingParams
import kotlin.math.sqrt

/**
 * Le grandezze del §2.5 sul nucleo vero, sopra le righe di una [FaceMatrix] (in ordine di riferimento: riga minore = riferimento minore).
 * Stessi conti, nello stesso ordine e con gli stessi spareggi, di `testing/GroupingMath`: centroide, esemplari, somiglianza come media dei
 * 3 coseni piu' alti, legame medio fra esemplari. Il riferimento non si usa in produzione (sta in `testing/`, fuori dalla release).
 */
internal object Exemplars {

  /** La media normalizzata delle righe [rows] (in ordine crescente), o null se sono vuote o si annullano. */
  fun centroid(matrix: FaceMatrix, rows: IntArray): FloatArray? {
    if (rows.isEmpty()) return null
    val dim = FaceMatrix.DIM
    val sum = DoubleArray(dim)
    for (r in rows) {
      val o = r * dim
      for (k in 0 until dim) sum[k] += matrix.data[o + k].toDouble()
    }
    var norm = 0.0
    for (x in sum) norm += x * x
    norm = sqrt(norm)
    if (norm == 0.0) return null
    return FloatArray(dim) { (sum[it] / norm).toFloat() }
  }

  /**
   * Gli esemplari (§2.5): al piu' [max] righe CORE (se non ce ne sono, quelle date), la piu' vicina al centroide, poi via via quella con il
   * minimo del massimo coseno verso le gia' scelte; a parita' la riga minore. [rows] in ordine crescente; [core] dice quali sono CORE.
   */
  fun choose(matrix: FaceMatrix, rows: IntArray, core: BooleanArray, max: Int): IntArray {
    val coreRows = rows.filter { core[it] }
    val pool = if (coreRows.isEmpty()) rows else coreRows.toIntArray()
    if (pool.isEmpty()) return pool
    val center = centroid(matrix, pool) ?: return intArrayOf(pool[0])
    var first = pool[0]
    var firstDot = matrix.dot(first, center)
    for (i in 1 until pool.size) {
      val d = matrix.dot(pool[i], center)
      if (d.compareTo(firstDot) > 0) {
        first = pool[i]
        firstDot = d
      }
    }
    val limit = minOf(max, pool.size)
    val chosen = IntArray(limit)
    chosen[0] = first
    if (limit == 1) return chosen
    val taken = BooleanArray(pool.size)
    taken[pool.indexOf(first)] = true
    // Il massimo coseno di ogni candidato verso le scelte, aggiornato a ogni scelta (Math.max come `maxOf`: stesso valore in ogni ordine).
    val maxCos = FloatArray(pool.size) { matrix.dot(pool[it], first) }
    for (n in 1 until limit) {
      var best = -1
      for (i in pool.indices) {
        if (taken[i]) continue
        if (best < 0 || maxCos[i].compareTo(maxCos[best]) < 0) best = i
      }
      taken[best] = true
      chosen[n] = pool[best]
      for (i in pool.indices) if (!taken[i]) maxCos[i] = maxOf(maxCos[i], matrix.dot(pool[i], pool[best]))
    }
    return chosen
  }

  /**
   * La somiglianza della riga [row] con una persona di esemplari [exemplars] (§2.5): la media dei 3 coseni piu' alti, esclusa la riga
   * stessa se e' un esemplare; null se non resta nessun esemplare. In float come il riferimento: ordine decrescente, somma, divisione.
   */
  fun similarity(matrix: FaceMatrix, row: Int, exemplars: IntArray): Float? {
    var count = 0
    val values = FloatArray(exemplars.size)
    for (e in exemplars) if (e != row) values[count++] = matrix.dot(row, e)
    return topMean(values, count)
  }

  /** La media dei [GroupingParams.TOP_EXEMPLARS] valori piu' alti fra i primi [count] di [values] (che riordina), o null. */
  fun topMean(values: FloatArray, count: Int): Float? {
    if (count == 0) return null
    values.sort(0, count)
    val top = minOf(GroupingParams.TOP_EXEMPLARS, count)
    var sum = 0f
    for (i in 0 until top) sum += values[count - 1 - i]
    return sum / top
  }

  /** Il legame medio (§2.5) fra due elenchi di esemplari: le somme dei coseni in double, nell'ordine degli elenchi, diviso il numero. */
  fun averageLink(matrix: FaceMatrix, a: IntArray, b: IntArray): Float {
    if (a.isEmpty() || b.isEmpty()) return 0f
    var sum = 0.0
    for (x in a) for (y in b) sum += matrix.dot(x, y).toDouble()
    return (sum / (a.size.toDouble() * b.size)).toFloat()
  }

  /** Il prodotto scalare di due vettori (i centroidi sono normalizzati: il coseno), come `GroupingMath.dot`. */
  fun dot(a: FloatArray, b: FloatArray): Float {
    var s = 0f
    for (i in a.indices) s += a[i] * b[i]
    return s
  }
}
