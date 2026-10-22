package dev.pampa.mycloudgallery.core.search.eval

// LA TARATURA DELL'ORDINE "PERTINENTI" (D48, §2.4): mezza vita `H` e peso `beta` del premio di recenza. Stesso file in core-search (test) e
// in :app (debug). Regola del §2.4: `H` fra {30, 90, 180, 365} giorni e `beta` fra {0,05; 0,10; 0,15; 0,20}; si sceglie il `beta` piu' grande
// che non toglie piu' di 1 punto di recall@20 rispetto a `beta = 0`, e fra gli `H` quello con l'MRR migliore sulle query con le date piu'
// recenti. I confini dei periodi di "Recenti" sono una scelta di presentazione e non si tarano.

/** La scelta di `H` e `beta` e i numeri che la giustificano (tutti con le soglie [thr] gia' calibrate). */
class OrderChoice(
  val params: OrderParams,
  /** Recall@20 con `beta = 0` e con la scelta (su tutte le query). */
  val recallAt20Base: Double,
  val recallAt20Chosen: Double,
  /** L'MRR della scelta sulle query "recenti" (vedi [EvalOrder.RECENT_QUERY_MAX_AGE_DAYS]) e con `beta = 0`. */
  val mrrRecentChosen: Double,
  val mrrRecentBase: Double,
  /** Quante query erano "recenti" (se zero si e' usato l'insieme intero). */
  val recentQueries: Int,
  /** Per ogni `H`, il `beta` piu' grande che rispettava il limite di 1 punto (0 = nessuno). */
  val betaByHalfLife: Map<Int, Float>,
) {
  override fun toString(): String = "OrderChoice(H=${params.halfLifeDays}, beta=${params.beta})"
}

object EvalOrder {
  val HALF_LIVES: List<Int> = listOf(30, 90, 180, 365)
  val BETAS: List<Float> = listOf(0.05f, 0.10f, 0.15f, 0.20f)

  /** Il recall@20 che il premio di recenza puo' togliere al massimo (1 punto, §5.3). */
  const val MAX_RECALL_LOSS: Double = 0.01

  /** Una query e' "recente" se la mediana dell'eta' delle sue attese e' di un anno o meno: sono quelle in cui la recenza puo' aiutare. */
  const val RECENT_QUERY_MAX_AGE_DAYS: Int = 365

  fun tune(tables: List<ScoreTable>, thr: Thr): OrderChoice {
    require(tables.any { it.expected.isNotEmpty() }) { "Servono query con almeno una cella attesa." }
    val base = EvalOrder.run(tables, thr, OrderParams(90, 0f))
    val baseRecall = base.total.recallAt20
    val recent = tables.filter { isRecent(it) }
    val recentSet = recent.ifEmpty { tables }
    val baseMrr = EvalCalibration.evaluate(recentSet, thr, OrderParams(90, 0f)).total.mrr
    val betaByH = LinkedHashMap<Int, Float>()
    var best: Triple<Int, Float, Double>? = null
    for (h in HALF_LIVES) {
      var chosen = 0f
      for (beta in BETAS) {
        val recall = run(tables, thr, OrderParams(h, beta)).total.recallAt20
        if (baseRecall - recall <= MAX_RECALL_LOSS + 1e-9) chosen = beta
      }
      betaByH[h] = chosen
      val mrr = EvalCalibration.evaluate(recentSet, thr, OrderParams(h, chosen)).total.mrr.let { if (it.isNaN()) 0.0 else it }
      val better = best == null || mrr > best.third + 1e-9 ||
        (kotlin.math.abs(mrr - best.third) <= 1e-9 && (chosen > best.second || (chosen == best.second && h < best.first)))
      if (better) best = Triple(h, chosen, mrr)
    }
    val (h, beta, mrr) = best!!
    val params = OrderParams(h, beta)
    return OrderChoice(
      params = params,
      recallAt20Base = baseRecall,
      recallAt20Chosen = run(tables, thr, params).total.recallAt20,
      mrrRecentChosen = mrr,
      mrrRecentBase = baseMrr,
      recentQueries = recent.size,
      betaByHalfLife = betaByH,
    )
  }

  /** La mediana dell'eta' (in giorni) delle celle attese di [table] che compaiono nella tabella; null se nessuna. */
  fun medianExpectedAge(table: ScoreTable): Int? {
    val ages = table.cells.filter { it.key in table.expected }.map { it.ageDays }.sorted()
    return if (ages.isEmpty()) null else ages[ages.size / 2]
  }

  private fun isRecent(table: ScoreTable): Boolean = medianExpectedAge(table)?.let { it <= RECENT_QUERY_MAX_AGE_DAYS } == true

  private fun run(tables: List<ScoreTable>, thr: Thr, params: OrderParams): EvalReport = EvalCalibration.evaluate(tables, thr, params)
}
