package dev.pampa.mycloudgallery.benchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.Metric
import androidx.benchmark.macro.TraceMetric
import androidx.benchmark.traceprocessor.TraceProcessor

/**
 * Il **primo disegno utile** dell'avvio a freddo (Avvio istantaneo, 28/10): dall'inizio del lancio (la sezione `launching:`
 * che il sistema apre al tocco sull'icona, la stessa da cui parte `timeToInitialDisplayMs`) alla fine del primo fotogramma
 * del RenderThread che segue il segno [BenchmarkTarget.TRACE_LIBRARY_USEFUL]: la Libreria con le miniature delle celle in
 * vista. E' il numero che conta per l'utente ("si vede subito qualcosa di vero"); `timeToFullDisplayMs` resta il caricamento
 * completo (`reportFullyDrawn`, con i dati vivi).
 *
 * Misure:
 * - `libraryUsefulMs`: il primo disegno utile;
 * - `libraryLiveMs`: i dati vivi al posto dell'istantanea (segno [BenchmarkTarget.TRACE_LIBRARY_LIVE], fotogramma dopo);
 * - `avvio<Segno>Ms`: i segni intermedi (sblocco, destinazione, istantanea letta, prima griglia), per leggere dove va il tempo;
 * - `libraryUsefulMissing` = 1 se il segno non c'e' (un'app vecchia, o la Libreria non raggiunta): `compare.ps1` lo dice
 *   invece di inventare un numero.
 */
@OptIn(ExperimentalMetricApi::class)
class LibraryUsefulMetric : TraceMetric() {

  override fun getMeasurements(captureInfo: Metric.CaptureInfo, traceSession: TraceProcessor.Session): List<Metric.Measurement> {
    val pkg = captureInfo.targetPackageName
    val launch = traceSession
      .query("SELECT ts FROM slice WHERE name = 'launching: $pkg' ORDER BY ts LIMIT 1")
      .map { it.long("ts") }
      .firstOrNull()
      ?: return listOf(Metric.Measurement("libraryUsefulMissing", 1.0))
    val out = ArrayList<Metric.Measurement>()
    val useful = AvvioQueries.markTs(traceSession, pkg, BenchmarkTarget.TRACE_LIBRARY_USEFUL)
    if (useful == null) {
      out += Metric.Measurement("libraryUsefulMissing", 1.0)
    } else {
      val end = AvvioQueries.frameEndAfter(traceSession, pkg, useful) ?: useful
      out += Metric.Measurement("libraryUsefulMs", (end - launch) / FrameQueries.NANOS_PER_MILLI)
    }
    AvvioQueries.markTs(traceSession, pkg, BenchmarkTarget.TRACE_LIBRARY_LIVE)?.let { live ->
      val end = AvvioQueries.frameEndAfter(traceSession, pkg, live) ?: live
      out += Metric.Measurement("libraryLiveMs", (end - launch) / FrameQueries.NANOS_PER_MILLI)
    }
    for ((name, metric) in BenchmarkTarget.TRACE_STARTUP_STEPS) {
      AvvioQueries.markTs(traceSession, pkg, name)?.let { out += Metric.Measurement(metric, (it - launch) / FrameQueries.NANOS_PER_MILLI) }
    }
    return out
  }
}

/**
 * L'apertura di una pagina principale (Raccolte, un album, Persone, Cerca a riposo): la durata della sezione asincrona
 * [section] che l'app apre al tocco e chiude al primo fotogramma con l'ultimo stato o lo scheletro, piu' il fotogramma del
 * RenderThread che la segue. Una sola apertura per iterazione. Senza la sezione: `<nome>Missing` = 1.
 */
@OptIn(ExperimentalMetricApi::class)
class PageOpenMetric(private val section: String, private val name: String) : TraceMetric() {

  override fun getMeasurements(captureInfo: Metric.CaptureInfo, traceSession: TraceProcessor.Session): List<Metric.Measurement> {
    val pkg = captureInfo.targetPackageName
    val row = traceSession
      .query("SELECT ts, dur FROM slice WHERE name = '$section' AND dur > 0 ORDER BY ts LIMIT 1")
      .map { it.long("ts") to it.long("dur") }
      .firstOrNull()
      ?: return listOf(Metric.Measurement("${name}Missing", 1.0))
    val (start, dur) = row
    val end = AvvioQueries.frameEndAfter(traceSession, pkg, start + dur) ?: (start + dur)
    return listOf(Metric.Measurement("${name}Ms", (end - start) / FrameQueries.NANOS_PER_MILLI))
  }
}

/** Le query comuni delle misure dell'avvio. */
object AvvioQueries {

  /** Il primo segno [name] scritto dal processo [pkg], o null. */
  fun markTs(session: TraceProcessor.Session, pkg: String, name: String): Long? =
    session.query(
      """
      SELECT s.ts AS ts FROM slice s JOIN thread_track tt ON s.track_id = tt.id JOIN thread t USING(utid) JOIN process p USING(upid)
      WHERE p.name = '$pkg' AND s.name = '$name' ORDER BY s.ts LIMIT 1
      """.trimIndent(),
    ).map { it.long("ts") }.firstOrNull()

  /** La fine del primo `DrawFrames` del RenderThread di [pkg] che comincia da [from] in poi: quando il fotogramma e' pronto. */
  fun frameEndAfter(session: TraceProcessor.Session, pkg: String, from: Long): Long? =
    session.query(
      """
      SELECT s.ts + s.dur AS end_ts FROM slice s JOIN thread_track tt ON s.track_id = tt.id JOIN thread t USING(utid) JOIN process p USING(upid)
      WHERE p.name = '$pkg' AND t.name = 'RenderThread' AND s.name GLOB 'DrawFrames *' AND s.ts >= $from AND s.dur >= 0
      ORDER BY s.ts LIMIT 1
      """.trimIndent(),
    ).map { it.long("end_ts") }.firstOrNull()
}
