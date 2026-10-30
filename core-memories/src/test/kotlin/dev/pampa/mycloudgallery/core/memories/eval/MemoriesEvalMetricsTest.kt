package dev.pampa.mycloudgallery.core.memories.eval

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import org.junit.Test

/** Le metriche di §5.3 (ricordi graditi, foto da togliere per ricordo, doppioni, "da non mostrare") con numeri contati a mano. */
class MemoriesEvalMetricsTest {

  /** Quattro ricordi: due "anni fa" da 10 foto, un viaggio da 12, una persona da 8 (questa senza giudizio). */
  private val run = listOf(sampleMemory(1, 10), sampleMemory(2, 10), sampleMemory(3, 12, kind = "trip"), sampleMemory(4, 8, kind = "person"))

  private fun labels(): MemoriesLabelSet {
    var l = MemoriesLabelSet().withRun(run)
    l = l.withJudgement(run[0].id, EvalVerdict.GOOD).withFault(run[0].id, run[0].members[1], EvalFault.DUPLICATE)
    l = l.withJudgement(run[1].id, EvalVerdict.SO_SO)
      .withFault(run[1].id, run[1].members[0], EvalFault.BLURRY_OR_DARK).withFault(run[1].id, run[1].members[4], EvalFault.UNRELATED)
    l = l.withJudgement(run[2].id, EvalVerdict.DONT_SHOW, EvalWhy.SAD)
    for (k in 0 until 4) l = l.withFault(run[2].id, run[2].members[k], EvalFault.SAD)
    l = l.withFault(run[2].id, run[2].members[7], EvalFault.BLURRY_OR_DARK)
    return l.withFault(run[3].id, run[3].members[0], EvalFault.DUPLICATE)
  }

  @Test
  fun iNumeriDiUnaSessioneContatiAMano() {
    val n = MemoriesEvalMetrics.of(labels())
    // Contano solo i tre ricordi giudicati: il quarto (con una foto segnata) no.
    assertThat(n.memories).isEqualTo(3)
    assertThat(listOf(n.good, n.soSo, n.dontShow, n.dontShowSad, n.dontShowWrong, n.dontShowBoring)).containsExactly(1, 1, 1, 1, 0, 0).inOrder()
    assertThat(n.photos).isEqualTo(32)
    assertThat(n.faults).isEqualTo(1 + 2 + 5)
    assertThat(n.faultsByKind).containsExactly(EvalFault.DUPLICATE, 1, EvalFault.BLURRY_OR_DARK, 2, EvalFault.UNRELATED, 1, EvalFault.SAD, 4)
    assertThat(n.goodShare).isWithin(1e-9).of(1.0 / 3)
    assertThat(n.dontShowShare).isWithin(1e-9).of(1.0 / 3)
    assertThat(n.faultShare).isWithin(1e-9).of(8.0 / 32)
    assertThat(n.meanFaultShare).isWithin(1e-9).of((0.1 + 0.2 + 5.0 / 12) / 3)
    assertThat(n.duplicateShare).isWithin(1e-9).of(1.0 / 32)
    assertThat(n.sadFaults).isEqualTo(4)
    assertThat(n.clean).isEqualTo(0)
  }

  @Test
  fun unGiudizioSenzaFotoSegnateEUnRicordoPulito() {
    val l = MemoriesLabelSet().withRun(run).withJudgement(run[0].id, EvalVerdict.GOOD)
    val n = MemoriesEvalMetrics.of(l)
    assertThat(n.clean).isEqualTo(1)
    assertThat(n.meanFaultShare).isEqualTo(0.0)
    assertThat(n.meets(MemoriesEvalTargets())).isTrue()
  }

  @Test
  fun senzaGiudiziNonCiSonoNumeri() {
    val n = MemoriesEvalMetrics.of(MemoriesLabelSet().withRun(run))
    assertThat(n.memories).isEqualTo(0)
    assertThat(n.goodShare.isNaN()).isTrue()
    assertThat(n.meanFaultShare.isNaN()).isTrue()
    assertThat(n.meets(MemoriesEvalTargets())).isFalse()
    assertThat(MemoriesEvalMetrics.report(MemoriesLabelSet().withRun(run)).conclusive).isFalse()
  }

  @Test
  fun perTipoSiSommanoAlTotale() {
    val l = labels()
    val byKind = MemoriesEvalMetrics.byKind(l)
    assertThat(byKind.keys).containsExactly("otd", "trip")
    assertThat(byKind.getValue("otd").memories).isEqualTo(2)
    assertThat(byKind.getValue("trip").dontShow).isEqualTo(1)
    assertThat(byKind.values.fold(MemoriesEvalNumbers.ZERO) { a, b -> a + b }.faults).isEqualTo(MemoriesEvalMetrics.of(l).faults)
    assertThat(MemoriesEvalMetrics.of(l) { it.kind == "person" }.memories).isEqualTo(0)
  }

  @Test
  fun gliObiettiviDiSezione5_3() {
    val t = MemoriesEvalTargets()
    assertThat(t.good).isEqualTo(0.70)
    assertThat(t.dontShow).isEqualTo(0.03)
    assertThat(t.removePerPhoto).isWithin(1e-12).of(1.0 / 12)
    assertThat(t.duplicates).isEqualTo(0.03)
    // I limiti sono inclusivi: 70% di "Bello", 3% di "Da non mostrare", 3% di doppioni passano (1 foto su 12: un soffio sotto); un attimo oltre no.
    // 100 ricordi da 12 foto (1.200 foto); [mean] e' la quota media di foto da togliere, [dups] i doppioni segnati.
    fun numbers(good: Int, dontShow: Int, mean: Double, dups: Int = 0): MemoriesEvalNumbers = MemoriesEvalNumbers(
      100, good, 100 - good - dontShow, dontShow, 0, 0, 0, 1_200, dups, if (dups > 0) mapOf(EvalFault.DUPLICATE to dups) else emptyMap(), mean * 100, 0,
    )
    assertThat(numbers(70, 3, 1.0 / 12 - 1e-9, 36).meets(t)).isTrue()
    assertThat(numbers(69, 3, 1.0 / 12 - 1e-9, 36).meets(t)).isFalse()
    assertThat(numbers(70, 4, 1.0 / 12 - 1e-9, 36).meets(t)).isFalse()
    assertThat(numbers(70, 3, 1.0 / 12 + 0.001, 36).meets(t)).isFalse()
    assertThat(numbers(70, 3, 1.0 / 12 - 1e-9, 37).meets(t)).isFalse()
  }

  @Test
  fun lIntervalloDiWilsonCoincideConIConti() {
    // 38 su 40 = 95%: da 83,5% a 98,6% (lo stesso intervallo che usa la valutazione delle persone).
    val (low, high) = MemoriesEvalMetrics.wilson(38, 40)
    assertThat(low).isWithin(0.001).of(0.835)
    assertThat(high).isWithin(0.001).of(0.986)
    assertThat(MemoriesEvalMetrics.wilson(0, 50).first).isEqualTo(0.0)
    assertThat(MemoriesEvalMetrics.wilson(50, 50).second).isEqualTo(1.0)
    assertThat(MemoriesEvalMetrics.wilson(0, 0).first.isNaN()).isTrue()
  }

  @Test
  fun ilResocontoESoloNumeri() {
    val l = labels()
    val text = MemoriesEvalMetrics.report(l).toNumbersText()
    assertThat(text).contains("3 giudicati (1 senza giudizio)")
    assertThat(text).contains("Misura incompleta: 1 ricordi senza giudizio.")
    assertThat(text).contains("Bello: NON raggiunto (33.3%")
    assertThat(text).contains("Foto tristi: 4 (solo una misura: con Q6 (a) non c'e' nessun filtro)")
    assertThat(text).contains("motivo: triste 1, sbagliato 0, noioso 0")
    // Mai id, titoli, giorni: solo numeri.
    for (m in run) {
      assertThat(text).doesNotContain(m.id)
      assertThat(text).doesNotContain(m.title)
      assertThat(text).doesNotContain(m.day.toString())
      for (media in m.members) assertThat(text).doesNotContain(media)
    }
    assertThat(Regex("20[0-9]{2}-[0-9]{2}-[0-9]{2}").containsMatchIn(text)).isFalse()
    // Con tutto giudicato la misura e' completa.
    var done = l
    done = done.withJudgement(run[3].id, EvalVerdict.GOOD)
    val report = MemoriesEvalMetrics.report(done)
    assertThat(report.conclusive).isTrue()
    assertThat(report.toNumbersText()).contains("Misura completa.")
  }

  @Test
  fun laFinestraEIlTettoDeiRicordiDaGiudicare() {
    val today = LocalDate.of(2026, 10, 30)
    val days = MemoriesEvalDays.window(today)
    assertThat(days).hasSize(45)
    assertThat(days.first()).isEqualTo(today.minusDays(44))
    assertThat(days.last()).isEqualTo(today)
    assertThat(MemoriesEvalDays.window(today, 1)).containsExactly(today)
    // Una finestra senza senso e' un errore di chi chiama.
    org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { MemoriesEvalDays.window(today, 0) }
    org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { MemoriesEvalDays.window(today, 500) }
  }

  @Test
  fun laScelta_SenzaRipetizioniAlPiuIlTettoSempreLaStessa() {
    val many = (1..100).map { sampleMemory(it, size = 5) }
    val chosen = MemoriesEvalDays.select(many)
    assertThat(chosen).hasSize(MemoriesEvalDays.MAX_JUDGED)
    // Sempre la stessa, in qualunque ordine arrivino; in ordine di giorno.
    assertThat(MemoriesEvalDays.select(many.shuffled(java.util.Random(3))).map { it.id }).isEqualTo(chosen.map { it.id })
    assertThat(chosen.map { it.day }).isInOrder()
    // I scelti sono quelli con l'id piu' piccolo (l'id e' un hash: una scelta a caso).
    assertThat(chosen.map { it.id }.toSet()).isEqualTo(many.map { it.id }.sorted().take(MemoriesEvalDays.MAX_JUDGED).toSet())
    // Sotto il tetto restano tutti, una volta sola.
    assertThat(MemoriesEvalDays.select(many.take(10) + many.take(10))).hasSize(10)
    org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { MemoriesEvalDays.select(many, cap = 0) }
  }
}
