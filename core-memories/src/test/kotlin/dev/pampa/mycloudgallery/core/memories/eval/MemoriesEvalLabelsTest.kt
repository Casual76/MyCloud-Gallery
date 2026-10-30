package dev.pampa.mycloudgallery.core.memories.eval

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assert.assertThrows
import org.junit.Test

/** I giudizi e il ricordo da giudicare (docs/fase9-progetto.md §5.3): le regole che il file e lo strumento danno per vere. */
class MemoriesEvalLabelsTest {

  private val run = (1..4).map { sampleMemory(it) }

  @Test
  fun unRicordoVuoleUnIdVeroEdElementiValidi() {
    assertThrows(IllegalArgumentException::class.java) { EvalMemory("non-un-id", "otd", java.time.LocalDate.of(2026, 1, 1), sampleMedia(1), List(5) { sampleMedia(it + 1) }, "t") }
    assertThrows(IllegalArgumentException::class.java) { sampleMemory(1, kind = "sconosciuto") }
    assertThrows(IllegalArgumentException::class.java) { sampleMemory(1, size = 2) }
    assertThrows(IllegalArgumentException::class.java) { sampleMemory(1, size = 41) }
    assertThrows(IllegalArgumentException::class.java) { sampleMemory(1, title = "a\nb") }
    // La copertina e' uno degli elementi, gli elementi non si ripetono e sono UUID.
    val members = List(5) { sampleMedia(it + 1) }
    assertThrows(IllegalArgumentException::class.java) { EvalMemory(sampleMemoryId(1), "otd", java.time.LocalDate.of(2026, 1, 1), sampleMedia(99), members, "t") }
    assertThrows(IllegalArgumentException::class.java) { EvalMemory(sampleMemoryId(1), "otd", java.time.LocalDate.of(2026, 1, 1), members[0], members + members[0], "t") }
    assertThrows(IllegalArgumentException::class.java) { EvalMemory(sampleMemoryId(1), "otd", java.time.LocalDate.of(2026, 1, 1), "x", listOf("x", "y", "z"), "t") }
  }

  @Test
  fun unaSessioneNuovaNonHaGiudizi() {
    val labels = MemoriesLabelSet().withRun(run)
    assertThat(labels.run).hasSize(4)
    assertThat(labels.judged).isEmpty()
    assertThat(labels.pending).hasSize(4)
    // Ricominciare scarta giudizi e foto segnate di prima.
    val again = labels.withJudgement(run[0].id, EvalVerdict.GOOD).withFault(run[0].id, run[0].members[1], EvalFault.BLURRY_OR_DARK).withRun(run.take(2))
    assertThat(again.judgements).isEmpty()
    assertThat(again.faults).isEmpty()
  }

  @Test
  fun unaSessioneHaAlPiuIlSuoTettoESenzaRipetizioni() {
    val many = (1..130).map { sampleMemory(it, size = 3) }
    assertThat(MemoriesLabelSet().withRun(many).run).hasSize(MemoriesLabelSet.MAX_RUN)
    assertThat(MemoriesLabelSet().withRun(run + run).run).hasSize(4)
  }

  @Test
  fun ilGiudizioEUnTocco_ConUnMotivoSoloPerDaNonMostrare() {
    val labels = MemoriesLabelSet().withRun(run)
      .withJudgement(run[0].id, EvalVerdict.GOOD)
      .withJudgement(run[1].id, EvalVerdict.DONT_SHOW, EvalWhy.SAD)
      .withJudgement(run[2].id, EvalVerdict.SO_SO)
    assertThat(labels.judged.map { it.id }).containsExactly(run[0].id, run[1].id, run[2].id).inOrder()
    assertThat(labels.judgement(run[1].id)!!.why).isEqualTo(EvalWhy.SAD)
    assertThat(labels.pending.map { it.id }).containsExactly(run[3].id)
    assertThrows(IllegalArgumentException::class.java) { labels.withJudgement(run[0].id, EvalVerdict.GOOD, EvalWhy.BORING) }
    assertThrows(IllegalArgumentException::class.java) { labels.withJudgement(sampleMemoryId(77), EvalVerdict.GOOD) }
    // Cambiare il tocco toglie il motivo di prima; togliere il giudizio riporta il ricordo fra quelli da guardare.
    val changed = labels.withJudgement(run[1].id, EvalVerdict.GOOD)
    assertThat(changed.judgement(run[1].id)!!.why).isNull()
    assertThat(changed.withoutJudgement(run[1].id).pending.map { it.id }).contains(run[1].id)
  }

  @Test
  fun leFotoDaTogliereSonoDelRicordoEUnaSolaVolta() {
    val memory = run[0]
    var labels = MemoriesLabelSet().withRun(run).withFault(memory.id, memory.members[2], EvalFault.DUPLICATE)
    assertThat(labels.faultsOf(memory.id)).containsExactly(memory.members[2], EvalFault.DUPLICATE)
    // Un secondo motivo per la stessa foto sostituisce il primo.
    labels = labels.withFault(memory.id, memory.members[2], EvalFault.SAD)
    assertThat(labels.faultsOf(memory.id)).containsExactly(memory.members[2], EvalFault.SAD)
    // Una foto che non e' del ricordo non si segna; un segno si toglie con null; senza segni il ricordo non ha voce.
    assertThrows(IllegalArgumentException::class.java) { labels.withFault(memory.id, sampleMedia(9_999), EvalFault.UNRELATED) }
    assertThrows(IllegalArgumentException::class.java) { labels.withFault(sampleMemoryId(77), memory.members[0], EvalFault.UNRELATED) }
    assertThat(labels.withFault(memory.id, memory.members[2], null).faults).isEmpty()
    assertThat(labels.withFault(memory.id, memory.members[0], EvalFault.UNRELATED).withoutFaults(memory.id).faults).isEmpty()
  }

  @Test
  fun unNascostoEsceDalRicordoEDalleMisure() {
    val a = run[0]
    val b = run[1]
    val c = run[2]
    val labels = MemoriesLabelSet().withRun(run)
      .withJudgement(a.id, EvalVerdict.GOOD).withFault(a.id, a.members[1], EvalFault.DUPLICATE).withFault(a.id, a.members[5], EvalFault.SAD)
      .withJudgement(b.id, EvalVerdict.SO_SO).withFault(b.id, b.members[0], EvalFault.UNRELATED)
      .withJudgement(c.id, EvalVerdict.GOOD)
    // Un nascosto del ricordo `a` (una foto segnata, e la copertina), tutti i media di `b` tranne due (il ricordo scende sotto 3 elementi).
    val hidden = setOf(a.members[1], a.cover) + b.members.drop(2)
    val after = labels.withVisibleOnly { it !in hidden }
    assertThat(after.memory(a.id)!!.members).containsNoneIn(hidden)
    assertThat(after.memory(a.id)!!.members).hasSize(a.members.size - 2)
    assertThat(after.memory(a.id)!!.cover).isEqualTo(a.members[2])
    // La foto segnata che e' uscita perde il segno; l'altra resta.
    assertThat(after.faultsOf(a.id)).containsExactly(a.members[5], EvalFault.SAD)
    // Il ricordo sceso sotto 3 elementi esce con il suo giudizio e le sue foto segnate; quello intatto e' lo stesso oggetto.
    assertThat(after.memory(b.id)).isNull()
    assertThat(after.judgement(b.id)).isNull()
    assertThat(after.faultsOf(b.id)).isEmpty()
    assertThat(after.memory(c.id)).isSameInstanceAs(c.let { labels.memory(it.id) })
    assertThat(after.judgement(c.id)!!.verdict).isEqualTo(EvalVerdict.GOOD)
    // Nelle metriche non c'e' piu' nessuna foto nascosta.
    val photos = MemoriesEvalMetrics.of(after).photos
    assertThat(photos).isEqualTo(after.judged.sumOf { it.members.size })
    assertThat(after.judged.flatMap { it.members }).containsNoneIn(hidden)
    // Senza nascosti non cambia niente.
    assertThat(labels.withVisibleOnly { true }.run.map { it.id }).isEqualTo(run.map { it.id })
  }

  @Test
  fun leEtichetteIncoerentiSiRifiutano() {
    val memory = run[0]
    assertThrows(IllegalArgumentException::class.java) { MemoriesLabelSet(run, judgements = mapOf(sampleMemoryId(77) to EvalJudgement(EvalVerdict.GOOD))) }
    assertThrows(IllegalArgumentException::class.java) { MemoriesLabelSet(run, faults = mapOf(sampleMemoryId(77) to mapOf(memory.members[0] to EvalFault.SAD))) }
    assertThrows(IllegalArgumentException::class.java) { MemoriesLabelSet(run, faults = mapOf(memory.id to mapOf(sampleMedia(9_999) to EvalFault.SAD))) }
    assertThrows(IllegalArgumentException::class.java) { MemoriesLabelSet(run + run[0]) }
  }

  @Test
  fun iCodiciDelFormatoSonoStabiliEUnici() {
    for (codes in listOf(EvalVerdict.entries.map { it.code }, EvalWhy.entries.map { it.code }, EvalFault.entries.map { it.code })) {
      assertThat(codes.toSet()).hasSize(codes.size)
    }
    assertThat(EvalVerdict.entries.map { it.code }).containsExactly('B', 'C', 'N').inOrder()
    assertThat(EvalWhy.entries.map { it.code }).containsExactly('t', 's', 'n').inOrder()
    assertThat(EvalFault.entries.map { it.code }).containsExactly('d', 'm', 's', 'x', 't').inOrder()
    for (kind in MemoriesEvalKinds.CODES) assertThat(MemoriesEvalKinds.label(kind)).isNotEmpty()
  }

  @Test
  fun nienteDiPrivatoInUnToString() {
    val memory = run[0]
    val labels = MemoriesLabelSet().withRun(run).withJudgement(memory.id, EvalVerdict.DONT_SHOW, EvalWhy.SAD).withFault(memory.id, memory.members[1], EvalFault.SAD)
    val text = listOf(memory.toString(), labels.toString(), labels.judgement(memory.id).toString())
    for (line in text) {
      assertWithMessage(line).that(line).doesNotContain(memory.id)
      assertThat(line).doesNotContain(memory.members[1])
      assertThat(line).doesNotContain(memory.title)
      assertThat(line).doesNotContain(memory.day.toString())
    }
    assertThat(labels.toString()).contains("4 ricordi")
  }
}
