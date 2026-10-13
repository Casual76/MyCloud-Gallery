package dev.pampa.mycloudgallery.benchmark

/**
 * Cio' che gli scenari di misura (P9) sanno dell'app sotto misura. Una costante sola per punto di contatto, cosi'
 * che l'app e il modulo di misura non si dicano cose diverse.
 *
 * L'id dell'app NON e' qui: arriva dallo strumentatore (`targetAppId`, vedi `benchmark/build.gradle`), perche'
 * la variante `benchmarkRelease` e quella `nonMinifiedRelease` hanno lo stesso id e la debug ne ha un altro.
 */
object BenchmarkTarget {

  /**
   * Il marcatore che la variante di misura cerca in `getExternalFilesDir(null)`. Se c'e', l'inizializzatore
   * (presente SOLO in `app/src/benchmarkRelease/`) riempie un database di 100 mila righe sintetiche che puntano
   * a rotazione ai media reali del dispositivo e lega i fake della famiglia "pronta", senza rete (§2.6, "Dati del
   * benchmark"). Senza il marcatore la variante si comporta come la release. Lo scrive lo scenario con
   * `adb shell` o con UiAutomator prima di avviare l'app.
   */
  const val DATASET_MARKER: String = "bench-library.marker"

  /** Il nome delle sezioni di traccia di cui la misura del viewer si fida (`TraceSectionMetric`). */
  const val TRACE_VIEWER_OPEN: String = "MCG:viewerOpen"

  /** Dove stanno le soglie assolute e i riferimenti accettati (letti da `tools/bench/compare.ps1`, P9). */
  const val THRESHOLDS_FILE: String = "benchmark/soglie.json"
}
