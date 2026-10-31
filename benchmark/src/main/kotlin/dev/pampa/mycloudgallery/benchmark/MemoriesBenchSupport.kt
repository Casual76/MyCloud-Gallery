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
import java.util.regex.Pattern
import org.json.JSONObject

/**
 * Le cose di `MemoriesBenchmark` (Fase 9, P12; `docs/fase9-progetto.md` §5.4, scenario `ricordi`): il dataset, i marcatori e i nomi che l'app deve rispettare,
 * le metriche e i gesti sulle Raccolte, sulla pagina di un ricordo e sulla presentazione.
 *
 * **Cosa c'e' nell'app (variante `benchmarkRelease`, `app/src/benchmarkRelease`)**:
 *  - il dataset da 100 mila righe ha lo **scaffale dei ricordi seminato** (cinque ricordi veri nel file che l'app legge al montaggio), il 60% delle righe con una
 *    posizione e un gruppo di righe con le date di "oggi" di 1 e 3 anni fa: lo stato `bench-dataset.json` lo dice con [FIELD_SHELF], [FIELD_POSITIONS] e
 *    [FIELD_ANNIVERSARY] (e [FIELD_SEED_DAY], il giorno della creazione: dopo qualche giorno il dataset si rifa' da solo);
 *  - i ricordi **si montano senza NAS** con le scelte in memoria (`MeasurementHooks.memoriesPersonalStore`): senza, Raccolte direbbe "Aspetto la tua libreria";
 *  - il **pilota della generazione** (`BenchMemoriesDriver`): con [GENERATE_MARKER] fa a ogni avvio un giro pieno e uno leggero su un motore suo (scaffale in
 *    memoria vuoto: ogni giro e' una generazione da zero) e scrive [STATUS]; con [LOAD_MARKER] li ripete di continuo a bassa priorita'.
 *  - le sezioni di traccia [TRACE_GENERATE] e [TRACE_GENERATE_LIGHT] dentro `MemoryEngine.refresh`.
 */
object MemoriesBenchTarget {

  /** Un giro pieno e uno leggero a ogni avvio (`BenchMemoriesDriver.GENERATE_MARKER`). */
  const val GENERATE_MARKER: String = "bench-library.memories"

  /** I giri di continuo, a bassa priorita': la generazione "in corso" (`BenchMemoriesDriver.LOAD_MARKER`). */
  const val LOAD_MARKER: String = "bench-library.memories.load"

  /** Lo stato del pilota (`BenchMemoriesDriver.STATUS`): `{"stato":"pronto","generazioneMs":...,"passoLeggeroMs":...,"calcoloInCorso":...}`. */
  const val STATUS: String = "bench-memories.json"

  /** Le sezioni di traccia del motore (`AvvioTrace.MEMORIES_GENERATE` e `MEMORIES_GENERATE_LIGHT` nell'app). */
  const val TRACE_GENERATE: String = "MCG:memories.generate"
  const val TRACE_GENERATE_LIGHT: String = "MCG:memories.generate.light"

  /** I campi di `bench-dataset.json` che la Fase 9 aggiunge (`BenchDataset`, SHAPE_VERSION 4). */
  const val FIELD_SHELF: String = "ricordiSeminati"
  const val FIELD_POSITIONS: String = "righePosizione"
  const val FIELD_ANNIVERSARY: String = "righeAnniversario"
  const val FIELD_SEED_DAY: String = "giornoSeme"

  /** Almeno un ricordo seminato e le righe con posizione sono il 55% (il 60% attesa meno il margine): senza, la misura non e' quella del §5.4. */
  const val MIN_POSITION_SHARE: Double = 0.55

  fun shelf(status: BenchDatasetStatus?): Int = BenchAppFiles.number(status, FIELD_SHELF)

  fun positions(status: BenchDatasetStatus?): Int = BenchAppFiles.number(status, FIELD_POSITIONS)

  /** Lo scaffale seminato c'e' e il dataset e' quello della Fase 9. */
  fun shelfSeeded(status: BenchDatasetStatus?): Boolean = shelf(status) >= 1

  /** Le righe con posizione sono almeno il [MIN_POSITION_SHARE] della Libreria (le coordinate della mappa). */
  fun positionsSeeded(status: BenchDatasetStatus?): Boolean {
    val rows = status?.rows ?: 0
    return rows > 0 && positions(status) >= rows * MIN_POSITION_SHARE
  }

  /** Perche' le prove dei ricordi si saltano, per chi legge il rapporto. */
  fun missingReason(status: BenchDatasetStatus?): String =
    "Il dataset non ha lo scaffale dei ricordi seminato (${shelf(status)} ricordi): lo scrive la variante di misura con il dataset della Fase 9 " +
      "(campo $FIELD_SHELF di bench-dataset.json, SHAPE_VERSION 4). Senza lo stato del dataset dell'app non c'e' niente da aprire."
}

/** Lo stato del pilota della generazione di un'iterazione (letto dalla prova, consegnato alle metriche proprie). */
object MemoriesBenchState {
  @Volatile var status: JSONObject? = null

  fun reset() {
    status = null
  }
}

/** Dal tocco su un ricordo di Raccolte al primo fotogramma con una cella della sua griglia (`memoryOpenMs`), misurato da UiAutomator. */
@OptIn(ExperimentalMetricApi::class)
class MemoryOpenMetric : TraceMetric() {

  override fun getMeasurements(captureInfo: Metric.CaptureInfo, traceSession: TraceProcessor.Session): List<Metric.Measurement> =
    listOf(Phase9BenchState.memoryOpenMs?.let { Metric.Measurement("memoryOpenMs", it) } ?: Metric.Measurement("memoryOpenMissing", 1.0))
}

/**
 * Le sezioni di generazione dei ricordi dell'iterazione: il passo pieno (`MCG:memories.generate`) e quello leggero (`MCG:memories.generate.light`).
 * `memoriesGenerateMs` e `memoriesLightMs` sono i piu' lunghi dell'iterazione (di solito uno: il pilota fa un giro pieno e uno leggero); `memoriesSections` quante
 * sezioni c'erano, per la validita' (se il buffer di Perfetto perde pacchetti mancano e `compare.ps1` lo dice). Senza sezioni: `memoriesSectionMissing` = 1.
 * Dallo stato del pilota arrivano gli stessi tempi misurati dentro l'app (`memoriesGenerateAppMs`, `memoriesLightAppMs`): un controllo incrociato, informativo.
 */
@OptIn(ExperimentalMetricApi::class)
class MemoriesGenerateMetric : TraceMetric() {

  override fun getMeasurements(captureInfo: Metric.CaptureInfo, traceSession: TraceProcessor.Session): List<Metric.Measurement> {
    val full = durations(traceSession, MemoriesBenchTarget.TRACE_GENERATE)
    val light = durations(traceSession, MemoriesBenchTarget.TRACE_GENERATE_LIGHT)
    val app = MemoriesBenchState.status
    val out = ArrayList<Metric.Measurement>()
    out += Metric.Measurement("memoriesSections", (full.size + light.size).toDouble())
    out += Metric.Measurement("tracePacketsLost", packetsLost(traceSession))
    if (full.isEmpty() && light.isEmpty()) {
      out += Metric.Measurement("memoriesSectionMissing", 1.0)
      val present = traceSession.query("SELECT name, COUNT(*) AS n, MIN(dur) AS d FROM slice WHERE name LIKE 'MCG:%' GROUP BY name")
        .map { it.string("name") + " x" + it.long("n") + " (dur min " + it.long("d") + ")" }.toList()
      android.util.Log.w("MCG-Bench", "sezioni dei ricordi assenti nella traccia; sezioni MCG presenti: " + present.joinToString("; ").ifEmpty { "nessuna" })
    }
    if (full.isNotEmpty()) out += Metric.Measurement("memoriesGenerateMs", full.max())
    if (light.isNotEmpty()) out += Metric.Measurement("memoriesLightMs", light.max())
    if (app != null && app.has("generazioneMs")) out += Metric.Measurement("memoriesGenerateAppMs", app.optDouble("generazioneMs"))
    if (app != null && app.has("passoLeggeroMs")) out += Metric.Measurement("memoriesLightAppMs", app.optDouble("passoLeggeroMs"))
    if (app != null && app.has("ricordiNuovi")) out += Metric.Measurement("memoriesPublished", app.optDouble("ricordiNuovi"))
    return out
  }

  private fun durations(session: TraceProcessor.Session, name: String): List<Double> =
    session.query("SELECT dur FROM slice WHERE name = '$name' AND dur > 0 ORDER BY ts").map { it.long("dur") / FrameQueries.NANOS_PER_MILLI }.toList()
}

/** Il dataset della Fase 9 c'era (1) o no (0): scaffale seminato; la validita' delle prove dei ricordi in `soglie.json`. */
@OptIn(ExperimentalMetricApi::class)
class MemoriesSeedMetric : TraceMetric() {

  override fun getMeasurements(captureInfo: Metric.CaptureInfo, traceSession: TraceProcessor.Session): List<Metric.Measurement> {
    val dataset = BenchState.dataset
    return listOf(
      Metric.Measurement("memoriesSeeded", if (MemoriesBenchTarget.shelfSeeded(dataset)) 1.0 else 0.0),
      Metric.Measurement("memoriesShelf", MemoriesBenchTarget.shelf(dataset).toDouble()),
    )
  }
}

/** La generazione lavorava mentre si misurava (1) o no (0): la validita' della guardia "con la generazione in corso" (`calcoloInCorso` del pilota). */
@OptIn(ExperimentalMetricApi::class)
class MemoriesComputingMetric : TraceMetric() {

  override fun getMeasurements(captureInfo: Metric.CaptureInfo, traceSession: TraceProcessor.Session): List<Metric.Measurement> =
    listOf(Metric.Measurement("memoriesComputing", if (Phase9BenchState.memoriesComputing) 1.0 else 0.0))
}

/** Quanto e' durata la presentazione misurata (ms): informativa, per controllare che fossero i 30 s. */
@OptIn(ExperimentalMetricApi::class)
class PresentationMetric : TraceMetric() {

  override fun getMeasurements(captureInfo: Metric.CaptureInfo, traceSession: TraceProcessor.Session): List<Metric.Measurement> =
    listOf(Metric.Measurement("presentationMs", Phase9BenchState.presentationMs.toDouble()))
}

/**
 * I gesti sulle Raccolte, sulla pagina di un ricordo e sulla presentazione, solo con l'albero di accessibilita' (come [AlbumUi] e [SearchUi]). Le etichette sono
 * quelle di `feature-memories` (`strings_memories_*.xml`) e `core-designsystem` (`tab_collections`); **se cambiano si cambiano qui**.
 */
object MemoriesUi {

  private val pkg: String get() = BenchSession.targetPackage

  /** La scheda "Raccolte" della barra (`tab_collections`). */
  private val TAB: BySelector get() = By.pkg(pkg).desc("Raccolte")

  /**
   * Un ricordo dello scaffale seminato nelle Raccolte (`MemoriesText.title`): "Un anno fa", "3 anni fa", "Viaggio di prova", "Evento di prova", "Il meglio di
   * agosto 2026". Il piu' recente per primo; il titolo del ricordo "Un anno fa" e' il primo dell'elenco.
   */
  private val MEMORY_TITLE: Pattern = Pattern.compile("^(Un anno fa|\\d+ anni fa|Viaggio di prova|Evento di prova|Il meglio di .+)$")

  /** Una cella della pagina di un ricordo: "Foto del 12 marzo 2025", "Video del ...", "Foto senza data" (`memorie_cell_*`). */
  val CELL: BySelector get() = By.pkg(pkg).desc(Pattern.compile("^(Foto del .+|Video del .+|Foto senza data)$"))

  /** "Riproduci" (`memorie_play`): il pulsante della pagina che avvia la presentazione. */
  private val PLAY: BySelector get() = By.pkg(pkg).text("Riproduci")

  /** "Pausa"/"Riproduci" del player e "Chiudi" (`memorie_player_*`): il player e' aperto quando c'e' il comando "Chiudi" o "Esci dalla presentazione". */
  private val PLAYER_CLOSE: BySelector get() = By.pkg(pkg).text(Pattern.compile("^(Chiudi|Esci dalla presentazione)$"))

  private const val TAB_TIMEOUT_MS = 8_000L
  private const val ROW_TIMEOUT_MS = 8_000L
  private const val PAGE_TIMEOUT_MS = 8_000L

  /** Apre le Raccolte; `false` se la scheda non c'e'. Aspetta la riga di un ricordo (lo scaffale seminato si disegna dal file, senza la Libreria). */
  fun openCollections(device: UiDevice): Boolean {
    BenchUi.ensureForeground(device)
    val tab = device.findObject(TAB) ?: device.findObject(By.pkg(pkg).text("Raccolte")) ?: return false
    tab.click()
    device.waitForIdle()
    return true
  }

  /** La riga di un ricordo nelle Raccolte, aspettandola; `null` se non compare (lo scaffale e' vuoto, o i ricordi non sono montati). */
  fun memoryRow(device: UiDevice): UiObject2? = device.wait(Until.findObject(By.pkg(pkg).text(MEMORY_TITLE)), ROW_TIMEOUT_MS)

  /**
   * Tocca la riga di un ricordo e restituisce i millisecondi fino alla prima cella della pagina; `null` se la riga non c'e' o la pagina non si apre. Le Raccolte non
   * hanno celle di foto: se ce ne fosse una prima del tocco la misura non direbbe niente, e la prova si ferma.
   */
  fun openMemory(device: UiDevice): Double? {
    val row = memoryRow(device) ?: return null
    check(!device.hasObject(CELL)) { "Una cella di ricordo c'e' gia' prima del tocco: la misura dell'apertura non direbbe niente." }
    BenchUi.ensureForeground(device)
    val start = System.nanoTime()
    row.click()
    val shown = device.wait(Until.hasObject(CELL), PAGE_TIMEOUT_MS) == true
    return if (shown) (System.nanoTime() - start) / NANOS_PER_MILLI else null
  }

  /** Avvia la presentazione dalla pagina di un ricordo; `false` se il pulsante o il player non compaiono. */
  fun play(device: UiDevice): Boolean {
    BenchUi.ensureForeground(device)
    val button = device.wait(Until.findObject(PLAY), PAGE_TIMEOUT_MS) ?: return false
    button.click()
    return device.wait(Until.hasObject(PLAYER_CLOSE), PAGE_TIMEOUT_MS) == true
  }

  /** La presentazione e' ancora aperta (non e' finita sul "Fine del ricordo", che resta aperta ma ferma). */
  fun playerOpen(device: UiDevice): Boolean = device.hasObject(PLAYER_CLOSE)

  /** La fine del ricordo: il player e' ancora aperto ma non si muove piu' (scheda finale, "Rivedi"). */
  fun playerEnded(device: UiDevice): Boolean = device.hasObject(By.pkg(pkg).text("Fine del ricordo"))

  /** Il pulsante "Rivedi" della scheda finale (ricomincia la presentazione). */
  fun restart(device: UiDevice): Boolean {
    val button = device.findObject(By.pkg(pkg).text("Rivedi")) ?: return false
    button.click()
    return true
  }

  private const val NANOS_PER_MILLI = 1_000_000.0
}
