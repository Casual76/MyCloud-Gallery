package dev.pampa.mycloudgallery.benchmark

import android.util.Log
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.Metric
import androidx.benchmark.macro.TraceMetric
import androidx.benchmark.traceprocessor.TraceProcessor

/**
 * Diagnosi dei fotogrammi piu' lunghi: **non produce numeri** per `compare.ps1`, scrive nel logcat (etichetta `MCGdiag`)
 * i fotogrammi piu' costosi del blocco misurato con le sezioni di traccia piu' lunghe di ciascuno (thread principale e
 * RenderThread), come passa il tempo del thread principale (in esecuzione / pronto ma senza CPU / dormiente) e le sezioni
 * lunghe degli altri thread dell'app. Serve a capire *dove* va il tempo di un fotogramma lungo senza aprire Perfetto
 * sul PC. Si accende con `-Pandroid.testInstrumentationRunnerArguments.benchDiag=true` (vedi [BenchConfig.diag]).
 *
 * Finestra: dall'inizio del blocco misurato (`measureBlock`), o, con [openingWindowNs], dall'inizio della sezione
 * `MCG:viewerOpen` per quella durata.
 */
@OptIn(ExperimentalMetricApi::class)
class FrameDiagMetric(private val openingWindowNs: Long? = null) : TraceMetric() {

  override fun getMeasurements(captureInfo: Metric.CaptureInfo, traceSession: TraceProcessor.Session): List<Metric.Measurement> {
    if (!BenchConfig.diag) return emptyList()
    val pkg = captureInfo.targetPackageName
    val from: Long
    val to: Long
    if (openingWindowNs != null) {
      from = traceSession
        .query("SELECT ts FROM slice WHERE name = '${BenchmarkTarget.TRACE_VIEWER_OPEN}' AND dur > 0 ORDER BY ts LIMIT 1")
        .map { it.long("ts") }.firstOrNull() ?: return emptyList()
      to = from + openingWindowNs
    } else {
      from = FrameQueries.measureStart(traceSession) ?: return emptyList()
      to = Long.MAX_VALUE / 4
    }
    val frames = traceSession.query(
      """
      WITH main AS (
        SELECT s.id, s.track_id, CAST(substr(s.name, 23) AS INT) AS vsync, s.ts, s.dur
        FROM slice s JOIN thread_track tt ON s.track_id = tt.id JOIN thread t USING(utid) JOIN process p USING(upid)
        WHERE p.name = '$pkg' AND t.tid = p.pid AND s.depth = 0 AND s.name GLOB 'Choreographer#doFrame [0-9]*'
          AND s.ts >= $from AND s.ts < $to
      ), rt AS (
        SELECT CAST(substr(s.name, 12) AS INT) AS vsync, MIN(s.ts) AS rt_start, MAX(s.ts + s.dur) AS end_ts, SUM(s.dur) AS rt_dur
        FROM slice s JOIN thread_track tt ON s.track_id = tt.id JOIN thread t USING(utid) JOIN process p USING(upid)
        WHERE p.name = '$pkg' AND t.name = 'RenderThread' AND s.name GLOB 'DrawFrames [0-9]*' AND s.dur >= 0
        GROUP BY 1
      )
      SELECT main.id AS id, main.track_id AS track, main.vsync AS vsync, main.ts AS ts, main.dur AS dur,
             COALESCE(rt.rt_dur, 0) AS rt_dur, COALESCE(rt.rt_start, 0) AS rt_start,
             MAX(COALESCE(rt.end_ts, main.ts + main.dur), main.ts + main.dur) - main.ts AS cpu
      FROM main LEFT JOIN rt USING(vsync)
      ORDER BY cpu DESC LIMIT 4
      """.trimIndent(),
    ).map {
      DiagFrame(it.long("id"), it.long("track"), it.long("vsync"), it.long("ts"), it.long("dur"), it.long("rt_dur"), it.long("rt_start"), it.long("cpu"))
    }.toList()

    if (openingWindowNs != null) {
      // Il fotogramma piu' lungo dei primi 100 ms (l'avvio dell'espansione) e quello dopo i 250 ms (l'arrivo a riposo), a parte.
      val early = traceSession.query(
        """
        WITH main AS (
          SELECT CAST(substr(s.name, 23) AS INT) AS vsync, s.ts AS ts, s.dur AS dur
          FROM slice s JOIN thread_track tt ON s.track_id = tt.id JOIN thread t USING(utid) JOIN process p USING(upid)
          WHERE p.name = '$pkg' AND t.tid = p.pid AND s.depth = 0 AND s.name GLOB 'Choreographer#doFrame [0-9]*' AND s.ts >= $from AND s.ts < $to
        ), rt AS (
          SELECT CAST(substr(s.name, 12) AS INT) AS vsync, MAX(s.ts + s.dur) AS end_ts
          FROM slice s JOIN thread_track tt ON s.track_id = tt.id JOIN thread t USING(utid) JOIN process p USING(upid)
          WHERE p.name = '$pkg' AND t.name = 'RenderThread' AND s.name GLOB 'DrawFrames [0-9]*' AND s.dur >= 0
          GROUP BY 1
        )
        SELECT MAX(CASE WHEN main.ts < ${from + 100_000_000L} THEN MAX(COALESCE(rt.end_ts, main.ts + main.dur), main.ts + main.dur) - main.ts END) AS early,
               MAX(CASE WHEN main.ts >= ${from + 250_000_000L} THEN MAX(COALESCE(rt.end_ts, main.ts + main.dur), main.ts + main.dur) - main.ts END) AS late
        FROM main LEFT JOIN rt USING(vsync)
        """.trimIndent(),
      ).firstOrNull()
      Log.i(TAG, "EARLY=${ms(early?.nullableLong("early") ?: 0)} LATE=${ms(early?.nullableLong("late") ?: 0)}")
    }
    Log.i(TAG, "=== finestra da ${from}: ${frames.size} fotogrammi piu' lunghi (pacchetto $pkg)")
    for (f in frames) {
      Log.i(TAG, "frame vsync=${f.vsync} +${ms(f.ts - from)} ms: cpu=${ms(f.cpu)} main=${ms(f.dur)} rt=${ms(f.rtDur)}")
      val main = traceSession.query(
        """
        SELECT s.name AS name, s.depth AS depth, s.dur AS dur, s.ts AS ts
        FROM slice s WHERE s.track_id = ${f.track} AND s.ts >= ${f.ts} AND s.ts + s.dur <= ${f.ts + f.dur} AND s.depth > 0 AND s.dur >= 800000
        ORDER BY s.ts
        """.trimIndent(),
      ).map { Triple(it.string("name"), it.long("depth"), it.long("dur")) }.toList()
      for ((name, depth, dur) in main.take(MAX_LINES)) Log.i(TAG, "   M${"  ".repeat(depth.toInt())}${name.take(60)} ${ms(dur)}")
      val rtSlices = traceSession.query(
        """
        SELECT s.name AS name, s.depth AS depth, s.dur AS dur
        FROM slice s JOIN thread_track tt ON s.track_id = tt.id JOIN thread t USING(utid) JOIN process p USING(upid)
        WHERE p.name = '$pkg' AND t.name = 'RenderThread' AND s.ts >= ${f.rtStart} AND s.ts < ${f.ts + f.cpu} AND s.dur >= 1500000
        ORDER BY s.ts
        """.trimIndent(),
      ).map { Triple(it.string("name"), it.long("depth"), it.long("dur")) }.toList()
      for ((name, depth, dur) in rtSlices.take(MAX_LINES)) Log.i(TAG, "   R${"  ".repeat(depth.toInt())}${name.take(60)} ${ms(dur)}")
      // Aggregato per nome sul thread principale (dentro il fotogramma): le sezioni di Compose e quelle nostre.
      val magg = traceSession.query(
        """
        SELECT s.name AS name, s.depth AS depth, SUM(s.dur) AS total, COUNT(*) AS n, MAX(s.dur) AS biggest
        FROM slice s WHERE s.track_id = ${f.track} AND s.ts >= ${f.ts} AND s.ts + s.dur <= ${f.ts + f.dur} AND s.depth >= 3
        GROUP BY s.name, s.depth ORDER BY total DESC LIMIT 16
        """.trimIndent(),
      ).map { "${it.string("name").take(50)} d${it.long("depth")} sum=${ms(it.long("total"))} n=${it.long("n")} max=${ms(it.long("biggest"))}" }.toList()
      magg.forEach { Log.i(TAG, "   M-agg $it") }
      // Aggregato per nome sul RenderThread (tutta la finestra del fotogramma): dove va il tempo che non sta nelle sezioni lunghe.
      val agg = traceSession.query(
        """
        SELECT s.name AS name, s.depth AS depth, SUM(s.dur) AS total, COUNT(*) AS n, MAX(s.dur) AS biggest
        FROM slice s JOIN thread_track tt ON s.track_id = tt.id JOIN thread t USING(utid) JOIN process p USING(upid)
        WHERE p.name = '$pkg' AND t.name = 'RenderThread' AND s.ts >= ${f.rtStart} AND s.ts < ${f.ts + f.cpu} AND s.depth >= 2
        GROUP BY s.name, s.depth ORDER BY total DESC LIMIT 14
        """.trimIndent(),
      ).map { "${it.string("name").take(50).replace(Regex("[0-9]+"), "#")} d${it.long("depth")} sum=${ms(it.long("total"))} n=${it.long("n")} max=${ms(it.long("biggest"))}" }.toList()
      agg.forEach { Log.i(TAG, "   RT-agg $it") }
      val states = traceSession.query(
        """
        SELECT ts.state AS state, SUM(MIN(ts.ts + ts.dur, ${f.ts + f.dur}) - MAX(ts.ts, ${f.ts})) AS d
        FROM thread_state ts JOIN thread t USING(utid) JOIN process p USING(upid)
        WHERE p.name = '$pkg' AND t.tid = p.pid AND ts.ts < ${f.ts + f.dur} AND ts.ts + ts.dur > ${f.ts}
        GROUP BY ts.state
        """.trimIndent(),
      ).map { it.string("state") + "=" + ms(it.long("d")) }.toList()
      Log.i(TAG, "   stati del thread principale: ${states.joinToString(" ")}")
    }
    // I fotogrammi in ritardo (FrameTimeline) nel loro insieme: quanti, di chi e' la colpa (thread principale o RenderThread), e le sezioni che pesano di piu'.
    val jankCtes = """
      WITH jf AS (
        SELECT a.surface_frame_token AS tok FROM actual_frame_timeline_slice a JOIN process p USING(upid)
        WHERE p.name = '$pkg' AND a.ts >= $from AND a.ts < $to AND (a.jank_type GLOB '*App Deadline Missed*' OR a.present_type = 'Dropped Frame')
      ), jm AS (
        SELECT s.track_id AS track_id, s.ts AS ts, s.dur AS dur
        FROM slice s JOIN thread_track tt ON s.track_id = tt.id JOIN thread t USING(utid) JOIN process p USING(upid)
        WHERE p.name = '$pkg' AND t.tid = p.pid AND s.depth = 0 AND s.name GLOB 'Choreographer#doFrame [0-9]*'
          AND CAST(substr(s.name, 23) AS INT) IN (SELECT tok FROM jf)
      ), jr AS (
        SELECT s.track_id AS track_id, s.ts AS ts, s.dur AS dur
        FROM slice s JOIN thread_track tt ON s.track_id = tt.id JOIN thread t USING(utid) JOIN process p USING(upid)
        WHERE p.name = '$pkg' AND t.name = 'RenderThread' AND s.name GLOB 'DrawFrames [0-9]*'
          AND CAST(substr(s.name, 12) AS INT) IN (SELECT tok FROM jf)
      )
    """.trimIndent()
    traceSession.query(
      jankCtes + """
      SELECT (SELECT COUNT(*) FROM jf) AS n, (SELECT COUNT(*) FROM jm WHERE dur > 8500000) AS main_over,
             (SELECT COUNT(*) FROM jr WHERE dur > 8500000) AS rt_over, (SELECT COUNT(*) FROM jm) AS main_n
      """.trimIndent(),
    ).firstOrNull()?.let { Log.i(TAG, "JANK: fotogrammi in ritardo=${it.long("n")} (con doFrame ${it.long("main_n")}) principale>8,5ms=${it.long("main_over")} RenderThread>8,5ms=${it.long("rt_over")}") }
    traceSession.query(
      jankCtes + """
      SELECT s.name AS name, s.depth AS depth, SUM(s.dur) AS total, COUNT(*) AS n
      FROM jm JOIN slice s ON s.track_id = jm.track_id AND s.ts >= jm.ts AND s.ts + s.dur <= jm.ts + jm.dur AND s.depth BETWEEN 2 AND 9
      GROUP BY s.name, s.depth ORDER BY total DESC LIMIT 14
      """.trimIndent(),
    ).forEach { Log.i(TAG, "JANK-M ${it.string("name").take(50)} d${it.long("depth")} sum=${ms(it.long("total"))} n=${it.long("n")}") }
    traceSession.query(
      jankCtes + """
      SELECT s.name AS name, s.depth AS depth, SUM(s.dur) AS total, COUNT(*) AS n
      FROM jr JOIN slice s ON s.track_id = jr.track_id AND s.ts >= jr.ts AND s.ts + s.dur <= jr.ts + jr.dur AND s.depth BETWEEN 2 AND 9
      GROUP BY s.name, s.depth ORDER BY total DESC LIMIT 12
      """.trimIndent(),
    ).forEach { Log.i(TAG, "JANK-R ${it.string("name").take(50).replace(Regex("[0-9]+"), "#")} d${it.long("depth")} sum=${ms(it.long("total"))} n=${it.long("n")}") }
    // Il filo dei fotogrammi in ritardo, uno per riga: quando, quanto il thread principale e il RenderThread, quante celle create/riusate dentro.
    traceSession.query(
      jankCtes + """
      SELECT jm.ts AS ts, jm.dur AS main_dur,
             COALESCE((SELECT r.dur FROM jr r WHERE r.ts >= jm.ts AND r.ts < jm.ts + 40000000 ORDER BY r.ts LIMIT 1), 0) AS rt_dur,
             (SELECT COUNT(*) FROM slice c WHERE c.track_id = jm.track_id AND c.ts >= jm.ts AND c.ts < jm.ts + jm.dur AND c.name = 'MCG:cell.create') AS created,
             (SELECT COUNT(*) FROM slice c WHERE c.track_id = jm.track_id AND c.ts >= jm.ts AND c.ts < jm.ts + jm.dur AND c.name = 'MCG:cell.update') AS reused
      FROM jm ORDER BY jm.ts LIMIT 90
      """.trimIndent(),
    ).forEach { Log.i(TAG, "TL +${ms(it.long("ts") - from)} main=${ms(it.long("main_dur"))} rt=${ms(it.long("rt_dur"))} new=${it.long("created")} reuse=${it.long("reused")}") }
    // I fotogrammi in cui e' il RenderThread a ritardare (oltre 11 ms): che cosa fa.
    traceSession.query(
      jankCtes + """
      SELECT jr.ts AS ts, jr.dur AS dur,
             (SELECT group_concat(n, ' | ') FROM (
                SELECT s.name || ' ' || CAST(ROUND(SUM(s.dur) / 100000.0) / 10.0 AS TEXT) AS n
                FROM slice s WHERE s.track_id = jr.track_id AND s.ts >= jr.ts AND s.ts + s.dur <= jr.ts + jr.dur AND s.depth BETWEEN 2 AND 5
                  AND s.name NOT GLOB 'auto skgpu*' AND s.name NOT GLOB 'DrawFrames*' AND s.name NOT GLOB 'Drawing*'
                GROUP BY s.name ORDER BY SUM(s.dur) DESC LIMIT 5)) AS top
      FROM jr WHERE jr.dur > 11000000 ORDER BY jr.ts LIMIT 40
      """.trimIndent(),
    ).forEach { Log.i(TAG, "RTJ +${ms(it.long("ts") - from)} rt=${ms(it.long("dur"))} ${it.string("top").take(150)}") }
    val others = traceSession.query(
      """
      SELECT t.name AS tname, s.name AS name, s.dur AS dur, s.ts AS ts
      FROM slice s JOIN thread_track tt ON s.track_id = tt.id JOIN thread t USING(utid) JOIN process p USING(upid)
      WHERE p.name = '$pkg' AND t.tid <> p.pid AND t.name <> 'RenderThread' AND s.dur >= 6000000 AND s.ts >= $from AND s.ts < $to AND s.depth = 0
      ORDER BY s.dur DESC LIMIT 12
      """.trimIndent(),
    ).map { "${it.string("tname")} / ${it.string("name").take(50)} ${ms(it.long("dur"))} +${ms(it.long("ts") - from)}" }.toList()
    others.forEach { Log.i(TAG, "   altro thread: $it") }
    return emptyList()
  }

  private class DiagFrame(val id: Long, val track: Long, val vsync: Long, val ts: Long, val dur: Long, val rtDur: Long, val rtStart: Long, val cpu: Long)

  private fun ms(ns: Long): String = String.format(java.util.Locale.ROOT, "%.1f", ns / 1_000_000.0)

  private companion object {
    const val TAG = "MCGdiag"
    const val MAX_LINES = 25
  }
}
