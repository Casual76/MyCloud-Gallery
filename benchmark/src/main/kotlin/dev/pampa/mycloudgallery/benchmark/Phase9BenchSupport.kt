package dev.pampa.mycloudgallery.benchmark

import org.json.JSONObject

/**
 * I file accanto al marcatore del dataset (`getExternalFilesDir` dell'app) per le prove della Fase 9: marcatori che il collaudo scrive da shell, stati
 * che l'app scrive e il collaudo legge. Come per gli album ([AlbumBenchTarget]) l'app guarda solo se un file di marcatore **esiste**: il contenuto di un file
 * creato da shell non lo puo' leggere.
 */
object BenchAppFiles {

  /** Dove l'app legge i marcatori e scrive gli stati: la stessa cartella di [BenchSession]. */
  val dir: String get() = "/sdcard/Android/data/${BenchSession.targetPackage}/files"

  /** Crea il marcatore [name]. */
  fun touch(name: String) {
    BenchSession.shell("mkdir -p $dir")
    BenchSession.shell("touch $dir/$name")
  }

  /** Toglie il marcatore (o lo stato) [name]. */
  fun remove(name: String) {
    BenchSession.shell("rm -f $dir/$name")
  }

  /** Il file di stato [name] come JSON, o `null` se non c'e' o non e' JSON. */
  fun readStatus(name: String): JSONObject? {
    val text = BenchSession.shell("cat $dir/$name").trim()
    if (!text.startsWith("{")) return null
    return runCatching { JSONObject(text) }.getOrNull()
  }

  /**
   * Aspetta che lo stato [name] diventi [ready]. L'app scrive uno stato `in_corso` subito: se dopo [ABSENT_TIMEOUT_MS] il file non c'e' ancora, quella parte
   * non c'e' e si smette di aspettare (`null`). Uno stato `errore` si restituisce com'e': chi chiama lo legge.
   */
  fun awaitStatus(name: String, timeoutMs: Long, ready: (JSONObject) -> Boolean): JSONObject? {
    val start = System.currentTimeMillis()
    while (System.currentTimeMillis() - start < timeoutMs) {
      val status = readStatus(name)
      if (status != null && (status.optString("stato") == "errore" || ready(status))) return status
      if (status == null && System.currentTimeMillis() - start > ABSENT_TIMEOUT_MS) return null
      Thread.sleep(POLL_MS)
    }
    return null
  }

  /** Un campo numerico del file di stato del dataset; 0 se manca. */
  fun number(status: BenchDatasetStatus?, field: String): Int =
    status?.let { runCatching { JSONObject(it.toString()).optInt(field, 0) }.getOrDefault(0) } ?: 0

  private const val ABSENT_TIMEOUT_MS = 20_000L
  private const val POLL_MS = 500L
}

/** Una misura in piu' di un test della Fase 9, azzerata a ogni iterazione (le prove di aperture la consegnano alle metriche proprie). */
object Phase9BenchState {
  @Volatile var memoryOpenMs: Double? = null
  @Volatile var mapOpenMs: Double? = null
  @Volatile var zoneOpenMs: Double? = null
  @Volatile var memoriesComputing: Boolean = false
  @Volatile var mapOpened: Boolean = false
  @Volatile var presentationMs: Long = 0L

  fun reset() {
    memoryOpenMs = null
    mapOpenMs = null
    zoneOpenMs = null
    memoriesComputing = false
    mapOpened = false
    presentationMs = 0L
  }
}
