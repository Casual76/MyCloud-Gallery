package dev.pampa.mycloudgallery.benchmark

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Zoom 200 MP sul tablet (§2.6, riga 5): zoom 1:1 e pan sui quattro angoli, 5 cicli, sulla foto piu' grande del
 * dispositivo (il dataset la mette nella riga piu' recente, in fondo). Soglie: nessun OOM (`processAlive` = 1 a ogni
 * iterazione) e PSS di picco (`pssPeakKb`) sotto il tetto calibrato alla prima misura (`soglie.json`, `compare.ps1 -Calibrate`).
 *
 * La foto da 200 MP la genera `tools/fixtures` (P3, `adb push` in `Pictures/MCG-prova/`, §5.5). **Senza una foto da almeno
 * 100 MP la prova si salta** con il motivo: una foto piu' piccola non mette sotto sforzo i tasselli.
 */
@RunWith(AndroidJUnit4::class)
class Zoom200MpBenchmark {

  @get:Rule
  val rule = MacrobenchmarkRule()

  @Before
  fun requireLibraryAndLargeImage() {
    val readiness = BenchSession.requireLibrary()
    val dataset = readiness.dataset
    // Nello smoke (emulatore) basta l'immagine piu' grande che c'e': si vuole vedere girare il gesto, non misurare la memoria.
    Assume.assumeTrue(
      "Manca la foto da 200 MP (la piu' grande ha ${dataset?.maxMegapixels?.toInt() ?: 0} MP): copiarla con tools/fixtures (P3) in Pictures/MCG-prova/.",
      dataset != null && dataset.zoomSubjectNewest && (BenchConfig.smoke || dataset.maxMegapixels >= MIN_MEGAPIXELS),
    )
  }

  @Test
  fun zoom200Mp() {
    rule.measureRepeated(
      packageName = BenchSession.targetPackage,
      metrics = listOf(PssPeakMetric(), FrameTimingMetric()),
      compilationMode = BenchConfig.compilationMode,
      iterations = BenchConfig.iterations(ITERATIONS),
      setupBlock = {
        killProcess()
        startActivityAndWait()
        check(BenchUi.waitForLibrary(device, LIBRARY_TIMEOUT_MS)) { "La Libreria non si vede piu' dopo l'avvio." }
        device.waitForIdle()
        BenchPss.reset()
      },
      measureBlock = {
        // La riga piu' recente e' il soggetto dello zoom (BenchSeeder): l'ultima cella in fondo.
        check(BenchUi.tapCell(device, BenchUi.newestCell(device))) { "Nessuna cella in fondo alla Libreria." }
        device.waitForIdle()
        Thread.sleep(SHARP_IMAGE_WAIT_MS)
        repeat(BenchConfig.repeats(CYCLES)) {
          // Zoom fino al massimo (1:1 e oltre), poi pan verso i quattro angoli e di nuovo al centro.
          BenchUi.doubleTapCenter(device)
          device.waitForIdle()
          repeat(PINCH_STEPS) { BenchUi.pinch(device, close = false, percent = 0.8f) }
          BenchPss.sample()
          for ((dx, dy) in CORNERS) {
            BenchUi.panFromCenter(device, dx, dy)
            device.waitForIdle()
            BenchPss.sample()
          }
          BenchUi.doubleTapCenter(device) // torna all'adattamento allo schermo
          device.waitForIdle()
        }
        BenchUi.back(device)
        device.waitForIdle()
        BenchPss.finish()
      },
    )
  }

  private companion object {
    const val ITERATIONS = 3
    const val CYCLES = 5
    const val PINCH_STEPS = 3
    const val LIBRARY_TIMEOUT_MS = 20_000L
    const val SHARP_IMAGE_WAIT_MS = 1_500L
    const val MIN_MEGAPIXELS = 100.0
    val CORNERS = listOf(-1 to -1, 1 to -1, 1 to 1, -1 to 1)
  }
}
