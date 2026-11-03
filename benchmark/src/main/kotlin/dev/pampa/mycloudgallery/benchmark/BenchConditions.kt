package dev.pampa.mycloudgallery.benchmark

import android.app.UiAutomation
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Configurator
import org.junit.runner.Description
import org.junit.runner.Result
import org.junit.runner.notification.RunListener

/**
 * Le **condizioni** sotto cui girano le guardie esistenti (Fasi 3-9; docs/fase10-progetto.md §5.4: "le guardie esistenti non peggiorano con il widget sulla Home, con due finestre e
 * con il servizio di accessibilita' attivo"). Il servizio di prova ([BenchA11yService]) e' una condizione di sistema: resta acceso mentre le prove uccidono e rilanciano l'app,
 * quindi si puo' accendere **una volta per l'intera corsa** e far girare con lui tutti gli scenari di sempre (scorrimenti, pinch, viewer, avvio, memoria, ricordi, mappa...).
 *
 * Si accende con un argomento dello strumentatore e il listener di JUnit:
 * ```
 * -Pandroid.testInstrumentationRunnerArguments.benchWithService=true
 * -Pandroid.testInstrumentationRunnerArguments.listener=dev.pampa.mycloudgallery.benchmark.BenchServiceListener
 * ```
 * **Mai `compare.ps1 -Accept` su una corsa con il servizio acceso**: il riferimento e' quello senza (la guardia confronta contro di lui).
 * Le due finestre non sono una condizione di sistema (uccidere l'app le chiude): le prova `PanesBenchmark` (`pannelli-memoria`, `pannelli-scroll`).
 */
object BenchConditions {

  /** `benchWithService=true`: tutta la corsa con il servizio di accessibilita' di prova acceso. */
  val withService: Boolean get() = InstrumentationRegistry.getArguments().getString("benchWithService") == "true"

  private var flagsBefore: Int = 0
  private var enabledHere = false

  /** Accende il servizio senza che UiAutomation lo sospenda; vero se si e' collegato entro il tempo. */
  @Synchronized
  fun enableService(): Boolean {
    if (enabledHere && BenchA11yStats.connected) return true
    flagsBefore = Configurator.getInstance().uiAutomationFlags
    BenchA11yStats.reset()
    BenchA11yStats.targetPackage = BenchSession.targetPackage
    Configurator.getInstance().setUiAutomationFlags(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    val context: Context = InstrumentationRegistry.getInstrumentation().context
    BenchSettings.accessibilityService("${context.packageName}/${BenchA11yService::class.java.name}")
    enabledHere = true
    val end = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
    while (!BenchA11yStats.connected && System.currentTimeMillis() < end) Thread.sleep(POLL_MS)
    return BenchA11yStats.connected
  }

  /** Spegne il servizio e rimette impostazioni e flag com'erano. */
  @Synchronized
  fun disableService() {
    if (!enabledHere) return
    BenchSettings.restoreAll()
    Configurator.getInstance().setUiAutomationFlags(flagsBefore)
    runCatching { InstrumentationRegistry.getInstrumentation().getUiAutomation(flagsBefore) }
    enabledHere = false
  }

  private const val CONNECT_TIMEOUT_MS = 15_000L
  private const val POLL_MS = 200L
}

/** Il listener di JUnit che accende il servizio per tutta la corsa quando [BenchConditions.withService] e' vero, e lo rimette com'era alla fine. */
class BenchServiceListener : RunListener() {

  override fun testRunStarted(description: Description?) {
    if (!BenchConditions.withService) return
    BenchSettings.restoreStale()
    val connected = BenchConditions.enableService()
    // Nel log di logcat (tag MCG-Bench): la corsa con il servizio acceso e' valida solo se si e' collegato.
    android.util.Log.i("MCG-Bench", "benchWithService: servizio di prova collegato=$connected")
  }

  override fun testRunFinished(result: Result?) {
    if (!BenchConditions.withService) return
    android.util.Log.i(
      "MCG-Bench",
      "benchWithService: fine corsa, letture=${BenchA11yStats.walks.get()} nodi=${BenchA11yStats.nodes.get()} scollegamenti=${BenchA11yStats.disconnections.get()} collegato=${BenchA11yStats.connected}",
    )
    BenchConditions.disableService()
  }
}
