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
 * I pannelli del tablet e le due finestre (docs/fase10-progetto.md §5.4, riga P9 del §4.4; scenario `pannelli`), sulla libreria mista da 100 mila elementi, **in una finestra
 * larga** (1280 dp: [BenchWindow.ensureWide]). Cinque prove, cinque scenari di `soglie.json`:
 *  - `panesScroll` (`pannelli-scroll`): lo scorrimento della griglia con il **viewer affiancato**: `jankPercent` <= 1%, guardia +10% relativo con pavimento di 0,15 punti;
 *  - `panesViewerSwap` (`pannelli-foto`): il cambio di foto toccando una cella del pannello (`viewerSwapMs`): p90 <= 100 ms, +15%;
 *  - `panesZoneOpen` (`pannelli-zona`): l'apertura della griglia di una zona **nel pannello** accanto alla mappa (`zoneOpenMs`): p90 <= 150 ms, +15%;
 *  - `panesCrossing` (`pannelli-soglia`): il ridimensionamento della finestra attraverso le soglie di 1200 e 840 dp con il viewer affiancato (`wm size`): il fotogramma piu'
 *    lungo (`maxFrameCpuMs`) <= 33 ms (due fotogrammi a 60 Hz), +15%;
 *  - `panesWindowsMemory` (`pannelli-memoria`): la PSS dell'app con **due finestre** (`windowsPssKb`): nessun tetto, la prima corsa buona e' il riferimento, +15%.
 *
 * **Le due finestre sono la Libreria e la Libreria** (la seconda si apre come farebbe un'altra app: `am start` con `NEW_TASK | MULTIPLE_TASK | LAUNCH_ADJACENT`, il tetto e' in
 * `MainActivity`): il §5.4 dice "Libreria e un album", ma gli album non sono montati nella variante di misura (L287). Con l'album la memoria sara' un po' piu' alta.
 *
 * **Sull'emulatore** la finestra si emula (`wm size`/`wm density`) e si rimette a fine prova; sul telefono vero la classe si salta, sul tablet vero serve la larghezza (orizzontale
 * o DeX). L'apertura e il cambio foto li misura UiAutomator (comprendono il suo ritardo di rilevamento: P12-10). Niente si misura sull'emulatore: i numeri valgono solo su Tab S9.
 */
@RunWith(AndroidJUnit4::class)
class PanesBenchmark {

  @get:Rule
  val rule = MacrobenchmarkRule()

  @Before
  fun requireWideWindow() {
    BenchSettings.restoreStale()
    val readiness = BenchSession.requireLibrary()
    Assume.assumeTrue(readiness.message, readiness.ready)
    val why = BenchWindow.ensureWide(BenchSession.device)
    Assume.assumeTrue(why ?: "", why == null)
    PanesBenchState.reset()
    PanesBenchState.wide = true
  }

  @After
  fun cleanUp() {
    BenchWindow.restore(BenchSession.device)
  }

  /** Fling avanti e indietro nella griglia a sinistra con il viewer affiancato a destra. */
  @OptIn(ExperimentalMetricApi::class)
  @Test
  fun panesScroll() {
    rule.measureRepeated(
      packageName = BenchSession.targetPackage,
      metrics = listOf(FrameTimingMetric(), FrameJankMetric(), PanesStateMetric(), LibraryMixMetric()),
      compilationMode = BenchConfig.compilationMode,
      iterations = BenchConfig.iterations(SCROLL_ITERATIONS),
      setupBlock = {
        PanesBenchState.reset()
        PanesBenchState.wide = true
        dockedViewer()
      },
      measureBlock = {
        PanesUi.fling(device, towardsOlder = true, times = BenchConfig.repeats(FLINGS))
        PanesUi.fling(device, towardsOlder = false, times = BenchConfig.repeats(FLINGS))
      },
    )
    undock()
  }

  /** Il cambio di foto con un tocco sul pannello: dal tocco alla posizione nuova nel viewer. */
  @OptIn(ExperimentalMetricApi::class)
  @Test
  fun panesViewerSwap() {
    rule.measureRepeated(
      packageName = BenchSession.targetPackage,
      metrics = listOf(ViewerSwapMetric(), FrameTimingMetric(), PanesStateMetric(), LibraryMixMetric()),
      compilationMode = BenchConfig.compilationMode,
      iterations = BenchConfig.iterations(SWAP_ITERATIONS),
      setupBlock = {
        PanesBenchState.reset()
        PanesBenchState.wide = true
        dockedViewer()
      },
      measureBlock = {
        PanesBenchState.viewerSwapMs = PanesUi.swapPhoto(device)
        device.waitForIdle()
        Thread.sleep(SETTLE_MS)
      },
    )
    undock()
  }

  /** La griglia di una zona nel pannello accanto alla mappa: dal tocco su una bolla alla prima cella. */
  @OptIn(ExperimentalMetricApi::class)
  @Test
  fun panesZoneOpen() {
    rule.measureRepeated(
      packageName = BenchSession.targetPackage,
      metrics = listOf(ZoneOpenMetric(), FrameTimingMetric(), MapSeedMetric(), PanesStateMetric(), LibraryMixMetric()),
      compilationMode = BenchConfig.compilationMode,
      iterations = BenchConfig.iterations(OPEN_ITERATIONS),
      setupBlock = {
        Phase9BenchState.reset()
        PanesBenchState.reset()
        PanesBenchState.wide = true
        openMap()
      },
      measureBlock = {
        val opened = PanesUi.openZone(device)
        // Una zona che non si apre non ha misurato niente: si dice perche' (le bolle viste) invece di consegnare un "mancante".
        Phase9BenchState.zoneOpenMs = checkNotNull(opened) { "La griglia della zona non si apre dalle bolle (bolle: ${PanesUi.describeBubbles(device)})." }
        device.waitForIdle()
        Thread.sleep(SETTLE_MS)
        if (MapUi.zoneOpen(device)) BenchUi.back(device)
      },
    )
  }

  /** Il ridimensionamento della finestra attraverso le soglie, con il viewer affiancato: il fotogramma piu' lungo. */
  @OptIn(ExperimentalMetricApi::class)
  @Test
  fun panesCrossing() {
    Assume.assumeTrue(
      "Il ridimensionamento della finestra si prova con `wm size`: sull'emulatore, o sul tablet con benchAllowScreen=true (si rimette a fine prova); altrimenti a mano in DeX (F188).",
      BenchSettings.canChangeScreen,
    )
    rule.measureRepeated(
      packageName = BenchSession.targetPackage,
      metrics = listOf(FrameTimingMetric(), FrameJankMetric(), PanesStateMetric(), LibraryMixMetric()),
      compilationMode = BenchConfig.compilationMode,
      iterations = BenchConfig.iterations(CROSSING_ITERATIONS),
      setupBlock = {
        PanesBenchState.reset()
        PanesBenchState.wide = true
        dockedViewer()
      },
      measureBlock = {
        for ((w, h) in PanesBenchTarget.CROSSING) {
          BenchSettings.size(w, h)
          device.waitForIdle()
          Thread.sleep(RESIZE_SETTLE_MS)
        }
      },
    )
    BenchSettings.size(PanesBenchTarget.WIDE_WIDTH_PX, PanesBenchTarget.WIDE_HEIGHT_PX)
    undock()
  }

  /** La PSS dell'app con due finestre (Libreria e Libreria), dopo qualche scorrimento. */
  @OptIn(ExperimentalMetricApi::class)
  @Test
  fun panesWindowsMemory() {
    rule.measureRepeated(
      packageName = BenchSession.targetPackage,
      metrics = listOf(WindowsMemoryMetric(), PanesStateMetric(), LibraryMixMetric()),
      compilationMode = BenchConfig.compilationMode,
      iterations = BenchConfig.iterations(MEMORY_ITERATIONS),
      setupBlock = {
        BenchMemory.reset()
        PanesBenchState.reset()
        PanesBenchState.wide = true
        startLibrary()
      },
      measureBlock = {
        PanesBenchState.windows = openSecondWindow()
        BenchUi.fling(device, towardsOlder = true, times = BenchConfig.repeats(MEMORY_FLINGS))
        BenchUi.fling(device, towardsOlder = false, times = BenchConfig.repeats(MEMORY_FLINGS))
        device.waitForIdle()
        BenchMemory.sample()
      },
    )
  }

  /** Come `Assume.assumeTrue`, ma il motivo va anche nel log (`MCG-Bench`): il rapporto di Gradle non lo riporta e una prova saltata senza motivo non si capisce. */
  private fun assume(reason: String, condition: Boolean) {
    if (!condition) android.util.Log.w("MCG-Bench", "SALTATA: $reason")
    Assume.assumeTrue(reason, condition)
  }

  /** Avvio a freddo e Libreria visibile. */
  private fun MacrobenchmarkScope.startLibrary() {
    killProcess()
    startActivityAndWait()
    check(BenchUi.waitForLibrary(device, LIBRARY_TIMEOUT_MS)) { "La Libreria non si vede dopo l'avvio." }
    device.waitForIdle()
  }

  /** Avvio a freddo, il viewer aperto e affiancato alla Libreria; altrimenti la prova si salta con il motivo. */
  private fun MacrobenchmarkScope.dockedViewer() {
    startLibrary()
    assume("Il viewer non si affianca alla Libreria dal suo menu (vedi PanesUi e 'Affianca alla Libreria').", PanesUi.dock(device))
    PanesBenchState.docked = true
  }

  /** La mappa aperta nella finestra larga con le bolle visibili, come `MapBenchmark`; senza bolle la prova si salta. */
  private fun MacrobenchmarkScope.openMap() {
    startLibrary()
    assume("La scheda Raccolte non si apre (vedi MemoriesUi).", MemoriesUi.openCollections(device))
    // Le Raccolte a due pannelli hanno l'elenco a sinistra e il dettaglio (piu' grande) a destra: la riga "Mappa" si cerca nell'elenco.
    assume("Raccolte non ha la riga \"Mappa\" della sezione Luoghi nell'elenco di sinistra (vedi PanesUi.mapRow).", PanesUi.mapRow(device) != null)
    assume("La mappa non mostra nessuna bolla (MapLibre non parte o nessun punto).", MapUi.open(device) != null)
    MapUi.settle(device)
  }

  /**
   * Apre la seconda finestra come farebbe un'altra app (`NEW_TASK | MULTIPLE_TASK | LAUNCH_ADJACENT`: le due finestre una accanto all'altra) e restituisce quante finestre
   * dell'app ci sono (le attivita' `MainActivity` nei task). Una finestra sola e' una prova che non ha misurato niente: `compare.ps1` la dice NON VALIDA.
   */
  private fun MacrobenchmarkScope.openSecondWindow(): Int {
    BenchSettings.shellRaw("am start -n ${BenchSession.targetPackage}/${BenchmarkTarget.MAIN_ACTIVITY} -f $SECOND_WINDOW_FLAGS")
    device.waitForIdle()
    Thread.sleep(SECOND_WINDOW_MS)
    return windowCount()
  }

  /** Le `MainActivity` vive nei task, contate per record distinto (lo stesso record compare piu' volte nella stessa uscita di `dumpsys`). */
  private fun windowCount(): Int {
    val record = Regex("ActivityRecord[{]([0-9a-f]+) [^}]*${Regex.escape(BenchSession.targetPackage)}/${Regex.escape(BenchmarkTarget.MAIN_ACTIVITY)}[^}]*[}]")
    return record.findAll(BenchSettings.shellRaw("dumpsys activity activities")).filter { !it.value.endsWith(" f}") }.map { it.groupValues[1] }.toSet().size.coerceAtMost(MAX_WINDOWS)
  }

  /** Rimette "Schermo intero" (la scelta si ricorda per profilo), prima che lo schermo torni com'era. */
  private fun undock() {
    runCatching {
      BenchSession.startApp()
      PanesUi.undock(BenchSession.device)
    }
  }

  private companion object {
    const val SCROLL_ITERATIONS = 8
    const val SWAP_ITERATIONS = 12
    const val OPEN_ITERATIONS = 12
    const val CROSSING_ITERATIONS = 5
    const val MEMORY_ITERATIONS = 3
    const val FLINGS = 6
    const val MEMORY_FLINGS = 3
    const val LIBRARY_TIMEOUT_MS = 20_000L
    const val SETTLE_MS = 500L
    const val RESIZE_SETTLE_MS = 700L
    const val SECOND_WINDOW_MS = 2_500L
    const val MAX_WINDOWS = 2

    /** `FLAG_ACTIVITY_NEW_TASK` (0x10000000) | `FLAG_ACTIVITY_MULTIPLE_TASK` (0x08000000) | `FLAG_ACTIVITY_LAUNCH_ADJACENT` (0x00001000). */
    const val SECOND_WINDOW_FLAGS = "0x18001000"
  }
}
