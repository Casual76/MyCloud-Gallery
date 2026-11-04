package dev.pampa.mycloudgallery.benchmark

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Apertura del viewer (§2.6, riga 4): dal tocco sulla miniatura al primo fotogramma con l'immagine. La misura e' la
 * sezione di traccia `MCG:viewerOpen` (`ViewerOpenMetric`), p90 < 100 ms, regressione +15%.
 *
 * **Lo deve emettere il viewer (P8)**, dal gestore del tocco al primo fotogramma con la bitmap: vedi "Sezioni di traccia"
 * in `docs/bench/README.md`. Se la sezione manca la prova gira lo stesso e riporta `viewerSectionMissing`: l'apertura del
 * viewer e' comunque eseguita (tocco, attesa, chiusura con Indietro), cosi' le regressioni di stabilita' si vedono prima.
 *
 * Per ogni iterazione: app appena avviata, tocco sulla cella piu' vicina al centro (una foto della rotazione dei media
 * veri), aspetta la quiete, chiude con Indietro. Il nome della prova e' `viewerOpen`: e' la chiave di `soglie.json`.
 *
 * **Sulla libreria mista (Fase 4A, P10)** la prima schermata e' del telefono: le 120 righe piu' recenti non hanno il NAS
 * (`BenchDataset.HEAD_PHONE_ROWS`), quindi la cella al centro e' sempre una foto del telefono e la prova resta confrontabile
 * con il 13/10 (p90 <= 25 ms). L'apertura di un media solo-NAS e' un'altra prova: `NasLibraryBenchmark.viewerOpenNas`.
 */
@RunWith(AndroidJUnit4::class)
class ViewerBenchmark {

  @get:Rule
  val rule = MacrobenchmarkRule()

  @Before
  fun requireLibrary() {
    BenchSession.requireLibrary()
  }

  @Test
  fun viewerOpen() {
    rule.measureRepeated(
      packageName = BenchSession.targetPackage,
      metrics = listOf(ViewerOpenMetric(), FrameTimingMetric(), LibraryMixMetric(), FrameDiagMetric(openingWindowNs = 500_000_000L)),
      compilationMode = BenchConfig.compilationMode,
      iterations = BenchConfig.iterations(ITERATIONS),
      setupBlock = {
        killProcess()
        startActivityAndWait()
        check(BenchUi.waitForLibrary(device, LIBRARY_TIMEOUT_MS)) { "La Libreria non si vede piu' dopo l'avvio." }
        device.waitForIdle()
      },
      measureBlock = {
        check(BenchUi.tapCell(device, BenchUi.centerCell(device))) { "Nessuna cella da toccare al centro della Libreria." }
        device.waitForIdle()
        Thread.sleep(VIEWER_SETTLE_MS)
        BenchUi.closeViewer(device)
        device.waitForIdle()
      },
    )
  }

  private companion object {
    const val ITERATIONS = 12
    const val LIBRARY_TIMEOUT_MS = 20_000L

    /** Il tempo perche' l'immagine nitida sostituisca la miniatura (E-V1): dentro la misura, la sezione di traccia e' una sola. */
    const val VIEWER_SETTLE_MS = 800L
  }
}
