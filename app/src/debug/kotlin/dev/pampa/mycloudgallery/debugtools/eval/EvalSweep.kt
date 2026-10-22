package dev.pampa.mycloudgallery.debugtools.eval

import dev.pampa.mycloudgallery.core.search.contract.SearchThresholds

// LA SCELTA DI Zc, Zl E Smin (D27) dalle tabelle dei punteggi e dalle etichette. Stesso file in core-search (test) e in :app (debug).
//
// Procedura (provata con dati sintetici; sul set privato la fa lo strumento di debug):
//  1. si cercano (Zc, Smin) con Zl di partenza: fra le terne che danno **precisione dei sicuri >= obiettivo (con un punto di margine, se c'e')**
//     si prende quella con il recall@20 piu' alto, poi l'MRR piu' alto, poi Smin piu' basso e Zc piu' basso (meno rinunce a parita');
//  2. con quelli, si sceglie Zl: il piu' alto che tiene il recall@20 entro mezzo punto dal massimo (meno probabili piu' puliti);
//  3. si ripete il passo 1 con lo Zl trovato (una volta): le due soglie si influenzano poco ma non zero.
// Senza nessuna terna che raggiunga la precisione, si prende quella con la precisione piu' alta e il risultato dice che l'obiettivo non e' raggiunto.

/** Il risultato di una calibrazione: la terna scelta, le metriche e se gli obiettivi sono raggiunti. */
class CalibrationResult(
  val thr: Thr,
  val report: EvalReport,
  val meetsPrecision: Boolean,
  val meetsRecall: Boolean,
  /** Quante terne sono state provate. */
  val evaluated: Int,
  /** Vero se la terna scelta rispetta anche il punto di margine sulla precisione. */
  val withMargin: Boolean,
) {
  val meetsTargets: Boolean get() = meetsPrecision && meetsRecall

  override fun toString(): String = "CalibrationResult(Zc=${thr.zc}, Zl=${thr.zl}, Smin=${thr.smin}, obiettivi=$meetsTargets)"
}

object EvalSweep {

  /** Le griglie provate (passi di un quarto per z; l'S_min parte da 0 = nessun minimo). */
  val ZC_GRID: List<Float> = (0..18).map { 2.5f + it * 0.25f }
  val ZL_GRID: List<Float> = (0..14).map { 1.0f + it * 0.25f }
  val SMIN_GRID: List<Float> = listOf(0f) + (1..12).map { it * 0.05f }

  /** Un punto in meno di margine sulla precisione: la terna scelta non sta sul filo dell'obiettivo con 50 query. */
  const val PRECISION_MARGIN: Double = 0.01

  private const val RECALL_TOLERANCE = 0.005

  fun calibrate(
    tables: List<ScoreTable>,
    targets: EvalTargets = EvalTargets(),
    start: Thr = Thr(SearchThresholds.ZC, SearchThresholds.ZL, SearchThresholds.S_MIN),
  ): CalibrationResult {
    require(tables.any { it.expected.isNotEmpty() }) { "Servono query con almeno una cella attesa." }
    var evaluated = 0
    var thr = start
    repeat(2) {
      val grid = ArrayList<EvalCalibration.Point>()
      for (zc in ZC_GRID) {
        val zl = minOf(thr.zl, zc - 0.25f)
        for (smin in SMIN_GRID) grid += EvalCalibration.point(tables, Thr(zc, zl, smin))
      }
      evaluated += grid.size
      thr = pick(grid, targets).thr
      val likelyGrid = ZL_GRID.filter { it < thr.zc }.map { EvalCalibration.point(tables, Thr(thr.zc, it, thr.smin)) }
      evaluated += likelyGrid.size
      val best = likelyGrid.maxOf { it.metrics.recallAt20.orZero() }
      thr = likelyGrid.filter { it.metrics.recallAt20.orZero() >= best - RECALL_TOLERANCE }.maxBy { it.thr.zl }.thr
    }
    val report = EvalCalibration.evaluate(tables, thr)
    val precisionOk = report.total.sureShown > 0 && report.total.precisionSure >= targets.sureAccuracy
    return CalibrationResult(
      thr = thr,
      report = report,
      meetsPrecision = precisionOk,
      meetsRecall = report.total.recallAt20 >= targets.recallAt20,
      evaluated = evaluated,
      withMargin = report.total.sureShown > 0 && report.total.precisionSure >= targets.sureAccuracy + PRECISION_MARGIN,
    )
  }

  /** La terna migliore di [points] per gli [targets] (vedi la procedura in testa al file). */
  fun pick(points: List<EvalCalibration.Point>, targets: EvalTargets): EvalCalibration.Point {
    fun feasible(margin: Double) = points.filter { it.metrics.sureShown > 0 && it.metrics.precisionSure >= targets.sureAccuracy + margin }
    val pool = feasible(PRECISION_MARGIN).ifEmpty { feasible(0.0) }
    if (pool.isEmpty()) {
      return points.maxWith(
        compareBy<EvalCalibration.Point> { if (it.metrics.sureShown == 0) -1.0 else it.metrics.precisionSure }.thenBy { it.metrics.recallAt20.orZero() },
      )
    }
    return pool.maxWith(
      compareBy<EvalCalibration.Point> { it.metrics.recallAt20.orZero() }
        .thenBy { it.metrics.mrr.orZero() }
        .thenByDescending { it.thr.smin }
        .thenByDescending { it.thr.zc },
    )
  }

  /** Il controllo contro l'adattamento ai dati: si calibra su meta' delle query e si misura sull'altra meta', e viceversa. */
  class CrossCheck(val trainOnA: CalibrationResult, val testOnB: EvalReport, val trainOnB: CalibrationResult, val testOnA: EvalReport) {
    /** Di quanto cala il recall@20 fuori dal campione, la media delle due direzioni (punti, 0..1). */
    val recallDrop: Double
      get() = ((trainOnA.report.total.recallAt20 - testOnB.total.recallAt20) + (trainOnB.report.total.recallAt20 - testOnA.total.recallAt20)) / 2
  }

  fun crossCheck(tables: List<ScoreTable>, targets: EvalTargets = EvalTargets()): CrossCheck {
    val a = tables.filterIndexed { i, _ -> i % 2 == 0 }
    val b = tables.filterIndexed { i, _ -> i % 2 == 1 }
    val onA = calibrate(a, targets)
    val onB = calibrate(b, targets)
    return CrossCheck(onA, EvalCalibration.evaluate(b, onA.thr), onB, EvalCalibration.evaluate(a, onB.thr))
  }

  private fun Double.orZero(): Double = if (isNaN()) 0.0 else this
}
