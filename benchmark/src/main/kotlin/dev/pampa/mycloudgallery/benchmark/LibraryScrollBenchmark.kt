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
 * Fotogrammi in ritardo durante fling e scorrimento su 100 mila elementi, ai 4 livelli e nei 2 stili (§2.6, riga 1).
 * `FrameTimingMetric`: la soglia e' `frameOverrunMs` P99 <= 0, cioe' meno dell'1% dei fotogrammi oltre gli 8,3 ms
 * (120 Hz); la regressione e' P95 di `frameDurationCpuMs` +10% sul riferimento (`benchmark/soglie.json`).
 *
 * Otto prove, una per coppia livello/stile, con il nome che `soglie.json` e `compare.ps1` si aspettano
 * (`scroll_<livello>_<stile>`). Ogni iterazione ricomincia da un'app appena avviata, sul livello e sullo stile della
 * prova, in fondo (D1) dove sono i piu' recenti; il gesto e' un andata e ritorno di fling nel tempo.
 *
 * Su un dispositivo a 120 Hz serve "Fluidita' movimento: Adattiva" (`check.ps1 -Bench` annota la frequenza letta).
 */
@RunWith(AndroidJUnit4::class)
class LibraryScrollBenchmark {

  @get:Rule
  val rule = MacrobenchmarkRule()

  @Before
  fun requireLibrary() {
    BenchSession.requireLibrary()
  }

  @Test fun scroll_years_square() = scroll(BenchLevel.YEARS, BenchStyle.SQUARE)

  @Test fun scroll_months_square() = scroll(BenchLevel.MONTHS, BenchStyle.SQUARE)

  @Test fun scroll_days_square() = scroll(BenchLevel.DAYS, BenchStyle.SQUARE)

  @Test fun scroll_all_square() = scroll(BenchLevel.ALL, BenchStyle.SQUARE)

  @Test fun scroll_years_justified() = scroll(BenchLevel.YEARS, BenchStyle.JUSTIFIED)

  @Test fun scroll_months_justified() = scroll(BenchLevel.MONTHS, BenchStyle.JUSTIFIED)

  @Test fun scroll_days_justified() = scroll(BenchLevel.DAYS, BenchStyle.JUSTIFIED)

  @Test fun scroll_all_justified() = scroll(BenchLevel.ALL, BenchStyle.JUSTIFIED)

  private fun scroll(level: BenchLevel, style: BenchStyle) {
    // Lo stile quadrato e' quello di partenza: si prova anche senza menu. Il giustificato no (D4: lo sceglie il menu).
    if (style == BenchStyle.JUSTIFIED) {
      Assume.assumeTrue("Menu dello stile '${style.label}' non trovato (in attesa di P7, vedi BenchLabels).", BenchSession.readiness().styleMenu)
    }
    rule.measureRepeated(
      packageName = BenchSession.targetPackage,
      metrics = listOf(FrameTimingMetric()),
      compilationMode = BenchConfig.compilationMode,
      iterations = BenchConfig.iterations(ITERATIONS),
      setupBlock = {
        killProcess()
        startActivityAndWait()
        check(BenchUi.waitForLibrary(device, LIBRARY_TIMEOUT_MS)) { "La Libreria non si vede piu' dopo l'avvio." }
        // Lo stile e il livello si ricordano fra gli avvii: si impostano sempre, mai per abitudine.
        BenchUi.selectStyle(device, style)
        check(BenchUi.selectLevel(device, level)) { "La griglia non c'e' per cambiare livello." }
        device.waitForIdle()
      },
      measureBlock = {
        // Indietro nel tempo e di nuovo ai piu' recenti: fling lunghi, uno dopo l'altro, senza aspettare la quiete.
        BenchUi.fling(device, towardsOlder = true, times = BenchConfig.repeats(FLINGS))
        BenchUi.fling(device, towardsOlder = false, times = BenchConfig.repeats(FLINGS))
      },
    )
  }

  private companion object {
    const val ITERATIONS = 8
    const val FLINGS = 6
    const val LIBRARY_TIMEOUT_MS = 20_000L
  }
}
