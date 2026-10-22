package dev.pampa.mycloudgallery.core.ai.testing

import dev.pampa.mycloudgallery.core.ai.contract.CutoutResult
import dev.pampa.mycloudgallery.core.ai.contract.SubjectCutter

/**
 * La SUITE DI CONTRATTO del ritagliatore (docs/fase6-progetto.md §5.2, §4.4 P13): lo stesso controllo sulle stesse fixture
 * ([CutoutFixtures]) per `FakeSubjectCutter` (sulla JVM) e per MagicTouch vero (sul dispositivo). Cosa deve fare un ritagliatore:
 *
 *  - su ogni fixture **con un soggetto**, toccato il soggetto: un [CutoutResult.Subject] con IoU almeno [CutoutQuality.MIN_IOU_EACH], e
 *    in media almeno [CutoutQuality.MIN_IOU_MEAN];
 *  - su ogni fixture **senza soggetto** (cielo, muro, sfocato, rumore): [CutoutResult.NoClearSubject] almeno nella quota
 *    [CutoutQuality.MIN_EMPTY_REJECTED] dei casi (E-V10);
 *  - mai un [CutoutResult.Failed].
 */
object SubjectCutterContract {

  /** Il risultato su una fixture. */
  class Line(val name: String, val hasSubject: Boolean, val outcome: Outcome, val iou: Float?, val coverage: Float?)

  enum class Outcome { SUBJECT, NO_SUBJECT, FAILED }

  class Report(val lines: List<Line>) {
    /** L'IoU medio sulle fixture con un soggetto (un soggetto perso vale 0). */
    val meanIou: Float
      get() {
        val withSubject = lines.filter { it.hasSubject }
        return if (withSubject.isEmpty()) 0f else withSubject.sumOf { (it.iou ?: 0f).toDouble() }.toFloat() / withSubject.size
      }

    /** La quota delle fixture vuote che non hanno dato un soggetto. */
    val emptyRejected: Float
      get() {
        val empty = lines.filter { !it.hasSubject }
        return if (empty.isEmpty()) 1f else empty.count { it.outcome == Outcome.NO_SUBJECT }.toFloat() / empty.size
      }

    val failures: Int get() = lines.count { it.outcome == Outcome.FAILED }

    /** Cio' che non va, una frase per voce; vuoto = il contratto e' rispettato. */
    fun problems(): List<String> = buildList {
      if (failures > 0) add("Errori del motore: $failures")
      for (line in lines) {
        if (line.hasSubject && (line.iou ?: 0f) < CutoutQuality.MIN_IOU_EACH) {
          add("${line.name}: ${if (line.outcome == Outcome.SUBJECT) "IoU ${"%.3f".format(line.iou)}" else "nessun soggetto"} sotto ${CutoutQuality.MIN_IOU_EACH}")
        }
      }
      if (meanIou < CutoutQuality.MIN_IOU_MEAN) add("IoU medio ${"%.3f".format(meanIou)} sotto ${CutoutQuality.MIN_IOU_MEAN}")
      if (emptyRejected < CutoutQuality.MIN_EMPTY_REJECTED) add("Vuoti respinti ${"%.2f".format(emptyRejected)} sotto ${CutoutQuality.MIN_EMPTY_REJECTED}")
    }
  }

  /** Fa girare il contratto; [onResult] vede ogni fixture e il suo risultato (le prove strumentate scrivono il log e le immagini). */
  suspend fun run(
    cutter: SubjectCutter,
    fixtures: List<CutoutFixture> = CutoutFixtures.all,
    onResult: (CutoutFixture, CutoutResult) -> Unit = { _, _ -> },
  ): Report {
    val lines = ArrayList<Line>()
    for (fixture in fixtures) {
      val result = cutter.cut(fixture.image(), fixture.point)
      onResult(fixture, result)
      lines += when (result) {
        is CutoutResult.Subject -> {
          val truth = fixture.truth
          Line(fixture.name, fixture.hasSubject, Outcome.SUBJECT, truth?.let { CutoutQuality.iou(result.mask, it) }, result.mask.coverage)
        }
        CutoutResult.NoClearSubject -> Line(fixture.name, fixture.hasSubject, Outcome.NO_SUBJECT, null, null)
        is CutoutResult.Failed -> Line(fixture.name, fixture.hasSubject, Outcome.FAILED, null, null)
      }
    }
    return Report(lines)
  }
}
