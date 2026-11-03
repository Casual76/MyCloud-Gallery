package dev.pampa.mycloudgallery.benchmark

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.IOException
import org.json.JSONObject
import org.junit.Assume

/**
 * I due profili della libreria di misura (Fase 4A, P10; `BenchProfile` nell'app).
 *
 * - [MIXED]: la libreria di 100 mila righe delle misure della Fase 3, ma con il 60% di media **solo-NAS** (miniature dalla
 *   replica sintetica); le 120 piu' recenti sul telefono, come in una libreria vera. E' quella di tutti gli scenari della Fase 3.
 * - [NAS_FIRST]: le 300 righe piu' recenti sono solo-NAS, con l'HQ in replica: la prima pagina di miniature del NAS dopo un
 *   avvio a freddo e l'apertura di un media solo-NAS (`NasLibraryBenchmark`).
 */
enum class BenchLibraryProfile(val statusFile: String, val label: String) {
  MIXED(BenchmarkTarget.DATASET_STATUS, "libreria mista"),
  NAS_FIRST(BenchmarkTarget.DATASET_STATUS_NAS_FIRST, "NAS prima"),
}

/** Lo stato del dataset scritto dall'app (`BenchDataset`): solo numeri. */
class BenchDatasetStatus(private val json: JSONObject) {
  val state: String get() = json.optString("stato")
  val rows: Int get() = json.optInt("righe")
  val realMedia: Int get() = json.optInt("mediaReali")
  val maxMegapixels: Double get() = json.optDouble("maxMegapixel", 0.0)
  val zoomSubjectNewest: Boolean get() = json.optBoolean("soggettoZoomPiuRecente", false)
  val error: String get() = json.optString("errore")

  // --- La libreria mista (P10). Le righe contate dall'indice riletto, non dai conti del seeder ----------------------------
  val phoneOnlyRows: Int get() = json.optInt("righeSoloTelefono")
  val nasOnlyRows: Int get() = json.optInt("righeSoloNas")
  val bothRows: Int get() = json.optInt("righeEntrambi")

  /** La quota di righe solo-NAS (0..1). Un dataset della Fase 3 (senza il campo) e' 0: non e' una libreria mista. */
  val nasShare: Double get() = json.optDouble("quotaSoloNas", 0.0)
  val headNasRows: Int get() = json.optInt("testaNas")
  val headPhoneRows: Int get() = json.optInt("testaTelefono")

  // --- La replica sintetica -----------------------------------------------------------------------------------------------
  val replicaBlobs: Int get() = json.optInt("replicaBlob")
  val replicaPackKb: Long get() = json.optLong("replicaPaccoKb")
  val replicaChecked: Int get() = json.optInt("replicaControllati")
  val replicaChecksOk: Int get() = json.optInt("replicaControlliOk")

  /** Millisecondi per decodificare un blob S e uno M su questo dispositivo (informativi). */
  val decodeSMs: Double get() = json.optDouble("decodificaSMs", 0.0)
  val decodeMMs: Double get() = json.optDouble("decodificaMMs", 0.0)

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
 * Prepara il dispositivo e dice se gli scenari hanno qualcosa da misurare. Gira **una volta per processo di test e per
 * profilo** ([readiness] mette in cache), dal primo `@Before` di qualunque classe.
 *
 * 1. concede i permessi di lettura dei media (`pm grant`): il dataset punta ai media veri;
 * 2. scrive il marcatore in `getExternalFilesDir` dell'app (`/sdcard/Android/data/<id>/files/`), da shell, e il file che
 *    sceglie il profilo (libreria mista oppure "NAS prima");
 * 3. avvia l'app e aspetta lo stato `pronto` del dataset (la prima volta sono 100 mila righe e la replica sintetica: decine
 *    di secondi sul telefono, minuti su un emulatore), poi la ferma: gli avvii misurati non lo pagano;
 * 4. riavvia l'app e cerca una cella della Libreria. **Se non c'e', gli scenari si saltano** (`Assume`) con il motivo:
 *    prima di M1 e del collegamento di P11 l'app si apre sull'onboarding della Fase 2 e non c'e' niente da misurare.
 *
 * Mai rete, mai dati dell'utente fuori dal dispositivo: i media veri si leggono dal MediaStore dentro l'app e di loro
 * non si scrive ne' nome ne' percorso.
 */
object BenchSession {

  private const val TAG = "MCG-Bench"
  private const val LIBRARY_TIMEOUT_MS = 25_000L
  private const val NO_STATUS_TIMEOUT_MS = 20_000L

  val targetPackage: String
    get() = InstrumentationRegistry.getArguments().getString("targetAppId")?.takeIf { it.isNotBlank() }
      ?: error("L'argomento targetAppId manca: lo imposta benchmark/build.gradle. Lancia i test con Gradle (tools\\check.ps1 -Bench).")

  val device: UiDevice get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

  /** `/sdcard/Android/data/<id>/files`: dove l'app legge il marcatore e scrive lo stato. */
  private val filesDir: String get() = "/sdcard/Android/data/$targetPackage/files"

  private val cache = HashMap<BenchLibraryProfile, BenchReadiness>()

  fun shell(command: String): String = try {
    device.executeShellCommand("$command 2>&1")
  } catch (e: IOException) {
    ""
  }

  /** Il dataset c'e' e la Libreria si vede: altrimenti il test e' saltato con il motivo, non fallito. */
  fun requireLibrary(profile: BenchLibraryProfile = BenchLibraryProfile.MIXED): BenchReadiness {
    val readiness = readiness(profile)
    Assume.assumeTrue(readiness.message, readiness.ready)
    return readiness
  }

  /**
   * La sonda del [profile] (una volta per processo di test, poi in cache) e, **ogni volta**, la scelta del profilo: un
   * file accanto al marcatore dice all'app quale database aprire, e la classe di test che ha girato prima potrebbe
   * aver lasciato l'altro. Dichiara anche a [BenchState] quale libreria sta per essere misurata (`LibraryMixMetric`).
   */
  @Synchronized
  fun readiness(profile: BenchLibraryProfile = BenchLibraryProfile.MIXED): BenchReadiness {
    val result = cache.getOrPut(profile) {
      val probed = try {
        probe(profile)
      } catch (e: Exception) {
        BenchReadiness(false, "Preparazione del dispositivo non riuscita: ${e.javaClass.simpleName}.", null)
      }
      Log.i(TAG, "sonda (${profile.label}): pronta=${probed.ready} ${probed.message}")
      probed
    }
    applyProfile(profile)
    BenchState.dataset = result.dataset
    BenchState.profile = profile
    return result
  }

  fun startApp() {
    shell("am start -W -n $targetPackage/${BenchmarkTarget.MAIN_ACTIVITY}")
  }

  fun stopApp() {
    shell("am force-stop $targetPackage")
  }

  /** Lo stato del dataset di [profile] come lo ha scritto l'app, o `null` se non c'e' (variante senza inizializzatore, marcatore non letto). */
  fun datasetStatus(profile: BenchLibraryProfile = BenchLibraryProfile.MIXED): BenchDatasetStatus? {
    val text = shell("cat $filesDir/${profile.statusFile}").trim()
    if (!text.startsWith("{")) return null
    return try {
      BenchDatasetStatus(JSONObject(text))
    } catch (e: Exception) {
      null
    }
  }

  /** Il file accanto al marcatore che sceglie il profilo (l'app guarda solo se esiste). */
  private fun applyProfile(profile: BenchLibraryProfile) {
    shell(
      if (profile == BenchLibraryProfile.NAS_FIRST) "touch $filesDir/${BenchmarkTarget.DATASET_NAS_FIRST_MARKER}"
      else "rm -f $filesDir/${BenchmarkTarget.DATASET_NAS_FIRST_MARKER}"
    )
  }

  private fun probe(profile: BenchLibraryProfile): BenchReadiness {
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
    applyProfile(profile)

    startApp()
    val dataset = awaitDataset(profile)
    stopApp()

    startApp()
    val library = BenchUi.waitForLibrary(device, LIBRARY_TIMEOUT_MS)
    // Il menu dello stile si prova una volta, sulla libreria mista (quella degli otto scorrimenti).
    val styleMenu = profile == BenchLibraryProfile.MIXED && library && BenchUi.selectStyle(device, BenchStyle.SQUARE)
    stopApp()

    val note = when {
      dataset == null -> "dataset assente (la variante non ha l'inizializzatore di app/src/benchmarkRelease o non ha letto il marcatore)"
      dataset.state != "pronto" -> "dataset ${dataset.state}${dataset.error.takeIf { it.isNotEmpty() }?.let { ": $it" } ?: ""}"
      dataset.nasShare <= 0.0 -> "dataset pronto ma NON misto (nessuna riga solo-NAS: e' la libreria della Fase 3, il confronto con la guardia non vale)"
      dataset.realMedia == 0 -> "dataset pronto ma SENZA media veri sul dispositivo: le celle del telefono restano vuote e le misure non sono rappresentative"
      else ->
        "dataset pronto (${profile.label}): ${dataset.rows} righe, ${(dataset.nasShare * 100).toInt()}% solo-NAS, " +
          "${dataset.replicaBlobs} blob in replica, su ${dataset.realMedia} media veri"
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

  private fun awaitDataset(profile: BenchLibraryProfile): BenchDatasetStatus? {
    val start = System.currentTimeMillis()
    var last: BenchDatasetStatus? = null
    while (System.currentTimeMillis() - start < BenchConfig.datasetTimeoutMs) {
      last = datasetStatus(profile)
      if (last != null && (last.state == "pronto" || last.state == "errore")) return last
      // L'inizializzatore scrive `in_corso` subito: se dopo qualche secondo non c'e' niente, la variante non ce l'ha.
      if (last == null && System.currentTimeMillis() - start > NO_STATUS_TIMEOUT_MS) return null
      Thread.sleep(1_000)
    }
    return last
  }
}
