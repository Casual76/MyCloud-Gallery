package dev.pampa.mycloudgallery.core.faces.grouping

import dev.pampa.mycloudgallery.core.faces.contract.GroupingParams

/** Un gruppo che nasce (§2.5 "Nascite"): i volti da cui nasce ([born], righe crescenti) e quelli presi dopo, a lotti ([absorbed]). */
internal class NewGroup(val born: IntArray, val exemplars: IntArray) {
  val absorbed = ArrayList<Absorbed>()

  /** Le righe di tutti i volti del gruppo, in ordine crescente. */
  fun rows(): IntArray = (born + absorbed.map { it.row }.toIntArray()).apply { sort() }
}

/** Un volto preso da un gruppo nato in un lotto precedente, con la sua somiglianza agli esemplari del gruppo. */
internal class Absorbed(val row: Int, val score: Float)

/**
 * I GRUPPI CHE NASCONO dai volti non assegnati (§2.5 "Nascite", D20).
 *
 * **Fino a [EXACT_POOL] volti** e' la regola del riferimento, identica: legame medio su tutto l'insieme ([AverageLinkage]), un gruppo nasce
 * con almeno `minFaces` volti di almeno `minPhotos` contenuti diversi; i gruppi in ordine di riferimento minimo.
 *
 * **Oltre** (il primo raggruppamento di un archivio grande, un ricalcolo completo, D22) l'insieme si prende **a lotti** di [BATCH] in ordine
 * di riferimento, come se i volti arrivassero a fine unita' (l'incrementale dipende dall'ordine di arrivo per progetto, D21): per ogni lotto
 * i suoi volti entrano prima nei gruppi nati dai lotti precedenti, con la regola dell'assegnazione (soglia `tJoin`, margine `margin` sulla
 * seconda persona contando anche le persone che esistono gia', [existing], e mai due volti della stessa foto); il resto, con i volti non
 * nati dei lotti prima, fa le sue nascite col legame medio. Cosi' gli archi e i conti restano proporzionati a un lotto: le memorie non
 * crescono con l'archivio (un gruppo enorme nasce nel primo lotto e poi prende i suoi volti per assegnazione). Il riferimento non ha lotti:
 * la parita' del §5.2 vale fino a [EXACT_POOL] (le storie sintetiche stanno sotto); oltre si provano le stesse regole (purezza, copertura,
 * vincoli) sulle storie grandi.
 */
internal object BirthGroups {
  const val EXACT_POOL: Int = 3_000
  const val BATCH: Int = 1_500

  fun form(table: FaceTable, pool: IntArray, params: GroupingParams, existing: List<IntArray>): List<NewGroup> {
    if (pool.size <= EXACT_POOL) return born(table, pool, params)
    val groups = ArrayList<NewGroup>()
    var carry = IntArray(0)
    var from = 0
    while (from < pool.size) {
      val chunk = pool.copyOfRange(from, minOf(pool.size, from + BATCH))
      from += chunk.size
      val rest = if (groups.isEmpty()) chunk else absorb(table, chunk, groups, existing, params)
      val next = (carry + rest).apply { sort() }
      val newborn = born(table, next, params)
      groups += newborn
      val taken = HashSet<Int>()
      for (g in newborn) for (r in g.born) taken += r
      carry = next.filter { it !in taken }.toIntArray()
    }
    return groups
  }

  /** Le nascite del riferimento su [rows]: legame medio e regola dei 3 volti di 2 foto. */
  private fun born(table: FaceTable, rows: IntArray, params: GroupingParams): List<NewGroup> =
    AverageLinkage(table.matrix, rows, table.photo, params.tBirth, params.tEdge).groups()
      .filter { g -> g.size >= params.minFaces && g.map { table.content[it] }.distinct().size >= params.minPhotos }
      .map { g -> NewGroup(g, Exemplars.choose(table.matrix, g, table.core, params.maxExemplars)) }

  /**
   * I volti di [chunk] che entrano nei gruppi gia' nati (gli altri tornano, in ordine): il migliore fra persone esistenti e gruppi nuovi
   * deve essere un gruppo nuovo, sopra `tJoin` (le nascite sono solo di volti CORE) e con il margine sul secondo; un gruppo che ha gia' un
   * volto della stessa foto non ne prende un altro. Gli esemplari dei gruppi sono quelli della nascita (fermi durante il lotto).
   */
  private fun absorb(table: FaceTable, chunk: IntArray, groups: List<NewGroup>, existing: List<IntArray>, params: GroupingParams): IntArray {
    val scorer = ExemplarScorer(table.matrix, existing + groups.map { it.exemplars })
    val scores = FloatArray(scorer.size)
    val photos = groups.map { g -> HashSet<Int>().apply { g.born.forEach { add(table.photo[it]) }; g.absorbed.forEach { add(table.photo[it.row]) } } }
    val rest = IntArrayBuilder(chunk.size)
    for (row in chunk) {
      scorer.score(row, scores)
      var best = -1
      var second = -1
      for (s in scores.indices) {
        if (scores[s].isNaN()) continue
        if (best < 0 || scores[s] > scores[best]) {
          second = best
          best = s
        } else if (second < 0 || scores[s] > scores[second]) {
          second = s
        }
      }
      val g = best - existing.size
      val ok = g >= 0 && scores[best] >= params.tJoin &&
        (second < 0 || scores[best] - scores[second] >= params.margin) && table.photo[row] !in photos[g]
      if (ok) {
        groups[g].absorbed += Absorbed(row, scores[best])
        photos[g] += table.photo[row]
      } else {
        rest.add(row)
      }
    }
    return rest.toArray()
  }
}
