package dev.pampa.mycloudgallery.benchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.Metric
import androidx.benchmark.macro.TraceMetric
import androidx.benchmark.traceprocessor.TraceProcessor

/**
 * L'apertura del viewer: la durata della sezione di traccia `MCG:viewerOpen` (dal tocco al primo fotogramma con
 * l'immagine, §2.6). La emette il viewer (P8): **sincrona o asincrona** (`Trace.beginAsyncSection`, perche' fra il tocco
 * e il primo fotogramma passano piu' fotogrammi). `TraceSectionMetric` di Macrobenchmark non e' una buona scelta per le
 * sezioni asincrone, quindi la query sulla tabella `slice` e' nostra: vale per tutte e due.
 *
 * Se la sezione non c'e' (il viewer di P8 non l'emette ancora) la misura non si inventa niente: scrive
 * `viewerSectionMissing = 1` e `compare.ps1` lo riporta come "sezione di traccia assente".
 */
@OptIn(ExperimentalMetricApi::class)
class ViewerOpenMetric : TraceMetric() {

  override fun getMeasurements(captureInfo: Metric.CaptureInfo, traceSession: TraceProcessor.Session): List<Metric.Measurement> {
    val sections = traceSession
      .query("SELECT ts, dur FROM slice WHERE name = '${BenchmarkTarget.TRACE_VIEWER_OPEN}' AND dur > 0 ORDER BY ts")
      .map { it.long("ts") to it.long("dur") }
      .toList()
    if (sections.isEmpty()) return listOf(Metric.Measurement("viewerSectionMissing", 1.0))
    // La prima apertura dell'iterazione: lo scenario ne fa una sola.
    val (start, dur) = sections.first()
    val frames = FrameQueries.appFrames(traceSession, captureInfo.targetPackageName, start, start + OPENING_WINDOW_NS)
    return listOfNotNull(
      Metric.Measurement("viewerOpenMs", dur / FrameQueries.NANOS_PER_MILLI),
      frames.maxCpuMs?.let { Metric.Measurement("viewerOpenMaxFrameMs", it) },
      Metric.Measurement("viewerOpenJankFrames", frames.late.toDouble()),
      Metric.Measurement("viewerOpenFrames", frames.total.toDouble()),
    )
  }

  private companion object {
    /**
     * L'apertura vista dall'utente: l'espansione dalla cella (`GalleryMotion.expandOpenSpec`, 300 ms) e i primi
     * fotogrammi a riposo (il pager che prende il posto della foto, le barre che compaiono). Lo scenario aspetta 800 ms
     * prima di Indietro: la finestra non tocca la chiusura.
     */
    const val OPENING_WINDOW_NS = 500_000_000L
  }
}

/**
 * I fotogrammi **davvero in ritardo** (FrameTimeline di SurfaceFlinger): `App Deadline Missed` nel tipo di jank (anche se
 * insieme ad altri) o `Dropped Frame` come presentazione, sul totale dei fotogrammi dell'app nel blocco misurato.
 *
 * Perche' non basta `frameOverrunMs` di `FrameTimingMetric`: a 120 Hz, mentre l'app produce un fotogramma dopo l'altro,
 * la coda di BufferQueue si riempie di uno ("Buffer Stuffing") e SurfaceFlinger presenta in orario un fotogramma che,
 * contato dall'inizio teorico, "sfora" di qualche millisecondo. Sull'S25 (13/10) erano 740 fotogrammi su 800 nel pinch,
 * tutti presentati in orario: `frameOverrunMs` P50 +3,1 ms senza nessuno scatto visibile. Il jank vero e' quello che
 * SurfaceFlinger attribuisce all'app (scadenza mancata) o che non arriva allo schermo.
 *
 * Misure: `jankPercent` (0-100), `jankFrames`, `timelineFrames`, e `maxFrameCpuMs` (il fotogramma piu' lungo, dal
 * `Choreographer#doFrame` alla fine del `DrawFrames` del RenderThread).
 */
@OptIn(ExperimentalMetricApi::class)
class FrameJankMetric : TraceMetric() {

  override fun getMeasurements(captureInfo: Metric.CaptureInfo, traceSession: TraceProcessor.Session): List<Metric.Measurement> {
    val start = FrameQueries.measureStart(traceSession)
    val frames = FrameQueries.appFrames(traceSession, captureInfo.targetPackageName, start, null)
    if (frames.total == 0L) return listOf(Metric.Measurement("timelineFrames", 0.0))
    return listOfNotNull(
      Metric.Measurement("jankPercent", frames.late * 100.0 / frames.total),
      Metric.Measurement("jankFrames", frames.late.toDouble()),
      Metric.Measurement("timelineFrames", frames.total.toDouble()),
      frames.maxCpuMs?.let { Metric.Measurement("maxFrameCpuMs", it) },
    )
  }
}

/** I fotogrammi dell'app in un intervallo: quanti, quanti in ritardo, il piu' lungo (ms di CPU, main + RenderThread). */
class AppFrames(val total: Long, val late: Long, val maxCpuMs: Double?)

/** Le query di Perfetto sui fotogrammi, comuni a [FrameJankMetric] e [ViewerOpenMetric]. */
object FrameQueries {

  const val NANOS_PER_MILLI = 1_000_000.0

  /**
   * L'inizio del blocco misurato (`measureBlock`, la sezione che Macrobenchmark scrive), o null se la traccia non lo ha.
   * Solo l'inizio: la traccia si ferma subito dopo il blocco, e la fine della sezione si puo' perdere quando il buffer
   * di Perfetto perde pacchetti (sull'S25 una sezione di 0,97 s per un pinch di 7 s, "traced_buf_sequence_packet_loss").
   */
  fun measureStart(session: TraceProcessor.Session): Long? =
    session.query("SELECT ts FROM slice WHERE name = 'measureBlock' ORDER BY ts LIMIT 1")
      .map { it.long("ts") }
      .firstOrNull()

  /** I fotogrammi del processo [pkg] che cominciano in `[from, to)`; un estremo null non limita. */
  fun appFrames(session: TraceProcessor.Session, pkg: String, from: Long?, to: Long?): AppFrames {
    val range = (if (from != null) "AND a.ts >= $from " else "") + (if (to != null) "AND a.ts < $to" else "")
    val counts = session.query(
      """
      SELECT COUNT(*) AS total,
             COALESCE(SUM(CASE WHEN a.jank_type GLOB '*App Deadline Missed*' OR a.present_type = 'Dropped Frame' THEN 1 ELSE 0 END), 0) AS late
      FROM actual_frame_timeline_slice a JOIN process p USING(upid)
      WHERE p.name = '$pkg' $range
      """.trimIndent(),
    ).firstOrNull()
    val doFrameRange = (if (from != null) "AND s.ts >= $from " else "") + (if (to != null) "AND s.ts < $to" else "")
    // Il fotogramma "di CPU" come in FrameTimingMetric: dall'inizio del doFrame (thread principale) alla fine del
    // DrawFrames con lo stesso id di vsync (RenderThread); senza DrawFrames (niente da disegnare) conta il solo doFrame.
    val maxCpu = session.query(
      """
      WITH main AS (
        SELECT CAST(substr(s.name, 23) AS INT) AS vsync, s.ts, s.dur
        FROM slice s JOIN thread_track tt ON s.track_id = tt.id JOIN thread t USING(utid) JOIN process p USING(upid)
        WHERE p.name = '$pkg' AND t.tid = p.pid AND s.depth = 0 AND s.name GLOB 'Choreographer#doFrame [0-9]*' $doFrameRange
      ), rt AS (
        SELECT CAST(substr(s.name, 12) AS INT) AS vsync, MAX(s.ts + s.dur) AS end_ts
        FROM slice s JOIN thread_track tt ON s.track_id = tt.id JOIN thread t USING(utid) JOIN process p USING(upid)
        WHERE p.name = '$pkg' AND t.name = 'RenderThread' AND s.name GLOB 'DrawFrames [0-9]*' AND s.dur >= 0
        GROUP BY 1
      )
      SELECT MAX(MAX(COALESCE(rt.end_ts, main.ts + main.dur), main.ts + main.dur) - main.ts) AS cpu
      FROM main LEFT JOIN rt USING(vsync)
      """.trimIndent(),
    ).firstOrNull()?.nullableLong("cpu")
    return AppFrames(
      total = counts?.long("total") ?: 0L,
      late = counts?.long("late") ?: 0L,
      maxCpuMs = maxCpu?.let { it / NANOS_PER_MILLI },
    )
  }
}

/**
 * La memoria di picco dello zoom a 200 MP: la PSS dell'app campionata con `dumpsys meminfo` durante il gesto, piu' "il
 * processo e' ancora vivo" (nessun OOM). Macrobenchmark non misura la PSS: lo scenario campiona con [BenchPss.sample]
 * dentro il blocco di misura e questa metrica ne consegna il massimo alla fine dell'iterazione.
 */
@OptIn(ExperimentalMetricApi::class)
class PssPeakMetric : TraceMetric() {

  override fun getMeasurements(captureInfo: Metric.CaptureInfo, traceSession: TraceProcessor.Session): List<Metric.Measurement> =
    listOf(
      Metric.Measurement("pssPeakKb", BenchPss.peakKb),
      Metric.Measurement("processAlive", if (BenchPss.aliveAtEnd) 1.0 else 0.0),
    )
}

/**
 * "La Libreria si vedeva": 1 se, alla fine dell'iterazione, la prima cella era sullo schermo. Serve all'avvio a freddo:
 * senza Libreria (onboarding, prima di M1) l'avvio misurato non e' quello dei [soglie.json], e `compare.ps1` lo dice
 * invece di confrontare il numero sbagliato con 1000 ms.
 */
@OptIn(ExperimentalMetricApi::class)
class LibraryShownMetric : TraceMetric() {

  override fun getMeasurements(captureInfo: Metric.CaptureInfo, traceSession: TraceProcessor.Session): List<Metric.Measurement> =
    listOf(Metric.Measurement("libraryShown", if (BenchState.libraryShown) 1.0 else 0.0))
}

/** Lo stato che le prove consegnano alle metriche proprie (`LibraryShownMetric`). */
object BenchState {
  @Volatile var libraryShown: Boolean = false
}

/** Il campionatore della PSS. Uno stato per processo di test: lo scenario lo azzera a ogni iterazione. */
object BenchPss {

  @Volatile var peakKb: Double = 0.0
    private set

  @Volatile var aliveAtEnd: Boolean = false
    private set

  private var pidAtStart: String = ""

  fun reset() {
    peakKb = 0.0
    aliveAtEnd = false
    pidAtStart = pid()
  }

  /** Legge la PSS totale dell'app (KB) e tiene il massimo. */
  fun sample() {
    val text = BenchSession.shell("dumpsys meminfo ${BenchSession.targetPackage}")
    val value = PSS_TOTAL.find(text)?.groupValues?.get(1)?.toDoubleOrNull()
      ?: PSS_TABLE_TOTAL.find(text)?.groupValues?.get(1)?.toDoubleOrNull()
      ?: return
    if (value > peakKb) peakKb = value
  }

  /** Alla fine dell'iterazione: il processo e' lo stesso di quando e' cominciata (se e' morto per OOM, il pid cambia o manca). */
  fun finish() {
    sample()
    val now = pid()
    aliveAtEnd = now.isNotEmpty() && now == pidAtStart
  }

  private fun pid(): String = BenchSession.shell("pidof ${BenchSession.targetPackage}").trim().substringBefore(' ')

  // "TOTAL PSS:   284351            TOTAL RSS:   410000 ..." (Android 10+) oppure la riga "TOTAL  284351 ..." della tabella.
  private val PSS_TOTAL = Regex("TOTAL PSS:\\s*(\\d+)")
  private val PSS_TABLE_TOTAL = Regex("(?m)^\\s*TOTAL\\s+(\\d+)")
}
