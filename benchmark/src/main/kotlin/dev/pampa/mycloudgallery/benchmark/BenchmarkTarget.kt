package dev.pampa.mycloudgallery.benchmark

/**
 * Cio' che gli scenari di misura (P9) sanno dell'app sotto misura. Una costante sola per punto di contatto, cosi'
 * che l'app e il modulo di misura non si dicano cose diverse.
 *
 * L'id dell'app NON e' qui: arriva dallo strumentatore (`targetAppId`, vedi `benchmark/build.gradle`). Le varianti
 * `benchmarkRelease` e `nonMinifiedRelease` hanno un id proprio, `dev.pampa.mycloudgallery.bench` (P11): sono firmate con
 * la chiave di debug e con l'id della release sostituirebbero l'app vera sul telefono, cancellandone i dati. La debug ha
 * `.debug`. Il nome della classe dell'activity ([MAIN_ACTIVITY]) non cambia con il suffisso.
 */
object BenchmarkTarget {

  /**
   * Il marcatore che la variante di misura cerca in `getExternalFilesDir(null)`. Se c'e', l'inizializzatore
   * (presente SOLO in `app/src/benchmarkRelease/`) riempie un database di 100 mila righe sintetiche che puntano
   * a rotazione ai media reali del dispositivo e lega i fake della famiglia "pronta", senza rete (§2.6, "Dati del
   * benchmark"). Senza il marcatore la variante si comporta come la release. Lo scrive `BenchSession.prepareDevice`
   * con `adb shell` (UiAutomator) prima di avviare l'app. **Stesso nome di `BenchDataset.MARKER` nell'app.**
   */
  const val DATASET_MARKER: String = "bench-library.marker"

  /** Lo stato che l'inizializzatore scrive accanto al marcatore (`BenchDataset.STATUS` nell'app): il profilo della libreria mista. */
  const val DATASET_STATUS: String = "bench-dataset.json"

  /**
   * Fase 4A (P10): con questo file accanto al marcatore l'app apre il profilo "NAS prima" (le 300 righe piu' recenti
   * solo-NAS con l'HQ in replica) invece della libreria mista (`BenchDataset.NAS_FIRST_MARKER`). Senza, il profilo e' quello misto.
   */
  const val DATASET_NAS_FIRST_MARKER: String = "bench-library.nasfirst"

  /** Lo stato del profilo "NAS prima" (`BenchProfile.NAS_FIRST.statusFile` nell'app). */
  const val DATASET_STATUS_NAS_FIRST: String = "bench-dataset-nasfirst.json"

  /** Le righe del dataset (`BenchDataset.ROWS`): il §5.2 vuole 100 mila elementi, dalla Fase 4A il 60% solo-NAS (fase4-progetto §5.6). */
  const val DATASET_ROWS: Int = 100_000

  /** Il file che, accanto al marcatore, riduce il dataset a poche migliaia di righe: lo smoke sull'emulatore (`BenchDataset.SMOKE_MARKER`). */
  const val DATASET_SMOKE_MARKER: String = "bench-library.smoke"

  /** Il nome della sezione di traccia di cui la misura del viewer si fida (`ViewerOpenMetric`). La emette il viewer (P8). */
  const val TRACE_VIEWER_OPEN: String = "MCG:viewerOpen"

  /** L'unica activity dell'app (launcher): serve ad avviarla da shell fuori da Macrobenchmark (preparazione, sonda). */
  const val MAIN_ACTIVITY: String = "dev.pampa.mycloudgallery.MainActivity"

  /** Dove stanno le soglie assolute e i riferimenti accettati (letti da `tools/bench/compare.ps1`, P9). */
  const val THRESHOLDS_FILE: String = "benchmark/soglie.json"

  /** I permessi di lettura dei media che il collaudo concede con `pm grant` prima del primo avvio. */
  val MEDIA_PERMISSIONS: List<String> = listOf(
    "android.permission.READ_MEDIA_IMAGES",
    "android.permission.READ_MEDIA_VIDEO",
    "android.permission.ACCESS_MEDIA_LOCATION",
    // Android 10-12 (maxSdkVersion 32 nel manifest): su 13+ il comando fallisce senza danni e si ignora.
    "android.permission.READ_EXTERNAL_STORAGE",
  )
}
