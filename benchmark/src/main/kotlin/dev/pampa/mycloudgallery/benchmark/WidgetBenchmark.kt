package dev.pampa.mycloudgallery.benchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Il widget (docs/fase10-progetto.md §5.4, riga P9 del §4.4; scenario `widget`), sulla libreria mista da 100 mila elementi con il **seme del widget** (`BenchWidgetServices`:
 * le porte di `feature-widget` sopra la Libreria del dataset) e un widget **grande** legato dal pilota (`BenchWidgetDriver`): le sette immagini, il caso piu' pesante.
 *
 * Tre prove:
 *  - `widgetUpdate` (`widget`, telefono): un giro di aggiornamento (sezione di traccia `MCG:widget.update`: piano, decodifica, registro, composizione Glance, consegna ad
 *    `AppWidgetManager`) e lo svuotamento dopo un "Nascondi" (`MCG:widget.blank`). Soglie: giro **p90 <= 400 ms** (processo caldo: dopo il primo giro; il primo, a freddo,
 *    ha la sua misura e la sua regressione), svuotamento **p90 <= 100 ms**, regressione +15%;
 *  - `widgetUpdateTablet` (`widget-tablet`): la stessa prova su un tablet (>= 600 dp di larghezza minima), con **600 ms** al posto di 400 (§5.4: "Tab S9 <= 600 ms"). Le due
 *    prove hanno nomi diversi perche' una prova sta in uno scenario solo e le soglie dipendono dalla classe del dispositivo: dove non e' la sua classe si salta, e il confronto
 *    dice "N/A";
 *  - `widgetLoadedScroll` (`widget-scroll-carico`): la guardia di scorrimento della Libreria (livello Tutte, stile quadrato) **con un aggiornamento del widget in corso** (i giri
 *    di continuo, uno alla volta con una pausa): le guardie esistenti non devono peggiorare (meno dell'1%, come la Libreria).
 *
 * Come i ricordi: se l'app non ha il pilota, o il sistema non lega il widget (lo lega la prova, con l'identita' della shell per `BIND_APPWIDGET`), la prova si **salta** con il motivo
 * (`Assume`), mai un falso verde. Niente si misura sull'emulatore: i numeri valgono solo su S25 e Tab S9 (`compare.ps1 -Smoke` altrove). Niente rete, nessun launcher: il disegno
 * arriva ad `AppWidgetManager` (il lavoro del launcher che lo mostra e' in un altro processo).
 */
@RunWith(AndroidJUnit4::class)
class WidgetBenchmark {

  @get:Rule
  val rule = MacrobenchmarkRule()

  @Before
  fun requireLibrary() {
    BenchSession.requireLibrary()
    WidgetBenchState.reset()
  }

  @After
  fun cleanUp() = WidgetDriver.cleanup()

  /** Un giro di aggiornamento e uno svuotamento, sul telefono: soglia 400 ms. */
  @Test
  fun widgetUpdate() {
    Assume.assumeFalse("Questa prova e' per i telefoni (sotto i ${WidgetBenchTarget.TABLET_MIN_DP} dp): sul tablet gira widgetUpdateTablet.", WidgetBenchTarget.isTablet())
    updateAndBlank()
  }

  /** Lo stesso giro sul tablet: soglia 600 ms. */
  @Test
  fun widgetUpdateTablet() {
    Assume.assumeTrue("Questa prova e' per i tablet (almeno ${WidgetBenchTarget.TABLET_MIN_DP} dp): sul telefono gira widgetUpdate.", WidgetBenchTarget.isTablet())
    updateAndBlank()
  }

  @OptIn(ExperimentalMetricApi::class)
  private fun updateAndBlank() {
    rule.measureRepeated(
      packageName = BenchSession.targetPackage,
      metrics = listOf(WidgetSectionsMetric(), LibraryMixMetric()),
      compilationMode = BenchConfig.compilationMode,
      iterations = BenchConfig.iterations(ITERATIONS),
      setupBlock = {
        WidgetBenchState.reset()
        startLibrary()
        prepareWidget()
      },
      measureBlock = {
        // Il primo giro del processo e' a freddo (Glance si compone la prima volta); gli altri sono a caldo. Poi due "Nascondi" di prova: ognuno svuota e rifa' il giro.
        WidgetDriver.round("UPDATE")
        repeat(WARM_UPDATES) { WidgetDriver.round("UPDATE") }
        repeat(BLANKS) { WidgetDriver.round("BLANK") }
      },
    )
  }

  /** Fling avanti e indietro nella Libreria mentre il widget si aggiorna di continuo (livello Tutte, stile quadrato: la prova `scroll_all_square`). */
  @OptIn(ExperimentalMetricApi::class)
  @Test
  fun widgetLoadedScroll() {
    rule.measureRepeated(
      packageName = BenchSession.targetPackage,
      metrics = listOf(FrameTimingMetric(), FrameJankMetric(), WidgetRefreshingMetric(), LibraryMixMetric()),
      compilationMode = BenchConfig.compilationMode,
      iterations = BenchConfig.iterations(SCROLL_ITERATIONS),
      setupBlock = {
        WidgetBenchState.reset()
        startLibrary()
        prepareWidget()
        Assume.assumeTrue("I giri del widget di continuo non partono (LOAD_START).", WidgetDriver.startLoad())
        check(BenchUi.selectStyle(device, BenchStyle.SQUARE)) { "Lo stile '${BenchStyle.SQUARE.label}' non si sceglie dal menu della vista." }
        check(BenchUi.selectLevel(device, BenchLevel.ALL)) { "La griglia non c'e' per cambiare livello." }
        device.waitForIdle()
      },
      measureBlock = {
        BenchUi.fling(device, towardsOlder = true, times = BenchConfig.repeats(FLINGS))
        BenchUi.fling(device, towardsOlder = false, times = BenchConfig.repeats(FLINGS))
      },
      // I giri di continuo si fermano alla fine di ogni iterazione: il processo successivo ricomincia da zero.
    )
  }

  /** Avvio a freddo e Libreria visibile. */
  private fun MacrobenchmarkScope.startLibrary() {
    killProcess()
    startActivityAndWait()
    check(BenchUi.waitForLibrary(device, LIBRARY_TIMEOUT_MS)) { "La Libreria non si vede dopo l'avvio." }
    device.waitForIdle()
  }

  /** Il seme, il widget grande e il primo disegno; altrimenti la prova si salta con il motivo. */
  private fun prepareWidget() {
    val why = WidgetDriver.prepare()
    Assume.assumeTrue(why ?: "", why == null)
  }

  private companion object {
    const val ITERATIONS = 8
    const val SCROLL_ITERATIONS = 8
    const val WARM_UPDATES = 3
    const val BLANKS = 2
    const val FLINGS = 6
    /** Un emulatore a 2560x1600 con il disegno in software avvia l'app in decine di secondi: nello smoke si aspetta il triplo. */
    val LIBRARY_TIMEOUT_MS: Long get() = if (BenchConfig.smoke) 60_000L else 20_000L
  }
}
