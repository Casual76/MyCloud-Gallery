package dev.pampa.mycloudgallery.core.search.eval

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Le metriche del §5.3 su casi calcolati a mano: recall@5, @10, @20, MRR, precisione dei sicuri, per categoria. */
class EvalMetricsTest {

  private fun run(
    expected: Set<String>,
    sure: List<String>,
    likely: List<String> = emptyList(),
    category: EvalCategory = EvalCategory.OBJECTS,
    id: String = "q",
  ) = QueryRun(id, category, expected, sure, likely)

  private fun keys(prefix: String, n: Int) = (1..n).map { "$prefix$it" }

  @Test
  fun recallEMrrSuUnaQuery() {
    val r = run(expected = setOf("a", "b", "c"), sure = listOf("a", "x", "b"), likely = listOf("y", "c"))
    assertThat(EvalMetrics.recallAt(r, 5)).isWithin(1e-9).of(1.0)
    assertThat(EvalMetrics.recallAt(r, 2)).isWithin(1e-9).of(0.5)
    assertThat(EvalMetrics.reciprocalRank(r)).isWithin(1e-9).of(1.0)
    val late = run(expected = setOf("c"), sure = listOf("x", "y", "c"))
    assertThat(EvalMetrics.reciprocalRank(late)).isWithin(1e-9).of(1.0 / 3)
    assertThat(EvalMetrics.reciprocalRank(run(expected = setOf("zz"), sure = listOf("x")))).isWithin(1e-9).of(0.0)
  }

  @Test
  fun ilRecallHaIlTettoDelleAttesePossibili() {
    // 30 attese: nelle prime 20 non possono starci tutte, quindi il denominatore e' 20 (e 5 per il recall@5).
    val expected = keys("e", 30).toSet()
    val r = run(expected, sure = keys("e", 30))
    assertThat(EvalMetrics.recallAt(r, 5)).isWithin(1e-9).of(1.0)
    assertThat(EvalMetrics.recallAt(r, 10)).isWithin(1e-9).of(1.0)
    assertThat(EvalMetrics.recallAt(r, 20)).isWithin(1e-9).of(1.0)
    // Con 4 attese e 10 posti il denominatore e' 4.
    val few = run(setOf("a", "b", "c", "d"), sure = listOf("a", "x", "b", "y", "z", "w", "v", "c", "u", "t"))
    assertThat(EvalMetrics.recallAt(few, 10)).isWithin(1e-9).of(0.75)
    assertThat(EvalMetrics.recallAt(few, 5)).isWithin(1e-9).of(0.5)
  }

  @Test
  fun iSicuriVengonoPrimaDeiMenoProbabili() {
    val r = run(setOf("a", "b"), sure = listOf("x", "y"), likely = listOf("a", "b"))
    assertThat(r.ranked).containsExactly("x", "y", "a", "b").inOrder()
    assertThat(EvalMetrics.reciprocalRank(r)).isWithin(1e-9).of(1.0 / 3)
    assertThat(EvalMetrics.recallAt(r, 2)).isWithin(1e-9).of(0.0)
    assertThat(EvalMetrics.recallAt(r, 4)).isWithin(1e-9).of(1.0)
  }

  @Test
  fun unaCellaContaUnaVolta() {
    val r = run(setOf("a"), sure = listOf("a", "a", "b"), likely = listOf("b", "a", "c"))
    assertThat(r.sure).containsExactly("a", "b").inOrder()
    assertThat(r.likely).containsExactly("c")
    assertThat(r.ranked).containsExactly("a", "b", "c").inOrder()
  }

  @Test
  fun laPrecisioneDeiSicuriGuardaSoloIPrimiCentoGiudicati() {
    val expected = keys("e", 100).toSet()
    // 100 giusti seguiti da 50 sconosciuti: i 50 non sono stati giudicati e non contano.
    val r = run(expected, sure = keys("e", 100) + keys("u", 50))
    assertThat(EvalMetrics.sureShown(r)).isEqualTo(JUDGED_DEPTH)
    assertThat(EvalMetrics.sureHits(r)).isEqualTo(100)
    val m = EvalMetrics.metrics(listOf(r))
    assertThat(m.precisionSure).isWithin(1e-9).of(1.0)
    // Uno sbagliato dentro i primi cento si vede.
    val wrong = run(expected, sure = listOf("u1") + keys("e", 99))
    assertThat(EvalMetrics.metrics(listOf(wrong)).precisionSure).isWithin(1e-9).of(0.99)
  }

  @Test
  fun lePrecisioniSiSommanoPesandoPerIRisultati() {
    val a = run(setOf("a", "b", "c", "d"), sure = listOf("a", "b", "c", "d"), id = "q1")
    val b = run(setOf("z"), sure = listOf("x", "y"), id = "q2")
    val m = EvalMetrics.metrics(listOf(a, b))
    assertThat(m.sureHits).isEqualTo(4)
    assertThat(m.sureShown).isEqualTo(6)
    assertThat(m.precisionSure).isWithin(1e-9).of(4.0 / 6)
    assertThat(m.queriesWithoutSure).isEqualTo(0)
  }

  @Test
  fun querySenzaAtteseONonCountanoNelRecall() {
    val judged = run(setOf("a"), sure = listOf("a"), id = "q1")
    val none = run(emptySet(), sure = listOf("x"), id = "q2")
    val m = EvalMetrics.metrics(listOf(judged, none))
    assertThat(m.queries).isEqualTo(2)
    assertThat(m.withExpected).isEqualTo(1)
    assertThat(m.recallAt20).isWithin(1e-9).of(1.0)
    assertThat(m.mrr).isWithin(1e-9).of(1.0)
    // Ma i suoi sicuri sono tutti sbagliati: la precisione li conta.
    assertThat(m.precisionSure).isWithin(1e-9).of(0.5)
  }

  @Test
  fun senzaNessunaQueryConAtteseLeMedieSonoNaN() {
    val m = EvalMetrics.metrics(listOf(run(emptySet(), sure = emptyList())))
    assertThat(m.recallAt20.isNaN()).isTrue()
    assertThat(m.mrr.isNaN()).isTrue()
    assertThat(m.precisionSure.isNaN()).isTrue()
    assertThat(m.queriesWithoutSure).isEqualTo(1)
    assertThat(EvalMetrics.metrics(emptyList()).queries).isEqualTo(0)
  }

  @Test
  fun ilResocontoPerCategoriaERisultaSoloNumeri() {
    val runs = listOf(
      run(setOf("secret-key-1"), sure = listOf("secret-key-1"), category = EvalCategory.OBJECTS, id = "q1"),
      run(setOf("secret-key-2"), sure = listOf("other"), likely = listOf("secret-key-2"), category = EvalCategory.TEXT, id = "q2"),
      run(setOf("secret-key-3"), sure = listOf("secret-key-3"), category = EvalCategory.TEXT, id = "q3"),
    )
    val report = EvalMetrics.report(runs)
    assertThat(report.total.queries).isEqualTo(3)
    assertThat(report.byCategory.keys).containsExactly(EvalCategory.OBJECTS, EvalCategory.TEXT).inOrder()
    assertThat(report.byCategory.getValue(EvalCategory.TEXT).queries).isEqualTo(2)
    assertThat(report.byCategory.getValue(EvalCategory.TEXT).mrr).isWithin(1e-9).of((0.5 + 1.0) / 2)
    val text = report.toNumbersText()
    assertThat(text).contains("totale")
    assertThat(text).contains("testo")
    assertThat(text).contains("oggetti")
    // Nessuna chiave, nessun id di query nel resoconto.
    assertThat(text).doesNotContain("secret-key")
    assertThat(text).doesNotContain("q1")
    assertThat(run(setOf("secret-key-1"), listOf("secret-key-1")).toString()).doesNotContain("secret-key")
  }

  @Test
  fun gliObiettiviDelPianoSonoUnRecallDi80EUnaPrecisioneDi90() {
    val targets = EvalTargets()
    assertThat(targets.recallAt20).isEqualTo(0.80)
    assertThat(targets.sureAccuracy).isEqualTo(0.90)
    val good = EvalMetrics.metrics(listOf(run(setOf("a", "b", "c", "d", "e"), sure = listOf("a", "b", "c", "d", "e"))))
    assertThat(good.meets(targets)).isTrue()
    val bad = EvalMetrics.metrics(listOf(run(setOf("a", "b", "c", "d", "e"), sure = listOf("a", "x", "y", "z", "w"))))
    assertThat(bad.meets(targets)).isFalse()
  }

  @Test
  fun leOttoCategorieHannoUnCodiceStabile() {
    assertThat(EvalCategory.entries).hasSize(8)
    assertThat(EvalCategory.entries.map { it.code }.toSet()).hasSize(8)
    for (c in EvalCategory.entries) assertThat(EvalCategory.fromCode(c.code)).isEqualTo(c)
    assertThat(EvalCategory.fromCode("sconosciuta")).isNull()
  }
}
