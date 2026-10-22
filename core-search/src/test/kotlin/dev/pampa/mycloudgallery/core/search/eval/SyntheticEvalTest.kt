package dev.pampa.mycloudgallery.core.search.eval

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.testing.FakeTextEmbedder
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.search.contract.RankParams
import dev.pampa.mycloudgallery.core.search.contract.SearchRequest
import dev.pampa.mycloudgallery.core.search.contract.SearchThresholds
import dev.pampa.mycloudgallery.core.search.engine.EmbedderQueryEmbedding
import dev.pampa.mycloudgallery.core.search.engine.EngineFixture
import dev.pampa.mycloudgallery.core.search.engine.IndexSearchData
import dev.pampa.mycloudgallery.core.search.engine.LocalSearchEngine
import dev.pampa.mycloudgallery.core.search.parse.ItalianQueryParser
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * La valutazione di punta a punta con **dati sintetici**: una Libreria finta con concetti noti, il motore vero di P6, le etichette costruite
 * dai concetti, le metriche, le tabelle dei punteggi e la calibrazione. Prova che metriche e tabelle vedono quello che vede il motore (le stesse
 * classi di sicuri e meno probabili) e che la procedura gira sui dati veri dell'indice. La qualita' del modello vero si misura sul set privato.
 */
class SyntheticEvalTest {
  @get:Rule val tmp = TemporaryFolder()

  private val concepts = listOf("cane", "gatto", "spiaggia", "montagna", "torta", "auto", "fiore", "albero", "pizza", "bambino")

  private class Case(val id: String, val category: EvalCategory, val text: String, val concept: String?, val textKey: String? = null)

  private val cases = listOf(
    Case("q1", EvalCategory.OBJECTS, "cane", "cane"),
    Case("q2", EvalCategory.OBJECTS, "cagnolino", "cane"),
    Case("q3", EvalCategory.SCENES, "mare", "spiaggia"),
    Case("q4", EvalCategory.SCENES, "neve", "montagna"),
    Case("q5", EvalCategory.EVENTS, "compleanno", "torta"),
    Case("q6", EvalCategory.OBJECTS, "macchina", "auto"),
    Case("q7", EvalCategory.COLORS, "rose", "fiore"),
    Case("q8", EvalCategory.TEXT, "esselunga", null, textKey = "esselunga"),
  )

  private class Bench(val f: EngineFixture, val expected: Map<String, Set<String>>)

  private suspend fun bench(): Bench {
    val f = EngineFixture(tmp.newFolder(), SyntheticLibrary(seed = 9, count = 600, placeIds = EngineFixture.PLACE_IDS))
    f.open()
    f.putAll((0 until 600).associate { it.toLong() to listOf(f.image(concepts[it % concepts.size], it.toLong())) })
    val textCells = (0 until 600 step 37).toList()
    for (i in textCells) f.putText(i.toLong(), "scontrino esselunga totale euro")
    val expected = cases.associate { c ->
      c.id to when {
        c.concept != null -> (0 until 600).filter { concepts[it % concepts.size] == c.concept }.map { keyHex(it) }.toSet()
        else -> textCells.map { keyHex(it) }.toSet()
      }
    }
    return Bench(f, expected)
  }

  private fun keyHex(i: Int): String = "%064x".format(i)

  private fun EngineFixture.keyOf(id: MediaId): String? = keys[id]?.own?.firstOrNull()?.hex

  private fun keysOf(f: EngineFixture, view: TimelineView): List<String> = EngineFixture.ids(view).mapNotNull { f.keyOf(it) }

  private fun scoring(b: Bench) = EvalScoring(
    IndexSearchData(b.f.data, b.f.keys, b.f.index), EmbedderQueryEmbedding(FakeTextEmbedder()), ItalianQueryParser(), b.f.today,
    { b.f.places.dictionary(b.f.snapshot) }, { id -> b.f.keyOf(id) }, { b.f.keys.sync() },
  )

  /** Il motore vero con l'ordine per pertinenza senza premio di recenza, come si misura la qualita' (§2.4). */
  private fun qualityEngine(f: EngineFixture) = LocalSearchEngine(
    f.data, f.index, f.keys, f.texts, EmbedderQueryEmbedding(FakeTextEmbedder()), f.today, f.places, params = RankParams(90, 0f),
  )

  @Test
  fun leMetricheDelMotoreVeroSuDatiSintetici() = runTest {
    val b = bench()
    val engine = qualityEngine(b.f)
    val runs = cases.map { c ->
      val r = engine.search(SearchRequest(c.text, order = dev.pampa.mycloudgallery.core.search.contract.ResultOrder.RELEVANT))
      QueryRun(c.id, c.category, b.expected.getValue(c.id), keysOf(b.f, r.confident), keysOf(b.f, r.likely))
    }
    val report = EvalMetrics.report(runs)
    assertThat(report.total.queries).isEqualTo(cases.size)
    assertThat(report.total.recallAt20).isAtLeast(0.8)
    assertThat(report.total.precisionSure).isAtLeast(0.9)
    assertThat(report.total.mrr).isAtLeast(0.9)
    assertThat(report.total.recallAt5).isAtLeast(0.9)
    assertThat(report.byCategory.keys).containsExactly(EvalCategory.OBJECTS, EvalCategory.SCENES, EvalCategory.EVENTS, EvalCategory.COLORS, EvalCategory.TEXT)
    assertThat(report.total.meets(EvalTargets())).isTrue()
  }

  @Test
  fun leTabelleDeiPuntiSeiVedonoLeStesseClassiDelMotore() = runTest {
    val b = bench()
    val engine = qualityEngine(b.f)
    val scoring = scoring(b)
    val thr = Thr(SearchThresholds.ZC, SearchThresholds.ZL, SearchThresholds.S_MIN)
    for (c in cases) {
      val real = engine.search(SearchRequest(c.text, order = dev.pampa.mycloudgallery.core.search.contract.ResultOrder.RELEVANT))
      val table = scoring.table(EvalQuery(c.id, c.category, c.text), b.expected.getValue(c.id))
      assertThat(table.contentAvailable).isTrue()
      val mine = EvalCalibration.run(table.table, thr)
      assertThat(mine.sure.toSet()).isEqualTo(keysOf(b.f, real.confident).toSet())
      assertThat(mine.likely.toSet()).isEqualTo(keysOf(b.f, real.likely).toSet())
    }
  }

  @Test
  fun laCalibrazioneGiraSuiDatiDellIndice() = runTest {
    val b = bench()
    val scoring = scoring(b)
    val tables = cases.map { scoring.table(EvalQuery(it.id, it.category, it.text), b.expected.getValue(it.id)).table }
    val result = EvalSweep.calibrate(tables)
    assertThat(result.meetsTargets).isTrue()
    val order = EvalOrder.tune(tables, result.thr)
    assertThat(order.recallAt20Base - order.recallAt20Chosen).isAtMost(EvalOrder.MAX_RECALL_LOSS + 1e-9)
    // Il resoconto e' fatto di numeri: nessuna query.
    val text = result.report.toNumbersText()
    for (c in cases) assertThat(text).doesNotContain(c.text)
  }

  @Test
  fun unNascostoNonEntraNeLeTabelleNeLaRicerca() = runTest {
    val b = bench()
    val hiddenPosition = (0 until 600).first { concepts[it % concepts.size] == "cane" }
    b.f.data.hiddenIds += b.f.id(hiddenPosition)
    val table = scoring(b).table(EvalQuery("q1", EvalCategory.OBJECTS, "cane"), b.expected.getValue("q1")).table
    assertThat(table.cells.map { it.key }).doesNotContain(keyHex(hiddenPosition))
    val real = qualityEngine(b.f).search(SearchRequest("cane"))
    assertThat(keysOf(b.f, real.confident) + keysOf(b.f, real.likely)).doesNotContain(keyHex(hiddenPosition))
  }

  @Test
  fun senzaIlMotoreDelleQueryLaTabellaHaSoloIlTesto() = runTest {
    val b = bench()
    val scoring = EvalScoring(
      IndexSearchData(b.f.data, b.f.keys, b.f.index), EmbedderQueryEmbedding(null), ItalianQueryParser(), b.f.today,
      { b.f.places.dictionary(b.f.snapshot) }, { id -> b.f.keyOf(id) }, { b.f.keys.sync() },
    )
    val noContent = scoring.table(EvalQuery("q1", EvalCategory.OBJECTS, "cane"), b.expected.getValue("q1"))
    assertThat(noContent.contentAvailable).isFalse()
    assertThat(noContent.table.cells).isEmpty()
    val text = scoring.table(EvalQuery("q8", EvalCategory.TEXT, "esselunga"), b.expected.getValue("q8"))
    assertThat(text.table.cells.filter { it.t == 1f }.map { it.key }).containsExactlyElementsIn(b.expected.getValue("q8"))
    assertThat(text.table.cells.filter { it.t == 0f }.all { it.z != null }).isTrue()
  }

  @Test
  fun soloFiltriDannoTuttiIFiltratiPerData() = runTest {
    val b = bench()
    val table = scoring(b).table(EvalQuery("q9", EvalCategory.VIDEO, "video"), emptySet())
    val videos = (0 until 600).count { b.f.snapshot.kindAt(it) == dev.pampa.mycloudgallery.core.model.MediaKind.VIDEO }
    assertThat(table.cells).isEqualTo(videos)
    val run = EvalCalibration.run(table.table, Thr(3.5f, 2f, 0f))
    assertThat(run.likely).isEmpty()
    assertThat(run.sure).hasSize(minOf(videos, ScoreTable.MAX_CELLS))
  }
}
