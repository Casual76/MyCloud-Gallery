package dev.pampa.mycloudgallery.core.faces.grouping

/**
 * L'ABBINAMENTO OTTIMO del ricalcolo completo (D22, E-F24, §2.5 "Ricalcolo completo"): i gruppi nuovi (righe) alle persone vecchie
 * (colonne) per numero di volti in comune, massimo del totale.
 *
 * **Per componenti**: le coppie con almeno un volto in comune fanno un grafo; ogni componente connessa si risolve da sola con l'algoritmo
 * ungherese (righe e colonne in ordine crescente). Il totale e' lo stesso della matrice intera (l'ottimo si scompone sulle componenti), ma
 * gli spareggi fra soluzioni ugualmente buone non dipendono da gruppi e persone che non c'entrano (con la matrice intera un gruppo nuovo in
 * fondo all'archivio poteva cambiare quale id eredita un gruppo in cima), e il costo e' quello delle componenti, non O(n³) su migliaia di
 * gruppi. Il riferimento (`GroupingMath.componentMatching`) fa lo stesso (scritto in `docs/decisioni.md`, Fase 8 P5).
 */
internal object OptimalMatching {

  /** Per ogni riga in `0 until rows` la colonna scelta o -1; [weights] = (riga, colonna) → peso > 0 (le coppie assenti pesano 0). */
  fun match(rows: Int, cols: Int, weights: Map<Pair<Int, Int>, Double>): IntArray {
    val result = IntArray(rows) { -1 }
    if (rows == 0 || cols == 0 || weights.isEmpty()) return result
    val parent = IntArray(rows + cols) { it }
    fun find(x: Int): Int {
      var r = x
      while (parent[r] != r) r = parent[r]
      var y = x
      while (parent[y] != r) {
        val next = parent[y]
        parent[y] = r
        y = next
      }
      return r
    }
    for ((key, w) in weights) {
      require(w > 0.0 && key.first in 0 until rows && key.second in 0 until cols) { "Peso non valido." }
      val a = find(key.first)
      val b = find(rows + key.second)
      if (a != b) parent[maxOf(a, b)] = minOf(a, b)
    }
    val components = sortedMapOf<Int, Pair<MutableList<Int>, MutableList<Int>>>()
    for ((key, _) in weights) {
      val c = components.getOrPut(find(key.first)) { mutableListOf<Int>() to mutableListOf() }
      c.first += key.first
      c.second += key.second
    }
    for ((_, members) in components) {
      val r = members.first.distinct().sorted()
      val c = members.second.distinct().sorted()
      val matrix = Array(r.size) { i -> DoubleArray(c.size) { j -> weights[r[i] to c[j]] ?: 0.0 } }
      val chosen = hungarian(matrix)
      for ((i, j) in chosen.withIndex()) if (j >= 0 && matrix[i][j] > 0.0) result[r[i]] = c[j]
    }
    return result
  }

  /**
   * L'assegnazione ottima (algoritmo ungherese, massimo del totale) per la matrice [weights] (righe x colonne, pesi >= 0): per ogni riga la
   * colonna scelta o -1. Lo stesso procedimento di `GroupingMath.hungarian` (righe e colonne scorse in ordine, stessi spareggi).
   */
  fun hungarian(weights: Array<DoubleArray>): IntArray {
    val rows = weights.size
    if (rows == 0) return IntArray(0)
    val cols = weights[0].size
    val n = maxOf(rows, cols)
    val max = weights.maxOfOrNull { row -> row.maxOrNull() ?: 0.0 } ?: 0.0
    val cost = Array(n) { i -> DoubleArray(n) { j -> if (i < rows && j < cols) max - weights[i][j] else max } }
    val u = DoubleArray(n + 1)
    val v = DoubleArray(n + 1)
    val p = IntArray(n + 1)
    val way = IntArray(n + 1)
    for (i in 1..n) {
      p[0] = i
      var j0 = 0
      val minv = DoubleArray(n + 1) { Double.POSITIVE_INFINITY }
      val used = BooleanArray(n + 1)
      do {
        used[j0] = true
        val i0 = p[j0]
        var delta = Double.POSITIVE_INFINITY
        var j1 = 0
        for (j in 1..n) {
          if (used[j]) continue
          val cur = cost[i0 - 1][j - 1] - u[i0] - v[j]
          if (cur < minv[j]) {
            minv[j] = cur
            way[j] = j0
          }
          if (minv[j] < delta) {
            delta = minv[j]
            j1 = j
          }
        }
        for (j in 0..n) {
          if (used[j]) {
            u[p[j]] += delta
            v[j] -= delta
          } else {
            minv[j] -= delta
          }
        }
        j0 = j1
      } while (p[j0] != 0)
      do {
        val j1 = way[j0]
        p[j0] = p[j1]
        j0 = j1
      } while (j0 != 0)
    }
    val result = IntArray(rows) { -1 }
    for (j in 1..n) {
      val i = p[j] - 1
      if (i in 0 until rows && j - 1 < cols) result[i] = j - 1
    }
    return result
  }
}
