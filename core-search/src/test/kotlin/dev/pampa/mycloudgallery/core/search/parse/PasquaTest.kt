package dev.pampa.mycloudgallery.core.search.parse

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.search.contract.PlaceDictionary
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import java.time.LocalDate
import org.junit.Test

/**
 * P7: Pasqua e Pasquetta dal 2015 al 2035 contro una tabella di date **scritta a mano** (`resources/parse/pasqua-2015-2035.txt`),
 * non contro un secondo calcolo con lo stesso algoritmo.
 */
class PasquaTest {
  private class Year(val year: Int, val easter: LocalDate, val monday: LocalDate)

  private val table: List<Year> = run {
    val stream = PasquaTest::class.java.getResourceAsStream("/parse/pasqua-2015-2035.txt") ?: error("Manca la tabella di Pasqua")
    stream.bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map {
      val p = it.trim().split(' ')
      Year(p[0].toInt(), LocalDate.parse(p[1]), LocalDate.parse(p[2]))
    }
  }

  private val parser = ItalianQueryParser()
  private val today = QueryTable.DEFAULT_TODAY

  private fun single(text: String, on: LocalDate = today): SearchChip.DateRange {
    val chips = parser.parse(text, on, PlaceDictionary.EMPTY).chips
    assertWithMessage("«$text»").that(chips).hasSize(1)
    return chips.single() as SearchChip.DateRange
  }

  @Test
  fun latabellaCopreDalDuemilaQuindiciAlDuemilaTrentacinque() {
    assertThat(table.map { it.year }).containsExactlyElementsIn(2015..2035).inOrder()
    for (row in table) {
      assertThat(row.easter.dayOfWeek.name).isEqualTo("SUNDAY")
      assertThat(row.monday).isEqualTo(row.easter.plusDays(1))
    }
  }

  @Test
  fun pasquaEPasquettaConL_annoSonoQuelleDellaTabella() {
    for (row in table) {
      val pasqua = single("Pasqua ${row.year}")
      assertWithMessage("Pasqua ${row.year}").that(pasqua.from).isEqualTo(row.easter)
      assertThat(pasqua.to).isEqualTo(row.easter)
      assertThat(pasqua.everyYear).isFalse()
      val pasquetta = single("Pasquetta ${row.year}")
      assertWithMessage("Pasquetta ${row.year}").that(pasquetta.from).isEqualTo(row.monday)
      assertThat(pasquetta.to).isEqualTo(row.monday)
    }
  }

  @Test
  fun l_algoritmoDelCalendarioDaLeStesseDateDellaTabella() {
    for (row in table) assertWithMessage("${row.year}").that(ItalianCalendar.easter(row.year)).isEqualTo(row.easter)
  }

  /** "Scorsa": l'ultima gia' passata (il giorno stesso non conta). */
  @Test
  fun scorsaEL_ultimaGiaPassata() {
    for (row in table.drop(1)) {
      val previous = table.first { it.year == row.year - 1 }
      assertThat(single("Pasqua scorsa", row.easter).from).isEqualTo(previous.easter)
      assertThat(single("Pasqua scorsa", row.easter.plusDays(1)).from).isEqualTo(row.easter)
    }
  }

  /**
   * Senza anno: **tutte** le Pasque (P6, richiesta di P7): un insieme con il giorno di ogni anno dal 1900 a quest'anno, che
   * contiene ogni riga della tabella fino a oggi e nient'altro che domeniche (lunedi' per Pasquetta).
   */
  @Test
  fun senzaAnnoSonoTutteLePasque() {
    val on = LocalDate.of(2035, 12, 31)
    val pasqua = set("Pasqua", on)
    val pasquetta = set("Pasquetta", on)
    assertThat(pasqua.days).hasSize(2035 - 1900 + 1)
    assertThat(pasqua.days).containsAtLeastElementsIn(table.map { it.easter })
    assertThat(pasquetta.days).containsAtLeastElementsIn(table.map { it.monday })
    assertThat(pasqua.days.map { it.dayOfWeek.name }.toSet()).containsExactly("SUNDAY")
    assertThat(pasquetta.days.map { it.dayOfWeek.name }.toSet()).containsExactly("MONDAY")
    assertThat(pasqua.label).isEqualTo("Pasqua (tutti gli anni)")
    // Il refuso porta allo stesso insieme.
    assertThat(set("pasqa", on).days).isEqualTo(pasqua.days)
  }

  private fun set(text: String, on: LocalDate): SearchChip.DateSet {
    val chips = parser.parse(text, on, PlaceDictionary.EMPTY).chips
    assertWithMessage("«$text»").that(chips).hasSize(1)
    return chips.single() as SearchChip.DateSet
  }

  /** Il chip porta l'anno e il giorno nell'etichetta: la data scelta si legge. */
  @Test
  fun l_etichettaDiceLAnnoEIlGiorno() {
    assertThat(single("Pasqua 2024").label).isEqualTo("Pasqua 2024 (31 marzo)")
    assertThat(single("Pasquetta 2035").label).isEqualTo("Pasquetta 2035 (26 marzo)")
  }
}
