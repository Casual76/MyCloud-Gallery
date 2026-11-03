package dev.pampa.mycloudgallery.benchmark

import androidx.benchmark.macro.Metric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Avvio a freddo (§2.6, riga 3): `StartupMode.COLD`, 15 ripetizioni. Dall'Avvio istantaneo (28/10) le soglie sono due: il **primo disegno
 * utile** (`libraryUsefulMs`, [LibraryUsefulMetric]: la Libreria con le miniature delle celle in vista), stretta, e il **caricamento completo**
 * (`timeToFullDisplayMs`: l'app chiama `reportFullyDrawn` alla prima pagina di miniature disegnata, o dopo 300 ms), larga (1.500 ms). Vedi
 * `benchmark/soglie.json` e docs/bench/README.md §2-quater.
 *
 * `StartupMode.COLD` butta la cache delle pipeline grafiche e quella delle pagine del kernel prima di ogni avvio: e' il primo avvio dopo
 * un'installazione o un aggiornamento, il caso peggiore. L'avvio di tutti i giorni e' [coldStartUsual].
 *
 * Il percorso d'avvio e' quello del §2.6: grafo della Fase 2 pigro e senza sessione NAS, database aperto fuori dal
 * thread principale, istantanea mappata in meno di 20 ms, primo fotogramma con i segnaposto ThumbHash, riconciliazione
 * con il database dopo il primo fotogramma. Il dataset e' gia' pronto quando si misura (lo prepara la sonda di
 * `BenchSession`): gli avvii misurati non lo creano.
 *
 * **Se l'app non chiama `reportFullyDrawn`** (P7/P11 non l'hanno ancora messo) `timeToFullDisplayMs` manca: la prova
 * gira lo stesso, `compare.ps1` riporta "TTFD assente" e confronta solo `timeToInitialDisplayMs` (informativo).
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

  @get:Rule
  val rule = MacrobenchmarkRule()

  private var libraryReachable = false

  @Before
  fun prepare() {
    // La sonda prepara il dataset e i permessi. Lo startup NON si salta se la Libreria non c'e': misura comunque l'avvio
    // (verso l'onboarding), cosi' l'infrastruttura si vede girare anche prima di M1. La metrica `libraryShown` dice poi
    // a compare.ps1 che quel numero non e' quello della Libreria e non va confrontato con la soglia.
    libraryReachable = BenchSession.readiness().ready
  }

  @Test
  fun coldStart() {
    rule.measureRepeated(
      packageName = BenchSession.targetPackage,
      // Non `StartupTimingMetric`: nell'avvio COLD sul Tab S9 non trovava il fotogramma dopo reportFullyDrawn (vedi [StartupDisplayMetric]).
      metrics = listOf<Metric>(StartupDisplayMetric(), LibraryUsefulMetric(), LibraryShownMetric(), LibraryMixMetric()),
      compilationMode = BenchConfig.compilationMode,
      startupMode = StartupMode.COLD,
      iterations = BenchConfig.iterations(ITERATIONS),
      setupBlock = {
        pressHome()
      },
      measureBlock = {
        startActivityAndWait()
        // Resta nella misura fino alla prima pagina di miniature: serve alla traccia per vedere reportFullyDrawn.
        BenchState.libraryShown = BenchUi.waitForLibrary(device, if (libraryReachable) FULL_DISPLAY_WAIT_MS else NO_LIBRARY_WAIT_MS)
        // La prima cella si vede prima di reportFullyDrawn (che aspetta le miniature, al piu' 300 ms): la traccia deve
        // contenere anche il fotogramma che lo segue, o StartupTimingMetric non trova il fotogramma del RenderThread
        // ("No RT frame slice ... after reportFullyDrawn", S25 13/10, quando l'avvio e' diventato piu' veloce).
        if (BenchState.libraryShown) Thread.sleep(FULLY_DRAWN_SETTLE_MS)
      },
    )
  }

  /**
   * Avvio istantaneo (28/10): l'**avvio a freddo abituale**, quello di tutti i giorni. Un processo nuovo ogni volta (lo si uccide nel
   * `setupBlock`), ma senza buttare la cache delle pipeline grafiche e quella delle pagine del kernel, come fa `StartupMode.COLD`: un utente
   * le butta solo installando o aggiornando l'app. `StartupTimingMetric` senza modo d'avvio legge l'avvio come freddo (e' un processo nuovo).
   */
  @Test
  fun coldStartUsual() {
    rule.measureRepeated(
      packageName = BenchSession.targetPackage,
      metrics = listOf(StartupTimingMetric(), LibraryUsefulMetric(), LibraryShownMetric(), LibraryMixMetric()),
      compilationMode = BenchConfig.compilationMode,
      startupMode = null,
      iterations = BenchConfig.iterations(ITERATIONS),
      setupBlock = {
        pressHome()
        killProcess()
      },
      measureBlock = {
        startActivityAndWait()
        BenchState.libraryShown = BenchUi.waitForLibrary(device, if (libraryReachable) FULL_DISPLAY_WAIT_MS else NO_LIBRARY_WAIT_MS)
        if (BenchState.libraryShown) Thread.sleep(FULLY_DRAWN_SETTLE_MS)
      },
    )
  }

  private companion object {
    const val ITERATIONS = 15
    const val FULL_DISPLAY_WAIT_MS = 5_000L
    const val NO_LIBRARY_WAIT_MS = 500L
    const val FULLY_DRAWN_SETTLE_MS = 1_000L
  }
}
