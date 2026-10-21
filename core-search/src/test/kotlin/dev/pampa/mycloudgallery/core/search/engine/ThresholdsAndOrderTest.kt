package dev.pampa.mycloudgallery.core.search.engine

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.search.contract.RankParams
import dev.pampa.mycloudgallery.core.search.contract.ResultOrder
import dev.pampa.mycloudgallery.core.search.contract.ResultRanking
import dev.pampa.mycloudgallery.core.search.contract.SearchThresholds
import dev.pampa.mycloudgallery.core.search.coverage.CoverageTracker
import dev.pampa.mycloudgallery.core.search.engine.EngineFixture.Companion.positions
import dev.pampa.mycloudgallery.core.search.history.HistoryStore
import dev.pampa.mycloudgallery.core.search.history.LocalSearchHistory
import dev.pampa.mycloudgallery.core.database.SearchHistoryRecord
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Le **soglie lette da una costante** (D27) con il test che le rilegge, i **due ordini** (D48, E-Q15) e la cronologia (D32).
 */
class ThresholdsAndOrderTest {
  @get:Rule val tmp = TemporaryFolder()

  /** I valori di partenza del §2.4: P9 li calibra e cambia questa riga insieme alla costante (e a docs/decisioni.md). */
  @Test
  fun leSogliSonoQuelleDellaCostante() {
    assertThat(SearchThresholds.ZC).isEqualTo(3.5f)
    assertThat(SearchThresholds.ZL).isEqualTo(2.0f)
    assertThat(SearchThresholds.S_MIN).isEqualTo(0f)
    assertThat(SearchThresholds.ZS).isEqualTo(3.5f)
    assertThat(SearchThresholds.ZLS).isEqualTo(2.0f)
    assertThat(SearchThresholds.MAX_LIKELY).isEqualTo(300)
    assertThat(SearchThresholds.COVERAGE_WARN_BELOW).isEqualTo(0.98f)
    assertThat(RankParams()).isEqualTo(RankParams(halfLifeDays = 90, recencyWeight = 0.10f))
  }

  /** Il motore decide le classi proprio con le costanti: appena sotto Zc e' "meno probabile", appena sotto Zl e' fuori. */
  @Test
  fun leClassiCambianoEsattamenteAlleSoglie() = runTest {
    val f = EngineFixture(tmp.newFolder(), SyntheticLibrary(seed = 2, count = 10))
    f.open()
    val core = SearchCore(f.data, f.index, f.keys, f.today, RankParams(), CoverageTracker(), 1)
    val z = listOf(SearchThresholds.ZC, Math.nextDown(SearchThresholds.ZC), SearchThresholds.ZL, Math.nextDown(SearchThresholds.ZL))
    val scored = z.mapIndexed { i, zi -> Scored(i, zi, 0.5f, 0f, 0) }
    val results = core.withMap(f.snapshot) { it }.let { map ->
      core.finish(f.snapshot, map, emptyList(), scored, false, Thresholds(SearchThresholds.ZC, SearchThresholds.ZL, SearchThresholds.S_MIN),
        ResultOrder.RELEVANT, emptyList())
    }
    assertThat(positions(results.confident)).containsExactly(0)
    assertThat(positions(results.likely)).containsExactly(1, 2).inOrder()
    // Il minimo assoluto: un punteggio sotto Smin non e' sicuro nemmeno con z alto.
    val low = core.finish(f.snapshot, core.withMap(f.snapshot) { it }, emptyList(), listOf(Scored(5, 9f, -0.1f, 0f, 0)), false,
      Thresholds(SearchThresholds.ZC, SearchThresholds.ZL, SearchThresholds.S_MIN), ResultOrder.RECENT, emptyList())
    assertThat(positions(low.likely)).containsExactly(5)
  }

  /** E-Q15: "Pertinenti" con due quasi uguali, uno di ieri e uno di anni fa: quello di ieri sopra; uno molto pertinente resta sopra. */
  @Test
  fun pertinentiPremiaUnPocoLaRecenza() {
    val today = LocalDate.of(2026, 10, 21)
    val items = listOf(
      ResultRanking.Ranked(0, 0.96f, today.minusYears(5), 1),
      ResultRanking.Ranked(1, 0.95f, today.minusDays(1), 2),
      ResultRanking.Ranked(2, 0.40f, today, 3),
    )
    assertThat(Ranking.order(items, ResultOrder.RELEVANT, today, RankParams()).toList()).containsExactly(1, 0, 2).inOrder()
    // La stessa cosa con le chiavi calcolate una volta (molte celle): stesso ordine.
    val many = (0 until 3_000).map { ResultRanking.Ranked(it, (it % 97) / 97f, today.minusDays((it * 13L) % 2_000), it.toLong()) }
    for (order in ResultOrder.entries) {
      assertThat(Ranking.order(many, order, today, RankParams()).toList())
        .containsExactlyElementsIn(many.sortedWith(ResultRanking.comparator(order, today)).map { it.index }).inOrder()
    }
  }

  /** E-Q15: "Recenti" per periodo; dentro lo stesso periodo un poco pertinente non sta mai sopra uno molto pertinente. */
  @Test
  fun recentiPerPeriodoEDentroPerPertinenza() {
    val today = LocalDate.of(2026, 10, 21)
    val items = listOf(
      ResultRanking.Ranked(0, 0.2f, LocalDate.of(2025, 3, 20), 3),
      ResultRanking.Ranked(1, 0.9f, LocalDate.of(2025, 3, 2), 1),
      ResultRanking.Ranked(2, 0.1f, today, 5),
    )
    assertThat(Ranking.order(items, ResultOrder.RECENT, today, RankParams()).toList()).containsExactly(2, 1, 0).inOrder()
  }

  /** D32: la cronologia passa dalla tabella di P1; il chip "Simili a" non si salva; una ricerca vuota nemmeno. */
  @Test
  fun laCronologiaSalvaTestoEChipSenzaSimili() = runTest {
    val store = object : HistoryStore {
      val rows = MutableStateFlow<List<SearchHistoryRecord>>(emptyList())
      var next = 1L
      override suspend fun record(text: String, chipLabels: List<String>) {
        rows.value = listOf(SearchHistoryRecord(next++, text, chipLabels)) + rows.value.filter { it.text != text }
      }
      override suspend fun delete(id: Long) { rows.value = rows.value.filter { it.id != id } }
      override suspend fun clear() { rows.value = emptyList() }
      override fun changes(limit: Int): Flow<List<SearchHistoryRecord>> = rows
    }
    val history = LocalSearchHistory(store, backgroundScope)
    history.record("  ", emptyList())
    history.record("cane al mare", listOf(SearchChip.Word("cane", false), SearchChip.Similar(dev.pampa.mycloudgallery.core.model.MediaId("00000000-0000-0000-0000-000000000001"), "Simili a")))
    assertThat(store.rows.value.single().chipLabels).containsExactly("cane")
    assertThat(history.entries.first { it.isNotEmpty() }.single().text).isEqualTo("cane al mare")
    history.clear()
    assertThat(store.rows.value).isEmpty()
  }
}
