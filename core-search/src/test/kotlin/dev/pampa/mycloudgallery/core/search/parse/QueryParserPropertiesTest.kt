package dev.pampa.mycloudgallery.core.search.parse

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.search.contract.ParsedQuery
import dev.pampa.mycloudgallery.core.search.contract.PlaceDictionary
import dev.pampa.mycloudgallery.core.search.contract.QueryParser
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import dev.pampa.mycloudgallery.core.search.testing.FixedToday
import java.time.LocalDate
import java.util.concurrent.Executors
import kotlin.random.Random
import org.junit.Test

/** P7: dimensione della tabella, determinismo, "oggi" solo dal parametro, robustezza e tetto dei 200 caratteri. */
class QueryParserPropertiesTest {
  private val parser = ItalianQueryParser()
  private val places = TestPlaces.DICTIONARY

  @Test
  fun laTabellaPubblicaHaAlmenoTrecentoQuery() {
    val rows = QueryTable.load()
    assertThat(rows.size).isAtLeast(300)
    assertThat(rows.map { it.query }.distinct().size).isAtLeast(300)
    QueryTable.FILES.forEach { file -> assertThat(rows.count { it.file == file }).isAtLeast(10) }
  }

  /** Stessa query e stesso "oggi" = stesso risultato: fra chiamate, fra istanze e fra thread. */
  @Test
  fun deterministico() {
    val rows = QueryTable.load()
    val first = rows.map { parser.parse(it.query, it.today, places) }
    assertThat(rows.map { parser.parse(it.query, it.today, places) }).isEqualTo(first)
    assertThat(rows.map { ItalianQueryParser().parse(it.query, it.today, places) }).isEqualTo(first)
    val pool = Executors.newFixedThreadPool(4)
    try {
      val concurrent = rows.map { row -> pool.submit<ParsedQuery> { parser.parse(row.query, row.today, places) } }.map { it.get() }
      assertThat(concurrent).isEqualTo(first)
    } finally {
      pool.shutdown()
    }
  }

  /** D39: "oggi" e' solo il parametro (la porta `Today`); cambiandolo cambiano i chip relativi e solo quelli. */
  @Test
  fun oggiArrivaSoloDalParametro() {
    val today = FixedToday(LocalDate.of(2026, 10, 21))
    val a = parser.parse("ieri", today.date(), places)
    today.today = LocalDate.of(2024, 3, 1)
    val b = parser.parse("ieri", today.date(), places)
    assertThat(a).isNotEqualTo(b)
    assertThat((a.chips.single() as SearchChip.DateRange).from).isEqualTo(LocalDate.of(2026, 10, 20))
    assertThat((b.chips.single() as SearchChip.DateRange).from).isEqualTo(LocalDate.of(2024, 2, 29))
    // Una query assoluta non dipende da oggi.
    assertThat(parser.parse("agosto 2023", LocalDate.of(2000, 1, 1), places)).isEqualTo(parser.parse("agosto 2023", LocalDate.of(2040, 6, 6), places))
  }

  @Test
  fun oltreDuecentoCaratteriSiTroncaESiRipeteIlRisultato() {
    val long = "Firenze estate 2023 " + "cane in spiaggia ".repeat(30)
    assertThat(long.length).isGreaterThan(QueryParser.MAX_QUERY_CHARS)
    assertThat(parser.parse(long, QueryTable.DEFAULT_TODAY, places))
      .isEqualTo(parser.parse(long.take(QueryParser.MAX_QUERY_CHARS), QueryTable.DEFAULT_TODAY, places))
    // Un taglio in mezzo a una coppia surrogata (emoji) non lascia mezzo carattere.
    val emoji = "a".repeat(199) + "\uD83D\uDC15 ieri"
    val parsed = parser.parse(emoji, QueryTable.DEFAULT_TODAY, places)
    assertThat(parsed.semantic).isEqualTo("a".repeat(199))
    assertThat(parsed.chips).isEmpty()
  }

  @Test
  fun unaLetteraOQuerySenzaParoleNonFaNiente() {
    for (text in listOf("", " ", "a", " é ", "7", "😀", "!!", "  \t ")) {
      val parsed = parser.parse(text, QueryTable.DEFAULT_TODAY, places)
      assertWithMessage("«$text»").that(parsed.chips).isEmpty()
      assertThat(parsed.semantic).isNull()
      assertThat(parsed.words).isEmpty()
    }
  }

  /** Il dizionario vuoto non rompe niente: i luoghi passano al contenuto. */
  @Test
  fun senzaDizionarioIlLuogoEUnaParola() {
    val parsed = parser.parse("cena a Firenze", QueryTable.DEFAULT_TODAY, PlaceDictionary.EMPTY)
    assertThat(parsed.chips).isEmpty()
    assertThat(parsed.words).containsExactly("cena", "firenze").inOrder()
  }

  /** Robustezza: stringhe casuali di pezzi di query non lanciano mai e danno chip validi e lo stesso risultato due volte. */
  @Test
  fun stringheCasualiNonRompononiente() {
    val pieces = listOf(
      "ieri", "oggi", "fa", "giorni", "mesi", "anni", "99999999999", "0", "31", "12", "2023", "1900", "2100", "/", "-", ".", "'", "\"", "«", "»",
      "agosto", "ago", "febbraio", "dal", "al", "tra", "e", "anni", "90", "natale", "pasqua", "inverno", "estate", "scorso", "quest",
      "foto", "video", "firenze", "monaco", "di", "baviera", "san", "marco", "solo", "sul", "nas", "😀", "*", "NEAR", "é", " ", "  ",
    )
    val random = Random(42)
    repeat(4000) {
      val text = (0 until random.nextInt(1, 14)).joinToString(if (random.nextBoolean()) " " else "") { pieces[random.nextInt(pieces.size)] }
      val today = LocalDate.of(2000, 1, 1).plusDays(random.nextLong(0, 40_000))
      val a = parser.parse(text, today, places)
      assertWithMessage("«$text»").that(parser.parse(text, today, places)).isEqualTo(a)
      a.chips.filterIsInstance<SearchChip.DateRange>().forEach { assertThat(it.everyYear || !it.to.isBefore(it.from)).isTrue() }
      assertThat(a.words.none { it in dev.pampa.mycloudgallery.core.search.contract.SearchText.STOPWORDS }).isTrue()
      if (a.semantic == null) assertThat(a.words).isEmpty()
    }
  }
}
