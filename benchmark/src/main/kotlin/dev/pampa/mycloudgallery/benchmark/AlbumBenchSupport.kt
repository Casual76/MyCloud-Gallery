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
 * Le cose di `AlbumBenchmark` (Fase 7, §5.4 di `docs/fase7-progetto.md`; debito del 27/10: lo scenario `album` era promesso e mancava).
 *
 * **Cosa serve dall'app** (variante `benchmarkRelease`, `app/src/benchmarkRelease`) e non e' di questo modulo:
 *  - gli **album sintetici** nel dataset della libreria mista: un album a mano di [BIG_ITEMS] elementi ([BIG_TITLE]), almeno [MIN_ALBUMS] album in tutto (le
 *    Raccolte "con 50 album") e un album smart la cui regola copre le [SMART_ROWS] righe della libreria. La richiesta si fa con il file
 *    [DATASET_ALBUMS_MARKER] accanto al marcatore; quando l'app li ha creati, lo stato `bench-dataset.json` porta [FIELD_ALBUMS], [FIELD_BIG_ITEMS] e
 *    [FIELD_SMART_ROWS];
 *  - il **ricalcolo dello smart** a richiesta: con il file [RECOMPUTE_MARKER] l'app, a ogni avvio, ricalcola quello smart mentre il resto del lavoro gira e
 *    scrive in [RECOMPUTE_STATUS] `{"stato":"pronto","ricalcoloSmartMs":...}` ([FIELD_RECOMPUTE_MS]).
 *
 * Oggi la variante di misura **non monta gli album** (nessun NAS, quindi nessun archivio dei dati personali: `AlbumsWiring` aspetta `nas.organizer`) e non
 * ha niente di tutto questo: le prove si **saltano** con il motivo (`Assume`), come quelle della ricerca senza i vettori. Appena il dataset li ha, girano
 * senza altre modifiche. Niente di questo si misura sull'emulatore: i numeri valgono solo su S25 e Tab S9 (`compare.ps1 -Smoke` altrove).
 */
object AlbumBenchTarget {

  /** Con questo file accanto al marcatore la variante di misura prepara anche gli album sintetici (`BenchDataset`). */
  const val DATASET_ALBUMS_MARKER: String = "bench-library.albums"

  /** Con questo file l'app ricalcola lo smart sintetico a ogni avvio e ne scrive il tempo in [RECOMPUTE_STATUS]. */
  const val RECOMPUTE_MARKER: String = "bench-library.albums.recompute"
  const val RECOMPUTE_STATUS: String = "bench-albums.json"

  /** I campi di `bench-dataset.json`: quanti album, gli elementi dell'album grande, le righe che lo smart copre. */
  const val FIELD_ALBUMS: String = "albumi"
  const val FIELD_BIG_ITEMS: String = "albumGrandeElementi"
  const val FIELD_SMART_ROWS: String = "albumSmartRighe"
  const val FIELD_RECOMPUTE_MS: String = "ricalcoloSmartMs"

  /** Il titolo dell'album a mano da 5.000 elementi (il testo della sua riga nelle Raccolte). */
  const val BIG_TITLE: String = "Bench 5000"

  /** Le misure del §5.4: album da 5.000 elementi, Raccolte con 50 album, ricalcolo di uno smart su 100 mila elementi. */
  const val BIG_ITEMS: Int = 5_000
  const val MIN_ALBUMS: Int = 50
  const val SMART_ROWS: Int = 100_000

  fun albums(status: BenchDatasetStatus?): Int = number(status, FIELD_ALBUMS)

  fun bigItems(status: BenchDatasetStatus?): Int = number(status, FIELD_BIG_ITEMS)

  fun smartRows(status: BenchDatasetStatus?): Int = number(status, FIELD_SMART_ROWS)

  /** Gli album del §5.4 ci sono tutti (altrimenti la misura non e' quella). */
  fun seeded(status: BenchDatasetStatus?): Boolean =
    albums(status) >= MIN_ALBUMS && bigItems(status) >= BIG_ITEMS && smartRows(status) >= SMART_ROWS

  /** Perche' le prove si saltano, per chi legge il rapporto. */
  fun missingReason(status: BenchDatasetStatus?): String =
    "Il dataset non ha gli album sintetici del §5.4 (${albums(status)} album, ${bigItems(status)} elementi nel grande, smart su ${smartRows(status)} righe; " +
      "servono $MIN_ALBUMS, $BIG_ITEMS e $SMART_ROWS): li prepara il collegamento di :app (file $DATASET_ALBUMS_MARKER, campi $FIELD_ALBUMS, " +
      "$FIELD_BIG_ITEMS e $FIELD_SMART_ROWS) e oggi la variante di misura non monta gli album (docs/bench/README.md, \"Album e persone\")."

  /** Un campo numerico di un file di stato scritto dall'app; 0 se manca. */
  fun number(status: BenchDatasetStatus?, field: String): Int =
    status?.let { runCatching { JSONObject(it.toString()).optInt(field, 0) }.getOrDefault(0) } ?: 0

  /** Dove l'app legge i marcatori e scrive gli stati (`getExternalFilesDir`): la stessa cartella di `BenchSession`. */
  val filesDir: String get() = "/sdcard/Android/data/${BenchSession.targetPackage}/files"

  /** Il file di stato `name` accanto al marcatore dell'app, o `null` se non c'e' o non e' JSON. */
  fun readStatus(name: String): JSONObject? {
    val text = BenchSession.shell("cat $filesDir/$name").trim()
    if (!text.startsWith("{")) return null
    return runCatching { JSONObject(text) }.getOrNull()
  }

  /**
   * Aspetta che il file di stato [name] diventi [ready]. L'app scrive uno stato `in_corso` subito (come `BenchDataset`): se dopo [absentTimeoutMs] il file
   * non c'e' ancora, la variante non ha quella parte e si smette di aspettare (`null`), invece di consumare il [timeoutMs] intero a ogni prova.
   */
  fun awaitStatus(name: String, timeoutMs: Long, absentTimeoutMs: Long = ABSENT_TIMEOUT_MS, ready: (JSONObject) -> Boolean): JSONObject? {
    val start = System.currentTimeMillis()
    while (System.currentTimeMillis() - start < timeoutMs) {
      val status = readStatus(name)
      if (status != null && ready(status)) return status
      if (status == null && System.currentTimeMillis() - start > absentTimeoutMs) return null
      Thread.sleep(STATUS_POLL_MS)
    }
    return null
  }

  private const val ABSENT_TIMEOUT_MS = 15_000L
  private const val STATUS_POLL_MS = 500L

  /** Chiede all'app gli album sintetici: il marcatore va scritto **prima** della sonda del dataset (la prima volta che gira, una per processo di test). */
  fun requestAlbums() {
    BenchSession.shell("mkdir -p $filesDir")
    BenchSession.shell("touch $filesDir/$DATASET_ALBUMS_MARKER")
  }

  /** Accende o spegne il ricalcolo dello smart a ogni avvio e toglie lo stato vecchio, perche' non si legga quello di un'iterazione prima. */
  fun requestRecompute(on: Boolean) {
    BenchSession.shell(if (on) "touch $filesDir/$RECOMPUTE_MARKER" else "rm -f $filesDir/$RECOMPUTE_MARKER")
    BenchSession.shell("rm -f $filesDir/$RECOMPUTE_STATUS")
  }
}

/** Le misure che le prove consegnano alle metriche proprie (uno stato per processo di test, azzerato a ogni iterazione). */
object AlbumBenchState {
  @Volatile var openMs: Double? = null
  @Volatile var collectionsMs: Double? = null
  @Volatile var recomputeMs: Double? = null

  fun reset() {
    openMs = null
    collectionsMs = null
    recomputeMs = null
  }
}

/**
 * Dal tocco sulla riga dell'album al primo fotogramma con una cella della sua griglia (`albumOpenMs`). Misurata da UiAutomator (nessuna sezione di traccia
 * dell'app: la pagina degli album non ne emette), quindi **comprende** il ritardo di rilevamento dell'albero di accessibilita' (decine di ms): e' un tetto
 * prudente per i 300 ms del §5.4, e la regressione (+15%) confronta sempre la stessa misura.
 */
@OptIn(ExperimentalMetricApi::class)
class AlbumOpenMetric : TraceMetric() {

  override fun getMeasurements(captureInfo: Metric.CaptureInfo, traceSession: TraceProcessor.Session): List<Metric.Measurement> =
    listOf(AlbumBenchState.openMs?.let { Metric.Measurement("albumOpenMs", it) } ?: Metric.Measurement("albumOpenMissing", 1.0))
}

/** Dal tocco sulla scheda Raccolte alla riga dell'album grande (`collectionsOpenMs`): le Raccolte con 50 album. */
@OptIn(ExperimentalMetricApi::class)
class CollectionsOpenMetric : TraceMetric() {

  override fun getMeasurements(captureInfo: Metric.CaptureInfo, traceSession: TraceProcessor.Session): List<Metric.Measurement> =
    listOf(AlbumBenchState.collectionsMs?.let { Metric.Measurement("collectionsOpenMs", it) } ?: Metric.Measurement("collectionsOpenMissing", 1.0))
}

/** Il tempo del ricalcolo dello smart su 100 mila elementi, come l'app l'ha scritto in [AlbumBenchTarget.RECOMPUTE_STATUS] (`albumRecomputeMs`). */
@OptIn(ExperimentalMetricApi::class)
class AlbumRecomputeMetric : TraceMetric() {

  override fun getMeasurements(captureInfo: Metric.CaptureInfo, traceSession: TraceProcessor.Session): List<Metric.Measurement> =
    listOf(AlbumBenchState.recomputeMs?.let { Metric.Measurement("albumRecomputeMs", it) } ?: Metric.Measurement("albumRecomputeMissing", 1.0))
}

/** Gli album sintetici c'erano tutti (1) o no (0): la validita' delle prove degli album in `soglie.json`. */
@OptIn(ExperimentalMetricApi::class)
class AlbumSeedMetric : TraceMetric() {

  override fun getMeasurements(captureInfo: Metric.CaptureInfo, traceSession: TraceProcessor.Session): List<Metric.Measurement> {
    val dataset = BenchState.dataset
    return listOf(
      Metric.Measurement("albumsSeeded", if (AlbumBenchTarget.seeded(dataset)) 1.0 else 0.0),
      Metric.Measurement("albumCount", AlbumBenchTarget.albums(dataset).toDouble()),
    )
  }
}

/**
 * I gesti sulle Raccolte e sulla pagina di un album, solo con l'albero di accessibilita' (come [BenchUi] e [SearchUi]). Le etichette sono quelle di
 * `feature-library` e `feature-albums` (`strings_collections.xml`, `strings_albums_list.xml`, `core-designsystem` `tab_collections`): **provvisorie**,
 * da confermare al primo giro sul telefono; se cambiano si cambiano qui.
 */
object AlbumUi {

  /** La scheda "Raccolte" della barra (`tab_collections`). */
  private val TAB: BySelector get() = By.pkg(BenchSession.targetPackage).desc("Raccolte")

  /** "Tutti gli album" (`albums_all`): l'elenco intero, se la riga non sta nella sezione breve delle Raccolte. */
  private val ALL_ALBUMS: BySelector get() = By.pkg(BenchSession.targetPackage).textContains("Tutti gli album")

  private const val TAB_TIMEOUT_MS = 8_000L
  private const val ROW_TIMEOUT_MS = 8_000L
  private const val PAGE_TIMEOUT_MS = 8_000L
  private const val ROW_SEARCH_SCROLLS = 10

  /** Apre le Raccolte e restituisce i millisecondi fino alla riga di [title] (o `null` se la scheda o la riga non compaiono). */
  fun openCollections(device: UiDevice, title: String): Double? {
    BenchUi.ensureForeground(device)
    val tab = device.findObject(TAB) ?: device.findObject(By.pkg(BenchSession.targetPackage).text("Raccolte")) ?: return null
    val start = System.nanoTime()
    tab.click()
    device.wait(Until.findObject(rowOf(title)), TAB_TIMEOUT_MS) ?: return null
    return (System.nanoTime() - start) / NANOS_PER_MILLI
  }

  /**
   * Tocca la riga di [title] (cercandola nell'elenco intero se non e' fra le prime) e restituisce i millisecondi dal tocco alla prima cella della griglia
   * dell'album; `null` se la riga non c'e' o la pagina non si apre. Le Raccolte non mostrano celle: se ce ne fosse una prima del tocco la misura non
   * direbbe niente, e la prova si ferma.
   */
  fun openAlbum(device: UiDevice, title: String): Double? {
    val row = findRow(device, title) ?: return null
    check(!device.hasObject(BenchUi.CELL)) { "Una cella della griglia c'e' gia' prima del tocco: la misura dell'apertura non direbbe niente." }
    BenchUi.ensureForeground(device)
    val start = System.nanoTime()
    row.click()
    val shown = device.wait(Until.hasObject(BenchUi.CELL), PAGE_TIMEOUT_MS) == true
    return if (shown) (System.nanoTime() - start) / NANOS_PER_MILLI else null
  }

  /** La riga di [title]: fra quelle visibili, poi nell'elenco intero scorrendo. */
  private fun findRow(device: UiDevice, title: String): UiObject2? {
    device.wait(Until.findObject(rowOf(title)), ROW_TIMEOUT_MS)?.let { return it }
    device.findObject(ALL_ALBUMS)?.click() ?: return null
    device.waitForIdle()
    repeat(ROW_SEARCH_SCROLLS) {
      device.findObject(rowOf(title))?.let { return it }
      val list = BenchUi.grid(device) ?: return null
      list.setGestureMargin(device.displayWidth / SCROLL_MARGIN_DIVISOR)
      list.scroll(androidx.test.uiautomator.Direction.DOWN, SCROLL_PERCENT)
      device.waitForIdle()
    }
    return null
  }

  private fun rowOf(title: String): BySelector =
    By.pkg(BenchSession.targetPackage).textContains(title)

  private const val NANOS_PER_MILLI = 1_000_000.0
  private const val SCROLL_MARGIN_DIVISOR = 8
  private const val SCROLL_PERCENT = 0.8f
}
