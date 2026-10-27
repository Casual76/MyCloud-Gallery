package dev.pampa.mycloudgallery.core.faces.eval

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceBox
import dev.pampa.mycloudgallery.core.model.FaceLocator
import org.junit.Test

/**
 * Le metriche a mano, su gruppi inventati: purezza, errori per volto, gruppi misti, frammentazione ("e' la stessa di ..."), copertura e l'intervallo
 * di Wilson. I numeri attesi si contano sulla carta; le prove sulle storie sintetiche (errori messi apposta) sono in `FacesEvalSyntheticTest`.
 */
class FacesEvalMetricsTest {
  private fun id(n: Int): String = "%08x-0000-4000-8000-%012x".format(n, n.toLong())

  private fun locator(n: Int): FaceLocator = FaceLocator(ContentKey("%064x".format(n.toLong() + 1)), 0, FaceBox(100, 100, 5_000, 6_000))

  private fun group(n: Int, shown: Int, wrong: Int = 0, mixed: Boolean = false, reviewed: Boolean = true, same: Set<Int> = emptySet()) =
    GroupLabel(id(n), shown, false, shown, reviewed, mixed, if (mixed) emptySet() else (1..wrong).map { locator(n * 1_000 + it) }.toSet(), same.map { id(it) }.toSet())

  private fun session(totals: EvalTotals, vararg groups: GroupLabel, kind: EvalSessionKind = EvalSessionKind.MEASURE) =
    SessionLabels(kind, 1L, totals, groups.toList())

  private val plain = EvalTotals(1_000, 800, 200, 50, 30)

  @Test
  fun laPurezzaEIlRapportoFraGruppiSenzaErroriEGruppiGuardati() {
    // 10 gruppi guardati: 8 puri, uno con un volto sbagliato, uno con tre.
    val groups = (1..8).map { group(it, 20) } + group(9, 20, wrong = 1) + group(10, 20, wrong = 3)
    val r = FacesEvalMetrics.report(session(plain, *groups.toTypedArray()))
    assertThat(r.groups).isEqualTo(10)
    assertThat(r.pureGroups).isEqualTo(8)
    assertThat(r.purity).isWithin(1e-12).of(0.8)
    assertThat(r.facesJudged).isEqualTo(200)
    assertThat(r.facesWrong).isEqualTo(4)
    assertThat(r.errorsPerFace).isWithin(1e-12).of(0.02)
    assertThat(r.mixedGroups).isEqualTo(0)
  }

  @Test
  fun IGruppiNonGuardatiNonContanoNeiNumeri() {
    val r = FacesEvalMetrics.report(session(plain, group(1, 20), group(2, 20, wrong = 2), group(3, 20, reviewed = false), group(4, 20, reviewed = false)))
    assertThat(r.selected).isEqualTo(4)
    assertThat(r.groups).isEqualTo(2)
    assertThat(r.purity).isWithin(1e-12).of(0.5)
    assertThat(r.facesJudged).isEqualTo(40)
  }

  @Test
  fun unGruppoMistoNonEPuroEQuandoNonSiSaQualiVoltiSonoSbagliatiNonEntraNegliErroriPerVolto() {
    val r = FacesEvalMetrics.report(session(plain, group(1, 20), group(2, 20, wrong = 1), group(3, 30, mixed = true)))
    assertThat(r.groups).isEqualTo(3)
    assertThat(r.pureGroups).isEqualTo(1)
    assertThat(r.mixedGroups).isEqualTo(1)
    assertThat(r.facesJudged).isEqualTo(40)
    assertThat(r.facesWrong).isEqualTo(1)
    assertThat(r.errorsPerFace).isWithin(1e-12).of(1.0 / 40)
  }

  @Test
  fun senzaGruppiGuardatiLaPurezzaNonValeNienteEQuestoSiVede() {
    val r = FacesEvalMetrics.report(session(plain, group(1, 20, reviewed = false)))
    assertThat(r.groups).isEqualTo(0)
    assertThat(r.meetsPurity()).isFalse()
    assertThat(r.errorsPerFace).isEqualTo(0.0)
    assertThat(r.fragmentation).isEqualTo(1.0)
    assertThat(FacesEvalMetrics.report(session(plain)).groups).isEqualTo(0)
  }

  @Test
  fun lAltraFacciaDelleEtichetteIlSessantaPerCentoDiErrori() {
    // Nessuna possibilita' di una divisione per zero con un gruppo senza volti mostrati.
    val r = FacesEvalMetrics.report(session(plain, group(1, 0)))
    assertThat(r.errorsPerFace).isEqualTo(0.0)
    assertThat(r.purity).isEqualTo(1.0)
  }

  @Test
  fun laFrammentazioneContaLeCatene() {
    // 1 = 2 = 3 (una catena), 4 = 5, 6 da sola: tre persone, sei gruppi, cinque dei sei guardati sono divisi.
    val r = FacesEvalMetrics.report(
      session(plain, group(1, 10, same = setOf(2)), group(2, 10, same = setOf(3)), group(3, 10), group(4, 10, same = setOf(5)), group(5, 10), group(6, 10)),
    )
    assertThat(r.identities).isEqualTo(3)
    assertThat(r.identityGroups).isEqualTo(6)
    assertThat(r.fragmentation).isWithin(1e-12).of(2.0)
    assertThat(r.splitGroups).isEqualTo(5)
  }

  @Test
  fun ilLegameVaInUnVersoSoloEBastaEUnGruppoCheNonSiEGuardatoConta() {
    // Il gruppo 1 dice "e' la stessa del 9", e il 9 non e' stato guardato: una persona con due gruppi (uno guardato).
    val r = FacesEvalMetrics.report(session(plain, group(1, 10, same = setOf(9)), group(2, 10), group(9, 10, reviewed = false)))
    assertThat(r.identities).isEqualTo(2)
    assertThat(r.identityGroups).isEqualTo(3)
    assertThat(r.fragmentation).isWithin(1e-12).of(1.5)
    assertThat(r.splitGroups).isEqualTo(1)
  }

  @Test
  fun nessunLegameNessunaFrammentazione() {
    val r = FacesEvalMetrics.report(session(plain, group(1, 10), group(2, 10), group(3, 10)))
    assertThat(r.fragmentation).isEqualTo(1.0)
    assertThat(r.splitGroups).isEqualTo(0)
  }

  @Test
  fun laCoperturaEIVoltiCoreInUnGruppoEIlPianoBScattaSottoMeta() {
    val low = FacesEvalMetrics.report(session(EvalTotals(1_000, 499, 100, 0, 20), group(1, 10)))
    assertThat(low.coreCoverage).isWithin(1e-12).of(0.499)
    assertThat(low.planB()).isTrue()
    val edge = FacesEvalMetrics.report(session(EvalTotals(1_000, 500, 100, 0, 20), group(1, 10)))
    assertThat(edge.planB()).isFalse()
    assertThat(FacesEvalMetrics.report(session(EvalTotals(0, 0, 0, 0, 0), group(1, 10))).planB()).isFalse()
  }

  @Test
  fun lObiettivoDellaMisuraEIlNovantaSetteEQuelloDellaCalibrazioneIlNovantottoECinque() {
    val ok = FacesEvalMetrics.report(session(plain, *((1..97).map { group(it, 10) } + (98..100).map { group(it, 10, wrong = 1) }).toTypedArray()))
    assertThat(ok.purity).isWithin(1e-12).of(0.97)
    assertThat(ok.meetsPurity()).isTrue()
    val miss = FacesEvalMetrics.report(session(plain, *((1..96).map { group(it, 10) } + (97..100).map { group(it, 10, wrong = 1) }).toTypedArray()))
    assertThat(miss.meetsPurity()).isFalse()
    assertThat(FacesEvalMetrics.toNumbersText(miss)).contains("mancato")
    assertThat(FacesEvalMetrics.toNumbersText(ok)).contains("raggiunto")
    val calibration = FacesEvalMetrics.report(session(plain, *(1..40).map { group(it, 10) }.toTypedArray(), kind = EvalSessionKind.CALIBRATION))
    assertThat(FacesEvalMetrics.toNumbersText(calibration)).contains("obiettivo di calibrazione")
    assertThat(FacesEvalMetrics.toNumbersText(calibration)).contains("raggiunto")
  }

  @Test
  fun lIntervalloDiWilsonHaIValoriNoti() {
    val (low, high) = FacesEvalMetrics.wilson(38, 40)
    assertThat(low).isWithin(0.002).of(0.8350)
    assertThat(high).isWithin(0.002).of(0.9862)
    val (l40, h40) = FacesEvalMetrics.wilson(40, 40)
    assertThat(l40).isWithin(0.002).of(0.9124)
    assertThat(h40).isWithin(1e-4).of(1.0)
    assertThat(FacesEvalMetrics.wilson(0, 0)).isEqualTo(0.0 to 1.0)
    val (l0, h0) = FacesEvalMetrics.wilson(0, 10)
    assertThat(l0).isEqualTo(0.0)
    assertThat(h0).isWithin(0.002).of(0.2775)
  }

  @Test
  fun ilTestoDeiNumeriHaSoloNumeri() {
    val s = session(
      plain,
      group(1, 20, wrong = 1, same = setOf(2)),
      group(2, 20),
      group(3, 20, mixed = true),
    )
    val text = FacesEvalMetrics.toNumbersText(FacesEvalMetrics.report(s))
    assertThat(text).contains("purezza: 33,3%".replace(',', '.'))
    assertThat(text).contains("errori per volto: 2.5% (1/40)")
    assertThat(text).contains("frammentazione: 1.50")
    assertThat(text).contains("volti CORE in un gruppo: 80.0% (800/1000)")
    for (g in s.groups) assertThat(text).doesNotContain(g.person)
    for (g in s.groups) for (w in g.wrong) assertThat(text).doesNotContain(w.contentKey.hex.take(8))
    assertThat(text).doesNotContain("0000-4000")
  }

  @Test
  fun ilToStringDelResocontoNonHaIdNeNomi() {
    val r = FacesEvalMetrics.report(session(plain, group(1, 10)))
    assertThat(r.toString()).doesNotContain(id(1))
    assertThat(r.toString()).contains("purezza")
  }
}
