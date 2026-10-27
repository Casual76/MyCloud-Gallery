package dev.pampa.mycloudgallery.core.search.engine

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.search.contract.ResultOrder
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import dev.pampa.mycloudgallery.core.search.contract.SearchNotice
import dev.pampa.mycloudgallery.core.search.contract.SimilarSelection
import dev.pampa.mycloudgallery.core.search.engine.EngineFixture.Companion.ids
import dev.pampa.mycloudgallery.core.search.engine.EngineFixture.Companion.positions
import java.util.SplittableRandom
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * "Trova simili" dalla selezione della Libreria (Fase 7, correzioni F, risposta dell'utente del 27/10, TS-4). La query e' il **centroide normalizzato** dei media
 * scelti (di ognuno la media normalizzata dei suoi vettori, poi la media di quelle): una passata sola dell'indice, le soglie di «Trova simili», niente di nuovo
 * nell'indice. Provato:
 *  - il motore vero e l'esecutore di riferimento a forza bruta danno le stesse classi e lo stesso ordine, in tutti e due gli ordini;
 *  - con un media solo e' esattamente «Trova simili» di sempre;
 *  - i media scelti non compaiono fra i risultati; il centroide di foto dello stesso soggetto trova quel soggetto;
 *  - un media senza vettore e' lasciato fuori e l'avviso lo conta; senza nessun vettore i risultati sono vuoti e dicono perche';
 *  - i Nascosti mai: non entrano nella query, nel chip ne' nei risultati; la selezione ha un tetto.
 */
class SimilarSelectionTest {
  @get:Rule val tmp = TemporaryFolder()

  private val concepts = listOf("cane", "gatto", "spiaggia", "montagna", "tramonto", "torta")

  /** Ogni cella un soggetto (i % 6): una selezione di soggetti uguali ha un centroide che li trova. Una cella su venti non ha vettori. */
  private suspend fun fill(f: EngineFixture) {
    val vectors = HashMap<Long, List<FloatArray>>()
    for (i in 0 until f.snapshot.size) {
      if (i % 20 == 19) continue
      vectors[i.toLong()] = listOf(f.image(concepts[i % concepts.size], i.toLong()))
    }
    for ((_, k) in f.memberKeys) vectors[k] = listOf(f.image(concepts[(k % concepts.size).toInt()], k))
    f.putAll(vectors)
  }

  private suspend fun fixture(count: Int = 1_200, seed: Long = 21): EngineFixture {
    val f = EngineFixture(tmp.newFolder(), SyntheticLibrary(seed = seed, count = count, placeIds = EngineFixture.PLACE_IDS))
    f.open()
    fill(f)
    return f
  }

  /** Le celle col soggetto [concept] che non sono copertine di un gruppo (la selezione della Libreria ne sceglie le copertine). */
  private fun cellsOf(f: EngineFixture, concept: Int, n: Int): List<Int> =
    (0 until f.snapshot.size).filter { it % concepts.size == concept && it % 20 != 19 && f.members[it] == null }.take(n)

  @Test
  fun ilMotoreVeroEIlRiferimentoDannoLeStesseClassiELoStessoOrdine() = runTest {
    val f = fixture()
    val engine = f.engine()
    val reference = f.reference()
    val r = SplittableRandom(5)
    var found = 0
    repeat(40) { n ->
      val size = 2 + r.nextInt(5)
      val picks = (0 until size).map { f.id(r.nextInt(f.snapshot.size)) }.distinct()
      if (picks.size < 2) return@repeat
      for (order in ResultOrder.entries) {
        val mine = engine.similarToAll(picks, order)
        val ref = reference.similarToAll(picks, order)
        assertWithMessage("sicuri della selezione $n ($order)").that(positions(mine.confident)).containsExactlyElementsIn(positions(ref.confident)).inOrder()
        assertWithMessage("meno probabili della selezione $n ($order)").that(positions(mine.likely)).containsExactlyElementsIn(positions(ref.likely)).inOrder()
        assertWithMessage("avvisi della selezione $n").that(mine.notices).isEqualTo(ref.notices)
        assertWithMessage("chip della selezione $n").that((mine.chips.single() as SearchChip.Similar).all).isEqualTo((ref.chips.single() as SearchChip.Similar).all)
        found += mine.confident.size
      }
    }
    assertThat(found).isGreaterThan(0)
  }

  @Test
  fun conUnMediaSoloEIlTrovaSimiliDiSempre() = runTest {
    val f = fixture(count = 600)
    val engine = f.engine()
    for (i in listOf(3, 40, 211)) {
      for (order in ResultOrder.entries) {
        val one = engine.similarToAll(listOf(f.id(i)), order)
        val old = engine.similar(f.id(i), order)
        assertThat(positions(one.confident)).containsExactlyElementsIn(positions(old.confident)).inOrder()
        assertThat(positions(one.likely)).containsExactlyElementsIn(positions(old.likely)).inOrder()
        assertThat(one.chips).isEqualTo(old.chips)
        assertThat(one.notices).isEqualTo(old.notices)
      }
    }
  }

  @Test
  fun iMediaSceltiNonCompaionoFraIRisultatiEIlCentroideTrovaIlSoggetto() = runTest {
    val f = fixture()
    val engine = f.engine()
    val picks = cellsOf(f, concept = 0, n = 4)
    assertThat(picks).hasSize(4)

    val results = engine.similarToAll(picks.map { f.id(it) }, ResultOrder.RELEVANT)

    val shown = ids(results.confident) + ids(results.likely)
    assertThat(shown).containsNoneIn(picks.map { f.id(it) })
    // Il centroide di quattro «cane» trova «cane»: la grande maggioranza dei sicuri ha quel soggetto.
    val sure = positions(results.confident)
    assertThat(sure.size).isAtLeast(10)
    val sameSubject = sure.count { it % concepts.size == 0 }
    assertWithMessage("sicuri con lo stesso soggetto: $sameSubject su ${sure.size}").that(sameSubject.toDouble() / sure.size).isAtLeast(0.8)
    // Il chip porta tutta la scelta, la prima per prima: la UI mostra la miniatura della prima e il numero.
    val chip = results.chips.single() as SearchChip.Similar
    assertThat(chip.all).containsExactlyElementsIn(picks.map { f.id(it) }).inOrder()
    assertThat(results.notices).isEmpty()
  }

  @Test
  fun unMediaSenzaVettoreELasciatoFuoriEL_avvisoLoConta() = runTest {
    val f = fixture()
    val engine = f.engine()
    val withVectors = cellsOf(f, concept = 2, n = 3)
    val without = 19 // i % 20 == 19: nessun vettore
    assertThat(f.members[without]).isNull()

    val results = engine.similarToAll((withVectors + without).map { f.id(it) }, ResultOrder.RECENT)

    // Il risultato e' quello delle altre tre (il media senza vettore non cambia la query), e dice che uno e' rimasto fuori.
    val onlyThree = engine.similarToAll(withVectors.map { f.id(it) }, ResultOrder.RECENT)
    assertThat(positions(results.confident)).containsExactlyElementsIn(positions(onlyThree.confident)).inOrder()
    assertThat(results.notices).containsExactly(SearchNotice.SimilarWithoutVectors(1))
    assertThat(onlyThree.notices).isEmpty()
  }

  @Test
  fun senzaNessunVettoreIRisultatiSonoVuotiEDiconoPerche() = runTest {
    val f = fixture()
    val engine = f.engine()
    val none = listOf(19, 39, 59).map { f.id(it) }

    val results = engine.similarToAll(none, ResultOrder.RECENT)

    assertThat(results.confident.size).isEqualTo(0)
    assertThat(results.likely.size).isEqualTo(0)
    assertThat(results.notices).containsExactly(SearchNotice.SimilarWithoutVectors(3))
    assertThat((results.chips.single() as SearchChip.Similar).all).containsExactlyElementsIn(none)
  }

  @Test
  fun iNascostiNonEntranoMaiNellaQueryNelChipNeiRisultati() = runTest {
    val f = fixture()
    val picks = cellsOf(f, concept = 1, n = 4)
    val secret = f.id(picks.first())
    val secretFriends = cellsOf(f, concept = 1, n = 40).drop(4).take(10).map { f.id(it) }
    f.data.hiddenIds += listOf(secret) + secretFriends
    val engine = f.engine()
    val reference = f.reference()

    for (e in listOf(engine, reference)) {
      val results = e.similarToAll(picks.map { f.id(it) }, ResultOrder.RECENT)
      val chip = results.chips.single() as SearchChip.Similar
      // Il nascosto non e' nel chip, e nessun nascosto (nemmeno un perfetto compagno di soggetto) esce.
      assertThat(chip.all).doesNotContain(secret)
      assertThat(chip.all).containsExactlyElementsIn(picks.drop(1).map { f.id(it) }).inOrder()
      assertThat(ids(results.confident) + ids(results.likely)).containsNoneIn(listOf(secret) + secretFriends)
      // Un nascosto da solo non ha simili.
      val alone = e.similarToAll(listOf(secret, f.id(picks[1])).take(1), ResultOrder.RECENT)
      assertThat(alone.confident.size + alone.likely.size).isEqualTo(0)
    }
    // Una selezione fatta solo di nascosti non cerca niente.
    val allHidden = engine.similarToAll(listOf(secret) + secretFriends.take(2), ResultOrder.RECENT)
    assertThat(allHidden.confident.size + allHidden.likely.size).isEqualTo(0)
    assertThat((allHidden.chips.single() as SearchChip.Similar).all).hasSize(1)
  }

  @Test
  fun laSelezioneHaUnTetto() = runTest {
    val f = fixture()
    val engine = f.engine()
    val many = (0 until SimilarSelection.MAX + 15).map { f.id(it) }

    val results = engine.similarToAll(many, ResultOrder.RECENT)

    val chip = results.chips.single() as SearchChip.Similar
    assertThat(chip.all).hasSize(SimilarSelection.MAX)
    assertThat(chip.all).containsExactlyElementsIn(many.take(SimilarSelection.MAX)).inOrder()
  }

  @Test
  fun unElencoVuotoNonSiCerca() = runTest {
    val f = fixture(count = 300)
    val failure = runCatching { f.engine().similarToAll(emptyList<MediaId>(), ResultOrder.RECENT) }.exceptionOrNull()
    // Il motore vero con una lista vuota non inventa un risultato: l'errore di programmazione si vede subito.
    assertThat(failure).isNotNull()
  }
}
