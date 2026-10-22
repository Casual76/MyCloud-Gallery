package dev.pampa.mycloudgallery.debugtools.eval

import dev.pampa.mycloudgallery.core.search.contract.RankParams
import dev.pampa.mycloudgallery.core.search.contract.SearchThresholds
import java.util.Locale

/**
 * SOLO debug (Fase 6, P9): la **corsa** della valutazione. Dalle tabelle dei punteggi (una per query con attese) calcola, in quest'ordine:
 *  1. le metriche con le soglie di oggi (`SearchThresholds`) e senza premio di recenza: recall@5, @10, @20, MRR, precisione dei sicuri, per categoria;
 *  2. la **calibrazione** di Zc, Zl e Smin (D27) e il controllo fuori campione;
 *  3. la taratura di H e beta dell'ordine "Pertinenti" (D48) con le soglie calibrate;
 *  4. la soglia di "Documenti" (D34), se l'utente ha segnato un campione.
 * Il testo che ne esce e' fatto di **soli numeri**: e' quello che va in `docs/decisioni.md`. Non cambia mai le soglie del codice di produzione: le propone.
 */
object EvalRunner {

  /** Le soglie e i parametri dell'ordine che il codice usa oggi. */
  fun current(): Thr = Thr(SearchThresholds.ZC, SearchThresholds.ZL, SearchThresholds.S_MIN)

  private val CURRENT_ORDER = RankParams()

  class Outcome(
    val queries: Int,
    val withExpected: Int,
    /** Query la cui tabella non ha il contenuto (motore delle query assente): contano solo per il testo. */
    val withoutContent: Int,
    val baseline: EvalReport,
    val calibration: CalibrationResult,
    val crossCheck: EvalSweep.CrossCheck?,
    val order: OrderChoice,
    val documents: DocumentChoice?,
    /** Il resoconto, soli numeri. */
    val text: String,
  )

  /** Meno di tante query con attese non permette il controllo fuori campione (due meta' da almeno 3 query ciascuna). */
  const val MIN_FOR_CROSS_CHECK: Int = 6

  fun summarize(tables: List<ScoreTable>, withoutContent: Int = 0, documents: DocumentChoice? = null): Outcome {
    val judged = tables.filter { it.expected.isNotEmpty() }
    require(judged.isNotEmpty()) { "Serve almeno una query con celle attese." }
    val now = current()
    val baseline = EvalCalibration.evaluate(judged, now)
    val calibration = EvalSweep.calibrate(judged)
    val cross = if (judged.size >= MIN_FOR_CROSS_CHECK && judged.chunkedHalves().all { half -> half.any { it.expected.isNotEmpty() } }) {
      EvalSweep.crossCheck(judged)
    } else {
      null
    }
    val order = EvalOrder.tune(judged, calibration.thr)
    val text = format(tables.size, judged.size, withoutContent, now, baseline, calibration, cross, order, documents)
    return Outcome(tables.size, judged.size, withoutContent, baseline, calibration, cross, order, documents, text)
  }

  private fun List<ScoreTable>.chunkedHalves(): List<List<ScoreTable>> =
    listOf(filterIndexed { i, _ -> i % 2 == 0 }, filterIndexed { i, _ -> i % 2 == 1 })

  private fun format(
    queries: Int,
    judged: Int,
    withoutContent: Int,
    now: Thr,
    baseline: EvalReport,
    calibration: CalibrationResult,
    cross: EvalSweep.CrossCheck?,
    order: OrderChoice,
    documents: DocumentChoice?,
  ): String {
    val out = StringBuilder()
    out.append("Valutazione della ricerca: $queries query ($judged con celle attese")
    if (withoutContent > 0) out.append(", $withoutContent senza il motore delle query: solo testo")
    out.append(")\n\n")
    out.append("1. Soglie di oggi: ${thr(now)}, ordine H=${CURRENT_ORDER.halfLifeDays} beta=${CURRENT_ORDER.recencyWeight} (qualita' con beta=0)\n")
    out.append("Obiettivi: recall@20 >= ${pct(EvalTargets().recallAt20)}, precisione dei sicuri >= ${pct(EvalTargets().sureAccuracy)}; ")
    out.append("precisione calcolata sui primi $JUDGED_DEPTH sicuri per query (i giudicati)\n")
    out.append(baseline.toNumbersText()).append('\n')
    out.append("2. Calibrazione proposta: ${thr(calibration.thr)} (${calibration.evaluated} terne provate)\n")
    out.append("Precisione dei sicuri: ${if (calibration.meetsPrecision) "RAGGIUNTA" else "NON raggiunta"}")
    out.append(if (calibration.withMargin) " (con un punto di margine)" else "").append("; ")
    out.append("recall@20: ${if (calibration.meetsRecall) "RAGGIUNTO" else "NON raggiunto"}\n")
    out.append(calibration.report.toNumbersText()).append('\n')
    if (cross != null) {
      out.append("Fuori campione (si calibra su meta' delle query e si misura sull'altra): calo medio del recall@20 ${pct(cross.recallDrop)}; ")
      out.append("precisione dei sicuri ${pct(cross.testOnB.total.precisionSure)} e ${pct(cross.testOnA.total.precisionSure)}\n\n")
    } else {
      out.append("Fuori campione: non calcolato (servono almeno $MIN_FOR_CROSS_CHECK query con attese).\n\n")
    }
    out.append("3. Ordine \"Pertinenti\": H=${order.params.halfLifeDays} beta=${order.params.beta} ")
    out.append("(recall@20 con beta=0 ${pct(order.recallAt20Base)}, con la scelta ${pct(order.recallAt20Chosen)}; ")
    out.append("MRR sulle ${order.recentQueries} query recenti ${num(order.mrrRecentBase)} -> ${num(order.mrrRecentChosen)})\n")
    out.append("Beta massimo per H: ").append(order.betaByHalfLife.entries.joinToString(", ") { "H${it.key}=${it.value}" }).append("\n\n")
    out.append("4. Documenti: ").append(documents?.toNumbersText() ?: "campione non segnato").append("\n\n")
    out.append("Da scrivere in docs/decisioni.md e, dopo la revisione, nel codice: ")
    out.append("SearchThresholds.ZC=${calibration.thr.zc}, ZL=${calibration.thr.zl}, S_MIN=${calibration.thr.smin}; ")
    out.append("RankParams(halfLifeDays=${order.params.halfLifeDays}, recencyWeight=${order.params.beta})")
    if (documents != null) out.append("; soglia Documenti ${"%.3f".format(Locale.ROOT, documents.threshold)}")
    out.append('\n')
    return out.toString()
  }

  private fun thr(t: Thr) = "Zc=${t.zc} Zl=${t.zl} Smin=${"%.2f".format(Locale.ROOT, t.smin)}"

  private fun pct(v: Double) = EvalReport.pct(v)

  private fun num(v: Double) = EvalReport.num(v)
}
