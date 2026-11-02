package dev.pampa.mycloudgallery.benchmark

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.Metric
import androidx.benchmark.macro.TraceMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.benchmark.traceprocessor.TraceProcessor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * L'accessibilita' (docs/fase10-progetto.md §5.4, riga P9 del §4.4, "Cosa serve a P9" di P8; scenario `accessibilita`), sulla libreria mista da 100 mila elementi. Tre prove, tre
 * scenari di `soglie.json`:
 *  - `a11yScroll` (`accessibilita-scroll`): lo scorrimento della Libreria (livello Tutte, stile quadrato) con il **servizio di prova acceso** ([BenchA11yService], che simula
 *    TalkBack: strumento di accessibilita', legge l'albero a ogni scorrimento): `jankPercent` <= 1%, guardia +10% relativo con pavimento di 0,15 punti;
 *  - `a11yTextScroll` (`accessibilita-testo`): lo stesso scorrimento con il **testo al 200%** (`font_scale` 2,0): <= 1%, come la Libreria;
 *  - `a11yViewerOpen` (`accessibilita-viewer`): l'apertura del viewer con il servizio acceso (la descrizione si costruisce quando il lettore chiede il nodo): la sezione
 *    `MCG:viewerOpen`, p90 <= 100 ms, +15% (la guardia del viewer).
 *
 * **Il servizio** si accende con `settings put secure enabled_accessibility_services` (rimesso com'era a fine prova: [BenchSettings]) e **UiAutomation non deve sospenderlo**:
 * senza `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES` UiAutomation disattiva gli altri servizi finche' e' collegato. La prova lo imposta ([enableService]) e controlla che il
 * servizio abbia letto davvero (`a11yWalks`, `a11yConnected`): se no la prova e' NON VALIDA, mai un falso verde.
 *
 * **`warm` delle descrizioni**: con uno strumento attivo la Libreria chiede alle porte i nomi delle celle in vista 150 ms dopo che lo scorrimento si ferma (P7, P8). Parte solo
 * con il descrittore legato da P13 (`MediaDescribers.install` con `A11yActivity` sopra `AccessibilityManager`); finche' non c'e' `warm` non fa niente e queste prove misurano il
 * lettore dell'albero e gli eventi, che sono il grosso. Dopo P13 includono anche `warm`. Niente si misura sull'emulatore: i numeri valgono solo su S25 e Tab S9.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityScrollBenchmark {

  @get:Rule
  val rule = MacrobenchmarkRule()

  @Before
  fun requireLibrary() {
    BenchSettings.restoreStale()
    BenchSession.requireLibrary()
    A11yBenchState.reset()
  }

  @After
  fun cleanUp() {
    // Il servizio, i flag di UiAutomation e la scala del testo tornano com'erano.
    BenchSettings.restoreAll()
    BenchConditions.disableService()
    A11yBenchState.reset()
  }

  /** Fling avanti e indietro nella Libreria con il servizio di prova acceso. */
  @OptIn(ExperimentalMetricApi::class)
  @Test
  fun a11yScroll() {
    enableService()
    scroll(listOf(FrameTimingMetric(), FrameJankMetric(), A11yServiceMetric(), LibraryMixMetric()))
  }

  /** Fling avanti e indietro con il testo al 200% (`font_scale` 2,0). */
  @OptIn(ExperimentalMetricApi::class)
  @Test
  fun a11yTextScroll() {
    A11yBenchState.fontScale = FONT_SCALE.toDouble()
    scroll(listOf(FrameTimingMetric(), FrameJankMetric(), TextScaleMetric(), LibraryMixMetric()), fontScale = FONT_SCALE)
  }

  /** L'apertura del viewer con il servizio acceso: la sezione `MCG:viewerOpen`. */
  @OptIn(ExperimentalMetricApi::class)
  @Test
  fun a11yViewerOpen() {
    enableService()
    rule.measureRepeated(
      packageName = BenchSession.targetPackage,
      metrics = listOf(ViewerOpenMetric(), FrameTimingMetric(), A11yServiceMetric(), LibraryMixMetric()),
      compilationMode = BenchConfig.compilationMode,
      iterations = BenchConfig.iterations(VIEWER_ITERATIONS),
      setupBlock = {
        killProcess()
        startActivityAndWait()
        check(BenchUi.waitForLibrary(device, LIBRARY_TIMEOUT_MS)) { "La Libreria non si vede dopo l'avvio." }
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

  /** Lo scorrimento della prova `scroll_all_square`, con le [metrics] date. */
  @OptIn(ExperimentalMetricApi::class)
  private fun scroll(metrics: List<androidx.benchmark.macro.Metric>, fontScale: String? = null) {
    rule.measureRepeated(
      packageName = BenchSession.targetPackage,
      metrics = metrics,
      compilationMode = BenchConfig.compilationMode,
      iterations = BenchConfig.iterations(SCROLL_ITERATIONS),
      setupBlock = {
        // Lo stile e il livello si scelgono dal menu a testo normale: a 2,0x le voci non si trovano piu' con gli stessi selettori. Il testo grande si mette dopo.
        if (fontScale != null) BenchSettings.fontScale("1.0")
        killProcess()
        startActivityAndWait()
        check(BenchUi.waitForLibrary(device, LIBRARY_TIMEOUT_MS)) { "La Libreria non si vede dopo l'avvio." }
        // Lo stile quadrato e' quello di partenza (come `LibraryScrollBenchmark`): se il menu non risponde, con il servizio che legge l'albero a ogni evento, non si ferma la prova.
        if (!BenchUi.selectStyle(device, BenchStyle.SQUARE)) android.util.Log.w("MCG-Bench", "Il menu dello stile non risponde: resta lo stile di partenza (quadrato).")
        check(BenchUi.selectLevel(device, BenchLevel.ALL)) { "La griglia non c'e' per cambiare livello." }
        device.waitForIdle()
        if (fontScale != null) {
          // Il cambio di scala ricrea l'attivita' (fontScale non e' fra i configChanges): lo stato salvato torna e si aspetta la Libreria.
          BenchSettings.fontScale(fontScale)
          device.waitForIdle()
          check(BenchUi.waitForLibrary(device, LIBRARY_TIMEOUT_MS)) { "La Libreria non si vede dopo il testo grande." }
          device.waitForIdle()
        }
      },
      measureBlock = {
        BenchUi.fling(device, towardsOlder = true, times = BenchConfig.repeats(FLINGS))
        BenchUi.fling(device, towardsOlder = false, times = BenchConfig.repeats(FLINGS))
      },
    )
  }

  /**
   * Accende il servizio di prova senza che UiAutomation lo sospenda: i flag di UiAutomator, la UiAutomation ricollegata con quei flag, poi `settings`. Aspetta che il
   * servizio sia collegato; se non lo e' la prova si salta con il motivo.
   */
  private fun enableService() {
    Assume.assumeTrue("Il servizio di prova non si collega: il sistema non lo accende (vedi BenchA11yService e BenchConditions).", BenchConditions.enableService())
  }

  private companion object {
    const val SCROLL_ITERATIONS = 8
    const val VIEWER_ITERATIONS = 12
    const val FLINGS = 6
    const val LIBRARY_TIMEOUT_MS = 20_000L
    const val VIEWER_SETTLE_MS = 800L
    const val FONT_SCALE = "2.0"
  }
}

/** Lo stato delle prove di accessibilita' consegnato alle metriche proprie. */
object A11yBenchState {
  @Volatile var fontScale: Double = 1.0

  fun reset() {
    fontScale = 1.0
  }
}

/**
 * Cosa ha fatto il servizio di prova nell'iterazione ([BenchA11yStats]): `a11yWalks` (letture dell'albero), `a11yNodes`, `a11yDisconnections` (quante volte il sistema l'ha
 * scollegato: UiAutomation puo' sospenderlo), `a11yConnected` (collegato alla fine), `a11yToolActive` (il sistema lo elenca come **strumento** di accessibilita': e' quello che
 * guarda il descrittore) e `a11yValid`: 1 se il servizio era collegato e ha letto davvero (almeno [MIN_WALKS] letture).
 */
@OptIn(ExperimentalMetricApi::class)
class A11yServiceMetric : TraceMetric() {

  override fun getMeasurements(captureInfo: Metric.CaptureInfo, traceSession: TraceProcessor.Session): List<Metric.Measurement> {
    val walks = BenchA11yStats.walks.get()
    val valid = BenchA11yStats.connected && walks >= MIN_WALKS
    return listOf(
      Metric.Measurement("a11yWalks", walks.toDouble()),
      Metric.Measurement("a11yNodes", BenchA11yStats.nodes.get().toDouble()),
      Metric.Measurement("a11yNodesWithText", BenchA11yStats.nodesWithText.get().toDouble()),
      Metric.Measurement("a11yEvents", BenchA11yStats.events.get().toDouble()),
      Metric.Measurement("a11yDisconnections", BenchA11yStats.disconnections.get().toDouble()),
      Metric.Measurement("a11yConnected", if (BenchA11yStats.connected) 1.0 else 0.0),
      Metric.Measurement("a11yToolActive", if (toolActive()) 1.0 else 0.0),
      Metric.Measurement("a11yValid", if (valid) 1.0 else 0.0),
    )
  }

  /** Il servizio e' fra quelli accesi e si dichiara strumento di accessibilita' (Android 14+: `isAccessibilityTool`). */
  private fun toolActive(): Boolean {
    val context = InstrumentationRegistry.getInstrumentation().context
    val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
    val ours = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).filter { it.id.startsWith(context.packageName) }
    return ours.isNotEmpty() && ours.all { if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) it.isAccessibilityTool else true }
  }

  companion object {
    /** Almeno cinque letture durante il blocco misurato (un fling ne fa decine). */
    const val MIN_WALKS = 5
  }
}

/** La scala del testo della prova (`a11yFontScale`): la validita' di `accessibilita-testo` (1,99 o piu': il 200%). */
@OptIn(ExperimentalMetricApi::class)
class TextScaleMetric : TraceMetric() {

  override fun getMeasurements(captureInfo: Metric.CaptureInfo, traceSession: TraceProcessor.Session): List<Metric.Measurement> {
    val read = BenchSettings.shellRaw("settings get system font_scale").trim().toDoubleOrNull() ?: A11yBenchState.fontScale
    return listOf(Metric.Measurement("a11yFontScale", read))
  }
}
