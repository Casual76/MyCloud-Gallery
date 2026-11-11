package dev.pampa.mycloudgallery.feature.search

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.search.contract.PlaceName
import dev.pampa.mycloudgallery.core.search.contract.SearchMediaType
import dev.pampa.mycloudgallery.core.search.contract.Suggestion
import dev.pampa.mycloudgallery.core.search.engine.LibraryPlaces
import dev.pampa.mycloudgallery.core.search.engine.LibrarySuggestions
import dev.pampa.mycloudgallery.core.search.testing.FakeSearchData
import dev.pampa.mycloudgallery.core.search.testing.FixedToday
import dev.pampa.mycloudgallery.core.search.testing.InMemorySearchEngine
import dev.pampa.mycloudgallery.core.search.testing.InMemorySearchHistory
import dev.pampa.mycloudgallery.core.search.testing.SimpleQueryParser
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * GLI ESEMPI CON LA FOTO (I2, risposte dell'utente dell'11/11 sera): «qualche esempio con una foto d'anteprima, sempre un po' ruotati, e con tanti
 * risultati, in modo da essere buoni esempi». Qui le regole pure (il riassunto della Libreria in una passata, la soglia dei risultati, la mescolanza dei
 * tipi, la rotazione senza ripetere) e il controller vero con gli esempi: la barra si apre gia' piena (la recente piu' nuova e due esempi con la foto) e a
 * ogni chiusura la terna gira. La prova dei Nascosti e' in [SearchHiddenNeverTest].
 */
class SearchExamplesTest {

  private val lib = SyntheticLibrary(seed = 33L, count = 240, placeIds = intArrayOf(1, 2, 3, 4, 5))

  private fun example(label: String, count: Int, kind: ExampleKind) = Example(SuggestionItem(Suggestion.Term(label)), count, kind)

  @Test
  fun ilRiassuntoContaLuoghiMesiETipiInUnaPassataEPrendeLaFotoPiuRecente() = runTest {
    val snapshot = lib.snapshot
    val digest = LibraryDigest.of(snapshot) { emptySet() }
    for (place in listOf(1, 2, 3)) {
      val indices = (0 until snapshot.size).filter { snapshot.placeAt(it) == place }
      val tally = digest.place(listOf(place))!!
      assertThat(tally.count).isEqualTo(indices.size)
      // La foto e' la piu' recente di quel luogo (l'indice 0 e' il piu' recente).
      assertThat(tally.newest).isEqualTo(snapshot.idAt(indices.maxBy { snapshot.sortTimeAt(it) }))
    }
    val videos = (0 until snapshot.size).count { snapshot.kindAt(it) == dev.pampa.mycloudgallery.core.model.MediaKind.VIDEO }
    assertThat(digest.type(SearchMediaType.VIDEO)?.count ?: 0).isEqualTo(videos)
    // I mesi pieni: il primo e' quello con piu' foto.
    val months = (0 until snapshot.size).map { snapshot.localDayAt(it) / 100 }.groupingBy { it }.eachCount()
    assertThat(digest.fullestMonths(1).single().second.count).isEqualTo(months.values.max())
  }

  @Test
  fun unaFotoNascostaUnAttimoFaNonFaDaAnteprima_SiPassaAllaSeguente_D29() = runTest {
    val snapshot = lib.snapshot
    val place = snapshot.placeAt((0 until snapshot.size).first { snapshot.placeAt(it) != 0 })
    val newest = LibraryDigest.of(snapshot) { emptySet() }.place(listOf(place))!!.newest!!
    val digest = LibraryDigest.of(snapshot) { ids -> ids.filter { it == newest }.toSet() }
    val tally = digest.place(listOf(place))!!
    assertThat(tally.newest).isNotNull()
    assertThat(tally.newest).isNotEqualTo(newest)
  }

  @Test
  fun gliEsempiHannoTantiRisultati_SiMescolanoESenzaDoppioni() {
    val pool = ExamplePool.of(
      listOf(
        example("mare", 140, ExampleKind.THING),
        example("cibo", 90, ExampleKind.THING),
        example("pochi", 4, ExampleKind.THING),
        example("Roma", 300, ExampleKind.PLACE),
        example("roma", 20, ExampleKind.PLACE),
        example("Agosto 2025", 55, ExampleKind.MONTH),
        example("Video", 30, ExampleKind.TYPE),
      ),
    )
    // «pochi» non e' un buon esempio; «roma» e «Roma» sono la stessa parola (resta la piu' ricca).
    assertThat(pool.map { it.item.suggestion.label }).containsExactly("mare", "Roma", "Agosto 2025", "Video", "cibo").inOrder()
  }

  @Test
  fun inUnaLibreriaPiccolaBastanoMenoRisultati() {
    val pool = ExamplePool.of(listOf(example("mare", 5, ExampleKind.THING), example("Roma", 3, ExampleKind.PLACE), example("zero", 1, ExampleKind.TYPE)))
    assertThat(pool.map { it.item.suggestion.label }).containsExactly("mare", "Roma")
  }

  @Test
  fun aOgniAperturaUnaTernaDiversaSenzaRipetereFinCheCeNeSonoAltre() {
    val pool = (1..7).map { example("e$it", 50, ExampleKind.THING) }
    val rotation = ExampleRotation()
    val first = rotation.next(pool, 2, emptySet())
    // Il campo svuotato o le recenti aggiornate: la stessa terna.
    assertThat(rotation.current(pool, 2, emptySet())).isEqualTo(first)
    val second = rotation.next(pool, 2, emptySet())
    val third = rotation.next(pool, 2, emptySet())
    assertThat(first.map { it.key } + second.map { it.key } + third.map { it.key }).containsNoDuplicates()
    // La parola gia' in barra (la ricerca recente) non si ripete fra gli esempi.
    val skipped = rotation.next(pool, 2, setOf("e7"))
    assertThat(skipped.map { it.key }).doesNotContain("e7")
  }

  private fun TestScope.controllerWithExamples(history: InMemorySearchHistory, data: FakeSearchData): SearchController {
    val places = LibraryPlaces { id -> PlaceName("Luogo $id", null, "Italia") }
    val engine = InMemorySearchEngine(data, embedder = null, parser = SimpleQueryParser(), today = FixedToday(), places = { places.dictionary(lib.snapshot) })
    val suggestions = LibrarySuggestions(data, places, history)
    val examples = SearchExamples(engine, data, null, backgroundScope, compute = StandardTestDispatcher(testScheduler), prepareDelayMillis = 10)
    return SearchController(engine, suggestions, history, data, null, backgroundScope, 100, 200, examples)
  }

  @Test
  fun laBarraSiApreGiaPienaConLaRecenteEDueEsempiConLaFoto_ELaTernaGiraAllaChiusura() = runTest {
    val history = InMemorySearchHistory()
    history.record("mare ad agosto", emptyList())
    val data = FakeSearchData(lib.snapshot)
    val c = controllerWithExamples(history, data)
    advanceTimeBy(1_000)
    runCurrent()
    val first = c.strip.value
    assertThat(first).hasSize(3)
    // Prima i due esempi, poi la recente (a 360 dp, se non entrano tutte, restano le foto d'esempio).
    assertThat(first.last().suggestion).isInstanceOf(Suggestion.Recent::class.java)
    // I due esempi hanno la foto del loro primo risultato, che sta nella Libreria visibile.
    first.dropLast(1).forEach { item ->
      val art = item.art as SuggestionArt.Photo
      assertThat(lib.snapshot.indexOf(art.media)).isAtLeast(0)
    }
    // Un luogo come esempio: la foto e' proprio di quel luogo.
    first.mapNotNull { it.suggestion as? Suggestion.PlaceSuggestion to it.art }.forEach { (place, art) ->
      if (place != null) assertThat(lib.snapshot.placeAt(lib.snapshot.indexOf((art as SuggestionArt.Photo).media))).isIn(place.option.ids)
    }
    // Si apre, si chiude: alla prossima apertura la terna e' diversa (la recente resta in testa).
    c.onOpened()
    runCurrent()
    c.clear()
    runCurrent()
    val second = c.strip.value
    assertThat(second.last().suggestion.label).isEqualTo("mare ad agosto")
    assertThat(second.dropLast(1).map { it.suggestion.label }).containsNoneIn(first.dropLast(1).map { it.suggestion.label })
    // `topSuggestions` resta lo stesso elenco, senza anteprime.
    assertThat(c.topSuggestions.value).isEqualTo(second.map { it.suggestion })
  }

  @Test
  fun scrivendoICompletamentiHannoLaStessaForma_UnLuogoConLaSuaFoto() = runTest {
    val history = InMemorySearchHistory()
    val data = FakeSearchData(lib.snapshot)
    val c = controllerWithExamples(history, data)
    advanceTimeBy(1_000)
    runCurrent()
    c.onQueryChange("Luo")
    advanceTimeBy(150)
    runCurrent()
    val places = c.strip.value.filter { it.suggestion is Suggestion.PlaceSuggestion }
    assertThat(places).isNotEmpty()
    places.forEach { assertThat(it.art).isInstanceOf(SuggestionArt.Photo::class.java) }
  }

  @Test
  fun unaRicercaFattaDallUtenteTornaFraLeRecentiConLaFotoDelSuoPrimoRisultato() = runTest {
    val history = InMemorySearchHistory()
    val data = FakeSearchData(lib.snapshot)
    data.texts[lib.id(4)] = "torta di compleanno"
    val c = controllerWithExamples(history, data)
    advanceTimeBy(1_000)
    runCurrent()
    c.onQueryChange("torta")
    c.onSubmit()
    runCurrent()
    c.clear()
    runCurrent()
    val recent = c.strip.value.last()
    assertThat(recent.suggestion.label).isEqualTo("torta")
    assertThat((recent.art as SuggestionArt.Photo).media).isEqualTo(lib.id(4))
  }
}
