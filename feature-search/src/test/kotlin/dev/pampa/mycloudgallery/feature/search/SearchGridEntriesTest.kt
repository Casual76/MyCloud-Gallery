package dev.pampa.mycloudgallery.feature.search

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryTimelineLevel
import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.search.contract.ResultOrder
import dev.pampa.mycloudgallery.core.search.contract.SearchCoverage
import dev.pampa.mycloudgallery.feature.search.contract.SearchScreenState
import java.time.LocalDate
import org.junit.Test

/** Le voci della griglia dei risultati (Fase 6, P8, §2.6, D28, D48): sezioni, intestazioni di periodo e ordine in cui scorre il viewer. */
class SearchGridEntriesTest {

  private val lib = library(count = 400)
  private val today = LocalDate.of(2026, 10, 21)

  private fun state(
    order: ResultOrder,
    confident: IntRange = 0..9,
    likely: IntRange = IntRange.EMPTY,
    showLikely: Boolean = false,
    likelyCount: Int = likely.count(),
  ): SearchScreenState.Results {
    val r = lib.results(confident = confident, likely = likely, order = order)
    return SearchScreenState.Results(r.chips, order, r.confident, r.likely, likelyCount, showLikely, SearchCoverage.Complete, emptyList())
  }

  private fun ids(view: OrderedTimelineView) = (0 until view.size).map { view.snapshot.idAt(view.snapshotIndex(it)) }

  @Test
  fun inRecentiOgniPeriodoHaLaSuaIntestazioneInOrdineEOgniCellaUna() {
    val entries = SearchGridEntries.build(state(ResultOrder.RECENT, 0..79), today)
    assertThat(entries.first()).isEqualTo(GridEntry.Title(ResultSection.CONFIDENT, 80))
    assertThat(entries.filterIsInstance<GridEntry.Cell>()).hasSize(80)
    val periods = entries.filterIsInstance<GridEntry.Period>()
    assertThat(periods).isNotEmpty()
    // Le intestazioni stanno sempre prima delle loro celle e non si ripetono di seguito.
    assertThat(entries[1]).isInstanceOf(GridEntry.Period::class.java)
    assertThat(periods.zipWithNext().all { (a, b) -> a != b }).isTrue()
    assertThat(entries.map { it.key }.toSet()).hasSize(entries.size)
  }

  @Test
  fun inPertinentiNienteIntestazioniDiPeriodo() {
    val entries = SearchGridEntries.build(state(ResultOrder.RELEVANT, 0..79), today)
    assertThat(entries.filterIsInstance<GridEntry.Period>()).isEmpty()
    assertThat(entries.filterIsInstance<GridEntry.Cell>().map { it.viewIndex }).isEqualTo((0..79).toList())
  }

  @Test
  fun leIntestazioniSonoGiornoMeseAnnoSecondoLEta() {
    assertThat(SearchGridEntries.levelOf(today.minusDays(3), today)).isEqualTo(GalleryTimelineLevel.Day)
    assertThat(SearchGridEntries.levelOf(today.minusDays(31), today)).isEqualTo(GalleryTimelineLevel.Day)
    assertThat(SearchGridEntries.levelOf(today.minusDays(32), today)).isEqualTo(GalleryTimelineLevel.Month)
    assertThat(SearchGridEntries.levelOf(today.minusDays(730), today)).isEqualTo(GalleryTimelineLevel.Month)
    assertThat(SearchGridEntries.levelOf(today.minusDays(731), today)).isEqualTo(GalleryTimelineLevel.Year)
    assertThat(SearchGridEntries.levelOf(null, today)).isEqualTo(GalleryTimelineLevel.All)
  }

  @Test
  fun iMenoProbabiliSonoUnBottoneFinoAChiNeChiede_E_Q12() {
    val hidden = SearchGridEntries.build(state(ResultOrder.RELEVANT, 0..4, likely = 10..16), today)
    assertThat(hidden.last()).isEqualTo(GridEntry.ShowLikely(7))
    assertThat(hidden.filterIsInstance<GridEntry.Cell>().map { it.section }.toSet()).containsExactly(ResultSection.CONFIDENT)

    val shown = SearchGridEntries.build(state(ResultOrder.RELEVANT, 0..4, likely = 10..16, showLikely = true), today)
    assertThat(shown.filterIsInstance<GridEntry.ShowLikely>()).isEmpty()
    val titles = shown.filterIsInstance<GridEntry.Title>()
    assertThat(titles.map { it.section to it.count }).containsExactly(ResultSection.CONFIDENT to 5, ResultSection.LIKELY to 7).inOrder()
    // Le celle dei meno probabili stanno dopo quelle dei sicuri.
    val sections = shown.filterIsInstance<GridEntry.Cell>().map { it.section }
    assertThat(sections.indexOf(ResultSection.LIKELY)).isEqualTo(5)
  }

  @Test
  fun senzaRisultatiSicuriMostratiICMenoProbabiliNonCeIlTitoloDeiSicuri() {
    val entries = SearchGridEntries.build(state(ResultOrder.RELEVANT, confident = IntRange.EMPTY, likely = 3..5, showLikely = true), today)
    assertThat(entries.filterIsInstance<GridEntry.Title>().map { it.section }).containsExactly(ResultSection.LIKELY)
    assertThat(entries.filterIsInstance<GridEntry.Cell>()).hasSize(3)
  }

  @Test
  fun ilViewerScorreINRisultatiNellOrdineMostrato() {
    val closed = state(ResultOrder.RELEVANT, 3..7, likely = 20..22)
    assertThat(ids(SearchGridEntries.openingView(closed))).isEqualTo(ids(closed.confident))

    val open = state(ResultOrder.RELEVANT, 0..2, likely = 20..22, showLikely = true)
    assertThat(ids(SearchGridEntries.openingView(open))).isEqualTo(ids(open.confident) + ids(open.likely))
  }
}
