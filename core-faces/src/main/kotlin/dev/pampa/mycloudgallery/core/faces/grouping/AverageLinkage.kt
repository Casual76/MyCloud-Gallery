package dev.pampa.mycloudgallery.core.faces.grouping

import java.util.PriorityQueue

/**
 * L'AGGLOMERAZIONE A LEGAME MEDIO DELLE NASCITE (docs/fase8-progetto.md §2.5 "Nascite", D20) sul nucleo vero: lo stesso risultato di
 * `GroupingMath.averageLinkage` del riferimento senza le sue matrici n x n.
 * - **Regola** (quella del riferimento): si uniscono ogni volta i due gruppi con la somiglianza media piu' alta, purche' sia >= [stop], fra
 *   i due ci sia un arco (un coseno >= [edge]) e nessuna coppia sia della stessa foto; a pari media la coppia di indici minori.
 * - **Archi**: due gruppi con media >= [stop] hanno per forza un coseno >= [stop] (il massimo non e' sotto la media), e devono averne uno
 *   >= [edge]: bastano quindi le coppie con coseno >= max([stop], [edge]) fra volti di foto diverse. Si calcolano tutte, a blocchi
 *   vettorizzati ([ColumnBlocks]), con gli stessi bit del riferimento.
 * - **Media**: fra due volti singoli e' il loro coseno (gli stessi bit del riferimento); fra gruppi e' il prodotto scalare delle somme dei
 *   vettori diviso il prodotto delle dimensioni (la somma di tutti i coseni incrociati, in double: differisce dal riferimento, che somma i
 *   coseni arrotondati in float, per meno di 1e-6).
 * - **Ordine**: ogni gruppo tiene il suo vicino migliore; una coda dice il migliore di tutti. Dopo un'unione il gruppo nuovo ricalcola
 *   le medie verso i suoi vicini (e chi trova il nuovo gruppo migliore del suo lo aggiorna); un vicino migliore diventato vecchio si
 *   ricalcola quando arriva in cima alla coda. La media del gruppo nuovo non supera mai la migliore dei due vecchi (legame medio).
 *
 * [rows] sono righe di [matrix] in ordine crescente (= di riferimento); [photoOf] da' la foto di ogni riga. Restituisce i gruppi (righe in
 * ordine crescente) in ordine di riga minima, come il riferimento.
 */
internal class AverageLinkage(
  private val matrix: FaceMatrix,
  private val rows: IntArray,
  private val photoOf: IntArray,
  private val stop: Float,
  private val edge: Float,
) {
  private val n = rows.size
  private val parent = IntArray(n) { it }
  private val size = IntArray(n) { 1 }
  private val sums = arrayOfNulls<DoubleArray>(n)
  private val photos = arrayOfNulls<IntArray>(n)
  private val neighbors = Array(n) { IntArrayBuilder(2) }
  private val content = IntArray(n)
  private val entry = IntArray(n)
  private val bestAvg = DoubleArray(n) { Double.NEGATIVE_INFINITY }
  private val bestTo = IntArray(n) { -1 }
  private val stamp = IntArray(n)
  private var stampNow = 0
  private val queue = PriorityQueue<Candidate>(maxOf(1, n), ORDER)

  /** Una proposta di unione del gruppo [owner] (alla sua versione [ownerEntry]) con [target] (al contenuto [targetContent]). */
  private class Candidate(val avg: Double, val lo: Int, val hi: Int, val owner: Int, val ownerEntry: Int, val target: Int, val targetContent: Int)

  fun groups(): List<IntArray> {
    if (n == 0) return emptyList()
    edges()
    for (x in 0 until n) refresh(x, others = false)
    while (true) {
      val c = queue.poll() ?: break
      val x = c.owner
      if (parent[x] != x || entry[x] != c.ownerEntry) continue
      val y = c.target
      if (parent[y] != y || content[y] != c.targetContent) {
        refresh(x, others = false)
        continue
      }
      if (c.avg < stop.toDouble()) break
      merge(c.lo, c.hi)
    }
    val members = HashMap<Int, IntArrayBuilder>()
    for (p in 0 until n) members.getOrPut(find(p)) { IntArrayBuilder() }.add(rows[p])
    return members.keys.sorted().map { members.getValue(it).toArray() }
  }

  private fun edges() {
    val tau = maxOf(stop, edge)
    if (n.toLong() * n <= DIRECT_PAIRS) {
      for (p in 0 until n) for (q in p + 1 until n) if (matrix.dot(rows[p], rows[q]) >= tau) link(p, q)
      return
    }
    val blocks = ColumnBlocks(matrix, rows)
    val out = FloatArray(ColumnBlocks.BLOCK)
    for (b in 0 until blocks.blocks) {
      val c0 = b * ColumnBlocks.BLOCK
      val c1 = minOf(n, c0 + ColumnBlocks.BLOCK)
      for (p in 0 until c1 - 1) {
        blocks.dots(matrix, rows[p], b, out)
        for (q in maxOf(p + 1, c0) until c1) if (out[q - c0] >= tau) link(p, q)
      }
    }
  }

  private fun link(p: Int, q: Int) {
    if (photoOf[rows[p]] == photoOf[rows[q]]) return
    neighbors[p].add(q)
    neighbors[q].add(p)
  }

  private fun find(x: Int): Int {
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

  private fun merge(lo: Int, hi: Int) {
    parent[hi] = lo
    val sum = sumOf(lo)
    addInto(sum, hi)
    sums[lo] = sum
    sums[hi] = null
    size[lo] += size[hi]
    photos[lo] = union(photosOf(lo), photosOf(hi))
    photos[hi] = null
    val mine = neighbors[lo]
    val theirs = neighbors[hi]
    for (i in 0 until theirs.size) mine.add(theirs[i])
    neighbors[hi] = IntArrayBuilder(0)
    content[lo]++
    content[hi]++
    refresh(lo, others = true)
  }

  /**
   * Il vicino migliore di [x] (radice), dai suoi vicini risolti e senza doppioni (l'elenco si compatta; le coppie con una foto in comune
   * si tolgono per sempre: le foto dei gruppi crescono soltanto). Con [others] ogni vicino che trova [x] migliore del suo lo aggiorna.
   */
  private fun refresh(x: Int, others: Boolean) {
    stampNow++
    stamp[x] = stampNow
    val list = neighbors[x]
    val kept = IntArrayBuilder(list.size)
    var bestValue = Double.NEGATIVE_INFINITY
    var best = -1
    for (i in 0 until list.size) {
      val k = find(list[i])
      if (stamp[k] == stampNow) continue
      stamp[k] = stampNow
      if (!disjoint(x, k)) continue
      kept.add(k)
      val v = average(x, k)
      if (best < 0 || better(v, x, k, bestValue, x, best)) {
        bestValue = v
        best = k
      }
      if (others && (bestTo[k] < 0 || better(v, k, x, bestAvg[k], k, bestTo[k]))) offer(k, x, v)
    }
    neighbors[x] = kept
    if (best >= 0) offer(x, best, bestValue) else {
      entry[x]++
      bestTo[x] = -1
      bestAvg[x] = Double.NEGATIVE_INFINITY
    }
  }

  private fun offer(owner: Int, target: Int, value: Double) {
    entry[owner]++
    bestAvg[owner] = value
    bestTo[owner] = target
    queue.add(Candidate(value, minOf(owner, target), maxOf(owner, target), owner, entry[owner], target, content[target]))
  }

  /** Vero se la coppia ([a], [b]) con media [v] batte la coppia ([c], [d]) con media [w]: media maggiore, poi indici minori. */
  private fun better(v: Double, a: Int, b: Int, w: Double, c: Int, d: Int): Boolean {
    if (v != w) return v > w
    val lo1 = minOf(a, b)
    val lo2 = minOf(c, d)
    if (lo1 != lo2) return lo1 < lo2
    return maxOf(a, b) < maxOf(c, d)
  }

  private fun average(x: Int, y: Int): Double {
    if (size[x] == 1 && size[y] == 1) return matrix.dot(rows[x], rows[y]).toDouble()
    val dim = FaceMatrix.DIM
    val sx = sums[x]
    val sy = sums[y]
    var s = 0.0
    when {
      sx != null && sy != null -> for (k in 0 until dim) s += sx[k] * sy[k]
      sx != null -> {
        val o = rows[y] * dim
        for (k in 0 until dim) s += sx[k] * matrix.data[o + k].toDouble()
      }
      sy != null -> {
        val o = rows[x] * dim
        for (k in 0 until dim) s += sy[k] * matrix.data[o + k].toDouble()
      }
    }
    return s / (size[x].toDouble() * size[y])
  }

  private fun sumOf(x: Int): DoubleArray {
    sums[x]?.let { return it }
    val out = DoubleArray(FaceMatrix.DIM)
    addInto(out, x)
    return out
  }

  private fun addInto(target: DoubleArray, x: Int) {
    val sx = sums[x]
    if (sx != null) {
      for (k in target.indices) target[k] += sx[k]
    } else {
      val o = rows[x] * FaceMatrix.DIM
      for (k in target.indices) target[k] += matrix.data[o + k].toDouble()
    }
  }

  private fun photosOf(x: Int): IntArray = photos[x] ?: intArrayOf(photoOf[rows[x]])

  private fun disjoint(x: Int, y: Int): Boolean {
    val px = photosOf(x)
    val py = photosOf(y)
    var i = 0
    var j = 0
    while (i < px.size && j < py.size) {
      when {
        px[i] == py[j] -> return false
        px[i] < py[j] -> i++
        else -> j++
      }
    }
    return true
  }

  private fun union(a: IntArray, b: IntArray): IntArray {
    val out = IntArray(a.size + b.size)
    var i = 0
    var j = 0
    var k = 0
    while (i < a.size || j < b.size) {
      out[k++] = if (j >= b.size || (i < a.size && a[i] < b[j])) a[i++] else b[j++]
    }
    return out
  }

  private companion object {
    /** Sotto questo numero di coppie gli archi si calcolano uno per uno (niente blocchi da allocare per le storie piccole). */
    const val DIRECT_PAIRS: Long = 600L * 600L

    val ORDER: Comparator<Candidate> = Comparator { a, b ->
      when {
        a.avg != b.avg -> b.avg.compareTo(a.avg)
        a.lo != b.lo -> a.lo.compareTo(b.lo)
        else -> a.hi.compareTo(b.hi)
      }
    }
  }
}
