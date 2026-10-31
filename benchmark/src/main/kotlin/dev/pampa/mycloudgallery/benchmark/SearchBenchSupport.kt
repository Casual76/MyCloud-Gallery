package dev.pampa.mycloudgallery.benchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.Metric
import androidx.benchmark.macro.TraceMetric
import androidx.benchmark.traceprocessor.TraceProcessor
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.json.JSONObject

/**
 * Le cose di `SearchBenchmark` (Fase 6, P9; docs/fase6-progetto.md §5.4): le 50 query **pubbliche** (nessun dato personale, nomi di luogo pubblici),
 * le metriche della sezione di traccia `search.firstResults`, i nomi che l'app deve rispettare e i gesti sulla schermata Cerca.
 *
 * **Cosa serve dall'app (P8, P11)** e non e' di P9:
 *  - la sezione di traccia [TRACE_FIRST_RESULTS], dall'invio della query (azione "Cerca" della tastiera) al primo fotogramma con i risultati (o con
 *    lo stato "nessun risultato"): sincrona o asincrona, come `MCG:viewerOpen`. Senza, le prove girano lo stesso e riportano `searchSectionMissing`;
 *  - il dataset della variante `benchmarkRelease` con i **vettori sintetici a gruppi** (768 float16 e prima passata) per tutte le righe e il testo sintetico
 *    sul 10%, e il motore delle query vero (270M, gia' sul dispositivo): quando c'e', lo stato `bench-dataset.json` ha [FIELD_VECTOR_ROWS] e [FIELD_TEXT_ROWS];
 *    la richiesta si fa con il file [DATASET_SEARCH_MARKER] accanto al marcatore. Senza questi campi le prove si **saltano** con il motivo (Assume).
 */
object SearchBenchTarget {

  /** Il nome della sezione che l'app emette (`MCG:` come `MCG:viewerOpen`); si accetta anche senza prefisso. */
  const val TRACE_FIRST_RESULTS: String = "MCG:search.firstResults"
  const val TRACE_FIRST_RESULTS_PLAIN: String = "search.firstResults"

  /** Con questo file accanto al marcatore la variante di misura prepara anche l'indice di ricerca sintetico (`BenchDataset`, P11). */
  const val DATASET_SEARCH_MARKER: String = "bench-library.search"

  /** I campi di `bench-dataset.json` che dicono quante righe hanno un vettore e quante un testo. */
  const val FIELD_VECTOR_ROWS: String = "righeVettori"
  const val FIELD_TEXT_ROWS: String = "righeTesto"

  /** Le righe minime con vettore perche' la misura valga (la Libreria della guardia: 100 mila elementi, §5.4). */
  const val MIN_VECTOR_ROWS: Int = 100_000

  /** I campi come numeri, dallo stato del dataset (0 se mancano). */
  fun vectorRows(status: BenchDatasetStatus?): Int = number(status, FIELD_VECTOR_ROWS)

  fun textRows(status: BenchDatasetStatus?): Int = number(status, FIELD_TEXT_ROWS)

  private fun number(status: BenchDatasetStatus?, field: String): Int =
    status?.let { runCatching { JSONObject(it.toString()).optInt(field, 0) }.getOrDefault(0) } ?: 0
}

/**
 * Le 50 query del set pubblico (§5.4): 30 per contenuto, 10 di soli filtri (data, tipo, luogo) e 10 di testo. Sono gli esempi dell'analizzatore (P7) e
 * non dicono niente di nessuno. La prima di ogni gruppo e' quella delle prove "a freddo".
 */
object SearchBenchQueries {

  val CONTENT: List<String> = listOf(
    "cane", "gatto che dorme", "tramonto sul mare", "torta di compleanno", "spiaggia d'estate", "montagna con la neve", "fiori rosa", "bicicletta",
    "pizza", "auto rossa", "bambini che giocano", "albero di Natale", "una strada di città", "cibo su un tavolo", "cielo con le nuvole", "lago al mattino",
    "persone a una festa", "un cappuccino", "cavallo in un prato", "barche in porto", "fuochi d'artificio", "un panorama di montagna", "foto di gruppo",
    "una chiesa antica", "un giardino fiorito", "uccelli sul ramo", "casa in campagna", "ponte sul fiume", "mercato con la frutta", "treno in stazione",
  )

  /** Solo filtri (E-Q4): date, tipi, luoghi pubblici: rispondono dall'istantanea, senza il motore delle query. */
  val FILTERS: List<String> = listOf(
    "video", "screenshot", "foto di agosto 2023", "Natale", "Pasqua 2024", "estate 2022", "foto a Firenze", "video a Roma", "documenti", "panorami",
  )

  /** Testo letto (D25): parole che stanno in uno scontrino o in una schermata. */
  val TEXT: List<String> = listOf(
    "scontrino", "totale", "iva inclusa", "farmacia", "biglietto", "orario", "prezzo al chilo", "grazie per la visita", "numero ordine", "ricevuta",
  )

  val ALL: List<String> = CONTENT + FILTERS + TEXT

  init {
    check(ALL.size == 50 && ALL.distinct().size == 50) { "Il set pubblico ha 50 query diverse." }
  }
}

/**
 * Le sezioni `search.firstResults` dell'iterazione (§5.4). [warm]: p50, p95 e massimo di tutte le sezioni, "a caldo" (una per query); altrimenti la
 * **prima** sezione dell'iterazione, per la prima query a freddo. Sempre `searchSections` (quante), per la validita': se il buffer di Perfetto perde
 * pacchetti le sezioni mancano e `compare.ps1` lo dice invece di confrontare un numero sbagliato.
 */
@OptIn(ExperimentalMetricApi::class)
class SearchFirstResultsMetric(private val warm: Boolean) : TraceMetric() {

  override fun getMeasurements(captureInfo: Metric.CaptureInfo, traceSession: TraceProcessor.Session): List<Metric.Measurement> {
    val names = "'${SearchBenchTarget.TRACE_FIRST_RESULTS}', '${SearchBenchTarget.TRACE_FIRST_RESULTS_PLAIN}'"
    val durations = traceSession
      .query("SELECT ts, dur FROM slice WHERE name IN ($names) AND dur > 0 ORDER BY ts")
      .map { it.long("dur") / FrameQueries.NANOS_PER_MILLI }
      .toList()
    if (durations.isEmpty()) return listOf(Metric.Measurement("searchSectionMissing", 1.0), Metric.Measurement("searchSections", 0.0))
    val sections = Metric.Measurement("searchSections", durations.size.toDouble())
    if (!warm) return listOf(Metric.Measurement("searchFirstMs", durations.first()), sections)
    val sorted = durations.sorted()
    return listOf(
      Metric.Measurement("searchWarmP50Ms", percentile(sorted, 50.0)),
      Metric.Measurement("searchWarmP95Ms", percentile(sorted, 95.0)),
      Metric.Measurement("searchWarmMaxMs", sorted.last()),
      sections,
    )
  }

  companion object {
    /** Percentile "nearest rank", come `Get-Percentile` di `tools/bench/compare.ps1`. */
    fun percentile(sorted: List<Double>, p: Double): Double {
      require(sorted.isNotEmpty())
      val rank = Math.ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
      return sorted[rank - 1]
    }
  }
}

/** La PSS dell'app con Cerca aperta dopo le query (`BenchMemory.sample`): `searchPssKb`. */
@OptIn(ExperimentalMetricApi::class)
class SearchMemoryMetric : TraceMetric() {

  override fun getMeasurements(captureInfo: Metric.CaptureInfo, traceSession: TraceProcessor.Session): List<Metric.Measurement> =
    listOfNotNull(BenchMemory.pssKb?.let { Metric.Measurement("searchPssKb", it) })
}

/** I gesti sulla schermata Cerca, solo con l'albero di accessibilita' (come `BenchUi`). Le etichette sono quelle di `feature-search` (strings_search_screen.xml). */
object SearchUi {

  /** La scheda "Cerca" della barra (`tab_search`). */
  private val TAB: BySelector = By.desc("Cerca")

  /** Il campo di ricerca: un `EditText` (il suggerimento "Foto, luoghi, date, parole…" e' il segnaposto). */
  private val FIELD: BySelector = By.clazz("android.widget.EditText")

  /** Un risultato (la cella della Libreria) o lo stato "nessun risultato per ...". */
  private val RESULT: BySelector = By.desc(BenchLabels.CELL)
  private val NO_RESULT: BySelector = By.textStartsWith("Nessun risultato")

  private const val SCREEN_TIMEOUT_MS = 8_000L
  private const val RESULTS_TIMEOUT_MS = 12_000L

  /** Apre la scheda Cerca e aspetta il campo; false se non c'e' (l'app non ha ancora la scheda collegata, P11). */
  fun open(device: UiDevice): Boolean {
    if (device.wait(Until.hasObject(FIELD), 500) == true) return true
    // La scheda ha il nome come testo (non come descrizione) nella pillola di adesso: prima la descrizione, poi il testo.
    (device.findObject(TAB) ?: device.findObject(By.text("Cerca")))?.click()
    return device.wait(Until.hasObject(FIELD), SCREEN_TIMEOUT_MS) == true
  }

  /** Scrive [query] e la invia con l'azione "Cerca" della tastiera; aspetta che compaiano risultati o lo stato vuoto. Restituisce se e' comparso qualcosa. */
  fun submit(device: UiDevice, query: String): Boolean {
    val field: UiObject2 = device.findObject(FIELD) ?: return false
    field.text = query
    device.pressEnter()
    return device.wait(Until.hasObject(RESULT), RESULTS_TIMEOUT_MS) == true || device.hasObject(NO_RESULT)
  }

  /** Svuota il campo per la query dopo, senza chiudere la schermata. */
  fun clear(device: UiDevice) {
    device.findObject(FIELD)?.text = ""
    device.waitForIdle()
  }
}
