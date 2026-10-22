package dev.pampa.mycloudgallery.core.search.eval

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Il formato del file di etichette (solo sul telefono) e l'errore per carattere del set OCR sul telefono (stessi numeri del PC). */
class EvalLabelsAndOcrTest {

  private fun key(i: Int) = "%064x".format(i)

  private fun sample(): LabelSet = LabelSet()
    .withQuery(EvalQuery("q2", EvalCategory.TEXT, "scontrino con\tun tab e un \\ strano"))
    .withQuery(EvalQuery("q1", EvalCategory.OBJECTS, "il mio cane"))
    .withExpected("q1", key(2), true)
    .withExpected("q1", key(1), true)
    .withExpected("q2", key(3), true)
    .withDocument(key(9), true)
    .withDocument(key(8), false)

  @Test
  fun leEtichetteTornanoUgualiDopoIlFile() {
    val labels = sample()
    val text = LabelFormat.serialize(labels)
    assertThat(text).startsWith(LabelFormat.MAGIC + "\n")
    val back = LabelFormat.parse(text)
    assertThat(back.queries.map { it.id }).containsExactly("q1", "q2").inOrder()
    assertThat(back.query("q2")?.text).isEqualTo("scontrino con\tun tab e un \\ strano")
    assertThat(back.expectedOf("q1")).containsExactly(key(1), key(2))
    assertThat(back.expectedOf("q2")).containsExactly(key(3))
    assertThat(back.documents).containsExactly(key(9), true, key(8), false)
    // Il file e' deterministico: serializzarlo di nuovo da' lo stesso testo.
    assertThat(LabelFormat.serialize(back)).isEqualTo(text)
  }

  @Test
  fun ilToStringNonDaMaiQueryNeChiavi() {
    val labels = sample()
    val shown = labels.toString() + labels.queries.joinToString { it.toString() }
    assertThat(shown).doesNotContain("cane")
    assertThat(shown).doesNotContain("scontrino")
    assertThat(shown).doesNotContain(key(1))
    assertThat(labels.toString()).contains("2 query")
  }

  @Test
  fun unFileRovinatoDaUnErroreConIlNumeroDellaRigaESenzaContenuto() {
    val good = LabelFormat.serialize(sample())
    val lines = good.split('\n').toMutableList()
    lines[2] = "E\tq1\tnon-una-chiave-privata"
    val error = runCatching { LabelFormat.parse(lines.joinToString("\n")) }.exceptionOrNull()
    assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    assertThat(error?.message).contains("Riga 3")
    assertThat(error?.message).doesNotContain("non-una-chiave-privata")
    assertThat(runCatching { LabelFormat.parse("altro\nQ\tq1\toggetti\tcane") }.isFailure).isTrue()
    assertThat(runCatching { LabelFormat.parse("${LabelFormat.MAGIC}\nQ\tq1\tcategoria-falsa\tcane") }.isFailure).isTrue()
    assertThat(runCatching { LabelFormat.parse("${LabelFormat.MAGIC}\nE\tqx\t${key(1)}") }.isFailure).isTrue()
  }

  @Test
  fun leModificheNonCambianoLEtichetteGia() {
    val a = sample()
    val b = a.withExpected("q1", key(1), false).withoutQuery("q2").withDocument(key(9), null)
    assertThat(a.expectedOf("q1")).containsExactly(key(1), key(2))
    assertThat(b.expectedOf("q1")).containsExactly(key(2))
    assertThat(b.query("q2")).isNull()
    assertThat(b.expectedOf("q2")).isEmpty()
    assertThat(b.documents.keys).containsExactly(key(8))
  }

  @Test
  fun leRegoleDelleQueryEDelleChiavi() {
    assertThat(runCatching { EvalQuery("Q 1", EvalCategory.TEXT, "x") }.isFailure).isTrue()
    assertThat(runCatching { EvalQuery("q1", EvalCategory.TEXT, "  ") }.isFailure).isTrue()
    assertThat(runCatching { EvalQuery("q1", EvalCategory.TEXT, "x".repeat(201)) }.isFailure).isTrue()
    assertThat(runCatching { sample().withExpected("q1", "ABC", true) }.isFailure).isTrue()
    assertThat(runCatching { sample().withExpected("zz", key(1), true) }.isFailure).isTrue()
    var many = LabelSet()
    repeat(LabelSet.MAX_QUERIES) { many = many.withQuery(EvalQuery("q$it", EvalCategory.SCENES, "x $it")) }
    assertThat(runCatching { many.withQuery(EvalQuery("extra", EvalCategory.SCENES, "x")) }.isFailure).isTrue()
    // Una query esistente si puo' riscrivere anche a tetto raggiunto.
    assertThat(many.withQuery(EvalQuery("q0", EvalCategory.TEXT, "nuovo")).queries).hasSize(LabelSet.MAX_QUERIES)
  }

  // --- OCR ------------------------------------------------------------------------------------------------------------------

  private val manifest = "id\tfile\tgenere\tlarghezza\tpx\tdegradazione\ttesto\n" +
    "a\timmagini/a.jpg\tscontrino\t1024\t12\tpulita\tTotale € 24,50\\nCaffè espresso\n" +
    "b\timmagini/b.jpg\tchat\t2048\t20\tsfocata\tCi vediamo alle 18:30\n"

  @Test
  fun ilManifestoOcrSiLeggeEIlTestoLettoSiConfronta() {
    val cases = EvalOcr.parseManifest(manifest)
    assertThat(cases.map { it.id }).containsExactly("a", "b").inOrder()
    assertThat(cases[0].truth).isEqualTo("Totale € 24,50\nCaffè espresso")
    assertThat(cases[1].px).isEqualTo(20)
    val read = EvalOcr.parseRead(EvalOcr.formatRead("a", "Totale  € 24,50\nCaffe espresso") + EvalOcr.formatRead("b", "Ci vediamo alle 18:30"))
    val a = EvalOcr.score(cases[0].truth, read.getValue("a"))
    // "Caffè" letto "Caffe": un carattere su 29.
    assertThat(a.edits).isEqualTo(1)
    assertThat(a.characterErrorRate).isWithin(1e-9).of(1.0 / 29)
    assertThat(a.wordRecall).isWithin(1e-9).of(1.0)
    assertThat(EvalOcr.score(cases[1].truth, read.getValue("b")).characterErrorRate).isEqualTo(0.0)
  }

  @Test
  fun ilResocontoOcrDaSoloNumeriEContaLeImmaginiNonLette() {
    val cases = EvalOcr.parseManifest(manifest)
    val report = EvalOcr.report(cases, mapOf("a" to "Totale € 24,50 Caffè espresso"), "lettore di prova")
    assertThat(report).contains("Lettore: lettore di prova")
    assertThat(report).contains("senza lettura: 1")
    assertThat(report).contains("1024 px 12")
    assertThat(report).contains("sfocata")
    assertThat(report).doesNotContain("Totale")
    assertThat(report).doesNotContain("Caffè")
    assertThat(EvalOcr.score("abcd", "").characterErrorRate).isEqualTo(1.0)
  }

  @Test
  fun laRicercaPerParolaUsaLaStessaNormalizzazioneDellaRicerca() {
    assertThat(EvalOcr.normalize("Più € 3,99 dell'Esselunga")).isEqualTo("piu euro 3 99 dell esselunga")
    // La normalizzazione e' quella di core-search (stessa funzione di `SearchText.normalize`).
    for (text in listOf("Caffè € 1,10", "L'offerta vale dal lunedì", "ÈCCO  il TOTALE!")) {
      assertThat(EvalOcr.normalize(text)).isEqualTo(dev.pampa.mycloudgallery.core.search.contract.SearchText.normalize(text))
    }
  }
}
