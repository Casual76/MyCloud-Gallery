package dev.pampa.mycloudgallery.bench

import android.content.Context
import java.io.File
import org.json.JSONObject

/**
 * I due dataset della variante di misura (Fase 4A, P10; docs/fase4-progetto.md §5.6). Stessa libreria di 100 mila righe,
 * stessa distribuzione di date, luoghi e proporzioni della Fase 3 (cosi' la guardia confronta mele con mele); cambia
 * **da dove vengono i pixel**:
 *
 * - [MIXED], il default: le [HEAD_PHONE_ROWS] righe piu' recenti sul telefono (come in una libreria vera: le foto appena
 *   scattate), poi il 60% solo-NAS (miniature dalla replica sintetica), il 6% su telefono **e** NAS e il resto solo telefono;
 * - [NAS_FIRST]: come [MIXED] ma le [HEAD_NAS_ROWS] piu' recenti sono solo-NAS, tutte con l'HQ in replica. Serve alle due
 *   misure nuove: la prima pagina di miniature del NAS dopo un avvio a freddo e l'apertura di un media solo-NAS (p90 < 100 ms).
 *
 * I due profili hanno database, stato e indice della replica propri: si passa dall'uno all'altro con un file accanto al
 * marcatore (nessuna ricostruzione dopo la prima volta).
 */
internal enum class BenchProfile(val code: String, val databaseName: String, val statusFile: String) {
  MIXED("mixed", "gallery-bench.db", "bench-dataset.json"),
  NAS_FIRST("nasfirst", "gallery-bench-nasfirst.db", "bench-dataset-nasfirst.json"),
}

/**
 * Il dataset della variante di misura: quando crearlo, dove scrivere lo stato, come riconoscere un dataset gia' pronto.
 *
 * **Come si attiva.** Il collaudo (`:benchmark`, `BenchSession`) scrive il marcatore [MARKER] in
 * `getExternalFilesDir(null)` dell'app, concede i permessi di lettura dei media con `pm grant` e avvia l'app. Al primo
 * avvio con il marcatore [BenchDatasetProvider] chiama [onProcessStart]: sul thread `bench-dataset` si leggono i media
 * veri ([BenchDeviceMedia]), si creano i modelli delle miniature ([BenchReplicaBuilder]) e il database del profilo con
 * [ROWS] righe ([BenchSeeder]). Lo stato sta in [BenchProfile.statusFile] accanto al marcatore (il collaudo lo legge con
 * `adb shell`): `in_corso`, poi `pronto` (o `errore`).
 *
 * **Quale profilo.** [MIXED] se c'e' solo il marcatore, [NAS_FIRST] se accanto c'e' anche [NAS_FIRST_MARKER] ([profileOf]).
 *
 * **Idempotente.** Con uno stato `pronto` della stessa [SHAPE_VERSION], con lo stesso numero di righe richiesto e con il
 * database e l'indice della replica presenti, un avvio successivo non rifa niente: gli avvii a freddo misurati non pagano il dataset.
 *
 * **Mai il database vero.** Si scrive [BenchProfile.databaseName] (`gallery-bench*.db`), non `gallery.db`.
 *
 * Il collegamento con la UI (famiglia "pronta", database della Libreria, sincronizzazione spenta, replica) e' descritto in
 * `docs/bench/README.md` ("Collegamento con l'app"): da qui si vedono solo [BenchFamilySetupRepository] e
 * [BenchPhoneLibrarySync], che [BenchMeasurementHooks] consegna all'app con un binding Hilt.
 */
internal object BenchDataset {

  const val MARKER: String = "bench-library.marker"

  /** Con questo file accanto al marcatore il profilo e' [BenchProfile.NAS_FIRST]. */
  const val NAS_FIRST_MARKER: String = "bench-library.nasfirst"

  /** Lo stato del profilo [BenchProfile.MIXED] (gli altri: [BenchProfile.statusFile]). */
  const val STATUS: String = "bench-dataset.json"

  /** Il database del profilo [BenchProfile.MIXED]. */
  const val DATABASE_NAME: String = "gallery-bench.db"
  const val ROWS: Int = 100_000

  /** Con questo file accanto al marcatore il dataset e' piccolo: solo per verificare che gli scenari girino. */
  const val SMOKE_MARKER: String = "bench-library.smoke"
  const val SMOKE_ROWS: Int = 5_000

  /** Quota di righe solo-NAS e su entrambi, in percentuale (§5.6: 60% solo-NAS). Il resto e' solo telefono. */
  const val NAS_ONLY_PERCENT: Int = 60
  const val BOTH_PERCENT: Int = 6

  /** [BenchProfile.MIXED]: quante righe fra le piu' recenti stanno sul telefono (la prima e' il soggetto dello zoom a 200 MP). */
  const val HEAD_PHONE_ROWS: Int = 120

  /** [BenchProfile.NAS_FIRST]: quante righe fra le piu' recenti sono solo-NAS con HQ in replica (piu' di due schermate di un tablet). */
  const val HEAD_NAS_ROWS: Int = 300

  /** Quante delle altre righe solo-NAS hanno anche l'HQ (qualita' "Alta" su una parte dell'archivio), in percentuale. */
  const val HQ_PERCENT: Int = 2

  /**
   * Da alzare quando cambia la forma delle righe: i dataset scritti prima si rifanno. 2: `quick_hash` valido (64 cifre
   * esadecimali); con la 1 il catalogo rifiutava le righe alla lettura dei dettagli del viewer. 3 (P10): libreria mista,
   * posizioni NAS, flag di presenza, ThumbHash delle righe solo-NAS e replica sintetica. 4 (Fase 9, P12): il 60% delle righe con una posizione plausibile
   * (`BenchGeo`), un gruppo di righe con le date di "oggi" di 1 e di 3 anni fa (`BenchAnniversary`) e lo scaffale dei ricordi seminato (`BenchShelf`).
   */
  const val SHAPE_VERSION: Int = 4

  private const val TAG = "BenchDataset"

  fun onProcessStart(context: Context) {
    val thread = Thread({ runIfRequested(context) }, "bench-dataset")
    thread.isDaemon = true
    thread.priority = Thread.MIN_PRIORITY
    thread.start()
  }

  /** Il profilo scelto dal collaudo: `bench-library.nasfirst` accanto al marcatore. Solo l'esistenza dei file conta. */
  fun profileOf(dir: File): BenchProfile = if (File(dir, NAS_FIRST_MARKER).exists()) BenchProfile.NAS_FIRST else BenchProfile.MIXED

  /** Come [profileOf], dal contesto: `MIXED` se la cartella non c'e' (variante senza marcatore). */
  fun profileOf(context: Context): BenchProfile {
    val dir = try {
      context.getExternalFilesDir(null)
    } catch (e: Exception) {
      null
    } ?: return BenchProfile.MIXED
    return profileOf(dir)
  }

  private fun runIfRequested(context: Context) {
    val dir = try {
      context.getExternalFilesDir(null)
    } catch (e: Exception) {
      null
    } ?: return
    if (!File(dir, MARKER).exists()) return
    // Fase 9 (P12): la mappa ricorda l'ultima camera da una sessione all'altra (`map/settings`, D29). Ogni avvio della variante di misura riparte dalla camera di serie
    // (tutti i punti), non da dove l'ha lasciata la prova di prima (un'area vuota: nessuna bolla, prova saltata). Solo i file della variante di misura, mai dell'app vera.
    try {
      File(File(context.noBackupFilesDir, "map"), "settings").deleteRecursively()
    } catch (e: Exception) {
      android.util.Log.w(TAG, "camera della mappa non azzerata: ${e.javaClass.simpleName}")
    }
    val profile = profileOf(dir)
    val rows = requestedRows(dir)
    val statusFile = File(dir, profile.statusFile)
    if (isReusable(context, statusFile, rows, profile)) return

    writeStatus(statusFile, JSONObject().put("formato", SHAPE_VERSION).put("stato", "in_corso").put("righe", rows).put("profilo", profile.code))
    try {
      val now = System.currentTimeMillis()
      val real = BenchDeviceMedia.load(context)
      val report = BenchSeeder.seed(context, real, rows, BenchDeviceMedia.version(context), now, profile, smoke = rows == SMOKE_ROWS)
      val replica = report.replica
      val nasShare = report.nasOnlyRows * 100.0 / report.rows.coerceAtLeast(1)
      // La quota attesa tiene conto della testa forzata dal profilo (con 5 mila righe pesa quasi il 2%).
      val head = report.headPhoneRows + report.headNasRows
      val expectedShare = (NAS_ONLY_PERCENT * (report.rows - head) + 100.0 * report.headNasRows) / report.rows.coerceAtLeast(1)
      val problems = ArrayList<String>()
      if (report.indexRows != rows) problems += "righe dell'indice ${report.indexRows} su $rows"
      if (report.phoneRefsResolved != report.phoneRefsAsked) problems += "riferimenti del telefono ${report.phoneRefsResolved} su ${report.phoneRefsAsked}"
      if (nasShare < expectedShare - 3 || nasShare > expectedShare + 3) problems += "quota solo-NAS ${"%.1f".format(nasShare)}% (attesa ${"%.1f".format(expectedShare)}%)"
      if (replica.check.ok != replica.check.asked) problems += "blob della replica che non si leggono ${replica.check.ok} su ${replica.check.asked}"
      val consistent = problems.isEmpty()
      val status = JSONObject()
        .put("formato", SHAPE_VERSION)
        .put("stato", if (consistent) "pronto" else "errore")
        .put("profilo", profile.code)
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
        .put("database", profile.databaseName)
        // La libreria mista (P10).
        .put("righeSoloTelefono", report.phoneOnlyRows)
        .put("righeSoloNas", report.nasOnlyRows)
        .put("righeEntrambi", report.bothRows)
        .put("quotaSoloNas", nasShare / 100.0)
        .put("testaTelefono", report.headPhoneRows)
        .put("testaNas", report.headNasRows)
        .put("righeConHq", report.hqRows)
        // Fase 9 (P12): coordinate, giorni "di un anno fa", scaffale dei ricordi.
        .put("righePosizione", report.positionRows)
        .put("righeAnniversario", report.anniversaryRows)
        .put("ricordiSeminati", report.shelfMemories)
        .put("giornoSeme", report.seedDay)
        // La replica sintetica.
        .put("replicaBlob", replica.blobs)
        .put("replicaBlobS", replica.blobsS)
        .put("replicaBlobM", replica.blobsM)
        .put("replicaBlobHq", replica.blobsHq)
        .put("replicaModelli", replica.templates)
        .put("replicaPaccoKb", replica.packBytes / 1024)
        .put("replicaIndiceKb", replica.indexBytes / 1024)
        .put("replicaMedioKbS", replica.averageBytesS / 1024.0)
        .put("replicaMedioKbM", replica.averageBytesM / 1024.0)
        .put("replicaMedioKbHq", replica.averageBytesHq / 1024.0)
        .put("replicaSecondiModelli", replica.templateMs / 1000.0)
        .put("replicaControllati", replica.check.asked)
        .put("replicaControlliOk", replica.check.ok)
        .put("decodificaSMs", replica.check.decodeSMs)
        .put("decodificaMMs", replica.check.decodeMMs)
      if (!consistent) status.put("errore", "il dataset non e' coerente: " + problems.joinToString("; "))
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

  private fun isReusable(context: Context, statusFile: File, rows: Int, profile: BenchProfile): Boolean {
    val previous = try {
      JSONObject(statusFile.readText())
    } catch (e: Exception) {
      return false
    }
    return previous.optString("stato") == "pronto" &&
      previous.optInt("formato") == SHAPE_VERSION &&
      previous.optInt("righe") == rows &&
      context.getDatabasePath(profile.databaseName).exists() &&
      BenchReplicaStore.indexFile(context, profile).exists() &&
      freshEnough(previous.optInt("giornoSeme")) &&
      (profile != BenchProfile.MIXED || previous.optInt("ricordiSeminati") == 0 || File(File(context.noBackupFilesDir, BenchShelf.DIR), "shelf.bin").exists())
  }

  /**
   * Fase 9 (P12): i giorni "di un anno fa" sono relativi al giorno della creazione: dopo [BenchAnniversary.VALID_DAYS] giorni l'anniversario di oggi non ha piu'
   * le sue righe, e il dataset si rifa' (decine di secondi sul telefono). Senza `giornoSeme` (un dataset vecchio) non c'e' niente da confrontare: la forma lo rifa'.
   */
  private fun freshEnough(seedDay: Int): Boolean {
    if (seedDay <= 0) return false
    val seed = try {
      java.time.LocalDate.of(seedDay / 10_000, seedDay / 100 % 100, seedDay % 100)
    } catch (e: java.time.DateTimeException) {
      return false
    }
    val age = java.time.temporal.ChronoUnit.DAYS.between(seed, java.time.LocalDate.now())
    return age in 0..BenchAnniversary.VALID_DAYS
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
