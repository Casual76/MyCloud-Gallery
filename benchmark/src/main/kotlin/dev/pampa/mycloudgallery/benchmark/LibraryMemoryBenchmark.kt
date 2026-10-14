package dev.pampa.mycloudgallery.benchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La memoria dell'app con la libreria mista da 100 mila righe (Fase 4A, P10; `docs/fase4-progetto.md` §5.6, "memoria
 * dell'indice con 100 mila elementi"): dopo un avvio a freddo e due secondi di quiete, PSS totale, heap Java, heap nativo
 * e grafica da `dumpsys meminfo` (`LibraryMemoryMetric`).
 *
 * Il conto preciso dell'indice della timeline (`estimatedBytes`, circa 100 B per elemento con `MCGT2`: +2 MB sui 100 mila)
 * lo fa il budget JVM di `core-library` (`IndexBudgetTest`, P7); questa prova dice che cosa ne vede il telefono. Nuova misura
 * della Fase 4: nessun riferimento del 13/10, la prima corsa buona (`compare.ps1 -Accept`) lo diventa e poi vale +15%.
 */
@RunWith(AndroidJUnit4::class)
class LibraryMemoryBenchmark {

  @get:Rule
  val rule = MacrobenchmarkRule()

  @Before
  fun requireLibrary() {
    BenchSession.requireLibrary()
  }

  @OptIn(ExperimentalMetricApi::class)
  @Test
  fun libraryMemory() {
    rule.measureRepeated(
      packageName = BenchSession.targetPackage,
      metrics = listOf(LibraryMemoryMetric(), LibraryShownMetric(), LibraryMixMetric()),
      compilationMode = BenchConfig.compilationMode,
      startupMode = StartupMode.COLD,
      iterations = BenchConfig.iterations(ITERATIONS),
      setupBlock = {
        pressHome()
        BenchMemory.reset()
      },
      measureBlock = {
        startActivityAndWait()
        BenchState.libraryShown = BenchUi.waitForLibrary(device, LIBRARY_TIMEOUT_MS)
        device.waitForIdle()
        // Quiete: l'istantanea e il layout del livello sullo schermo sono pronti, le miniature della prima pagina caricate.
        Thread.sleep(SETTLE_MS)
        BenchMemory.sample()
      },
    )
  }

  private companion object {
    const val ITERATIONS = 5
    const val LIBRARY_TIMEOUT_MS = 10_000L
    const val SETTLE_MS = 2_000L
  }
}
