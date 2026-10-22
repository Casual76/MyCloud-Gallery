package dev.pampa.mycloudgallery.benchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La latenza della ricerca (docs/fase6-progetto.md §5.4, riga P9 del §4.4): la Libreria sintetica da **100 mila** elementi con vettori a gruppi e testo
 * sul 10% (il dataset delle guardie, con l'indice di ricerca: serve P11), il motore delle query **vero** (270M, gia' sul dispositivo) e le 50 query
 * **pubbliche** ([SearchBenchQueries]). Si misura dall'invio della query al primo fotogramma con i risultati (sezione `search.firstResults`).
 *
 * Quattro prove, quattro scenari di `soglie.json` (i nomi sono la chiave):
 *  - `searchWarm`: tutte le 50 query a **motore caldo**, 5 iterazioni; p50, p95 e massimo per iterazione. Soglia: p95 <= 300 ms, regressione +15%;
 *  - `searchColdContent`: la **prima** query per contenuto dopo un avvio a freddo (il motore 270M si carica e si inizializza: D35). Soglia: <= 4 s;
 *  - `searchColdFilters`: la prima query di soli filtri e la prima di testo dopo un avvio a freddo: rispondono mentre il motore si carica. Soglia: <= 300 ms;
 *  - `searchMemory`: PSS con Cerca aperta dopo le query (regressione +15%).
 *
 * Come le altre prove (`ViewerBenchmark`): se la scheda Cerca non c'e' o l'app non emette la sezione la prova **gira lo stesso** e lo dice
 * (`searchSectionMissing`); se il dataset non ha i vettori (`righeVettori` sotto le 100 mila) la prova si **salta** con il motivo. Niente di tutto questo si
 * misura sull'emulatore: i numeri valgono solo su S25 e Tab S9 (`compare.ps1 -Smoke` sugli emulatori).
 *
 * Non misura la qualita': quella e' lo strumento di valutazione di debug sul set privato (docs/decisioni.md, P9).
 */
@RunWith(AndroidJUnit4::class)
class SearchBenchmark {

  @get:Rule
  val rule = MacrobenchmarkRule()

  @Before
  fun requireSearchLibrary() {
    val readiness = BenchSession.requireLibrary()
    val vectors = SearchBenchTarget.vectorRows(readiness.dataset)
    Assume.assumeTrue(
      "Il dataset non ha l'indice di ricerca sintetico ($vectors righe con vettore, servono ${SearchBenchTarget.MIN_VECTOR_ROWS}): lo prepara P11 " +
        "(file ${SearchBenchTarget.DATASET_SEARCH_MARKER}, campi ${SearchBenchTarget.FIELD_VECTOR_ROWS} e ${SearchBenchTarget.FIELD_TEXT_ROWS}).",
      BenchConfig.smoke || vectors >= SearchBenchTarget.MIN_VECTOR_ROWS,
    )
  }

  /** A motore caldo: 50 query una dopo l'altra, in 5 iterazioni dall'avvio a freddo. */
  @OptIn(ExperimentalMetricApi::class)
  @Test
  fun searchWarm() {
    rule.measureRepeated(
      packageName = BenchSession.targetPackage,
      metrics = listOf(SearchFirstResultsMetric(warm = true), LibraryMixMetric()),
      compilationMode = BenchConfig.compilationMode,
      iterations = BenchConfig.iterations(WARM_ITERATIONS),
      setupBlock = {
        openSearch()
        // Il riscaldamento: la prima query per contenuto carica il motore; non fa parte della misura (la sua sezione sta nel setup).
        SearchUi.submit(device, SearchBenchQueries.CONTENT.first())
        SearchUi.clear(device)
        Thread.sleep(WARM_SETTLE_MS)
      },
      measureBlock = {
        for (query in queries()) {
          SearchUi.submit(device, query)
          SearchUi.clear(device)
        }
      },
    )
  }

  /** La prima query per contenuto dopo un avvio a freddo: comprende il caricamento del motore delle query. */
  @OptIn(ExperimentalMetricApi::class)
  @Test
  fun searchColdContent() = cold(SearchBenchQueries.CONTENT.first())

  /** La prima query di soli filtri dopo un avvio a freddo: non aspetta il motore (E-Q11). */
  @OptIn(ExperimentalMetricApi::class)
  @Test
  fun searchColdFilters() = cold(SearchBenchQueries.FILTERS.first())

  /** La memoria con Cerca aperta dopo tutte le query. */
  @OptIn(ExperimentalMetricApi::class)
  @Test
  fun searchMemory() {
    rule.measureRepeated(
      packageName = BenchSession.targetPackage,
      metrics = listOf(SearchMemoryMetric(), LibraryMixMetric()),
      compilationMode = BenchConfig.compilationMode,
      iterations = BenchConfig.iterations(MEMORY_ITERATIONS),
      setupBlock = {
        BenchMemory.reset()
        openSearch()
      },
      measureBlock = {
        for (query in queries()) {
          SearchUi.submit(device, query)
          SearchUi.clear(device)
        }
        device.waitForIdle()
        Thread.sleep(WARM_SETTLE_MS)
        BenchMemory.sample()
      },
    )
  }

  @OptIn(ExperimentalMetricApi::class)
  private fun cold(query: String) {
    rule.measureRepeated(
      packageName = BenchSession.targetPackage,
      metrics = listOf(SearchFirstResultsMetric(warm = false), LibraryMixMetric()),
      compilationMode = BenchConfig.compilationMode,
      iterations = BenchConfig.iterations(COLD_ITERATIONS),
      setupBlock = {
        // Processo ucciso: il motore delle query non e' caricato. Si apre solo la Libreria; Cerca si apre dentro la misura (D35: all'apertura
        // di Cerca il motore comincia a caricarsi, e la query parte subito).
        killProcess()
        startActivityAndWait()
        check(BenchUi.waitForLibrary(device, LIBRARY_TIMEOUT_MS)) { "La Libreria non si vede dopo l'avvio." }
        device.waitForIdle()
      },
      measureBlock = {
        check(SearchUi.open(device)) { "La scheda Cerca non si apre (la lega P11)." }
        SearchUi.submit(device, query)
      },
    )
  }

  /** Apre la Libreria e poi Cerca, e aspetta il campo; salta la prova se la scheda non c'e'. */
  private fun MacrobenchmarkScope.openSearch() {
    killProcess()
    startActivityAndWait()
    check(BenchUi.waitForLibrary(device, LIBRARY_TIMEOUT_MS)) { "La Libreria non si vede dopo l'avvio." }
    device.waitForIdle()
    Assume.assumeTrue("La scheda Cerca non si apre: l'app non ha ancora la ricerca collegata (P11).", SearchUi.open(device))
  }

  /** Le 50 query nell'ordine fisso (lo stesso in ogni iterazione), meno 3 nello smoke. */
  private fun queries(): List<String> = if (BenchConfig.smoke) SearchBenchQueries.ALL.take(SMOKE_QUERIES) else SearchBenchQueries.ALL

  private companion object {
    const val WARM_ITERATIONS = 5
    const val COLD_ITERATIONS = 8
    const val MEMORY_ITERATIONS = 3
    const val LIBRARY_TIMEOUT_MS = 20_000L
    const val WARM_SETTLE_MS = 500L
    const val SMOKE_QUERIES = 3
  }
}
