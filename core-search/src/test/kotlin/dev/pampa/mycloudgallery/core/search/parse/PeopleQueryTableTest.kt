package dev.pampa.mycloudgallery.core.search.parse

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.search.contract.PeopleDictionary
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Fase 8 (P10, D26, §2.7): **la tabella pubblica delle query con le persone**, una riga per test (`resources/parse/query-persone*.txt`, formato in
 * `LEGGIMI.txt`). Solo nomi inventati ([TestPeople]). Copre un nome solo, le congiunzioni ("e" = tutte, "o" = una qualunque, piu' nomi senza
 * congiunzione = tutte), i nomi di piu' parole, la corrispondenza piu' lunga rispetto ai luoghi, le ambiguita' con feste, mesi e luoghi (E-F36), i refusi
 * e le virgolette; e, con il dizionario vuoto, i nomi che restano testo.
 */
@RunWith(Parameterized::class)
class PeopleQueryTableTest(private val row: QueryRow) {

  @Test
  fun laRigaDellaTabella() {
    val parsed = ItalianQueryParser().parse(row.query, row.today, TestPeople.PLACES, TestPeople.DICTIONARY)
    val actual = QueryRenderer.columns(parsed)
    val names = listOf("chip", "contenuto", "parole", "testo esatto", "correzioni")
    for (k in actual.indices) {
      assertWithMessage("${names[k]} di $row").that(actual[k]).isEqualTo(row.expected[k])
    }
  }

  companion object {
    val FILES = listOf("query-persone.txt", "query-persone-ambiguita.txt", "query-persone-refusi.txt")

    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun rows(): List<Array<Any>> = FILES.flatMap { QueryTable.load(it) }.map { arrayOf(it) }
  }
}

/** Con il dizionario vuoto (Persone spenta, in attesa dell'avviso, esclusa) un nome e' testo: nessun chip "Persona", nessun errore (D26, E-F37). */
@RunWith(Parameterized::class)
class PeopleOffQueryTableTest(private val row: QueryRow) {

  @Test
  fun laRigaDellaTabella() {
    val parsed = ItalianQueryParser().parse(row.query, row.today, TestPeople.PLACES, PeopleDictionary.EMPTY)
    val actual = QueryRenderer.columns(parsed)
    val names = listOf("chip", "contenuto", "parole", "testo esatto", "correzioni")
    for (k in actual.indices) {
      assertWithMessage("${names[k]} di $row").that(actual[k]).isEqualTo(row.expected[k])
    }
    assertThat(parsed.chips.none { it is SearchChip.Person }).isTrue()
  }

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun rows(): List<Array<Any>> = QueryTable.load("query-persone-spente.txt").map { arrayOf(it) }
  }
}

class PeopleQueryTableShapeTest {

  /** La tabella e' pubblica e ha almeno 120 query con le persone (riga P10 del §4.4), piu' quelle con il dizionario vuoto. */
  @Test
  fun laTabellaHaAlmenoCentoventiQuery() {
    val withPeople = PeopleQueryTableTest.FILES.flatMap { QueryTable.load(it) }
    val off = QueryTable.load("query-persone-spente.txt")
    assertWithMessage("query con le persone").that(withPeople.size).isAtLeast(120)
    assertWithMessage("query con il dizionario vuoto").that(off.size).isAtLeast(10)
    assertThat(withPeople.map { it.query }.toSet().size).isAtLeast(100)
  }
}
