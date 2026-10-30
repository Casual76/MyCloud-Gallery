package dev.pampa.mycloudgallery.core.memories.eval

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/** Il file dei giudizi (`ricordi.mcgmem`): va e torna, e un file rovinato o ostile da' un errore con il numero della riga, mai il contenuto. */
class MemoriesEvalFormatTest {

  private val run = (1..3).map { sampleMemory(it, size = 6) }

  private fun full(): MemoriesLabelSet = MemoriesLabelSet().withRun(run)
    .withJudgement(run[0].id, EvalVerdict.GOOD)
    .withJudgement(run[1].id, EvalVerdict.DONT_SHOW, EvalWhy.SAD)
    .withFault(run[0].id, run[0].members[3], EvalFault.DUPLICATE)
    .withFault(run[1].id, run[1].members[0], EvalFault.SAD)
    .withFault(run[1].id, run[1].members[1], EvalFault.SAD)

  private fun same(a: MemoriesLabelSet, b: MemoriesLabelSet) {
    assertThat(b.run.map { it.id }).containsExactlyElementsIn(a.run.map { it.id }).inOrder()
    for ((x, y) in a.run.zip(b.run)) {
      assertThat(y.members).isEqualTo(x.members)
      assertThat(y.cover).isEqualTo(x.cover)
      assertThat(y.kind).isEqualTo(x.kind)
      assertThat(y.day).isEqualTo(x.day)
      assertThat(y.title).isEqualTo(x.title)
    }
    assertThat(b.judgements).isEqualTo(a.judgements)
    assertThat(b.faults).isEqualTo(a.faults)
  }

  @Test
  fun ilFileVaETorna() {
    val labels = full()
    val text = MemoriesLabelFormat.serialize(labels)
    assertThat(text).startsWith(MemoriesLabelFormat.MAGIC + "\n")
    same(labels, MemoriesLabelFormat.parse(text))
    // Anche vuoto, e con CRLF.
    assertThat(MemoriesLabelFormat.parse(MemoriesLabelFormat.serialize(MemoriesLabelSet())).run).isEmpty()
    same(labels, MemoriesLabelFormat.parse(text.replace("\n", "\r\n")))
  }

  @Test
  fun ilTitoloConBarreETestoCheSembraUnAcapoTornaUguale() {
    val odd = sampleMemory(9, title = "uno\\due \\t dopo \\n non e' un a-capo \\\\")
    val labels = MemoriesLabelSet().withRun(listOf(odd))
    assertThat(MemoriesLabelFormat.parse(MemoriesLabelFormat.serialize(labels)).run.single().title).isEqualTo(odd.title)
  }

  @Test
  fun lAltraIntestazioneONienteNonVa() {
    assertThrows(IllegalArgumentException::class.java) { MemoriesLabelFormat.parse("") }
    assertThrows(IllegalArgumentException::class.java) { MemoriesLabelFormat.parse("MCGMEM02\n") }
    assertThrows(IllegalArgumentException::class.java) { MemoriesLabelFormat.parse("MCGALB01\n") }
  }

  @Test
  fun unaRigaRovinataDaUnErroreConIlNumeroDellaRigaEMaiIlContenuto() {
    val good = MemoriesLabelFormat.serialize(full()).trimEnd('\n').split('\n')
    val member = run[0].members[3]
    val broken = listOf(
      "X\tsconosciuta",
      "R\tnon-un-id\totd\t2026-09-01\t${run[0].cover}\t${run[0].members.joinToString(",")}\ttitolo",
      "R\t${run[0].id}\totd\tnon-una-data\t${run[0].cover}\t${run[0].members.joinToString(",")}\ttitolo",
      "R\t${run[0].id}\totd\t2026-09-01\t${run[0].cover}\tsolo-uno\ttitolo",
      "M\t${run[0].id}\tZ\t-",
      "M\t${run[0].id}\tN\tq",
      "M\t${run[0].id}\tB",
      "F\t${run[0].id}\tnon-un-media\td",
      "F\t${run[0].id}\t$member\tw",
      "F\t${run[0].id}\t$member",
    )
    for (line in broken) {
      val error = assertThrows(IllegalArgumentException::class.java) { MemoriesLabelFormat.parse(good.take(1).joinToString("\n") + "\n" + line + "\n") }
      assertThat(error.message).contains("Riga 2")
      assertThat(error.message).doesNotContain(run[0].id)
      assertThat(error.message).doesNotContain(member)
      assertThat(error.message).doesNotContain("titolo")
    }
  }

  @Test
  fun leVociDoppieEIncoerentiSiRifiutano() {
    val head = MemoriesLabelFormat.serialize(MemoriesLabelSet().withRun(run))
    val id = run[0].id
    val media = run[0].members[2]
    // Un giudizio doppio, una foto segnata due volte.
    assertThrows(IllegalArgumentException::class.java) { MemoriesLabelFormat.parse(head + "M\t$id\tB\t-\nM\t$id\tC\t-\n") }
    assertThrows(IllegalArgumentException::class.java) { MemoriesLabelFormat.parse(head + "F\t$id\t$media\td\nF\t$id\t$media\tm\n") }
    // Un giudizio su un ricordo che non c'e', una foto che non e' del ricordo, un ricordo ripetuto: il file non e' coerente.
    val other = sampleMemoryId(77)
    val e1 = assertThrows(IllegalArgumentException::class.java) { MemoriesLabelFormat.parse(head + "M\t$other\tB\t-\n") }
    assertThat(e1.message).doesNotContain(other)
    assertThrows(IllegalArgumentException::class.java) { MemoriesLabelFormat.parse(head + "F\t$id\t${sampleMedia(9_999)}\td\n") }
    assertThrows(IllegalArgumentException::class.java) { MemoriesLabelFormat.parse(head + head.lines()[1] + "\n") }
  }

  @Test
  fun unFileOstileNonLanciaMaiAltroCheErroriDiDati() {
    // Righe tagliate o riempite a caso in ogni punto: o si legge, o si dice "riga non valida"; mai un'altra eccezione, mai un messaggio col contenuto.
    val text = MemoriesLabelFormat.serialize(full())
    val random = java.util.Random(5)
    repeat(400) {
      val cut = random.nextInt(text.length)
      val mutated = when (it % 3) {
        0 -> text.substring(0, cut)
        1 -> text.substring(0, cut) + "\t" + text.substring(cut)
        else -> text.substring(0, cut) + (random.nextInt(94) + 33).toChar() + text.substring(cut + 1)
      }
      try {
        MemoriesLabelFormat.parse(mutated)
      } catch (e: IllegalArgumentException) {
        assertThat(e.message).doesNotContain(run[0].id)
        assertThat(e.message).doesNotContain(run[0].title)
      }
    }
  }
}
