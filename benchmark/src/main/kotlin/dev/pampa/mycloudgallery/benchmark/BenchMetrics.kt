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
    val millis = traceSession
      .query("SELECT dur FROM slice WHERE name = '${BenchmarkTarget.TRACE_VIEWER_OPEN}' AND dur > 0 ORDER BY ts")
      .map { it.long("dur") / NANOS_PER_MILLI }
      .toList()
    return if (millis.isEmpty()) {
      listOf(Metric.Measurement("viewerSectionMissing", 1.0))
    } else {
      // La prima apertura dell'iterazione: lo scenario ne fa una sola.
      listOf(Metric.Measurement("viewerOpenMs", millis.first()))
    }
  }

  private companion object {
    const val NANOS_PER_MILLI = 1_000_000.0
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
