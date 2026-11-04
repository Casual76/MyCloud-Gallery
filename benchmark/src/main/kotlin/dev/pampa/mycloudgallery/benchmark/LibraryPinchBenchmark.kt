package dev.pampa.mycloudgallery.benchmark

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pinch fra i livelli (§2.6, riga 2): 10 pinch continui con UiAutomator (cinque coppie di chiusura e apertura, una dopo
 * l'altra senza aspettare la quiete), sulla Libreria di 100 mila elementi. Soglia: `jankPercent` (`FrameJankMetric`, i
 * fotogrammi davvero in ritardo secondo FrameTimeline) <= 1% in media sulle iterazioni; regressione P95 di
 * `frameDurationCpuMs` (`FrameTimingMetric`) +10%.
 *
 * Il pinch cambia livello e l'ancoraggio tiene l'elemento sotto il dito (D3, `GridAnchors`): in questa prova conta che
 * il cambio, con il layout dei quattro livelli precalcolato fuori dal thread principale, non faccia perdere fotogrammi.
 * Il nome della prova e' `pinch`: e' la chiave di `soglie.json`.
 */
@RunWith(AndroidJUnit4::class)
class LibraryPinchBenchmark {

  @get:Rule
  val rule = MacrobenchmarkRule()

  @Before
  fun requireLibrary() {
    BenchSession.requireLibrary()
  }

  @Test
  fun pinch() {
    rule.measureRepeated(
      packageName = BenchSession.targetPackage,
      metrics = listOf(FrameTimingMetric(), FrameJankMetric(), LibraryMixMetric(), FrameDiagMetric()),
      compilationMode = BenchConfig.compilationMode,
      iterations = BenchConfig.iterations(ITERATIONS),
      setupBlock = {
        killProcess()
        startActivityAndWait()
        check(BenchUi.waitForLibrary(device, LIBRARY_TIMEOUT_MS)) { "La Libreria non si vede piu' dopo l'avvio." }
        // Si parte dal livello di mezzo: ci sono livelli da attraversare in tutte e due le direzioni.
        BenchUi.selectLevel(device, BenchLevel.DAYS)
        device.waitForIdle()
      },
      measureBlock = {
        repeat(BenchConfig.repeats(PINCH_PAIRS)) {
          BenchUi.pinch(device, close = true)
          BenchUi.pinch(device, close = false)
        }
      },
    )
  }

  private companion object {
    const val ITERATIONS = 6

    /** 5 coppie = 10 pinch continui (§2.6). */
    const val PINCH_PAIRS = 5
    const val LIBRARY_TIMEOUT_MS = 20_000L
  }
}
