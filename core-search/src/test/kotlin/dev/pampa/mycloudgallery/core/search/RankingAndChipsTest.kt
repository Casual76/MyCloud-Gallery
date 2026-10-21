package dev.pampa.mycloudgallery.core.search

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.search.contract.ResultOrder
import dev.pampa.mycloudgallery.core.search.contract.ResultRanking
import dev.pampa.mycloudgallery.core.search.contract.ResultRanking.Ranked
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import dev.pampa.mycloudgallery.core.search.contract.SearchText
import dev.pampa.mycloudgallery.core.search.testing.InMemorySearchHistory
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Fase 6 (P0): la formula dell'ordine (D48, §2.4, E-Q15), il significato dei chip di data, la normalizzazione e la cronologia. */
class RankingAndChipsTest {

  private val today = LocalDate.of(2026, 10, 21)

  private fun ranked(index: Int, relevance: Float, day: LocalDate) = Ranked(index, relevance, day, day.toEpochDay() * 86_400_000L)

  /** E-Q15: in "Pertinenti" a pertinenza quasi uguale vince il piu' recente; molto piu' pertinente resta sopra anche se vecchio. */
  @Test
  fun pertinentiPremiaUnPocoLaRecenza() {
    val old = ranked(0, 0.95f, today.minusYears(3))
    val yesterday = ranked(1, 0.93f, today.minusDays(1))
    val weak = ranked(2, 0.40f, today)
    val sorted = listOf(old, yesterday, weak).sortedWith(ResultRanking.comparator(ResultOrder.RELEVANT, today)).map { it.index }
    assertThat(sorted).containsExactly(1, 0, 2).inOrder()
    assertThat(ResultRanking.recency(0)).isEqualTo(1f)
    assertThat(ResultRanking.recency(90)).isWithin(1e-4f).of(0.5f)
  }

  /** E-Q15: in "Recenti" un risultato poco pertinente non sta sopra uno molto pertinente dello stesso periodo. */
  @Test
  fun recentiVaPerPeriodoEDentroPerPertinenza() {
    val augustStrong = ranked(0, 0.9f, LocalDate.of(2026, 8, 3))
    val augustWeak = ranked(1, 0.3f, LocalDate.of(2026, 8, 20))
    val lastWeek = ranked(2, 0.2f, today.minusDays(6))
    val yearsAgo = ranked(3, 1f, LocalDate.of(2021, 5, 5))
    val sorted = listOf(augustWeak, yearsAgo, augustStrong, lastWeek).sortedWith(ResultRanking.comparator(ResultOrder.RECENT, today))
    assertThat(sorted.map { it.index }).containsExactly(2, 0, 1, 3).inOrder()
    // I periodi crescono col tempo anche fra granularita' diverse.
    assertThat(ResultRanking.period(today.minusDays(40), today)).isLessThan(ResultRanking.period(today.minusDays(30), today))
    assertThat(ResultRanking.period(today.minusYears(3), today)).isLessThan(ResultRanking.period(today.minusDays(700), today))
  }

  @Test
  fun laPertinenzaSegueLaFormulaDelProgetto() {
    assertThat(ResultRanking.relevance(null, 0f)).isEqualTo(0f)
    assertThat(ResultRanking.relevance(null, 1f)).isEqualTo(1f)
    assertThat(ResultRanking.relevance(2.0f, 0f)).isEqualTo(0f)
    assertThat(ResultRanking.relevance(5.5f, 0f)).isEqualTo(1f)
    assertThat(ResultRanking.relevance(3.75f, 0.6f)).isWithin(1e-4f).of(0.7f)
  }

  /** "Capodanno" (31/12-1/1, tutti gli anni) attraversa la fine dell'anno; un periodo con l'anno no. */
  @Test
  fun iChipDiDataSiLeggonoSulGiornoDelloScatto() {
    val newYear = SearchChip.DateRange(LocalDate.of(2000, 12, 31), LocalDate.of(2000, 1, 1), everyYear = true, label = "Capodanno")
    val august2023 = SearchChip.DateRange(LocalDate.of(2023, 8, 1), LocalDate.of(2023, 8, 31), everyYear = false, label = "Agosto 2023")
    assertThat(newYear.isFilter).isTrue()
    assertThat(SearchChip.Word("cane", exact = false).isFilter).isFalse()
    assertThat(dev.pampa.mycloudgallery.core.search.contract.ChipMatching.dayOf(20231231)).isEqualTo(LocalDate.of(2023, 12, 31))
    assertThat(dev.pampa.mycloudgallery.core.search.contract.ChipMatching.dayOf(20230231)).isNull()
    assertThat(august2023.to).isEqualTo(LocalDate.of(2023, 8, 31))
  }

  /** D13: minuscolo, senza accenti, apostrofi separati, "€" -> "euro". */
  @Test
  fun laNormalizzazioneDelTestoEQuellaDelProgetto() {
    assertThat(SearchText.words("Dell'Esselunga: Totale 12,50€ — CAFFÈ"))
      .containsExactly("dell", "esselunga", "totale", "12", "50", "euro", "caffe").inOrder()
  }

  /** D32: al massimo 50 voci, la piu' recente prima, una ricerca ripetuta una volta sola, cancellabili. */
  @Test
  fun laCronologiaTieneLeUltime50() = runTest {
    val history = InMemorySearchHistory()
    repeat(60) { history.record("ricerca $it", emptyList()) }
    history.record("ricerca 59", emptyList())
    assertThat(history.entries.value).hasSize(50)
    assertThat(history.entries.value.first().text).isEqualTo("ricerca 59")
    assertThat(history.entries.value.count { it.text == "ricerca 59" }).isEqualTo(1)
    history.delete(history.entries.value.first().id)
    assertThat(history.entries.value.first().text).isEqualTo("ricerca 58")
    history.clear()
    assertThat(history.entries.value).isEmpty()
  }
}
