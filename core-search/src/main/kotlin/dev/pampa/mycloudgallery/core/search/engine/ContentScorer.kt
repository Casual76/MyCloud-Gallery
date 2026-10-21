package dev.pampa.mycloudgallery.core.search.engine

import dev.pampa.mycloudgallery.core.search.contract.CandidateSet
import dev.pampa.mycloudgallery.core.search.contract.ScoreBlock
import dev.pampa.mycloudgallery.core.search.contract.SearchThresholds
import dev.pampa.mycloudgallery.core.search.index.CellMap
import dev.pampa.mycloudgallery.core.search.index.TwoPassIndex
import kotlin.math.abs

/**
 * I punteggi di contenuto di una query (D24, D27): per le celle con un punteggio **esatto** ([positions]) il coseno [scores], il
 * punteggio standardizzato [z] e il membro che lo da' ([members], per il filtro finale dei nascosti). [withVectors] = quante celle
 * candidate hanno un vettore. Senza almeno due celle con vettore non c'e' distribuzione: nessun punteggio (come il riferimento).
 */
internal class ContentScores(
  val positions: IntArray,
  val scores: FloatArray,
  val z: FloatArray,
  val members: IntArray,
  val withVectors: Int,
) {
  val size: Int get() = positions.size

  companion object {
    fun none(withVectors: Int) = ContentScores(IntArray(0), FloatArray(0), FloatArray(0), IntArray(0), withVectors)
  }
}

/**
 * LA SOGLIA RELATIVA ALLA QUERY (D27, §2.4) sull'indice a due passate (D24).
 *
 * - **Fino a [EXACT_ALL] celle** con vettore: coseno esatto per tutte (costa meno di 3 ms), mediana e deviazione robusta esatte: le
 *   stesse classi dell'esecutore di riferimento a forza bruta.
 * - **Oltre**: prima passata int8 su tutte; mediana e `σ = 1,4826 x MAD` dal coseno **esatto** di un campione fisso di
 *   [EXACT_STATS] celle a passo costante (la prima passata ha una scala sua: le soglie sono calibrate sul coseno vero); poi seconda
 *   passata sulle migliori [RERANK] della prima, e ancora su blocchi doppi dei successivi finche' un blocco e' fitto di sicuri
 *   (almeno l'1% con `z >= zSure - 0,5`): le ricerche con migliaia di risultati veri li trovano tutti, le code del rumore no.
 *   Esatto quanto basta (D24): fino a [EXACT_STATS] celle con vettore tutte hanno il coseno esatto e le classi sono quelle del
 *   riferimento; oltre, un "meno probabile" isolato nel rumore che la prima passata mette in basso puo' mancare.
 * - Le celle non rilette hanno `z` sconosciuto: sono fuori (sotto la soglia bassa).
 */
internal object ContentScorer {
  const val EXACT_ALL: Int = 4_096
  const val EXACT_STATS: Int = 8_192
  const val RERANK: Int = 1_000
  private const val EXTEND_MARGIN = 0.5f
  private const val DENSE = 100

  fun score(query: FloatArray, candidates: IntArray, map: CellMap, two: TwoPassIndex, zSure: Float, zLow: Float): ContentScores {
    val withVec = candidates.filter { map.hasVector(it) }.toIntArray()
    val n = withVec.size
    if (n < 2) return ContentScores.none(n)
    val exactS = FloatArray(n) { Float.NaN }
    val exactM = IntArray(n) { -1 }
    fun reread(rows: IntArray) {
      val (s, m) = two.rerankWithMembers(query, IntArray(rows.size) { withVec[rows[it]] })
      rows.forEachIndexed { i, r ->
        exactS[r] = s[i]
        exactM[r] = m[i]
      }
    }
    val median: Float
    val sigma: Float
    if (n <= EXACT_ALL) {
      reread(IntArray(n) { it })
      val stats = stats(exactS)
      median = stats.first
      sigma = stats.second
    } else {
      val coarse = ScoreBlock(n)
      two.coarse(query, CandidateSet(withVec), coarse)
      val k = minOf(n, EXACT_STATS)
      reread(IntArray(k) { (it.toLong() * n / k).toInt() })
      val stats = stats(IntArray(k) { (it.toLong() * n / k).toInt() }.map { exactS[it] }.toFloatArray())
      median = stats.first
      sigma = stats.second
      var count = 0
      while (count < n) {
        val next = minOf(n, if (count == 0) RERANK else count * 2)
        val block = topRange(coarse.scores, count, next).filter { exactS[it].isNaN() && exactM[it] == -1 }.toIntArray()
        reread(block)
        count = next
        // Si continua solo dentro una serie fitta di sicuri (almeno l'1% del blocco): migliaia di foto del cane sono tutte trovate,
        // mentre le code del rumore (sparse in tutta la prima passata) non fanno rileggere la Libreria intera.
        val sure = block.count { !exactS[it].isNaN() && z(exactS[it], median, sigma) >= zSure - EXTEND_MARGIN }
        if (sure < maxOf(1, block.size / DENSE)) break
      }
    }
    val kept = (0 until n).filter { !exactS[it].isNaN() }
    return ContentScores(
      positions = IntArray(kept.size) { withVec[kept[it]] },
      scores = FloatArray(kept.size) { exactS[kept[it]] },
      z = FloatArray(kept.size) { z(exactS[kept[it]], median, sigma) },
      members = IntArray(kept.size) { exactM[kept[it]] },
      withVectors = n,
    )
  }

  private fun z(s: Float, median: Float, sigma: Float): Float = if (sigma <= 1e-6f) 0f else (s - median) / sigma

  /** Mediana e σ = 1,4826 x MAD dei valori non NaN (come l'esecutore di riferimento: media dei due centrali se sono pari). */
  fun stats(values: FloatArray): Pair<Float, Float> {
    val sorted = values.filter { !it.isNaN() }.toFloatArray()
    if (sorted.isEmpty()) return 0f to 0f
    sorted.sort()
    val median = median(sorted)
    val dev = FloatArray(sorted.size) { abs(sorted[it] - median) }
    dev.sort()
    return median to SearchThresholds.MAD_SCALE * median(dev)
  }

  private fun median(sorted: FloatArray): Float =
    if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2f

  /**
   * Gli indici dei punteggi di rango [from] (incluso) .. [to] (escluso) per [scores] decrescenti (NaN in fondo). Una selezione per
   * soglia (O(n)) e un ordinamento dei soli scelti.
   */
  fun topRange(scores: FloatArray, from: Int, to: Int): IntArray {
    val n = scores.size
    if (from >= to || from >= n) return IntArray(0)
    val keys = LongArray(n)
    for (i in 0 until n) keys[i] = (sortable(scores[i]).toLong() shl 32) or i.toLong()
    // Il valore di rango `to - 1` dall'alto: quanti stanno sopra.
    val threshold = select(keys.copyOf(), n - to)
    val chosen = keys.filter { it >= threshold }.sortedDescending()
    return chosen.subList(from, minOf(to, chosen.size)).map { (it and 0xffffffffL).toInt() }.toIntArray()
  }

  /** I bit di un float ordinabili come interi (NaN in fondo). */
  private fun sortable(f: Float): Int {
    if (f.isNaN()) return Int.MIN_VALUE
    val bits = java.lang.Float.floatToIntBits(f)
    return if (bits < 0) bits xor 0x7fffffff else bits
  }

  /** Il k-esimo valore (0 = il minimo) di [a], quickselect in place. */
  private fun select(a: LongArray, k: Int): Long {
    var lo = 0
    var hi = a.size - 1
    val target = k.coerceIn(0, a.size - 1)
    while (lo < hi) {
      val pivot = a[(lo + hi) ushr 1]
      var i = lo
      var j = hi
      while (i <= j) {
        while (a[i] < pivot) i++
        while (a[j] > pivot) j--
        if (i <= j) {
          val t = a[i]; a[i] = a[j]; a[j] = t
          i++; j--
        }
      }
      if (target <= j) hi = j else if (target >= i) lo = i else return a[target]
    }
    return a[target]
  }
}
