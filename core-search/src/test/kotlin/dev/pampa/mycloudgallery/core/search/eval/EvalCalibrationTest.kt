package dev.pampa.mycloudgallery.core.search.eval

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.search.contract.ResultRanking
import dev.pampa.mycloudgallery.core.search.contract.SearchThresholds
import org.junit.Test

/**
 * La calibrazione di Zc, Zl e Smin (D27) su dati sintetici: la procedura trova le soglie che portano la precisione dei sicuri sopra il 90%
 * senza perdere il recall@20, e dice quando non ci riesce. Sul set privato dell'utente i numeri veri li da' lo strumento di debug.
 */
class EvalCalibrationTest {

  @Test
  fun leFormuleDellOrdineSonoQuelleDelMotore() {
    for (z in listOf(null, -1f, 0f, 1.9f, 2f, 3f, 3.5f, 5f, 5.5f, 9f)) {
      for (t in listOf(0f, 0.6f, 1f)) {
        assertThat(EvalFormulas.relevance(z, t, SearchThresholds.ZL, SearchThresholds.ZC))
          .isWithin(1e-6f).of(ResultRanking.relevance(z, t))
      }
    }
    for (age in listOf(0, 1, 30, 90, 365, 5_000)) {
      for (h in EvalOrder.HALF_LIVES) {
        assertThat(EvalFormulas.recency(age, h)).isWithin(1e-6f).of(ResultRanking.recency(age, dev.pampa.mycloudgallery.core.search.contract.RankParams(halfLifeDays = h)))
      }
    }
    assertThat(EvalFormulas.MAX_LIKELY).isEqualTo(SearchThresholds.MAX_LIKELY)
  }

  @Test
  fun leSogliePredefiniSonoQuelleDiPartenzaDellaProcedura() {
    // Lo stesso valore che P6 tiene in SearchThresholds: se P9 le cambia dopo la misura vera, la partenza segue.
    assertThat(SearchThresholds.ZC).isEqualTo(3.5f)
    assertThat(SearchThresholds.ZL).isEqualTo(2.0f)
    assertThat(SearchThresholds.S_MIN).isEqualTo(0f)
  }

  @Test
  fun leSoglieDiPartenzaNonBastanoQuandoCiSonoNegativiDifficili() {
    val tables = EvalTestData.tables()
    val start = EvalCalibration.evaluate(tables, Thr(SearchThresholds.ZC, SearchThresholds.ZL, SearchThresholds.S_MIN))
    assertThat(start.total.precisionSure).isLessThan(0.9)
    val result = EvalSweep.calibrate(tables)
    assertThat(result.meetsPrecision).isTrue()
    assertThat(result.meetsRecall).isTrue()
    assertThat(result.meetsTargets).isTrue()
    assertThat(result.report.total.precisionSure).isAtLeast(0.9)
    assertThat(result.report.total.recallAt20).isAtLeast(0.8)
    // Il recall@20 non e' sceso rispetto alla partenza: la procedura ha solo tolto i falsi sicuri.
    assertThat(result.report.total.recallAt20).isAtLeast(start.total.recallAt20 - 0.02)
    assertThat(result.evaluated).isGreaterThan(300)
  }

  @Test
  fun unaSogliaSoloSuZDeveSuperareINegativiDifficili() {
    // Senza differenze di coseno fra attese e negativi (nessun Smin che aiuti) lo Zc deve salire sopra i negativi (z ~ 4,4 +- 0,25).
    val tables = EvalTestData.tables { EvalTestData.table(it, hardS = 0.33) }
    val result = EvalSweep.calibrate(tables)
    assertThat(result.thr.zc).isGreaterThan(4.7f)
    assertThat(result.meetsPrecision).isTrue()
  }

  @Test
  fun sMinSceglieDoveLaZNonBasta() {
    // I negativi hanno lo stesso z delle attese ma un coseno piu' basso: solo Smin li separa.
    val tables = EvalTestData.tables { EvalTestData.table(it, hard = 8, hardZ = 6.5, hardS = 0.20, expectedS = 0.34) }
    val result = EvalSweep.calibrate(tables)
    assertThat(result.thr.smin).isGreaterThan(0.2f)
    assertThat(result.thr.smin).isAtMost(0.34f)
    assertThat(result.meetsPrecision).isTrue()
  }

  @Test
  fun quandoNessunaTernaRaggiungeLaPrecisioneLoDice() {
    // I negativi sono indistinguibili dalle attese (stesso z e stesso coseno): nessuna soglia va bene.
    val tables = EvalTestData.tables { EvalTestData.table(it, hard = 12, hardZ = 6.5, hardZSd = 1.2, hardS = 0.33) }
    val result = EvalSweep.calibrate(tables)
    assertThat(result.meetsPrecision).isFalse()
    assertThat(result.meetsTargets).isFalse()
    assertThat(result.report.total.precisionSure).isLessThan(0.9)
  }

  @Test
  fun lAttesaFraIMenoProbabiliContaNelRecallA20() {
    // Attese con z fra 2,5 e 3,4: sotto Zc di partenza (3,5), quindi "meno probabili": comparono dopo i sicuri ma nei primi 20.
    val tables = EvalTestData.tables(10) { EvalTestData.table(it, expectedZ = 2.9, hard = 0, noise = 200, noiseMaxZ = 1.9) }
    val report = EvalCalibration.evaluate(tables, Thr(3.5f, 2.0f, 0f))
    assertThat(report.total.recallAt20).isGreaterThan(0.6)
    // Con Zl troppo alto (4) non escono piu'.
    val strict = EvalCalibration.evaluate(tables, Thr(5f, 4f, 0f))
    assertThat(strict.total.recallAt20).isLessThan(report.total.recallAt20)
  }

  @Test
  fun iMenoProbabiliSonoAlPiu300() {
    val cells = (0 until 800).map { ScoredCell("c$it", 2.5f + it * 0.001f, 0.2f, 0f, it) } + ScoredCell("e", 2.1f, 0.2f, 0f, 1)
    val table = ScoreTable("q", EvalCategory.OBJECTS, setOf("e"), cells)
    val run = EvalCalibration.run(table, Thr(3.5f, 2.0f, 0f))
    assertThat(run.sure.size + run.likely.size).isAtMost(800 + 1)
    assertThat(run.likely.size).isAtMost(300)
    assertThat(run.likely).doesNotContain("e")
  }

  @Test
  fun ilTestoTuttoEsattoEUnSicuroEMezzoEUnMenoProbabile() {
    val cells = listOf(
      ScoredCell("tutto", null, null, 1f, 10),
      ScoredCell("meta", null, null, 0.6f, 10),
      ScoredCell("contenuto", 4.0f, 0.3f, 0f, 10),
      ScoredCell("basso", 1.0f, 0.3f, 0f, 10),
    )
    val run = EvalCalibration.run(ScoreTable("q", EvalCategory.TEXT, setOf("tutto"), cells), Thr(3.5f, 2.0f, 0f))
    assertThat(run.sure).containsExactly("tutto", "contenuto")
    assertThat(run.likely).containsExactly("meta")
    // Uno Smin sopra il coseno toglie il contenuto dai sicuri (il testo no).
    val strict = EvalCalibration.run(ScoreTable("q", EvalCategory.TEXT, setOf("tutto"), cells), Thr(3.5f, 2.0f, 0.5f))
    assertThat(strict.sure).containsExactly("tutto")
    assertThat(strict.likely).containsExactly("meta", "contenuto")
  }

  @Test
  fun aParitaDiPertinenzaVinceLaPiuRecente() {
    val cells = listOf(ScoredCell("vecchia", 5f, 0.3f, 0f, 900), ScoredCell("nuova", 5f, 0.3f, 0f, 3), ScoredCell("media", 5f, 0.3f, 0f, 90))
    val run = EvalCalibration.run(ScoreTable("q", EvalCategory.SCENES, setOf("nuova"), cells), Thr(3.5f, 2.0f, 0f))
    assertThat(run.sure).containsExactly("nuova", "media", "vecchia").inOrder()
  }

  @Test
  fun laCalibrazioneNonSiAdattaSoloAiPropriDati() {
    val tables = EvalTestData.tables(24)
    val check = EvalSweep.crossCheck(tables)
    assertThat(check.trainOnA.meetsPrecision).isTrue()
    assertThat(check.trainOnB.meetsPrecision).isTrue()
    // Fuori dal campione il recall@20 cala poco: dati omogenei, nessun adattamento ai singoli casi.
    assertThat(check.recallDrop).isLessThan(0.05)
    assertThat(check.testOnA.total.precisionSure).isAtLeast(0.85)
    assertThat(check.testOnB.total.precisionSure).isAtLeast(0.85)
  }

  @Test
  fun seServonoQuerySenzaAtteseLaProceduraLoDice() {
    val empty = ScoreTable("q", EvalCategory.OBJECTS, emptySet(), listOf(ScoredCell("a", 5f, 0.3f, 0f, 1)))
    val result = runCatching { EvalSweep.calibrate(listOf(empty)) }
    assertThat(result.isFailure).isTrue()
  }

  @Test
  fun laTabellaTieneSoloQuelloCheServe() {
    val cells = (0 until 6_000).map { ScoredCell("c$it", it / 1_000f, 0.1f, 0f, 1) } +
      ScoredCell("testo", null, null, 1f, 1) + ScoredCell("attesa", -5f, 0.0f, 0f, 1)
    val table = ScoreTable.of("q", EvalCategory.OBJECTS, setOf("attesa"), cells)
    assertThat(table.cells.size).isEqualTo(ScoreTable.MAX_CELLS + 2)
    assertThat(table.cells.map { it.key }).containsAtLeast("testo", "attesa")
    // Le piu' alte in testa.
    assertThat(table.cells.first().z).isWithin(1e-6f).of(5.999f)
    assertThat(table.toString()).doesNotContain("attesa")
  }
}
