package dev.pampa.mycloudgallery.core.search.parse

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.search.contract.ParsedQuery
import dev.pampa.mycloudgallery.core.search.contract.PeopleDictionary
import dev.pampa.mycloudgallery.core.search.contract.PersonEntry
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import dev.pampa.mycloudgallery.core.search.contract.SearchText
import java.time.LocalDate
import java.util.concurrent.Executors
import kotlin.random.Random
import org.junit.Test

/**
 * Fase 8 (P10, D26): le proprieta' dell'analizzatore con le persone. Il dizionario vuoto non cambia niente (tutta la tabella di prima da' lo stesso risultato
 * con la firma nuova, e stringhe a caso pure); con il dizionario l'analisi e' deterministica anche fra thread, non lancia mai, e ogni chip "Persona" e' valido:
 * persone distinte, etichetta e parola coerenti con il testo scritto. Gli omonimi si risolvono sempre alla stessa persona.
 */
class PeopleParserPropertiesTest {
  private val parser = ItalianQueryParser()
  private val places = TestPeople.PLACES
  private val today = LocalDate.of(2026, 10, 21)

  /** Con il dizionario vuoto la firma nuova e' la firma di prima, riga per riga della tabella di prima. */
  @Test
  fun ilDizionarioVuotoNonCambiaLaTabellaDiPrima() {
    for (row in QueryTable.load()) {
      val old = parser.parse(row.query, row.today, TestPlaces.DICTIONARY)
      val new = parser.parse(row.query, row.today, TestPlaces.DICTIONARY, PeopleDictionary.EMPTY)
      assertWithMessage(row.toString()).that(new).isEqualTo(old)
    }
  }

  /** Un dizionario con nomi che non compaiono mai nella tabella di prima non cambia il risultato di nessuna riga (i nomi sono solo i loro). */
  @Test
  fun unDizionarioDiNomiLontaniNonCambiaLaTabellaDiPrima() {
    val far = PeopleDictionary(listOf(PersonEntry("zefiro", TestPeople.id(40), "Zefiro", 9), PersonEntry("olimpia strega", TestPeople.id(41), "Olimpia Strega", 4)))
    for (row in QueryTable.load()) {
      assertWithMessage(row.toString()).that(parser.parse(row.query, row.today, TestPlaces.DICTIONARY, far)).isEqualTo(parser.parse(row.query, row.today, TestPlaces.DICTIONARY))
    }
  }

  @Test
  fun deterministicoAncheFraThread() {
    val rows = PeopleQueryTableTest.FILES.flatMap { QueryTable.load(it) }
    val first = rows.map { parser.parse(it.query, it.today, places, TestPeople.DICTIONARY) }
    assertThat(rows.map { ItalianQueryParser().parse(it.query, it.today, places, TestPeople.DICTIONARY) }).isEqualTo(first)
    val pool = Executors.newFixedThreadPool(4)
    try {
      val concurrent = rows.map { row -> pool.submit<ParsedQuery> { parser.parse(row.query, row.today, places, TestPeople.DICTIONARY) } }.map { it.get() }
      assertThat(concurrent).isEqualTo(first)
    } finally {
      pool.shutdown()
    }
  }

  /** Stringhe casuali di pezzi di query con nomi, congiunzioni, feste, luoghi e refusi: mai un'eccezione, sempre chip validi e coerenti. */
  @Test
  fun stringheCasualiConINomiNonRompononiente() {
    val pieces = listOf(
      "marco", "anna", "giulia", "luca", "sara", "maria", "teresa", "gian", "nicolo", "d", "amico", "nonna", "rosa", "zio", "piero", "natale", "marina", "roma",
      "lucca", "aprile", "maggio", "bianchi", "stefano", "santo", "e", "o", "ed", "con", "oppure", "di", "a", "foto", "video", "ieri", "2024", "agosto", "firenze",
      "san", "marco", "monaco", "baviera", "marko", "giulai", "chiarra", "annna", ",", "&", "-", "/", "'", "\"", "«", "»", "😀", " ", "  ",
    )
    val random = Random(8)
    var withPerson = 0
    repeat(4000) {
      val text = (0 until random.nextInt(1, 14)).joinToString(if (random.nextBoolean()) " " else "") { pieces[random.nextInt(pieces.size)] }
      val parsed = parser.parse(text, today, places, TestPeople.DICTIONARY)
      assertWithMessage("«$text»").that(parser.parse(text, today, places, TestPeople.DICTIONARY)).isEqualTo(parsed)
      for (chip in parsed.chips.filterIsInstance<SearchChip.Person>()) {
        withPerson++
        val ids = chip.option.ids
        assertWithMessage("«$text»: persone distinte").that(ids.toSet().size).isEqualTo(ids.size)
        if (ids.size == 1) assertThat(chip.option.mode).isEqualTo(PeopleMode.ANY)
        assertWithMessage("«$text»: l'etichetta ha tante persone quante gli id").that(labelPeople(chip.label)).isAtLeast(ids.size)
        assertThat(chip.alternatives.map { it.chip }.last()).isEqualTo(SearchChip.Word(chip.word, exact = false))
        assertThat(chip.word).isEqualTo(SearchText.normalize(chip.word))
      }
      assertThat(parsed.words.none { it in SearchText.STOPWORDS }).isTrue()
      if (parsed.semantic == null) assertThat(parsed.words).isEmpty()
      // Il dizionario vuoto non produce mai un chip "Persona" (E-F37).
      assertThat(parser.parse(text, today, places, PeopleDictionary.EMPTY).chips.none { it is SearchChip.Person }).isTrue()
    }
    assertThat(withPerson).isGreaterThan(500)
  }

  private fun labelPeople(label: String): Int = label.split(" e ", " o ", ", ").size

  /** Fra omonimi vince il piu' frequente, a pari numero l'id minore: sempre la stessa persona (deterministico). */
  @Test
  fun gliOmonimiSiRisolvonoSempreAllaStessaPersona() {
    val a = TestPeople.id(50)
    val b = TestPeople.id(51)
    val c = TestPeople.id(52)
    for (order in listOf(listOf(a, b, c), listOf(c, b, a), listOf(b, a, c))) {
      val entries = order.map { PersonEntry("dario", it, "Dario", if (it == c) 9 else 4) } + PersonEntry("dario", TestPeople.id(53), "Dario", 9)
      val chip = parser.parse("Dario", today, places, PeopleDictionary(entries)).chips.single() as SearchChip.Person
      // c e 53 hanno 9 media: vince l'id minore dei due.
      assertThat(chip.option.ids).containsExactly(c)
    }
  }

  /** Una parola sola per tante persone con lo stesso nome: il chip e' uno, mai una persona per omonimo. */
  @Test
  fun unNomeAPiuPersoneDaUnChipSolo() {
    val entries = listOf(PersonEntry("dario", TestPeople.id(60), "Dario", 3), PersonEntry("dario", TestPeople.id(61), "Dario", 2))
    assertThat(parser.parse("Dario e Dario", today, places, PeopleDictionary(entries)).chips).hasSize(1)
  }
}
