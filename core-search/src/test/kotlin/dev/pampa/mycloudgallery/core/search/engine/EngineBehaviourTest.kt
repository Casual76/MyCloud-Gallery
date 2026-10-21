package dev.pampa.mycloudgallery.core.search.engine

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.search.contract.ChipMatching
import dev.pampa.mycloudgallery.core.search.contract.CoverageReason
import dev.pampa.mycloudgallery.core.search.contract.ResultOrder
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import dev.pampa.mycloudgallery.core.search.contract.SearchCoverage
import dev.pampa.mycloudgallery.core.search.contract.SearchNotice
import dev.pampa.mycloudgallery.core.search.contract.SearchReadiness
import dev.pampa.mycloudgallery.core.search.contract.SearchRequest
import dev.pampa.mycloudgallery.core.search.coverage.CoverageReasons
import dev.pampa.mycloudgallery.core.search.coverage.CoverageTracker
import dev.pampa.mycloudgallery.core.search.engine.EngineFixture.Companion.ids
import dev.pampa.mycloudgallery.core.search.index.key
import java.io.File
import java.io.RandomAccessFile
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Il comportamento del motore (riga P6 del §4.4): ripiego sulla versione precedente e ricetta nuova (E-A10), una cella per gruppo,
 * motore delle query assente o in caricamento (D35, E-Q11), testo esatto, refusi e frasi (D25, D27), copertura (D31), un record
 * rovinato trovato durante una ricerca (§2.4), l'insieme di giorni di Pasqua.
 */
class EngineBehaviourTest {
  @get:Rule val tmp = TemporaryFolder()

  private suspend fun fixture(count: Int = 300): EngineFixture {
    val f = EngineFixture(tmp.newFolder(), SyntheticLibrary(seed = 4, count = count, placeIds = EngineFixture.PLACE_IDS))
    f.open()
    val fillers = listOf("albero", "auto", "pizza", "neve", "fiore", "bambino", "torta", "bicicletta")
    f.putAll((0 until count).associate { it.toLong() to listOf(f.image(fillers[it % fillers.size], it.toLong())) })
    return f
  }

  /** E-A10: la versione nuova senza vettore usa quello della precedente; quando arriva il suo, vale il suo. */
  @Test
  fun senzaIlVettoreDellaVersioneNuovaValeQuelloDellaPrecedente() = runTest {
    val f = fixture()
    val i = 17
    f.cells.replace(f.cellOf(i, own = listOf(700_000L, i.toLong())))
    f.keys.invalidate(listOf(f.id(i)))
    f.putImage(i.toLong(), "cane")
    val engine = f.engine()
    assertThat(ids(engine.search(SearchRequest("cane")).confident)).contains(f.id(i))
    f.putImage(700_000L, "gatto")
    assertThat(ids(engine.search(SearchRequest("cane")).confident)).doesNotContain(f.id(i))
    assertThat(ids(engine.search(SearchRequest("gatto")).confident)).contains(f.id(i))
  }

  /** E-A10, D21: con una ricetta nuova la copertura dice "aggiornamento" e la ricerca resta usabile sui vettori vecchi. */
  @Test
  fun unaRicettaNuovaNonSvuotaLaRicerca() = runTest {
    val f = fixture(200)
    val tracker = CoverageTracker()
    val engine = LocalSearchEngine(f.data, f.index, f.keys, f.texts, EmbedderQueryEmbedding(dev.pampa.mycloudgallery.core.ai.testing.FakeTextEmbedder()),
      f.today, f.places, coverageTracker = tracker, currentRecipe = 2)
    f.index.saveVectors(key(3L), dev.pampa.mycloudgallery.core.search.index.vecBody(f.image("cane", 3), recipe = 2))
    f.putImage(4L, "cane")
    val results = engine.search(SearchRequest("cane"))
    assertThat(ids(results.confident)).containsAtLeast(f.id(3), f.id(4))
    val coverage = tracker.state.value as SearchCoverage.Updating
    assertThat(coverage.percent).isEqualTo(0)
  }

  /** D28: una cella per gruppo, il punteggio del membro migliore; i membri non escono mai da soli. */
  @Test
  fun unaCellaPerGruppo() = runTest {
    val f = fixture()
    val cover = f.members.keys.first()
    f.putImage(cover.toLong(), "auto")
    for (m in f.members.getValue(cover)) f.putImage(f.memberKeys.getValue(m), "cane")
    val results = f.engine().search(SearchRequest("cane"))
    val found = ids(results.confident) + ids(results.likely)
    assertThat(found.count { it == f.id(cover) }).isEqualTo(1)
    assertThat(found).containsNoneIn(f.members.getValue(cover))
  }

  /** D35, E-Q11: senza modello filtri e testo funzionano e lo si dice; in caricamento "Sto preparando…". */
  @Test
  fun senzaModelloRispondonoFiltriETesto() = runTest {
    val f = fixture()
    f.putText(5L, "scontrino esselunga")
    val engine = f.engine(embedder = null)
    assertThat(engine.readiness.value).isEqualTo(SearchReadiness.FiltersAndTextOnly(modelMissing = true))
    val results = engine.search(SearchRequest("esselunga cane"))
    assertThat(results.notices).contains(SearchNotice.OnlyFiltersAndText)
    assertThat(ids(results.likely)).contains(f.id(5))
    val loading = object : QueryEmbedding {
      override val readiness: StateFlow<SearchReadiness> = MutableStateFlow(SearchReadiness.PreparingContent)
      override suspend fun vector(text: String): FloatArray? = null
    }
    val preparing = LocalSearchEngine(f.data, f.index, f.keys, f.texts, loading, f.today, f.places)
    assertThat(preparing.search(SearchRequest("cane")).notices).contains(SearchNotice.PreparingContentSearch)
    // Solo filtri: tutti i video, per data (Recenti).
    val videos = preparing.search(SearchRequest("video"))
    val expected = (0 until f.snapshot.size).filter { f.snapshot.kindAt(it) == dev.pampa.mycloudgallery.core.model.MediaKind.VIDEO }
    assertThat(EngineFixture.positions(videos.confident)).containsExactlyElementsIn(expected)
  }

  /** D25, D27: parole esatte = sicuro, con un refuso = meno probabile e lo si dice; le frasi fra virgolette sono obbligatorie. */
  @Test
  fun testoEsattoRefusiEFrasi() = runTest {
    val f = fixture()
    f.putText(8L, "scontrino esselunga totale")
    f.putText(9L, "totale scontrino")
    val engine = f.engine(embedder = null)
    assertThat(ids(engine.search(SearchRequest("esselunga")).confident)).containsExactly(f.id(8))
    val typo = engine.search(SearchRequest("eselunga"))
    assertThat(ids(typo.confident)).isEmpty()
    assertThat(ids(typo.likely)).containsExactly(f.id(8))
    assertThat(typo.notices).contains(SearchNotice.TypoCorrected("esselunga", "eselunga"))
    assertThat(ids(engine.search(SearchRequest("\"scontrino esselunga\"")).confident)).containsExactly(f.id(8))
    assertThat(ids(engine.search(SearchRequest("\"esselunga scontrino\"")).confident)).isEmpty()
    // Meta' delle parole = meno probabile.
    val half = engine.search(SearchRequest("totale lavatrice"))
    assertThat(ids(half.likely)).containsExactly(f.id(8), f.id(9))
  }

  /** D31: copertura con i motivi, avviso solo sotto il 98%, modello assente. */
  @Test
  fun laCoperturaContaLeCelleEDiceIMotivi() = runTest {
    val f = EngineFixture(tmp.newFolder(), SyntheticLibrary(seed = 4, count = 100))
    f.open()
    f.putAll((0 until 95).associate { it.toLong() to listOf(f.image("albero", it.toLong())) })
    val tracker = CoverageTracker(CoverageReasons { mapOf(CoverageReason.NO_INDEXER_FOR_AREA to it.size) })
    val engine = f.engine(coverage = tracker)
    engine.search(SearchRequest("albero"))
    val partial = tracker.state.value as SearchCoverage.Partial
    assertThat(partial.covered).isEqualTo(95)
    assertThat(partial.total).isEqualTo(100)
    assertThat(partial.reasons).containsExactly(CoverageReason.NO_INDEXER_FOR_AREA, 5)
    assertThat(partial.warns).isTrue()
    f.putAll((95 until 100).associate { it.toLong() to listOf(f.image("albero", it.toLong())) })
    engine.search(SearchRequest("albero"))
    assertThat(tracker.state.value).isEqualTo(SearchCoverage.Complete)
    val missing = CoverageTracker(queryModelBytes = 164_626_432, modelMissing = { true })
    f.engine(embedder = null, coverage = missing).search(SearchRequest("albero"))
    assertThat(missing.state.value).isEqualTo(SearchCoverage.ModelMissing(164_626_432))
  }

  /** §2.4: un record rovinato scoperto dalla seconda passata non esce, si toglie e si chiede di riaverlo. */
  @Test
  fun unRecordRovinatoDuranteUnaRicercaSiRipara() = runTest {
    val f = fixture(120)
    f.putImage(7L, "cane")
    f.putImage(8L, "cane")
    val engine = f.engine()
    assertThat(ids(engine.search(SearchRequest("cane")).confident)).containsAtLeast(f.id(7), f.id(8))
    // Il record del vettore della chiave 7 (l'ultimo ma uno scritto) si rovina sul disco.
    f.index.close()
    val dir = tmp.root.listFiles()!!.first { File(it, "ledger.log").isFile }
    val file = dir.listFiles()!!.first { it.name.startsWith("vectors-") }
    val recordSize = 44 + 768 * 2 + 4
    RandomAccessFile(file, "rw").use { it.seek(file.length() - 2L * recordSize + 300); it.write(byteArrayOf(7, 7, 7, 7)) }
    f.index.open()
    val after = f.engine().search(SearchRequest("cane"))
    assertThat(ids(after.confident)).doesNotContain(f.id(7))
    assertThat(ids(after.confident)).contains(f.id(8))
    f.engine().search(SearchRequest("cane"))
    assertThat(f.repair.keys).contains(key(7L))
    assertThat(f.index.counters.corruptRecords.get()).isAtLeast(1)
  }

  /** P7: "Pasqua" senza anno e' un insieme di giorni; una cella entra se il suo giorno locale e' uno di quelli. */
  @Test
  fun lInsiemeDiGiorniFiltraSulGiornoLocale() = runTest {
    val f = fixture(50)
    val day = ChipMatching.dayOf(f.snapshot.localDayAt(10))!!
    val chip = SearchChip.DateSet(setOf(day, LocalDate.of(1999, 4, 4)), "Pasqua (tutti gli anni)")
    assertThat(ChipMatching.accepts(chip, f.snapshot, 10)).isTrue()
    val other = (0 until f.snapshot.size).first { ChipMatching.dayOf(f.snapshot.localDayAt(it)) != day }
    assertThat(ChipMatching.accepts(chip, f.snapshot, other)).isFalse()
    val results = f.engine().search(SearchRequest("", editedChips = listOf(chip)))
    assertThat(EngineFixture.positions(results.confident)).contains(10)
    assertThat(results.order).isEqualTo(ResultOrder.RECENT)
  }
}
