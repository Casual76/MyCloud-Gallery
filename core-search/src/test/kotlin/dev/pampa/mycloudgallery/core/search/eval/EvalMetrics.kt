package dev.pampa.mycloudgallery.core.search.eval

import java.util.Locale

// LE METRICHE DELLA VALUTAZIONE (docs/fase6-progetto.md §5.3, P9): recall@5, @10 e @20, MRR e precisione dei sicuri, in totale e per
// categoria. Codice puro, senza dipendenze dal progetto: LO STESSO FILE sta in `core-search/src/test/.../eval/` (provato con dati
// sintetici) e in `app/src/debug/.../debugtools/eval/` (lo strumento di corsa sul telefono); cambia solo la riga `package`, e un test
// di :app (EvalSourceParityTest) controlla che le due copie restino uguali.
//
// Le cose sono "celle" (un gruppo conta una volta), identificate da una **chiave di contenuto** (stringa esadecimale). Le query e le
// chiavi del set privato non finiscono mai in un `toString`, in un log o in un file esportato.

/** Le otto categorie del set privato (§5.3). */
enum class EvalCategory(val code: String, val label: String) {
  OBJECTS("oggetti", "Oggetti e animali"),
  SCENES("scene", "Scene"),
  EVENTS("eventi", "Eventi e feste"),
  TEXT("testo", "Testo nelle foto"),
  COLORS("colori", "Colori e aspetto"),
  PLACES_DATES("luoghi", "Luoghi e date"),
  VIDEO("video", "Video"),
  SCREENSHOTS("schermate", "Screenshot e documenti"),
  ;

  companion object {
    fun fromCode(code: String): EvalCategory? = entries.firstOrNull { it.code == code }
  }
}

/** Gli obiettivi del §5.3: recall@20 almeno 80% (piano §4), precisione dei sicuri almeno 90% (D27). */
data class EvalTargets(val recallAt20: Double = 0.80, val sureAccuracy: Double = 0.90)

/**
 * Quante celle per elenco sono state **giudicate** dall'etichettatura "a pozzo": i primi 100 risultati di ciascuna configurazione.
 * La precisione dei sicuri si misura solo sui primi [JUDGED_DEPTH] sicuri: oltre, le foto non sono state viste dall'utente e non si
 * possono dichiarare sbagliate.
 */
const val JUDGED_DEPTH: Int = 100

/**
 * L'esito di una query: le celle attese ([expected], le etichette) e quelle che il motore ha dato, prima i **sicuri** e poi i
 * **meno probabili**, ciascun elenco nell'ordine mostrato. La qualita' si misura con l'ordine per pertinenza senza premio di recenza.
 */
class QueryRun(
  val id: String,
  val category: EvalCategory,
  val expected: Set<String>,
  sure: List<String>,
  likely: List<String>,
) {
  /** Senza doppioni: una cella conta una volta. */
  val sure: List<String> = sure.distinct()
  val likely: List<String> = likely.filter { it !in this.sure }.distinct()
  val ranked: List<String> = this.sure + this.likely

  override fun toString(): String = "QueryRun(${category.code}, ${expected.size} attese, ${sure.size} sicuri, ${likely.size} meno probabili)"
}

/** Le metriche di un gruppo di query. I valori sono frazioni 0..1; [precisionSure] e' NaN se nessuna query aveva sicuri. */
data class MetricSet(
  val queries: Int,
  /** Query con almeno una cella attesa: le sole su cui recall e MRR hanno senso. */
  val withExpected: Int,
  val recallAt5: Double,
  val recallAt10: Double,
  val recallAt20: Double,
  val mrr: Double,
  /** Quante delle attese stanno fra i sicuri (primi [JUDGED_DEPTH]), in media sulle query con attese: cio' che i sicuri "coprono". */
  val sureCoverage: Double,
  /** Celle giudicate giuste e totali fra i sicuri (primi [JUDGED_DEPTH] per query). */
  val sureHits: Int,
  val sureShown: Int,
  val queriesWithoutSure: Int,
) {
  /** Precisione dei sicuri sommando tutte le query (media pesata). */
  val precisionSure: Double get() = if (sureShown == 0) Double.NaN else sureHits.toDouble() / sureShown

  fun meets(targets: EvalTargets): Boolean =
    recallAt20 >= targets.recallAt20 && (sureShown == 0 || precisionSure >= targets.sureAccuracy)
}

/** Totale e per categoria; solo numeri. */
class EvalReport(val total: MetricSet, val byCategory: Map<EvalCategory, MetricSet>) {

  /** Il testo da copiare in `docs/decisioni.md`: **solo numeri**, mai query, chiavi o nomi (§5.3). */
  fun toNumbersText(): String {
    val out = StringBuilder()
    out.append("categoria | query | recall@5 | recall@10 | recall@20 | MRR | precisione sicuri (giudicati) | senza sicuri\n")
    out.append("---|---|---|---|---|---|---|---\n")
    out.append(line("totale", total))
    for (category in EvalCategory.entries) byCategory[category]?.let { out.append(line(category.code, it)) }
    return out.toString()
  }

  private fun line(name: String, m: MetricSet): String =
    "$name | ${m.queries} | ${pct(m.recallAt5)} | ${pct(m.recallAt10)} | ${pct(m.recallAt20)} | ${num(m.mrr)} | " +
      "${pct(m.precisionSure)} (${m.sureHits}/${m.sureShown}) | ${m.queriesWithoutSure}\n"

  override fun toString(): String = "EvalReport(${total.queries} query)"

  companion object {
    fun pct(v: Double): String = if (v.isNaN()) "-" else String.format(Locale.ROOT, "%.1f%%", v * 100)

    fun num(v: Double): String = if (v.isNaN()) "-" else String.format(Locale.ROOT, "%.3f", v)
  }
}

object EvalMetrics {

  /**
   * Recall@[k] di una query: le attese fra le prime [k] celle, diviso `min(attese, k)`. Il tetto a [k] serve perche' con piu' di
   * [k] foto attese non possono starci tutte nelle prime [k]: sarebbe un tetto irraggiungibile. NaN se non ci sono attese.
   */
  fun recallAt(run: QueryRun, k: Int): Double {
    require(k > 0) { "k deve essere positivo." }
    if (run.expected.isEmpty()) return Double.NaN
    val hits = run.ranked.take(k).count { it in run.expected }
    return hits.toDouble() / minOf(run.expected.size, k)
  }

  /** 1 / posizione (da 1) della prima cella attesa nell'elenco intero, 0 se non c'e'; NaN se non ci sono attese. */
  fun reciprocalRank(run: QueryRun): Double {
    if (run.expected.isEmpty()) return Double.NaN
    val at = run.ranked.indexOfFirst { it in run.expected }
    return if (at < 0) 0.0 else 1.0 / (at + 1)
  }

  /** Quanti dei primi [JUDGED_DEPTH] sicuri sono attesi. */
  fun sureHits(run: QueryRun): Int = run.sure.take(JUDGED_DEPTH).count { it in run.expected }

  fun sureShown(run: QueryRun): Int = minOf(run.sure.size, JUDGED_DEPTH)

  /** Le attese fra i primi [JUDGED_DEPTH] sicuri, diviso `min(attese, JUDGED_DEPTH)`; NaN se non ci sono attese. */
  fun sureCoverage(run: QueryRun): Double =
    if (run.expected.isEmpty()) Double.NaN else sureHits(run).toDouble() / minOf(run.expected.size, JUDGED_DEPTH)

  fun metrics(runs: List<QueryRun>): MetricSet {
    val judged = runs.filter { it.expected.isNotEmpty() }
    fun mean(f: (QueryRun) -> Double): Double = if (judged.isEmpty()) Double.NaN else judged.sumOf(f) / judged.size
    return MetricSet(
      queries = runs.size,
      withExpected = judged.size,
      recallAt5 = mean { recallAt(it, 5) },
      recallAt10 = mean { recallAt(it, 10) },
      recallAt20 = mean { recallAt(it, 20) },
      mrr = mean { reciprocalRank(it) },
      sureCoverage = mean { sureCoverage(it) },
      sureHits = runs.sumOf { sureHits(it) },
      sureShown = runs.sumOf { sureShown(it) },
      queriesWithoutSure = runs.count { it.sure.isEmpty() },
    )
  }

  fun report(runs: List<QueryRun>): EvalReport =
    EvalReport(metrics(runs), runs.groupBy { it.category }.mapValues { (_, v) -> metrics(v) }.toSortedMap())
}
