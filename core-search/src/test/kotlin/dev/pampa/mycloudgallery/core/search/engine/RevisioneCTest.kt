package dev.pampa.mycloudgallery.core.search.engine

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.testing.FakeConcepts
import dev.pampa.mycloudgallery.core.library.index.IndexOrderedTimelineView
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.search.contract.ResultOrder
import dev.pampa.mycloudgallery.core.search.contract.ResultRanking
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import dev.pampa.mycloudgallery.core.search.contract.SearchEngine
import dev.pampa.mycloudgallery.core.search.contract.SearchNotice
import dev.pampa.mycloudgallery.core.search.contract.SearchReadiness
import dev.pampa.mycloudgallery.core.search.contract.SearchRequest
import dev.pampa.mycloudgallery.core.search.engine.EngineFixture.Companion.ids
import dev.pampa.mycloudgallery.core.search.testing.FakeSearchData
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Fase 6, correzioni dopo le revisioni, pacchetto C: CE-2 (le parole libere sopravvivono a un chip tolto), CE-4 (la vista viva per il viewer), CE-9 (il
 * motore delle query non si aspetta) e CE-12 (le foto senza data stanno in fondo). Ogni test falliva prima della sua correzione.
 */
class RevisioneCTest {
  @get:Rule val tmp = TemporaryFolder()

  private suspend fun fixture(count: Int = 120): EngineFixture {
    val f = EngineFixture(tmp.newFolder(), SyntheticLibrary(seed = 4, count = count, placeIds = EngineFixture.PLACE_IDS))
    f.open()
    val fillers = listOf("albero", "auto", "pizza", "neve", "fiore", "bambino", "torta", "bicicletta")
    f.putAll((0 until count).associate { it.toLong() to listOf(f.image(fillers[it % fillers.size], it.toLong())) })
    return f
  }

  // --- CE-2 --------------------------------------------------------------------------------------------------------------------

  /**
   * CE-2: "scontrino ieri": togliere il chip "Ieri" cambia solo quel filtro. Prima, con i chip modificati, le parole libere (`parsed.words`) si perdevano: il
   * testo letto non cercava piu' "scontrino". Lo stesso nel motore vero e in quello di riferimento (la suite di parita' li confronta).
   */
  @Test
  fun togliereUnChipNonButtaLeParoleLibere() = runTest {
    val f = fixture()
    f.putText(5L, "scontrino esselunga")
    for (engine in listOf<SearchEngine>(f.engine(), f.reference())) {
      val first = engine.search(SearchRequest("scontrino ieri"))
      val yesterday = first.chips.filterIsInstance<SearchChip.DateRange>().single()
      assertThat(first.freeText).isTrue()
      // Con il filtro del giorno la cella 5 (di un altro giorno) non c'e'.
      assertThat(ids(first.confident)).doesNotContain(f.id(5))

      val without = engine.search(SearchRequest("scontrino ieri", editedChips = first.chips - yesterday))
      assertThat(without.chips).doesNotContain(yesterday)
      assertThat(ids(without.confident)).contains(f.id(5))

      // Anche senza nessun chip rimasto la query continua: il testo e' ancora cercato.
      val bare = engine.search(SearchRequest("scontrino ieri", editedChips = emptyList()))
      assertThat(bare.chips).isEmpty()
      assertThat(ids(bare.confident)).contains(f.id(5))
    }
  }

  /** CE-2: una frase esatta (chip `Word`) tolta non si cerca piu'; le parole libere restano. */
  @Test
  fun laFraseEsattaTolta_LeParoleLibereRestano() = runTest {
    val f = fixture()
    f.putText(7L, "scontrino esselunga totale")
    f.putText(8L, "scontrino conad")
    for (engine in listOf<SearchEngine>(f.engine(), f.reference())) {
      val first = engine.search(SearchRequest("scontrino \"esselunga totale\""))
      val phrase = first.chips.filterIsInstance<SearchChip.Word>().single { it.exact }
      assertThat(ids(first.confident)).contains(f.id(7))
      assertThat(ids(first.confident)).doesNotContain(f.id(8))
      val without = engine.search(SearchRequest("scontrino \"esselunga totale\"", editedChips = first.chips - phrase))
      assertThat(ids(without.confident)).containsAtLeast(f.id(7), f.id(8))
    }
  }

  /** Una query di soli filtri: tolto l'ultimo chip non resta niente da cercare, e non esce "tutta la Libreria". */
  @Test
  fun unaQueryDiSoliFiltri_ToltoIlChipNonRestaNiente() = runTest {
    val f = fixture()
    for (engine in listOf<SearchEngine>(f.engine(), f.reference())) {
      val first = engine.search(SearchRequest("video"))
      val video = first.chips.single()
      assertThat(first.freeText).isFalse()
      val none = engine.search(SearchRequest("video", editedChips = first.chips - video))
      assertThat(none.confident.size).isEqualTo(0)
      assertThat(none.likely.size).isEqualTo(0)
    }
  }

  // --- CE-12 -------------------------------------------------------------------------------------------------------------------

  private val today = LocalDate.of(2026, 10, 21)

  private fun ranked(index: Int, relevance: Float, day: LocalDate?) =
    ResultRanking.Ranked(index, relevance, day, index.toLong())

  /** CE-12: in "Recenti" una foto senza data sta dopo tutte le altre (prima valeva "oggi" e stava in cima). */
  @Test
  fun leFotoSenzaDataStannoInFondoA_Recenti() {
    val items = listOf(ranked(0, 1f, null), ranked(1, 0.2f, today.minusYears(8)), ranked(2, 0.5f, today.minusDays(3)))
    val order = items.sortedWith(ResultRanking.comparator(ResultOrder.RECENT, today)).map { it.index }
    assertThat(order).containsExactly(2, 1, 0).inOrder()
    assertThat(ResultRanking.period(null, today)).isLessThan(ResultRanking.period(today.minusYears(80), today))
  }

  /** CE-12: in "Pertinenti" la foto senza data non prende nessun premio di recenza. */
  @Test
  fun inPertinentiLaFotoSenzaDataNonHaRecenza() {
    assertThat(ResultRanking.recency(null as LocalDate?, today)).isEqualTo(0f)
    assertThat(ResultRanking.recency(today, today)).isEqualTo(1f)
    // A pertinenza quasi pari vince quella con la data: lo scatto di ieri prende il premio di recenza, quello senza data no.
    val items = listOf(ranked(0, 0.50f, null), ranked(1, 0.45f, today.minusDays(1)))
    assertThat(items.sortedWith(ResultRanking.comparator(ResultOrder.RELEVANT, today)).map { it.index }).containsExactly(1, 0).inOrder()
  }

  /** CE-12: lo stesso ordine nel percorso veloce (oltre 2.048 celle le chiavi si calcolano una volta per cella). */
  @Test
  fun lOrdineVelocePerMolteCelleDaLoStessoRisultato() {
    val many = (0 until 3_000).map { ranked(it, (it % 97) / 97f, if (it % 50 == 0) null else today.minusDays((it * 13L) % 2_000)) }
    for (order in ResultOrder.entries) {
      val fast = Ranking.order(many, order, today, dev.pampa.mycloudgallery.core.search.contract.RankParams()).toList()
      val slow = many.sortedWith(ResultRanking.comparator(order, today)).map { it.index }
      assertThat(fast).isEqualTo(slow)
    }
    val recent = Ranking.order(many, ResultOrder.RECENT, today, dev.pampa.mycloudgallery.core.search.contract.RankParams())
    // Le 60 senza data (una ogni 50) sono le ultime.
    assertThat(recent.takeLast(60).all { it % 50 == 0 }).isTrue()
  }

  // --- CE-9 --------------------------------------------------------------------------------------------------------------------

  /** Un motore delle query "a freddo": non risponde finche' non e' caricato, e [vectorIfLoaded] non aspetta. */
  private class ColdEmbedding : QueryEmbedding {
    override val readiness = MutableStateFlow<SearchReadiness>(SearchReadiness.Ready)
    var loaded = false
    var preloads = 0
    var waited = 0

    override suspend fun vector(text: String): FloatArray? {
      waited++
      loaded = true
      readiness.value = SearchReadiness.Ready
      return FakeConcepts.mix(FakeConcepts.conceptsOf(text))
    }

    override suspend fun vectorIfLoaded(text: String): FloatArray? {
      if (loaded) return vector(text)
      readiness.value = SearchReadiness.PreparingContent
      preload()
      return null
    }

    override fun preload() {
      preloads++
    }
  }

  /**
   * CE-9, E-Q11: la prima ricerca a freddo non aspetta il motore delle query. Risponde subito con filtri e testo e dice "sto preparando"; il contenuto
   * arriva alla ricerca dopo, quando il motore e' pronto. Prima aspettava `vector()` prima di tutto il resto.
   */
  @Test
  fun laPrimaRicercaAFreddoNonAspettaIlMotore() = runTest {
    val f = fixture()
    f.putImage(3L, "cane")
    f.putText(9L, "cane al parco")
    val cold = ColdEmbedding()
    val engine = LocalSearchEngine(f.data, f.index, f.keys, f.texts, cold, f.today, f.places)

    val first = engine.search(SearchRequest("cane"))
    assertThat(cold.waited).isEqualTo(0)
    assertThat(cold.preloads).isEqualTo(1)
    assertThat(first.notices).contains(SearchNotice.PreparingContentSearch)
    // Il testo risponde subito; il contenuto (la cella 3) non c'e' ancora.
    assertThat(ids(first.confident)).contains(f.id(9))
    assertThat(ids(first.confident)).doesNotContain(f.id(3))

    cold.loaded = true
    val second = engine.search(SearchRequest("cane"))
    assertThat(second.notices).doesNotContain(SearchNotice.PreparingContentSearch)
    assertThat(ids(second.confident)).containsAtLeast(f.id(3), f.id(9))
  }

  /** CE-9: all'apertura di Cerca il motore comincia a caricarsi (`warmUp`), senza aspettare una query. */
  @Test
  fun all_aperturaDiCercaIlMotoreComincia_aCaricarsi() = runTest {
    val f = fixture(60)
    val cold = ColdEmbedding()
    val engine = LocalSearchEngine(f.data, f.index, f.keys, f.texts, cold, f.today, f.places)
    engine.warmUp()
    assertThat(cold.preloads).isEqualTo(1)
    assertThat(cold.waited).isEqualTo(0)
  }

  // --- CE-4 --------------------------------------------------------------------------------------------------------------------

  /**
   * CE-4, E-Q13: la lista che il viewer scorre segue la Libreria viva. Un media nascosto altrove, con una istantanea nuova, esce dalla lista nello stesso
   * ordine degli altri; prima il viewer riceveva un flusso fermo e la foto restava nel pager.
   */
  @Test
  fun laVistaDelViewerSegueLaLibreriaViva() = runTest {
    val lib = SyntheticLibrary(seed = 7, count = 60)
    val data = FakeSearchData(lib.snapshot)
    val view = IndexOrderedTimelineView(lib.snapshot, intArrayOf(4, 2, 9, 7))
    val live = LiveResults.followView(view, data)

    assertThat(ids(live.first())).containsExactly(lib.snapshot.idAt(4), lib.snapshot.idAt(2), lib.snapshot.idAt(9), lib.snapshot.idAt(7)).inOrder()

    data.hiddenIds += lib.snapshot.idAt(2)
    data.snapshot.value = lib.snapshot.withFlags(0, lib.snapshot.flagsAt(0))
    assertThat(ids(live.first())).containsExactly(lib.snapshot.idAt(4), lib.snapshot.idAt(9), lib.snapshot.idAt(7)).inOrder()
  }
}
