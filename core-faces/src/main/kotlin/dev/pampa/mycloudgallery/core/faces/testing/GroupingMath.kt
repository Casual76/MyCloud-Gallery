package dev.pampa.mycloudgallery.core.faces.testing

import dev.pampa.mycloudgallery.core.faces.contract.GroupFace
import dev.pampa.mycloudgallery.core.faces.contract.GroupingParams
import dev.pampa.mycloudgallery.core.model.FaceRef
import dev.pampa.mycloudgallery.core.model.FaceTier
import kotlin.math.sqrt

/**
 * Le grandezze del raggruppamento (docs/fase8-progetto.md §2.5 "Le grandezze") scritte nel modo piu' semplice, per l'esecutore di
 * riferimento ([ReferenceGrouping]). Deterministiche: ogni spareggio per riferimento. Nessuna dipendenza dall'ordine delle mappe.
 */
object GroupingMath {

  /** La media normalizzata dei vettori di [faces] (il centroide, §2.5), o null se non ce ne sono. */
  fun centroid(faces: Collection<GroupFace>): FloatArray? {
    if (faces.isEmpty()) return null
    val dim = faces.first().vector().size
    val sum = DoubleArray(dim)
    for (f in faces.sortedBy { it.ref }) {
      val v = f.vector()
      for (i in 0 until dim) sum[i] += v[i].toDouble()
    }
    var norm = 0.0
    for (x in sum) norm += x * x
    norm = sqrt(norm)
    if (norm == 0.0) return null
    return FloatArray(dim) { (sum[it] / norm).toFloat() }
  }

  /**
   * Gli esemplari di una persona (§2.5): al piu' [max] volti CORE (se non ne ha, i suoi volti qualunque), il piu' vicino al centroide, poi
   * via via quello piu' lontano dai gia' scelti (il minimo del massimo coseno), a parita' il riferimento minore.
   */
  fun exemplars(faces: Collection<GroupFace>, max: Int): List<FaceRef> {
    val core = faces.filter { it.tier == FaceTier.CORE }
    val pool = (core.ifEmpty { faces.toList() }).sortedBy { it.ref }
    if (pool.isEmpty()) return emptyList()
    val center = centroid(pool) ?: return listOf(pool.first().ref)
    val chosen = ArrayList<GroupFace>()
    chosen += pool.maxWith(compareBy<GroupFace> { it.dot(center) }.thenByDescending { it.ref })
    while (chosen.size < max && chosen.size < pool.size) {
      val next = pool.filter { it !in chosen }.minWith(
        compareBy<GroupFace> { candidate -> chosen.maxOf { candidate.cosine(it) } }.thenBy { it.ref },
      )
      chosen += next
    }
    return chosen.map { it.ref }
  }

  /**
   * La somiglianza di [face] con una persona (§2.5): la media dei [GroupingParams.TOP_EXEMPLARS] coseni piu' alti fra [face] e gli
   * [exemplars] (escluso [face] stesso se ne fa parte, come nelle espulsioni); null se non resta nessun esemplare.
   */
  fun similarity(face: GroupFace, exemplars: List<GroupFace>): Float? {
    val values = exemplars.filter { it.ref != face.ref }.map { face.cosine(it) }.sortedDescending()
    if (values.isEmpty()) return null
    val top = values.take(GroupingParams.TOP_EXEMPLARS)
    return (top.sum() / top.size)
  }

  /** Il prodotto scalare di due vettori della stessa dimensione (i centroidi sono normalizzati: il coseno). */
  fun dot(a: FloatArray, b: FloatArray): Float {
    var s = 0f
    for (i in a.indices) s += a[i] * b[i]
    return s
  }

  /** Il legame medio fra due insiemi di volti (§2.5: la media di tutti i coseni incrociati). */
  fun averageLink(a: List<GroupFace>, b: List<GroupFace>): Float {
    if (a.isEmpty() || b.isEmpty()) return 0f
    var sum = 0.0
    for (x in a) for (y in b) sum += x.cosine(y).toDouble()
    return (sum / (a.size.toDouble() * b.size)).toFloat()
  }

  /**
   * L'agglomerazione a legame medio delle nascite (§2.5 "Nascite"): parte da un gruppo per volto, unisce ogni volta i due gruppi con la
   * somiglianza media piu' alta finche' resta >= [stop], purche' fra i due ci sia almeno un arco (un coseno >= [edge] fra volti di foto
   * diverse); due volti della **stessa foto** non stanno mai nello stesso gruppo; a pari media i gruppi con i riferimenti minori.
   * Restituisce i gruppi (ognuno in ordine di riferimento), in ordine di riferimento minimo.
   */
  fun averageLinkage(faces: List<GroupFace>, stop: Float, edge: Float): List<List<GroupFace>> {
    val items = faces.sortedBy { it.ref }
    val n = items.size
    if (n == 0) return emptyList()
    val members = ArrayList<MutableList<Int>>(n)
    for (i in 0 until n) members += mutableListOf(i)
    val alive = BooleanArray(n) { true }
    val sum = Array(n) { DoubleArray(n) }
    val strongest = Array(n) { DoubleArray(n) }
    val forbidden = Array(n) { BooleanArray(n) }
    for (i in 0 until n) {
      for (j in i + 1 until n) {
        val same = items[i].photo == items[j].photo
        forbidden[i][j] = same
        forbidden[j][i] = same
        val c = items[i].cosine(items[j]).toDouble()
        sum[i][j] = c
        sum[j][i] = c
        strongest[i][j] = c
        strongest[j][i] = c
      }
    }
    while (true) {
      var best = -1
      var other = -1
      var bestAvg = Double.NEGATIVE_INFINITY
      for (i in 0 until n) {
        if (!alive[i]) continue
        for (j in i + 1 until n) {
          if (!alive[j] || forbidden[i][j] || strongest[i][j] < edge) continue
          val avg = sum[i][j] / (members[i].size.toDouble() * members[j].size)
          // Gli indici vivi sono il primo membro (il riferimento minore) di ogni gruppo: a pari media vince la coppia (i, j) minore.
          if (avg > bestAvg) {
            bestAvg = avg
            best = i
            other = j
          }
        }
      }
      if (best < 0 || bestAvg < stop) break
      members[best].addAll(members[other])
      alive[other] = false
      for (k in 0 until n) {
        if (!alive[k] || k == best) continue
        sum[best][k] += sum[other][k]
        sum[k][best] = sum[best][k]
        strongest[best][k] = maxOf(strongest[best][k], strongest[other][k])
        strongest[k][best] = strongest[best][k]
        forbidden[best][k] = forbidden[best][k] || forbidden[other][k]
        forbidden[k][best] = forbidden[best][k]
      }
    }
    return (0 until n).filter { alive[it] }.map { i -> members[i].sorted().map { items[it] } }
  }

  /**
   * L'assegnazione ottima (algoritmo ungherese, massimo del totale) per la matrice dei pesi [weights] (righe x colonne, pesi >= 0): per ogni
   * riga la colonna scelta o -1. Deterministica: a pari totale la soluzione che l'algoritmo trova scorrendo righe e colonne in ordine.
   */
  fun hungarian(weights: Array<DoubleArray>): IntArray {
    val rows = weights.size
    if (rows == 0) return IntArray(0)
    val cols = weights[0].size
    val n = maxOf(rows, cols)
    val max = weights.maxOfOrNull { row -> row.maxOrNull() ?: 0.0 } ?: 0.0
    // Minimo del costo = massimo del peso: costo = max - peso; righe e colonne in piu' a costo max (peso 0).
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
