package dev.pampa.mycloudgallery.bench

import android.content.Context
import java.io.File
import org.json.JSONObject

/**
 * Il dataset della variante di misura: quando crearlo, dove scrivere lo stato, come riconoscere un dataset gia' pronto.
 *
 * **Come si attiva.** Il collaudo (`:benchmark`, `BenchSession`) scrive il marcatore [MARKER] in
 * `getExternalFilesDir(null)` dell'app, concede i permessi di lettura dei media con `pm grant` e avvia l'app. Al primo
 * avvio con il marcatore [BenchDatasetProvider] chiama [onProcessStart]: sul thread `bench-dataset` si leggono i media
 * veri ([BenchDeviceMedia]) e si crea il database [DATABASE_NAME] con [ROWS] righe ([BenchSeeder]). Lo stato sta in
 * [STATUS] accanto al marcatore (il collaudo lo legge con `adb shell`): `in_corso`, poi `pronto` (o `errore`).
 *
 * **Idempotente.** Con uno stato `pronto` della stessa [SHAPE_VERSION], con lo stesso numero di righe richiesto e con il
 * file del database presente, un avvio successivo non rifa niente: gli avvii a freddo misurati non pagano il dataset.
 *
 * **Mai il database vero.** Si scrive [DATABASE_NAME] (`gallery-bench.db`), non `gallery.db`.
 *
 * Il collegamento con la UI (famiglia "pronta", database della Libreria, sincronizzazione spenta) e' descritto in
 * `docs/bench/README.md` ("Collegamento con l'app") ed e' un lavoro di P11: da qui si vedono solo i servizi
 * [BenchFamilySetupRepository] e [BenchPhoneLibrarySync], registrati con `ServiceLoader`.
 */
internal object BenchDataset {

  const val MARKER: String = "bench-library.marker"
  const val STATUS: String = "bench-dataset.json"
  const val DATABASE_NAME: String = "gallery-bench.db"
  const val ROWS: Int = 100_000

  /** Con questo file accanto al marcatore il dataset e' piccolo: solo per verificare che gli scenari girino. */
  const val SMOKE_MARKER: String = "bench-library.smoke"
  const val SMOKE_ROWS: Int = 5_000

  /** Da alzare quando cambia la forma delle righe: i dataset scritti prima si rifanno. */
  const val SHAPE_VERSION: Int = 1

  private const val TAG = "BenchDataset"

  fun onProcessStart(context: Context) {
    val thread = Thread({ runIfRequested(context) }, "bench-dataset")
    thread.isDaemon = true
    thread.priority = Thread.MIN_PRIORITY
    thread.start()
  }

  private fun runIfRequested(context: Context) {
    val dir = try {
      context.getExternalFilesDir(null)
    } catch (e: Exception) {
      null
    } ?: return
    if (!File(dir, MARKER).exists()) return
    val rows = requestedRows(dir)
    val statusFile = File(dir, STATUS)
    if (isReusable(context, statusFile, rows)) return

    writeStatus(statusFile, JSONObject().put("formato", SHAPE_VERSION).put("stato", "in_corso").put("righe", rows))
    try {
      val now = System.currentTimeMillis()
      val real = BenchDeviceMedia.load(context)
      val report = BenchSeeder.seed(context, real, rows, BenchDeviceMedia.version(context), now)
      val consistent = report.indexRows == rows && report.phoneRefsResolved == report.phoneRefsAsked
      val status = JSONObject()
        .put("formato", SHAPE_VERSION)
        .put("stato", if (consistent) "pronto" else "errore")
        .put("righe", report.rows)
        .put("mediaReali", report.realMedia)
        .put("immagini", report.realImages)
        .put("video", report.realVideos)
        .put("maxMegapixel", report.maxMegapixels)
        .put("soggettoZoomPiuRecente", report.zoomSubjectNewest)
        .put("secondiCreazione", report.seedMs / 1000.0)
        .put("righeIndice", report.indexRows)
        .put("letturaIndiceMs", report.indexReadMs)
        .put("riferimentiTelefono", report.phoneRefsResolved)
        .put("riferimentiChiesti", report.phoneRefsAsked)
        .put("database", DATABASE_NAME)
      if (!consistent) status.put("errore", "il dataset non e' coerente con le query dell'indice (righe o riferimenti)")
      writeStatus(statusFile, status)
    } catch (e: SecurityException) {
      // Permesso di lettura dei media non ancora concesso: il collaudo lo concede e riavvia l'app, che riprova.
      writeStatus(statusFile, JSONObject().put("formato", SHAPE_VERSION).put("stato", "errore").put("errore", "permesso di lettura dei media mancante"))
    } catch (e: Throwable) {
      // Solo la classe dell'errore e la prima riga: niente percorsi o nomi di file nello stato.
      writeStatus(
        statusFile,
        JSONObject().put("formato", SHAPE_VERSION).put("stato", "errore").put("errore", "${e.javaClass.simpleName}: ${e.message?.lineSequence()?.firstOrNull().orEmpty().take(160)}"),
      )
    }
  }

  /**
   * Le righe richieste: [ROWS] (la misura vera) oppure [SMOKE_ROWS] se accanto al marcatore c'e' [SMOKE_MARKER] (lo smoke
   * sull'emulatore, che in software non scrive 100 mila righe in tempi ragionevoli). Si guarda solo se i file ESISTONO: il
   * contenuto di un file creato da shell in `Android/data/<id>/files/` l'app non lo puo' leggere (permesso negato), il nome si'.
   */
  private fun requestedRows(dir: File): Int = if (File(dir, SMOKE_MARKER).exists()) SMOKE_ROWS else ROWS

  private fun isReusable(context: Context, statusFile: File, rows: Int): Boolean {
    val previous = try {
      JSONObject(statusFile.readText())
    } catch (e: Exception) {
      return false
    }
    return previous.optString("stato") == "pronto" &&
      previous.optInt("formato") == SHAPE_VERSION &&
      previous.optInt("righe") == rows &&
      context.getDatabasePath(DATABASE_NAME).exists()
  }

  /** Scrive lo stato in modo atomico: il collaudo non deve mai leggerne meta'. */
  private fun writeStatus(file: File, json: JSONObject) {
    try {
      val tmp = File(file.parentFile, file.name + ".tmp")
      tmp.writeText(json.toString())
      if (!tmp.renameTo(file)) {
        file.delete()
        tmp.renameTo(file)
      }
    } catch (e: Exception) {
      android.util.Log.w(TAG, "stato non scritto: ${e.javaClass.simpleName}")
    }
  }
}
