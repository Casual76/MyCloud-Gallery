package dev.pampa.mycloudgallery.benchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La mappa (docs/fase9-progetto.md §5.4, riga P12 del §4.4; scenario `mappa`), sulla libreria mista da 100 mila elementi **con il 60% delle righe con una
 * posizione** ([MapBenchTarget]). **La carta e' solo quella di base offline**: la variante di misura spegne la mappa dettagliata (`mapOnlineAllowed = false`),
 * quindi nessuna prova genera traffico verso OpenFreeMap e nessuna accende lo stile online; sul telefono le impostazioni della mappa non si toccano.
 *
 * Otto prove, otto scenari di `soglie.json`:
 *  - `mapOpenCold` (`mappa-apertura-freddo`) e `mapOpenWarm` (`mappa-apertura-caldo`): dal tocco su "Mappa" nella sezione Luoghi alla prima bolla visibile
 *    (`mapOpenMs`), con MapLibre e l'indice dei punti ancora da avviare o gia' pronti. Soglie: p90 <= 800 ms a freddo, <= 300 ms a caldo, +15%;
 *  - `mapGestures` (`mappa-gesti`): spostamenti e pizzichi scriptati per 20 s (4 s nello smoke): fotogrammi dell'interfaccia in ritardo (`jankPercent`, come la Libreria);
 *  - `mapDraw` (`mappa-disegno`): il tempo di disegno di MapLibre per fotogramma durante gli stessi gesti (`mapDrawP95Ms`, dal contatore `MCG:map.drawUs`).
 *    Soglia provvisoria da calibrare sul primo riferimento (`null` in `soglie.json`), +15%;
 *  - `mapClusters` (`mappa-bolle`): l'aggiornamento delle bolle a camera ferma (sezione di traccia `MCG:map.clusters`, `BubbleEngine.compute`). Soglia: p95 <= 8 ms, +15%;
 *  - `mapZoneOpen` (`mappa-zona`): dal tocco su una bolla alla prima cella della griglia di una zona (`zoneOpenMs`). Soglia: p90 <= 150 ms, +15%;
 *  - `mapZoneViewer` (`mappa-viewer`): dal tocco su una cella della griglia al viewer (`MCG:viewerOpen`, la guardia del viewer): p90 < 100 ms, +15%;
 *  - `mapMemory` (`mappa-memoria`): la PSS a mappa aperta dopo i gesti (nessun tetto: la prima corsa buona e' il riferimento), +15%;
 *  - `mapLoadedScroll` (`mappa-scroll-carico`): la guardia di scorrimento della Libreria (livello Tutte, stile quadrato) **dopo aver aperto la mappa** (MapLibre, l'indice
 *    dei punti e le bolle restano nel processo): le guardie esistenti non peggiorano. La mappa e' una pagina a tutto schermo e non sta "in un'altra scheda" mentre
 *    si scorre la Libreria: e' la cosa piu' vicina che l'app permette (vedi docs/bench/README.md, Fase 9).
 *
 * Come le altre: se il dataset non ha le posizioni, o la mappa non si apre (MapLibre non parte, nessuna bolla), la prova si **salta** con il motivo (`Assume`), mai un
 * falso verde. Niente si misura sull'emulatore: i numeri valgono solo su S25 e Tab S9 (`compare.ps1 -Smoke` sugli emulatori).
 */
@RunWith(AndroidJUnit4::class)
class MapBenchmark {

  @get:Rule
  val rule = MacrobenchmarkRule()

  @Before
  fun requireMap() {
    val readiness = BenchSession.requireLibrary()
    Assume.assumeTrue(MapBenchTarget.missingReason(readiness.dataset), MemoriesBenchTarget.positionsSeeded(readiness.dataset))
    Phase9BenchState.reset()
  }

  /** A freddo: processo nuovo, MapLibre e l'indice dei punti da avviare; dal tocco su "Mappa" alla prima bolla. */
  @OptIn(ExperimentalMetricApi::class)
  @Test
  fun mapOpenCold() {
    rule.measureRepeated(
      packageName = BenchSession.targetPackage,
      metrics = listOf(MapOpenMetric(), FrameTimingMetric(), MapSeedMetric(), LibraryMixMetric()),
      compilationMode = BenchConfig.compilationMode,
      iterations = BenchConfig.iterations(OPEN_ITERATIONS),
      setupBlock = {
        Phase9BenchState.reset()
        openLuoghi()
      },
      measureBlock = {
        Phase9BenchState.mapOpenMs = MapUi.open(device)
        MapUi.settle(device)
        BenchUi.back(device)
      },
    )
  }

  /** A caldo: la mappa e' gia' stata aperta in questo processo (MapLibre e indice pronti); si torna a Raccolte e si riapre. */
  @OptIn(ExperimentalMetricApi::class)
  @Test
  fun mapOpenWarm() {
    rule.measureRepeated(
      packageName = BenchSession.targetPackage,
      metrics = listOf(MapOpenMetric(), FrameTimingMetric(), MapSeedMetric(), LibraryMixMetric()),
      compilationMode = BenchConfig.compilationMode,
      iterations = BenchConfig.iterations(OPEN_ITERATIONS),
      setupBlock = {
        Phase9BenchState.reset()
        openLuoghi()
        // La prima apertura non si misura: scalda MapLibre, l'indice e la cache delle miniature.
        Assume.assumeTrue("La mappa non mostra nessuna bolla (MapLibre non parte o nessun punto).", MapUi.open(device) != null)
        MapUi.settle(device)
        BenchUi.back(device)
        device.waitForIdle()
      },
      measureBlock = {
        Phase9BenchState.mapOpenMs = MapUi.open(device)
        MapUi.settle(device)
        BenchUi.back(device)
      },
    )
  }

  /** Spostamento e pizzico scriptati per 20 s: fotogrammi dell'interfaccia in ritardo (`jankPercent`). */
  @OptIn(ExperimentalMetricApi::class)
  @Test
  fun mapGestures() = gestures(listOf(FrameTimingMetric(), FrameJankMetric(), MapSeedMetric(), LibraryMixMetric()))

  /** Il tempo di disegno di MapLibre durante gli stessi gesti: il contatore `MCG:map.drawUs`. */
  @OptIn(ExperimentalMetricApi::class)
  @Test
  fun mapDraw() = gestures(listOf(MapDrawMetric(), MapSeedMetric(), LibraryMixMetric()))

  /** L'aggiornamento delle bolle a camera ferma: uno spostamento, la pausa, e la sezione `MCG:map.clusters` all'arresto. */
  @OptIn(ExperimentalMetricApi::class)
  @Test
  fun mapClusters() {
    rule.measureRepeated(
      packageName = BenchSession.targetPackage,
      metrics = listOf(MapClustersMetric(), MapSeedMetric(), LibraryMixMetric()),
      compilationMode = BenchConfig.compilationMode,
      iterations = BenchConfig.iterations(CLUSTER_ITERATIONS),
      setupBlock = {
        Phase9BenchState.reset()
        openMap()
      },
      measureBlock = {
        // Nello smoke `repeats` varrebbe 1: una sola sezione. Qui 3 spostamenti bastano a vedere che le sezioni si contano.
        repeat(if (BenchConfig.smoke) SMOKE_CLUSTER_PANS else CLUSTER_PANS) { step ->
          MapUi.pan(device, dx = if (step % 2 == 0) 1 else -1, dy = 0)
          // Camera ferma: la mappa si assesta e le bolle si rifanno (il controllore le ricalcola all'arresto).
          MapUi.settle(device)
        }
      },
    )
  }

  /** Dal tocco su una bolla alla prima cella della griglia di una zona. */
  @OptIn(ExperimentalMetricApi::class)
  @Test
  fun mapZoneOpen() {
    rule.measureRepeated(
      packageName = BenchSession.targetPackage,
      metrics = listOf(ZoneOpenMetric(), FrameTimingMetric(), MapSeedMetric(), LibraryMixMetric()),
      compilationMode = BenchConfig.compilationMode,
      iterations = BenchConfig.iterations(OPEN_ITERATIONS),
      setupBlock = {
        Phase9BenchState.reset()
        openMap()
      },
      measureBlock = {
        Phase9BenchState.zoneOpenMs = MapUi.openZone(device)
        device.waitForIdle()
        Thread.sleep(SETTLE_MS)
        if (MapUi.zoneOpen(device)) BenchUi.back(device)
      },
    )
  }

  /** Dal tocco su una cella della griglia di una zona al viewer: la sezione di traccia `MCG:viewerOpen` (la guardia del viewer, p90 < 100 ms). */
  @OptIn(ExperimentalMetricApi::class)
  @Test
  fun mapZoneViewer() {
    rule.measureRepeated(
      packageName = BenchSession.targetPackage,
      metrics = listOf(ViewerOpenMetric(), FrameTimingMetric(), MapSeedMetric(), LibraryMixMetric()),
      compilationMode = BenchConfig.compilationMode,
      iterations = BenchConfig.iterations(OPEN_ITERATIONS),
      setupBlock = {
        Phase9BenchState.reset()
        openMap()
        Assume.assumeTrue("La griglia di una zona non si apre dalla mappa (vedi MapUi).", MapUi.openZone(device) != null)
        device.waitForIdle()
        Thread.sleep(SETTLE_MS)
      },
      measureBlock = {
        check(MapUi.tapCenterZoneCell(device)) { "Nessuna cella da toccare nella griglia della zona." }
        check(BenchUi.awaitViewer(device)) { "Il viewer non si apre dalla griglia della zona." }
        device.waitForIdle()
        Thread.sleep(VIEWER_SETTLE_MS)
        BenchUi.closeViewer(device)
      },
    )
  }

  /** La PSS a mappa aperta, dopo qualche gesto: `mapPssKb` (e gli heap, informativi). */
  @OptIn(ExperimentalMetricApi::class)
  @Test
  fun mapMemory() {
    rule.measureRepeated(
      packageName = BenchSession.targetPackage,
      metrics = listOf(MapMemoryMetric(), MapSeedMetric(), LibraryMixMetric()),
      compilationMode = BenchConfig.compilationMode,
      iterations = BenchConfig.iterations(MEMORY_ITERATIONS),
      setupBlock = {
        BenchMemory.reset()
        openMap()
      },
      measureBlock = {
        repeat(BenchConfig.repeats(MEMORY_GESTURES)) { step ->
          MapUi.pan(device, dx = if (step % 2 == 0) 1 else -1, dy = if (step % 3 == 0) 1 else 0)
          MapUi.settle(device)
        }
        MapUi.pinch(device, close = false)
        MapUi.settle(device)
        BenchMemory.sample()
      },
    )
  }

  /** La guardia di scorrimento della Libreria dopo aver aperto la mappa (MapLibre, indice e bolle restano nel processo). */
  @OptIn(ExperimentalMetricApi::class)
  @Test
  fun mapLoadedScroll() {
    rule.measureRepeated(
      packageName = BenchSession.targetPackage,
      metrics = listOf(FrameTimingMetric(), FrameJankMetric(), MapWasOpenedMetric(), LibraryMixMetric()),
      compilationMode = BenchConfig.compilationMode,
      iterations = BenchConfig.iterations(SCROLL_ITERATIONS),
      setupBlock = {
        Phase9BenchState.reset()
        openMap()
        Phase9BenchState.mapOpened = true
        // Fuori dalla mappa e dalle Raccolte, di nuovo nella Libreria: Indietro dalla mappa, poi la scheda della Libreria.
        BenchUi.back(device)
        device.waitForIdle()
        PagesUi.tab(device, "Libreria")
        check(BenchUi.waitForLibrary(device, LIBRARY_TIMEOUT_MS)) { "La Libreria non si vede dopo la mappa." }
        // Come la prova `scroll_all_square`: livello Tutte, stile quadrato, in fondo (i piu' recenti).
        check(BenchUi.selectStyle(device, BenchStyle.SQUARE)) { "Lo stile '${BenchStyle.SQUARE.label}' non si sceglie dal menu della vista." }
        check(BenchUi.selectLevel(device, BenchLevel.ALL)) { "La griglia non c'e' per cambiare livello." }
        device.waitForIdle()
      },
      measureBlock = {
        BenchUi.fling(device, towardsOlder = true, times = BenchConfig.repeats(FLINGS))
        BenchUi.fling(device, towardsOlder = false, times = BenchConfig.repeats(FLINGS))
      },
    )
  }

  /** Gli spostamenti e i pizzichi scriptati: [GESTURE_MS] di gesti (4 s nello smoke) con le [metrics] date. */
  @OptIn(ExperimentalMetricApi::class)
  private fun gestures(metrics: List<androidx.benchmark.macro.Metric>) {
    rule.measureRepeated(
      packageName = BenchSession.targetPackage,
      metrics = metrics,
      compilationMode = BenchConfig.compilationMode,
      iterations = BenchConfig.iterations(GESTURE_ITERATIONS),
      setupBlock = {
        Phase9BenchState.reset()
        openMap()
      },
      measureBlock = {
        val duration = if (BenchConfig.smoke) SMOKE_GESTURE_MS else GESTURE_MS
        val started = System.currentTimeMillis()
        var step = 0
        while (System.currentTimeMillis() - started < duration) {
          when (step % GESTURE_CYCLE) {
            0 -> MapUi.pan(device, dx = 1, dy = 0)
            1 -> MapUi.pan(device, dx = 0, dy = 1)
            2 -> MapUi.pinch(device, close = false)
            3 -> MapUi.pan(device, dx = -1, dy = 0)
            4 -> MapUi.pan(device, dx = 0, dy = -1)
            else -> MapUi.pinch(device, close = true)
          }
          step++
        }
      },
    )
  }

  /** Avvio a freddo, scheda Raccolte e la riga "Mappa" della sezione Luoghi; altrimenti la prova si salta. */
  private fun MacrobenchmarkScope.openLuoghi() {
    killProcess()
    startActivityAndWait()
    check(BenchUi.waitForLibrary(device, LIBRARY_TIMEOUT_MS)) { "La Libreria non si vede dopo l'avvio." }
    device.waitForIdle()
    Assume.assumeTrue("La scheda Raccolte non si apre (vedi MemoriesUi).", MemoriesUi.openCollections(device))
    Assume.assumeTrue("Raccolte non ha la riga \"Mappa\" della sezione Luoghi (vedi MapUi).", MapUi.mapRow(device) != null)
    device.waitForIdle()
  }

  /** Come [openLuoghi] e poi la mappa aperta con le bolle visibili e assestata (non misurata); senza bolle la prova si salta. */
  private fun MacrobenchmarkScope.openMap() {
    openLuoghi()
    Assume.assumeTrue("La mappa non mostra nessuna bolla (MapLibre non parte o nessun punto).", MapUi.open(device) != null)
    MapUi.settle(device)
  }

  private companion object {
    const val OPEN_ITERATIONS = 12
    const val GESTURE_ITERATIONS = 5
    const val CLUSTER_ITERATIONS = 8
    const val MEMORY_ITERATIONS = 3
    const val SCROLL_ITERATIONS = 8
    const val CLUSTER_PANS = 12
    const val SMOKE_CLUSTER_PANS = 3
    const val MEMORY_GESTURES = 6
    const val FLINGS = 6
    const val GESTURE_MS = 20_000L
    const val SMOKE_GESTURE_MS = 4_000L
    const val GESTURE_CYCLE = 6
    const val LIBRARY_TIMEOUT_MS = 20_000L
    const val SETTLE_MS = 500L
    const val VIEWER_SETTLE_MS = 800L
  }
}
