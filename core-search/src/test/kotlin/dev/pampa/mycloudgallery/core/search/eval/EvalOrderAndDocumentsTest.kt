package dev.pampa.mycloudgallery.core.search.eval

import com.google.common.truth.Truth.assertThat
import java.util.Random
import org.junit.Test

/**
 * La taratura dell'ordine "Pertinenti" (H e beta, D48, §2.4) e della soglia di "Documenti" (D34) su dati sintetici: la regola del §2.4 si applica
 * come scritta (il beta piu' grande che non toglie piu' di 1 punto di recall@20) e dice quando la recenza non serve.
 */
class EvalOrderAndDocumentsTest {

  private val thr = Thr(4f, 2f, 0f)

  /** Attese e distrattori con lo stesso z (differenze minime): la sola cosa che li distingue e' l'eta'. */
  private fun equalRelevance(index: Int, expectedAge: (Random) -> Int, otherAge: (Random) -> Int) =
    EvalTestData.table(
      index, expected = 12, expectedZ = 5.0, hard = 12, hardZ = 5.0, hardS = 0.33, expectedS = 0.33, noise = 0,
      expectedAge = expectedAge, otherAge = otherAge,
    )

  @Test
  fun seLeAtteseSonoRecentiIlPremioDiRecenzaAiuta() {
    val tables = EvalTestData.tables(12) { equalRelevance(it, expectedAge = { r -> r.nextInt(60) }, otherAge = { r -> 800 + r.nextInt(1000) }) }
    val choice = EvalOrder.tune(tables, thr)
    assertThat(choice.params.beta).isEqualTo(0.20f)
    assertThat(choice.recallAt20Chosen).isAtLeast(choice.recallAt20Base)
    assertThat(choice.mrrRecentChosen).isAtLeast(choice.mrrRecentBase)
    assertThat(choice.recentQueries).isEqualTo(12)
    assertThat(EvalOrder.HALF_LIVES).contains(choice.params.halfLifeDays)
  }

  @Test
  fun seLeAtteseSonoVecchieIlPremioNonDeveTogliereDiPiuDiUnPunto() {
    val tables = EvalTestData.tables(12) { equalRelevance(it, expectedAge = { r -> 1000 + r.nextInt(1000) }, otherAge = { r -> r.nextInt(60) }) }
    val choice = EvalOrder.tune(tables, thr)
    assertThat(choice.params.beta).isEqualTo(0f)
    assertThat(choice.betaByHalfLife.values.toSet()).containsExactly(0f)
    // Nessuna query recente: la scelta dell'H si fa su tutte.
    assertThat(choice.recentQueries).isEqualTo(0)
    assertThat(choice.recallAt20Chosen).isWithin(1e-9).of(choice.recallAt20Base)
  }

  @Test
  fun ilBetaSceltoNonTogliePiuDiUnPuntoDiRecall() {
    val tables = EvalTestData.tables(16) { i ->
      equalRelevance(i, expectedAge = { r -> if (i % 2 == 0) r.nextInt(60) else 1000 + r.nextInt(1000) }, otherAge = { r -> r.nextInt(2000) })
    }
    val choice = EvalOrder.tune(tables, thr)
    assertThat(choice.recallAt20Base - choice.recallAt20Chosen).isAtMost(EvalOrder.MAX_RECALL_LOSS + 1e-9)
    for ((h, beta) in choice.betaByHalfLife) {
      val loss = choice.recallAt20Base - EvalCalibration.evaluate(tables, thr, OrderParams(h, beta)).total.recallAt20
      assertThat(loss).isAtMost(EvalOrder.MAX_RECALL_LOSS + 1e-9)
    }
  }

  @Test
  fun leGrigliaDelleParametriSonoQuelleDelPiano() {
    assertThat(EvalOrder.HALF_LIVES).containsExactly(30, 90, 180, 365).inOrder()
    assertThat(EvalOrder.BETAS).containsExactly(0.05f, 0.10f, 0.15f, 0.20f).inOrder()
    assertThat(EvalOrder.MAX_RECALL_LOSS).isEqualTo(0.01)
  }

  // --- Documenti (D34) ------------------------------------------------------------------------------------------------------

  private fun samples(seed: Long, textNegatives: Int = 0): List<DocSample> {
    val r = Random(seed)
    val out = ArrayList<DocSample>()
    repeat(40) { out += DocSample("d$it", (0.58 + r.nextGaussian() * 0.04).toFloat(), if (it % 5 == 0) 8 else 1, if (it % 5 == 0) 300 else 40, true) }
    repeat(60) { out += DocSample("n$it", (0.35 + r.nextGaussian() * 0.05).toFloat(), 1, 20, false) }
    // Foto piene di testo che l'utente non considera documenti (un cartello, uno screenshot di una chat): la regola del testo le accende.
    repeat(textNegatives) { out += DocSample("h$it", (0.45 + r.nextGaussian() * 0.01).toFloat(), 9, 400, false) }
    return out
  }

  @Test
  fun laSogliaDeiDocumentiStaFraLeDueNuvole() {
    val choice = EvalDocuments.calibrate(samples(3))
    assertThat(choice.meetsPrecision).isTrue()
    assertThat(choice.precision).isAtLeast(0.9)
    assertThat(choice.recall).isAtLeast(0.9)
    assertThat(choice.threshold).isGreaterThan(0.35f)
    assertThat(choice.threshold).isLessThan(0.6f)
    assertThat(choice.byText).isEqualTo(8)
    assertThat(choice.toNumbersText()).contains("soglia")
  }

  @Test
  fun laRegolaDelTestoBastaDaSolaMaNonTogliePrecisione() {
    val s = DocSample("x", null, DocumentPrototypes.TEXT_LINES, 0, true)
    assertThat(EvalDocuments.isDocument(s, Float.POSITIVE_INFINITY)).isTrue()
    assertThat(EvalDocuments.isDocument(DocSample("y", null, 1, DocumentPrototypes.TEXT_CHARS, true), 0.9f)).isTrue()
    assertThat(EvalDocuments.isDocument(DocSample("z", null, 5, 199, true), 0.9f)).isFalse()
    // Senza vettore e senza testo fitto non e' un documento.
    assertThat(EvalDocuments.isDocument(DocSample("w", null, 0, 0, false), 0.0f)).isFalse()
  }

  @Test
  fun quandoLaRegolaDelTestoSbagliaTroppoNessunaSogliaBasta() {
    // Trenta foto piene di testo che non sono documenti: la regola del testo da sola ha il 21% di precisione e le soglie non la tolgono.
    val choice = EvalDocuments.calibrate(samples(5, textNegatives = 30))
    assertThat(choice.meetsPrecision).isFalse()
    assertThat(choice.precision.isNaN() || choice.precision < 0.9).isTrue()
  }

  @Test
  fun serveUnCampioneConDocumentiENonDocumenti() {
    val onlyDocs = (0 until 5).map { DocSample("d$it", 0.6f, 0, 0, true) }
    assertThat(runCatching { EvalDocuments.calibrate(onlyDocs) }.isFailure).isTrue()
  }

  @Test
  fun leFrasiPrototipoSonoQuelleDelProgetto() {
    assertThat(DocumentPrototypes.PHRASES).containsExactly("un documento", "uno scontrino", "una ricevuta", "un foglio con del testo").inOrder()
    // Revisione A (CE-1): le soglie del testo sono quelle provvisorie dell'app (set OCR sintetico di P9; D34 diceva 6 righe o 200 caratteri).
    assertThat(DocumentPrototypes.TEXT_LINES).isEqualTo(9)
    assertThat(DocumentPrototypes.TEXT_CHARS).isEqualTo(250)
  }
}
