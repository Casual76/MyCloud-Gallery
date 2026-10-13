package dev.pampa.mycloudgallery.benchmark

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.IOException
import org.json.JSONObject
import org.junit.Assume

/** Lo stato del dataset scritto dall'app (`BenchDataset`): solo numeri. */
class BenchDatasetStatus(private val json: JSONObject) {
  val state: String get() = json.optString("stato")
  val rows: Int get() = json.optInt("righe")
  val realMedia: Int get() = json.optInt("mediaReali")
  val maxMegapixels: Double get() = json.optDouble("maxMegapixel", 0.0)
  val zoomSubjectNewest: Boolean get() = json.optBoolean("soggettoZoomPiuRecente", false)
  val error: String get() = json.optString("errore")
  override fun toString(): String = json.toString()
}

/**
 * Il risultato della sonda: la Libreria si raggiunge (e ha celle da toccare) oppure no, e perche'.
 * [message] e' scritto per chi legge il rapporto dei test saltati.
 */
class BenchReadiness(
  val ready: Boolean,
  val message: String,
  val dataset: BenchDatasetStatus?,
  /** Il menu dello stile della griglia (D4) esiste e si sa usare: senza, le prove dello stile giustificato si saltano. */
  val styleMenu: Boolean = false,
)

/**
 * Prepara il dispositivo e dice se gli scenari hanno qualcosa da misurare. Gira **una volta per processo di test**
 * ([readiness] mette in cache), dal primo `@Before` di qualunque classe.
 *
 * 1. concede i permessi di lettura dei media (`pm grant`): il dataset punta ai media veri;
 * 2. scrive il marcatore in `getExternalFilesDir` dell'app (`/sdcard/Android/data/<id>/files/`), da shell;
 * 3. avvia l'app e aspetta lo stato `pronto` del dataset (la prima volta sono 100 mila righe: secondi sul telefono,
 *    decine di secondi su un emulatore), poi la ferma: gli avvii misurati non lo pagano;
 * 4. riavvia l'app e cerca una cella della Libreria. **Se non c'e', gli scenari si saltano** (`Assume`) con il motivo:
 *    prima di M1 e del collegamento di P11 l'app si apre sull'onboarding della Fase 2 e non c'e' niente da misurare.
 *
 * Mai rete, mai dati dell'utente fuori dal dispositivo: i media veri si leggono dal MediaStore dentro l'app e di loro
 * non si scrive ne' nome ne' percorso.
 */
object BenchSession {

  private const val TAG = "MCG-Bench"
  private const val DATASET_TIMEOUT_MS = 240_000L
  private const val LIBRARY_TIMEOUT_MS = 25_000L
  private const val NO_STATUS_TIMEOUT_MS = 20_000L

  val targetPackage: String
    get() = InstrumentationRegistry.getArguments().getString("targetAppId")?.takeIf { it.isNotBlank() }
      ?: error("L'argomento targetAppId manca: lo imposta benchmark/build.gradle. Lancia i test con Gradle (tools\\check.ps1 -Bench).")

  val device: UiDevice get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

  /** `/sdcard/Android/data/<id>/files`: dove l'app legge il marcatore e scrive lo stato. */
  private val filesDir: String get() = "/sdcard/Android/data/$targetPackage/files"

  @Volatile private var cached: BenchReadiness? = null

  fun shell(command: String): String = try {
    device.executeShellCommand("$command 2>&1")
  } catch (e: IOException) {
    ""
  }

  /** Il dataset c'e' e la Libreria si vede: altrimenti il test e' saltato con il motivo, non fallito. */
  fun requireLibrary(): BenchReadiness {
    val readiness = readiness()
    Assume.assumeTrue(readiness.message, readiness.ready)
    return readiness
  }

  @Synchronized
  fun readiness(): BenchReadiness {
    cached?.let { return it }
    val result = try {
      probe()
    } catch (e: Exception) {
      BenchReadiness(false, "Preparazione del dispositivo non riuscita: ${e.javaClass.simpleName}.", null)
    }
    Log.i(TAG, "sonda: pronta=${result.ready} ${result.message}")
    cached = result
    return result
  }

  fun startApp() {
    shell("am start -W -n $targetPackage/${BenchmarkTarget.MAIN_ACTIVITY}")
  }

  fun stopApp() {
    shell("am force-stop $targetPackage")
  }

  /** Lo stato del dataset come lo ha scritto l'app, o `null` se non c'e' (variante senza inizializzatore, marcatore non letto). */
  fun datasetStatus(): BenchDatasetStatus? {
    val text = shell("cat $filesDir/${BenchmarkTarget.DATASET_STATUS}").trim()
    if (!text.startsWith("{")) return null
    return try {
      BenchDatasetStatus(JSONObject(text))
    } catch (e: Exception) {
      null
    }
  }

  private fun probe(): BenchReadiness {
    stopApp()
    for (permission in BenchmarkTarget.MEDIA_PERMISSIONS) shell("pm grant $targetPackage $permission")
    shell("mkdir -p $filesDir")
    shell("touch $filesDir/${BenchmarkTarget.DATASET_MARKER}")
    // Con il file `.smoke` accanto il dataset e' piccolo (BenchDataset.SMOKE_ROWS); senza, sono 100 mila righe (la misura vera).
    // L'app non legge il contenuto dei file creati da shell: conta solo se esistono.
    shell(
      if (BenchConfig.smoke) "touch $filesDir/${BenchmarkTarget.DATASET_SMOKE_MARKER}"
      else "rm -f $filesDir/${BenchmarkTarget.DATASET_SMOKE_MARKER}"
    )

    startApp()
    val dataset = awaitDataset()
    stopApp()

    startApp()
    val library = BenchUi.waitForLibrary(device, LIBRARY_TIMEOUT_MS)
    val styleMenu = library && BenchUi.selectStyle(device, BenchStyle.SQUARE)
    stopApp()

    val note = when {
      dataset == null -> "dataset assente (la variante non ha l'inizializzatore di app/src/benchmarkRelease o non ha letto il marcatore)"
      dataset.state != "pronto" -> "dataset ${dataset.state}${dataset.error.takeIf { it.isNotEmpty() }?.let { ": $it" } ?: ""}"
      dataset.realMedia == 0 -> "dataset pronto ma SENZA media veri sul dispositivo: le celle restano vuote e le misure non sono rappresentative"
      else -> "dataset pronto: ${dataset.rows} righe su ${dataset.realMedia} media veri"
    }
    return if (library) {
      BenchReadiness(true, "Libreria raggiunta; $note", dataset, styleMenu)
    } else {
      BenchReadiness(
        false,
        "In attesa di M1: la Libreria non e' raggiungibile (nessuna cella 'Foto, ...' o 'Video, ...' dopo ${LIBRARY_TIMEOUT_MS / 1000} s; " +
          "l'app si apre sull'onboarding finche' P11 non lega la famiglia 'pronta' e il database del dataset). $note",
        dataset,
      )
    }
  }

  private fun awaitDataset(): BenchDatasetStatus? {
    val start = System.currentTimeMillis()
    var last: BenchDatasetStatus? = null
    while (System.currentTimeMillis() - start < DATASET_TIMEOUT_MS) {
      last = datasetStatus()
      if (last != null && (last.state == "pronto" || last.state == "errore")) return last
      // L'inizializzatore scrive `in_corso` subito: se dopo qualche secondo non c'e' niente, la variante non ce l'ha.
      if (last == null && System.currentTimeMillis() - start > NO_STATUS_TIMEOUT_MS) return null
      Thread.sleep(1_000)
    }
    return last
  }
}
