package dev.pampa.mycloudgallery.benchmark

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Le due misure **nuove** della Fase 4A (`docs/fase4-progetto.md` §5.6, P10), sul profilo "NAS prima" della libreria di
 * misura: le 300 righe piu' recenti sono solo-NAS, tutte con le miniature S/M e l'HQ nella replica sintetica
 * (`BenchDataset.HEAD_NAS_ROWS`). Cosi' la prima pagina della Libreria e' fatta solo di miniature del NAS e la cella al
 * centro e' sempre un media solo-NAS, **senza** che la prova debba riconoscere le celle dal testo (che il filone grafico puo'
 * cambiare): il profilo lo sceglie il collaudo con un file accanto al marcatore (`BenchSession.readiness`).
 *
 * - `coldStartNasFirst`: avvio a freddo fino alla prima pagina di miniature del NAS (`reportFullyDrawn`, TTFD). Soglia
 *   p90 < 1000 ms come l'avvio della Fase 3; la guardia dell'avvio normale (<= 750 ms) vale per `coldStart`.
 * - `viewerOpenNas`: apertura di un media solo-NAS **con l'HQ in replica**, dalla sezione di traccia `MCG:viewerOpen` (P8):
 *   p90 < 100 ms.
 *
 * Senza il collegamento dell'app (P7: miniature dalla replica; P8: viewer del NAS; P11: `MeasurementHooks.replicaBlobs`) le
 * prove girano lo stesso: le celle del NAS restano segnaposto e la sezione di traccia puo' mancare (`compare.ps1` lo dice).
 * I numeri valgono solo dopo P7, P8 e P11 su S25 e Tab S9.
 */
@RunWith(AndroidJUnit4::class)
class NasLibraryBenchmark {

  @get:Rule
  val rule = MacrobenchmarkRule()

  @Before
  fun requireNasFirstLibrary() {
    val readiness = BenchSession.requireLibrary(BenchLibraryProfile.NAS_FIRST)
    val dataset = readiness.dataset
    Assume.assumeTrue(
      "La libreria 'NAS prima' non e' pronta (righe solo-NAS in testa: ${dataset?.headNasRows ?: 0}, blob in replica: ${dataset?.replicaBlobs ?: 0}).",
      dataset != null && dataset.headNasRows >= MIN_HEAD_ROWS && dataset.replicaBlobs > 0,
    )
  }

  @Test
  fun coldStartNasFirst() {
    rule.measureRepeated(
      packageName = BenchSession.targetPackage,
      metrics = listOf(StartupTimingMetric(), LibraryShownMetric(), LibraryMixMetric()),
      compilationMode = BenchConfig.compilationMode,
      startupMode = StartupMode.COLD,
      iterations = BenchConfig.iterations(START_ITERATIONS),
      setupBlock = {
        pressHome()
      },
      measureBlock = {
        startActivityAndWait()
        // Resta nella misura fino alla prima pagina di miniature: serve alla traccia per vedere reportFullyDrawn.
        BenchState.libraryShown = BenchUi.waitForLibrary(device, FULL_DISPLAY_WAIT_MS)
        if (BenchState.libraryShown) Thread.sleep(FULLY_DRAWN_SETTLE_MS)
      },
    )
  }

  @Test
  fun viewerOpenNas() {
    rule.measureRepeated(
      packageName = BenchSession.targetPackage,
      metrics = listOf(ViewerOpenMetric(), FrameTimingMetric(), LibraryMixMetric()),
      compilationMode = BenchConfig.compilationMode,
      iterations = BenchConfig.iterations(VIEWER_ITERATIONS),
      setupBlock = {
        killProcess()
        startActivityAndWait()
        check(BenchUi.waitForLibrary(device, LIBRARY_TIMEOUT_MS)) { "La Libreria non si vede piu' dopo l'avvio." }
        device.waitForIdle()
      },
      measureBlock = {
        // La prima pagina e' tutta solo-NAS: la cella al centro lo e'.
        check(BenchUi.tapCell(device, BenchUi.centerCell(device))) { "Nessuna cella da toccare al centro della Libreria." }
        device.waitForIdle()
        Thread.sleep(VIEWER_SETTLE_MS)
        BenchUi.closeViewer(device)
        device.waitForIdle()
      },
    )
  }

  private companion object {
    const val START_ITERATIONS = 15
    const val VIEWER_ITERATIONS = 12
    const val FULL_DISPLAY_WAIT_MS = 5_000L
    const val FULLY_DRAWN_SETTLE_MS = 1_000L
    const val LIBRARY_TIMEOUT_MS = 20_000L
    const val VIEWER_SETTLE_MS = 800L

    /** Quanto deve essere lunga la testa solo-NAS perche' la prima schermata (anche di un tablet) ne sia fatta solo. */
    const val MIN_HEAD_ROWS = 100
  }
}
