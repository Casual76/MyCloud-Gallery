package dev.pampa.mycloudgallery.core.faces.eval

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceBox
import dev.pampa.mycloudgallery.core.model.FaceLocator
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Le etichette della valutazione delle persone (P11): il formato del file va e torna, un file rovinato o ostile da' un errore con il numero della
 * riga e mai il contenuto, nessun id e nessun posto passa da un `toString()`. Solo id e posti inventati.
 */
class FacesEvalLabelsTest {
  private val a = "11111111-1111-4111-8111-111111111111"
  private val b = "22222222-2222-4222-8222-222222222222"
  private val c = "33333333-3333-4333-8333-333333333333"

  private fun locator(n: Int, frame: Int = 0): FaceLocator {
    val hex = "%064x".format(n.toLong() * 7919L + 13L)
    return FaceLocator(ContentKey(hex), frame, FaceBox(1_000 + n, 2_000, 9_000 + n, 12_000))
  }

  private fun sample(): FacesLabelSet {
    val first = SessionLabels(EvalSessionKind.CALIBRATION, 77L, EvalTotals(900, 600, 300, 100, 31), emptyList())
      .withGroup(GroupLabel(a, 40, named = true, shown = 40, reviewed = true, wrong = setOf(locator(1), locator(2, frame = 3)), sameAs = setOf(c)))
      .withGroup(GroupLabel(b, 12, named = false, shown = 12, reviewed = true, mixed = true))
      .withGroup(GroupLabel(c, 9, named = false, shown = 9))
    val second = SessionLabels(EvalSessionKind.MEASURE, -5L, EvalTotals(1_000, 700, 200, 80, 45), listOf(GroupLabel(a, 41, true, 41, true))).closed()
    return FacesLabelSet().with(first).with(second)
  }

  @Test
  fun ilFileVaETorna() {
    val labels = sample()
    val text = FacesLabelFormat.serialize(labels)
    assertThat(text).startsWith(FacesLabelFormat.MAGIC + "\n")
    val back = FacesLabelFormat.parse(text)
    assertThat(FacesLabelFormat.serialize(back)).isEqualTo(text)
    val one = back.session(EvalSessionKind.CALIBRATION)!!
    assertThat(one.seed).isEqualTo(77L)
    assertThat(one.totals).isEqualTo(EvalTotals(900, 600, 300, 100, 31))
    assertThat(one.group(a)!!.wrong).containsExactly(locator(1), locator(2, frame = 3))
    assertThat(one.group(a)!!.sameAs).containsExactly(c)
    assertThat(one.group(b)!!.mixed).isTrue()
    assertThat(one.group(c)!!.reviewed).isFalse()
    assertThat(back.session(EvalSessionKind.MEASURE)!!.closed).isTrue()
    assertThat(back.session(EvalSessionKind.MEASURE)!!.seed).isEqualTo(-5L)
  }

  @Test
  fun unFileVuotoEUnaSessioneSenzaGruppiVannoETornano() {
    assertThat(FacesLabelFormat.parse(FacesLabelFormat.serialize(FacesLabelSet())).isEmpty).isTrue()
    val empty = FacesLabelSet().with(SessionLabels(EvalSessionKind.MEASURE, 1L, EvalTotals.EMPTY, emptyList()))
    assertThat(FacesLabelFormat.parse(FacesLabelFormat.serialize(empty)).session(EvalSessionKind.MEASURE)!!.groups).isEmpty()
  }

  @Test
  fun unFileRovinatoDaUnErroreConIlNumeroDellaRigaEMaiIlContenuto() {
    val valid = FacesLabelFormat.serialize(sample()).lines().filter { it.isNotEmpty() }
    val bad = listOf(
      "X\t1\tciao",
      "S\t9\t1\t0\t0\t0\t0\t0\t0",
      "S\t1\tnon-un-numero\t0\t0\t0\t0\t0\t0",
      "S\t1\t5\t3\t9\t0\t0\t0\t0",
      "G\t1\tnon-una-persona\t3\t1\t3\t-",
      "W\t1\t$a\tnon-un-posto",
      "E\t1\t$a\tnon-una-persona",
      "G\t1\t$a\t3\t2\t3\tz",
    )
    for (line in bad) {
      val lines = valid.take(1) + listOf("S\t1\t5\t0\t0\t0\t0\t0\t0") + line
      val e = assertThrows(IllegalArgumentException::class.java) { FacesLabelFormat.parse(lines.joinToString("\n")) }
      assertThat(e.message).contains("Riga")
      assertThat(e.message).doesNotContain(a)
      assertThat(e.message).doesNotContain("ciao")
    }
    assertThrows(IllegalArgumentException::class.java) { FacesLabelFormat.parse("MCGEVAL1\nQ\tq1\tcane") }
    assertThrows(IllegalArgumentException::class.java) { FacesLabelFormat.parse("") }
  }

  @Test
  fun ilNumeroDellaRigaEQuelloGiusto() {
    val text = "${FacesLabelFormat.MAGIC}\nS\t1\t5\t0\t0\t0\t0\t0\t0\nG\t1\t$a\t3\t0\t3\t-\nE\t1\t$a\t$a\n"
    val e = assertThrows(IllegalArgumentException::class.java) { FacesLabelFormat.parse(text) }
    assertThat(e.message).contains("Riga 4")
  }

  @Test
  fun unGruppoDoppioOUnaSessioneDoppiaSonoRifiutati() {
    val dup = "${FacesLabelFormat.MAGIC}\nS\t1\t5\t0\t0\t0\t0\t0\t0\nG\t1\t$a\t3\t0\t3\t-\nG\t1\t$a\t3\t0\t3\t-\n"
    assertThrows(IllegalArgumentException::class.java) { FacesLabelFormat.parse(dup) }
    val twice = "${FacesLabelFormat.MAGIC}\nS\t1\t5\t0\t0\t0\t0\t0\t0\nS\t1\t5\t0\t0\t0\t0\t0\t0\n"
    assertThrows(IllegalArgumentException::class.java) { FacesLabelFormat.parse(twice) }
  }

  @Test
  fun unGruppoMistoNonHaVoltiSegnatiUnoAUno() {
    assertThrows(IllegalArgumentException::class.java) { GroupLabel(a, 5, false, 5, reviewed = true, mixed = true, wrong = setOf(locator(1))) }
    assertThrows(IllegalArgumentException::class.java) { GroupLabel(a, 5, false, sameAs = setOf(a)) }
    assertThrows(IllegalArgumentException::class.java) { GroupLabel("ciao", 5, false) }
    assertThrows(IllegalArgumentException::class.java) { GroupLabel(a, 5, false, sameAs = setOf("ciao")) }
  }

  @Test
  fun leTotaliNonHannoPiuAssegnatiCheVolti() {
    assertThrows(IllegalArgumentException::class.java) { EvalTotals(10, 11, 0, 0, 1) }
    assertThrows(IllegalArgumentException::class.java) { EvalTotals(-1, 0, 0, 0, 1) }
    assertThat(EvalTotals(10, 4, 0, 0, 1).coreCoverage).isWithin(1e-12).of(0.4)
    assertThat(EvalTotals.EMPTY.coreCoverage).isEqualTo(1.0)
  }

  @Test
  fun unaSessioneChiusaNonSiCambiaEUnGruppoSiSostituisce() {
    val s = SessionLabels(EvalSessionKind.CALIBRATION, 1L, EvalTotals.EMPTY, listOf(GroupLabel(a, 5, false)))
    val changed = s.withGroup(GroupLabel(a, 5, false, 5, reviewed = true))
    assertThat(changed.groups).hasSize(1)
    assertThat(changed.group(a)!!.pure).isTrue()
    assertThat(s.group(a)!!.pure).isFalse()
    assertThrows(IllegalStateException::class.java) { changed.closed().withGroup(GroupLabel(b, 5, false)) }
  }

  @Test
  fun nessunIdENessunPostoPassaDaUnToString() {
    val labels = sample()
    val text = listOf(labels.toString()) + labels.sessions.values.map { it.toString() } + labels.sessions.values.flatMap { s -> s.groups.map { it.toString() } } +
      locator(1).toString()
    for (t in text) {
      assertThat(t).doesNotContain(a)
      assertThat(t).doesNotContain(b)
      assertThat(t).doesNotContain(locator(1).contentKey.hex.take(8))
    }
  }

  @Test
  fun iNumeriDelleSessioniSonoDeiCodiciStabili() {
    assertThat(EvalSessionKind.entries.map { it.number }).containsExactly(1, 2).inOrder()
    assertThat(EvalSessionKind.fromNumber(2)).isEqualTo(EvalSessionKind.MEASURE)
    assertThat(EvalSessionKind.fromNumber(3)).isNull()
  }
}
