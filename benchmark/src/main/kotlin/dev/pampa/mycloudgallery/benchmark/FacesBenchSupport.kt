package dev.pampa.mycloudgallery.benchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.Metric
import androidx.benchmark.macro.TraceMetric
import androidx.benchmark.traceprocessor.TraceProcessor
import org.json.JSONObject

/**
 * Le cose di `FacesBenchmark` (Fase 8, §5.4 di `docs/fase8-progetto.md`; debito del 27/10: lo scenario `volti` era promesso e mancava).
 *
 * **Cosa serve dall'app** (variante `benchmarkRelease`, `app/src/benchmarkRelease`) e non e' di questo modulo: i motori dei volti (YuNet e SFace, i modelli
 * stanno nell'APK) misurati **dentro il processo dell'app**, come il test strumentato di P2 (`FaceMeasurementsDeviceTest`), perche' un modulo `com.android.test`
 * non ne vede le classi.
 *  - con il file [MEASURE_MARKER] accanto al marcatore l'app, a ogni avvio, misura i motori su immagini e volti **disegnati** (nessun volto vero, nessun dato
 *    dell'utente) e scrive in [STATUS] `{"stato":"pronto","yunetP95Ms":...,"sfaceP95Ms":...,"msPerFoto":...,"pssKb":...}` (i campi `FIELD_*`);
 *  - con il file [LOAD_MARKER] l'app, dopo la misura, **continua** il calcolo dei volti su quelle immagini a bassa priorita' finche' il processo vive, e lo dice
 *    con `"calcoloInCorso":true` in [STATUS]: serve a guardare la Libreria mentre il calcolo dei volti lavora.
 *
 * Oggi la variante di misura non ha questa parte (la Fase 8 la lascia ai test strumentati di P2, mai lanciati su un telefono: F104): le prove si **saltano** con
 * il motivo (`Assume`), come quelle della ricerca senza i vettori. Appena l'app scrive lo stato, girano senza altre modifiche. I numeri valgono solo su S25
 * e Tab S9; sugli emulatori la CPU e' quella del PC.
 */
object FacesBenchTarget {

  /** Con questo file accanto al marcatore l'app misura i motori dei volti a ogni avvio. */
  const val MEASURE_MARKER: String = "bench-library.faces"

  /** Con questo file (oltre al precedente) l'app tiene acceso il calcolo dei volti, a bassa priorita', mentre la Libreria si usa. */
  const val LOAD_MARKER: String = "bench-library.faces.load"

  /** Lo stato scritto dall'app, accanto al marcatore. */
  const val STATUS: String = "bench-faces.json"

  const val FIELD_YUNET_P50: String = "yunetP50Ms"
  const val FIELD_YUNET_P95: String = "yunetP95Ms"
  const val FIELD_SFACE_P50: String = "sfaceP50Ms"
  const val FIELD_SFACE_P95: String = "sfaceP95Ms"
  const val FIELD_MS_PER_PHOTO: String = "msPerFoto"
  const val FIELD_PSS_KB: String = "pssKb"
  const val FIELD_LOADED: String = "calcoloInCorso"

  /** Perche' le prove si saltano, per chi legge il rapporto. */
  fun missingReason(): String =
    "L'app non scrive $STATUS con la misura dei motori dei volti (file $MEASURE_MARKER, campi $FIELD_YUNET_P95, $FIELD_SFACE_P95, $FIELD_MS_PER_PHOTO " +
      "e $FIELD_PSS_KB): la parte dei volti non e' ancora nel dataset della variante di misura (docs/bench/README.md, \"Album e persone\")."

  fun filesDir(): String = AlbumBenchTarget.filesDir

  /** Lo stato dei volti dell'app, o `null` se non c'e'. */
  fun readStatus(): JSONObject? = AlbumBenchTarget.readStatus(STATUS)

  /** Chiede la misura (e, con [load], il calcolo che continua) a ogni avvio; toglie lo stato vecchio, perche' non si legga quello di un'iterazione prima. */
  fun request(measure: Boolean, load: Boolean) {
    val dir = filesDir()
    BenchSession.shell("mkdir -p $dir")
    BenchSession.shell(if (measure) "touch $dir/$MEASURE_MARKER" else "rm -f $dir/$MEASURE_MARKER")
    BenchSession.shell(if (load) "touch $dir/$LOAD_MARKER" else "rm -f $dir/$LOAD_MARKER")
    BenchSession.shell("rm -f $dir/$STATUS")
  }

  /** Aspetta lo stato `pronto` dell'app (con la misura dei motori); `null` se non arriva in [timeoutMs]. */
  fun awaitStatus(timeoutMs: Long, needLoad: Boolean = false): JSONObject? =
    AlbumBenchTarget.awaitStatus(STATUS, timeoutMs) { it.optString("stato") == "pronto" && (!needLoad || it.optBoolean(FIELD_LOADED, false)) }
}

/** Lo stato dei volti dell'iterazione, consegnato alle metriche (uno stato per processo di test, azzerato a ogni iterazione). */
object FacesBenchState {
  @Volatile var status: JSONObject? = null

  fun reset() {
    status = null
  }
}

/** I tempi dei motori dei volti come l'app li ha misurati: p95 di YuNet a 1024 px e di SFace per volto, millisecondi per foto e PSS dell'app a fine misura. */
@OptIn(ExperimentalMetricApi::class)
class FacesEnginesMetric : TraceMetric() {

  override fun getMeasurements(captureInfo: Metric.CaptureInfo, traceSession: TraceProcessor.Session): List<Metric.Measurement> {
    val status = FacesBenchState.status ?: return listOf(Metric.Measurement("facesStatusMissing", 1.0))
    return listOfNotNull(
      value(status, FacesBenchTarget.FIELD_YUNET_P50, "facesYunetP50Ms"),
      value(status, FacesBenchTarget.FIELD_YUNET_P95, "facesYunetP95Ms"),
      value(status, FacesBenchTarget.FIELD_SFACE_P50, "facesSfaceP50Ms"),
      value(status, FacesBenchTarget.FIELD_SFACE_P95, "facesSfaceP95Ms"),
      value(status, FacesBenchTarget.FIELD_MS_PER_PHOTO, "facesMsPerPhoto"),
      value(status, FacesBenchTarget.FIELD_PSS_KB, "facesPssKb"),
    )
  }

  private fun value(status: JSONObject, field: String, metric: String): Metric.Measurement? =
    if (status.has(field)) Metric.Measurement(metric, status.optDouble(field)) else null
}

/** Il calcolo dei volti lavorava durante la misura (1) o no (0): la validita' della prova dello scorrimento "con il calcolo in corso". */
@OptIn(ExperimentalMetricApi::class)
class FacesLoadMetric : TraceMetric() {

  override fun getMeasurements(captureInfo: Metric.CaptureInfo, traceSession: TraceProcessor.Session): List<Metric.Measurement> =
    listOf(Metric.Measurement("facesComputing", if (FacesBenchState.status?.optBoolean(FacesBenchTarget.FIELD_LOADED, false) == true) 1.0 else 0.0))
}
