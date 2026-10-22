package dev.pampa.mycloudgallery.debugtools.eval

// LA CALIBRAZIONE DELLE SOGLIE (docs/fase6-progetto.md §2.4 e §5.3, D27): da una **tabella dei punteggi** per query (z, coseno e testo di
// ogni cella, calcolati una volta) e dalle etichette si ricalcola la ricerca per ogni terna (Zc, Zl, Smin) senza rifare la ricerca.
// Stesso file in core-search (test) e in :app (debug): vedi EvalMetrics.kt.
//
// Oggi **non si possono scegliere** i valori: manca il set privato dell'utente. Qui c'e' la procedura, provata con dati sintetici.

/** Il punteggio di una cella per una query: contenuto ([z], [s]: null se la cella non ha vettore), testo ([t]: 1, 0,6 o 0) ed eta' in giorni. */
class ScoredCell(val key: String, val z: Float?, val s: Float?, val t: Float, val ageDays: Int)

/**
 * Tutte le celle che possono comparire in una ricerca (le altre non escono con nessuna soglia ragionevole) per una query, con le attese.
 * Per limitare la memoria la tabella tiene le prime [MAX_CELLS] celle per punteggio z, le prime [MAX_CELLS] con testo (per
 * pertinenza, a parita' le piu' recenti) e tutte le attese.
 */
class ScoreTable(val id: String, val category: EvalCategory, val expected: Set<String>, cells: List<ScoredCell>) {
  /** Ordinate per z decrescente (le celle senza contenuto in fondo): serve a prendere in fretta le piu' alte. */
  val cells: List<ScoredCell> = cells.sortedWith(compareByDescending<ScoredCell> { it.z ?: Float.NEGATIVE_INFINITY }.thenBy { it.key })

  override fun toString(): String = "ScoreTable(${category.code}, ${cells.size} celle, ${expected.size} attese)"

  companion object {
    const val MAX_CELLS: Int = 3_000

    /** Riduce [all] a quanto serve: le prime [MAX_CELLS] per z, le prime [MAX_CELLS] con testo e le attese. */
    fun of(id: String, category: EvalCategory, expected: Set<String>, all: List<ScoredCell>): ScoreTable {
      val top = all.asSequence().filter { it.z != null }.sortedByDescending { it.z }.take(MAX_CELLS).toHashSet()
      val texts = all.asSequence().filter { it.t > 0f }.sortedWith(compareByDescending<ScoredCell> { it.t }.thenBy { it.ageDays }.thenBy { it.key })
        .take(MAX_CELLS).toHashSet()
      val kept = all.filter { it in top || it in texts || it.key in expected }
      return ScoreTable(id, category, expected, kept)
    }
  }
}

/** Una terna di soglie: sicuri `z >= zc` e `s >= smin`; meno probabili `zl <= z < zc`. */
data class Thr(val zc: Float, val zl: Float, val smin: Float) {
  init {
    require(zl < zc) { "Zl deve stare sotto Zc." }
  }
}

/** I parametri dell'ordine "Pertinenti": mezza vita della recenza in giorni e peso. Con `beta = 0` la classifica non ha premio di recenza. */
data class OrderParams(val halfLifeDays: Int = 90, val beta: Float = 0f)

/** Le formule del §2.4 riscritte qui (il file e' autonomo); un test le confronta con `ResultRanking` del motore. */
object EvalFormulas {
  const val MAX_LIKELY: Int = 300

  /** La pertinenza (§2.4): contenuto `(z - Zl) / (Zc + 2 - Zl)` limitato a 0..1, testo 1 o 0,6, il massimo piu' 0,1 se entrambi. */
  fun relevance(z: Float?, t: Float, zl: Float, zc: Float): Float {
    val pc = if (z == null) 0f else ((z - zl) / (zc + 2 - zl)).coerceIn(0f, 1f)
    val bonus = if (pc > 0f && t > 0f) 0.1f else 0f
    return (maxOf(pc, t) + bonus).coerceAtMost(1f)
  }

  /** La recenza `2^(-eta / H)`. */
  fun recency(ageDays: Int, halfLifeDays: Int): Float = Math.pow(2.0, -ageDays.coerceAtLeast(0).toDouble() / halfLifeDays).toFloat()
}

object EvalCalibration {

  private const val SURE = 2
  private const val LIKELY = 1

  /** Lo stesso criterio di `SearchCore.finish`: sicuro se il contenuto supera `zc` e `smin` o il testo ha tutte le parole; meno probabile se supera `zl` o il testo ha almeno meta'. */
  private fun classify(c: ScoredCell, thr: Thr): Int {
    val sure = c.z != null && c.s != null && c.z >= thr.zc && c.s >= thr.smin
    val likely = c.z != null && c.z >= thr.zl
    return when {
      sure || c.t >= 1f -> SURE
      likely || c.t > 0f -> LIKELY
      else -> 0
    }
  }

  /** Il risultato della ricerca di [table] con le soglie [thr]: sicuri e meno probabili, ognuno nell'ordine "Pertinenti" di [order]. */
  fun run(table: ScoreTable, thr: Thr, order: OrderParams = OrderParams()): QueryRun {
    class Item(val cell: ScoredCell, val key: Float)
    val sure = ArrayList<Item>()
    val likely = ArrayList<Item>()
    for (c in table.cells) {
      val cls = classify(c, thr)
      if (cls == 0) continue
      val p = EvalFormulas.relevance(c.z, c.t, thr.zl, thr.zc)
      val key = p + order.beta * EvalFormulas.recency(c.ageDays, order.halfLifeDays)
      (if (cls == SURE) sure else likely) += Item(c, key)
    }
    val byRank = compareByDescending<Item> { it.key }.thenBy { it.cell.ageDays }.thenBy { it.cell.key }
    sure.sortWith(byRank)
    // Come il motore: i meno probabili sono al piu' MAX_LIKELY, i piu' pertinenti (la pertinenza, senza premio di recenza), poi ordinati.
    val cappedLikely = likely.sortedWith(compareByDescending<Item> { EvalFormulas.relevance(it.cell.z, it.cell.t, thr.zl, thr.zc) }.thenBy { it.cell.ageDays }.thenBy { it.cell.key })
      .take(EvalFormulas.MAX_LIKELY).sortedWith(byRank)
    return QueryRun(table.id, table.category, table.expected, sure.map { it.cell.key }, cappedLikely.map { it.cell.key })
  }

  fun evaluate(tables: List<ScoreTable>, thr: Thr, order: OrderParams = OrderParams()): EvalReport =
    EvalMetrics.report(tables.map { run(it, thr, order) })

  /** Una terna provata con le sue metriche totali. */
  class Point(val thr: Thr, val metrics: MetricSet)

  fun point(tables: List<ScoreTable>, thr: Thr, order: OrderParams = OrderParams()): Point =
    Point(thr, EvalMetrics.metrics(tables.map { run(it, thr, order) }))
}
