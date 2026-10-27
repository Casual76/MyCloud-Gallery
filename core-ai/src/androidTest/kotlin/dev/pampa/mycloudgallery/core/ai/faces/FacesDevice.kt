package dev.pampa.mycloudgallery.core.ai.faces

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.mycloudgallery.core.ai.contract.FaceEngines
import dev.pampa.mycloudgallery.core.ai.contract.FaceImage
import dev.pampa.mycloudgallery.core.models.LocalModels
import dev.pampa.mycloudgallery.core.models.contract.BundledAssets
import dev.pampa.mycloudgallery.core.models.contract.EnsureResult
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.ModelFiles
import dev.pampa.mycloudgallery.core.models.contract.ModelFeature
import dev.pampa.mycloudgallery.core.models.contract.ModelSpec
import dev.pampa.mycloudgallery.core.models.contract.ModelTrigger
import dev.pampa.mycloudgallery.core.models.noop.ClosedModelNetworkGate
import dev.pampa.mycloudgallery.core.models.source.BundledModelFetcher
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue

/**
 * Gli arnesi dei test strumentati dei motori dei volti (Fase 8, P2). Come per i motori della Fase 6 (`DeviceSupport.kt`):
 *
 * **I modelli** sono gli asset dell'APK (`assets/faces/`, Q1 (a)): qui passano dalla stessa strada dell'app, cioe' dal gestore dei modelli con
 * l'origine `Bundled` (nessuna rete) e da `ModelFiles.open`, che verifica dimensione e SHA-256 a ogni apertura. Niente da copiare sul dispositivo.
 *
 * **Le immagini con i volti NON stanno nel repository** (regola dei brief della Fase 8: nessun ritratto, nessun volto). Le prove che vogliono
 * volti veri le leggono da `/data/local/tmp/mcg-faces/` (l'app non legge `/data/local/tmp`: si passa dalla shell, come per i modelli della Fase 6):
 *   - `img/<serie>-img-NNN.png`: le composizioni di P1 (`out\composizioni\A|B`, ritratti di pubblico dominio incollati su sfondi senza volti);
 *   - `refs.txt`: i riferimenti numerici, esportati sul PC da `core-ai/src/androidTest/faces/esporta_riferimenti.py` con l'ONNX di P1.
 * Senza, quelle prove si saltano (`Assume`, mai un falso verde): il resoconto di F104 elenca i test eseguiti e quelli saltati. Come prepararle e
 * lanciarle: `core-ai/src/androidTest/faces/LEGGIMI.md` e `docs/decisioni.md`, "Fase 8 P2".
 */
internal object FacesDevice {

  private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
  val context: Context get() = instrumentation.targetContext

  const val TAG = "P2Measure"

  /** La cartella sul dispositivo con le immagini e i riferimenti (`adb push <cartella> /data/local/tmp/mcg-faces`). */
  const val FIXTURES_DIR = "/data/local/tmp/mcg-faces"

  /** La cartella `models/` dell'APK di prova. */
  val modelsRoot: File get() = File(context.filesDir, "p2-models").apply { mkdirs() }

  private var cached: LocalModels? = null

  /** I modelli di prova: nessuna rete, l'origine e' l'APK. */
  @Synchronized
  fun models(): LocalModels = cached ?: LocalModels(
    root = modelsRoot,
    fetcher = BundledModelFetcher(BundledAssets { name -> runCatching { context.assets.open(name) }.getOrNull() }),
    networkGate = ClosedModelNetworkGate,
  ).also { cached = it }

  /** I file verificati dei due modelli dei volti, estratti dall'APK se non ci sono gia' (come fa `EnsuringModelFiles` all'attivazione). */
  fun files(): ModelFiles {
    val models = models()
    runBlocking {
      for (spec in ModelCatalog.needed(emptySet(), setOf(ModelFeature.FACES))) {
        val result = models.manager.ensure(spec.id, ModelTrigger.USER_NOW, 120.seconds)
        check(result == EnsureResult.Ready) { "il modello ${spec.id} non e' pronto: $result" }
      }
    }
    return models.files
  }

  /** I quattro pezzi veri, sopra i file verificati. */
  fun engines(): FaceEngines = FaceEnginesFactory.create(files())

  /** Dove sta nel file system il modello verificato (`models/<sha256>`). */
  fun modelFile(spec: ModelSpec): File = File(modelsRoot, spec.sha256.hex)

  // --- Le immagini e i riferimenti dell'operatore ----------------------------------------------------------------------------------

  /** I nomi dei file in una cartella del dispositivo (via shell); vuoto se la cartella non c'e'. */
  fun shellList(dir: String): List<String> {
    val pfd = instrumentation.uiAutomation.executeShellCommand("ls -1 $dir")
    return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }.toString(Charsets.UTF_8)
      .lines().map { it.trim() }.filter { it.isNotEmpty() && !it.contains(' ') && !it.startsWith("ls:") }
  }

  /** Un file del dispositivo (via shell), o null se manca. */
  fun shellRead(path: String): ByteArray? {
    val pfd = instrumentation.uiAutomation.executeShellCommand("cat $path")
    val out = ByteArrayOutputStream()
    ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.copyTo(out, 1 shl 16) }
    val bytes = out.toByteArray()
    return bytes.takeIf { it.isNotEmpty() && !String(bytes, 0, minOf(bytes.size, 80), Charsets.UTF_8).contains("No such file") }
  }

  /** Salta il test se l'operatore non ha messo i riferimenti sul dispositivo; altrimenti li da'. */
  fun requireReferences(): FaceReferences {
    val bytes = shellRead("$FIXTURES_DIR/refs.txt")
    assumeTrue("Riferimenti assenti: esporta_riferimenti.py e adb push in $FIXTURES_DIR (vedi core-ai/src/androidTest/faces/LEGGIMI.md)", bytes != null)
    return FaceReferences.parse(bytes!!.toString(Charsets.UTF_8))
  }

  /** L'immagine di una composizione come `FaceImage`, dai pixel del PNG (gli stessi che ha letto Python: nessuna ricodifica). */
  fun loadFixture(name: String): FaceImage {
    val bytes = checkNotNull(shellRead("$FIXTURES_DIR/$name")) { "manca $name in $FIXTURES_DIR" }
    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 })
    checkNotNull(bitmap) { "$name non e' un'immagine" }
    try {
      val pixels = IntArray(bitmap.width * bitmap.height)
      bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
      return FaceImage(pixels, bitmap.width, bitmap.height)
    } finally {
      bitmap.recycle()
    }
  }

  // --- Misure ----------------------------------------------------------------------------------------------------------------------

  /** Una riga JSON per misura nel log `P2Measure` e in `files/p2-measures.jsonl` (il resoconto di F104 le riporta con il dispositivo e la data). */
  fun record(name: String, build: JSONObject.() -> Unit) {
    val json = JSONObject()
      .put("misura", name)
      .put("dispositivo", "${Build.MANUFACTURER} ${Build.MODEL} (${Build.HARDWARE}), Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}")
    json.build()
    Log.i(TAG, json.toString())
    File(context.filesDir, "p2-measures.jsonl").appendText(json.toString() + "\n")
  }

  fun percentile(sorted: List<Double>, p: Double): Double = sorted[((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)]

  fun ms(nanos: Long): Double = nanos / 1e6

  /** La memoria del processo in MB (PSS totale). */
  fun pssMb(): Double {
    val info = Debug.MemoryInfo()
    Debug.getMemoryInfo(info)
    return info.totalPss / 1024.0
  }
}
