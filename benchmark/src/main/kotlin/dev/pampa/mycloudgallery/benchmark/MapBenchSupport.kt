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

/**
 * Le cose di `MapBenchmark` (Fase 9, P12; `docs/fase9-progetto.md` §5.4, scenario `mappa`): i nomi che l'app deve rispettare, le metriche della traccia e i
 * gesti sulla mappa.
 *
 * **Cosa c'e' nell'app (variante `benchmarkRelease`)**:
 *  - il dataset da 100 mila righe ha il **60% delle righe con una posizione** plausibile (campo [MemoriesBenchTarget.FIELD_POSITIONS]);
 *  - la **mappa dettagliata e' spenta dalla variante** (`MeasurementHooks.mapOnlineAllowed = false`): la carta e' solo quella di base offline, non parte nessuna
 *    richiesta verso OpenFreeMap e le impostazioni del dispositivo non si toccano. Le prove **non** accendono mai lo stile online;
 *  - la sezione di traccia [TRACE_CLUSTERS] (`BubbleEngine.compute`) e il contatore [COUNTER_DRAW_US] (il tempo di disegno di ogni fotogramma di MapLibre, in
 *    microsecondi, dalla superficie della mappa).
 */
object MapBenchTarget {

  /** La sezione di traccia delle bolle (`AvvioTrace.MAP_CLUSTERS`). */
  const val TRACE_CLUSTERS: String = "MCG:map.clusters"

  /** Il contatore di traccia del tempo di disegno di MapLibre, in microsecondi (`AvvioTrace.MAP_DRAW_US`). */
  const val COUNTER_DRAW_US: String = "MCG:map.drawUs"

  /** Perche' le prove della mappa si saltano senza coordinate nel dataset. */
  fun missingReason(status: BenchDatasetStatus?): String =
    "Il dataset non ha il 60% delle righe con una posizione (${MemoriesBenchTarget.positions(status)} su ${status?.rows ?: 0}): le coordinate le scrive la variante " +
      "di misura con il dataset della Fase 9 (campo ${MemoriesBenchTarget.FIELD_POSITIONS} di bench-dataset.json, SHAPE_VERSION 4)."
}

/**
 * Quanti pacchetti ha perso il buffer di Perfetto in questa traccia (`stats` di Perfetto): se e' piu' di zero le sezioni piu' recenti (o piu' vecchie, secondo la
 * politica del buffer) possono mancare, e un percentile calcolato sulle rimaste non vale. Informativa (`tracePacketsLost`), accanto alla validita' sul numero delle
 * sezioni.
 */
internal fun packetsLost(session: TraceProcessor.Session): Double =
  session.query("SELECT COALESCE(SUM(value), 0) AS lost FROM stats WHERE name IN ('traced_buf_chunks_overwritten', 'traced_buf_trace_writer_packet_loss', 'traced_buf_sequence_packet_loss')")
    .map { it.long("lost").toDouble() }.firstOrNull() ?: 0.0

/** I tempi di una sezione di traccia dell'iterazione: p50, p95 e massimo, e quante erano (la validita': se il buffer di Perfetto perde pacchetti ne mancano). */
@OptIn(ExperimentalMetricApi::class)
internal fun sectionStats(session: TraceProcessor.Session, name: String, prefix: String): List<Metric.Measurement> {
  val durations = session.query("SELECT dur FROM slice WHERE name = '$name' AND dur > 0 ORDER BY dur")
    .map { it.long("dur") / FrameQueries.NANOS_PER_MILLI }.toList()
  if (durations.isEmpty()) {
    // Il motivo per chi legge il log: quali sezioni `MCG:` ci sono nella traccia (e con quale durata minima: una sezione aperta e mai chiusa ha durata < 0).
    val present = session.query("SELECT name, COUNT(*) AS n, MIN(dur) AS d FROM slice WHERE name LIKE 'MCG:%' GROUP BY name")
      .map { it.string("name") + " x" + it.long("n") + " (dur min " + it.long("d") + ")" }.toList()
    android.util.Log.w("MCG-Bench", "sezione $name assente nella traccia; sezioni MCG presenti: " + present.joinToString("; ").ifEmpty { "nessuna" })
    return listOf(Metric.Measurement("${prefix}SectionMissing", 1.0), Metric.Measurement("${prefix}Sections", 0.0), Metric.Measurement("tracePacketsLost", packetsLost(session)))
  }
  return listOf(
    Metric.Measurement("tracePacketsLost", packetsLost(session)),
    Metric.Measurement("${prefix}P50Ms", SearchFirstResultsMetric.percentile(durations, 50.0)),
    Metric.Measurement("${prefix}P95Ms", SearchFirstResultsMetric.percentile(durations, 95.0)),
    Metric.Measurement("${prefix}MaxMs", durations.last()),
    Metric.Measurement("${prefix}Sections", durations.size.toDouble()),
  )
}

/** L'aggiornamento delle bolle a camera ferma: le sezioni `MCG:map.clusters` dell'iterazione (`mapClustersP95Ms`, `mapClustersP50Ms`, `mapClustersMaxMs`, `mapClustersSections`). */
@OptIn(ExperimentalMetricApi::class)
class MapClustersMetric : TraceMetric() {

  override fun getMeasurements(captureInfo: Metric.CaptureInfo, traceSession: TraceProcessor.Session): List<Metric.Measurement> =
    sectionStats(traceSession, MapBenchTarget.TRACE_CLUSTERS, "mapClusters")
}

/**
 * Il tempo di disegno di MapLibre per fotogramma: i valori del contatore `MCG:map.drawUs` (in microsecondi, scritto a ogni fotogramma disegnato) come
 * millisecondi: `mapDrawP50Ms`, `mapDrawP95Ms`, `mapDrawMaxMs` e `mapDrawFrames` (quanti, per la validita'). Senza il contatore (la mappa non si e' disegnata,
 * o MapLibre non da' i tempi) `mapDrawSectionMissing` = 1: mai un numero inventato.
 */
@OptIn(ExperimentalMetricApi::class)
class MapDrawMetric : TraceMetric() {

  override fun getMeasurements(captureInfo: Metric.CaptureInfo, traceSession: TraceProcessor.Session): List<Metric.Measurement> {
    val millis = traceSession.query(
      """
      SELECT c.value AS value FROM counter c JOIN counter_track t ON c.track_id = t.id
      WHERE t.name = '${MapBenchTarget.COUNTER_DRAW_US}' ORDER BY c.value
      """.trimIndent(),
    ).map { it.double("value") / MICROS_PER_MILLI }.toList()
    if (millis.isEmpty()) return listOf(Metric.Measurement("mapDrawSectionMissing", 1.0), Metric.Measurement("mapDrawFrames", 0.0))
    return listOf(
      Metric.Measurement("tracePacketsLost", packetsLost(traceSession)),
      Metric.Measurement("mapDrawP50Ms", SearchFirstResultsMetric.percentile(millis, 50.0)),
      Metric.Measurement("mapDrawP95Ms", SearchFirstResultsMetric.percentile(millis, 95.0)),
      Metric.Measurement("mapDrawMaxMs", millis.last()),
      Metric.Measurement("mapDrawFrames", millis.size.toDouble()),
    )
  }

  private companion object {
    const val MICROS_PER_MILLI = 1_000.0
  }
}

/** Dal tocco su "Mappa" nelle Raccolte alla prima bolla visibile (`mapOpenMs`), misurato da UiAutomator. */
@OptIn(ExperimentalMetricApi::class)
class MapOpenMetric : TraceMetric() {

  override fun getMeasurements(captureInfo: Metric.CaptureInfo, traceSession: TraceProcessor.Session): List<Metric.Measurement> =
    listOf(Phase9BenchState.mapOpenMs?.let { Metric.Measurement("mapOpenMs", it) } ?: Metric.Measurement("mapOpenMissing", 1.0))
}

/** Dal tocco su una bolla alla prima cella della griglia della zona (`zoneOpenMs`), misurato da UiAutomator. */
@OptIn(ExperimentalMetricApi::class)
class ZoneOpenMetric : TraceMetric() {

  override fun getMeasurements(captureInfo: Metric.CaptureInfo, traceSession: TraceProcessor.Session): List<Metric.Measurement> =
    listOf(Phase9BenchState.zoneOpenMs?.let { Metric.Measurement("zoneOpenMs", it) } ?: Metric.Measurement("zoneOpenMissing", 1.0))
}

/** Il dataset aveva le posizioni (1) o no (0): la validita' delle prove della mappa in `soglie.json`. */
@OptIn(ExperimentalMetricApi::class)
class MapSeedMetric : TraceMetric() {

  override fun getMeasurements(captureInfo: Metric.CaptureInfo, traceSession: TraceProcessor.Session): List<Metric.Measurement> {
    val dataset = BenchState.dataset
    return listOf(
      Metric.Measurement("mapSeeded", if (MemoriesBenchTarget.positionsSeeded(dataset)) 1.0 else 0.0),
      Metric.Measurement("mapPositions", MemoriesBenchTarget.positions(dataset).toDouble()),
    )
  }
}

/** La mappa si era aperta (1) prima della misura: la validita' della guardia "con la mappa aperta in un'altra scheda". */
@OptIn(ExperimentalMetricApi::class)
class MapWasOpenedMetric : TraceMetric() {

  override fun getMeasurements(captureInfo: Metric.CaptureInfo, traceSession: TraceProcessor.Session): List<Metric.Measurement> =
    listOf(Metric.Measurement("mapWasOpened", if (Phase9BenchState.mapOpened) 1.0 else 0.0))
}

/** La PSS dell'app a mappa aperta (`BenchMemory.sample`): `mapPssKb`, con heap Java, nativo e grafica (informativi). */
@OptIn(ExperimentalMetricApi::class)
class MapMemoryMetric : TraceMetric() {

  override fun getMeasurements(captureInfo: Metric.CaptureInfo, traceSession: TraceProcessor.Session): List<Metric.Measurement> =
    listOfNotNull(
      BenchMemory.pssKb?.let { Metric.Measurement("mapPssKb", it) },
      BenchMemory.javaHeapKb?.let { Metric.Measurement("mapJavaHeapKb", it) },
      BenchMemory.nativeHeapKb?.let { Metric.Measurement("mapNativeHeapKb", it) },
      BenchMemory.graphicsKb?.let { Metric.Measurement("mapGraphicsKb", it) },
    )
}

/**
 * I gesti sulla mappa e sulla griglia di una zona, solo con l'albero di accessibilita'. Le bolle sono composable con la frase di TalkBack come descrizione
 * (`MapText.bubble`: "12 foto" o "12 foto, Firenze"); la carta e' una `MapView` che l'albero non vede, quindi i gesti sono sullo schermo. Le etichette sono quelle
 * di `feature-map` (`strings_map_*.xml`): **se cambiano si cambiano qui**.
 */
object MapUi {

  private val pkg: String get() = BenchSession.targetPackage

  /** La riga "Mappa" della sezione Luoghi (`mappa_row_map`). */
  private val MAP_ROW: BySelector get() = By.pkg(pkg).text("Mappa")

  /** Una bolla: "1.240 foto" o "1.240 foto, Firenze" (`MapText.bubble`). */
  private val BUBBLE_PATTERN: Pattern = Pattern.compile("^([0-9.]+) foto(, .+)?$")
  // Il nodo con la frase di TalkBack non e' quello cliccabile (lo e' il suo vicino con lo stesso riquadro): il tocco sul centro del nodo arriva alla bolla.
  private val BUBBLE: BySelector get() = By.pkg(pkg).desc(BUBBLE_PATTERN)

  /** Una cella della griglia di una zona: "Foto del 12 marzo 2025", "Video del ..." (`mappa_cell_*`) o, come la Libreria, "Foto, 12 marzo 2025". */
  val ZONE_CELL: BySelector get() = By.pkg(pkg).desc(Pattern.compile("^((Foto|Video|Foto RAW|Foto in movimento|Immagine animata)(,| del) .*|Foto senza data)$"))

  /** Il titolo della griglia di una zona (`mappa_zone_title`): c'e' solo a griglia aperta. */
  private val ZONE_TITLE: BySelector get() = By.pkg(pkg).text("Questa zona")

  private const val ROW_SHORT_TIMEOUT_MS = 1_500L
  private const val ROW_SEARCH_SCROLLS = 8
  private const val ROW_SAFE_BOTTOM = 0.82
  private const val LIST_SAFE_TOP = 0.20
  private const val LIST_SAFE_BOTTOM_MARGIN = 0.18
  private const val ROW_SCROLL_PERCENT = 0.6f
  private const val BUBBLE_TIMEOUT_MS = 15_000L
  private const val ZONE_TIMEOUT_MS = 8_000L
  private const val NANOS_PER_MILLI = 1_000_000.0
  private const val MAX_ZOOM_TAPS = 8
  private const val GESTURE_MARGIN_DIVISOR = 8
  private const val SWIPE_STEPS = 24
  private const val PINCH_PERCENT = 0.5f
  private const val PINCH_SPEED = 600

  /** Le bolle visibili adesso. */
  fun bubbles(device: UiDevice): List<UiObject2> = device.findObjects(BUBBLE)

  /** I conti delle bolle visibili, dalla frase di TalkBack (il numero prima di "foto"); 0 se non si legge. */
  fun countOf(bubble: UiObject2): Int = try {
    BUBBLE_PATTERN.matcher(bubble.contentDescription ?: "").takeIf { it.matches() }?.group(1)?.replace(".", "")?.toIntOrNull() ?: 0
  } catch (e: androidx.test.uiautomator.StaleObjectException) {
    0
  }

  /**
   * La riga "Mappa" della sezione Luoghi nelle Raccolte: sta sotto Album e Ricordi, quindi si scorre l'elenco finche' compare **sopra la pillola** (un tocco
   * sotto la pillola finirebbe sulla barra); `null` se non c'e' dopo [ROW_SEARCH_SCROLLS] scorrimenti.
   */
  fun mapRow(device: UiDevice): UiObject2? {
    device.wait(Until.findObject(MAP_ROW), ROW_SHORT_TIMEOUT_MS)
    repeat(ROW_SEARCH_SCROLLS) {
      val row = device.findObject(MAP_ROW)
      if (row != null && runCatching { row.visibleBounds.bottom < device.displayHeight * ROW_SAFE_BOTTOM }.getOrDefault(false)) return row
      val list = BenchUi.grid(device) ?: return null
      // Margini sicuri come `BenchUi.fling`: il gesto non deve cominciare sulla pillola in basso (non scorrerebbe l'elenco) ne' sulla barra in alto.
      val w = device.displayWidth
      val h = device.displayHeight
      list.setGestureMargins(w / GESTURE_MARGIN_DIVISOR, (h * LIST_SAFE_TOP).toInt(), w / GESTURE_MARGIN_DIVISOR, (h * LIST_SAFE_BOTTOM_MARGIN).toInt())
      list.scroll(androidx.test.uiautomator.Direction.DOWN, ROW_SCROLL_PERCENT)
      device.waitForIdle()
    }
    return device.findObject(MAP_ROW)
  }

  /**
   * Tocca la riga "Mappa" e restituisce i millisecondi fino alla prima bolla visibile; `null` se la riga non c'e' o le bolle non compaiono (nessun punto, o la
   * mappa non parte). Una bolla gia' presente prima del tocco non direbbe niente: la prova si ferma.
   */
  fun open(device: UiDevice): Double? {
    val row = mapRow(device) ?: return null
    check(!device.hasObject(BUBBLE)) { "Una bolla c'e' gia' prima del tocco: la misura dell'apertura non direbbe niente." }
    BenchUi.ensureForeground(device)
    val start = System.nanoTime()
    row.click()
    val shown = device.wait(Until.hasObject(BUBBLE), BUBBLE_TIMEOUT_MS) == true
    return if (shown) (System.nanoTime() - start) / NANOS_PER_MILLI else null
  }

  /** Aspetta che la mappa si assesti (camera ferma, bolle composte). */
  fun settle(device: UiDevice) {
    device.waitForIdle()
    Thread.sleep(SETTLE_MS)
  }

  /**
   * Un trascinamento sulla mappa: orizzontale (`dx` = 1 verso destra, -1 verso sinistra) o verticale (`dy`), nella **fascia bassa** dello schermo: le bolle sono
   * composable che prendono il tocco (un trascinamento che parte su una bolla non sposta la mappa) e a zoom basso stanno attorno al centro; la chrome in alto e la
   * pillola in basso restano fuori.
   */
  fun pan(device: UiDevice, dx: Int, dy: Int) {
    BenchUi.ensureForeground(device)
    val w = device.displayWidth
    val h = device.displayHeight
    val cx = w / 2
    val cy = (h * PAN_CENTER_Y).toInt()
    device.swipe(cx - dx * w / 5, cy - dy * h / 12, cx + dx * w / 5, cy + dy * h / 12, SWIPE_STEPS)
  }

  /**
   * Un pizzico nella fascia bassa della mappa (lontano dalle bolle): [close] avvicina le dita (ci si allontana), altrimenti le allontana (ci si avvicina). Il gesto e'
   * del contenitore dell'activity con i margini che ne portano il centro nella fascia bassa.
   */
  fun pinch(device: UiDevice, close: Boolean) {
    BenchUi.ensureForeground(device)
    val surface = device.findObject(By.res("android:id/content")) ?: return
    val h = device.displayHeight
    surface.setGestureMargins(0, (h * PINCH_TOP).toInt(), 0, (h * PINCH_BOTTOM).toInt())
    if (close) surface.pinchClose(PINCH_PERCENT, PINCH_SPEED) else surface.pinchOpen(PINCH_PERCENT, PINCH_SPEED)
  }

  /**
   * Apre la griglia di una zona: tocca la bolla piu' piccola (sotto 30 foto la bolla apre la griglia, D28) e, se quella e' ancora un gruppo grande,
   * si avvicina a ogni tocco finche' una bolla apre la griglia (al piu' [MAX_ZOOM_TAPS] tocchi). Restituisce i millisecondi dall'**ultimo** tocco alla prima
   * cella; `null` se la griglia non si apre.
   */
  fun openZone(device: UiDevice): Double? {
    repeat(MAX_ZOOM_TAPS) {
      BenchUi.ensureForeground(device)
      val bubbles = bubbles(device)
      val target = bubbles.minByOrNull { countOf(it).let { count -> if (count <= 0) Int.MAX_VALUE else count } } ?: return null
      val opensGrid = countOf(target) in 1..GRID_BELOW
      val start = System.nanoTime()
      try {
        target.click()
      } catch (e: androidx.test.uiautomator.StaleObjectException) {
        return@repeat
      }
      if (opensGrid) {
        val shown = device.wait(Until.hasObject(ZONE_CELL), ZONE_TIMEOUT_MS) == true
        return if (shown) (System.nanoTime() - start) / NANOS_PER_MILLI else null
      }
      // Un gruppo grande: la mappa si avvicina, le bolle si rifanno; si riprova dopo l'assestamento.
      settle(device)
      if (device.hasObject(ZONE_CELL)) return (System.nanoTime() - start) / NANOS_PER_MILLI
    }
    return null
  }

  /** La griglia di una zona e' aperta. */
  fun zoneOpen(device: UiDevice): Boolean = device.hasObject(ZONE_TITLE) || device.hasObject(ZONE_CELL)

  /**
   * Tocca la cella piu' vicina al centro dello schermo nella griglia della zona (per aprire il viewer), lontana dalla barra in alto e dalla pillola in basso.
   * Le celle si rileggono se la lista si ricompone sotto (le miniature arrivano: `StaleObjectException`); il tocco e' sul centro letto, senza tenere l'oggetto.
   * `false` se non c'e' nessuna cella da toccare.
   */
  fun tapCenterZoneCell(device: UiDevice): Boolean {
    BenchUi.ensureForeground(device)
    val w = device.displayWidth
    val h = device.displayHeight
    repeat(STALE_RETRIES) {
      device.waitForIdle()
      try {
        val target = device.findObjects(ZONE_CELL)
          .map { it.visibleBounds }
          .filter { b -> b.width() > 0 && b.top > h * ZONE_SAFE_TOP && b.bottom < h * ZONE_SAFE_BOTTOM }
          .minByOrNull { b -> Math.hypot(b.centerX() - w / 2.0, b.centerY() - h / 2.0) }
        if (target != null) {
          device.click(target.centerX(), target.centerY())
          return true
        }
      } catch (stale: androidx.test.uiautomator.StaleObjectException) {
        // la lista si e' rifatta mentre la si leggeva: si rilegge
      }
    }
    return false
  }

  /** Quante foto al piu' ha una bolla che apre la griglia (`MapPoints.GRID_BELOW`, D28). */
  private const val GRID_BELOW = 30
  private const val SETTLE_MS = 600L
  private const val STALE_RETRIES = 3
  private const val PAN_CENTER_Y = 0.72
  private const val PINCH_TOP = 0.55
  private const val PINCH_BOTTOM = 0.10
  // La griglia di una zona con poche foto ha la prima riga subito sotto il titolo (circa il 16% dell'altezza): il limite in alto non deve escluderla.
  private const val ZONE_SAFE_TOP = 0.15
  private const val ZONE_SAFE_BOTTOM = 0.88
}
